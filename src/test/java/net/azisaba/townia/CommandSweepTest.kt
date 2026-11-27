package net.azisaba.townia

import net.azisaba.townia.command.CommandHelp
import net.azisaba.townia.command.InviteCommand
import net.azisaba.townia.command.NationCommand
import net.azisaba.townia.command.PlotCommand
import net.azisaba.townia.command.ResidentCommand
import net.azisaba.townia.command.TownCommand
import net.azisaba.townia.command.TownConfigCommand
import net.azisaba.townia.command.TowniaAdminCommand
import net.azisaba.townia.command.TowniaCommand
import net.azisaba.townia.command.TowniaWorldCommand
import net.azisaba.townia.data.TownRank
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Runs every subcommand the help lists, in every form its usage line allows, as a player without a
 * town, a resident, an assistant, a mayor and a king, standing in a resident's plot and in the
 * wilderness next to the town. Each run starts from the same small world.
 *
 * Fails on any exception and writes a table (build/sweep/sweep.tsv) of what each run answered,
 * for reading who was refused what. Takes minutes: runs only with TOWNIA_SWEEP=1.
 */
class CommandSweepTest {

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

    /** The small world every run starts from. */
    private class Scene(val env: TowniaTestEnv, spotX: Int) {
        val players = LinkedHashMap<String, Player>()
        val bank = Bank()
        val chunk: Chunk = mock()
        val spot = Location(env.world, spotX * 16 + 5.0, 64.0, 5.0)

        fun player(name: String): Player {
            val p = env.mockPlayer(UUID.randomUUID(), name, spot)
            env.residentManager.getOrCreate(p)
            players[name] = p
            bank.balances[p.uniqueId] = 1000.0
            whenever(env.server.getPlayerExact(name)).thenReturn(p)
            whenever(env.server.getPlayer(p.uniqueId)).thenReturn(p)
            whenever(env.server.getPlayer(name)).thenReturn(p)
            whenever(env.server.getOfflinePlayer(p.uniqueId)).thenReturn(p)
            return p
        }

        init {
            whenever(env.plugin.economy).thenReturn(bank.economy)
            whenever(env.plugin.hasEconomy()).thenReturn(true)
            for (n in listOf("Mayor", "Assistant", "Sub", "Rita", "Xeno", "King", "Yuri", "Queen", "Duke")) player(n)
            whenever(env.server.onlinePlayers).thenReturn(players.values.toList())

            val alpha = env.townManager.createTown("Alpha", players["Mayor"]!!.uniqueId).id!!
            env.residentManager.setTown(players["Mayor"]!!.uniqueId, alpha, TownRank.MAYOR)
            env.residentManager.setTown(players["Assistant"]!!.uniqueId, alpha, TownRank.ASSISTANT)
            env.residentManager.setTown(players["Rita"]!!.uniqueId, alpha, TownRank.RESIDENT)
            env.residentManager.setTown(players["Sub"]!!.uniqueId, alpha, TownRank.SUB_MAYOR)
            val beta = env.townManager.createTown("Beta", players["King"]!!.uniqueId).id!!
            env.residentManager.setTown(players["King"]!!.uniqueId, beta, TownRank.MAYOR)
            env.residentManager.setTown(players["Yuri"]!!.uniqueId, beta, TownRank.RESIDENT)
            val delta = env.townManager.createTown("Delta", players["Queen"]!!.uniqueId).id!!
            env.residentManager.setTown(players["Queen"]!!.uniqueId, delta, TownRank.MAYOR)
            env.nationManager.createNation("Norland", beta, players["King"]!!.uniqueId)
            env.nationManager.createNation("Southia", delta, players["Queen"]!!.uniqueId)
            // The mayor of a town in Norland that is not its capital.
            val epsilon = env.townManager.createTown("Epsilon", players["Duke"]!!.uniqueId).id!!
            env.residentManager.setTown(players["Duke"]!!.uniqueId, epsilon, TownRank.MAYOR)
            env.nationManager.addTownToNation(env.nationManager.getNationByName("Norland").get().id, epsilon)
            env.claimChunk(30, 0, epsilon)
            for (x in 0..2) env.claimChunk(x, 0, alpha)
            env.claimChunk(10, 0, beta)
            env.claimChunk(20, 0, delta)
            env.plotManager.transferOwnership("world", 1, 0, players["Rita"]!!.uniqueId)
            env.plotManager.getPlot("world", 2, 0).get().let {
                it.isForSale = true
                it.price = 10.0
                env.plotManager.persistPlot(it)
            }
            env.townManager.getTown(alpha).get().balance = 500.0
            env.residentManager.addFriend(env.residentManager.getResident(players["Rita"]!!.uniqueId).get(),
                env.residentManager.getResident(players["Xeno"]!!.uniqueId).get())

            // What a real server answers and the mocks would not.
            whenever(env.server.pluginManager).thenReturn(mock())
            val inventory = mock<org.bukkit.inventory.Inventory>()
            whenever(env.server.createInventory(anyOrNull(), any<Int>(), any<net.kyori.adventure.text.Component>())).thenReturn(inventory)
            val meta = mock<io.papermc.paper.plugin.configuration.PluginMeta>()
            whenever(meta.version).thenReturn("test")
            whenever(env.plugin.pluginMeta).thenReturn(meta)
            whenever(env.messageManager.miniMessage).thenReturn(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage())
            whenever(chunk.tileEntities).thenReturn(emptyArray())
            whenever(chunk.entities).thenReturn(emptyArray())
            whenever(chunk.world).thenReturn(env.world)
            whenever(chunk.x).thenReturn(spotX)
            whenever(chunk.z).thenReturn(0)
            whenever(env.world.getChunkAt(any<Location>())).thenReturn(chunk)
            whenever(env.world.getChunkAt(any<Int>(), any<Int>())).thenReturn(chunk)
        }

