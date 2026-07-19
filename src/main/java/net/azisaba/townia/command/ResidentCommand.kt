package net.azisaba.townia.command

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.PermissionMatrix
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.manager.NationManager
import net.azisaba.townia.manager.ResidentManager
import net.azisaba.townia.manager.TownManager
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.util.StringUtil

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.function.Function
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.isEmpty
import kotlin.collections.mutableListOf
import kotlin.text.contains
import kotlin.text.equals
import kotlin.text.isEmpty
import kotlin.toString

class ResidentCommand(private val plugin: Townia) : CommandExecutor, TabCompleter {
    private val residentManager: ResidentManager = plugin.residentManager
    private val townManager: TownManager = plugin.townManager
    private val nationManager: NationManager = plugin.nationManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            val player = requirePlayer(sender) ?: return true
            showResidentInfo(sender, residentManager.getOrCreate(player))
            return true
        }

        when (args[0].lowercase(Locale.getDefault())) {
            "list" -> showList(sender)
            "set" -> {
                if (args.size >= 3 && args[1].equals("mode", ignoreCase = true) && args[2].equals(
                        "map",
                        ignoreCase = true
                    )
                ) {
                    if (sender is Player) {
                        sender.performCommand("townia map")
                    } else {
                        plugin.messageManager.sendMessage(sender, "error.player-only")
                    }
                } else if (args.size >= 2 && args[1].equals("perm", ignoreCase = true)) {
                    handleSetPerm(sender, args.toList().toTypedArray())
                } else {
                    plugin.messageManager.sendMessage(sender, "error.invalid-args")
                }
            }

            "toggle" -> handleToggle(sender, args.toList().toTypedArray())
            "spawn" -> handleSpawn(sender, args.toList().toTypedArray())
            "tax" -> handleTax(sender, args.toList().toTypedArray())
            "jail" -> handleJail(sender, args.toList().toTypedArray())
            "friend" -> handleFriend(sender, args)
            "?", "help" -> plugin.messageManager.sendMessage(sender, "townia.help")
            else -> {
                val targetName = args[0]
                val onlineTarget = Bukkit.getPlayerExact(targetName)
                val targetOpt: Optional<TowniaPlayer> = if (onlineTarget != null) {
                    Optional.of(residentManager.getOrCreate(onlineTarget))
                } else {
                    residentManager.getResidentByName(targetName)
                }
                if (targetOpt.isEmpty) {
                    plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", targetName)
                    return true
                }
                showResidentInfo(sender, targetOpt.get())
            }
        }
        return true
    }

    private fun handleFriend(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return
        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "resident.friend.help")
            return
        }

        val res: TowniaPlayer = residentManager.getOrCreate(player)

        when (args[1].lowercase(Locale.getDefault())) {
            "add" -> {
                if (args.size < 3) {
                    plugin.messageManager.sendMessage(sender, "error.invalid-args")
                    return
                }
                val targetOpt: Optional<TowniaPlayer> = residentManager.getResidentByName(args[2])
                if (targetOpt.isEmpty) {
                    plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", args[2])
                    return
                }
                if (targetOpt.get().uuid!! == player.uniqueId) {
                    plugin.messageManager.sendMessage(sender, "resident.friend.cannot-add-self")
                    return
                }
                val target: TowniaPlayer = targetOpt.get()
                if (res.friends!!.contains(target.uuid.toString())) {
                    plugin.messageManager
                        .sendMessage(sender, "resident.friend.already-friend", "player", (target.name ?: "Unknown"))
                    return
                }
                residentManager.addFriend(res, target)
                plugin.messageManager.sendMessage(sender, "resident.friend.added", "player", (target.name ?: "Unknown"))
            }

            "remove" -> {
                if (args.size < 3) {
                    plugin.messageManager.sendMessage(sender, "error.invalid-args")
                    return
                }
                val targetOpt: Optional<TowniaPlayer> = residentManager.getResidentByName(args[2])
                if (targetOpt.isEmpty) {
                    plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", args[2])
                    return
                }
                val target: TowniaPlayer = targetOpt.get()
                if (!res.friends!!.contains(target.uuid.toString())) {
                    plugin.messageManager
                        .sendMessage(sender, "resident.friend.not-friend", "player", (target.name ?: "Unknown"))
                    return
                }
                residentManager.removeFriend(res, target)
                plugin.messageManager.sendMessage(sender, "resident.friend.removed", "player", (target.name ?: "Unknown"))
            }

            "list" -> {
                if (res.friends!!.isEmpty()) {
                    plugin.messageManager.sendMessage(sender, "resident.friend.list-empty")
                    return
                }
                val friendNames: MutableList<String?> = ArrayList()
                for (friendUuidStr in res.friends!!) {
                    residentManager.getResident(UUID.fromString(friendUuidStr))
                        .ifPresent({ f -> friendNames.add(f.name) })
                }
                plugin.messageManager
                    .sendMessage(sender, "resident.friend.list", "friends", friendNames.filterNotNull().joinToString(", "))
            }

            "clear" -> {
                val friendsCopy: MutableList<String> = ArrayList(res.friends!!.filterNotNull())
                for (friendUuidStr in friendsCopy) {
                    residentManager.getResident(UUID.fromString(friendUuidStr))
                        .ifPresent { f -> residentManager.removeFriend(res, f) }
                }
                plugin.messageManager.sendMessage(sender, "resident.friend.cleared")
            }

            else -> plugin.messageManager.sendMessage(sender, "resident.friend.help")
        }
    }

    private fun showResidentInfo(sender: CommandSender, res: TowniaPlayer) {
        val uuid = res.uuid
        if (uuid == null) {
            plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", res.name ?: "Unknown")
            return
        }

        var townName = "None"
        var rankName: String? = "None"
        var nationName = "None"
        var title = ""
        var townInfo = ""
        var nationInfo = ""

        var townMayor = "None"
        var townRegistered = "Unknown"
        var nationLeader = "None"

        if (res.isInTown) {
            val townOpt: Optional<Town> = townManager.getTown(res.townUuid)
            if (townOpt.isPresent) {
                val town: Town = townOpt.get()
                townName = (town.name ?: "")
                rankName = res.rank.name
                val residentsCount = residentManager.getResidentsByTown(town.id!!).size
                townInfo = clickableCount(
                    residentsCount,
                    "town info ${town.id}",
                    "Click to view town information"
                )
                townRegistered = DATE_FMT.format(Instant.ofEpochMilli(town.createdAt))

                val mayorOpt = residentManager.getResident(town.mayorUuid!!)
                if (mayorOpt.isPresent) {
                    val m = mayorOpt.get()
                    townMayor = m.name ?: "Unknown"
                    var mayorPrefix = ""
                    if (town.isInNation) {
                        val nOpt = nationManager.getNation(town.nationUuid)
                        if (nOpt.isPresent && nOpt.get().leaderUuid == m.uuid) {
                            mayorPrefix = "Leader "
                        } else {
                            mayorPrefix = "Mayor "
                        }
                    } else {
                        mayorPrefix = "Mayor "
                    }
                    townMayor = mayorPrefix + townMayor
                }

                if (town.mayorUuid == res.uuid) {
                    title = "Mayor "
                } else if (res.rank == net.azisaba.townia.data.TownRank.CO_MAYOR) {
                    title = "Co-Mayor "
                } else if (res.rank == net.azisaba.townia.data.TownRank.ASSISTANT) {
                    title = "Assistant "
                }

                if (town.isInNation) {
                    val nationOpt: Optional<Nation> = nationManager.getNation(town.nationUuid)
                    if (nationOpt.isPresent) {
                        val nation = nationOpt.get()
                        nationName = nation.name ?: "None"
                        val townsCount = nationManager.getTownsInNation(nation.id!!).size
                        nationInfo = clickableCount(
                            townsCount,
                            "nation info ${nation.id}",
                            "Click to view nation information"
                        )
                        
                        val leaderOpt = residentManager.getResident(nation.leaderUuid!!)
                        if (leaderOpt.isPresent) {
                            val l = leaderOpt.get()
                            nationLeader = "Leader " + (l.name ?: "Unknown")
                        }

                        if (nation.leaderUuid == res.uuid) {
                            title = "Leader "
                        }
                    }
                }
            }
        }

        val offlinePlayer = Bukkit.getOfflinePlayer(uuid)
        val lastSeen = if (offlinePlayer.isOnline) "Online" else DATE_FMT.format(Instant.ofEpochMilli(res.lastSeen))
        val friends: String? =
            if (res.friends!!.isEmpty()) "None" else res.friends!!.size.toString()
        val plotsCount = plugin.plotManager.countPlotsByOwner(uuid)

        var balance = plugin.messageManager.getPlainMessage(sender, "common.not-set")
        val registeredMillis = when {
            res.registeredAt > 0 -> res.registeredAt
            offlinePlayer.firstPlayed > 0 -> offlinePlayer.firstPlayed
            else -> System.currentTimeMillis()
        }
        val registered = DATE_FMT.format(Instant.ofEpochMilli(registeredMillis))

        if (plugin.hasEconomy()) {
            balance = plugin.economy!!.format(plugin.economy!!.getBalance(offlinePlayer)).replace("[^\\d.,-]".toRegex(), "")
        }

        plugin.messageManager.sendMessageWithoutPrefix(
            sender, "resident.info",
            "player", (res.name ?: "Unknown"),
            "title", title,
            "about", "/res set about [msg]",
            "town", townName,
            "town_info", townInfo,
            "town_mayor", townMayor,
            "town_registered", townRegistered,
            "town_residents", townInfo.replace("[", "").replace("]", ""),
            "rank", (rankName ?: "None"),
            "nation", nationName,
            "nation_info", nationInfo,
            "nation_leader", nationLeader,
            "nation_towns", nationInfo.replace("[", "").replace("]", ""),
            "last_seen", lastSeen,
            "balance", balance,
            "friends", (friends ?: "None"),
            "registered", registered,
            "plots", plotsCount.toString(),
            "perms_build", formatPerm(res, 'B'),
            "perms_destroy", formatPerm(res, 'D'),
            "perms_switch", formatPerm(res, 'S'),
            "perms_item", formatPerm(res, 'I'),
            "pvp", "N/A",
            "explosions", "N/A",
            "fire", "N/A",
            "mobs", "N/A"
        )
    }

    private fun formatPerm(res: TowniaPlayer, action: Char): String {
        val sb = java.lang.StringBuilder()
        sb.append(if ((res.defaultPermsFriend?.indexOf(action) ?: -1) >= 0) "F" else "-")
        sb.append(if ((res.defaultPermsResident?.indexOf(action) ?: -1) >= 0) "R" else "-")
        sb.append(if ((res.defaultPermsAlly?.indexOf(action) ?: -1) >= 0) "A" else "-")
        sb.append(if ((res.defaultPermsOutsider?.indexOf(action) ?: -1) >= 0) "O" else "-")
        return sb.toString()
    }

    private fun clickableCount(count: Int, command: String, hover: String): String {
        return "<click:run_command:'/$command'><hover:show_text:'$hover'>[$count]</hover></click>"
    }

    private fun showList(sender: CommandSender) {
        val all: MutableList<TowniaPlayer> = residentManager.allResidents
        plugin.messageManager.sendMessageWithoutPrefix(
            sender, "resident.list-header",
            "count", all.size.toString()
        )
        for (res in all) {
            var townName = "None"
            if (res.isInTown) {
                val townOpt: Optional<Town> = townManager.getTown(res.townUuid)
                townName = townOpt.map({ it.name ?: "None" }).orElse("None")
            }
            plugin.messageManager.sendMessageWithoutPrefix(
                sender, "resident.list-entry",
                "player", (res.name ?: "Unknown"),
                "town", townName
            )
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): MutableList<String>? {
        val completions: MutableList<String> = ArrayList()
        when (args.size) {
            1 -> {
                val options: MutableList<String> = ArrayList()
                options.add("list")
                options.add("friend")
                options.add("set")
                options.add("toggle")
                options.add("spawn")
                options.add("tax")
                options.add("jail")
                for (p in plugin.server.onlinePlayers) {
                    options.add(p.name)
                }
                StringUtil.copyPartialMatches(args[0], options, completions)
            }

            2 if args[0].equals("friend", ignoreCase = true) -> {
                val options = mutableListOf<String?>("add", "remove", "list", "clear")
                StringUtil.copyPartialMatches(args[1], options, completions)
            }

            2 if args[0].equals("set", ignoreCase = true) -> {
                StringUtil.copyPartialMatches(args[1], mutableListOf<String?>("perm"), completions)
            }

            2 if args[0].equals("toggle", ignoreCase = true) -> {
                StringUtil.copyPartialMatches(
                    args[1],
                    mutableListOf<String?>("map", "townclaim", "plotborder", "pvp", "fire", "explosions", "mobs", "reset"),
                    completions
                )
            }

            2 if args[0].equals("jail", ignoreCase = true) -> {
                StringUtil.copyPartialMatches(args[1], mutableListOf<String?>("paybail"), completions)
            }

            3 if args[0].equals("set", ignoreCase = true) && args[1].equals("perm", ignoreCase = true) -> {
                StringUtil.copyPartialMatches(
                    args[2],
                    mutableListOf<String?>("friend", "ally", "outsider", "resident", "build", "destroy", "switch", "itemuse", "on", "off", "reset"),
                    completions
                )
            }

            3 if args[0].equals("friend", ignoreCase = true) && (args[1].equals(
                "add",
                ignoreCase = true
            ) || args[1].equals("remove", ignoreCase = true))
                -> {
                val options: MutableList<String> = ArrayList()
                for (p in plugin.server.onlinePlayers) {
                    options.add(p.name)
                }
                StringUtil.copyPartialMatches(args[2], options, completions)
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


    private fun handleSetPerm(sender: CommandSender, args: Array<String>?) {
        val player = requirePlayer(sender) ?: return
        val res = residentManager.getOrCreate(player)
        val parts = args?.drop(2) ?: emptyList()
        if (parts.isEmpty()) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        if (parts[0].equals("reset", ignoreCase = true)) {
            applyDefaultPermsToOwnedPlots(res)
            plugin.messageManager.sendMessage(sender, "resident.perm-applied")
            return
        }

        val group = parts.getOrNull(0)?.takeIf { it.equals("friend", true) || it.equals("ally", true) || it.equals("outsider", true) || it.equals("resident", true) }
        val action = if (group == null) parts.getOrNull(0) else parts.getOrNull(1)
        val stateText = if (group == null) parts.getOrNull(1) ?: parts.getOrNull(0) else parts.getOrNull(2) ?: parts.getOrNull(1)
        val state = parseState(stateText)
        if (state == null) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val groups = if (group == null || group.equals("all", true)) listOf("friend", "ally", "outsider", "resident") else listOf(group.lowercase(Locale.getDefault()))
        val actions = resolvePermActions(if (stateText == action) null else action)
        if (actions == null) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        for (g in groups) {
            var current = when (g) {
                "friend" -> res.defaultPermsFriend ?: ""
                "ally" -> res.defaultPermsAlly ?: ""
                "outsider" -> res.defaultPermsOutsider ?: ""
                else -> res.defaultPermsResident ?: ""
            }
            for (a in actions) current = PermissionMatrix.setPerm(current, a, state)
            when (g) {
                "friend" -> res.defaultPermsFriend = current
                "ally" -> res.defaultPermsAlly = current
                "outsider" -> res.defaultPermsOutsider = current
                else -> res.defaultPermsResident = current
            }
        }
        residentManager.saveResident(res)
        plugin.messageManager.sendMessage(sender, "resident.perm-updated")
    }

    private fun handleToggle(sender: CommandSender, args: Array<String>?) {
        val player = requirePlayer(sender) ?: return
        val res = residentManager.getOrCreate(player)
        if (args == null || args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        when (args[1].lowercase(Locale.getDefault())) {
            "map" -> {
                res.isToggleMap = !res.isToggleMap
                residentManager.saveResident(res)
                plugin.messageManager.sendMessage(sender, "resident.toggle-set", "setting", "map", "state", stateName(res.isToggleMap))
            }
            "townclaim" -> {
                res.isToggleTownClaim = !res.isToggleTownClaim
                residentManager.saveResident(res)
                plugin.messageManager.sendMessage(sender, "resident.toggle-set", "setting", "townclaim", "state", stateName(res.isToggleTownClaim))
            }
            "plotborder", "townborder", "constantplotborder" -> {
                res.isTogglePlotBorder = !res.isTogglePlotBorder
                residentManager.saveResident(res)
                plugin.messageManager.sendMessage(sender, "resident.toggle-set", "setting", "plotborder", "state", stateName(res.isTogglePlotBorder))
            }
            "pvp", "fire", "explosion", "explosions", "mobs", "mob" -> {
                toggleOwnedPlots(sender, res, args[1].lowercase(Locale.getDefault()))
            }
            "reset" -> {
                res.isToggleMap = false
                res.isToggleTownClaim = false
                res.isTogglePlotBorder = false
                residentManager.saveResident(res)
                plugin.messageManager.sendMessage(sender, "resident.toggle-reset")
            }
            else -> plugin.messageManager.sendMessage(sender, "error.invalid-args")
        }
    }

    private fun handleSpawn(sender: CommandSender, args: Array<String>?) {
        val player = requirePlayer(sender) ?: return
        val bed = player.respawnLocation
        if (bed == null) {
            plugin.messageManager.sendMessage(sender, "town.spawn-not-set")
            return
        }
        player.teleport(bed)
        plugin.messageManager.sendMessage(sender, "resident.spawn-teleport")
    }

    private fun handleTax(sender: CommandSender, args: Array<String>?) {
        val player = requirePlayer(sender) ?: return
        val res = residentManager.getOrCreate(player)
        val town = if (res.townUuid != null) townManager.getTown(res.townUuid).orElse(null) else null
        if (town == null) {
            plugin.messageManager.sendMessage(sender, "error.not-in-town")
            return
        }
        plugin.messageManager.sendMessage(sender, "resident.tax", "town_tax", town.taxes.toString(), "plot_tax", town.plotTax.toString())
    }

    private fun parseState(value: String?): Boolean? {
        return when (value?.lowercase(Locale.getDefault())) {
            "on", "true", "yes", "allow" -> true
            "off", "false", "no", "deny" -> false
            else -> null
        }
    }

    private fun resolvePermActions(value: String?): List<Char>? {
        return when (value?.lowercase(Locale.getDefault())) {
            null, "all" -> listOf('B', 'D', 'S', 'I')
            "build" -> listOf('B')
            "destroy" -> listOf('D')
            "switch" -> listOf('S')
            "item", "itemuse" -> listOf('I')
            else -> null
        }
    }

    private fun applyDefaultPermsToOwnedPlots(res: TowniaPlayer) {
        val uuid = res.uuid ?: return
        for (plot in plugin.plotManager.getPlotsByOwner(uuid)) {
            plot.permsResident = res.defaultPermsResident
            plot.permsAlly = res.defaultPermsAlly
            plot.permsOutsider = res.defaultPermsOutsider
            plugin.databaseManager.savePlot(plot)
        }
    }

    private fun toggleOwnedPlots(sender: CommandSender, res: TowniaPlayer, toggle: String) {
        val uuid = res.uuid ?: return
        var count = 0
        for (plot in plugin.plotManager.getPlotsByOwner(uuid)) {
            when (toggle) {
                "pvp" -> plot.setPvp(!plot.hasPvp())
                "fire" -> plot.setFire(!plot.hasFire())
                "explosion", "explosions" -> plot.setExplosions(!plot.hasExplosions())
                "mobs", "mob" -> plot.setMobs(!plot.hasMobs())
            }
            plugin.databaseManager.savePlot(plot)
            count++
        }
        plugin.messageManager.sendMessage(sender, "resident.plots-updated", "count", count.toString())
    }

    private fun handleJail(sender: CommandSender, args: Array<String>?) {
        val player = requirePlayer(sender) ?: return
        val res = residentManager.getOrCreate(player)
        if (args == null || args.size < 2 || !args[1].equals("paybail", ignoreCase = true)) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        if (!res.isJailed || res.jailBail <= 0.0) {
            plugin.messageManager.sendMessage(sender, "jail.no-bail")
            return
        }
        if (!plugin.hasEconomy()) {
            plugin.messageManager.sendMessage(sender, "error.no-vault")
            return
        }
        if (!plugin.economy!!.has(player, res.jailBail)) {
            plugin.messageManager.sendMessage(sender, "error.insufficient-funds", "amount", res.jailBail.toString())
            return
        }
        plugin.economy!!.withdrawPlayer(player, res.jailBail)
        res.jailedTownUuid = null
        res.jailReleaseAt = 0L
        res.jailBail = 0.0
        residentManager.saveResident(res)
        plugin.messageManager.sendMessage(sender, "jail.bail-paid")
    }

    private fun stateName(value: Boolean): String = if (value) "ON" else "OFF"

    companion object {
        private val DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
