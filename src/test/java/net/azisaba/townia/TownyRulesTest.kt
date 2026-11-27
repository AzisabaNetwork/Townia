package net.azisaba.townia

import net.azisaba.townia.command.TownCommand
import net.azisaba.townia.config.TownyRules
import net.azisaba.townia.manager.Cooldowns
import net.azisaba.townia.manager.DailyTask
import net.azisaba.townia.migration.TownyConfigMigrator
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.OfflinePlayer
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Path
import java.time.LocalTime
import java.util.UUID

/** The rules Towny kept in config.yml: off by default, the same as Towny once configured. */
class TownyRulesTest {

    private fun TowniaTestEnv.set(vararg pairs: Pair<String, Any>) {
        pairs.forEach { (key, value) -> config[key] = value }
        towniaConfig.reload()
    }

    private fun TowniaTestEnv.chunk(x: Int, z: Int): Chunk {
        val chunk = mock<Chunk>()
        whenever(chunk.world).thenReturn(world)
        whenever(chunk.x).thenReturn(x)
        whenever(chunk.z).thenReturn(z)
        return chunk
    }

    private fun TowniaTestEnv.town(name: String): Pair<Player, UUID> {
        val mayor = mockPlayer(UUID.randomUUID(), "Mayor_$name")
        residentManager.getOrCreate(mayor)
        return mayor to townManager.createTown(name, mayor.uniqueId).id!!
    }

