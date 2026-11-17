package net.azisaba.townia

import net.azisaba.townia.command.TowniaAdminCommand
import net.azisaba.townia.data.TownRank
import org.bukkit.Bukkit
import org.bukkit.command.ConsoleCommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.UUID

class TowniaAdminTownRestoreTest {

    @Test
    fun `admin setmayor and addresident restore ruined town without touching DB`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val res1Uuid = UUID.randomUUID()
            val res2Uuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "BerryMayor")
            val res1 = env.mockPlayer(res1Uuid, "ResidentOne")
            val res2 = env.mockPlayer(res2Uuid, "ResidentTwo")

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(res1)
            env.residentManager.getOrCreate(res2)

            val town = env.townManager.createTown("berry_town", mayorUuid)
            // Simulate rollback state: all residents (including mayor) lost town_uuid
            env.residentManager.clearTown(mayorUuid)
            assertEquals(0, env.residentManager.countResidentsByTown(town.id!!))

            val console = mock<ConsoleCommandSender>()
            whenever(console.hasPermission("townia.admin")).thenReturn(true)

            val adminCmd = TowniaAdminCommand(env.plugin)
            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Any> { Bukkit.getPlayerExact("BerryMayor") }.thenReturn(mayor)
                bukkit.`when`<Any> { Bukkit.getPlayerExact("ResidentOne") }.thenReturn(res1)
                bukkit.`when`<Any> { Bukkit.getPlayerExact("ResidentTwo") }.thenReturn(res2)

                adminCmd.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("setmayor", "berry_town", "BerryMayor"))
                assertTrue(env.sentMessages.any { it.recipient === console && it.key == "admin.mayor-set" })

                adminCmd.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("addresident", "berry_town", "ResidentOne"))
                adminCmd.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("addresident", "berry_town", "ResidentTwo"))
            }

            // Reload from DB to verify persistence
            env.reloadManagers()
            val reloadedTown = env.townManager.getTownByName("berry_town").orElseThrow()
            assertEquals(mayorUuid, reloadedTown.mayorUuid)
            assertEquals(3, env.residentManager.countResidentsByTown(reloadedTown.id!!))
            assertEquals(TownRank.MAYOR, env.residentManager.getResident(mayorUuid).orElseThrow().rank)
            assertEquals(TownRank.RESIDENT, env.residentManager.getResident(res1Uuid).orElseThrow().rank)
            assertEquals(reloadedTown.id, env.residentManager.getResident(res2Uuid).orElseThrow().townUuid)
        }
    }

    @Test
    fun `admin setmayor and addresident reject player already in another town`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorAUuid = UUID.randomUUID()
            val mayorBUuid = UUID.randomUUID()
            val mayorA = env.mockPlayer(mayorAUuid, "MayorA")
            val mayorB = env.mockPlayer(mayorBUuid, "MayorB")

            env.residentManager.getOrCreate(mayorA)
            env.residentManager.getOrCreate(mayorB)
            env.townManager.createTown("berry_town", mayorAUuid)
            env.townManager.createTown("other_town", mayorBUuid)

            val console = mock<ConsoleCommandSender>()
            whenever(console.hasPermission("townia.admin")).thenReturn(true)
            val adminCmd = TowniaAdminCommand(env.plugin)

            adminCmd.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("addresident", "berry_town", "MayorB"))
            assertTrue(env.sentMessages.any { it.recipient === console && it.key == "error.already-in-town" })

            env.sentMessages.clear()
            adminCmd.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("setmayor", "berry_town", "MayorB"))
            assertTrue(env.sentMessages.any { it.recipient === console && it.key == "error.already-in-town" })
        }
    }

    @Test
    fun `admin addresident clears outlaw status if target was outlawed`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val targetUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "BerryMayor")
            val target = env.mockPlayer(targetUuid, "Bob")

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(target)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            env.townManager.addOutlaw(town.id, targetUuid)
            assertTrue(town.outlaws.contains(targetUuid))

            val console = mock<ConsoleCommandSender>()
            whenever(console.hasPermission("townia.admin")).thenReturn(true)
            val adminCmd = TowniaAdminCommand(env.plugin)

            adminCmd.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("addresident", "berry_town", "Bob"))
            assertFalse(town.outlaws.contains(targetUuid))
            assertEquals(town.id, env.residentManager.getResident(targetUuid).orElseThrow().townUuid)
        }
    }
}
