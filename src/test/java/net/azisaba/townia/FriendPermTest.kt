package net.azisaba.townia

import net.azisaba.townia.command.PlotCommand
import net.azisaba.townia.command.ResidentCommand
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.listener.PlotProtectionListener
import net.azisaba.townia.listener.PlotProtectionListener.ActionType
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
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
import java.nio.file.Path
import java.util.UUID

/**
 * Friends, as Towny has them: the owner of a plot lets their friends in with
 * `/plot set perm friend ...`, and `/resident` lists them.
 */
class FriendPermTest {

    private class Fixture(val env: TowniaTestEnv) {
        val players = HashMap<UUID, Player>()
        val mayor = player("Mayor")
        val owner = player("Owner")
        val pal = player("Pal")
        val neighbour = player("Neighbour")
        val townId: UUID = env.townManager.createTown("alpha", mayor.uniqueId).id!!
        val chunk: Chunk = mock()
        val plotCmd: PlotCommand
        val listener = PlotProtectionListener(env.plugin)

        init {
            listOf(owner, neighbour).forEach { env.residentManager.setTown(it.uniqueId, townId, TownRank.RESIDENT) }
            env.claimChunk(0, 0, townId)   // Owner's plot
            env.claimChunk(1, 0, townId)   // a plot of the town
            env.plotManager.transferOwnership("world", 0, 0, owner.uniqueId)
            env.residentManager.addFriend(resident(owner), resident(pal))
            whenever(chunk.world).thenReturn(env.world)
            whenever(chunk.z).thenReturn(0)
            whenever(env.world.getChunkAt(any<Location>())).thenReturn(chunk)
            plotCmd = PlotCommand(env.plugin)
        }

        fun player(name: String): Player {
            val player = env.mockPlayer(UUID.randomUUID(), name)
            env.residentManager.getOrCreate(player)
            players[player.uniqueId] = player
            return player
        }

        fun resident(player: Player) = env.residentManager.getResident(player.uniqueId).get()

        fun plot(x: Int) = env.plotManager.getPlot("world", x, 0).get()

        fun <T> online(block: () -> T): T = Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            players.forEach { (id, p) ->
                bukkit.`when`<Any> { Bukkit.getPlayer(id) }.thenReturn(p)
                bukkit.`when`<Any> { Bukkit.getOfflinePlayer(id) }.thenReturn(p)
            }
            block()
        }

        /** Runs `/plot <args>` standing in the plot at chunk [x]; the keys of what the player was told. */
        fun run(who: Player, x: Int, vararg args: String): List<String> = online {
            whenever(chunk.x).thenReturn(x)
            env.sentMessages.clear()
            plotCmd.onCommand(who, env.dummyCommand("plot"), "plot", arrayOf(*args))
            env.sentMessages.filter { it.recipient === who }.map { it.key }
        }

        /** What a screen sent without the prefix (`/plot perm`, `/resident`) showed for [name]. */
        fun shown(key: String, name: String, block: () -> Unit): String {
            Mockito.clearInvocations(env.messageManager)
            online(block)
            val call = Mockito.mockingDetails(env.messageManager).invocations
                .first { it.method.name == "sendMessageWithoutPrefix" && it.arguments[1] == key }
            val raw = call.arguments.drop(2)
            val words = (if (raw.size == 1 && raw[0] is Array<*>) (raw[0] as Array<*>).toList() else raw).map { it?.toString() ?: "" }
            return words[words.indexOf(name) + 1]
        }

        fun switchShown(who: Player, x: Int): String = shown("plot.info", "perms_switch") {
            whenever(chunk.x).thenReturn(x)
            plotCmd.onCommand(who, env.dummyCommand("plot"), "plot", arrayOf("perm"))
        }

        fun maySwitch(who: Player, x: Int) = !listener.handleAction(who, Location(env.world, x * 16 + 5.0, 64.0, 5.0), ActionType.SWITCH)
    }

    @Test
    fun `the owner of a plot lets their friends use switches there`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            assertEquals("-----", f.switchShown(f.owner, 0))
            assertFalse(f.maySwitch(f.pal, 0))

            assertTrue("town.perm-set" in f.run(f.owner, 0, "set", "perm", "friend", "switch", "on"))
            assertEquals("F----", f.switchShown(f.owner, 0), "the F shows on the plot's switch line")
            assertTrue(f.maySwitch(f.pal, 0))
            assertFalse(f.maySwitch(f.neighbour, 0), "someone of the same town who is no friend stays out")

            f.run(f.owner, 0, "set", "perm", "friend", "switch", "off")
            assertEquals("-----", f.switchShown(f.owner, 0))
            assertFalse(f.maySwitch(f.pal, 0))

            // Towny's name for the owner's town on a resident's plot.
            f.run(f.owner, 0, "set", "perm", "town", "switch", "on")
            assertEquals("-R---", f.switchShown(f.owner, 0))
            assertTrue(f.maySwitch(f.neighbour, 0))
        }
    }

    @Test
    fun `on a plot of the town a friend setting is refused and nothing changes`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            val before = f.switchShown(f.mayor, 1)
            val stored = f.plot(1).let { listOf(it.permsFriend, it.permsResident, it.permsAlly, it.permsNation, it.permsOutsider) }
            val mayAlready = f.maySwitch(f.neighbour, 1)

            for (state in listOf("on", "off")) {
                val told = f.run(f.mayor, 1, "set", "perm", "friend", "switch", state)
                assertEquals(listOf("plot.perm-friend-town-plot"), told, "says why no F can appear, and changes nothing")
            }
            assertEquals(stored, f.plot(1).let { listOf(it.permsFriend, it.permsResident, it.permsAlly, it.permsNation, it.permsOutsider) })
            assertEquals(before, f.switchShown(f.mayor, 1))
            assertEquals(mayAlready, f.maySwitch(f.neighbour, 1), "the town's residents get nothing they did not have")

            // Setting every group at once leaves out the friends there, as before.
            assertTrue("town.perm-set" in f.run(f.mayor, 1, "set", "perm", "switch", "on"))
            assertNull(f.plot(1).permsFriend)
        }
    }

    @Test
    fun `a forgotten on or off changes nothing`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            f.run(f.owner, 0, "set", "perm", "friend", "switch", "on")
            val before = f.plot(0).let { listOf(it.permsFriend, it.permsResident, it.permsAlly, it.permsNation, it.permsOutsider) }

            // These used to be read as "off" and switched every permission of the plot off.
            for (args in listOf(listOf("friend"), listOf("friend", "switch"), listOf("switch"), listOf("friend", "switch", "onn"), listOf())) {
                assertEquals(listOf("plot.perm-usage"), f.run(f.owner, 0, "set", "perm", *args.toTypedArray()), "/plot set perm $args")
            }
            assertEquals(before, f.plot(0).let { listOf(it.permsFriend, it.permsResident, it.permsAlly, it.permsNation, it.permsOutsider) })
            assertTrue(f.maySwitch(f.pal, 0))
        }
    }

    @Test
    fun `the resident screen lists the friends by name`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            env.residentManager.addFriend(f.resident(f.owner), f.resident(f.neighbour))
            val cmd = ResidentCommand(env.plugin)
            fun friendsOf(who: Player, name: String) = f.shown("resident.info-friends", name) {
                cmd.onCommand(who, env.dummyCommand("resident"), "res", arrayOf())
            }
            assertEquals("2", friendsOf(f.owner, "count"))
            assertEquals("Neighbour, Pal", friendsOf(f.owner, "friends"))
            assertEquals("0", friendsOf(f.pal, "count"), "being someone's friend does not make them yours")
        }
    }
}
