package net.azisaba.townia

import net.azisaba.townia.command.NationCommand
import net.azisaba.townia.command.PlotCommand
import net.azisaba.townia.command.TownCommand
import net.azisaba.townia.command.TowniaAdminCommand
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.migration.TownyFlatfileMigrator
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Path
import java.util.UUID

/** The commands Towny 0.96 had and Townia lacked. */
class TownyCommandsTest {

    private class World(val env: TowniaTestEnv) {
        val players = HashMap<UUID, Player>()

        fun player(name: String): Player {
            val player = env.mockPlayer(UUID.randomUUID(), name)
            env.residentManager.getOrCreate(player)
            players[player.uniqueId] = player
            return player
        }

        /** Runs [block] with Bukkit.getPlayer(uuid) answering for the players made here. */
        fun <T> online(block: () -> T): T = Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            players.forEach { (id, p) -> bukkit.`when`<Any> { Bukkit.getPlayer(id) }.thenReturn(p) }
            bukkit.`when`<Any> { Bukkit.getWorld("world") }.thenReturn(env.world)
            block()
        }

        fun keys(who: Any): List<String> = env.sentMessages.filter { it.recipient === who }.map { it.key }
    }

    @Test
    fun `town settings Towny had can be set and survive a restart`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val w = World(env)
            val mayor = w.player("Mayor")
            val member = w.player("Member")
            val townId = env.townManager.createTown("alpha", mayor.uniqueId).id!!
            env.residentManager.setTown(member.uniqueId, townId, TownRank.RESIDENT)
            var cmd = TownCommand(env.plugin)
            fun run(who: Player, vararg args: String): List<String> = w.online {
                env.sentMessages.clear()
                cmd.onCommand(who, env.dummyCommand("town"), "town", arrayOf(*args))
                w.keys(who)
            }

            assertTrue("town.not-mayor" in run(member, "set", "tag", "ABC"))
            assertTrue("town.tag-invalid" in run(mayor, "set", "tag", "TOOLONG"))
            assertTrue("town.tag-set" in run(mayor, "set", "tag", "ALP"))
            assertTrue("town.value-set" in run(mayor, "set", "plottax", "7"))
            assertTrue("town.value-set" in run(mayor, "set", "shoptax", "9"))
            assertTrue("town.value-set" in run(mayor, "set", "embassyprice", "300"))
            assertTrue("error.invalid-amount" in run(mayor, "set", "shopprice", "-1"))
            // Nothing is configured as the server's fare, so a town cannot ask for one either.
            assertTrue("town.taxes-too-high" in run(mayor, "set", "spawncost", "5"))
            run(mayor, "toggle", "taxpercent")

            env.databaseManager.flush()
            env.reloadManagers()
            cmd = TownCommand(env.plugin) // commands keep the managers they were built with
            val town = env.townManager.getTown(townId).get()
            assertEquals("ALP", town.tag)
            assertEquals(7.0, town.plotTax)
            assertEquals(9.0, town.shopTax)
            assertEquals(300.0, town.embassyPrice)
            assertTrue(town.isTaxPercent, "the tax mode used to be forgotten on restart")
            assertEquals(-1.0, town.spawnCost)

            // The mayor hands the town over.
            assertTrue("error.player-not-same-town" in run(mayor, "set", "mayor", "Nobody"))
            run(mayor, "set", "mayor", "Member")
            assertEquals(member.uniqueId, env.townManager.getTown(townId).get().mayorUuid)
            assertEquals(TownRank.MAYOR, env.residentManager.getResident(member.uniqueId).get().rank)
            assertEquals(TownRank.RESIDENT, env.residentManager.getResident(mayor.uniqueId).get().rank)
            assertTrue("town.mayor-changed" in w.keys(member))
            assertTrue("town.not-mayor" in run(mayor, "set", "tag", "XYZ"), "the old mayor is an ordinary member now")
        }
    }

    @Test
    fun `bonus claims are bought from the town bank and say reaches the town`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val w = World(env)
            val mayor = w.player("Mayor")
            val member = w.player("Member")
            val outsider = w.player("Outsider")
            val townId = env.townManager.createTown("alpha", mayor.uniqueId).id!!
            env.residentManager.setTown(member.uniqueId, townId, TownRank.RESIDENT)
            val cmd = TownCommand(env.plugin)
            fun run(who: Player, vararg args: String): List<String> = w.online {
                env.sentMessages.clear()
                cmd.onCommand(who, env.dummyCommand("town"), "town", arrayOf(*args))
                w.keys(who)
            }

            assertTrue("town.buy-disabled" in run(mayor, "buy", "bonus", "2"))
            env.config["economy.bonus-block-cost"] = 25.0
            env.config["max-bonus-claims"] = 3
            env.towniaConfig.reload()
            val town = env.townManager.getTown(townId).get()
            assertTrue("town.claim-insufficient-funds" in run(mayor, "buy", "bonus", "2"))
            town.balance = 100.0
            assertTrue("error.no-permission" in run(member, "buy", "bonus", "1"))
            assertTrue("town.buy-done" in run(mayor, "buy", "bonus", "2"))
            assertEquals(50.0, town.balance)
            assertEquals(2, town.bonusClaims)
            assertTrue("town.buy-limit" in run(mayor, "buy", "bonus", "2"))

            run(mayor, "say", "hello", "<red>all")
            assertTrue("town.say" in w.keys(member))
            assertFalse("town.say" in w.keys(outsider))
            assertTrue("error.no-permission" in run(member, "say", "hi"), "town.say is the mayor's by default")
        }
    }

    @Test
    fun `nations can be open and alliances can need consent`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val w = World(env)
            val kingA = w.player("KingA")
            val kingB = w.player("KingB")
            val mayorC = w.player("MayorC")
            val townA = env.townManager.createTown("alpha", kingA.uniqueId).id!!
            val townB = env.townManager.createTown("beta", kingB.uniqueId).id!!
            val townC = env.townManager.createTown("gamma", mayorC.uniqueId).id!!
            env.nationManager.createNation("Aland", townA, kingA.uniqueId)
            env.nationManager.createNation("Bland", townB, kingB.uniqueId)
            val aland = env.nationManager.getNationByName("Aland").get()
            val bland = env.nationManager.getNationByName("Bland").get()
            var cmd = NationCommand(env.plugin)
            fun run(who: Player, vararg args: String): List<String> = w.online {
                env.sentMessages.clear()
                cmd.onCommand(who, env.dummyCommand("nation"), "nation", arrayOf(*args))
                w.keys(who)
            }

            // Not open: a town cannot walk in. Open: it can.
            assertTrue("invite.no-pending" in run(mayorC, "join", "Aland"))
            assertTrue("nation.toggled" in run(kingA, "toggle", "open"))
            assertTrue("nation.toggled" in run(kingA, "toggle", "public"))
            assertTrue("nation.tag-set" in run(kingA, "set", "tag", "AL"))
            assertTrue("nation.joined" in run(mayorC, "join", "Aland"))
            assertEquals(aland.id, env.townManager.getTown(townC).get().nationUuid)

            env.databaseManager.flush()
            env.reloadManagers()
            cmd = NationCommand(env.plugin) // commands keep the managers they were built with
            val reloaded = env.nationManager.getNationByName("Aland").get()
            assertTrue(reloaded.isOpen)
            assertTrue(reloaded.isPublic)
            assertEquals("AL", reloaded.tag)

            // Without the rule an alliance is one-sided and immediate, as Townia always did.
            val a = env.nationManager.getNationByName("Aland").get()
            val b = env.nationManager.getNationByName("Bland").get()
            assertTrue("nation.relation-added" in run(kingA, "ally", "add", "Bland"))
            assertTrue(a.allies.contains(b.id))
            assertFalse(b.allies.contains(a.id))
            run(kingA, "ally", "remove", "Bland")

            env.config["nation.require-ally-approval"] = true
            env.towniaConfig.reload()
            assertTrue("nation.ally-request-sent" in run(kingA, "ally", "add", "Bland"))
            assertTrue("nation.ally-request-received" in w.keys(kingB))
            assertFalse(a.allies.contains(b.id), "nothing happens until the other side agrees")
            assertTrue("nation.ally-requests-received" in run(kingB, "ally", "received"))
            assertTrue("nation.ally-no-request" in run(kingA, "ally", "accept", "Bland"))
            assertTrue("nation.relation-added" in run(kingB, "ally", "accept", "Aland"))
            assertTrue(a.allies.contains(b.id))
            assertTrue(b.allies.contains(a.id))
            assertTrue("nation.allylist" in run(mayorC, "allylist"))
            run(kingB, "ally", "remove", "Aland")
            assertFalse(a.allies.contains(b.id))
            assertFalse(b.allies.contains(a.id))
            run(kingA, "say", "hi")
            assertTrue("nation.say" in w.keys(mayorC))
        }
    }

    @Test
    fun `an owner can hand a plot back and admins can move bank money`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val w = World(env)
            val mayor = w.player("Mayor")
            val owner = w.player("Owner")
            val other = w.player("Other")
            val townId = env.townManager.createTown("alpha", mayor.uniqueId).id!!
            env.residentManager.setTown(owner.uniqueId, townId, TownRank.RESIDENT)
            env.residentManager.setTown(other.uniqueId, townId, TownRank.RESIDENT)
            env.claimChunk(0, 0, townId)
            env.plotManager.transferOwnership("world", 0, 0, owner.uniqueId)
            val chunk = mock<Chunk>()
            whenever(chunk.world).thenReturn(env.world)
            whenever(chunk.x).thenReturn(0)
            whenever(chunk.z).thenReturn(0)
            whenever(env.world.getChunkAt(any<Location>())).thenReturn(chunk)

            val plotCmd = PlotCommand(env.plugin)
            fun plot(who: Player, vararg args: String): List<String> = w.online {
                env.sentMessages.clear()
                plotCmd.onCommand(who, env.dummyCommand("plot"), "plot", arrayOf(*args))
                w.keys(who)
            }
            assertTrue("error.no-permission" in plot(other, "unclaim"), "not their plot")
            assertEquals(owner.uniqueId, env.plotManager.getPlot("world", 0, 0).get().ownerUuid)
            assertTrue("plot.returned" in plot(owner, "unclaim"))
            assertNull(env.plotManager.getPlot("world", 0, 0).get().ownerUuid)
            assertTrue("plot.not-owned" in plot(owner, "unclaim"))

            val console = mock<ConsoleCommandSender>()
            val admin = TowniaAdminCommand(env.plugin)
            admin.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("town", "alpha", "deposit", "150"))
            assertEquals(150.0, env.townManager.getTown(townId).get().balance)
            admin.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("town", "alpha", "withdraw", "50"))
            assertEquals(100.0, env.townManager.getTown(townId).get().balance)
            admin.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("town", "alpha", "rank", "add", "Other", "sheriff"))
            assertTrue("sheriff" in env.residentManager.getResident(other.uniqueId).get().townRanks)
            admin.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("town", "alpha", "rename", "omega"))
            assertTrue(env.townManager.getTownByName("omega").isPresent)
            env.sentMessages.clear()
            admin.onCommand(console, env.dummyCommand("towniaadmin"), "ta", arrayOf("newday"))
            assertTrue(env.sentMessages.any { it.key == "admin.newday-done" })
        }
    }

    @Test
    fun `plot types Towny 0_96 stored as numbers are kept`(@TempDir tempDir: Path) {
        val data = File(tempDir.toFile(), "Towny/data")
        fun write(path: String, text: String) {
            val file = File(data, path)
            file.parentFile.mkdirs()
            file.writeText(text.trimIndent() + "\n")
        }
        write("towns/Alpha.txt", "name=Alpha\nresidents=Ann\nmayor=Ann\nuuid=11111111-1111-1111-1111-111111111111")
        write("residents/Ann.txt", "town=Alpha\nfriends=")
        listOf(0 to "0", 1 to "1", 2 to "2", 3 to "3", 4 to "4", 5 to "6", 6 to "7", 7 to "8", 8 to "9").forEach { (x, type) ->
            write("townblocks/world/${x}_0_16.data", "town=Alpha\ntype=$type\nprice=-1.0\nchanged=false")
        }
        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            Mockito.mockStatic(Bukkit::class.java).use {
                TownyFlatfileMigrator.migrateOnly(env.plugin, mock<ConsoleCommandSender>(), "Alpha", null, false, false)
            }
            fun type(x: Int) = env.plotManager.getPlot("world", x, 0).get().plotType
            assertEquals(
                listOf(
                    PlotType.DEFAULT, PlotType.SHOP, PlotType.ARENA, PlotType.EMBASSY, PlotType.WILDS,
                    PlotType.INN, PlotType.DEFAULT, PlotType.FARM, PlotType.BANK
                ),
                (0..8).map(::type)
            )
        }
    }

    @Test
    fun `leaving a town works and the town is told`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val w = World(env)
            val mayor = w.player("Mayor")
            val member = w.player("Member")
            val townId = env.townManager.createTown("alpha", mayor.uniqueId).id!!
            env.residentManager.setTown(member.uniqueId, townId, TownRank.RESIDENT)
            val cmd = TownCommand(env.plugin)

            w.online { cmd.onCommand(member, env.dummyCommand("town"), "town", arrayOf("leave")) }

            assertNull(env.residentManager.getResident(member.uniqueId).get().townUuid)
            assertTrue("town.left" in w.keys(member))
            assertTrue("town.left-broadcast" in w.keys(mayor), "those who stay are told; this used to end in an exception")
        }
    }
}
