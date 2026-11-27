package net.azisaba.townia.database

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Runs database writes on one background thread, in the order they were issued.
 *
 * A write method asks for [recordingConnection] instead of a real connection. The JDBC calls it
 * makes (prepareStatement, setString, executeUpdate, ...) are only recorded, together with the
 * values passed at that moment, and are replayed against a real connection by the writer thread.
 * The caller never waits for the disk, and because values are captured when the call is made, a
 * later change to the same object cannot leak into an earlier save.
 *
 * Anything that reads must call [drain] first so it sees every write issued before it.
 */
internal class WriteBehind(private val logger: Logger, private val connect: () -> Connection) {

    private class Op(val target: Int, val method: Method, val args: Array<Any?>?)

    /** [selfManaged] scripts set auto-commit themselves and are replayed outside the shared batch. */
    private class Script(val ops: List<Op>, val statements: Map<Int, String?>, val selfManaged: Boolean, val origin: String)

    private val queue = LinkedBlockingQueue<Script>()

    /** How many writes were handed in, and how many the writer is done with (written or given up on). */
    private val submitted = AtomicLong()

    @Volatile
    private var finished = 0L
    private val lock = Object()

    @Volatile
    private var running = true

    private val thread = Thread(::runLoop, "Townia-DB-Writer").apply {
        isDaemon = true
        start()
    }

    /** A connection whose writes are queued when it is closed. It cannot run queries. */
    fun recordingConnection(): Connection {
        if (!running || Thread.currentThread() === thread) return connect()
        val ops = ArrayList<Op>()
        val statements = HashMap<Int, String?>()
        var nextId = 1
        var autoCommit = true
        var selfManaged = false
        var closed = false
        val origin = callerName()

        fun statementProxy(id: Int, type: Class<*>): Any =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "executeQuery", "getResultSet", "getGeneratedKeys" ->
                        throw IllegalStateException("A queued database write cannot read ($origin)")
                    "isClosed" -> false
                    "toString" -> "RecordingStatement#$id"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> {
                        ops.add(Op(id, method, args?.clone()))
                        defaultValue(method.returnType)
                    }
                }
            }

        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { proxy, method, args ->
            when (method.name) {
                "prepareStatement" -> {
                    val id = nextId++
                    statements[id] = args!![0] as String
                    statementProxy(id, PreparedStatement::class.java)
                }
                "createStatement" -> {
                    val id = nextId++
                    statements[id] = null
                    statementProxy(id, Statement::class.java)
                }
                "getAutoCommit" -> autoCommit
                "setAutoCommit" -> {
                    autoCommit = args!![0] as Boolean
                    selfManaged = true
                    ops.add(Op(0, method, args.clone()))
                    null
                }
                "commit", "rollback" -> {
                    selfManaged = true
                    ops.add(Op(0, method, args?.clone()))
                    null
                }
                "close" -> {
                    if (!closed) {
                        closed = true
                        if (ops.isNotEmpty()) submit(Script(ops, statements, selfManaged, origin))
                    }
                    null
                }
                "isClosed" -> closed
                "toString" -> "RecordingConnection($origin)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> throw UnsupportedOperationException("Connection.${method.name} is not available on a queued write ($origin)")
            }
        } as Connection
    }

    private fun submit(script: Script) {
        submitted.incrementAndGet()
        queue.add(script)
    }

    /**
     * Blocks until every write issued before this call has been committed. Writes are done in the
     * order they came in, so it does not wait for those another thread issues meanwhile: a reader
     * on the server thread must not be held up for as long as an import keeps writing.
     */
    fun drain() {
        if (Thread.currentThread() === thread) return
        val target = submitted.get()
        if (finished >= target) return
        synchronized(lock) {
            while (finished < target && thread.isAlive) lock.wait(50L)
        }
    }

    /** Finishes the queued writes and stops the writer thread. */
    fun close() {
        drain()
        running = false
        thread.interrupt()
        runCatching { thread.join(5000L) }
    }

    private fun runLoop() {
        while (running || queue.isNotEmpty()) {
            val first = try {
                queue.poll(500L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                continue
            } ?: continue
            // Counts every write taken off the queue, the first included: a write that could not
            // even get a connection is given up on, and whoever waits in drain() must be let go.
            // Left uncounted, one failed connection made every later read wait forever.
            var done = 1
            try {
                connect().use { connection ->
                    connection.autoCommit = false
                    var script: Script? = first
                    while (script != null) {
                        if (script.selfManaged) {
                            connection.commit()
                            connection.autoCommit = true
                            replay(connection, script)
                            connection.autoCommit = false
                        } else {
                            replay(connection, script)
                        }
                        script = if (done < MAX_BATCH) queue.poll() else null
                        if (script != null) done++
                    }
                    connection.commit()
                    connection.autoCommit = true
                }
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Townia could not write to the database; $done write(s) were lost (first: ${first.origin})", e)
            } finally {
                finished += done
                synchronized(lock) { lock.notifyAll() }
            }
        }
    }

    private fun replay(connection: Connection, script: Script) {
        val live = HashMap<Int, Statement>()
        try {
            for (op in script.ops) {
                val target: Any = if (op.target == 0) {
                    connection
                } else {
                    live.getOrPut(op.target) {
                        val sql = script.statements[op.target]
                        if (sql == null) connection.createStatement() else connection.prepareStatement(sql)
                    }
                }
                try {
                    if (op.args == null) op.method.invoke(target) else op.method.invoke(target, *op.args)
                } catch (e: InvocationTargetException) {
                    // One failed statement must not drop the rest of the queue.
                    logger.log(Level.SEVERE, "Townia database write failed (${script.origin}): ${script.statements[op.target] ?: op.method.name}", e.targetException)
                }
            }
        } finally {
            live.values.forEach { runCatching { it.close() } }
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        IntArray::class.java -> IntArray(0)
        LongArray::class.java -> LongArray(0)
        else -> null
    }

    /**
     * Which DatabaseManager method asked for the connection, for error messages. This runs for every
     * queued write, so it only looks at the few frames above it instead of building the whole stack.
     */
    private fun callerName(): String =
        STACK.walk { frames ->
            frames.limit(12)
                .filter { it.className.endsWith("DatabaseManager") && it.methodName != "queued" }
                .findFirst().map { it.methodName }.orElse("unknown")
        }

    private companion object {
        val STACK: StackWalker = StackWalker.getInstance()
        const val MAX_BATCH = 500
    }
}
