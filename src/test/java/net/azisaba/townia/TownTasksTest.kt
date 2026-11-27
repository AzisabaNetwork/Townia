package net.azisaba.townia

import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.listener.PlayerJoinListener
import net.azisaba.townia.listener.PlayerMoveListener
import net.azisaba.townia.manager.ActionBarTask
import net.azisaba.townia.manager.DailyTask
import net.azisaba.townia.manager.TownTimers
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Cow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.UUID

/** The repeating tasks and the join, quit and move handling added since 1.3.3. */
class TownTasksTest {

    private fun TowniaTestEnv.player(name: String, chunkX: Int = 0): Player {
        val p = mockPlayer(UUID.randomUUID(), name, Location(world, chunkX * 16 + 5.0, 64.0, 5.0))
        residentManager.getOrCreate(p)
        return p
    }

    // ---- town timers

    @Test
    fun `the timers only run when switched on, and stop again`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val task = mock<BukkitTask>()
            whenever(env.scheduler.runTaskTimer(any<Plugin>(), any<Runnable>(), any<Long>(), any<Long>())).thenReturn(task)
            val timers = TownTimers(env.plugin)
            timers.restart()
            verify(env.scheduler, never()).runTaskTimer(any<Plugin>(), any<Runnable>(), any<Long>(), any<Long>())

