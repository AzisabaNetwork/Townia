package net.azisaba.townia

import net.azisaba.townia.command.TowniaAdminCommand
import net.azisaba.townia.migration.TownyConfigMigrator
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Path

/** What `/ta migrate config` takes from Towny besides config.yml: worlds, townyperms and personal perms. */
class ConfigImportExtrasTest {

    private fun write(root: File, path: String, text: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(text, Charsets.UTF_8)
    }

    @Test
    fun `worlds, townyperms and the default plot perms come over from Towny`(@TempDir tempDir: Path) {
        val towny = File(tempDir.toFile(), "Towny")
        // Towny 0.96 writes every value as a quoted string.
        write(towny, "settings/config.yml", """
            default_perm_flags:
              resident:
                friend:
                  build: 'true'
                  destroy: 'false'
                  switch: 'true'
                  item_use: 'false'
        """.trimIndent())
        val perms = System.getenv("TOWNIA_TOWNY_PERMS")?.let(::File)?.takeIf { it.isFile }?.readText()
            ?: File("src/main/resources/townyperms.yml").readText()
        write(towny, "settings/townyperms.yml", perms.replace("    - towny.command.town.list\n", "    - towny.command.town.list\n    - towny.command.resident.friend\n"))
        File(towny, "data/towns").mkdirs()
        write(towny, "data/worlds/world.txt", "usingTowny=true\npvp=true\n")
        write(towny, "data/worlds/world_nether.txt", "usingTowny=true\npvp=false\nforcepvp=false\n")
        write(towny, "data/worlds/arena.txt", "usingTowny=true\npvp=false\nforcepvp=true\n")
        write(towny, "data/worlds/creative.txt", "usingTowny=false\n")

        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            File(env.dataFolder, "townyperms.yml").writeText("nomad:\n    - towny.command.town.list\n")
            TownyConfigMigrator.migrate(env.plugin, mock<ConsoleCommandSender>())
            val saved = YamlConfiguration.loadConfiguration(File(env.dataFolder, "config.yml"))
            assertEquals(listOf("arena", "world", "world_nether"), saved.getStringList("allowed-worlds"))
            assertEquals(listOf("world_nether"), saved.getStringList("combat.pvp-disabled-worlds"), "forced PvP and PvP-on worlds keep it")
            assertEquals("BS", saved.getString("resident-defaults.perms.friend"))

            assertTrue(File(env.dataFolder, "townyperms.yml").readText().contains("towny.command.resident.friend"))
            assertTrue(File(env.dataFolder, "townyperms.yml.bak").isFile, "the file it replaced is kept")
            val nomad = env.residentManager.getOrCreate(env.mockPlayer(java.util.UUID.randomUUID(), "Nomad"))
            assertEquals(true, env.townyPerms.permsFor(nomad)["towny.command.resident.friend"], "loaded at once")

            // A second run with nothing new changes nothing and keeps the backup as it is.
            val backup = File(env.dataFolder, "townyperms.yml.bak").readText()
            TownyConfigMigrator.migrate(env.plugin, mock<ConsoleCommandSender>())
            assertEquals(backup, File(env.dataFolder, "townyperms.yml.bak").readText())
        }
    }

    @Test
    fun `migrate only needs a town or a resident and refuses words it does not know`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val console = mock<ConsoleCommandSender>()
            val cmd = TowniaAdminCommand(env.plugin)
            fun run(vararg args: String): List<String> {
                env.sentMessages.clear()
                cmd.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("migrate", *args))
                return env.sentMessages.map { it.key }
            }
            assertEquals(listOf("admin.migrate-only-usage"), run("only"))
            assertEquals(listOf("admin.migrate-only-usage"), run("only", "settings-only"))
            assertEquals(listOf("admin.migrate-only-usage"), run("only", "town", "alpha", "everything"))
            assertTrue(run("only", "town", "alpha").none { it == "admin.migrate-only-usage" }, "goes on to look for Towny's files")
            assertEquals(listOf("admin.migrate-usage"), run("nonsense"))
        }
    }
}
