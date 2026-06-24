package net.azisaba.townia.command

import net.azisaba.townia.Townia
import net.azisaba.townia.TowniaException
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.manager.PlotManager
import net.azisaba.townia.manager.ResidentManager
import net.azisaba.townia.manager.TownManager
import net.milkbowl.vault.economy.Economy
import org.bukkit.Chunk
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.util.StringUtil

import java.sql.SQLException
import java.util.*
import java.util.logging.Level
import kotlin.Array
import kotlin.Boolean
import kotlin.Char
import kotlin.Double
import kotlin.NumberFormatException
import kotlin.text.equals
import kotlin.text.format
import kotlin.text.lowercase
import kotlin.text.toDouble

class PlotCommand(private val plugin: Townia) : CommandExecutor, TabCompleter {
    private val plotManager: PlotManager = plugin.plotManager
    private val residentManager: ResidentManager = plugin.residentManager
    private val townManager: TownManager = plugin.townManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = requirePlayer(sender) ?: return true

        if (args.isEmpty()) {
            sendHelp(player)
            return true
        }

        when (args[0].lowercase(Locale.getDefault())) {
            "info", "perm" -> handleInfo(player)
            "set" -> {
                if (args.size < 2) {
                    sendHelp(player)
                    return true
                }
                if (args[1].equals("type", ignoreCase = true) && args.size >= 3) {
                    handleSetType(player, args[2])
                } else if (args[1].equals("name", ignoreCase = true) && args.size >= 3) {
                    handleSetName(player, args.copyOfRange(2, args.size).joinToString(" "))
                } else if (args[1].equals("reset", ignoreCase = true)) {
                    handleSetReset(player)
                } else if (args[1].equals("perm", ignoreCase = true)) {
                    if (args.size >= 3 && args[2].equals("reset", ignoreCase = true)) {
                        handleSetReset(player)
                    } else {
                        handleSetPerm(player, args.copyOfRange(2, args.size))
                    }
                } else {
                    sendHelp(player)
                }
            }

            "toggle" -> handleToggle(player, args)
            "forsale", "fs" -> {
                if (args.size < 2) {
                    sendHelp(player)
                    return true
                }
                handleForSale(player, args[1])
            }

            "notforsale", "nfs" -> handleNotForSale(player)
            "buy", "claim" -> {
                if (args.size > 1 && args[1].equals("auto", ignoreCase = true)) {
                    handleClaimAuto(player)
                } else {
                    handleBuy(player)
                }
            }

            "clear" -> handleClear(player)
            "evict" -> handleEvict(player)
            "?", "help" -> sendHelp(player)
            else -> sendHelp(player)
        }
        return true
    }

    private fun handleInfo(player: Player) {
        val chunk = player.location.chunk
        val plotOpt: Optional<Plot> = plotManager.getPlot(chunk)
        if (plotOpt.isEmpty) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }
        val plot: Plot = plotOpt.get()
        val townOpt: Optional<Town> = townManager.getTown(plot.townUuid)
        val townName = townOpt.map { it.name }.orElse("Unknown")
        val owner: String? = if (plot.isTownOwned) townName else resolveOwnerName(plot.ownerUuid)
        val forSale = if (plot.isForSale) "Yes (" + formatMoney(plot.price) + ")" else "No"
        plugin.messageManager.sendMessage(
            player, "plot.info",
            "world", chunk.world.name,
            "town", (townName ?: "Unknown"),
            "type", (plot.plotType?.name ?: "Unknown"),
            "owner", (owner ?: "Unknown"),
            "for_sale", forSale,
            "x", chunk.x.toString(),
            "z", chunk.z.toString()
        )
    }

    private fun resolveOwnerName(ownerUuid: UUID?): String {
        if (ownerUuid == null) return "Town"
        val res: Optional<TowniaPlayer> = residentManager.getResident(ownerUuid)
        return (res.map { it.name ?: "Unknown" }.orElse("Unknown") ?: "Unknown")
    }

    private fun handleSetType(player: Player, typeName: String?) {
        val plotType: PlotType = PlotType.fromString(typeName ?: "")
        val validInput: Boolean = Arrays.stream(PlotType.entries.toTypedArray())
            .anyMatch({ t -> t.name.equals(typeName, ignoreCase = true) })
        if (!validInput) {
            plugin.messageManager.sendMessage(
                player, "error.invalid-args",
                "{usage}", "/plot set type <DEFAULT|SHOP|ARENA|EMBASSY|FARM|INN>"
            )
            return
        }

        val chunk = player.location.chunk
        if (plotManager.isClaimed(chunk)) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }

        if (isAssistantOrHigherInPlotTown(player, chunk)) {
            plugin.messageManager.sendMessage(player, "town.not-assistant")
            return
        }

        try {
            plotManager.setPlotType(
                chunk.world.name,
                chunk.x,
                chunk.z,
                plotType
            )
            plugin.messageManager.sendMessage(player, "plot.set-type", "{type}", plotType.name)
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(player, (e.messageKey ?: "Unknown"), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun handleForSale(player: Player, priceStr: String) {
        val price: Double
        try {
            price = priceStr.toDouble()
            if (price < 0) throw NumberFormatException()
        } catch (_: NumberFormatException) {
            plugin.messageManager.sendMessage(player, "error.invalid-amount")
            return
        }

        val chunk = player.location.chunk
        if (plotManager.isClaimed(chunk)) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }

        if (isAssistantOrHigherInPlotTown(player, chunk)) {
            plugin.messageManager.sendMessage(player, "town.not-assistant")
            return
        }

        try {
            plotManager.setForSale(
                chunk.world.name,
                chunk.x,
                chunk.z,
                true, price
            )
            plugin.messageManager.sendMessage(player, "plot.for-sale", "{price}", formatMoney(price))
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(player, (e.messageKey ?: "Unknown"), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun handleNotForSale(player: Player) {
        val chunk = player.location.chunk
        if (plotManager.isClaimed(chunk)) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }

        if (isAssistantOrHigherInPlotTown(player, chunk)) {
            plugin.messageManager.sendMessage(player, "town.not-assistant")
            return
        }

        val plotOpt: Optional<Plot> = plotManager.getPlot(chunk)
        if (plotOpt.isEmpty) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }

        try {
            plotManager.setForSale(
                plotOpt.get().worldName,
                plotOpt.get().chunkX,
                plotOpt.get().chunkZ,
                false,
                0.0
            )
            plugin.messageManager.sendMessage(player, "plot.not-for-sale")
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(player, (e.messageKey ?: "Unknown"), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun handleSetName(player: Player, name: String?) {
        val plot: Plot? = plotManager.getPlot(player.location.chunk).orElse(null)
        if (plot == null) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }

        val res: TowniaPlayer = requireMayorOrOwner(player, plot) ?: return

        plot.name = (name ?: "")
        try {
            plugin.databaseManager.savePlot(plot)
            plugin.messageManager.sendMessage(player, "plot.name-set", "name", (name ?: "Unknown"))
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to update plot name", e)
            plugin.messageManager.sendMessage(player, "error.database")
        }
    }

    private fun handleToggle(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.messageManager.sendMessage(player, "error.invalid-args")
            return
        }

        val plot: Plot? = plotManager.getPlot(player.location.chunk).orElse(null)
        if (plot == null) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }

        val res: TowniaPlayer = requireMayorOrOwner(player, plot) ?: return

        when (args[1].lowercase(Locale.getDefault())) {
            "pvp" -> {
                plot.setPvp(!plot.hasPvp())
                savePlotToggle(player, plot, "PvP", plot.hasPvp())
            }

            "mobs" -> {
                plot.setMobs(!plot.hasMobs())
                savePlotToggle(player, plot, "Mobs", plot.hasMobs())
            }

            "explosions" -> {
                plot.setExplosions(!plot.hasExplosions())
                savePlotToggle(player, plot, "Explosions", plot.hasExplosions())
            }

            "fire" -> {
                plot.setFire(!plot.hasFire())
                savePlotToggle(player, plot, "Fire", plot.hasFire())
            }

            else -> plugin.messageManager.sendMessage(player, "error.invalid-args")
        }
    }

    private fun savePlotToggle(player: Player?, plot: Plot?, setting: String?, state: Boolean) {
        try {
            plugin.databaseManager.savePlot(plot!!)
            plugin.messageManager
                .sendMessage(player!!, "plot.toggled", "setting", (setting ?: ""), "state", if (state) "ON" else "OFF")
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to save plot toggle", e)
            plugin.messageManager.sendMessage(player!!, "error.database")
        }
    }

    private fun handleBuy(player: Player) {
        if (!plugin.hasEconomy()) {
            plugin.messageManager.sendMessage(player, "error.no-vault")
            return
        }

        val chunk = player.location.chunk
        val plotOpt: Optional<Plot> = plotManager.getPlot(chunk)
        if (plotOpt.isEmpty) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }

        val plot: Plot = plotOpt.get()
        if (!plot.isForSale) {
            plugin.messageManager.sendMessage(player, "plot.not-for-sale-error")
            return
        }

        val price: Double = plot.price
        val eco: Economy = plugin.economy!!

        if (!eco.has(player, price)) {
            plugin.messageManager.sendMessage(
                player, "error.insufficient-funds",
                "amount", formatMoney(price)
            )
            return
        }

        try {
            val resp = eco.withdrawPlayer(player, price)
            if (!resp.transactionSuccess()) {
                plugin.messageManager.sendMessage(
                    player, "error.insufficient-funds",
                    "amount", formatMoney(price)
                )
                return
            }

            townManager.addBalance(plot.townUuid, price)
            plotManager.transferOwnership(
                chunk.world.name,
                chunk.x,
                chunk.z,
                player.uniqueId
            )

            plotManager.setForSale(
                chunk.world.name,
                chunk.x,
                chunk.z,
                false, 0.0
            )

            plugin.messageManager.sendMessage(player, "plot.bought", "{price}", formatMoney(price))
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(player, (e.messageKey ?: "Unknown"), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun isAssistantOrHigherInPlotTown(player: Player, chunk: Chunk?): Boolean {
        val plotOpt: Optional<Plot> = plotManager.getPlot(chunk)
        if (plotOpt.isEmpty) return true
        val plotTownId: UUID = plotOpt.get().townUuid!!

        val resOpt: Optional<TowniaPlayer> = residentManager.getResident(player.uniqueId)
        if (resOpt.isEmpty) return true
        val res: TowniaPlayer = resOpt.get()
        if (!res.isInTown) return true
        if (plotTownId != res.townUuid) return true
        return !res.isAssistantOrHigher
    }

    private fun formatMoney(amount: Double): String {
        if (plugin.hasEconomy()) return plugin.economy!!.format(amount)
        return String.format("%.2f", amount)
    }

    private fun sendHelp(player: Player?) {
        plugin.messageManager.sendMessage(player!!, "plot.help")
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): MutableList<String>? {
        val completions = ArrayList<String>()
        if (args.size == 1) {
            StringUtil.copyPartialMatches(
                args[0],
                mutableListOf("info", "set", "forsale", "notforsale", "buy", "perm"), completions
            )
        } else if (args.size == 2 && args[0].equals("set", ignoreCase = true)) {
            StringUtil.copyPartialMatches(
                args[1],
                mutableListOf("type", "name", "reset", "perm"),
                completions
            )
        } else if (args.size == 3 && args[0].equals("set", ignoreCase = true) && args[1].equals(
                "type",
                ignoreCase = true
            )
        ) {
            val types = ArrayList<String>()
            for (pt in PlotType.entries) types.add(pt.name.lowercase(Locale.getDefault()))
            StringUtil.copyPartialMatches(args[2], types, completions)
        } else if (args.size == 3 && args[0].equals("set", ignoreCase = true) && args[1].equals(
                "perm",
                ignoreCase = true
            )
        ) {
            StringUtil.copyPartialMatches(
                args[2],
                mutableListOf(
                    "resident",
                    "ally",
                    "outsider",
                    "nation",
                    "build",
                    "destroy",
                    "switch",
                    "itemuse",
                    "on",
                    "off"
                ),
                completions
            )
        } else if (args.size == 4 && args[0].equals("set", ignoreCase = true) && args[1].equals(
                "perm",
                ignoreCase = true
            )
        ) {
            if (!args[2].equals("on", ignoreCase = true) && !args[2].equals("off", ignoreCase = true)) {
                StringUtil.copyPartialMatches(
                    args[3],
                    mutableListOf("build", "destroy", "switch", "itemuse", "on", "off"),
                    completions
                )
            }
        } else if (args.size == 5 && args[0].equals("set", ignoreCase = true) && args[1].equals(
                "perm",
                ignoreCase = true
            )
        ) {
            if (!args[3].equals("on", ignoreCase = true) && !args[3].equals("off", ignoreCase = true)) {
                StringUtil.copyPartialMatches(
                    args[4],
                    mutableListOf("on", "off"),
                    completions
                )
            }
        }
        return completions as MutableList<String>?
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender !is Player) {
            plugin.messageManager.sendMessage(sender, "error.player-only")
            return null
        }
        return sender
    }

    private fun requireMayorOrOwner(player: Player, plot: Plot): TowniaPlayer? {
        val res: TowniaPlayer? = residentManager.getResident(player.uniqueId).orElse(null)
        if (res == null) {
            plugin.messageManager.sendMessage(player, "error.player-not-found")
            return null
        }
        if (plot.ownerUuid == player.uniqueId) {
            return res
        }
        if (plot.townUuid == res.townUuid && res.isMayorOrHigher) {
            return res
        }
        plugin.messageManager.sendMessage(player, "error.no-permission")
        return null
    }

    private fun handleSetReset(player: Player) {
        val chunk = player.location.chunk
        val plotOpt: Optional<Plot> = plotManager.getPlot(chunk)
        if (plotOpt.isEmpty) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }
        val plot: Plot = plotOpt.get()
        val res: TowniaPlayer = requireMayorOrOwner(player, plot) ?: return

        plot.setPvp(false)
        plot.setMobs(false)
        plot.setExplosions(false)
        plot.setFire(false)
        plot.name = null
        try {
            plugin.databaseManager.savePlot(plot)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to save plot reset", e)
        }
        plugin.messageManager.sendMessage(player, "plot.reset")
    }

    private fun handleSetPerm(player: Player, args: Array<out String>) {
        val chunk = player.location.chunk
        val plot: Plot? =
            plugin.plotManager.getPlot(chunk.world.name, chunk.x, chunk.z).orElse(null)
        if (plot == null) {
            plugin.messageManager.sendMessage(player, "plot.not-owned-by-your-town")
            return
        }
        val res: TowniaPlayer? =
            plugin.residentManager.getResident(player.uniqueId).orElse(null)
        if (res == null || res.townUuid == null || res.townUuid != plot.townUuid) {
            plugin.messageManager.sendMessage(player, "plot.not-owned-by-your-town")
            return
        }

        if (plot.ownerUuid != null) {
            if (plot.ownerUuid == res.uuid && !res.isAssistantOrHigher) {
                plugin.messageManager.sendMessage(player, "error.no-permission")
                return
            }
        } else {
            if (!res.isAssistantOrHigher) {
                plugin.messageManager.sendMessage(player, "error.no-permission")
                return
            }
        }

        if (args.isEmpty()) {
            plugin.messageManager.sendMessage(player, "error.invalid-args")
            return
        }

        var groupStr: String? = null
        var actionStr: String? = null
        var stateStr: String? = null

        if (args.size == 1) {
            stateStr = args[0]
        } else if (args.size == 2) {
            val first = args[0].lowercase(Locale.getDefault())
            if (first == "resident" || first == "ally" || first == "outsider" || first == "nation") {
                groupStr = args[0]
            } else {
                actionStr = args[0]
            }
            stateStr = args[1]
        } else if (args.size >= 3) {
            groupStr = args[0]
            actionStr = args[1]
            stateStr = args[2]
        }

        val state = stateStr.equals("on", ignoreCase = true) || stateStr.equals("true", ignoreCase = true)

        val groups: MutableList<String> = ArrayList<String>()
        if (groupStr == null) {
            groups.addAll(mutableListOf("resident", "ally", "outsider", "nation"))
        } else {
            if (groupStr.equals("resident", ignoreCase = true)) groups.add("resident")
            else if (groupStr.equals("ally", ignoreCase = true)) groups.add("ally")
            else if (groupStr.equals("outsider", ignoreCase = true)) groups.add("outsider")
            else if (groupStr.equals("nation", ignoreCase = true)) groups.add("nation")
            else {
                plugin.messageManager.sendMessage(player, "error.invalid-args")
                return
            }
        }

        val actions: MutableList<Char?> = ArrayList<Char?>()
        if (actionStr == null) {
            actions.addAll(mutableListOf<Char?>('B', 'D', 'S', 'I'))
        } else {
            if (actionStr.equals("build", ignoreCase = true)) actions.add('B')
            else if (actionStr.equals("destroy", ignoreCase = true)) actions.add('D')
            else if (actionStr.equals("switch", ignoreCase = true)) actions.add('S')
            else if (actionStr.equals("item", ignoreCase = true) || actionStr.equals(
                    "itemuse",
                    ignoreCase = true
                )
            ) actions.add('I')
            else {
                plugin.messageManager.sendMessage(player, "error.invalid-args")
                return
            }
        }

        for (group in groups) {
            var current: String? = ""
            if (group == "resident") current = if (plot.permsResident != null) plot.permsResident else ""
            else if (group == "ally") current = if (plot.permsAlly != null) plot.permsAlly else ""
            else if (group == "outsider") current = if (plot.permsOutsider != null) plot.permsOutsider else ""
            else if (group == "nation") current = if (plot.permsNation != null) plot.permsNation else ""

            for (action in actions) {
                current = net.azisaba.townia.data.PermissionMatrix.setPerm(current, action!!, state)
            }

            if (group == "resident") plot.permsResident = current
            else if (group == "ally") plot.permsAlly = current
            else if (group == "outsider") plot.permsOutsider = current
            else if (group == "nation") plot.permsNation = current
        }

        try {
            plugin.databaseManager.savePlot(plot)
        } catch (e: SQLException) {
            e.printStackTrace()
            plugin.messageManager.sendMessage(player, "error.database")
            return
        }
        plugin.messageManager.sendMessage(
            player,
            "town.perm-set",
            "perm",
            (if (groupStr != null) "$groupStr " else "") + (actionStr ?: "all"),
            "state",
            if (state) "ON" else "OFF"
        )
    }

    private fun handleClaimAuto(player: Player?) {
        plugin.messageManager.sendMessage(player!!, "error.not-implemented")
    }

    private fun handleClear(player: Player?) {
        plugin.messageManager.sendMessage(player!!, "error.not-implemented")
    }

    private fun handleEvict(player: Player?) {
        plugin.messageManager.sendMessage(player!!, "error.not-implemented")
    }
}