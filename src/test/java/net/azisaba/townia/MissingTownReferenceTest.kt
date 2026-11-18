package net.azisaba.townia

import net.azisaba.townia.command.TowniaAdminCommand
import net.azisaba.townia.data.TownRank
import org.bukkit.command.ConsoleCommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.UUID

/** Residents whose town_uuid points at a town that does not exist (e.g. after a partial Towny migration). */
class MissingTownReferenceTest {

    @Test
    fun `startup cleanup clears memberships and jail entries of missing towns only`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val memberUuid = UUID.randomUUID()
            val strayUuid = UUID.randomUUID()
            env.residentManager.getOrCreate(env.mockPlayer(mayorUuid, "Mayor"))
            env.residentManager.getOrCreate(env.mockPlayer(memberUuid, "Member"))
            val stray = env.residentManager.getOrCreate(env.mockPlayer(strayUuid, "fukoato"))
            val town = env.townManager.createTown("berry_town", mayorUuid)
            env.residentManager.setTown(memberUuid, town.id, TownRank.ASSISTANT)

            val missingTown = UUID.randomUUID()
            env.residentManager.setTown(strayUuid, missingTown, TownRank.ASSISTANT)
            stray.jailedTownUuid = missingTown
            stray.jailBail = 100.0
            env.residentManager.saveResident(stray)

            val (members, jailed) = env.databaseManager.clearMissingTownReferences()
            assertEquals(1, members)
            assertEquals(1, jailed)

            val strayRow = env.databaseManager.getResident(strayUuid).orElseThrow()
            assertNull(strayRow.townUuid)
            assertEquals(TownRank.RESIDENT, strayRow.rank)
            assertNull(strayRow.jailedTownUuid)
            assertEquals(0.0, strayRow.jailBail)

            assertEquals(town.id, env.databaseManager.getResident(memberUuid).orElseThrow().townUuid)
            assertEquals(town.id, env.databaseManager.getResident(mayorUuid).orElseThrow().townUuid)
        }
    }

    @Test
    fun `admin commands treat a reference to a missing town as townless`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val strayUuid = UUID.randomUUID()
            env.residentManager.getOrCreate(env.mockPlayer(mayorUuid, "Mayor"))
            env.residentManager.getOrCreate(env.mockPlayer(strayUuid, "fukoato"))
            val town = env.townManager.createTown("berry_town", mayorUuid)
            env.residentManager.setTown(strayUuid, UUID.randomUUID(), TownRank.RESIDENT)

            val console = mock<ConsoleCommandSender>()
            whenever(console.hasPermission("townia.admin")).thenReturn(true)
            TowniaAdminCommand(env.plugin)
                .onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("addresident", "berry_town", "fukoato"))

            assertFalse(env.sentMessages.any { it.key == "admin.player-in-other-town" })
            assertTrue(env.sentMessages.any { it.recipient === console && it.key == "admin.resident-added" })
            assertEquals(town.id, env.residentManager.getResident(strayUuid).orElseThrow().townUuid)
            assertEquals(town.id, env.databaseManager.getResident(strayUuid).orElseThrow().townUuid)
        }
    }
}
