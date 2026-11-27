package net.azisaba.townia

import net.azisaba.townia.data.TownRank
import net.azisaba.townia.migration.TownyAudit
import net.azisaba.townia.migration.TownyFlatfileMigrator
import org.bukkit.Bukkit
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Path

/**
 * Migrates a real Towny data folder when one is available (environment variable
 * TOWNIA_TOWNY_DIR=<a folder shaped like plugins/Towny>). Skipped otherwise.
 * It checks that everything in the files arrives, and prints what the audit says.
 */
class ProductionDataMigrationTest {

    private fun read(file: File): Map<String, String> =
        file.readLines(Charsets.UTF_8).filter { it.contains('=') }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    @Test
    fun `a real Towny data folder migrates completely`(@TempDir tempDir: Path) {
        val source = System.getenv("TOWNIA_TOWNY_DIR")?.let(::File)
        assumeTrue(source != null && File(source, "data/towns").isDirectory, "no real Towny folder given")
        val towny = File(tempDir.toFile(), "Towny")
        source!!.copyRecursively(towny)

        val townFiles = File(towny, "data/towns").listFiles { f -> f.isFile && f.extension == "txt" }!!.map(::read)
        val nationFiles = File(towny, "data/nations").listFiles { f -> f.isFile && f.extension == "txt" }!!.map(::read)
        fun flag(v: Map<String, String>, name: String) = v["protectionStatus"].orEmpty().split(',').any { it.trim() == name }

        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            val started = System.currentTimeMillis()
            Mockito.mockStatic(Bukkit::class.java).use {
                TownyFlatfileMigrator.migrate(env.plugin, mock<ConsoleCommandSender>(), emptySet())
            }
            env.databaseManager.flush()
            println("MIGRATION took ${System.currentTimeMillis() - started} ms")
            println("MESSAGES " + env.sentMessages.joinToString(" | ") { it.key + it.replacements })

            val towns = env.townManager.allTowns
            val nations = env.nationManager.allNations
            println("RESULT towns=${towns.size} nations=${nations.size} residents=${env.residentManager.allResidents.size} plots=${env.plotManager.allPlots.size}")
            assertEquals(townFiles.size, towns.size, "every town file becomes a town")
            // A renamed nation leaves its old file behind: files sharing a UUID are one nation.
            val distinctNations = nationFiles.groupBy { it["uuid"] }
            assertEquals(distinctNations.size, nations.size, "every nation becomes a nation")
            val usedNames = townFiles.mapNotNull { it["nation"]?.takeIf(String::isNotBlank) }.toSet()
            assertTrue(usedNames.all { env.nationManager.getNationByName(it).isPresent }, "each nation keeps the name its towns use")

            // Settings that used to be lost.
            assertEquals(townFiles.count { flag(it, "pvp") }, towns.count { it.hasPvp() }, "towns with PvP on")
            assertEquals(townFiles.count { flag(it, "fire") }, towns.count { it.hasFire() }, "towns with fire on")
            assertEquals(townFiles.count { flag(it, "explosion") }, towns.count { it.hasExplosions() }, "towns with explosions on")
            assertEquals(townFiles.count { flag(it, "mobs") }, towns.count { it.hasMobs() }, "towns with mobs on")
            assertEquals(townFiles.count { !it["tag"].isNullOrBlank() }, towns.count { !it.tag.isNullOrBlank() }, "town tags")
            assertEquals(townFiles.count { it["taxpercent"] == "true" }, towns.count { it.isTaxPercent }, "towns taxing by percent")
            assertEquals(townFiles.count { (it["plotTax"]?.toDoubleOrNull() ?: 0.0) > 0 }, towns.count { it.plotTax > 0 }, "plot tax")
            assertEquals(townFiles.count { (it["spawnCost"]?.toDoubleOrNull() ?: 0.0) > 0 }, towns.count { it.spawnCost > 0 }, "spawn cost")
            assertEquals(townFiles.count { it["open"] == "true" }, towns.count { it.isOpen }, "open towns")
            assertEquals(townFiles.count { it["public"] == "true" }, towns.count { it.isPublic }, "public towns")
            assertEquals(townFiles.count { !it["jailspawns"].isNullOrBlank() }, towns.count { it.hasJail() }, "towns with a jail")
            assertEquals(
                townFiles.sumOf { it["outpostspawns"].orEmpty().split(';').count { s -> s.isNotBlank() } },
                towns.sumOf { it.outposts.size }, "outpost spawns"
            )
            assertEquals(townFiles.count { !it["nation"].isNullOrBlank() }, towns.count { it.nationUuid != null }, "towns in a nation")
            // denyAll towns let nobody build, residents included.
            val denyAll = townFiles.filter { flag(it, "denyAll") }.mapNotNull { it["name"] }
            assertTrue(denyAll.all { name -> env.townManager.getTownByName(name).map { it.permsResident.isNullOrEmpty() }.orElse(false) }, "denyAll towns")

            // Where two files of one nation disagree, either answer is acceptable.
            fun nationCount(label: String, actual: Int, test: (Map<String, String>) -> Boolean) {
                val atLeast = distinctNations.values.count { group -> group.all(test) }
                val atMost = distinctNations.values.count { group -> group.any(test) }
                println("NATION $label: $actual (files say $atLeast..$atMost)")
                assertTrue(actual in atLeast..atMost, "$label: $actual not in $atLeast..$atMost")
            }
            nationCount("tags", nations.count { !it.tag.isNullOrBlank() }) { !it["tag"].isNullOrBlank() }
            nationCount("open", nations.count { it.isOpen }) { it["isOpen"] == "true" }
            nationCount("public", nations.count { it.isPublic }) { it["isPublic"] == "true" }
            nationCount("neutral", nations.count { it.isNeutral }) { it["neutral"] == "true" }

            // Every member a town lists belongs to it, and each town's mayor is its mayor.
            val listed = townFiles.sumOf { it["residents"].orEmpty().split(',').count { s -> s.isNotBlank() } }
            val members = env.residentManager.allResidents.count { it.townUuid != null }
            println("MEMBERS listed-in-town-files=$listed in-town=$members")
            assertEquals(listed, members, "exactly the residents the towns list are in a town")
            val owners = File(towny, "data/townblocks").walkTopDown().filter { it.isFile && it.extension == "data" }
                .count { read(it)["resident"].orEmpty().isNotBlank() }
            println("PLOTS with-owner-in-files=$owners owned=${env.plotManager.allPlots.count { it.ownerUuid != null }}")
            assertEquals(owners, env.plotManager.allPlots.count { it.ownerUuid != null }, "every plot keeps its owner")
            val mayorsPerTown = env.residentManager.allResidents.filter { it.rank == TownRank.MAYOR }.groupingBy { it.townUuid }.eachCount()
            assertTrue(towns.all { mayorsPerTown[it.id] == 1 }, "every town has exactly one mayor")
            val mayorProblems = towns.mapNotNull { town ->
                val mayor = env.residentManager.getResident(town.mayorUuid).orElse(null)
                when {
                    mayor == null -> "no resident for the mayor"
                    mayor.townUuid != town.id -> "mayor belongs to " + (if (mayor.townUuid == null) "no town" else "another town")
                    mayor.rank != TownRank.MAYOR -> "mayor has rank ${mayor.rank}"
                    else -> null
                }
            }
            println("MAYORS " + mayorProblems.groupingBy { it }.eachCount())
            assertTrue(mayorProblems.isEmpty(), "every mayor holds the mayor rank in their town: " + mayorProblems.groupingBy { it }.eachCount())
            println("RANKS sub_mayor=${env.residentManager.allResidents.count { it.rank == TownRank.SUB_MAYOR }} assistant=${env.residentManager.allResidents.count { it.rank == TownRank.ASSISTANT }} other=" +
                env.residentManager.allResidents.flatMap { it.townRanks }.groupingBy { it }.eachCount())

            // What "/town list by residents" costs on the main thread.
            repeat(3) { towns.sortedByDescending { env.residentManager.getResidentsByTown(it.id!!).size } }
            val sortStarted = System.nanoTime()
            repeat(10) { towns.sortedByDescending { env.residentManager.getResidentsByTown(it.id!!).size } }
            println("TOWN-LIST sort by residents: ${(System.nanoTime() - sortStarted) / 10_000_000.0} ms per call")

            // Survives a restart.
            env.reloadManagers()
            assertEquals(townFiles.size, env.townManager.allTowns.size)
            assertEquals(townFiles.count { !it["tag"].isNullOrBlank() }, env.townManager.allTowns.count { !it.tag.isNullOrBlank() })
            assertEquals(nations.count { it.isOpen }, env.nationManager.allNations.count { it.isOpen })

            val auditStarted = System.currentTimeMillis()
            TownyAudit.run(env.plugin, mock<ConsoleCommandSender>())
            println("AUDIT took ${System.currentTimeMillis() - auditStarted} ms")
            println("AUDIT\n" + env.sentMessages.filter { it.key == "admin.audit-line" }.joinToString("\n") { it.replacements.getOrElse(1) { "" } })
            val report = YamlConfiguration.loadConfiguration(File(env.dataFolder, "towny-compat/audit.yml"))
            assertEquals(townFiles.size, report.getInt("data.towns.total"))
        }
    }
}
