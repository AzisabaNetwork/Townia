package net.azisaba.townia

import net.azisaba.townia.command.TownCommand
import net.azisaba.townia.command.TowniaAdminCommand
import net.azisaba.townia.data.Invite
import net.azisaba.townia.data.TownRank
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.UUID

/** The admin subcommands added since 1.3.3, and the town's sent invites and custom ranks. */
class AdminAndListsTest {

    private class World(val env: TowniaTestEnv) {
        val console: ConsoleCommandSender = mock()
        val admin: Player = player("Admin").also { whenever(it.isOp).thenReturn(true) }
        val mayor = player("Mayor")
        val bob = player("Bob")
        val king = player("King")
        val alpha = env.townManager.createTown("alpha", mayor.uniqueId).id!!
        val beta = env.townManager.createTown("beta", king.uniqueId).id!!
        val cmd = TowniaAdminCommand(env.plugin)

        init {
            env.residentManager.setTown(mayor.uniqueId, alpha, TownRank.MAYOR)
            env.residentManager.setTown(bob.uniqueId, alpha, TownRank.RESIDENT)
            env.residentManager.setTown(king.uniqueId, beta, TownRank.MAYOR)
            env.claimChunk(0, 0, alpha)
            val chunk = mock<Chunk>()
            whenever(chunk.world).thenReturn(env.world)
            whenever(chunk.x).thenReturn(0)
            whenever(chunk.z).thenReturn(0)
            whenever(env.world.getChunkAt(any<Location>())).thenReturn(chunk)
        }

        fun player(name: String): Player {
            val p = env.mockPlayer(UUID.randomUUID(), name, Location(env.world, 5.0, 64.0, 5.0))
            env.residentManager.getOrCreate(p)
            whenever(env.server.getPlayerExact(name)).thenReturn(p)
            return p
        }

        fun ta(who: CommandSender, vararg args: String): List<String> {
            env.sentMessages.clear()
            // Names nobody has a record of are looked up among the online players.
            Mockito.mockStatic(org.bukkit.Bukkit::class.java).use {
                cmd.onCommand(who, env.dummyCommand("towniaadmin"), "ta", arrayOf(*args))
            }
            return env.sentMessages.filter { it.recipient === who }.map { it.key }
        }

        fun replacements(key: String) = env.sentMessages.first { it.key == key }.replacements
    }