        fun <T> online(block: () -> T): T = Mockito.mockStatic(Bukkit::class.java) { inv ->
            val a = inv.arguments
            when (inv.method.name) {
                "getPlayer" -> if (a[0] is UUID) players.values.firstOrNull { it.uniqueId == a[0] } else players[a[0] as String]
                "getPlayerExact" -> players[a[0] as String]
                "getOfflinePlayer" -> if (a[0] is UUID) players.values.firstOrNull { it.uniqueId == a[0] } ?: mock<OfflinePlayer>() else players[a[0] as String] ?: mock<OfflinePlayer>()
                "getOnlinePlayers" -> players.values.toList()
                "getWorld" -> env.world
                "getWorlds" -> listOf(env.world)
                "getServer" -> env.server
                "getScheduler" -> env.scheduler
                "createInventory" -> mock<org.bukkit.inventory.Inventory>()
                "getPluginManager" -> env.server.pluginManager
                else -> Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        }.use { block() }
    }

    private class Run(val root: String, val args: List<String>, val who: String, val spot: Int, val answer: String, val error: Throwable?)

    @Test
    fun `every subcommand in every form, by every kind of player`() {
        assumeTrue(System.getenv("TOWNIA_SWEEP") == "1", "set TOWNIA_SWEEP=1 to run the sweep")
        val lang = YamlConfiguration.loadConfiguration(File("src/main/resources/messages/lang_en.yml"))
        val runs = ArrayList<Run>()
        for ((root, entries) in CommandHelp.allEntries()) {
            for (entry in entries) {
                // The menu builds item stacks, which need a running server: checked on the test server.
                if (root == "town" && entry == "menu") continue
                val usage = lang.getString("cmdhelp.$root.$entry.usage") ?: continue
                val forms = forms(usage, root).take(MAX_FORMS)
                val roles = if (root == "towniaadmin") listOf("Console", "Mayor") else listOf("Xeno", "Rita", "Assistant", "Sub", "Mayor", "Duke", "King")
                val spots = if (root in setOf("town", "plot", "resident", "towniaadmin")) listOf(1, 3) else listOf(1)
                for (form in forms) for (who in roles) for (spot in spots) runs.add(runOnce(root, form, who, spot))
            }
        }

        val out = File("build/sweep").apply { mkdirs() }
        File(out, "sweep.tsv").printWriter(Charsets.UTF_8).use { w ->
            w.println("command\twho\tspot\tanswer")
            for (r in runs) w.println("/${r.root} ${r.args.joinToString(" ")}\t${r.who}\t${r.spot}\t${r.error?.let { "EXCEPTION " + it }  ?: r.answer}")
        }
        val failures = runs.filter { it.error != null }
        println("runs: ${runs.size}, exceptions: ${failures.size}")
        failures.groupBy { it.error!!.javaClass.simpleName + " at " + (it.error.stackTrace.firstOrNull { f -> f.className.startsWith("net.azisaba") } ?: "?") }
            .forEach { (where, list) ->
                println("EXCEPTION $where  x${list.size}")
                list.take(3).forEach { println("    /${it.root} ${it.args.joinToString(" ")} as ${it.who} at chunk ${it.spot}: ${it.error}") }
            }
        assertTrue(failures.isEmpty(), "${failures.size} runs threw")
    }