            env.config["protection.health-regen.enabled"] = true
            env.config["protection.town-mob-removal.enabled"] = true
            env.towniaConfig.reload()
            timers.restart()
            verify(env.scheduler, times(2)).runTaskTimer(any<Plugin>(), any<Runnable>(), any<Long>(), any<Long>())
            timers.stop()
            verify(task, times(2)).cancel()
        }
    }

    @Test
    fun `hostile mobs are removed from towns with mobs off, and nothing else is`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            env.config["protection.town-mob-removal.enabled"] = true
            env.towniaConfig.reload()
            val mayor = env.player("Mayor")
            val alpha = env.townManager.createTown("alpha", mayor.uniqueId).id!!
            env.claimChunk(0, 0, alpha)
            env.claimChunk(1, 0, alpha)
            env.plotManager.getPlot("world", 1, 0).get().let {
                it.setMobs(true)
                env.plotManager.persistPlot(it)
            }
            fun <T : LivingEntity> mob(type: Class<T>, chunkX: Int): T {
                val e = Mockito.mock(type)
                whenever(e.location).thenReturn(Location(env.world, chunkX * 16 + 5.0, 64.0, 5.0))
                return e
            }
            val inTown = mob(Zombie::class.java, 0)
            val inMobPlot = mob(Zombie::class.java, 1)
            val outside = mob(Zombie::class.java, 5)
            val cow = mob(Cow::class.java, 0)
            whenever(env.world.getEntitiesByClass(LivingEntity::class.java)).thenReturn(listOf(inTown, inMobPlot, outside, cow))
            whenever(env.server.worlds).thenReturn(listOf(env.world))
            val run = argumentCaptor<Runnable>()
            whenever(env.scheduler.runTaskTimer(any<Plugin>(), run.capture(), any<Long>(), any<Long>())).thenReturn(mock())
            TownTimers(env.plugin).restart()
            run.firstValue.run()
            verify(inTown).remove()
            verify(inMobPlot, never()).remove()
            verify(outside, never()).remove()
            verify(cow, never()).remove()
        }
    }

    // ---- plot taxes

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
                val now = (balances[id(it.arguments[0])] ?: 0.0) + it.arguments[1] as Double
                balances[id(it.arguments[0])] = now
                EconomyResponse(it.arguments[1] as Double, now, EconomyResponse.ResponseType.SUCCESS, "")
            }
            whenever(economy.format(any<Double>())).doAnswer { String.format("%.2f", it.arguments[0] as Double) }
        }
    }

    @Test
    fun `plot owners pay the plot tax, the shop tax on shops, and lose a plot they cannot pay for`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val bank = Bank()
            whenever(env.plugin.economy).thenReturn(bank.economy)
            whenever(env.plugin.hasEconomy()).thenReturn(true)
            val mayor = env.player("Mayor")
            val payer = env.player("Payer")
            val broke = env.player("Broke")
            val absent = env.player("Absent")
            val town = env.townManager.createTown("alpha", mayor.uniqueId)
            for (p in listOf(payer, broke, absent)) env.residentManager.setTown(p.uniqueId, town.id, TownRank.RESIDENT)
            for (x in 0..3) env.claimChunk(x, 0, town.id!!)
            env.plotManager.transferOwnership("world", 0, 0, payer.uniqueId)
            env.plotManager.transferOwnership("world", 1, 0, payer.uniqueId)
            env.plotManager.getPlot("world", 1, 0).get().let {
                it.plotType = PlotType.SHOP
                env.plotManager.persistPlot(it)
            }
            env.plotManager.transferOwnership("world", 2, 0, broke.uniqueId)
            env.plotManager.transferOwnership("world", 3, 0, absent.uniqueId)
            town.plotTax = 5.0
            town.shopTax = 20.0
            town.balance = 0.0
            env.townManager.saveTown(town)
            bank.balances[mayor.uniqueId] = 0.0
            bank.balances[payer.uniqueId] = 100.0
            bank.balances[broke.uniqueId] = 1.0
            // Absent has no account (came over from Towny and never joined since): skipped, keeps the plot.

            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                for (p in listOf(mayor, payer, broke, absent)) bukkit.`when`<Any> { Bukkit.getOfflinePlayer(p.uniqueId) }.thenReturn(p)
                DailyTask(env.plugin).run()
            }
            assertEquals(75.0, bank.balances[payer.uniqueId]!!, 0.001, "5 for the plot and 20 for the shop")
            assertEquals(25.0, env.townManager.getTown(town.id).get().balance, 0.001)
            assertNull(env.plotManager.getPlot("world", 2, 0).get().ownerUuid, "a plot that is not paid for goes back to the town")
            assertEquals(absent.uniqueId, env.plotManager.getPlot("world", 3, 0).get().ownerUuid)
            assertEquals(payer.uniqueId, env.plotManager.getPlot("world", 1, 0).get().ownerUuid)
        }
    }

    // ---- the action bar

    @Test
    fun `the action bar names the land, and only for as long as configured`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            whenever(env.messageManager.miniMessage).thenReturn(MiniMessage.miniMessage())
            ActionBarTask.clearCache()
            val mayor = env.player("Mayor")
            val owner = env.player("Owner")
            val town = env.townManager.createTown("alpha", mayor.uniqueId)
            env.residentManager.setTown(owner.uniqueId, town.id, TownRank.RESIDENT)
            env.claimChunk(0, 0, town.id!!)
            env.claimChunk(1, 0, town.id!!)
            env.plotManager.transferOwnership("world", 1, 0, owner.uniqueId)
            fun bar(chunkX: Int): String? {
                Mockito.clearInvocations(owner)
                ActionBarTask.sendActionBar(env.plugin, owner, Location(env.world, chunkX * 16 + 5.0, 64.0, 5.0))
                val sent = argumentCaptor<Component>()
                return runCatching { verify(owner).sendActionBar(sent.capture()); MiniMessage.miniMessage().serialize(sent.firstValue) }.getOrNull()
            }
            assertTrue(bar(0)!!.contains("alpha"))
            assertTrue(bar(1)!!.contains("Owner"))
            assertTrue(bar(5)!!.contains("town.actionbar-wilderness-name"))
            val nether = mock<org.bukkit.World>()
            whenever(nether.name).thenReturn("world_nether")
            Mockito.clearInvocations(owner)
            ActionBarTask.sendActionBar(env.plugin, owner, Location(nether, 5.0, 64.0, 5.0))
            verify(owner, never()).sendActionBar(any<Component>())

            // Shown again by the task until it expires; with a duration of 0 it is rebuilt every time.
            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Any> { Bukkit.getOnlinePlayers() }.thenReturn(listOf(owner))
                bukkit.`when`<Any> { Bukkit.getPlayer(owner.uniqueId) }.thenReturn(owner)
                env.config["actionbar-duration"] = 5
                env.towniaConfig.reload()
                ActionBarTask.sendActionBar(env.plugin, owner, Location(env.world, 5.0, 64.0, 5.0))
                Mockito.clearInvocations(owner)
                ActionBarTask(env.plugin).run()
                verify(owner).sendActionBar(any<Component>())
                ActionBarTask.forget(owner.uniqueId)
                Mockito.clearInvocations(owner)
                ActionBarTask(env.plugin).run()
                verify(owner, never()).sendActionBar(any<Component>())
            }
        }
    }

    // ---- join, quit and moving

    @Test
    fun `boards are shown at login when switched on, and a quit forgets the player`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            env.config["board-on-login"] = true
            env.towniaConfig.reload()
            val mayor = env.player("Mayor")
            val town = env.townManager.createTown("alpha", mayor.uniqueId)
            town.board = "welcome home"
            env.townManager.saveTown(town)
            env.nationManager.createNation("north", town.id, mayor.uniqueId)
            env.nationManager.getNationByName("north").get().board = "for the north"
            val listener = PlayerJoinListener(env.plugin)
            val join = mock<org.bukkit.event.player.PlayerJoinEvent>()
            whenever(join.player).thenReturn(mayor)
            Mockito.mockStatic(Bukkit::class.java).use { listener.onPlayerJoin(join) }
            val keys = env.sentMessages.filter { it.recipient === mayor }.map { it.key }
            assertTrue("town.board-login" in keys, "$keys")
            assertTrue("nation.board-login" in keys, "$keys")

            val quit = mock<PlayerQuitEvent>()
            whenever(quit.player).thenReturn(mayor)
            listener.onPlayerQuit(quit)   // forgets the action bar and the granted nodes; must not throw
        }
    }

    @Test
    fun `a jailed player is let go when the jail's town is gone, and held while it stands`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayor = env.player("Mayor")
            val prisoner = env.player("Prisoner")
            val town = env.townManager.createTown("alpha", mayor.uniqueId)
            env.claimChunk(0, 0, town.id!!)
            val res = env.residentManager.getResident(prisoner.uniqueId).get()
            res.jailedTownUuid = town.id
            res.jailReleaseAt = System.currentTimeMillis() + 60_000
            env.residentManager.saveResident(res)
            val listener = PlayerMoveListener(env.plugin)
            fun move(fromX: Int, toX: Int): PlayerMoveEvent = mock<PlayerMoveEvent>().also {
                whenever(it.player).thenReturn(prisoner)
                whenever(it.from).thenReturn(Location(env.world, fromX * 16 + 5.0, 64.0, 5.0))
                whenever(it.to).thenReturn(Location(env.world, toX * 16 + 5.0, 64.0, 5.0))
                listener.onPlayerMove(it)
            }
            verify(move(0, 1)).isCancelled = true
            assertTrue(env.sentMessages.any { it.key == "jail.cannot-leave" })

            // Deleting the town frees its prisoners at once.
            env.townManager.deleteTown(town.id)
            assertNull(env.residentManager.getResident(prisoner.uniqueId).get().jailedTownUuid)
            verify(move(0, 1), never()).isCancelled = true

            // A record that still names the jail of a town that is gone is let go on the next step.
            res.jailedTownUuid = UUID.randomUUID()
            res.jailReleaseAt = System.currentTimeMillis() + 60_000
            env.sentMessages.clear()
            verify(move(0, 1), never()).isCancelled = true
            assertTrue(env.sentMessages.any { it.key == "jail.released" })
            assertNull(env.residentManager.getResident(prisoner.uniqueId).get().jailedTownUuid)

            // So is one whose time is up.
            val other = env.townManager.createTown("beta", env.player("Mayor2").uniqueId)
            res.jailedTownUuid = other.id
            res.jailReleaseAt = System.currentTimeMillis() - 1
            env.sentMessages.clear()
            verify(move(0, 1), never()).isCancelled = true
            assertTrue(env.sentMessages.any { it.key == "jail.released" })
        }
    }

    // ---- where spawn travel is refused

    @Test
    fun `spawn travel is refused from the places the config names`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            env.config["spawn.prevent-in"] = listOf("unclaimed", "outlaw")
            env.towniaConfig.reload()
            val mayor = env.player("Mayor", 0)
            val traveller = env.player("Traveller", 5)
            val town = env.townManager.createTown("alpha", mayor.uniqueId)
            env.claimChunk(0, 0, town.id!!)
            var arrived = 0
            fun go(): List<String> {
                env.sentMessages.clear()
                net.azisaba.townia.manager.SpawnTravel.go(env.plugin, traveller, net.azisaba.townia.manager.SpawnTravel.Kind.PUBLIC, town,
                    Location(env.world, 5.0, 64.0, 5.0)) { arrived++ }
                return env.sentMessages.map { it.key }
            }
            assertEquals(listOf("spawn.prevented-here"), go(), "in the wilderness")
            whenever(traveller.location).thenReturn(Location(env.world, 5.0, 64.0, 5.0))
            town.outlaws.add(traveller.uniqueId)
            assertEquals(listOf("spawn.prevented-here"), go(), "an outlaw standing in the town")
            town.outlaws.remove(traveller.uniqueId)
            go()
            assertEquals(1, arrived, "from inside the town it goes")
        }
    }
}
