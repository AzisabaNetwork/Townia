package net.azisaba.townia.command

import net.azisaba.townia.Townia
import net.azisaba.townia.TowniaException
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.manager.NationManager
import net.azisaba.townia.manager.PlotManager
import net.azisaba.townia.manager.ResidentManager
import net.azisaba.townia.manager.TownManager
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.util.StringUtil
import java.sql.SQLException
import java.util.*
import java.util.logging.Level

class TowniaAdminCommand(private val plugin: Townia) : CommandExecutor, TabCompleter {
    private val townManager: TownManager
    private val nationManager: NationManager
    private val residentManager: ResidentManager
    private val plotManager: PlotManager

    init {
        this.townManager = plugin.townManager
        this.nationManager = plugin.nationManager
        this.residentManager = plugin.residentManager
        this.plotManager = plugin.plotManager
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("townia.admin")) {
            plugin.messageManager.sendMessage(sender, "error.no-permission")
            return true
        }

        if (args.size == 0) {
            sendHelp(sender)
            return true
        }

        when (args[0]!!.lowercase(Locale.getDefault())) {
            "reload" -> handleReload(sender)
            "bypass" -> handleBypass(sender)
            "forceclaim" -> handleForceClaim(sender, args)
            "forceunclaim" -> handleForceUnclaim(sender)
            "deletetown" -> handleDeleteTown(sender, args)
            "deletenation" -> handleDeleteNation(sender, args)
            "givebonus" -> handleGiveBonus(sender, args)
            "migrate" -> net.azisaba.townia.migration.TownyMigrator.migrate(plugin, sender)
            else -> sendHelp(sender)
        }
        return true
    }

    private fun handleReload(sender: CommandSender) {
        plugin.towniaConfig.reload()
        plugin.messageManager.loadAllMessages()
        plugin.messageManager.sendMessage(sender, "admin.reloaded")
    }

    private fun handleBypass(sender: CommandSender) {
        val player = requirePlayer(sender)
        if (player == null) return

        val uuid = player.getUniqueId()
        if (bypassSet.contains(uuid)) {
            bypassSet.remove(uuid)
            plugin.messageManager.sendMessage(sender, "admin.bypass-off")
        } else {
            bypassSet.add(uuid)
            plugin.messageManager.sendMessage(sender, "admin.bypass-on")
        }
    }

    private fun handleForceClaim(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender)
        if (player == null) return

        val chunk = player.getLocation().getChunk()
        val townUuid: UUID

        if (args.size >= 2) {
            val townOpt: Optional<Town> = townManager.getTownByName(args[1])
            if (townOpt.isEmpty()) {
                plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
                return
            }
            townUuid = townOpt.get().id!!
        } else {
            val resOpt: Optional<TowniaPlayer> = residentManager.getResident(player.getUniqueId())
            if (resOpt.isEmpty() || !resOpt.get().isInTown) {
                plugin.messageManager.sendMessage(sender, "error.not-in-town")
                return
            }
            townUuid = resOpt.get().townUuid!!
        }

        try {
            plotManager.forceClaimChunk(townUuid, chunk)
            plugin.messageManager.sendMessage(sender, "admin.force-claimed", "town", "Unknown")
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *(e.replacements?.filterNotNull()?.toTypedArray() ?: emptyArray()))
        }
    }

    private fun handleForceUnclaim(sender: CommandSender) {
        val player = requirePlayer(sender)
        if (player == null) return

        val chunk = player.getLocation().getChunk()
        if (plotManager.isClaimed(chunk)) {
            plugin.messageManager.sendMessage(sender, "town.chunk-not-claimed")
            return
        }

        try {
            plotManager.forceUnclaimChunk(chunk)
            plugin.messageManager.sendMessage(sender, "admin.force-unclaimed")
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *(e.replacements?.filterNotNull()?.toTypedArray() ?: emptyArray()))
        }
    }

    private fun handleDeleteTown(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val townName = args[1]
        val townOpt: Optional<Town> = townManager.getTownByName(townName)
        if (townOpt.isEmpty()) {
            plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
            return
        }
        val town: Town = townOpt.get()
        val townUuid: UUID = town.id!!

        try {
            // Clear residents
            residentManager.getResidentsByTown(townUuid)
                .forEach({ res -> residentManager.clearTown(res.uuid) })

            // Delete invites
            plugin.databaseManager.deleteInvitesByTown(townUuid)

            // Delete town
            townManager.deleteTown(townUuid)
            plugin.messageManager.sendMessage(sender, "admin.town-deleted", "town", townName)
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *(e.replacements?.filterNotNull()?.toTypedArray() ?: emptyArray()))
        } catch (e: SQLException) {
            plugin.getLogger().log(Level.SEVERE, "DB error deleting town via admin", e)
            plugin.messageManager.sendMessage(sender, "error.database")
        }
    }

    private fun handleDeleteNation(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val nationName = args[1]
        val nationOpt: Optional<Nation> = nationManager.getNationByName(nationName)
        if (nationOpt.isEmpty()) {
            plugin.messageManager.sendMessage(sender, "error.nation-not-found", "nation", "Unknown")
            return
        }

        try {
            nationManager.deleteNation(nationOpt.get().id!!)
            plugin.messageManager.sendMessage(sender, "admin.nation-deleted", "nation", nationName)
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *(e.replacements?.filterNotNull()?.toTypedArray() ?: emptyArray()))
        }
    }

    private fun handleGiveBonus(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val townOpt: Optional<Town> = townManager.getTownByName(args[1])
        if (townOpt.isEmpty()) {
            plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
            return
        }

        val amount: Int
        try {
            amount = args[2]!!.toInt()
            if (amount < 0) throw NumberFormatException()
        } catch (e: NumberFormatException) {
            plugin.messageManager.sendMessage(sender, "error.invalid-amount")
            return
        }

        try {
            townManager.setBonusClaims(townOpt.get().id, amount)
            plugin.messageManager.sendMessage(
                sender, "admin.bonus-given",
                "{town}", args[1], "{amount}", amount.toString()
            )
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *(e.replacements?.filterNotNull()?.toTypedArray() ?: emptyArray()))
        }
    }

    private fun sendHelp(sender: CommandSender) {
        plugin.messageManager.sendMessage(sender, "admin.help")
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender !is Player) {
            plugin.messageManager.sendMessage(sender, "error.player-only")
            return null
        }
        return sender
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): MutableList<String>? {
        if (!sender.hasPermission("townia.admin")) return mutableListOf<String>()

        val completions = ArrayList<String>()
        if (args.size == 1) {
            StringUtil.copyPartialMatches(
                args[0]!!,
                mutableListOf<String>(
                    "reload", "bypass", "forceclaim", "forceunclaim",
                    "deletetown", "deletenation", "givebonus", "migrate"
                ),
                completions
            )
        } else if (args.size == 2) {
            when (args[0]!!.lowercase(Locale.getDefault())) {
                "forceclaim", "deletetown", "givebonus" -> {
                    val townNames = townManager.allTowns.stream()
                        .map { it.name }.toList()
                    StringUtil.copyPartialMatches(args[1]!!, townNames, completions)
                }

                "deletenation" -> {
                    val nationNames = nationManager.allNations.stream()
                        .map { it.name }.toList()
                    StringUtil.copyPartialMatches(args[1]!!, nationNames, completions)
                }
            }
        }
        return completions
    }

    companion object {
        private val bypassSet: MutableSet<UUID> = Collections.synchronizedSet<UUID>(HashSet<UUID>())

        fun isBypassing(uuid: UUID): Boolean {
            return bypassSet.contains(uuid)
        }
    }
}