package net.azisaba.townia

import net.azisaba.townia.migration.TownyAudit
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Path

/** `/ta migrate audit` reports what a Towny installation used and changes nothing. */
class TownyAuditTest {
    @Test
    fun `the audit reports settings ranks and data and leaves Townia untouched`(@TempDir tempDir: Path) {
        val towny = File(tempDir.toFile(), "Towny")
        fun write(path: String, text: String) {
            val file = File(towny, path)
            file.parentFile.mkdirs()
            file.writeText(text.trimIndent() + "\n")
        }
        write(
            "settings/config.yml", """
            version:
              version: 0.96.1.0
            economy:
              new_expand:
                price_new_town: '250.0'
              closed_economy:
                enabled: 'true'
            global_nation_settings:
              nationzone:
                enable: 'true'
              max_towns_per_nation: '0'
            global_town_settings:
              spawn_cooldown_time: '30'
              keep_inventory_on_death_in_town: 'false'
            """
        )
        write(
            "settings/townyperms.yml", """
            nomad:
                - towny.command.town.new
                - essentials.home
            towns:
                default:
                mayor:
                ranks:
                    assistant:
                    builder:
            nations:
                default:
                king:
                ranks:
                    general:
            """
        )
        write("data/towns/Alpha.txt", "name=Alpha\nmayor=Ann\ntag=ALP\ntaxpercent=true\nplotTax=5.0\noutlaws=Zed")
        write("data/towns/Beta.txt", "name=Beta\nmayor=Bob")
        write("data/nations/Aland.txt", "allies=Bland\nneutral=true")
        write("data/residents/Ann.txt", "town=Alpha\ntown-ranks=builder,sheriff\nnation-ranks=general\ntitle=Sir\nfriends=Bob")
        write("data/residents/Bob.txt", "town=Beta\ntown-ranks=builder\nfriends=")
        write("data/residents/Zed.txt", "friends=")
        write("data/townblocks/world/0_0_16.data", "town=Alpha\ntype=1\nprice=-1.0\nchanged=true\nresident=Ann")
        write("data/townblocks/world/1_0_16.data", "town=Alpha\ntype=0\nprice=10.0\nchanged=false\ngroupID=abc")
        write("data/townblocks/world/2_0_16.data", "town=Alpha\ntype=7\nprice=-1.0")
        write("data/plotgroups/abc.data", "groupName=shops")
        write("data/worlds/world.txt", "pvp=true\nclaimable=true\nusingTowny=true\nunclaimedZoneBuild=false")
        write("data/worlds/resource.txt", "pvp=true\nclaimable=false\nusingTowny=true\nunclaimedZoneBuild=true")

        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            TownyAudit.run(env.plugin, mock<ConsoleCommandSender>())
            val report = YamlConfiguration.loadConfiguration(File(env.dataFolder, "towny-compat/audit.yml"))

            assertEquals("0.96.1.0", report.getString("towny-version"))
            assertEquals("250.0", report.getString("config.applied.economy/new_expand/price_new_town"))
            assertEquals("30", report.getString("config.applied.global_town_settings/spawn_cooldown_time"))
            assertEquals("true", report.getString("config.not-applied.economy/closed_economy/enabled"))
            val unsupported = report.getStringList("unsupported-features")
            assertTrue(unsupported.any { it.startsWith("nation zones") })
            assertTrue(unsupported.any { it.startsWith("closed economy") })
            assertFalse(unsupported.any { it.startsWith("keep inventory") }, "switched off, so not reported")
            assertFalse(unsupported.any { it.startsWith("maximum towns") }, "0 means no limit")

            assertEquals(listOf("assistant", "builder"), report.getStringList("townyperms.town-ranks"))
            assertEquals(listOf("general"), report.getStringList("townyperms.nation-ranks"))
            assertEquals(listOf("essentials (1)"), report.getStringList("townyperms.nodes-of-other-plugins"))

            assertEquals(3, report.getInt("data.residents.total"))
            assertEquals(2, report.getInt("data.residents.in-a-town"))
            assertEquals(listOf("builder: 2", "sheriff: 1"), report.getStringList("data.residents.town-ranks"))
            assertEquals(listOf("general: 1"), report.getStringList("data.residents.nation-ranks"))
            assertEquals(1, report.getInt("data.residents.with-title-or-surname"))
            assertEquals(2, report.getInt("data.towns.total"))
            assertEquals(1, report.getInt("data.towns.with-tag"))
            assertEquals(1, report.getInt("data.towns.taxing-by-percent"))
            assertEquals(1, report.getInt("data.towns.with-outlaws"))
            assertEquals(1, report.getInt("data.nations.neutral"))
            assertEquals(3, report.getInt("data.plots.total"))
            assertEquals(listOf("default: 1", "jail: 1", "shop: 1"), report.getStringList("data.plots.by-type"))
            assertEquals(1, report.getInt("data.plots.for-sale"))
            assertEquals(1, report.getInt("data.plots.with-their-own-permissions"))
            assertEquals(1, report.getInt("data.plots.in-a-plot-group"))
            assertEquals(1, report.getInt("data.plot-groups"))
            assertEquals(listOf("resource"), report.getStringList("data.worlds.not-claimable"))
            assertTrue(report.contains("data.worlds.settings-that-differ.unclaimedZoneBuild"))
            assertFalse(report.contains("data.worlds.settings-that-differ.pvp"))

            // Read only: no town, resident or plot appeared in Townia.
            assertTrue(env.townManager.allTowns.isEmpty())
            assertTrue(env.plotManager.allPlots.isEmpty())
            assertTrue(env.sentMessages.any { it.key == "admin.audit-done" })
        }
    }
}
