package net.azisaba.townia

import net.azisaba.townia.migration.BulkRead
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * A repair started on a live server read Towny's files so fast that the world save waited for the
 * disk for over five seconds, and a second repair started meanwhile doubled the load.
 */
class BulkReadTest {

    @Test
    fun `only one file job runs at a time`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val sender = mock<ConsoleCommandSender>()
            var secondRan = false
            var secondStarted = true
            val first = BulkRead.start(env.plugin, sender, "repair", Runnable {
                assertEquals("repair", BulkRead.current)
                secondStarted = BulkRead.start(env.plugin, sender, "repair", Runnable { secondRan = true })
            })
            assertTrue(first)
            assertFalse(secondStarted, "a job started while one is running is refused")
            assertFalse(secondRan)
            assertTrue(env.sentMessages.any { it.key == "admin.migration-busy" })
            assertNull(BulkRead.current, "the slot is free again once the job has ended")

            var thirdRan = false
            assertTrue(BulkRead.start(env.plugin, sender, "audit", Runnable { thirdRan = true }))
            assertTrue(thirdRan)

            // A job that fails must not block every later one.
            runCatching { BulkRead.start(env.plugin, sender, "migrate", Runnable { error("boom") }) }
            assertNull(BulkRead.current)
        }
    }

    @Test
    fun `reading stands aside while the server thread is behind`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            var serverTick: Runnable? = null
            val heartbeat = mock<BukkitTask>()
            whenever(env.scheduler.runTaskTimer(any<Plugin>(), any<Runnable>(), any(), any())).doAnswer { inv ->
                serverTick = inv.getArgument(1)
                heartbeat
            }
            var waitedMs = 0L
            var quickMs = 0L
            BulkRead.start(env.plugin, mock<ConsoleCommandSender>(), "repair", Runnable {
                // The server ticks normally: a batch costs no more than its short rest.
                val ticking = java.util.concurrent.atomic.AtomicBoolean(true)
                val ticker = thread { while (ticking.get()) { serverTick!!.run(); Thread.sleep(20) } }
                var started = System.nanoTime()
                repeat(100) { BulkRead.fileRead() }
                quickMs = (System.nanoTime() - started) / 1_000_000
                ticking.set(false)
                ticker.join()

                // The server thread hangs (no tick) and comes back after about 0.6 s.
                Thread.sleep(250)
                val server = thread {
                    Thread.sleep(600)
                    serverTick!!.run()
                }
                started = System.nanoTime()
                repeat(50) { BulkRead.fileRead() }
                waitedMs = (System.nanoTime() - started) / 1_000_000
                server.join()
            })
            assertTrue(quickMs < 1_000, "a healthy server is not held up: $quickMs ms")
            assertTrue(waitedMs in 400..5_000, "the job waited for the server to tick again: $waitedMs ms")
        }
    }
}