    @Test
    fun `an admin moves a nation's money and renames it`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val w = World(env)
            env.nationManager.createNation("north", w.beta, w.king.uniqueId)
            val north = env.nationManager.getNationByName("north").get()
            assertEquals(listOf("admin.bank-changed"), w.ta(w.console, "nation", "north", "deposit", "50"))
            assertEquals(50.0, north.balance)
            w.ta(w.console, "nation", "north", "withdraw", "20")
            assertEquals(30.0, north.balance)
            assertTrue(w.ta(w.console, "nation", "north", "withdraw", "500").none { it == "admin.bank-changed" })
            assertEquals(30.0, north.balance, "no more than the bank holds")
            assertEquals(listOf("error.invalid-amount"), w.ta(w.console, "nation", "north", "deposit", "-5"))
            assertEquals(listOf("error.invalid-args"), w.ta(w.console, "nation", "north", "rename"))
            assertEquals(listOf("admin.renamed"), w.ta(w.console, "nation", "north", "rename", "south"))
            assertTrue(env.nationManager.getNationByName("south").isPresent)
            assertEquals(listOf("error.nation-not-found"), w.ta(w.console, "nation", "nowhere", "deposit", "1"))
            assertEquals(listOf("error.invalid-args"), w.ta(w.console, "nation", "south", "explode"))
        }
    }

    @Test
    fun `an admin hands the plot they stand in to a player`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val w = World(env)
            assertEquals(listOf("admin.plot-given"), w.ta(w.admin, "plot", "claim", "Bob"))
            assertEquals(w.bob.uniqueId, env.plotManager.getPlot("world", 0, 0).get().ownerUuid)
            assertEquals(listOf("error.player-not-found"), w.ta(w.admin, "plot", "claim", "Nobody"))
            assertEquals(listOf("admin.plot-owner-usage"), w.ta(w.admin, "plot", "give", "Bob"))
            assertEquals(listOf("admin.plot-owner-usage"), w.ta(w.console, "plot", "claim", "Bob"), "the console stands nowhere")
            assertEquals(listOf("error.no-permission"), w.ta(w.mayor, "plot", "claim", "Bob"), "not for a mayor")
        }
    }

    @Test
    fun `an admin sets or clears the owner of any plot, from the console by coordinates`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val w = World(env)
            env.claimChunk(-1, 0, w.alpha)   // blocks x -16..-1, z 0..15
            fun owner(x: Int, z: Int) = env.plotManager.getPlot("world", x, z).get().ownerUuid

            // From the console, by block coordinates; negative ones round down to their chunk.
            assertEquals(listOf("admin.plot-given"), w.ta(w.console, "plot", "owner", "Bob", "world", "-5", "10"))
            assertEquals(w.bob.uniqueId, owner(-1, 0))
            // By UUID too.
            assertEquals(listOf("admin.plot-given"), w.ta(w.console, "plot", "owner", w.mayor.uniqueId.toString(), "world", "-16", "15"))
            assertEquals(w.mayor.uniqueId, owner(-1, 0))
            // Back to the town.
            assertEquals(listOf("admin.plot-owner-cleared"), w.ta(w.console, "plot", "owner", "none", "world", "-1", "0"))
            assertEquals(null, owner(-1, 0))

            // Someone outside the town can be made the owner, and the admin is told.
            assertEquals(listOf("admin.plot-given", "admin.plot-owner-not-member"), w.ta(w.admin, "plot", "owner", "King"))
            assertEquals(w.king.uniqueId, owner(0, 0))

            // A sale ends and the plot's own permissions go with the old owner.
            env.plotManager.getPlot("world", 0, 0).get().let {
                it.isForSale = true
                it.price = 30.0
                it.permsFriend = "BDSI"
                env.plotManager.persistPlot(it)
            }
            w.ta(w.admin, "plot", "owner", "Bob")
            env.plotManager.getPlot("world", 0, 0).get().let {
                assertEquals(false, it.isForSale)
                assertEquals(null, it.permsFriend)
            }

            assertEquals(listOf("plot.no-plot-here"), w.ta(w.console, "plot", "owner", "Bob", "world", "500", "500"))
            assertEquals(listOf("admin.plot-owner-usage"), w.ta(w.console, "plot", "owner", "Bob", "world", "x", "1"))
            assertEquals(listOf("admin.plot-owner-usage"), w.ta(w.console, "plot", "owner", "Bob", "world", "1"))

            env.databaseManager.flush()
            env.reloadManagers()
            assertEquals(w.bob.uniqueId, owner(0, 0), "and it is saved")
        }
    }

    @Test
    fun `an admin makes a town its nation's capital`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val w = World(env)
            env.nationManager.createNation("north", w.beta, w.king.uniqueId)
            val north = env.nationManager.getNationByName("north").get()
            assertEquals(listOf("town.not-in-nation"), w.ta(w.console, "set", "capital", "alpha"))
            env.nationManager.addTownToNation(north.id, w.alpha)
            assertEquals(listOf("nation.capital-set"), w.ta(w.console, "set", "capital", "alpha"))
            assertEquals(w.alpha, north.capitalTownUuid)
            assertEquals(w.mayor.uniqueId, north.leaderUuid, "the capital's mayor leads the nation")
            assertEquals(listOf("error.town-not-found"), w.ta(w.console, "set", "capital", "nowhere"))
            assertEquals(listOf("error.invalid-args"), w.ta(w.console, "set", "king", "alpha"))
        }
    }

    @Test
    fun `checkperm tells what an online player may do, townyperms included`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val w = World(env)
            w.ta(w.console, "checkperm", "Mayor", "towny.command.town.claim")
            assertEquals("true", w.replacements("admin.checkperm").let { it[it.indexOf("result") + 1] })
            w.ta(w.console, "checkperm", "Bob", "towny.command.town.claim")
            assertEquals("false", w.replacements("admin.checkperm").let { it[it.indexOf("result") + 1] })
            assertEquals(listOf("admin.diagnose-target-offline"), w.ta(w.console, "checkperm", "Ghost", "towny.command.town.claim"))
            assertEquals(listOf("error.invalid-args"), w.ta(w.console, "checkperm", "Mayor"))
        }
    }

    @Test
    fun `a town lists the invites it sent and the custom ranks of its residents`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val w = World(env)
            val carl = w.player("Carl")
            val dora = w.player("Dora")
            env.databaseManager.addInvite(Invite(0, carl.uniqueId, w.alpha, w.mayor.uniqueId, System.currentTimeMillis()))
            env.databaseManager.addInvite(Invite(0, dora.uniqueId, w.alpha, w.mayor.uniqueId, 1L))   // long expired
            val town = TownCommand(env.plugin)
            fun run(who: Player, vararg args: String): List<String> {
                env.sentMessages.clear()
                Mockito.clearInvocations(env.messageManager)
                town.onCommand(who, env.dummyCommand("town"), "town", arrayOf(*args))
                return env.sentMessages.filter { it.recipient === who }.map { it.key }
            }
            assertEquals(listOf("town.invites-sent"), run(w.mayor, "invite", "sent"))
            assertEquals("Carl", w.replacements("town.invites-sent").let { it[it.indexOf("players") + 1] })
            // towny.command.town.invite.sent is true by default in Towny: every resident may look.
            assertEquals(listOf("town.invites-sent"), run(w.bob, "invite", "sent"))

            env.residentManager.getResident(w.bob.uniqueId).get().townRanks.add("sheriff")
            run(w.mayor, "ranklist")
            val lines = Mockito.mockingDetails(env.messageManager).invocations
                .filter { it.method.name == "sendMessageWithoutPrefix" && it.arguments[1] == "town.ranklist-entry" }
                .map { inv -> (inv.arguments.drop(2).let { if (it.size == 1 && it[0] is Array<*>) (it[0] as Array<*>).toList() else it }).joinToString(" ") }
            assertTrue(lines.any { "sheriff" in it && "Bob" in it }, "$lines")
            assertTrue(lines.any { "MAYOR" in it && "Mayor" in it }, "$lines")
        }
    }
}
