package net.azisaba.townia

import net.azisaba.townia.command.NationCommand
import net.azisaba.townia.command.TownCommand
import net.azisaba.townia.listener.PlayerJoinListener
import net.azisaba.townia.listener.PlayerMoveListener
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.UUID

class OutlawEvictionAndSpawnTest {

    @Test
    fun `outlawing player inside town teleports them to world spawn and blocks town spawn`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val targetUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val insideTownLoc = Location(env.world, 160.0, 64.0, 160.0) // chunk (10, 10)
            val target = env.mockPlayer(targetUuid, "Bob", insideTownLoc)

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(target)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            town.isPublic = true
            town.setSpawn("world", 160.0, 64.0, 160.0, 0f, 0f)
            env.townManager.saveTown(town)
            env.claimChunk(10, 10, town.id!!)

            val townCmd = TownCommand(env.plugin)
            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Any> { Bukkit.getPlayer(targetUuid) }.thenReturn(target)
                bukkit.`when`<Any> { Bukkit.getWorld("world") }.thenReturn(env.world)

                townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("outlaw", "add", "Bob"))

                verify(target).teleport(env.worldSpawn)
                assertTrue(env.sentMessages.any { it.recipient === target && it.key == "outlaw.entry-denied" })

                env.sentMessages.clear()
                townCmd.onCommand(target, env.dummyCommand("town"), "town", arrayOf("spawn", "berry_town"))
                assertTrue(env.sentMessages.any { it.recipient === target && it.key == "outlaw.entry-denied" })
                verify(target, never()).teleport(Location(env.world, 160.0, 64.0, 160.0, 0f, 0f))
            }
        }
    }

    @Test
    fun `outlawing player outside town does not teleport them`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val targetUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val outsideLoc = Location(env.world, 320.0, 64.0, 320.0) // chunk (20, 20)
            val target = env.mockPlayer(targetUuid, "Bob", outsideLoc)

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(target)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            env.claimChunk(10, 10, town.id!!)

            val townCmd = TownCommand(env.plugin)
            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Any> { Bukkit.getPlayer(targetUuid) }.thenReturn(target)
                townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("outlaw", "add", "Bob"))
            }

            verify(target, never()).teleport(env.worldSpawn)
        }
    }

    @Test
    fun `outlaw moving between chunks inside town is evicted to world spawn`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val targetUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val chunk10 = Location(env.world, 165.0, 64.0, 165.0)
            val chunk11 = Location(env.world, 180.0, 64.0, 165.0)
            val target = env.mockPlayer(targetUuid, "Bob", chunk10)

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(target)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            env.claimChunk(10, 10, town.id!!)
            env.claimChunk(11, 10, town.id)
            env.townManager.addOutlaw(town.id, targetUuid)

            val listener = PlayerMoveListener(env.plugin)
            val event = PlayerMoveEvent(target, chunk10, chunk11)
            listener.onPlayerMove(event)

            assertTrue(event.isCancelled)
            verify(target).teleport(env.worldSpawn)
        }
    }

    @Test
    fun `outlaw joining inside town is evicted to world spawn`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val targetUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val insideTownLoc = Location(env.world, 165.0, 64.0, 165.0)
            val target = env.mockPlayer(targetUuid, "Bob", insideTownLoc)

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(target)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            env.claimChunk(10, 10, town.id!!)
            env.townManager.addOutlaw(town.id, targetUuid)

            val joinListener = PlayerJoinListener(env.plugin)
            joinListener.onPlayerJoin(PlayerJoinEvent(target, net.kyori.adventure.text.Component.empty()))

            verify(target).teleport(env.worldSpawn)
            assertTrue(env.sentMessages.any { it.recipient === target && it.key == "outlaw.entry-denied" })
        }
    }

    @Test
    fun `un-outlawed player can use town spawn and nation spawn when town is public`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val targetUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val target = env.mockPlayer(targetUuid, "Bob")

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(target)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            town.isPublic = true
            town.setSpawn("world", 160.0, 64.0, 160.0, 0f, 0f)
            env.townManager.saveTown(town)
            env.nationManager.createNation("Azisaba", town.id, mayorUuid)
            val nation = env.nationManager.getNationByName("Azisaba").orElseThrow()
            nation.setSpawn("world", 160.0, 64.0, 160.0, 0f, 0f)
            env.nationManager.saveNation(nation)

            val townCmd = TownCommand(env.plugin)
            val nationCmd = NationCommand(env.plugin)

            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Any> { Bukkit.getPlayer(targetUuid) }.thenReturn(target)
                bukkit.`when`<Any> { Bukkit.getWorld("world") }.thenReturn(env.world)

                townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("outlaw", "add", "Bob"))
                assertTrue(town.outlaws.contains(targetUuid))

                townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("outlaw", "remove", "Bob"))
                assertFalse(town.outlaws.contains(targetUuid))

                // Verify removal persisted across reload
                env.reloadManagers()
                val reloadedTown = env.townManager.getTown(town.id).orElseThrow()
                assertFalse(reloadedTown.outlaws.contains(targetUuid))

                env.sentMessages.clear()
                townCmd.onCommand(target, env.dummyCommand("town"), "town", arrayOf("spawn", "berry_town"))
                assertTrue(env.sentMessages.any { it.recipient === target && it.key == "town.teleporting" }, "Expected town.teleporting, got: ${env.sentMessages}")

                env.sentMessages.clear()
                nationCmd.onCommand(target, env.dummyCommand("nation"), "nation", arrayOf("spawn", "Azisaba"))
                assertTrue(env.sentMessages.any { it.recipient === target && it.key == "nation.teleporting" })

                env.sentMessages.clear()
                nationCmd.onCommand(target, env.dummyCommand("nation"), "nation", arrayOf("spawn", "berry_town"))
                assertTrue(env.sentMessages.any { it.recipient === target && it.key == "town.teleporting" })
            }
        }
    }
}
