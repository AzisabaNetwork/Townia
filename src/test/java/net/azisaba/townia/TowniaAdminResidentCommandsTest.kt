package net.azisaba.townia

import net.azisaba.townia.command.TowniaAdminCommand
import net.azisaba.townia.data.TownRank
import org.bukkit.command.ConsoleCommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.UUID

class TowniaAdminResidentCommandsTest {

    private class Fixture(val env: TowniaTestEnv) {
        val mayorAUuid: UUID = UUID.randomUUID()
        val mayorBUuid: UUID = UUID.randomUUID()
        val bobUuid: UUID = UUID.randomUUID()
        val townA = run {
            env.residentManager.getOrCreate(env.mockPlayer(mayorAUuid, "MayorA"))
            env.residentManager.getOrCreate(env.mockPlayer(mayorBUuid, "MayorB"))
            env.residentManager.getOrCreate(env.mockPlayer(bobUuid, "Bob"))
            env.townManager.createTown("berry_town", mayorAUuid)
        }
        val townB = env.townManager.createTown("other_town", mayorBUuid)
        val console: ConsoleCommandSender = mock<ConsoleCommandSender>().also {
            whenever(it.hasPermission("townia.admin")).thenReturn(true)
        }
        private val cmd = TowniaAdminCommand(env.plugin)

        fun run(vararg args: String) {
            env.sentMessages.clear()
            cmd.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf(*args))
        }

        fun sent(key: String) = env.sentMessages.any { it.recipient === console && it.key == key }
        fun resident(uuid: UUID) = env.residentManager.getResident(uuid).orElseThrow()
    }

    @Test
    fun `setsubmayor, setassistant and setrank add and change ranks`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)

            f.run("setsubmayor", "berry_town", "Bob")
            assertTrue(f.sent("admin.rank-set"))
            assertEquals(f.townA.id, f.resident(f.bobUuid).townUuid)
            assertEquals(TownRank.SUB_MAYOR, f.resident(f.bobUuid).rank)

            f.run("setassistant", "berry_town", "Bob")
            assertEquals(TownRank.ASSISTANT, f.resident(f.bobUuid).rank)

            f.run("setrank", "berry_town", "Bob", "resident")
            assertEquals(TownRank.RESIDENT, f.resident(f.bobUuid).rank)

            f.run("setrank", "berry_town", "Bob", "nonsense")
            assertEquals(TownRank.RESIDENT, f.resident(f.bobUuid).rank)

            // setrank mayor and setmayor are the same path: the old mayor becomes a resident.
            f.run("setrank", "berry_town", "Bob", "mayor")
            assertTrue(f.sent("admin.rank-set"))
            assertEquals(f.bobUuid, f.townA.mayorUuid)
            assertEquals(TownRank.MAYOR, f.resident(f.bobUuid).rank)
            assertEquals(TownRank.RESIDENT, f.resident(f.mayorAUuid).rank)

            f.run("setmayor", "berry_town", "MayorA")
            assertTrue(f.sent("admin.rank-set"))
            assertEquals(f.mayorAUuid, f.townA.mayorUuid)
            assertEquals(TownRank.RESIDENT, f.resident(f.bobUuid).rank)

            env.reloadManagers()
            assertEquals(f.townA.id, env.residentManager.getResident(f.bobUuid).orElseThrow().townUuid)
            assertEquals(f.mayorAUuid, env.townManager.getTownByName("berry_town").orElseThrow().mayorUuid)
        }
    }

    @Test
    fun `rank commands refuse other-town members and the mayor`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)

            f.run("setsubmayor", "berry_town", "MayorB")
            assertTrue(f.sent("admin.player-in-other-town"))
            assertEquals(f.townB.id, f.resident(f.mayorBUuid).townUuid)

            f.run("setassistant", "berry_town", "MayorA")
            assertTrue(f.sent("admin.target-is-mayor"))
            assertEquals(TownRank.MAYOR, f.resident(f.mayorAUuid).rank)

            f.run("setmayor", "berry_town", "MayorB")
            assertTrue(f.sent("admin.player-in-other-town"))
            assertEquals(f.mayorAUuid, f.townA.mayorUuid)
        }
    }

    @Test
    fun `moveresident moves a member of another town and kickresident removes them`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            f.run("setassistant", "other_town", "Bob")
            assertEquals(f.townB.id, f.resident(f.bobUuid).townUuid)

            f.run("addresident", "berry_town", "Bob")
            assertTrue(f.sent("admin.player-in-other-town"))

            f.run("moveresident", "berry_town", "Bob")
            assertTrue(f.sent("admin.resident-moved"))
            assertEquals(f.townA.id, f.resident(f.bobUuid).townUuid)
            assertEquals(TownRank.RESIDENT, f.resident(f.bobUuid).rank)

            f.run("moveresident", "berry_town", "Bob")
            assertTrue(f.sent("error.player-already-in-town"))

            f.run("moveresident", "berry_town", "MayorB")
            assertTrue(f.sent("admin.target-is-mayor"))
            assertEquals(f.townB.id, f.resident(f.mayorBUuid).townUuid)

            f.run("kickresident", "Bob")
            assertTrue(f.sent("admin.resident-kicked"))
            assertNull(f.resident(f.bobUuid).townUuid)

            f.run("kickresident", "Bob")
            assertTrue(f.sent("error.player-not-in-town"))

            f.run("kickresident", "MayorA")
            assertTrue(f.sent("admin.target-is-mayor"))

            env.reloadManagers()
            assertNull(env.residentManager.getResident(f.bobUuid).orElseThrow().townUuid)
        }
    }
}
