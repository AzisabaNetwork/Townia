package net.azisaba.townia.command

import net.azisaba.townia.Townia
import net.azisaba.townia.TowniaException
import net.azisaba.townia.data.PermissionMatrix
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.manager.PlotManager
import net.azisaba.townia.manager.ResidentManager
import net.azisaba.townia.manager.TownManager
import net.azisaba.townia.permission.Perm
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

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean =
        CommandHelp.withCommand("plot", args) { execute(sender, command, label, args) }

    private fun execute(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = requirePlayer(sender) ?: return true

        if (args.isEmpty()) {
            sendHelp(player)
            return true
        }

        val node = nodeFor(args)
        if (node != null && !Perm.has(player, node)) {
            plugin.messageManager.sendMessage(player, "error.no-permission")
            return true
        }
        when (args[0].lowercase(Locale.getDefault())) {
            "info", "perm", "here" -> handleInfo(player)
            "set" -> {
                if (args.size < 2) {
                    plugin.messageManager.sendMessage(player, "error.invalid-args")
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
                } else if (PlotType.parseOrNull(args[1]) != null) {
                    handleSetType(player, args[1])
                } else {
                    plugin.messageManager.sendMessage(player, "error.invalid-args")
                }
            }

            "toggle" -> handleToggle(player, args)
            "forsale", "fs" -> handleForSale(player, args)

            "notforsale", "nfs" -> handleNotForSale(player, args)
            "buy", "claim" -> {
                if (args.size > 1 && args[1].equals("auto", ignoreCase = true)) {
                    handleClaimAuto(player)
                } else {
                    handleBuy(player)
                }
            }

            "clear" -> handleClear(player)
            "unclaim" -> handleUnclaim(player)
            "evict" -> handleEvict(player)
            "?", "help" -> CommandHelp.handleHelp(plugin.messageManager, player, "plot", args.drop(1))
            else -> CommandHelp.sendUnknown(plugin.messageManager, player, "plot", args[0])
        }
        return true
    }

    /** The Towny node a `/plot` invocation needs, or null when it needs none. */
    private fun nodeFor(args: Array<out String>): String? {
        val sub = args.getOrNull(1)?.lowercase(Locale.getDefault())
        return when (args[0].lowercase(Locale.getDefault())) {
            "info", "perm", "here" -> "towny.command.plot.perm"
            "forsale", "fs" -> "towny.command.plot.forsale"
            "notforsale", "nfs" -> "towny.command.plot.notforsale"
            "buy", "claim" -> "towny.command.plot.claim"
            "clear" -> "towny.command.plot.clear"
            "unclaim" -> "towny.command.plot.unclaim"
            "evict" -> "towny.command.plot.evict"
            "toggle" -> sub?.let { "towny.command.plot.toggle." + if (it == "explosions") "explosion" else it }
            "set" -> when (sub) {
                null -> null
                "name", "perm", "reset" -> "towny.command.plot.set.$sub"
                // "/plot set type shop" and the short form "/plot set shop"
                else -> plotTypeNode(if (sub == "type") args.getOrNull(2) else sub)
            }
            else -> null
        }
    }

    private fun plotTypeNode(type: String?): String? {
        val parsed = PlotType.parseOrNull(type ?: return null) ?: return null
        return "towny.command.plot.set." + if (parsed == PlotType.DEFAULT) "reset" else parsed.name.lowercase(Locale.ROOT)
    }

    /** /plot info | perm | here: Towny-style status of the plot the player stands in. */
    private fun handleInfo(player: Player) {
        val chunk = player.location.chunk
        val plotOpt: Optional<Plot> = plotManager.getPlot(chunk)
        if (plotOpt.isEmpty) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }
        val plot: Plot = plotOpt.get()
        val town: Town? = townManager.getTown(plot.townUuid).orElse(null)
        val townName = town?.name ?: "Unknown"
        val owner: String = if (plot.isTownOwned) townName else resolveOwnerName(plot.ownerUuid)
        val forSale = if (plot.isForSale) formatMoney(plot.price) else "No"
        val plotName = plot.name?.takeIf { it.isNotBlank() } ?: "(${chunk.x}, ${chunk.z})"
        plugin.messageManager.sendMessageWithoutPrefix(
            player, "plot.info",
            "name", plotName,
            "world", chunk.world.name,
            "town", townName,
            "type", (plot.plotType?.name ?: "DEFAULT"),
            "owner", owner,
            "for_sale", forSale,
            "outpost", onOff(plot.isOutpost),
            "x", chunk.x.toString(),
            "z", chunk.z.toString(),
            "perms_build", formatPlotPerm(plot, town, 'B'),
            "perms_destroy", formatPlotPerm(plot, town, 'D'),
            "perms_switch", formatPlotPerm(plot, town, 'S'),
            "perms_item", formatPlotPerm(plot, town, 'I'),
            "pvp", onOff(plot.hasPvp()),
            "explosions", onOff(plot.hasExplosions()),
            "fire", onOff(plot.hasFire()),
            "mobs", onOff(plot.hasMobs())
        )
    }

    /**
     * Effective perms per group (F=owner's friends, R=resident, A=ally, N=nation, O=outsider), using
     * the plot's own setting when present and the fallback otherwise, as PlotProtectionListener does.
     * Friends only exist on resident-owned plots.
     */
    private fun formatPlotPerm(plot: Plot, town: Town?, action: Char): String {
        val owner = plot.ownerUuid?.let { residentManager.getResident(it).orElse(null) }
        fun has(plotPerms: String?, group: String) =
            ((plotPerms ?: PermissionMatrix.inheritedPlotPerms(plot, town, owner, group))?.indexOf(action) ?: -1) >= 0
        val sb = StringBuilder()
        sb.append(if (plot.ownerUuid != null && has(plot.permsFriend, "friend")) "F" else "-")
        sb.append(if (has(plot.permsResident, "resident")) "R" else "-")
        sb.append(if (has(plot.permsAlly, "ally")) "A" else "-")
        sb.append(if (has(plot.permsNation, "nation")) "N" else "-")
        sb.append(if (has(plot.permsOutsider, "outsider")) "O" else "-")
        return sb.toString()
    }

    private fun onOff(value: Boolean): String = if (value) "ON" else "OFF"

    private fun resolveOwnerName(ownerUuid: UUID?): String {
        if (ownerUuid == null) return "Town"
        val res: Optional<TowniaPlayer> = residentManager.getResident(ownerUuid)
        return (res.map { it.name ?: "Unknown" }.orElse("Unknown") ?: "Unknown")
    }

    private fun handleSetType(player: Player, typeName: String?) {
        val plotType: PlotType? = typeName?.let { PlotType.parseOrNull(it) }
        if (plotType == null) {
            plugin.messageManager.sendMessage(
                player, "error.invalid-args",
                "{usage}", "/plot set type <DEFAULT|SHOP|ARENA|EMBASSY|FARM|INN>"
            )
            return
        }

        val chunk = player.location.chunk
        if (!plotManager.isClaimed(chunk)) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }

        if (isAssistantOrHigherInPlotTown(player, chunk, allowOwner = true)) {
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

    private fun handleForSale(player: Player, args: Array<out String>) {
        // Towny allows "/plot fs rect 3" and bare "/plot fs": fall back to the town's plot price.
        val priceGiven = args.size >= 2 && !isRangeShape(args[1])
        val price: Double
        if (priceGiven) {
            try {
                price = args[1].toDouble()
                if (price.isNaN() || price.isInfinite() || price < 0) throw NumberFormatException()
            } catch (_: NumberFormatException) {
                plugin.messageManager.sendMessage(player, "error.invalid-amount")
                return
            }
        } else {
            price = defaultPlotPrice(player)
        }
        val shapeIndex = if (priceGiven) 2 else 1

        val maxPrice = plugin.towniaConfig.maxPlotPrice
        if (maxPrice > 0.0 && price > maxPrice) {
            plugin.messageManager.sendMessage(player, "plot.price-too-high", "max", formatMoney(maxPrice))
            return
        }

        if (args.size > shapeIndex + 1 && isRangeShape(args[shapeIndex])) {
            val radius = parseRadius(args[shapeIndex + 1])
            if (radius == null) {
                plugin.messageManager.sendMessage(player, "error.invalid-amount")
                return
            }
            applyForSaleRange(player, args[shapeIndex], radius, true, price)
            return
        }

        val chunk = player.location.chunk
        if (!plotManager.isClaimed(chunk)) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }

        if (isAssistantOrHigherInPlotTown(player, chunk, allowOwner = true)) {
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

    private fun defaultPlotPrice(player: Player): Double {
        val townId = plotManager.getPlot(player.location.chunk).map { it.townUuid }.orElse(null)
            ?: residentManager.getResident(player.uniqueId).map { it.townUuid }.orElse(null)
        return townManager.getTown(townId).map { it.plotPrice }.orElse(0.0)
    }

    private fun handleNotForSale(player: Player, args: Array<out String>) {
        if (args.size >= 3 && isRangeShape(args[1])) {
            val radius = parseRadius(args[2])
            if (radius == null) {
                plugin.messageManager.sendMessage(player, "error.invalid-amount")
                return
            }
            applyForSaleRange(player, args[1], radius, false, 0.0)
            return
        }

        val chunk = player.location.chunk
        if (!plotManager.isClaimed(chunk)) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }

        if (isAssistantOrHigherInPlotTown(player, chunk, allowOwner = true)) {
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

    private fun applyForSaleRange(player: Player, shape: String, radius: Int, forSale: Boolean, price: Double) {
        val chunks = collectRangeChunks(player.location.chunk, shape, radius)
        var changed = 0
        var failed = 0
        for (chunk in chunks) {
            if (!plotManager.isClaimed(chunk) || isAssistantOrHigherInPlotTown(player, chunk, allowOwner = true)) {
                failed++
                continue
            }
            val plot = plotManager.getPlot(chunk).orElse(null)
            if (plot == null) {
                failed++
                continue
            }
            try {
                plotManager.setForSale(plot.worldName, plot.chunkX, plot.chunkZ, forSale, price)
                changed++
            } catch (_: TowniaException) {
                failed++
            }
        }
        if (forSale) {
            plugin.messageManager.sendMessage(player, "plot.range-for-sale", "count", changed.toString(), "price", formatMoney(price), "failed", failed.toString())
        } else {
            plugin.messageManager.sendMessage(player, "plot.range-not-for-sale", "count", changed.toString(), "failed", failed.toString())
        }
    }

    private fun collectRangeChunks(center: Chunk, shape: String, radius: Int): List<Chunk> {
        val chunks: MutableList<Chunk> = ArrayList()
        val circle = shape.equals("circle", ignoreCase = true)
        for (x in center.x - radius..center.x + radius) {
            for (z in center.z - radius..center.z + radius) {
                val dx = x - center.x
                val dz = z - center.z
                if (circle && dx * dx + dz * dz > radius * radius) continue
                chunks.add(center.world.getChunkAt(x, z))
            }
        }
        return chunks
    }

    private fun isRangeShape(value: String): Boolean {
        return value.equals("rect", ignoreCase = true) || value.equals("circle", ignoreCase = true)
    }

    private fun parseRadius(value: String): Int? {
        val radius = value.toIntOrNull() ?: return null
        return radius.coerceIn(0, MAX_RANGE_RADIUS)
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
                val key = "pvp:" + plot.worldName + ":" + plot.chunkX + ":" + plot.chunkZ
                val wait = net.azisaba.townia.manager.Cooldowns.remaining(key, plugin.towniaConfig.rules.pvpToggleCooldownSeconds)
                if (wait > 0 && !Perm.isAdmin(player)) {
                    plugin.messageManager.sendMessage(player, "error.cooldown", "seconds", wait.toString())
                    return
                }
                net.azisaba.townia.manager.Cooldowns.start(key)
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

        if (plot.ownerUuid == player.uniqueId) {
            plugin.messageManager.sendMessage(player, "plot.already-owner")
            return
        }
        val maxPlots = plugin.towniaConfig.rules.maxPlotsPerResident
        if (maxPlots > 0 && plotManager.countPlotsByOwner(player.uniqueId) >= maxPlots && !Perm.isAdmin(player)) {
            plugin.messageManager.sendMessage(player, "plot.max-plots", "max", maxPlots.toString())
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

            val seller = plot.ownerUuid
            if (seller != null) {
                // Resident-owned plot: the seller is paid, as in Towny.
                eco.depositPlayer(plugin.server.getOfflinePlayer(seller), price)
            } else {
                townManager.addBalance(plot.townUuid, price)
                val townBalance = townManager.getTown(plot.townUuid).map { it.balance }.orElse(0.0)
                plugin.databaseManager.recordBankTransaction(
                    "TOWN",
                    plot.townUuid!!,
                    player.uniqueId,
                    price,
                    "plot_purchase",
                    townBalance
                )
            }
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
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to record plot purchase transaction", e)
            plugin.messageManager.sendMessage(player, "error.database")
        }
    }

    /**
     * Despite the name this returns true when the player is NOT allowed to manage the plot:
     * they must belong to the plot's town and be assistant or higher, the town's mayor, or
     * (with [allowOwner]) the plot's own owner, who may sell their land as in Towny.
     */
    private fun isAssistantOrHigherInPlotTown(player: Player, chunk: Chunk?, allowOwner: Boolean = false): Boolean {
        val plotOpt: Optional<Plot> = plotManager.getPlot(chunk)
        if (plotOpt.isEmpty) return true
        val plotTownId: UUID = plotOpt.get().townUuid!!
        if (TowniaAdminCommand.isBypassing(player)) return false
        if (allowOwner && plotOpt.get().ownerUuid == player.uniqueId) return false
        if (townManager.getTown(plotTownId).map { it.mayorUuid == player.uniqueId }.orElse(false)) return false

        val resOpt: Optional<TowniaPlayer> = residentManager.getResident(player.uniqueId)
        if (resOpt.isEmpty) return true
        val res: TowniaPlayer = resOpt.get()
        if (!res.isInTown) return true
        if (plotTownId != res.townUuid) return true
        // Towny: managing town land you do not own needs plot.asmayor (mayors and assistants hold it).
        return !Perm.has(player, "towny.command.plot.asmayor")
    }

    private fun formatMoney(amount: Double): String {
        if (plugin.hasEconomy()) return plugin.economy!!.format(amount).replace("[^\\d.,-]".toRegex(), "")
        return String.format("%.2f", amount)
    }

    private fun sendHelp(player: Player?) {
        CommandHelp.sendList(plugin.messageManager, player!!, "plot", 1)
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
                mutableListOf("help", "info", "here", "set", "forsale", "fs", "notforsale", "nfs", "buy", "claim", "unclaim", "toggle", "clear", "evict", "perm"), completions
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
        } else if (args.size == 2 && (args[0].equals("notforsale", ignoreCase = true) || args[0].equals("nfs", ignoreCase = true))) {
            StringUtil.copyPartialMatches(args[1], mutableListOf("rect", "circle"), completions)
        } else if (args.size == 2 && args[0].equals("toggle", ignoreCase = true)) {
            StringUtil.copyPartialMatches(args[1], mutableListOf("pvp", "mobs", "explosions", "fire"), completions)
        } else if ((args.size == 2 || args.size == 3) && (args[0].equals("forsale", ignoreCase = true) || args[0].equals("fs", ignoreCase = true)) &&
            (args.size == 2 || !isRangeShape(args[1]))
        ) {
            StringUtil.copyPartialMatches(args[args.size - 1], mutableListOf("rect", "circle"), completions)
        } else if (
            (args.size == 3 && (args[0].equals("notforsale", ignoreCase = true) || args[0].equals("nfs", ignoreCase = true)) && isRangeShape(args[1])) ||
            (args.size == 4 && (args[0].equals("forsale", ignoreCase = true) || args[0].equals("fs", ignoreCase = true)) && isRangeShape(args[2])) ||
            (args.size == 3 && (args[0].equals("forsale", ignoreCase = true) || args[0].equals("fs", ignoreCase = true)) && isRangeShape(args[1]))
        ) {
            StringUtil.copyPartialMatches(args[args.size - 1], mutableListOf("1", "2", "3", "5", "10"), completions)
        } else if (args.size == 3 && args[0].equals("set", ignoreCase = true) && args[1].equals(
                "perm",
                ignoreCase = true
            )
        ) {
            StringUtil.copyPartialMatches(
                args[2],
                mutableListOf(
                    "friend",
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
        return completions
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
            plugin.messageManager.sendMessage(player, "error.player-not-found", "player", player.name)
            return null
        }
        if (plot.ownerUuid == player.uniqueId || TowniaAdminCommand.isBypassing(player)) {
            return res
        }
        // Naming, toggling, resetting and clearing someone else's plot is the mayor's, as in 1.3.3.
        // (Towny let plot.asmayor holders do it too; here that node only covers perms, type and sale.)
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
        val bypass = TowniaAdminCommand.isBypassing(player)
        val isOwner = plot.ownerUuid != null && plot.ownerUuid == player.uniqueId
        if (!bypass && !isOwner) {
            // Non-owners must belong to the plot's town and be assistant or higher.
            if (res == null || res.townUuid == null || res.townUuid != plot.townUuid) {
                plugin.messageManager.sendMessage(player, "plot.not-owned-by-your-town")
                return
            }
            if (!Perm.has(player, "towny.command.plot.asmayor")) {
                plugin.messageManager.sendMessage(player, "error.no-permission")
                return
            }
        }

        // [group] [action] <on|off>. The last word has to be the state: reading any other word as
        // "off" used to switch every permission of the plot off for a forgotten on/off.
        val words = args.map { it.lowercase(Locale.ROOT) }
        val state = when (words.lastOrNull()) {
            "on", "true" -> true
            "off", "false" -> false
            else -> null
        }
        if (state == null || words.size > 3) {
            plugin.messageManager.sendMessage(player, "plot.perm-usage")
            return
        }
        var groupStr: String? = null
        var actionStr: String? = null
        when (words.size) {
            2 -> if (words[0] in PERM_GROUPS) groupStr = words[0] else actionStr = words[0]
            3 -> {
                groupStr = words[0]
                actionStr = words[1]
            }
        }

        // Towny's names for the groups; on a resident's plot "town" is the owner's town. A plot
        // of the town has no owner and so no friends: a friend setting there would be stored and
        // never used, so it is refused and nothing changes. Towny 0.96 read "friend" as
        // "resident" here instead; refusing was chosen so that nobody opens town land to every
        // resident while meaning a few friends (decided 2026-11-27).
        val townOwned = plot.ownerUuid == null
        if (groupStr == "friend" && townOwned) {
            plugin.messageManager.sendMessage(player, "plot.perm-friend-town-plot")
            return
        }
        val chosen: String? = when (groupStr) {
            null -> null
            "town" -> "resident"
            "friend" -> "friend"
            in PERM_GROUPS -> groupStr
            else -> {
                plugin.messageManager.sendMessage(player, "plot.perm-usage")
                return
            }
        }
        val groups: List<String> = when {
            chosen != null -> listOf(chosen)
            townOwned -> listOf("resident", "ally", "outsider", "nation")
            else -> listOf("friend", "resident", "ally", "outsider", "nation")
        }

        val actions: List<Char> = when (actionStr) {
            null -> listOf('B', 'D', 'S', 'I')
            "build" -> listOf('B')
            "destroy" -> listOf('D')
            "switch" -> listOf('S')
            "item", "itemuse" -> listOf('I')
            else -> {
                plugin.messageManager.sendMessage(player, "plot.perm-usage")
                return
            }
        }

        val town = townManager.getTown(plot.townUuid).orElse(null)
        val owner = plot.ownerUuid?.let { residentManager.getResident(it).orElse(null) }
        for (group in groups) {
            // Unset plot perms inherit (owner's defaults or the town's); start from those so
            // toggling one action doesn't silently revoke the others.
            var current: String? = when (group) {
                "friend" -> plot.permsFriend
                "resident" -> plot.permsResident
                "ally" -> plot.permsAlly
                "outsider" -> plot.permsOutsider
                else -> plot.permsNation
            } ?: PermissionMatrix.inheritedPlotPerms(plot, town, owner, group) ?: ""

            for (action in actions) {
                current = net.azisaba.townia.data.PermissionMatrix.setPerm(current, action, state)
            }

            if (group == "friend") plot.permsFriend = current
            else if (group == "resident") plot.permsResident = current
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
            (if (chosen != null) "$chosen " else "") + (actionStr ?: "all"),
            "state",
            if (state) "ON" else "OFF"
        )
    }

    private fun handleClaimAuto(player: Player?) {
        if (player == null) return
        if (!plugin.hasEconomy()) {
            plugin.messageManager.sendMessage(player, "error.no-vault")
            return
        }
        val res = residentManager.getResident(player.uniqueId).orElse(null)
        if (res == null || res.townUuid == null) {
            plugin.messageManager.sendMessage(player, "error.not-in-town")
            return
        }
        var bought = 0
        var skipped = 0
        for (chunk in collectRangeChunks(player.location.chunk, "circle", 3)) {
            val plot = plotManager.getPlot(chunk).orElse(null)
            if (plot == null || !plot.isForSale || plot.ownerUuid != null || plot.townUuid != res.townUuid) {
                skipped++
                continue
            }
            val price = plot.price
            if (!plugin.economy!!.has(player, price)) {
                skipped++
                continue
            }
            val response = plugin.economy!!.withdrawPlayer(player, price)
            if (!response.transactionSuccess()) {
                skipped++
                continue
            }
            try {
                townManager.addBalance(plot.townUuid, price)
                plotManager.transferOwnership(plot.worldName, plot.chunkX, plot.chunkZ, player.uniqueId)
                bought++
            } catch (_: Exception) {
                plugin.economy!!.depositPlayer(player, price)
                skipped++
            }
        }
        plugin.messageManager.sendMessage(player, "plot.auto-claimed", "claimed", bought.toString(), "skipped", skipped.toString())
    }

    private fun handleClear(player: Player?) {
        if (player == null) return
        val plot = plotManager.getPlot(player.location.chunk).orElse(null)
        if (plot == null) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }
        requireMayorOrOwner(player, plot) ?: return
        plot.name = null
        plot.plotType = net.azisaba.townia.data.PlotType.DEFAULT
        plot.setPvp(false)
        plot.setMobs(false)
        plot.setExplosions(false)
        plot.setFire(false)
        plot.clearPerms()
        // Like Towny's /plot clear: remove every sign (standing, wall, hanging) in the plot.
        // Signs are tile entities, so scanning the chunk's tile entities covers all heights cheaply.
        player.location.chunk.tileEntities
            .filterIsInstance<org.bukkit.block.Sign>()
            .map { it.block }
            .forEach { it.setType(org.bukkit.Material.AIR, false) }
        try {
            plugin.databaseManager.savePlot(plot)
            plugin.messageManager.sendMessage(player, "plot.reset")
        } catch (_: SQLException) {
            plugin.messageManager.sendMessage(player, "error.database")
        }
    }

    /** `/plot unclaim`: the owner hands the plot back to the town (a mayor or assistant may do it for them). */
    private fun handleUnclaim(player: Player) {
        val plot = plotManager.getPlot(player.location.chunk).orElse(null)
        if (plot == null) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }
        if (plot.ownerUuid == null) {
            plugin.messageManager.sendMessage(player, "plot.not-owned")
            return
        }
        requireMayorOrOwner(player, plot) ?: return
        try {
            plotManager.transferOwnership(plot.worldName, plot.chunkX, plot.chunkZ, null)
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(player, e.messageKey ?: "error.database", *e.replacements.filterNotNull().toTypedArray())
            return
        }
        plugin.messageManager.sendMessage(player, "plot.returned")
    }

    private fun handleEvict(player: Player?) {
        if (player == null) return
        val plot = plotManager.getPlot(player.location.chunk).orElse(null)
        if (plot == null) {
            plugin.messageManager.sendMessage(player, "plot.no-plot-here")
            return
        }
        val res = residentManager.getResident(player.uniqueId).orElse(null)
        if (!TowniaAdminCommand.isBypassing(player) &&
            (res == null || res.townUuid != plot.townUuid || !Perm.has(player, "towny.command.plot.evict"))
        ) {
            plugin.messageManager.sendMessage(player, "error.no-permission")
            return
        }
        if (plot.ownerUuid == null) {
            plugin.messageManager.sendMessage(player, "plot.already-town-owned")
            return
        }
        plot.changeOwner(null)
        plot.isForSale = false
        plot.price = 0.0
        try {
            plugin.databaseManager.savePlot(plot)
            plugin.messageManager.sendMessage(player, "plot.evicted")
        } catch (_: SQLException) {
            plugin.messageManager.sendMessage(player, "error.database")
        }
    }

    companion object {
        private const val MAX_RANGE_RADIUS = 10
        private val PERM_GROUPS = setOf("friend", "town", "resident", "ally", "nation", "outsider")
    }
}
