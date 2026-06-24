package net.azisaba.townia.command

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TowniaPlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.util.StringUtil
import java.util.*
import java.util.function.ToIntFunction
import kotlin.Array
import kotlin.Boolean
import kotlin.Comparator
import kotlin.Double
import kotlin.Int
import kotlin.Long
import kotlin.math.max
import kotlin.text.StringBuilder
import kotlin.text.equals
import kotlin.text.format
import kotlin.text.lowercase
import kotlin.unaryMinus

class TowniaCommand(private val plugin: Townia) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].toString().lowercase()) {
            "reload" -> {
                if (!sender.hasPermission("townia.admin.reload")) {
                    plugin.messageManager.sendMessage(sender, "error.no-permission")
                    return true
                }
                plugin.towniaConfig.reload()
                plugin.messageManager.loadAllMessages()
                plugin.messageManager.sendMessage(sender, "admin.reloaded")
            }

            "info" -> {
                val version = plugin.getDescription().version
                val authors = java.lang.String.join(", ", plugin.getDescription().authors)
                plugin.messageManager.sendMessage(
                    sender, "admin.help",
                    "version", version,
                    "authors", authors
                )
            }

            "map" -> {
                val player = requirePlayer(sender)
                if (player != null) {
                    sendMap(player)
                }
            }

