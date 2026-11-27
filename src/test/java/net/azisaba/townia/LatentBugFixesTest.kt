package net.azisaba.townia

import net.azisaba.townia.data.TownRank
import net.azisaba.townia.listener.PlotProtectionListener
import net.azisaba.townia.listener.PlotProtectionListener.ActionType
import org.bukkit.Location
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/** Faults found by reading the code rather than reported by players. */
class LatentBugFixesTest {

    @Test
    fun `a deleted town leaves no land and no prisoners behind`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayor = env.mockPlayer(UUID.randomUUID(), "Mayor")
            val prisoner = env.mockPlayer(UUID.randomUUID(), "Prisoner")
            env.residentManager.getOrCreate(mayor)
            val held = env.residentManager.getOrCreate(prisoner)
            val town = env.townManager.createTown("gone_town", mayor.uniqueId)
            env.claimChunk(3, 3, town.id!!)
            env.claimChunk(4, 3, town.id!!)
            held.jailedTownUuid = town.id
            env.residentManager.saveResident(held)

            env.townManager.deleteTown(town.id!!)
            env.databaseManager.flush()

            assertNull(held.jailedTownUuid, "nobody stays in the jail of a town that is gone")
            // What a restart would load: the rows themselves must be gone, not just the cache.
            env.reloadManagers()
            assertTrue(env.plotManager.getPlot("world", 3, 3).isEmpty)
            assertTrue(env.plotManager.getPlot("world", 4, 3).isEmpty)
            assertEquals(0, env.plotManager.allPlots.size)
        }
    }

    @Test
    fun `member lists follow every change of town`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayor = env.mockPlayer(UUID.randomUUID(), "Mayor")
            val other = env.mockPlayer(UUID.randomUUID(), "OtherMayor")
            val ann = env.mockPlayer(UUID.randomUUID(), "Ann")
            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(other)
            val annRecord = env.residentManager.getOrCreate(ann)
            val a = env.townManager.createTown("town_a", mayor.uniqueId).id!!
            val b = env.townManager.createTown("town_b", other.uniqueId).id!!
            fun names(town: UUID) = env.residentManager.getResidentsByTown(town).map { it.name }

            assertEquals(listOf("Mayor"), names(a))
            env.residentManager.setTown(ann.uniqueId, a, TownRank.RESIDENT)
            assertEquals(listOf("Ann", "Mayor"), names(a))
            assertEquals(2, env.residentManager.countResidentsByTown(a))

            // Migration and repair code assigns the field directly; the lists must still follow.
            annRecord.townUuid = b
            assertEquals(listOf("Mayor"), names(a))
            assertEquals(listOf("Ann", "OtherMayor"), names(b))

            env.residentManager.clearTown(ann.uniqueId)
            assertEquals(listOf("OtherMayor"), names(b))
            assertEquals(1, env.residentManager.countResidentsByTown(b))
            assertEquals(0, env.residentManager.countResidentsByTown(UUID.randomUUID()))
        }
    }

    @Test
    fun `members of an allied nation get the ally permissions`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val host = env.mockPlayer(UUID.randomUUID(), "Host")
            val ally = env.mockPlayer(UUID.randomUUID(), "Ally")
            val stranger = env.mockPlayer(UUID.randomUUID(), "Stranger")
            listOf(host, ally, stranger).forEach { env.residentManager.getOrCreate(it) }
            val hostTown = env.townManager.createTown("host_town", host.uniqueId)
            val allyTown = env.townManager.createTown("ally_town", ally.uniqueId)
            val strangerTown = env.townManager.createTown("stranger_town", stranger.uniqueId)
            env.nationManager.createNation("host_nation", hostTown.id, host.uniqueId)
            env.nationManager.createNation("ally_nation", allyTown.id, ally.uniqueId)
            env.nationManager.createNation("far_nation", strangerTown.id, stranger.uniqueId)
            env.nationManager.getNation(hostTown.nationUuid).get().allies.add(allyTown.nationUuid!!)
            hostTown.permsAlly = "S"
            hostTown.permsOutsider = ""
            hostTown.permsNation = ""
            env.claimChunk(10, 10, hostTown.id!!)
            val listener = PlotProtectionListener(env.plugin)
            val at = Location(env.world, 165.0, 64.0, 165.0)

            assertFalse(listener.handleAction(ally, at, ActionType.SWITCH), "the town lets allies use switches")
            assertTrue(listener.handleAction(ally, at, ActionType.BUILD), "but not build")
            assertTrue(listener.handleAction(stranger, at, ActionType.SWITCH), "a nation that is no ally stays outside")
        }
    }

    @Test
    fun `blocks added after 1_20 count as switches`() {
        for (name in listOf(
            "CHEST", "COPPER_CHEST", "WAXED_OXIDIZED_COPPER_CHEST", "PALE_OAK_DOOR", "COPPER_DOOR", "WAXED_COPPER_TRAPDOOR",
            "PALE_OAK_FENCE_GATE", "PALE_OAK_BUTTON", "OAK_SHELF", "CRAFTER", "DECORATED_POT", "CHISELED_BOOKSHELF",
            "JUKEBOX", "REPEATER", "POTTED_CACTUS", "LIME_SHULKER_BOX"
        )) {
            assertTrue(PlotProtectionListener.isSwitch(Material.valueOf(name)), name)
        }
        for (name in listOf("STONE", "BOOKSHELF", "OAK_PLANKS", "LECTERN")) {
            assertFalse(PlotProtectionListener.isSwitch(Material.valueOf(name)), name)
        }
    }

    @Test
    fun `tools that change a block in place are recognised`() {
        fun tool(from: Material, to: Material) = PlotProtectionListener.isToolResult(from, to)
        assertTrue(tool(Material.OAK_LOG, Material.STRIPPED_OAK_LOG))
        assertTrue(tool(Material.GRASS_BLOCK, Material.DIRT_PATH))
        assertTrue(tool(Material.DIRT, Material.FARMLAND))
        assertTrue(tool(Material.COPPER_BLOCK, Material.WAXED_COPPER_BLOCK))
        assertFalse(tool(Material.FARMLAND, Material.DIRT), "trampling is handled where the player steps")
        assertFalse(tool(Material.BIG_DRIPLEAF, Material.BIG_DRIPLEAF), "a leaf tilting under a visitor is not a change to guard")
    }
}
