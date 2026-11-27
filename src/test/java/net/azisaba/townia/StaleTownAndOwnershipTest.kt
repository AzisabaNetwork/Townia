package net.azisaba.townia

import net.azisaba.townia.data.TownRank
import net.azisaba.townia.migration.TownyFlatfileMigrator
import org.bukkit.Bukkit
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Path
import java.util.UUID

/**
 * The two faults reported from the live server:
 *  1. plots lost their owner and towns ended up with more than one mayor;
 *  2. residents were back in a town they had left.
 * Both came from trusting the `town=` line of a Towny resident file, which Towny did not always
 * rewrite when a player left a town.
 */
class StaleTownAndOwnershipTest {

    private val oldId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val newId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    /**
     * Mia founded NewTown after leaving OldTown, but her resident file still says OldTown.
     * Sam left OldTown and is in no town; his file still says OldTown too.
     * Pat is listed by NewTown and has no resident file at all, yet owns a plot there.
     */
    private fun writeTowny(pluginsDir: File) {
        fun write(path: String, text: String) {
            val file = File(pluginsDir, "Towny/data/$path")
            file.parentFile.mkdirs()
            file.writeText(text.trimIndent() + "\n")
        }
        write("towns/OldTown.txt", "name=OldTown\nresidents=Olga\nmayor=Olga\nassistants=\nuuid=$oldId\nhomeBlock=world,0,0")
        write("towns/NewTown.txt", "name=NewTown\nresidents=Mia,Pat\nmayor=Mia\nassistants=\nuuid=$newId\nhomeBlock=world,50,50")
        write("residents/Olga.txt", "town=OldTown\nfriends=")
        write("residents/Mia.txt", "town=OldTown\ntown-ranks=assistant\nfriends=")
        write("residents/Sam.txt", "town=OldTown\nfriends=")
        write("townblocks/world/0_0_16.data", "town=OldTown\ntype=0\nprice=-1.0\nchanged=false")
        write("townblocks/world/50_50_16.data", "town=NewTown\nresident=Mia\ntype=0\nprice=-1.0\nchanged=false")
        write("townblocks/world/51_50_16.data", "town=NewTown\nresident=Pat\ntype=0\nprice=-1.0\nchanged=false")
    }

    private fun migrate(env: TowniaTestEnv) {
        Mockito.mockStatic(Bukkit::class.java).use {
            TownyFlatfileMigrator.migrate(env.plugin, mock<ConsoleCommandSender>(), emptySet())
        }
    }

    private fun mayorsOf(env: TowniaTestEnv, town: UUID) =
        env.residentManager.allResidents.filter { it.townUuid == town && it.rank == TownRank.MAYOR }.mapNotNull { it.name }

    @Test
    fun `a town's own member list decides who belongs to it`(@TempDir tempDir: Path) {
        writeTowny(tempDir.toFile())
        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            migrate(env)
            fun resident(name: String) = env.residentManager.getResidentByName(name).get()

            assertEquals(newId, resident("Mia").townUuid, "Mia is in the town that lists her, not the one her file names")
            assertEquals(TownRank.MAYOR, resident("Mia").rank)
            assertNull(resident("Sam").townUuid, "Sam left OldTown; its member list no longer has him")
            assertEquals(listOf("Olga"), mayorsOf(env, oldId), "OldTown keeps exactly one mayor")
            assertEquals(listOf("Mia"), mayorsOf(env, newId))
            assertTrue(resident("Mia").townRanks.isEmpty(), "ranks in a stale file belong to the town she left")

            assertEquals(resident("Mia").uuid, env.plotManager.getPlot("world", 50, 50).get().ownerUuid)
            assertEquals(resident("Pat").uuid, env.plotManager.getPlot("world", 51, 50).get().ownerUuid, "an owner without a resident file keeps their plot")
        }
    }

    @Test
    fun `leaving a town gives up the plots of that town only`(@TempDir tempDir: Path) {
        writeTowny(tempDir.toFile())
        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            migrate(env)
            val pat = env.residentManager.getResidentByName("Pat").get()
            // An admin takes Pat out of a town he was wrongly put into: his plot elsewhere must survive.
            pat.townUuid = oldId
            env.residentManager.clearTown(pat.uuid)
            assertEquals(pat.uuid, env.plotManager.getPlot("world", 51, 50).get().ownerUuid)
            // Leaving the town the plot is in does release it.
            env.residentManager.setTown(pat.uuid, newId, TownRank.RESIDENT)
            env.residentManager.clearTown(pat.uuid)
            assertNull(env.plotManager.getPlot("world", 51, 50).get().ownerUuid)
        }
    }

    @Test
    fun `repair puts right what the faulty import left in a live server`(@TempDir tempDir: Path) {
        writeTowny(tempDir.toFile())
        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            migrate(env)
            fun resident(name: String) = env.residentManager.getResidentByName(name).get()
            // Recreate the state the old import produced.
            val mia = resident("Mia")
            val sam = resident("Sam")
            mia.townUuid = oldId
            mia.rank = TownRank.MAYOR
            sam.townUuid = oldId
            env.plotManager.getPlot("world", 50, 50).get().changeOwner(null)
            env.plotManager.getPlot("world", 51, 50).get().changeOwner(null)
            assertEquals(2, mayorsOf(env, oldId).size, "the reported symptom: two mayors")
            // Something a player did after the import, which repair must leave alone.
            val pat = resident("Pat")
            env.residentManager.setTown(pat.uuid, null, TownRank.RESIDENT)

            fun repair(apply: Boolean) = Mockito.mockStatic(Bukkit::class.java).use {
                TownyFlatfileMigrator.repair(env.plugin, mock<ConsoleCommandSender>(), apply)
            }
            repair(false)
            assertEquals(oldId, mia.townUuid, "a dry run changes nothing")
            assertTrue(env.sentMessages.any { it.key == "admin.repair-dry-run" })
            val dryRun = YamlConfiguration.loadConfiguration(File(env.dataFolder, "towny-compat/repair-report.yml"))
            assertEquals(1, dryRun.getStringList("residents-moved-to-their-town").size)

            repair(true)
            assertEquals(newId, mia.townUuid)
            assertEquals(TownRank.MAYOR, mia.rank)
            assertEquals(listOf("Olga"), mayorsOf(env, oldId))
            assertEquals(listOf("Mia"), mayorsOf(env, newId))
            assertEquals(mia.uuid, env.plotManager.getPlot("world", 50, 50).get().ownerUuid, "her plot is hers again")
            assertNull(env.plotManager.getPlot("world", 51, 50).get().ownerUuid, "Pat left the town since, so his plot stays with the town")
            assertEquals(oldId, sam.townUuid, "Sam is only reported: he may have rejoined on purpose")
            val report = YamlConfiguration.loadConfiguration(File(env.dataFolder, "towny-compat/repair-report.yml"))
            assertEquals(1, report.getStringList("residents-in-a-town-towny-no-longer-had-them-in-NOT-changed").size)
            assertTrue(env.sentMessages.any { it.key == "admin.repair-done" })
        }
    }
}
