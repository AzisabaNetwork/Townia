package net.azisaba.townia

import net.azisaba.townia.command.TownCommand
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.listener.DeathCostListener
import net.azisaba.townia.manager.DailyTask
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.UUID

/** Money moves the way Towny moved it, checked against an economy that really keeps balances. */
class EconomyRulesTest {

    /** A Vault economy backed by a map, so every withdrawal and deposit can be checked. */
    private class Bank {
        val balances = HashMap<UUID, Double>()
        val economy: Economy = mock()

        init {
            fun id(p: Any?) = (p as OfflinePlayer).uniqueId
            whenever(economy.getBalance(any<OfflinePlayer>())).doAnswer { balances[id(it.arguments[0])] ?: 0.0 }
            whenever(economy.hasAccount(any<OfflinePlayer>())).doAnswer { balances.containsKey(id(it.arguments[0])) }
            whenever(economy.has(any<OfflinePlayer>(), any<Double>())).doAnswer { (balances[id(it.arguments[0])] ?: 0.0) >= it.arguments[1] as Double }
            whenever(economy.withdrawPlayer(any<OfflinePlayer>(), any<Double>())).doAnswer {
                val amount = it.arguments[1] as Double
                val now = balances[id(it.arguments[0])] ?: 0.0
                if (now < amount) EconomyResponse(0.0, now, EconomyResponse.ResponseType.FAILURE, "insufficient")
                else {
                    balances[id(it.arguments[0])] = now - amount
                    EconomyResponse(amount, now - amount, EconomyResponse.ResponseType.SUCCESS, "")
                }
            }
            whenever(economy.depositPlayer(any<OfflinePlayer>(), any<Double>())).doAnswer {
                val amount = it.arguments[1] as Double
                val now = (balances[id(it.arguments[0])] ?: 0.0) + amount
                balances[id(it.arguments[0])] = now
                EconomyResponse(amount, now, EconomyResponse.ResponseType.SUCCESS, "")
            }
            whenever(economy.format(any<Double>())).doAnswer { String.format("%.2f", it.arguments[0] as Double) }
        }
    }

    private fun TowniaTestEnv.useBank(bank: Bank) {
        whenever(plugin.economy).thenReturn(bank.economy)
        whenever(plugin.hasEconomy()).thenReturn(true)
    }

    private fun TowniaTestEnv.standIn(x: Int, z: Int) {
        val chunk = mock<Chunk>()
        whenever(chunk.world).thenReturn(world)
        whenever(chunk.x).thenReturn(x)
        whenever(chunk.z).thenReturn(z)
        whenever(world.getChunkAt(any<Location>())).thenReturn(chunk)
    }