    @Test
    fun `nothing is switched on until it is configured`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val rules = env.towniaConfig.rules
            assertEquals(25.0, rules.claimPrice(25.0, 40), "a flat price by default")
            assertTrue(rules.outpostCost < 0)
            assertEquals(0.0, rules.nationUpkeep)
            assertFalse(rules.hasDeathCosts)
            assertEquals(0, rules.spawnCooldownSeconds)
            assertEquals(0, rules.minDistanceFromTownPlot)
            assertEquals(0, rules.maxPlotsPerResident)
            assertTrue(rules.friendlyFire)
            assertFalse(rules.healthRegen)
            assertFalse(rules.townMobRemoval)
            assertFalse(rules.boardOnLogin)
            assertNull(rules.newDayTime)
            assertTrue(rules.wildernessIgnore.isEmpty())
        }
    }

    @Test
    fun `durations and prices are read the way Towny wrote them`(@TempDir tempDir: Path) {
        assertEquals(3, TownyRules.seconds("3s", -1))
        assertEquals(300, TownyRules.seconds("5m", -1))
        assertEquals(43200, TownyRules.seconds("12h", -1))
        assertEquals(30, TownyRules.seconds("30", -1))
        assertEquals(-1, TownyRules.seconds("soon", -1))
        TowniaTestEnv(tempDir).use { env ->
            env.set("economy.claim-cost-increase" to 1.1, "new-day-time" to "12:00")
            val rules = env.towniaConfig.rules
            assertEquals(25.0, rules.claimPrice(25.0, 0))
            assertEquals(Math.round(Math.pow(1.1, 10.0) * 25.0).toDouble(), rules.claimPrice(25.0, 10))
            assertEquals(LocalTime.NOON, rules.newDayTime)
        }
    }

    @Test
    fun `land keeps its distance from other towns`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            env.set(
                "claiming.min-distance-from-town-plot" to 3,
                "claiming.min-distance-from-town-homeblock" to 5,
                "claiming.min-distance-for-outpost" to 4
            )
            val (_, alpha) = env.town("alpha")
            val (_, beta) = env.town("beta")
            env.plotManager.claimChunk(alpha, env.chunk(0, 0))
            env.townManager.getTown(alpha).get().setHomeBlock("world", 0, 0)

            // Two chunks away from Alpha's land: closer than the three required.
            var error = assertThrows(TowniaException::class.java) { env.plotManager.claimChunk(beta, env.chunk(2, 0)) }
            assertEquals("town.claim-too-close-plot", error.messageKey)
            // Far enough from the land, still too close to the home block.
            error = assertThrows(TowniaException::class.java) { env.plotManager.claimChunk(beta, env.chunk(4, 0)) }
            assertEquals("town.claim-too-close-homeblock", error.messageKey)
            env.plotManager.claimChunk(beta, env.chunk(5, 0))
            assertTrue(env.plotManager.isClaimed("world", 5, 0))

            // A town's own land never blocks its normal claims...
            env.plotManager.claimChunk(alpha, env.chunk(0, 1))
            // ...but an outpost has to stand apart from any land, the town's own included.
            error = assertThrows(TowniaException::class.java) { env.plotManager.claimChunk(alpha, env.chunk(0, 4), outpost = true) }
            assertEquals("town.outpost-too-close", error.messageKey)
            env.plotManager.claimChunk(alpha, env.chunk(0, 5), outpost = true)
        }
    }

    @Test
    fun `town taxes are capped`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            env.set("economy.max-tax-amount" to 1000.0)
            val (mayor, townId) = env.town("alpha")
            val cmd = TownCommand(env.plugin)
            cmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("set", "taxes", "5000"))
            assertTrue(env.sentMessages.any { it.key == "town.taxes-too-high" })
            assertEquals(0.0, env.townManager.getTown(townId).get().taxes)
            cmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("set", "taxes", "500"))
            assertEquals(500.0, env.townManager.getTown(townId).get().taxes)
        }
    }

    @Test
    fun `spawn travel has a cooldown and a fare paid to the town`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            env.set("spawn.cooldown" to 30, "economy.spawn-travel.public" to 10.0)
            Cooldowns.clear()
            val (_, townId) = env.town("alpha")
            val town = env.townManager.getTown(townId).get()
            town.isPublic = true
            town.setSpawn("world", 8.0, 64.0, 8.0, 0f, 0f)
            env.townManager.saveTown(town)

            val economy = mock<Economy>()
            whenever(economy.has(any<OfflinePlayer>(), any<Double>())).thenReturn(true)
            whenever(economy.withdrawPlayer(any<OfflinePlayer>(), any<Double>()))
                .thenReturn(EconomyResponse(10.0, 0.0, EconomyResponse.ResponseType.SUCCESS, ""))
            whenever(economy.format(any<Double>())).thenReturn("10")
            whenever(env.plugin.economy).thenReturn(economy)

            val visitor = env.mockPlayer(UUID.randomUUID(), "Visitor")
            env.residentManager.getOrCreate(visitor)
            val cmd = TownCommand(env.plugin)
            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Any> { Bukkit.getWorld("world") }.thenReturn(env.world)

                cmd.onCommand(visitor, env.dummyCommand("town"), "town", arrayOf("spawn", "alpha"))
                assertTrue(env.sentMessages.any { it.recipient === visitor && it.key == "town.teleporting" })
                assertTrue(env.sentMessages.any { it.recipient === visitor && it.key == "spawn.fare-paid" })
                assertEquals(10.0, env.townManager.getTown(townId).get().balance)

                env.sentMessages.clear()
                cmd.onCommand(visitor, env.dummyCommand("town"), "town", arrayOf("spawn", "alpha"))
                assertTrue(env.sentMessages.any { it.recipient === visitor && it.key == "error.cooldown" })
                assertFalse(env.sentMessages.any { it.key == "town.teleporting" })
                assertEquals(10.0, env.townManager.getTown(townId).get().balance, "no second fare")
            }
            Cooldowns.clear()
        }
    }

    @Test
    fun `a nation that cannot pay its upkeep is disbanded and neutrality costs money`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            env.set("economy.nation-upkeep" to 100.0, "economy.neutrality-cost" to 50.0)
            val (richMayor, richTown) = env.town("alpha")
            val (poorMayor, poorTown) = env.town("beta")
            val (thinMayor, thinTown) = env.town("gamma")
            env.nationManager.createNation("Rich", richTown, richMayor.uniqueId)
            env.nationManager.createNation("Poor", poorTown, poorMayor.uniqueId)
            env.nationManager.createNation("Thin", thinTown, thinMayor.uniqueId)
            val rich = env.nationManager.getNationByName("Rich").get().apply { balance = 500.0; isNeutral = true }
            env.nationManager.getNationByName("Poor").get().balance = 50.0
            val thin = env.nationManager.getNationByName("Thin").get().apply { balance = 120.0; isNeutral = true }

            DailyTask(env.plugin).run()

            assertEquals(350.0, rich.balance, "upkeep and the neutrality fee")
            assertTrue(rich.isNeutral)
            assertTrue(env.nationManager.getNationByName("Poor").isEmpty)
            assertNull(env.townManager.getTown(poorTown).get().nationUuid)
            assertEquals(20.0, thin.balance, "paid the upkeep, could not pay for neutrality")
            assertFalse(thin.isNeutral)
        }
    }

    @Test
    fun `a Towny config is carried over`(@TempDir tempDir: Path) {
        val townyConfig = File(tempDir.toFile(), "Towny/settings/config.yml")
        townyConfig.parentFile.mkdirs()
        townyConfig.writeText(
            """
            town:
              max_plots_per_resident: '100'
              max_claim_radius_value: '4'
              min_plot_distance_from_town_plot: '5'
              min_distance_from_town_homeblock: '5'
              min_distance_for_outpost_from_plot: '5'
            global_town_settings:
              friendly_fire: 'false'
              health_regen:
                speed: 3s
                enable: 'true'
              allow_town_spawn_travel: 'true'
              allow_town_spawn_travel_ally: 'false'
              teleport_warmup_time: '0'
              spawn_cooldown_time: '30'
              pvp_cooldown_time: '30'
              prevent_town_spawn_in: enemy
              display_board_onlogin: 'true'
            plugin:
              day_timer:
                day_interval: 1d
                new_day_time: 12h
            protection:
              town_mob_removal_entities: Monster,Flying,Slime
              mob_removal_speed: 5s
              potion_types: BLINDNESS,CONFUSION,HARM
            unclaimed:
              unclaimed_zone_ignore: GOLD_ORE,LOG,TORCH
            economy:
              spawn_travel:
                price_town_spawn_travel: '0.0'
                price_town_nation_spawn_travel: '5.0'
                price_town_ally_spawn_travel: '10.0'
                price_town_public_spawn_travel: '10.0'
                town_spawn_cost_paid_to_town: 'true'
              price_nation_neutrality: '100.0'
              new_expand:
                price_outpost: '500.0'
                price_claim_townblock: '25.0'
                price_claim_townblock_increase: '1.0'
              death:
                price_death_type: fixed
                price_death: '1.0'
              daily_taxes:
                max_tax_amount: '1000.0'
                max_tax_percent: '25'
                price_nation_upkeep: '100.0'
            """.trimIndent()
        )
        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            TownyConfigMigrator.migrate(env.plugin, mock<ConsoleCommandSender>())
            val saved = YamlConfiguration.loadConfiguration(File(env.dataFolder, "config.yml"))
            val rules = TownyRules(saved)

            assertEquals(500.0, rules.outpostCost)
            assertEquals(1.0, rules.claimCostIncrease)
            assertEquals(100.0, rules.nationUpkeep)
            assertEquals(100.0, rules.neutralityCost)
            assertEquals(1000.0, rules.maxTaxAmount)
            assertEquals(25.0, rules.maxTaxPercent)
            assertEquals(1.0, rules.deathPrice)
            assertFalse(rules.deathPercentage)
            assertEquals(0.0, rules.spawnPriceTown)
            assertEquals(5.0, rules.spawnPriceNation)
            assertEquals(10.0, rules.spawnPriceAlly)
            assertEquals(10.0, rules.spawnPricePublic)
            assertTrue(rules.spawnPaidToTown)
            assertEquals(30, rules.spawnCooldownSeconds)
            assertEquals(0, rules.spawnWarmupSeconds)
            assertTrue(rules.allowSpawnTravel)
            assertFalse(rules.allowSpawnTravelAlly)
            assertEquals(setOf("enemy"), rules.preventSpawnIn)
            assertEquals(5, rules.minDistanceFromTownPlot)
            assertEquals(5, rules.minDistanceFromTownHomeblock)
            assertEquals(5, rules.minDistanceForOutpost)
            assertEquals(4, rules.maxClaimRadius)
            assertEquals(100, rules.maxPlotsPerResident)
            assertFalse(rules.friendlyFire)
            assertEquals(30, rules.pvpToggleCooldownSeconds)
            assertTrue(rules.townMobRemoval)
            assertEquals(5, rules.townMobRemovalSeconds)
            assertTrue(rules.healthRegen)
            assertEquals(3, rules.healthRegenSeconds)
            assertTrue(rules.boardOnLogin)
            assertEquals(LocalTime.NOON, rules.newDayTime)
            assertEquals(listOf("GOLD_ORE", "LOG", "TORCH"), saved.getStringList("wilderness.ignore"))
            assertEquals(listOf("BLINDNESS", "CONFUSION", "HARM"), saved.getStringList("protection.potion-types"))
            assertEquals(25.0, saved.getDouble("claim-cost"))
        }
    }
}
