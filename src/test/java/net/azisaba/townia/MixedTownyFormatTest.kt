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
 * The live server's Towny folder came from a 1.15.2 server (Towny 0.96.1) and was then opened by
 * Towny 0.103. Files that newer Towny saved again are in its format; the others still are as the
 * old one left them, including member lists in town files that nobody has updated for years.
 */
class MixedTownyFormatTest {

    private val oldId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val newId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private fun offline(name: String) = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))

    /**
     * OldTown's file was never saved by the newer Towny: it still lists Mia, Kai and Ren, who all
     * left it since. NewTown's file is in the newer format and lists nobody.
     * Mia founded NewTown, Kai is an assistant there, Ren is in no town.
     */
    private fun writeTowny(pluginsDir: File) {
        fun write(path: String, text: String) {
            val file = File(pluginsDir, "Towny/data/$path")
            file.parentFile.mkdirs()
            file.writeText(text.trimIndent() + "\n")
        }
        write("towns/OldTown.txt", "name=OldTown\nresidents=Olga,Mia,Kai,Ren\nmayor=Olga\nassistants=Kai\nuuid=$oldId\nhomeBlock=world,0,0")
        write("towns/NewTown.txt", "name=NewTown\nmayor=Mia\nuuid=$newId\nhomeBlock=world,50,50")
        write("residents/Olga.txt", "town=OldTown\nfriends=")
        write("residents/Mia.txt", "uuid=${offline("Mia")}\ntown=NewTown\ntown-ranks=\nfriends=")
        write("residents/Kai.txt", "uuid=${offline("Kai")}\ntown=NewTown\ntown-ranks=assistant,helper\nfriends=")
        write("residents/Ren.txt", "uuid=${offline("Ren")}\ntown=\nfriends=")
        write("townblocks/world/0_0_16.data", "town=OldTown\ntype=0\nprice=-1.0\nchanged=false")
        write("townblocks/world/50_50_16.data", "town=$newId\ntype=0\nprice=-1.0\nchanged=false")
        write("townblocks/world/51_50_16.data", "town=$newId\nresident=${offline("Kai")}\ntype=0\nprice=-1.0\nchanged=false")
    }

    private fun migrate(env: TowniaTestEnv) {
        Mockito.mockStatic(Bukkit::class.java).use {
            TownyFlatfileMigrator.migrate(env.plugin, mock<ConsoleCommandSender>(), emptySet())
        }
    }

    private fun repair(env: TowniaTestEnv, apply: Boolean): YamlConfiguration {
        Mockito.mockStatic(Bukkit::class.java).use {
            TownyFlatfileMigrator.repair(env.plugin, mock<ConsoleCommandSender>(), apply)
        }
        return YamlConfiguration.loadConfiguration(File(env.dataFolder, "towny-compat/repair-report.yml"))
    }

    @Test
    fun `a newer resident file decides, not a member list nobody updated`(@TempDir tempDir: Path) {
        writeTowny(tempDir.toFile())
        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            migrate(env)
            fun resident(name: String) = env.residentManager.getResidentByName(name).get()

            assertEquals(oldId, resident("Olga").townUuid)
            assertEquals(newId, resident("Mia").townUuid, "Mia left OldTown; its stale list must not pull her back")
            assertEquals(TownRank.MAYOR, resident("Mia").rank)
            assertEquals(newId, resident("Kai").townUuid)
            assertEquals(TownRank.ASSISTANT, resident("Kai").rank, "from his own file, in the town that file names")
            assertEquals(setOf("helper"), resident("Kai").townRanks)
            assertNull(resident("Ren").townUuid, "Ren is in no town; a stale list does not make him a member")
            assertEquals(listOf("Olga"), env.residentManager.getResidentsByTown(oldId).map { it.name })
            assertEquals(resident("Kai").uuid, env.plotManager.getPlot("world", 51, 50).get().ownerUuid)
        }
    }

    @Test
    fun `repair undoes an import that trusted the stale list`(@TempDir tempDir: Path) {
        writeTowny(tempDir.toFile())
        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            migrate(env)
            fun resident(name: String) = env.residentManager.getResidentByName(name).get()
            // What the earlier import left: Kai and Ren back in OldTown, Kai's plot without an owner.
            val kai = resident("Kai")
            val ren = resident("Ren")
            kai.townUuid = oldId
            kai.rank = TownRank.ASSISTANT
            kai.townRanks.clear()
            ren.townUuid = oldId
            env.plotManager.getPlot("world", 51, 50).get().changeOwner(null)

            val dryRun = repair(env, false)
            assertEquals(oldId, kai.townUuid, "a dry run changes nothing")
            assertEquals(1, dryRun.getStringList("residents-moved-to-their-town").size)
            assertEquals(1, dryRun.getStringList("plot-owners-restored").size, "the plot of a resident who is about to be moved back counts")
            assertEquals(1, dryRun.getStringList("residents-in-a-town-towny-no-longer-had-them-in-NOT-changed").size)
            assertEquals(4, dryRun.getInt("diagnosis.format.resident-files"))
            assertEquals(3, dryRun.getInt("diagnosis.format.resident-files-with-uuid-key"))
            assertEquals(1, dryRun.getInt("diagnosis.format.town-files-with-member-list"))
            assertEquals(1, dryRun.getInt("diagnosis.residents.different-town"))
            assertEquals(1, dryRun.getInt("diagnosis.residents.in-a-town-in-townia-but-townless-in-towny"))
            assertEquals(1, dryRun.getInt("diagnosis.plots.owned-in-towny"))
            assertTrue(dryRun.getStringList("diagnosis-samples.residents.different-town").single().contains("Kai"))

            repair(env, true)
            assertEquals(newId, kai.townUuid)
            assertEquals(TownRank.ASSISTANT, kai.rank)
            assertEquals(setOf("helper"), kai.townRanks)
            assertEquals(kai.uuid, env.plotManager.getPlot("world", 51, 50).get().ownerUuid)
            assertEquals(oldId, ren.townUuid, "Ren is only reported: he may have joined on purpose since")
        }
    }

    @Test
    fun `an owner who has logged in since is still found`(@TempDir tempDir: Path) {
        writeTowny(tempDir.toFile())
        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            migrate(env)
            // Kai logs in: his record moves from the offline UUID Towny knew to his own.
            val real = UUID.fromString("9a5fe0c6-040e-4048-bf66-295a3b89e0c4")
            val kai = env.residentManager.getOrCreate(real, "Kai")
            assertEquals(real, kai.uuid)
            assertEquals(newId, kai.townUuid)
            val plot = env.plotManager.getPlot("world", 51, 50).get()
            plot.changeOwner(null)

            val report = repair(env, true)
            assertEquals(real, plot.ownerUuid, "the UUID in Towny's file no longer finds him; his name does")
            assertEquals(1, report.getInt("diagnosis.plots.no-owner-in-townia.restorable"))
        }
    }

    @Test
    fun `a record that was named after its UUID is taken over at login and renamed by repair`(@TempDir tempDir: Path) {
        writeTowny(tempDir.toFile())
        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            migrate(env)
            // What the earlier import stored for players Towny only knew by an offline UUID.
            val kai = env.residentManager.getResidentByName("Kai").get()
            env.residentManager.renameRecord(kai, offline("Kai").toString())
            val mia = env.residentManager.getResidentByName("Mia").get()
            env.residentManager.renameRecord(mia, offline("Mia").toString())

            // Kai logs in: the record under his name's offline UUID is his, whatever it is called.
            val real = UUID.fromString("9a5fe0c6-040e-4048-bf66-295a3b89e0c4")
            val joined = env.residentManager.getOrCreate(real, "Kai")
            assertEquals("Kai", joined.name)
            assertEquals(newId, joined.townUuid, "he is still in his town")
            assertEquals(real, env.plotManager.getPlot("world", 51, 50).get().ownerUuid, "and still owns his plot")

            // Mia has not logged in; repair gives her record its name back.
            val report = repair(env, true)
            assertEquals(1, report.getStringList("residents-given-their-name-back").size)
            assertEquals("Mia", mia.name)
            assertEquals(mia.uuid, env.residentManager.getResidentByName("Mia").get().uuid)
        }
    }
}