    @Test
    fun `founding claiming and buying bonus claims cost what Towny charged`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val bank = Bank()
            env.useBank(bank)
            env.config["town-creation-cost"] = 250.0
            env.config["claim-cost"] = 25.0
            env.config["economy.claim-cost-increase"] = 2.0
            env.config["economy.bonus-block-cost"] = 25.0
            env.towniaConfig.reload()
            val mayor = env.mockPlayer(UUID.randomUUID(), "Mayor")
            env.residentManager.getOrCreate(mayor)
            val cmd = TownCommand(env.plugin)
            fun run(vararg args: String): List<String> {
                env.sentMessages.clear()
                cmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf(*args))
                return env.sentMessages.map { it.key }
            }

            env.standIn(0, 0)
            bank.balances[mayor.uniqueId] = 100.0
            assertTrue("error.insufficient-funds" in run("new", "alpha"))
            assertTrue(env.townManager.getTownByName("alpha").isEmpty)
            assertEquals(100.0, bank.balances[mayor.uniqueId], "nothing is taken when the town cannot be founded")

            bank.balances[mayor.uniqueId] = 1000.0
            assertTrue("town.created" in run("new", "alpha"))
            assertEquals(750.0, bank.balances[mayor.uniqueId])
            val town = env.townManager.getTownByName("alpha").get()

            run("deposit", "500")
            assertEquals(250.0, bank.balances[mayor.uniqueId])
            assertEquals(500.0, town.balance)

            // The town holds one plot, so with a doubling price the next costs 25 x 2^1 and the one after 25 x 2^2.
            env.standIn(1, 0)
            assertTrue("town.claimed" in run("claim"))
            assertEquals(450.0, town.balance)
            env.standIn(2, 0)
            assertTrue("town.claimed" in run("claim"))
            assertEquals(350.0, town.balance)

            assertTrue("town.buy-done" in run("buy", "bonus", "2"))
            assertEquals(300.0, town.balance)
            assertEquals(2, town.bonusClaims)
            assertEquals(250.0, bank.balances[mayor.uniqueId], "claims and bonus claims are paid by the town, not the mayor")
        }
    }

    @Test
    fun `a new day takes taxes from residents and upkeep from the town`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val bank = Bank()
            env.useBank(bank)
            val mayor = env.mockPlayer(UUID.randomUUID(), "Mayor")
            val assistant = env.mockPlayer(UUID.randomUUID(), "Assistant")
            val payer = env.mockPlayer(UUID.randomUUID(), "Payer")
            val broke = env.mockPlayer(UUID.randomUUID(), "Broke")
            // Came over from Towny and has not logged in since: no account under this UUID.
            val absent = env.mockPlayer(UUID.randomUUID(), "Absent")
            listOf(mayor, assistant, payer, broke, absent).forEach { env.residentManager.getOrCreate(it) }
            val town = env.townManager.createTown("alpha", mayor.uniqueId)
            env.residentManager.setTown(assistant.uniqueId, town.id, TownRank.ASSISTANT)
            env.residentManager.setTown(payer.uniqueId, town.id, TownRank.RESIDENT)
            env.residentManager.setTown(broke.uniqueId, town.id, TownRank.RESIDENT)
            env.residentManager.setTown(absent.uniqueId, town.id, TownRank.RESIDENT)
            town.taxes = 5.0
            town.dailyUpkeep = 10.0
            town.balance = 100.0
            listOf(mayor, assistant, payer).forEach { bank.balances[it.uniqueId] = 50.0 }
            bank.balances[broke.uniqueId] = 2.0

            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                listOf(mayor, assistant, payer, broke, absent).forEach { p ->
                    bukkit.`when`<Any> { Bukkit.getOfflinePlayer(p.uniqueId) }.thenReturn(p)
                }
                DailyTask(env.plugin).run()
            }

            assertEquals(45.0, bank.balances[payer.uniqueId], "a resident pays the town tax")
            assertEquals(50.0, bank.balances[mayor.uniqueId], "the mayor does not")
            // Only the mayor is exempt, as in Townia 1.3.3 (Towny exempted assistants too).
            assertEquals(45.0, bank.balances[assistant.uniqueId], "an assistant pays like any resident")
            assertEquals(2.0, bank.balances[broke.uniqueId])
            assertEquals(null, env.residentManager.getResident(broke.uniqueId).get().townUuid, "who cannot pay is removed from the town")
            assertEquals(town.id, env.residentManager.getResident(absent.uniqueId).get().townUuid, "having no account at all is not being unable to pay")
            assertEquals(100.0, town.balance, "+5 tax from the resident and +5 from the assistant, -10 upkeep")
        }
    }

    @Test
    fun `dying costs the player and optionally the town`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val bank = Bank()
            env.useBank(bank)
            env.config["economy.death.price"] = 1.0
            env.config["economy.death.town-price"] = 20.0
            env.towniaConfig.reload()
            val player = env.mockPlayer(UUID.randomUUID(), "Unlucky")
            env.residentManager.getOrCreate(player)
            val town = env.townManager.createTown("alpha", player.uniqueId)
            town.balance = 15.0
            bank.balances[player.uniqueId] = 10.0

            val event = mock<PlayerDeathEvent>()
            whenever(event.entity).thenReturn(player)
            DeathCostListener(env.plugin).onPlayerDeath(event)

            assertEquals(9.0, bank.balances[player.uniqueId])
            assertEquals(0.0, town.balance, "a bank that holds less than the price loses what it has")

            // An arena is free of charge, and so is a death no other player caused when pvp-only is set.
            env.config["economy.death.pvp-only"] = true
            env.towniaConfig.reload()
            DeathCostListener(env.plugin).onPlayerDeath(event)
            assertEquals(9.0, bank.balances[player.uniqueId])
            val killer = mock<Player>()
            whenever(player.killer).thenReturn(killer)
            DeathCostListener(env.plugin).onPlayerDeath(event)
            assertEquals(8.0, bank.balances[player.uniqueId])
        }
    }
}
