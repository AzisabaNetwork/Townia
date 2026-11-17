package net.azisaba.townia

import net.azisaba.townia.command.NationCommand
import net.azisaba.townia.command.TownCommand
import org.bukkit.Bukkit
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Path
import java.util.UUID

class TownPublicPrivatePersistenceTest {

    @Test
    fun `town set public and private persist across reload and control spawn access`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val outsiderUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val outsider = env.mockPlayer(outsiderUuid, "OutsiderBob")

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(outsider)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            town.setSpawn("world", 160.0, 64.0, 160.0, 0f, 0f)
            env.townManager.saveTown(town)
            assertFalse(town.isPublic)

            val townCmd = TownCommand(env.plugin)
            val nationCmd = NationCommand(env.plugin)

            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Any> { Bukkit.getWorld("world") }.thenReturn(env.world)

                // Private town denies outsider spawn
                townCmd.onCommand(outsider, env.dummyCommand("town"), "town", arrayOf("spawn", "berry_town"))
                assertTrue(env.sentMessages.any { it.recipient === outsider && it.key == "error.no-permission" })

                // Set public -> persists across reload
                env.sentMessages.clear()
                townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("set", "public"))
                assertTrue(env.sentMessages.any { it.recipient === mayor && it.key == "town.set-public" })

                env.reloadManagers()
                assertTrue(env.townManager.getTown(town.id).orElseThrow().isPublic)

                // Outsider can now spawn via /t spawn and /n spawn
                env.sentMessages.clear()
                townCmd.onCommand(outsider, env.dummyCommand("town"), "town", arrayOf("spawn", "berry_town"))
                assertTrue(env.sentMessages.any { it.recipient === outsider && it.key == "town.teleporting" })

                env.sentMessages.clear()
                nationCmd.onCommand(outsider, env.dummyCommand("nation"), "nation", arrayOf("spawn", "berry_town"))
                assertTrue(env.sentMessages.any { it.recipient === outsider && it.key == "town.teleporting" })

                // Set private -> persists across reload and denies outsider again
                env.sentMessages.clear()
                townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("set", "private"))
                assertTrue(env.sentMessages.any { it.recipient === mayor && it.key == "town.set-private" })

                env.reloadManagers()
                assertFalse(env.townManager.getTown(town.id).orElseThrow().isPublic)

                env.sentMessages.clear()
                townCmd.onCommand(outsider, env.dummyCommand("town"), "town", arrayOf("spawn", "berry_town"))
                assertTrue(env.sentMessages.any { it.recipient === outsider && it.key == "error.no-permission" })
            }
        }
    }

    @Test
    fun `town toggle public persists across reload`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            env.residentManager.getOrCreate(mayor)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            assertFalse(town.isPublic)

            val townCmd = TownCommand(env.plugin)
            townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("toggle", "public"))
            assertTrue(town.isPublic)

            // Must survive DB reload
            env.reloadManagers()
            assertTrue(env.townManager.getTown(town.id).orElseThrow().isPublic)

            // Toggle back to private and verify DB reload
            townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("toggle", "public"))
            env.reloadManagers()
            assertFalse(env.townManager.getTown(town.id).orElseThrow().isPublic)
        }
    }

    @Test
    fun `town set with no subargument does not throw`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            env.residentManager.getOrCreate(mayor)
            env.townManager.createTown("berry_town", mayorUuid)

            val townCmd = TownCommand(env.plugin)
            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                val offlineMayor = Mockito.mock(org.bukkit.OfflinePlayer::class.java)
                Mockito.`when`(offlineMayor.firstPlayed).thenReturn(1000L)
                Mockito.`when`(offlineMayor.isOnline).thenReturn(true)
                bukkit.`when`<Any> { Bukkit.getOfflinePlayer(mayorUuid) }.thenReturn(offlineMayor)

                assertDoesNotThrow {
                    townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("set"))
                }
            }
        }
    }
}