            "price" -> handlePrice(sender)
            "time" -> handleTime(sender)
            "top" -> handleTop(sender, args)
            "?" -> sendHelp(sender)
            else -> sendHelp(sender)
        }
        return true
    }

    private fun sendHelp(sender: CommandSender) {
        plugin.messageManager.sendMessage(sender, "townia.help")
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        val completions = mutableListOf<String>()
        if (args.size == 1) {
            val options = mutableListOf("map", "price", "time", "top", "?", "info")
            if (sender.hasPermission("townia.admin.reload")) options.add("reload")
            StringUtil.copyPartialMatches(args[0].toString(), options, completions)
        } else if (args.size == 2 && args[0].equals("top", ignoreCase = true)) {
            StringUtil.copyPartialMatches(args[1].toString(), listOf("residents", "land"), completions)
        } else if (args.size == 3 && args[0].equals("top", ignoreCase = true)) {
            StringUtil.copyPartialMatches(args[2].toString(), listOf("all", "town", "nation", "resident"), completions)
        }
        return completions
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender !is Player) {
            plugin.messageManager.sendMessage(sender, "error.player-only")
            return null
        }
        return sender
    }

    private fun handlePrice(sender: CommandSender) {
        val townCreate: Double = plugin.towniaConfig.townCreationCost
        val claimCost: Double = plugin.towniaConfig.claimCost
        val nationCreate: Double = plugin.towniaConfig.nationCreationCost
        plugin.messageManager.sendMessage(
            sender, "townia.price",
            "town_create", (formatMoney(townCreate) ?: ""),
            "claim", (formatMoney(claimCost) ?: ""),
            "nation_create", (formatMoney(nationCreate) ?: "")
        )
    }

    private fun handleTime(sender: CommandSender) {
        val now = System.currentTimeMillis()
        val nextMs: Long = plugin.nextUpkeepTime
        val diffMs = max(0, nextMs - now)
        val hours = diffMs / 3600000
        val mins = (diffMs % 3600000) / 60000
        plugin.messageManager.sendMessage(
            sender, "townia.time",
            "hours", hours.toString(), "minutes", mins.toString()
        )
    }

    private fun handleTop(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val category = args[1].toString().lowercase()
        val scope = args[2].toString().lowercase()

        when (category) {
            "residents" -> handleTopResidents(sender, scope)
            "land", "lands" -> handleTopLand(sender, scope)
            else -> plugin.messageManager.sendMessage(sender, "error.invalid-args")
        }
    }

    private fun handleTopResidents(sender: CommandSender, scope: kotlin.String) {
        val towns: MutableList<Town> = plugin.townManager.allTowns
        val nations: MutableList<Nation> = plugin.nationManager.allNations

        when (scope) {
            "all" -> {
                plugin.messageManager.sendMessage(sender, "townia.top-header", "type", "住民数 (全佁E")
                towns.stream()
                    .sorted(
                        Comparator.comparingInt(
                            ToIntFunction { t: Town? ->
                                -plugin.residentManager.getResidentsByTown(t!!.id!!).size
                            })
                    )
                    .limit(10)
                    .forEach { t: Town? ->
                        val cnt: Int = plugin.residentManager.getResidentsByTown(t!!.id!!).size
                        plugin.messageManager.sendMessage(
                            sender, "townia.top-entry-town",
                            "name", t.name!!, "count", cnt.toString()
                        )
                    }
            }

            "town" -> {
                plugin.messageManager.sendMessage(sender, "townia.top-header", "type", "住民数 (町)")
                towns.stream()
                    .sorted(
                        Comparator.comparingInt(
                            ToIntFunction { t: Town? ->
                                -plugin.residentManager.getResidentsByTown(t!!.id!!).size
                            })
                    )
                    .limit(10)
                    .forEach { t: Town? ->
                        val cnt: Int = plugin.residentManager.getResidentsByTown(t!!.id!!).size
                        plugin.messageManager.sendMessage(
                            sender, "townia.top-entry-town",
                            "name", t.name!!, "count", cnt.toString()
                        )
                    }
            }

            "nation" -> {
                plugin.messageManager.sendMessage(sender, "townia.top-header", "type", "住民数 (国)")
                nations.stream()
                    .sorted(Comparator.comparingInt(ToIntFunction { n: Nation? ->
                        var total = 0
                        for (t in plugin.townManager.getTownsByNation(n!!.id!!)) {
                            total += plugin.residentManager.getResidentsByTown(t.id!!).size
                        }
                        -total
                    }))
                    .limit(10)
                    .forEach { n: Nation? ->
                        var total = 0
                        for (t in plugin.townManager.getTownsByNation(n!!.id!!)) {
                            total += plugin.residentManager.getResidentsByTown(t.id!!).size
                        }
                        plugin.messageManager.sendMessage(
                            sender, "townia.top-entry-nation",
                            "name", n.name!!, "count", total.toString()
                        )
                    }
            }

            else -> plugin.messageManager.sendMessage(sender, "error.invalid-args")
        }
    }

    private fun handleTopLand(sender: CommandSender, scope: String) {
        val towns: MutableList<Town> = plugin.townManager.allTowns
        val nations: MutableList<Nation> = plugin.nationManager.allNations

        when (scope) {
            "all", "town" -> {
                val label = if (scope == "all") "土地の所有数 (全佁E" else "土地の所有数 (町)"
                plugin.messageManager.sendMessage(sender, "townia.top-header", "type", label)
                towns.stream().filter { it != null }.map { it!! }
                    .sorted(
                        Comparator.comparingInt { t ->
                            -plugin.plotManager.countPlotsByTown(t.id!!)
                        }
                    )
                    .limit(10)
                    .forEach { t ->
                        val cnt: Int = plugin.plotManager.countPlotsByTown(t.id!!)
                        plugin.messageManager.sendMessage(
                            sender, "townia.top-entry-town",
                            "name", t.name!!, "count", cnt.toString()
                        )
                    }
            }

            "nation" -> {
                plugin.messageManager.sendMessage(sender, "townia.top-header", "type", "土地の所有数 (国)")
                nations.stream().filter { it != null }.map { it!! }
                    .sorted(Comparator.comparingInt { n ->
                        var total = 0
                        for (t in plugin.townManager.getTownsByNation(n.id!!)) {
                            total += plugin.plotManager.countPlotsByTown(t.id!!)
                        }
                        -total
                    })
                    .limit(10)
                    .forEach { n ->
                        var total = 0
                        for (t in plugin.townManager.getTownsByNation(n.id!!)) {
                            total += plugin.plotManager.countPlotsByTown(t.id!!)
                        }
                        plugin.messageManager.sendMessage(
                            sender, "townia.top-entry-nation",
                            "name", n.name!!, "count", total.toString()
                        )
                    }
            }

            "resident" -> {
                plugin.messageManager.sendMessage(sender, "townia.top-header", "type", "土地の所有数 (住人)")
                plugin.residentManager.allResidents.stream().filter { it != null }.map { it!! }
                    .sorted(Comparator.comparingInt { r ->
                        -plugin.plotManager.countPlotsByOwner(r.uuid!!)
                    })
                    .limit(10)
                    .forEach { r ->
                        val cnt: Int = plugin.plotManager.countPlotsByOwner(r.uuid!!)
                        plugin.messageManager.sendMessage(
                            sender, "townia.top-entry-resident",
                            "name", r.name!!, "count", cnt.toString()
                        )
                    }
            }

            else -> plugin.messageManager.sendMessage(sender, "error.invalid-args")
        }
    }

    private fun formatMoney(amount: Double): String? {
        if (plugin.hasEconomy()) return plugin.economy!!.format(amount)
        return String.format("%.2f", amount)
    }


    private fun sendMap(player: Player) {
        val center = player.location.chunk
        val cx = center.x
        val cz = center.z
        val worldName = center.world.name

        val resOpt: TowniaPlayer? =
            plugin.residentManager.getResident(player.uniqueId).orElse(null)
        val playerTownUuid: UUID? = resOpt?.townUuid

        player.sendMessage("§8================ §6Townia Map §8================")
        for (z in cz - 5..cz + 5) {
            val row = StringBuilder()
            for (x in cx - 15..cx + 15) {
                val plot: net.azisaba.townia.data.Plot? = plugin.plotManager.getPlot(worldName, x, z).orElse(null)
                val symbol = if (plot == null) "-" else "+"

                if (x == cx && z == cz) {
                    row.append("§e").append(symbol)
                } else if (plot == null) {
                    row.append("§7").append(symbol)
                } else if (playerTownUuid != null && plot.townUuid == playerTownUuid) {
                    row.append("§a").append(symbol)
                } else {
                    row.append("§c").append(symbol)
                }
            }
            player.sendMessage(row.toString())
        }
    }
}