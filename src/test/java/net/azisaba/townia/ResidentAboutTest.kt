package net.azisaba.townia

import net.azisaba.townia.command.ResidentCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class ResidentAboutTest {

    @Test
    fun `resident set about persists across reload and can be cleared`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val uuid = UUID.randomUUID()
            val player = env.mockPlayer(uuid, "Alice")
            env.residentManager.getOrCreate(player)
            val cmd = ResidentCommand(env.plugin)

            cmd.onCommand(player, env.dummyCommand("resident"), "res", arrayOf("set", "about", "よろしく", "お願いします"))
            assertTrue(env.sentMessages.any { it.recipient === player && it.key == "resident.about-set" })

            env.reloadManagers()
            assertEquals("よろしく お願いします", env.residentManager.getResident(uuid).orElseThrow().about)

            env.sentMessages.clear()
            cmd.onCommand(player, env.dummyCommand("resident"), "res", arrayOf("set", "about", "x".repeat(101)))
            assertTrue(env.sentMessages.any { it.recipient === player && it.key == "resident.about-too-long" })
            assertEquals("よろしく お願いします", env.residentManager.getResident(uuid).orElseThrow().about)

            cmd.onCommand(player, env.dummyCommand("resident"), "res", arrayOf("set", "about", "none"))
            env.reloadManagers()
            assertNull(env.residentManager.getResident(uuid).orElseThrow().about)
        }
    }
}
