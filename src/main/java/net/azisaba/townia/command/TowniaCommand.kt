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
import java.util.UUID

class TowniaCommand(private val plugin: Townia) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "reload" -> handleReload(sender)
            "info" -> handleInfo(sender)
            "map" -> requirePlayer(sender)?.let { sendMap(it) }
            "price" -> handlePrice(sender)
            "time" -> handleTime(sender)
            "top" -> handleTop(sender, args)
            "debug" -> handleDebug(sender)
            "?" -> sendHelp(sender)
            else -> sendHelp(sender)
        }
        return true
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
            if (sender.hasPermission("townia.admin.debug")) options.add("debug")
            StringUtil.copyPartialMatches(args[0], options, completions)
        } else if (args.size == 2 && args[0].equals("top", ignoreCase = true)) {
            StringUtil.copyPartialMatches(args[1], listOf("residents", "land"), completions)
        } else if (args.size == 3 && args[0].equals("top", ignoreCase = true)) {
            StringUtil.copyPartialMatches(args[2], listOf("all", "town", "nation", "resident"), completions)
        }
        return completions
    }

    private fun handleReload(sender: CommandSender) {
        if (!sender.hasPermission("townia.admin.reload")) {
            plugin.messageManager.sendMessage(sender, "error.no-permission")
            return
        }
        plugin.towniaConfig.reload()
        plugin.messageManager.loadAllMessages()
        plugin.messageManager.sendMessage(sender, "admin.reloaded")
    }

    private fun handleInfo(sender: CommandSender) {
        val version = plugin.description.version
        val authors = plugin.description.authors.joinToString(", ")
        plugin.messageManager.sendMessage(sender, "admin.help", "version", version, "authors", authors)
    }

    private fun sendHelp(sender: CommandSender) {
        plugin.messageManager.sendMessage(sender, "townia.help")
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender !is Player) {
            plugin.messageManager.sendMessage(sender, "error.player-only")
            return null
        }
        return sender
    }

    private fun handlePrice(sender: CommandSender) {
        plugin.messageManager.sendMessage(
            sender,
            "townia.price",
            "town_create", formatMoney(plugin.towniaConfig.townCreationCost),
            "claim", formatMoney(plugin.towniaConfig.claimCost),
            "nation_create", formatMoney(plugin.towniaConfig.nationCreationCost),
            "town_upkeep", formatMoney(plugin.towniaConfig.townUpkeep)
        )
    }

    private fun handleTime(sender: CommandSender) {
        val diff = plugin.nextUpkeepTime - System.currentTimeMillis()
        val time = if (diff <= 0) {
            "Now"
        } else {
            val hours = diff / 3600000L
            val mins = diff % 3600000L / 60000L
            "%02d:%02d".format(hours, mins)
        }
        plugin.messageManager.sendMessage(sender, "town.time-upkeep", "time", time)
    }

    private fun handleDebug(sender: CommandSender) {
        val player = requirePlayer(sender) ?: return
        if (!player.hasPermission("townia.admin.debug")) {
            plugin.messageManager.sendMessage(player, "error.no-permission")
            return
        }

        plugin.messageManager.sendMessageWithoutPrefix(player, "townia.debug-header")
        debugEntry(player, "World Name", player.world.name)
        debugEntry(player, "Is World Allowed", plugin.towniaConfig.isWorldAllowed(player.world.name).toString())

        val x = player.location.blockX shr 4
        val z = player.location.blockZ shr 4
        val plotOpt = plugin.plotManager.getPlot(player.world.name, x, z)
        debugEntry(player, "Chunk", "$x, $z")
        debugEntry(player, "Plot Present", plotOpt.isPresent.toString())
        if (plotOpt.isPresent) {
            val townOpt = plugin.townManager.getTown(plotOpt.get().townUuid)
            debugEntry(player, "Town", townOpt.map { it.name ?: "Unknown" }.orElse("Unknown"))
        } else {
            debugEntry(player, "Town", plugin.messageManager.getPlainMessage(player, "town.actionbar-wilderness-name"))
        }

        try {
            plugin.messageManager.sendActionBar(player, "town.actionbar-wilderness")
            plugin.messageManager.sendMessageWithoutPrefix(player, "townia.debug-actionbar-ok")
        } catch (e: Exception) {
            plugin.messageManager.sendMessageWithoutPrefix(player, "townia.debug-actionbar-error", "error", e.message ?: "Unknown")
            e.printStackTrace()
        }

        try {
            plugin.messageManager.sendTitle(player, "town.title-wilderness-main", "town.title-wilderness-sub")
            plugin.messageManager.sendMessageWithoutPrefix(player, "townia.debug-title-ok")
        } catch (e: Exception) {
            plugin.messageManager.sendMessageWithoutPrefix(player, "townia.debug-title-error", "error", e.message ?: "Unknown")
            e.printStackTrace()
        }
    }

    private fun debugEntry(player: Player, key: String, value: String) {
        plugin.messageManager.sendMessageWithoutPrefix(player, "townia.debug-entry", "key", key, "value", value)
    }

    private fun handleTop(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        when (args[1].lowercase()) {
            "residents" -> handleTopResidents(sender, args[2].lowercase())
            "land", "lands" -> handleTopLand(sender, args[2].lowercase())
            else -> plugin.messageManager.sendMessage(sender, "error.invalid-args")
        }
    }

    private fun handleTopResidents(sender: CommandSender, scope: String) {
        when (scope) {
            "all", "town" -> {
                val labelKey = if (scope == "all") "townia.top-label-residents-all" else "townia.top-label-residents-town"
                plugin.messageManager.sendMessage(sender, "townia.top-header", "type", plugin.messageManager.getPlainMessage(sender, labelKey))
                plugin.townManager.allTowns
                    .sortedByDescending { plugin.residentManager.getResidentsByTown(it.id!!).size }
                    .take(10)
                    .forEachIndexed { index, town -> sendTownTopEntry(sender, index + 1, town) }
            }
            "nation" -> {
                plugin.messageManager.sendMessage(sender, "townia.top-header", "type", plugin.messageManager.getPlainMessage(sender, "townia.top-label-residents-nation"))
                plugin.nationManager.allNations
                    .sortedByDescending { nationResidentCount(it) }
                    .take(10)
                    .forEachIndexed { index, nation -> sendNationTopEntry(sender, index + 1, nation) }
            }
            else -> plugin.messageManager.sendMessage(sender, "error.invalid-args")
        }
    }

    private fun handleTopLand(sender: CommandSender, scope: String) {
        when (scope) {
            "all", "town" -> {
                val labelKey = if (scope == "all") "townia.top-label-land-all" else "townia.top-label-land-town"
                plugin.messageManager.sendMessage(sender, "townia.top-header", "type", plugin.messageManager.getPlainMessage(sender, labelKey))
                plugin.townManager.allTowns
                    .sortedByDescending { plugin.plotManager.countPlotsByTown(it.id!!) }
                    .take(10)
                    .forEachIndexed { index, town -> sendTownTopEntry(sender, index + 1, town) }
            }
            "nation" -> {
                plugin.messageManager.sendMessage(sender, "townia.top-header", "type", plugin.messageManager.getPlainMessage(sender, "townia.top-label-land-nation"))
                plugin.nationManager.allNations
                    .sortedByDescending { nationPlotCount(it) }
                    .take(10)
                    .forEachIndexed { index, nation -> sendNationTopEntry(sender, index + 1, nation) }
            }
            "resident" -> {
                plugin.messageManager.sendMessage(sender, "townia.top-header", "type", plugin.messageManager.getPlainMessage(sender, "townia.top-label-land-resident"))
                plugin.residentManager.allResidents
                    .sortedByDescending { plugin.plotManager.countPlotsByOwner(it.uuid!!) }
                    .take(10)
                    .forEachIndexed { index, resident -> sendResidentTopEntry(sender, index + 1, resident) }
            }
            else -> plugin.messageManager.sendMessage(sender, "error.invalid-args")
        }
    }

    private fun sendTownTopEntry(sender: CommandSender, rank: Int, town: Town) {
        plugin.messageManager.sendMessage(
            sender,
            "townia.top-entry-town",
            "rank", rank.toString(),
            "town", town.name ?: "Unknown",
            "name", town.name ?: "Unknown",
            "claims", plugin.plotManager.countPlotsByTown(town.id!!).toString(),
            "residents", plugin.residentManager.getResidentsByTown(town.id!!).size.toString(),
            "count", plugin.residentManager.getResidentsByTown(town.id!!).size.toString()
        )
    }

    private fun sendNationTopEntry(sender: CommandSender, rank: Int, nation: Nation) {
        plugin.messageManager.sendMessage(
            sender,
            "townia.top-entry-nation",
            "rank", rank.toString(),
            "nation", nation.name ?: "Unknown",
            "name", nation.name ?: "Unknown",
            "claims", nationPlotCount(nation).toString(),
            "towns", plugin.townManager.getTownsByNation(nation.id!!).size.toString(),
            "count", nationResidentCount(nation).toString()
        )
    }

    private fun sendResidentTopEntry(sender: CommandSender, rank: Int, resident: TowniaPlayer) {
        val claims = plugin.plotManager.countPlotsByOwner(resident.uuid!!)
        plugin.messageManager.sendMessage(
            sender,
            "townia.top-entry-resident",
            "rank", rank.toString(),
            "player", resident.name ?: "Unknown",
            "name", resident.name ?: "Unknown",
            "claims", claims.toString(),
            "balance", claims.toString(),
            "count", claims.toString()
        )
    }

    private fun nationResidentCount(nation: Nation): Int {
        return plugin.townManager.getTownsByNation(nation.id!!).sumOf { town ->
            plugin.residentManager.getResidentsByTown(town.id!!).size
        }
    }

    private fun nationPlotCount(nation: Nation): Int {
        return plugin.townManager.getTownsByNation(nation.id!!).sumOf { town ->
            plugin.plotManager.countPlotsByTown(town.id!!)
        }
    }

    private fun formatMoney(amount: Double): String {
        if (plugin.hasEconomy()) {
            return plugin.economy!!.format(amount).replace("[^\\d.,-]".toRegex(), "")
        }
        return "%.2f".format(amount)
    }

    private fun sendMap(player: Player) {
        val center = player.location.chunk
        val playerTownUuid: UUID? = plugin.residentManager.getResident(player.uniqueId).orElse(null)?.townUuid

        plugin.messageManager.sendMessageWithoutPrefix(player, "townia.map-header")
        for (z in center.z - 5..center.z + 5) {
            val row = StringBuilder()
            for (x in center.x - 15..center.x + 15) {
                val plot = plugin.plotManager.getPlot(center.world.name, x, z).orElse(null)
                val symbol = if (plot == null) "-" else "+"
                when {
                    x == center.x && z == center.z -> row.append("<yellow>").append(symbol)
                    plot == null -> row.append("<gray>").append(symbol)
                    playerTownUuid != null && plot.townUuid == playerTownUuid -> row.append("<green>").append(symbol)
                    else -> row.append("<red>").append(symbol)
                }
            }
            player.sendMessage(plugin.messageManager.miniMessage.deserialize(row.toString()))
        }
    }
}
