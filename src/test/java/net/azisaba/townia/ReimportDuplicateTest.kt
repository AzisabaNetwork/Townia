package net.azisaba.townia

import net.azisaba.townia.data.TownRank
import net.azisaba.townia.migration.MigrationSupport
import net.azisaba.townia.migration.TownyFlatfileMigrator
import org.bukkit.Bukkit
import org.bukkit.command.ConsoleCommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Path
import java.util.UUID

/**
 * The live data: Towny 0.103 ran on an offline-mode server, so every resident file is named by,
 * and carries, the offline UUID of the player's name. Players log in here with their real UUID.
 * Importing Towny's files a second time gave each of them a second record under the offline UUID,
 * still in the town they had in Towny; the next login merged that copy into the real record and
 * put a player who had left back into that town.
 */
class ReimportDuplicateTest {

    private val townId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private fun offline(name: String) = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
    private val realKai = UUID.fromString("9a5fe0c6-040e-4048-bf66-295a3b89e0c4")

    private fun writeTowny(pluginsDir: File) {
        fun write(path: String, text: String) {
            val file = File(pluginsDir, "Towny/data/$path")
            file.parentFile.mkdirs()
            file.writeText(text.trimIndent() + "\n")
        }
        write("towns/NewTown.txt", "name=NewTown\nuuid=$townId\nmayor=${offline("Mia")}\nmayorName=Mia\nhomeBlock=world,50,50")
        write("residents/${offline("Mia")}.txt", "name=Mia\nuuid=${offline("Mia")}\ntown=NewTown\ntown-ranks=\nfriends=")
        write("residents/${offline("Kai")}.txt", "name=Kai\nuuid=${offline("Kai")}\ntown=NewTown\ntown-ranks=assistant\nfriends=")
        write("townblocks/world/50_50_16.data", "town=$townId\ntype=default\nprice=-1.0\nchanged=false")
        write("townblocks/world/51_50_16.data", "town=$townId\nresident=${offline("Kai")}\ntype=default\nprice=-1.0\nchanged=false")
    }

    private fun migrate(env: TowniaTestEnv, vararg options: String) {
        Mockito.mockStatic(Bukkit::class.java).use {
            TownyFlatfileMigrator.migrate(env.plugin, mock<ConsoleCommandSender>(), options.toSet())
        }
    }

    private fun recordsNamed(env: TowniaTestEnv, name: String) = env.residentManager.allResidents.filter { it.name == name }

    @Test
    fun `importing again does not give a logged-in player a second record`(@TempDir tempDir: Path) {
        writeTowny(tempDir.toFile())
        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            migrate(env)
            assertEquals(offline("Kai"), recordsNamed(env, "Kai").single().uuid, "nobody has logged in yet: Towny's UUID")
            assertEquals(offline("Mia"), env.townManager.getTown(townId).get().mayorUuid)

            // Kai logs in with his real UUID, then leaves the town.
            val kai = env.residentManager.getOrCreate(realKai, "Kai")
            assertEquals(townId, kai.townUuid)
            assertEquals(realKai, env.plotManager.getPlot("world", 51, 50).get().ownerUuid)
            env.residentManager.clearTown(realKai)
            assertNull(env.plotManager.getPlot("world", 51, 50).get().ownerUuid, "leaving gave the plot back")

            // "Only what is missing": he exists, so Towny's old file must not touch him.
            migrate(env, MigrationSupport.MISSING_ONLY)
            assertEquals(realKai, recordsNamed(env, "Kai").single().uuid, "no second record under the offline UUID")
            assertNull(kai.townUuid, "what he did here since is newer than Towny's file")
            assertNull(env.plotManager.getPlot("world", 51, 50).get().ownerUuid)

            // His next login finds nothing to merge and leaves him where he is.
            assertNull(env.residentManager.getOrCreate(realKai, "Kai").townUuid)
            assertEquals(1, recordsNamed(env, "Kai").size)
        }
    }

    @Test
    fun `a full import writes to the record the player uses`(@TempDir tempDir: Path) {
        writeTowny(tempDir.toFile())
        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            migrate(env)
            env.residentManager.getOrCreate(realKai, "Kai")

            // Asking for everything again puts Towny's state back, on purpose - but onto his own record.
            migrate(env)
            val kai = recordsNamed(env, "Kai").single()
            assertEquals(realKai, kai.uuid)
            assertEquals(townId, kai.townUuid)
            assertEquals(TownRank.ASSISTANT, kai.rank)
            assertEquals(realKai, env.plotManager.getPlot("world", 51, 50).get().ownerUuid, "the plot's owner follows him, not Towny's UUID")
        }
    }
}
