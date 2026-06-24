package net.azisaba.townia.command

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TowniaPlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.util.StringUtil
import java.util.*

class TownConfigCommand(private val plugin: Townia) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            plugin.messageManager.sendMessage(sender, "error.player-only")
            return true
        }

        if (args.size < 2) {
            sendHelp(sender)
            return true
        }

        val resOpt: Optional<TowniaPlayer> = plugin.residentManager.getResident(sender.getUniqueId())
        if (resOpt.isEmpty() || !resOpt.get().isInTown) {
            plugin.messageManager.sendMessage(sender, "error.not-in-town")
            return true
        }
        val res: TowniaPlayer = resOpt.get()

        if (!res.isMayor) {
            plugin.messageManager.sendMessage(sender, "town.not-mayor")
            return true
        }

        val townOpt: Optional<Town> = plugin.townManager.getTown(res.townUuid)
        if (townOpt.isEmpty()) {
            plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
            return true
        }
        val town: Town = townOpt.get()

        val key = args[0]!!.lowercase(Locale.getDefault())
        val valueStr = args[1]!!.lowercase(Locale.getDefault())

        when (key) {
            "allowinvisibility" -> {
                val `val` = parseBoolean(valueStr, true)
                town.isAllowInvisibility = `val`
                plugin.townManager.saveTown(town)
                plugin.messageManager.sendMessage(
                    sender, "townconfig.set",
                    "key", "allowInvisibility", "value", `val`.toString()
                )
            }

            "allowsit" -> {
                val `val` = parseBoolean(valueStr, true)
                town.isAllowSit = `val`
                plugin.townManager.saveTown(town)
                plugin.messageManager.sendMessage(
                    sender, "townconfig.set",
                    "key", "allowSit", "value", `val`.toString()
                )
            }

            "allowpetpickup" -> {
                val `val` = parseBoolean(valueStr, true)
                town.isAllowPetPickup = `val`
                plugin.townManager.saveTown(town)
                plugin.messageManager.sendMessage(
                    sender, "townconfig.set",
                    "key", "allowPetPickup", "value", `val`.toString()
                )
            }

            "allowpassenger" -> {
                val `val` = parseBoolean(valueStr, true)
                town.isAllowPassenger = `val`
                plugin.townManager.saveTown(town)
                plugin.messageManager.sendMessage(
                    sender, "townconfig.set",
                    "key", "allowPassenger", "value", `val`.toString()
                )
            }

            else -> sendHelp(sender)
        }
        return true
    }

    private fun parseBoolean(s: String, def: Boolean): Boolean {
        if (s.equals("true", ignoreCase = true) || s.equals("yes", ignoreCase = true) || s == "1") return true
        if (s.equals("false", ignoreCase = true) || s.equals("no", ignoreCase = true) || s == "0") return false
        return def
    }

    private fun sendHelp(sender: CommandSender) {
        plugin.messageManager.sendMessage(sender, "townconfig.help")
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): MutableList<String?> {
        val completions: MutableList<String?> = ArrayList<String?>()
        if (args.size == 1) {
            StringUtil.copyPartialMatches<MutableList<String?>?>(
                args[0]!!,
                mutableListOf<String?>("allowInvisibility", "allowSit", "allowPetPickup", "allowPassenger"),
                completions
            )
        } else if (args.size == 2) {
            StringUtil.copyPartialMatches<MutableList<String?>?>(
                args[1]!!,
                mutableListOf<String?>("true", "false"),
                completions
            )
        }
        return completions
    }
}