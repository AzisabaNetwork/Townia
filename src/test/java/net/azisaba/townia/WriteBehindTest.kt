package net.azisaba.townia

import net.azisaba.townia.database.WriteBehind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/** The background writer must never leave the server thread waiting for good. */
class WriteBehindTest {

    private fun quietLogger() = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        level = Level.OFF
    }

    private fun database(dir: Path): String {
        val url = "jdbc:sqlite:" + dir.resolve("writes.db")
        DriverManager.getConnection(url).use { it.createStatement().use { s -> s.execute("CREATE TABLE t (v INTEGER)") } }
        return url
    }

    private fun insert(writer: WriteBehind, value: Int) {
        writer.recordingConnection().use { connection ->
            connection.prepareStatement("INSERT INTO t (v) VALUES (?)").use { ps ->
                ps.setInt(1, value)
                ps.executeUpdate()
            }
        }
    }

    private fun stored(url: String): List<Int> = DriverManager.getConnection(url).use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery("SELECT v FROM t ORDER BY v").use { rs ->
                val values = ArrayList<Int>()
                while (rs.next()) values.add(rs.getInt(1))
                values
            }
        }
    }

    @Test
    @Timeout(20)
    fun `a write that gets no connection does not leave readers waiting forever`(@TempDir dir: Path) {
        val url = database(dir)
        val away = AtomicBoolean(true)
        val writer = WriteBehind(quietLogger()) {
            if (away.get()) throw SQLException("the database is away") else DriverManager.getConnection(url)
        }
        try {
            insert(writer, 1)
            // Used to hang here: the lost write was never counted as dealt with.
            writer.drain()

            away.set(false)
            insert(writer, 2)
            writer.drain()
            assertEquals(listOf(2), stored(url), "later writes still arrive")
        } finally {
            writer.close()
        }
    }

    @Test
    @Timeout(20)
    fun `a reader waits for the writes before it, not for the ones that follow`(@TempDir dir: Path) {
        val url = database(dir)
        val writer = WriteBehind(quietLogger()) { DriverManager.getConnection(url) }
        val stop = AtomicBoolean(false)
        // An import: another thread that keeps writing until it is told to stop. Paced at about a
        // write per millisecond: writing as fast as the CPU allows piled up tens of thousands of
        // writes in 0.2 s, and the test then measured the disk (7 to 16 s) rather than drain().
        val importer = Thread {
            var next = 100
            while (!stop.get()) {
                insert(writer, next++)
                Thread.sleep(1)
            }
        }
        try {
            insert(writer, 1)
            importer.start()
            Thread.sleep(200)
            // While the importer never stops, "wait until nothing is pending" would never return.
            writer.drain()
            assertTrue(importer.isAlive, "the reader came back while the import was still writing")
            assertTrue(1 in stored(url), "and what was written before it is there")
        } finally {
            stop.set(true)
            importer.join(5000)
            writer.close()
        }
    }
}