    private fun runOnce(root: String, args: List<String>, who: String, spot: Int): Run {
        val dir = Files.createTempDirectory("sweep")
        try {
            TowniaTestEnv(dir).use { env ->
                val scene = Scene(env, spot)
                val sender: CommandSender = if (who == "Console") mock<ConsoleCommandSender>().also {
                    whenever(it.name).thenReturn("CONSOLE")
                } else scene.players[who]!!
                val cmd = command(root, env)
                var error: Throwable? = null
                scene.online {
                    try {
                        cmd.onCommand(sender, env.dummyCommand(root), root, args.toTypedArray())
                    } catch (t: Throwable) {
                        error = t
                    }
                }
                val keys = env.sentMessages.filter { it.recipient === sender }.map { it.key }.distinct()
                val shown = Mockito.mockingDetails(env.messageManager).invocations
                    .filter { it.method.name == "sendMessageWithoutPrefix" && it.arguments[0] === sender }
                    .map { it.arguments[1].toString() }.distinct()
                return Run(root, args, who, spot, (keys + shown).joinToString(",").ifEmpty { "-" }, error)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun command(root: String, env: TowniaTestEnv): CommandExecutor = when (root) {
        "town" -> TownCommand(env.plugin)
        "nation" -> NationCommand(env.plugin)
        "plot" -> PlotCommand(env.plugin)
        "resident" -> ResidentCommand(env.plugin)
        "townia" -> TowniaCommand(env.plugin)
        "towniaadmin" -> TowniaAdminCommand(env.plugin)
        "towniaworld" -> TowniaWorldCommand(env.plugin)
        "invite" -> InviteCommand(env.plugin)
        else -> TownConfigCommand(env.plugin)
    }

    // ---- usage lines to argument lists

    private sealed class Item {
        class Word(val text: String) : Item()
        class Group(val required: Boolean, val alternatives: List<List<Item>>) : Item()
    }

    private fun parse(text: String): List<Item> {
        var i = 0
        fun sequence(stop: Set<Char>): List<Item> {
            val items = ArrayList<Item>()
            val word = StringBuilder()
            fun flush() {
                if (word.isNotBlank()) items.add(Item.Word(word.toString().trim()))
                word.clear()
            }
            while (i < text.length && text[i] !in stop) {
                val c = text[i]
                when (c) {
                    '<', '[' -> {
                        flush()
                        val close = if (c == '<') '>' else ']'
                        i++
                        val alternatives = ArrayList<List<Item>>()
                        while (true) {
                            alternatives.add(sequence(setOf('|', close)))
                            if (i >= text.length || text[i] == close) break
                            i++ // past '|'
                        }
                        i++ // past the close
                        items.add(Item.Group(c == '<', alternatives))
                    }
                    ' ' -> {
                        flush()
                        i++
                    }
                    else -> {
                        word.append(c)
                        i++
                    }
                }
            }
            flush()
            return items
        }
        return sequence(emptySet())
    }

    private fun forms(usage: String, root: String): List<List<String>> {
        val items = parse(usage.trim().removePrefix("/").substringAfter(' ', ""))
        fun expand(seq: List<Item>, inChoice: Boolean): List<List<String>> {
            var acc = listOf(emptyList<String>())
            for (item in seq) {
                val options: List<List<String>> = when (item) {
                    is Item.Word -> if (item.text == "...") listOf(emptyList()) else listOf(listOf(
                        if (inChoice) value(item.text, usage, root) ?: item.text else item.text
                    ))
                    is Item.Group -> {
                        val single = item.alternatives.size == 1
                        val alts = item.alternatives.flatMap { alt ->
                            // <player> is a value; <pvp|mobs> are words to choose from, unless a word is a known value.
                            if (alt.size == 1 && alt[0] is Item.Word) {
                                val w = (alt[0] as Item.Word).text
                                val v = value(w, usage, root)
                                listOf(listOf(if (single || v != null && w !in KEYWORDS) v ?: w else w))
                            } else expand(alt, true)
                        }
                        if (item.required) alts else listOf(emptyList<String>()) + alts
                    }
                }
                acc = acc.flatMap { a -> options.map { a + it } }.take(MAX_FORMS * 4)
            }
            return acc
        }
        return expand(items, false).distinct()
    }

    private fun value(token: String, usage: String, root: String): String? {
        val t = token.lowercase()
        return when {
            t == "from name" -> "Rita"
            t == "into name" -> "Xeno"
            t == "uuid" -> null
            t in setOf("player", "resident") ->
                if (listOf("invite", "addresident", "moveresident", "friend", "mergeresident", "outlaw").any { it in usage }) "Xeno" else "Rita"
            t == "town" -> if (root == "towniaadmin") "Alpha" else "Beta"
            t == "nation" -> if ("ally" in usage || "enemy" in usage) "Southia" else "Norland"
            t in setOf("amount", "price") -> "10"
            t in setOf("count", "hours", "bail", "radius", "page", "number", "cell") -> "1"
            t in setOf("name", "new name") -> "Gamma"
            t in setOf("message", "title", "surname") -> "hello"
            t == "tag" -> "TAG"
            t == "rank" -> "assistant"
            t == "sort" -> "residents"
            t == "group" -> "friend"
            t == "action" -> "switch"
            t == "type" -> "shop"
            t == "world" -> "world"
            t == "node" -> "towny.command.town.claim"
            t in setOf("target", "options") -> "all"
            else -> null
        }
    }

    companion object {
        private const val MAX_FORMS = 12
        /** Words that are choices even though they also name a value elsewhere. */
        private val KEYWORDS = setOf("resident", "town", "nation", "player", "name", "tag")
    }
}
