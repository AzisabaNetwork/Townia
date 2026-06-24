package net.azisaba.townia.command

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Nation
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
            showResidentInfo(sender, player.uniqueId.toString(), player.name)
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
            "friend" -> handleFriend(sender, args)
            "?", "help" -> plugin.messageManager.sendMessage(sender, "townia.help")
            else -> {
                val targetName = args[0]
                val targetOpt: Optional<TowniaPlayer> = residentManager.getResidentByName(targetName)
                if (targetOpt.isEmpty) {
                    plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", targetName)
                    return true
                }
                val target: TowniaPlayer = targetOpt.get()
                showResidentInfo(sender, target.uuid.toString(), (target.name ?: "Unknown"))
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

        val res: TowniaPlayer = residentManager.getOrCreate(player)!!

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

    private fun showResidentInfo(sender: CommandSender, uuidStr: String, name: String?) {
        val uuid = UUID.fromString(uuidStr)
        val resOpt: Optional<TowniaPlayer> = residentManager.getResident(uuid)
        if (resOpt.isEmpty) {
            plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", uuid.toString())
            return
        }

        val res: TowniaPlayer = resOpt.get()

        var townName = "None"
        var rankName: String? = "None"
        var nationName = "None"

        if (res.isInTown) {
            val townOpt: Optional<Town> = townManager.getTown(res.townUuid)
            if (townOpt.isPresent) {
                val town: Town = townOpt.get()
                townName = (town.name ?: "")
                rankName = res.rank.name

                if (town.isInNation) {
                    val nationOpt: Optional<Nation> = nationManager.getNation(town.nationUuid)
                    nationName =
                        nationOpt.map({ it.name ?: "None" }).orElse("None")
                }
            }
        }

        val lastSeen = DATE_FMT.format(Instant.ofEpochMilli(res.lastSeen))
        val friends: String? =
            if (res.friends!!.isEmpty()) "None" else res.friends!!.size.toString()

        var balance: String? = "0"
        if (plugin.hasEconomy()) {
            val offlinePlayer = Bukkit.getOfflinePlayer(uuid)
            balance = String.format("%.2f", plugin.economy!!.getBalance(offlinePlayer))
        }

        plugin.messageManager.sendMessage(
            sender, "resident.info",
            "player", (res.name ?: "Unknown"),
            "town", townName,
            "rank", (rankName ?: "None"),
            "nation", nationName,
            "last_seen", lastSeen,
            "balance", (balance ?: "0"),
            "friends", (friends ?: "None")
        )
    }

    private fun showList(sender: CommandSender) {
        val all: MutableList<TowniaPlayer> = residentManager.allResidents
        plugin.messageManager.sendMessage(
            sender, "resident.list-header",
            "count", all.size.toString()
        )
        for (res in all) {
            var townName = "None"
            if (res.isInTown) {
                val townOpt: Optional<Town> = townManager.getTown(res.townUuid)
                townName = townOpt.map({ it.name ?: "None" }).orElse("None")
            }
            plugin.messageManager.sendMessage(
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
        val completions: MutableList<String?> = ArrayList()
        when (args.size) {
            1 -> {
                val options: MutableList<String?> = ArrayList()
                options.add("list")
                options.add("friend")
                for (p in plugin.server.onlinePlayers) {
                    options.add(p.name)
                }
                StringUtil.copyPartialMatches(args[0], options, completions)
            }

            2 if args[0].equals("friend", ignoreCase = true) -> {
                val options = mutableListOf<String?>("add", "remove", "list", "clear")
                StringUtil.copyPartialMatches(args[1], options, completions)
            }

            3 if args[0].equals("friend", ignoreCase = true) && (args[1].equals(
                "add",
                ignoreCase = true
            ) || args[1].equals("remove", ignoreCase = true))
                -> {
                val options: MutableList<String?> = ArrayList()
                for (p in plugin.server.onlinePlayers) {
                    options.add(p.name)
                }
                StringUtil.copyPartialMatches(args[2], options, completions)
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


    private fun handleSetPerm(sender: CommandSender, args: Array<String>?) {
        plugin.messageManager.sendMessage(sender, "error.not-implemented")
    }

    private fun handleToggle(sender: CommandSender, args: Array<String>?) {
        plugin.messageManager.sendMessage(sender, "error.not-implemented")
    }

    private fun handleSpawn(sender: CommandSender, args: Array<String>?) {
        plugin.messageManager.sendMessage(sender, "error.not-implemented")
    }

    private fun handleTax(sender: CommandSender, args: Array<String>?) {
        plugin.messageManager.sendMessage(sender, "error.not-implemented")
    }

    companion object {
        private val DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}