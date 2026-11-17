package net.azisaba.townia

import net.azisaba.townia.command.TownCommand
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaPlayer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.UUID

/**
 * A second resident row with the same name (re-registered name, or a Towny migration
 * leftover under an old UUID) used to win the name lookup, so invites and outlaw entries
 * went to a UUID that nobody was logged in with.
 */
class StaleNameRecordTest {

    private fun addStaleRecord(env: TowniaTestEnv, name: String): UUID {
        val staleUuid = UUID.randomUUID()
        val stale = TowniaPlayer(staleUuid, name, null, TownRank.RESIDENT, 1L, null)
        env.databaseManager.saveResident(stale)
        env.residentManager.cacheResident(stale) // indexes the name to the stale UUID
        return staleUuid
    }

    @Test
    fun `invite reaches the online player even if a stale record has the same name`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val targetUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val target = env.mockPlayer(targetUuid, "Bob")
            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(target)
            val staleUuid = addStaleRecord(env, "Bob")
            val town = env.townManager.createTown("berry_town", mayorUuid)
            whenever(env.server.getPlayerExact("Bob")).thenReturn(target)

            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Any> { Bukkit.getPlayer(targetUuid) }.thenReturn(target)
                TownCommand(env.plugin).onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("invite", "Bob"))
            }

            assertTrue(env.databaseManager.getInvite(targetUuid, town.id!!).isPresent)
            assertFalse(env.databaseManager.getInvite(staleUuid, town.id).isPresent)
            assertTrue(env.sentMessages.any { it.recipient === target && it.key == "town.invite-received" })
        }
    }

    @Test
    fun `outlawing by name evicts the online player even if a stale record has the same name`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val targetUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val target = env.mockPlayer(targetUuid, "Bob", Location(env.world, 165.0, 64.0, 165.0))
            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(target)
            addStaleRecord(env, "Bob")
            val town = env.townManager.createTown("berry_town", mayorUuid)
            env.claimChunk(10, 10, town.id!!)
            whenever(env.server.getPlayerExact("Bob")).thenReturn(target)

            val cmd = TownCommand(env.plugin)
            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Any> { Bukkit.getPlayer(targetUuid) }.thenReturn(target)
                cmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("outlaw", "add", "Bob"))
                assertTrue(town.outlaws.contains(targetUuid))
                verify(target).teleport(env.worldSpawn)

                cmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("outlaw", "remove", "Bob"))
                assertFalse(town.outlaws.contains(targetUuid))
            }
        }
    }

    @Test
    fun `joining player takes over the name index from a stale record`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val targetUuid = UUID.randomUUID()
            val target = env.mockPlayer(targetUuid, "Bob")
            env.residentManager.getOrCreate(target)
            addStaleRecord(env, "Bob")

            env.residentManager.getOrCreate(target) // join again
            assertEquals(targetUuid, env.residentManager.getResidentByName("bob").orElseThrow().uuid)
        }
    }

    @Test
    fun `toggle open persists across reload and toggles accept explicit state`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            env.residentManager.getOrCreate(mayor)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            val cmd = TownCommand(env.plugin)

            cmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("toggle", "open"))
            env.reloadManagers()
            assertTrue(env.townManager.getTown(town.id).orElseThrow().isOpen)

            // "on" twice must stay on instead of flipping back
            cmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("toggle", "public", "on"))
            cmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("toggle", "public", "on"))
            env.reloadManagers()
            assertTrue(env.townManager.getTown(town.id).orElseThrow().isPublic)

            cmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("toggle", "public", "off"))
            env.reloadManagers()
            assertFalse(env.townManager.getTown(town.id).orElseThrow().isPublic)
        }
    }
}
