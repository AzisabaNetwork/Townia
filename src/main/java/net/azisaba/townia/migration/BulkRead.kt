package net.azisaba.townia.migration

import net.azisaba.townia.Townia
import org.bukkit.command.CommandSender
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the jobs that read Towny's files (migrate, repair, audit) so that the server keeps the disk.
 *
 * Such a job runs off the server thread, but it still shares the disk with it: reading a large
 * townblocks folder at full speed kept the disk so busy on a live server that the world save,
 * which the server thread does itself, waited for more than five seconds. A second job started
 * meanwhile read everything once more.
 *
 * So: one job at a time, and the job
 *  - rests after every batch of files for as long as that batch took, and
 *  - stands aside altogether while the server thread is behind.
 * It takes longer and the server stays responsive.
 */
object BulkRead {
    private const val BATCH = 50
    private const val MAX_REST_MS = 200L
    private const val SERVER_BEHIND_NANOS = 200_000_000L
    private const val MAX_WAIT_MS = 10_000L
    private const val REPORT_EVERY_NANOS = 15_000_000_000L

    private val running = AtomicReference<String?>(null)

    @Volatile
    private var lastServerTick = 0L

    @Volatile
    private var heartbeat: BukkitTask? = null

    @Volatile
    private var report: ((Int) -> Unit)? = null

    // Only the job's own thread touches these.
    private var files = 0
    private var batchStarted = 0L
    private var lastReport = 0L

    /** The job that is running now, or null. */
    val current: String?
        get() = running.get()

    /**
     * Starts [job] off the server thread. If another one is still running it is not started,
     * the sender is told, and false comes back.
     */
    fun start(plugin: Townia, sender: CommandSender, name: String, job: Runnable): Boolean {
        val busyWith = running.compareAndExchange(null, name)
        if (busyWith != null) {
            plugin.messageManager.sendMessage(sender, "admin.migration-busy", "job", busyWith)
            return false
        }
        files = 0
        val startedAt = System.currentTimeMillis()
        plugin.logger.info("Towny file job \"$name\" started.")
        batchStarted = System.nanoTime()
        lastReport = batchStarted
        lastServerTick = batchStarted
        report = { count ->
            plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "$name: files read", "count", count.toString())
        }
        // Ticks with the server; when it stops being updated the server thread is stuck.
        heartbeat = runCatching {
            plugin.server.scheduler.runTaskTimer(plugin, Runnable { lastServerTick = System.nanoTime() }, 1L, 1L)
        }.getOrNull()
        try {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                try {
                    job.run()
                } finally {
                    plugin.logger.info("Towny file job \"$name\" ended: $files files read in ${(System.currentTimeMillis() - startedAt) / 1000} s.")
                    finish()
                }
            })
        } catch (e: RuntimeException) {
            finish()
            throw e
        }
        return true
    }

    private fun finish() {
        runCatching { heartbeat?.cancel() }
        heartbeat = null
        report = null
        running.set(null)
    }

    /** Call for every file read. Cheap except once per batch, where the job gives the disk back. */
    fun fileRead() {
        if (++files % BATCH != 0) return
        try {
            val tookMs = (System.nanoTime() - batchStarted) / 1_000_000
            Thread.sleep(tookMs.coerceIn(1L, MAX_REST_MS))
            val beat = heartbeat
            var waited = 0L
            while (beat != null && !beat.isCancelled && waited < MAX_WAIT_MS &&
                System.nanoTime() - lastServerTick > SERVER_BEHIND_NANOS
            ) {
                Thread.sleep(50)
                waited += 50
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val now = System.nanoTime()
        if (now - lastReport > REPORT_EVERY_NANOS) {
            lastReport = now
            runCatching { report?.invoke(files) }
        }
        batchStarted = System.nanoTime()
    }
}
