package net.azisaba.townia

import net.azisaba.townia.config.TownyRules
import net.azisaba.townia.migration.TownyConfigMigrator
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Path

/**
 * Runs the config migration against a real Towny 0.96.1.0 config.yml when one is available
 * (environment variable TOWNIA_TOWNY_CONFIG=<path>). Skipped otherwise, so the build never depends on it.
 */
class ReferenceConfigMigrationTest {
    @Test
    fun `a real Towny 0_96 config migrates to the values Towny used`(@TempDir tempDir: Path) {
        val source = System.getenv("TOWNIA_TOWNY_CONFIG")?.let(::File)
        assumeTrue(source != null && source.isFile, "no reference Towny config given")
        val target = File(tempDir.toFile(), "Towny/settings/config.yml")
        target.parentFile.mkdirs()
        source!!.copyTo(target)

        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            TownyConfigMigrator.migrate(env.plugin, mock<ConsoleCommandSender>())
            val saved = YamlConfiguration.loadConfiguration(File(env.dataFolder, "config.yml"))
            val rules = TownyRules(saved)
            println("MIGRATED " + saved.getKeys(true).filter { !saved.isConfigurationSection(it) && !it.startsWith("town_level") && !it.startsWith("nation_level") }
                .joinToString("\n") { "$it = ${saved.get(it)}" })

            // Towny's shipped defaults.
            assertEquals(250.0, saved.getDouble("town-creation-cost"))
            assertEquals(1000.0, saved.getDouble("nation-creation-cost"))
            assertEquals(25.0, saved.getDouble("claim-cost"))
            assertEquals(10.0, saved.getDouble("town-upkeep"))
            assertEquals(8, saved.getInt("town-block-ratio"))
            assertFalse(saved.getBoolean("wilderness.build"))
            assertFalse(saved.getBoolean("wilderness.destroy"))
            assertTrue(saved.getBoolean("wilderness.pvp"))
            assertEquals("BDSI", saved.getString("town-defaults.perms.resident"))
            assertEquals("BDSI", saved.getString("resident-defaults.perms.friend"))
            assertEquals(500.0, rules.outpostCost)
            assertEquals(100.0, rules.nationUpkeep)
            assertEquals(30, rules.spawnCooldownSeconds)
            assertEquals(5, rules.minDistanceFromTownPlot)
            assertEquals(4, rules.maxClaimRadius)
            assertTrue(rules.healthRegen)
            assertTrue(rules.boardOnLogin)
        }
    }
}
