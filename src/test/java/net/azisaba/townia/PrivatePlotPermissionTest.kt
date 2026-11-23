package net.azisaba.townia

import net.azisaba.townia.data.TownRank
import net.azisaba.townia.listener.PlotProtectionListener
import net.azisaba.townia.listener.PlotProtectionListener.ActionType
import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/**
 * Another member of the same town must not open containers (SWITCH) or build on a resident-owned
 * plot just because the town's resident perms allow it on town-owned land.
 */
class PrivatePlotPermissionTest {

    private class Fixture(val env: TowniaTestEnv) {
        val mayorUuid: UUID = UUID.randomUUID()
        val ownerUuid: UUID = UUID.randomUUID()
        val neighbourUuid: UUID = UUID.randomUUID()
        val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
        val owner = env.mockPlayer(ownerUuid, "OwnerBob")
        val neighbour = env.mockPlayer(neighbourUuid, "NeighbourCarol")
        val plotLoc = Location(env.world, 165.0, 64.0, 165.0)
        val listener: PlotProtectionListener

        init {
            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(owner)
            env.residentManager.getOrCreate(neighbour)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            env.residentManager.setTown(ownerUuid, town.id, TownRank.RESIDENT)
            env.residentManager.setTown(neighbourUuid, town.id, TownRank.RESIDENT)
            env.claimChunk(10, 10, town.id!!)
            listener = PlotProtectionListener(env.plugin)
        }

        fun denied(uuid: UUID, type: ActionType): Boolean {
            val player = when (uuid) {
                ownerUuid -> owner
                mayorUuid -> mayor
                else -> neighbour
            }
            return listener.handleAction(player, plotLoc, type)
        }
    }

    @Test
    fun `town-owned plot follows the town's resident perms`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            assertFalse(f.denied(f.neighbourUuid, ActionType.SWITCH))
            assertFalse(f.denied(f.neighbourUuid, ActionType.BUILD))
        }
    }

    @Test
    fun `same-town resident cannot use another resident's plot by default`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            env.plotManager.transferOwnership("world", 10, 10, f.ownerUuid)

            assertTrue(f.denied(f.neighbourUuid, ActionType.SWITCH))
            assertTrue(f.denied(f.neighbourUuid, ActionType.BUILD))
            assertTrue(f.denied(f.neighbourUuid, ActionType.DESTROY))
            assertTrue(f.denied(f.neighbourUuid, ActionType.ITEM))
            assertFalse(f.denied(f.ownerUuid, ActionType.SWITCH))
            assertFalse(f.denied(f.mayorUuid, ActionType.SWITCH))
        }
    }

    @Test
    fun `owner's resident defaults and friends still grant access`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            env.plotManager.transferOwnership("world", 10, 10, f.ownerUuid)
            val owner = env.residentManager.getResident(f.ownerUuid).orElseThrow()

            owner.defaultPermsResident = "S"
            assertFalse(f.denied(f.neighbourUuid, ActionType.SWITCH))
            assertTrue(f.denied(f.neighbourUuid, ActionType.BUILD))

            owner.defaultPermsResident = ""
            owner.defaultPermsFriend = "S"
            owner.friends?.add(f.neighbourUuid.toString())
            assertFalse(f.denied(f.neighbourUuid, ActionType.SWITCH))
        }
    }

    @Test
    fun `old BDSI resident defaults are cleared once on startup`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            val db = env.databaseManager
            fun setDefault(uuid: UUID, perms: String) {
                val res = db.getResident(uuid).orElseThrow()
                res.defaultPermsResident = perms
                db.saveResident(res)
            }
            fun storedDefault(uuid: UUID) = db.getResident(uuid).orElseThrow().defaultPermsResident
            fun restart() {
                db.close()
                db.initialize()
            }

            // Data written by a version before the fix: pretend the migration never ran.
            setDefault(f.ownerUuid, "BDSI")
            setDefault(f.neighbourUuid, "BD")
            db.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM townia_data_migrations") } }
            restart()
            assertEquals("", storedDefault(f.ownerUuid))
            assertEquals("BD", storedDefault(f.neighbourUuid))

            // Once applied, a BDSI chosen on purpose later is left alone.
            setDefault(f.ownerUuid, "BDSI")
            restart()
            assertEquals("BDSI", storedDefault(f.ownerUuid))
        }
    }

    @Test
    fun `plot perms are cleared when the owner changes`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            val plot = env.plotManager.getPlot("world", 10, 10).orElseThrow()
            plot.permsResident = "BDSI"
            plot.permsFriend = "S"

            env.plotManager.transferOwnership("world", 10, 10, f.ownerUuid)

            assertNull(plot.permsResident)
            assertNull(plot.permsFriend)
            assertTrue(f.denied(f.neighbourUuid, ActionType.SWITCH))
        }
    }
}
