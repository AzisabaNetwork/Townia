package net.azisaba.townia.command

import net.azisaba.townia.Townia
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.util.StringUtil
import java.util.*

class TowniaWorldCommand(private val plugin: Townia) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("townia.admin.world")) {
            plugin.messageManager.sendMessage(sender, "error.no-permission")
            return true
        }

        if (args.isEmpty()) {
            handleList(sender)
            return true
        }

        when (args[0].lowercase(Locale.getDefault())) {
            "list" -> handleList(sender)
            "add" -> {
                if (args.size < 2) {
                    plugin.messageManager.sendMessage(sender, "error.invalid-args")
                    return true
                }
                handleAdd(sender, args[1])
            }

            "remove" -> {
                if (args.size < 2) {
                    plugin.messageManager.sendMessage(sender, "error.invalid-args")
                    return true
                }
                handleRemove(sender, args[1])
            }

            else -> handleList(sender)
        }
        return true
    }

    private fun handleList(sender: CommandSender) {
        val worlds = plugin.towniaConfig.allowedWorlds
        plugin.messageManager.sendMessageWithoutPrefix(
            sender, "world.list-header",
            "count", worlds.size.toString()
        )
        for (world in worlds) {
            plugin.messageManager.sendMessageWithoutPrefix(sender, "world.list-entry", "world", world)
        }
    }

    private fun handleAdd(sender: CommandSender, worldName: String) {
        if (plugin.towniaConfig.isWorldAllowed(worldName)) {
            plugin.messageManager.sendMessage(sender, "world.already-allowed", "world", worldName)
            return
        }

        val current = ArrayList(plugin.towniaConfig.allowedWorlds)
        current.add(worldName)

        plugin.getConfig().set("allowed-worlds", current)
        plugin.saveConfig()
        plugin.towniaConfig.reload()

        plugin.messageManager.sendMessage(sender, "world.added", "world", worldName)
    }

    private fun handleRemove(sender: CommandSender, worldName: String) {
        if (!plugin.towniaConfig.isWorldAllowed(worldName)) {
            plugin.messageManager.sendMessage(sender, "world.not-allowed", "world", worldName)
            return
        }

        val current = ArrayList(plugin.towniaConfig.allowedWorlds)
        current.remove(worldName)

        plugin.getConfig().set("allowed-worlds", current)
        plugin.saveConfig()
        plugin.towniaConfig.reload()

        plugin.messageManager.sendMessage(sender, "world.removed", "world", worldName)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): MutableList<String?> {
        if (!sender.hasPermission("townia.admin.world")) return ArrayList<String?>()

        val completions: MutableList<String?> = ArrayList<String?>()
        if (args.size == 1) {
            StringUtil.copyPartialMatches(
                args[0],
                mutableListOf<String?>("list", "add", "remove"),
                completions
            )
        } else if (args.size == 2) {
            if (args[0].equals("add", ignoreCase = true)) {
                val worldNames: MutableList<String?> = ArrayList<String?>()
                for (w in Bukkit.getWorlds()) worldNames.add(w.name)
                StringUtil.copyPartialMatches(args[1], worldNames, completions)
            } else if (args[0].equals("remove", ignoreCase = true)) {
                StringUtil.copyPartialMatches(
                    args[1],
                    plugin.towniaConfig.allowedWorlds, completions
                )
            }
        }
        return completions
    }
}