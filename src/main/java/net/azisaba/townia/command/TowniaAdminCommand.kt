package net.azisaba.townia.command

import com.palmergames.bukkit.towny.TownyUniverse
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
    private val townManager: TownManager = plugin.townManager
    private val nationManager: NationManager = plugin.nationManager
    private val residentManager: ResidentManager = plugin.residentManager
    private val plotManager: PlotManager = plugin.plotManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("townia.admin")) {
            plugin.messageManager.sendMessage(sender, "error.no-permission")
            return true
        }

        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase(Locale.getDefault())) {
            "reload" -> handleReload(sender)
            "bypass" -> handleBypass(sender)
            "forceclaim" -> handleForceClaim(sender, args)
            "forceunclaim" -> handleForceUnclaim(sender)
            "deletetown" -> handleDeleteTown(sender, args)
            "deletenation" -> handleDeleteNation(sender, args)
            "givebonus" -> handleGiveBonus(sender, args)
            "migrate" -> handleMigrate(sender, args)
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
        val player = requirePlayer(sender) ?: return

        val uuid = player.uniqueId
        if (bypassSet.contains(uuid)) {
            bypassSet.remove(uuid)
            plugin.messageManager.sendMessage(sender, "admin.bypass-off")
        } else {
            bypassSet.add(uuid)
            plugin.messageManager.sendMessage(sender, "admin.bypass-on")
        }
    }

    private fun handleForceClaim(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return

        val chunk = player.location.chunk
        val townUuid: UUID

        if (args.size >= 2) {
            val townOpt: Optional<Town> = townManager.getTownByName(args[1])
            if (townOpt.isEmpty) {
                plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
                return
            }
            townUuid = townOpt.get().id!!
        } else {
            val resOpt: Optional<TowniaPlayer> = residentManager.getResident(player.uniqueId)
            if (resOpt.isEmpty || !resOpt.get().isInTown) {
                plugin.messageManager.sendMessage(sender, "error.not-in-town")
                return
            }
            townUuid = resOpt.get().townUuid!!
        }

        try {
            plotManager.forceClaimChunk(townUuid, chunk)
            plugin.messageManager.sendMessage(sender, "admin.force-claimed", "town", "Unknown")
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun handleForceUnclaim(sender: CommandSender) {
        val player = requirePlayer(sender) ?: return

        val chunk = player.location.chunk
        if (plotManager.isClaimed(chunk)) {
            plugin.messageManager.sendMessage(sender, "town.chunk-not-claimed")
            return
        }

        try {
            plotManager.forceUnclaimChunk(chunk)
            plugin.messageManager.sendMessage(sender, "admin.force-unclaimed")
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun handleDeleteTown(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val townName = args[1]
        val townOpt: Optional<Town> = townManager.getTownByName(townName)
        if (townOpt.isEmpty) {
            plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
            return
        }
        val town: Town = townOpt.get()
        val townUuid: UUID = town.id!!

        try {
            residentManager.getResidentsByTown(townUuid)
                .forEach { res -> residentManager.clearTown(res.uuid) }

            plugin.databaseManager.deleteInvitesByTown(townUuid)

            townManager.deleteTown(townUuid)
            plugin.messageManager.sendMessage(sender, "admin.town-deleted", "town", townName)
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "DB error deleting town via admin", e)
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
            plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun handleGiveBonus(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val townOpt: Optional<Town> = townManager.getTownByName(args[1])
        if (townOpt.isEmpty) {
            plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
            return
        }

        val amount: Int
        try {
            amount = args[2].toInt()
            if (amount < 0) throw NumberFormatException()
        } catch (_: NumberFormatException) {
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
            plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun handleMigrate(sender: CommandSender, args: Array<out String>) {
        if (args.size > 1 && args[1].lowercase(Locale.getDefault()) == "config") {
            net.azisaba.townia.migration.TownyConfigMigrator.migrate(plugin, sender)
        } else if (args.size > 1 && args[1].lowercase(Locale.getDefault()) in setOf("diagnose", "debug")) {
            diagnoseTownyResident(sender, args.getOrNull(2))
        } else if (args.size > 1 && args[1].lowercase(Locale.getDefault()) == "data") {
            migrateTownyData(sender, parseMigrationParts(args.drop(2)))
        } else {
            if (args.size == 1) {
                // Default to data migration if no sub-args provided
                migrateTownyData(sender, emptySet())
            } else {
                plugin.messageManager.sendMessage(sender, "admin.migrate-usage")
            }
        }
    }

    /**
     * Prints the complete UUID-to-town resolution path for one online player.
     * This is intentionally separate from migration so a live server can prove
     * whether the mismatch originates in Towny, Townia's cache, or name lookup.
     */
    private fun diagnoseTownyResident(sender: CommandSender, playerName: String?) {
        if (!plugin.server.pluginManager.isPluginEnabled("Towny")) {
            sender.sendMessage("§c[Townia] Towny is not enabled; Towny API diagnostics are unavailable.")
            return
        }

        val target = when {
            !playerName.isNullOrBlank() -> plugin.server.getPlayerExact(playerName)
            sender is Player -> sender
            else -> null
        }
        if (target == null) {
            sender.sendMessage("§c[Townia] Target must be online. Usage: /towniaadmin migrate diagnose <player>")
            return
        }

        val minecraftUuid = target.uniqueId
        sender.sendMessage("§6[Townia] Town membership diagnostic for §e${target.name}")
        sender.sendMessage("§7Minecraft UUID: §f$minecraftUuid")

        val townyResident = runCatching { TownyUniverse.getInstance().getResident(minecraftUuid) }.getOrNull()
        if (townyResident == null) {
            sender.sendMessage("§cTowny API: no Resident for this UUID")
        } else {
            val townyTown = runCatching { if (townyResident.hasTown()) townyResident.town else null }.getOrNull()
            sender.sendMessage("§aTowny API: §f${townyResident.name} §7(uuid=${townyResident.uuid})")
            sender.sendMessage("§7Towny town: §f${townyTown?.name ?: "<none>"} §7(uuid=${townyTown?.uuid ?: "<none>"})")
        }

        val towniaResident = residentManager.getResident(minecraftUuid).orElse(null)
        val towniaTown = towniaResident?.townUuid?.let { townManager.getTown(it).orElse(null) }
        sender.sendMessage("§bTownia UUID lookup: §f${towniaResident?.name ?: "<none>"} §7(town=${towniaTown?.name ?: "<none>"}, uuid=${towniaResident?.townUuid ?: "<none>"})")

        val nameMatch = residentManager.getResidentByName(target.name).orElse(null)
        if (nameMatch != null && nameMatch.uuid != minecraftUuid) {
            val nameMatchTown = nameMatch.townUuid?.let { townManager.getTown(it).orElse(null) }
            sender.sendMessage("§eTownia name lookup differs: §f${nameMatch.uuid} §7(town=${nameMatchTown?.name ?: "<none>"}, uuid=${nameMatch.townUuid ?: "<none>"})")
        }
        sender.sendMessage("§7Copy these lines from console/chat when reporting the result.")
    }

    private fun migrateTownyData(sender: CommandSender, parts: Set<String>) {
        if (plugin.server.pluginManager.isPluginEnabled("Towny")) {
            net.azisaba.townia.migration.TownyMigrator.migrate(plugin, sender, parts)
        } else {
            net.azisaba.townia.migration.TownyFlatfileMigrator.migrate(plugin, sender, parts)
        }
    }

    private fun parseMigrationParts(raw: List<String>): Set<String> {
        return raw.flatMap { it.split(',', ';') }
            .map { it.trim().lowercase(Locale.getDefault()) }
            .filter { it.isNotEmpty() }
            .toSet()
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
    ): MutableList<String> {
        if (!sender.hasPermission("townia.admin")) return mutableListOf<String>()

        val completions = ArrayList<String>()
        if (args.size == 1) {
            StringUtil.copyPartialMatches(
                args[0],
                mutableListOf(
                    "reload",
                    "bypass",
                    "forceclaim",
                    "forceunclaim",
                    "deletetown",
                    "deletenation",
                    "givebonus",
                    "migrate"
                ),
                completions
            )
        } else if (args.size == 2 && args[0].lowercase(Locale.getDefault()) == "migrate") {
            StringUtil.copyPartialMatches(
                args[1],
                mutableListOf("data", "config", "diagnose"),
                completions
            )
        } else if (args.size == 3 && args[0].lowercase(Locale.getDefault()) == "migrate" && args[1].lowercase(Locale.getDefault()) in setOf("diagnose", "debug")) {
            StringUtil.copyPartialMatches(args[2], plugin.server.onlinePlayers.map { it.name }, completions)
        } else if (args.size >= 3 && args[0].lowercase(Locale.getDefault()) == "migrate" && args[1].lowercase(Locale.getDefault()) == "data") {
            StringUtil.copyPartialMatches(
                args.last(),
                mutableListOf("all", "towns", "nations", "residents", "townblocks", "plots", "jails", "outlaws", "relations"),
                completions
            )
        } else if (args.size == 2) {
            when (args[0].lowercase(Locale.getDefault())) {
                "forceclaim", "deletetown", "givebonus" -> {
                    val townNames = townManager.allTowns.stream()
                        .map { it.name }.toList()
                    StringUtil.copyPartialMatches(args[1], townNames, completions)
                }

                "deletenation" -> {
                    val nationNames = nationManager.allNations.stream()
                        .map { it.name }.toList()
                    StringUtil.copyPartialMatches(args[1], nationNames, completions)
                }
            }
        }
        return completions
    }

    companion object {
        private val bypassSet: MutableSet<UUID> = Collections.synchronizedSet(HashSet())

        fun isBypassing(uuid: UUID): Boolean {
            return bypassSet.contains(uuid)
        }
    }
}
