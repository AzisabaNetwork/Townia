package net.azisaba.townia.command

import com.palmergames.bukkit.towny.TownyUniverse
import net.azisaba.townia.Townia
import net.azisaba.townia.TowniaException
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.manager.NationManager
import net.azisaba.townia.manager.PlotManager
import net.azisaba.townia.manager.ResidentManager
import net.azisaba.townia.manager.TownManager
import org.bukkit.Bukkit
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

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean =
        CommandHelp.withCommand("towniaadmin", args) { execute(sender, command, label, args) }

    private fun execute(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!net.azisaba.townia.permission.Perm.isAdmin(sender)) {
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
            "dedupe" -> handleDedupe(sender, args)
            "mergeresident" -> handleMergeResident(sender, args)
            "setmayor" -> handleSetRank(sender, args, TownRank.MAYOR)
            "addresident" -> handleAddResident(sender, args)
            "setsubmayor" -> handleSetRank(sender, args, TownRank.SUB_MAYOR)
            "setassistant" -> handleSetRank(sender, args, TownRank.ASSISTANT)
            "setrank" -> handleSetRank(sender, args, null)
            "moveresident" -> handleMoveResident(sender, args)
            "kickresident" -> handleKickResident(sender, args)
            "newday" -> {
                net.azisaba.townia.manager.DailyTask(plugin).run()
                plugin.messageManager.sendMessage(sender, "admin.newday-done")
            }
            "checkperm" -> handleCheckPerm(sender, args)
            "town" -> handleTownAdmin(sender, args)
            "nation" -> handleNationAdmin(sender, args)
            "plot" -> handlePlotAdmin(sender, args)
            "set" -> handleSetAdmin(sender, args)
            "?", "help" -> CommandHelp.handleHelp(plugin.messageManager, sender, "towniaadmin", args.drop(1))
            else -> CommandHelp.sendUnknown(plugin.messageManager, sender, "towniaadmin", args[0])
        }
        return true
    }

    private fun handleReload(sender: CommandSender) {
        plugin.towniaConfig.reload()
        plugin.townyPerms.load()
        plugin.townTimers.restart()
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

    /** `/ta checkperm <player> <node>`: does an online player hold a node, townyperms included? */
    private fun handleCheckPerm(sender: CommandSender, args: Array<out String>) {
        val target = args.getOrNull(1)?.let { plugin.server.getPlayerExact(it) }
        val node = args.getOrNull(2)
        if (target == null || node == null) {
            plugin.messageManager.sendMessage(sender, if (args.size < 3) "error.invalid-args" else "admin.diagnose-target-offline")
            return
        }
        val has = net.azisaba.townia.permission.Perm.has(target, node)
        plugin.messageManager.sendMessage(sender, "admin.checkperm", "player", target.name, "node", node, "result", if (has) "true" else "false")
    }

    private fun amountOrNull(sender: CommandSender, raw: String?): Double? {
        val amount = raw?.toDoubleOrNull()
        if (amount == null || amount <= 0.0 || amount.isNaN() || amount.isInfinite()) {
            plugin.messageManager.sendMessage(sender, "error.invalid-amount")
            return null
        }
        return amount
    }

    /** `/ta town <town> deposit|withdraw <amount> | rename <name> | spawn | rank add|remove <player> <rank>`. */
    private fun handleTownAdmin(sender: CommandSender, args: Array<out String>) {
        val town = args.getOrNull(1)?.let { townManager.getTownByName(it).orElse(null) }
        val action = args.getOrNull(2)?.lowercase(Locale.getDefault())
        if (town == null || action == null) {
            plugin.messageManager.sendMessage(sender, if (args.size < 3) "error.invalid-args" else "error.town-not-found", "town", args.getOrNull(1) ?: "")
            return
        }
        try {
            when (action) {
                "deposit", "withdraw" -> {
                    val amount = amountOrNull(sender, args.getOrNull(3)) ?: return
                    if (action == "deposit") townManager.addBalance(town.id, amount) else townManager.subtractBalance(town.id, amount)
                    runCatching {
                        plugin.databaseManager.recordBankTransaction(
                            "TOWN", town.id!!, (sender as? Player)?.uniqueId, if (action == "deposit") amount else -amount, "admin", town.balance
                        )
                    }
                    plugin.messageManager.sendMessage(sender, "admin.bank-changed", "name", town.name ?: "", "balance", String.format("%.2f", town.balance))
                }
                "rename" -> {
                    val name = args.getOrNull(3)
                    if (name == null) {
                        plugin.messageManager.sendMessage(sender, "error.invalid-args")
                        return
                    }
                    townManager.renameTown(town.id, name)
                    plugin.messageManager.sendMessage(sender, "admin.renamed", "name", name)
                }
                "spawn" -> {
                    val player = requirePlayer(sender) ?: return
                    val world = town.spawnWorld?.let { plugin.server.getWorld(it) }
                    if (!town.hasSpawn() || world == null) {
                        plugin.messageManager.sendMessage(sender, "town.spawn-not-set")
                        return
                    }
                    player.teleport(org.bukkit.Location(world, town.spawnX, town.spawnY, town.spawnZ, town.spawnYaw, town.spawnPitch))
                }
                "rank" -> {
                    val add = args.getOrNull(3)?.equals("add", ignoreCase = true)
                    val target = args.getOrNull(4)?.let { residentManager.getResidentByName(it).orElse(null) }
                    val rank = args.getOrNull(5)?.trim()?.lowercase(Locale.ROOT)
                    if (add == null || rank == null || (add == false && !args[3].equals("remove", ignoreCase = true))) {
                        plugin.messageManager.sendMessage(sender, "error.invalid-args")
                        return
                    }
                    if (target == null || target.townUuid != town.id) {
                        plugin.messageManager.sendMessage(sender, "error.player-not-same-town", "player", args.getOrNull(4) ?: "")
                        return
                    }
                    if (rank !in plugin.townyPerms.townRankNames()) {
                        plugin.messageManager.sendMessage(sender, "error.rank-not-found", "{rank}", rank)
                        return
                    }
                    if (add) target.townRanks.add(rank) else target.townRanks.remove(rank)
                    residentManager.saveResident(target)
                    plugin.messageManager.sendMessage(
                        sender, if (add) "town.rank-set" else "town.rank-removed", "player", target.name ?: "", "rank", rank
                    )
                }
                else -> plugin.messageManager.sendMessage(sender, "error.invalid-args")
            }
        } catch (e: TowniaException) {
            sendFailure(sender, e)
        }
    }

    /** `/ta nation <nation> deposit|withdraw <amount> | rename <name>`. */
    private fun handleNationAdmin(sender: CommandSender, args: Array<out String>) {
        val nation = args.getOrNull(1)?.let { plugin.nationManager.getNationByName(it).orElse(null) }
        val action = args.getOrNull(2)?.lowercase(Locale.getDefault())
        if (nation == null || action == null) {
            plugin.messageManager.sendMessage(sender, if (args.size < 3) "error.invalid-args" else "error.nation-not-found", "nation", args.getOrNull(1) ?: "")
            return
        }
        try {
            when (action) {
                "deposit", "withdraw" -> {
                    val amount = amountOrNull(sender, args.getOrNull(3)) ?: return
                    if (action == "deposit") plugin.nationManager.addBalance(nation.id, amount) else plugin.nationManager.subtractBalance(nation.id, amount)
                    plugin.messageManager.sendMessage(sender, "admin.bank-changed", "name", nation.name ?: "", "balance", String.format("%.2f", nation.balance))
                }
                "rename" -> {
                    val name = args.getOrNull(3)
                    if (name == null) {
                        plugin.messageManager.sendMessage(sender, "error.invalid-args")
                        return
                    }
                    plugin.nationManager.renameNation(nation.id, name)
                    plugin.messageManager.sendMessage(sender, "admin.renamed", "name", name)
                }
                else -> plugin.messageManager.sendMessage(sender, "error.invalid-args")
            }
        } catch (e: TowniaException) {
            sendFailure(sender, e)
        }
    }

    /**
     * `/ta plot claim|owner <player|UUID|none> [<world> <x> <z>]`: makes a player the owner of a
     * plot, or (`owner none`) gives the plot back to its town. The plot is the one the sender
     * stands in, or the one holding the given block coordinates, so the console can use it too.
     * As with Towny's `/townyadmin plot claim`, anyone can be made the owner; someone who is not
     * a member of the plot's town is pointed out, since they could lose it again on leaving.
     */
    private fun handlePlotAdmin(sender: CommandSender, args: Array<out String>) {
        val action = args.getOrNull(1)?.lowercase(Locale.ROOT)
        val who = args.getOrNull(2)
        if ((action != "claim" && action != "owner") || who == null || (args.size != 3 && args.size != 6)) {
            plugin.messageManager.sendMessage(sender, "admin.plot-owner-usage")
            return
        }
        val world: String
        val chunkX: Int
        val chunkZ: Int
        if (args.size == 6) {
            val x = args[4].toIntOrNull()
            val z = args[5].toIntOrNull()
            if (x == null || z == null) {
                plugin.messageManager.sendMessage(sender, "admin.plot-owner-usage")
                return
            }
            world = args[3]
            chunkX = Math.floorDiv(x, 16)
            chunkZ = Math.floorDiv(z, 16)
        } else {
            val player = sender as? Player
            if (player == null) {
                // The console stands nowhere: it has to say where.
                plugin.messageManager.sendMessage(sender, "admin.plot-owner-usage")
                return
            }
            val chunk = player.location.chunk
            world = chunk.world.name
            chunkX = chunk.x
            chunkZ = chunk.z
        }
        val plot = plugin.plotManager.getPlot(world, chunkX, chunkZ).orElse(null)
        if (plot == null) {
            plugin.messageManager.sendMessage(sender, "plot.no-plot-here")
            return
        }
        val toTown = action == "owner" && who.lowercase(Locale.ROOT) in setOf("none", "town", "-")
        val target = if (toTown) null else resolveTargetResident(who)
        if (!toTown && target == null) {
            plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", who)
            return
        }
        val townName = townManager.getTown(plot.townUuid).map { it.name ?: "" }.orElse("")
        try {
            plugin.plotManager.transferOwnership(plot.worldName, plot.chunkX, plot.chunkZ, target?.uuid)
        } catch (e: TowniaException) {
            sendFailure(sender, e)
            return
        }
        plugin.logger.info(
            "${sender.name} set the owner of the plot at ${plot.worldName} (chunk ${plot.chunkX}, ${plot.chunkZ}) of $townName to " +
                (target?.let { "${it.name} (${it.uuid})" } ?: "the town")
        )
        if (target == null) {
            plugin.messageManager.sendMessage(sender, "admin.plot-owner-cleared", "town", townName)
            return
        }
        plugin.messageManager.sendMessage(sender, "admin.plot-given", "player", target.name ?: who)
        if (target.townUuid != plot.townUuid) {
            plugin.messageManager.sendMessage(sender, "admin.plot-owner-not-member", "player", target.name ?: who, "town", townName)
        }
    }

    /** `/ta set capital <town>`: makes a town the capital of the nation it is in. */
    private fun handleSetAdmin(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3 || !args[1].equals("capital", ignoreCase = true)) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val town = townManager.getTownByName(args[2]).orElse(null)
        if (town == null || town.nationUuid == null) {
            plugin.messageManager.sendMessage(sender, if (town == null) "error.town-not-found" else "town.not-in-nation", "town", args[2])
            return
        }
        try {
            plugin.nationManager.setCapital(town.nationUuid, town.id)
            plugin.messageManager.sendMessage(sender, "nation.capital-set", "town", town.name ?: args[2])
        } catch (e: TowniaException) {
            sendFailure(sender, e)
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

    private fun resolveTargetResident(key: String): TowniaPlayer? {
        val uuid = runCatching { UUID.fromString(key) }.getOrNull()
        if (uuid != null) {
            return residentManager.getResident(uuid).orElse(null)
        }
        return residentManager.getResidentByName(key).orElse(null)
            ?: Bukkit.getPlayerExact(key)?.let { residentManager.getOrCreate(it) }
    }

    /** Town in args[1] and resident in args[2], or null after telling [sender] what is wrong. */
    private fun resolveTownAndTarget(sender: CommandSender, args: Array<out String>): Pair<Town, TowniaPlayer>? {
        if (args.size < 3) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return null
        }
        val town = townManager.getTownByName(args[1]).orElse(null)
        if (town == null) {
            plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", args[1])
            return null
        }
        val target = resolveTargetResident(args[2])
        if (target == null || target.uuid == null) {
            plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", args[2])
            return null
        }
        dropMissingTown(target)
        return town to target
    }

    /**
     * A resident can still reference a town that no longer exists (a Towny migration that
     * could not import the town). Such a player is townless: clear the reference so they
     * are not refused as "already in a town" that cannot even be named.
     */
    private fun dropMissingTown(target: TowniaPlayer) {
        val townUuid = target.townUuid ?: return
        if (townManager.getTown(townUuid).isPresent) return
        plugin.logger.info("Clearing ${target.name}'s reference to missing town $townUuid")
        residentManager.clearTown(target.uuid)
    }

    private fun townName(townUuid: UUID?): String =
        townUuid?.let { townManager.getTown(it).orElse(null)?.name } ?: "Unknown"

    /** Refuses when [target] belongs to a town other than [town]; points to moveresident. */
    private fun rejectIfInOtherTown(sender: CommandSender, target: TowniaPlayer, town: Town): Boolean {
        if (target.townUuid == null || target.townUuid == town.id) return false
        plugin.messageManager.sendMessage(
            sender, "admin.player-in-other-town",
            "player", target.name ?: "Unknown",
            "town", townName(target.townUuid)
        )
        return true
    }

    /** Refuses when [target] is the mayor of their town: removing or demoting them would leave it without one. */
    private fun rejectIfMayor(sender: CommandSender, target: TowniaPlayer): Boolean {
        val currentTown = target.townUuid?.let { townManager.getTown(it).orElse(null) } ?: return false
        if (currentTown.mayorUuid != target.uuid) return false
        plugin.messageManager.sendMessage(
            sender, "admin.target-is-mayor",
            "player", target.name ?: "Unknown",
            "town", currentTown.name ?: "Unknown"
        )
        return true
    }

    private fun sendFailure(sender: CommandSender, e: TowniaException) {
        plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull().toTypedArray())
    }

    /** Puts [target] into [town] with [rank], clearing an outlaw entry for that town. [target] must be townless or in [town]. */
    private fun placeInTown(target: TowniaPlayer, town: Town, rank: TownRank) {
        if (town.outlaws.contains(target.uuid)) {
            townManager.removeOutlaw(town.id, target.uuid!!)
        }
        if (target.townUuid == town.id) {
            residentManager.setRank(target.uuid, rank)
        } else {
            residentManager.setTown(target.uuid, town.id, rank)
        }
    }

    /** Tells [target], if online, that an admin changed their town. */
    private fun notifyTarget(target: TowniaPlayer, key: String, town: String) {
        target.uuid?.let { plugin.server.getPlayer(it) }?.let { plugin.messageManager.sendMessage(it, key, "town", town) }
    }

    /** `/ta addresident <town> <player>`: forces a townless [player] into [town] as a resident. */
    private fun handleAddResident(sender: CommandSender, args: Array<out String>) {
        val (town, target) = resolveTownAndTarget(sender, args) ?: return
        if (target.townUuid == town.id) {
            plugin.messageManager.sendMessage(
                sender, "error.player-already-in-town",
                "player", target.name ?: args[2],
                "town", town.name ?: args[1]
            )
            return
        }
        if (rejectIfInOtherTown(sender, target, town)) return
        try {
            placeInTown(target, town, TownRank.RESIDENT)
            notifyTarget(target, "admin.notify-added", town.name ?: args[1])
            plugin.messageManager.sendMessage(
                sender, "admin.resident-added",
                "town", town.name ?: args[1],
                "player", target.name ?: args[2]
            )
        } catch (e: TowniaException) {
            sendFailure(sender, e)
        }
    }

    /**
     * `/ta setrank <town> <player> <resident|assistant|sub_mayor|mayor>` and its shortcuts
     * `/ta setmayor|setsubmayor|setassistant <town> <player>`. A townless player is added to the town;
     * a member of another town is refused (see moveresident). Making someone mayor demotes the old mayor
     * to resident; the current mayor cannot be given a lower rank, so a town never loses its mayor.
     */
    private fun handleSetRank(sender: CommandSender, args: Array<out String>, fixedRank: TownRank?) {
        val (town, target) = resolveTownAndTarget(sender, args) ?: return
        val rank = fixedRank ?: run {
            val name = args.getOrNull(3)
            val parsed = name?.let { TownRank.fromString(it) }
            // fromString falls back to RESIDENT, so only accept an exact rank name.
            if (parsed == null || parsed.name != name.trim().uppercase(Locale.ROOT).replace('-', '_')) {
                plugin.messageManager.sendMessage(sender, "error.invalid-args")
                return
            }
            parsed
        }
        if (rejectIfInOtherTown(sender, target, town)) return
        if (rank != TownRank.MAYOR && target.townUuid == town.id && rejectIfMayor(sender, target)) return
        try {
            val joined = target.townUuid != town.id
            placeInTown(target, town, rank)
            if (rank == TownRank.MAYOR && town.mayorUuid != target.uuid) {
                townManager.setMayor(town.id, target.uuid)
            }
            if (joined) notifyTarget(target, "admin.notify-added", town.name ?: args[1])
            plugin.messageManager.sendMessage(
                sender, "admin.rank-set",
                "player", target.name ?: args[2],
                "town", town.name ?: args[1],
                "rank", rank.name.lowercase(Locale.ROOT)
            )
        } catch (e: TowniaException) {
            sendFailure(sender, e)
        }
    }

    /** `/ta moveresident <town> <player>`: moves [player] into [town] as a resident, even out of another town. */
    private fun handleMoveResident(sender: CommandSender, args: Array<out String>) {
        val (town, target) = resolveTownAndTarget(sender, args) ?: return
        if (target.townUuid == town.id) {
            plugin.messageManager.sendMessage(
                sender, "error.player-already-in-town",
                "player", target.name ?: args[2],
                "town", town.name ?: args[1]
            )
            return
        }
        if (rejectIfMayor(sender, target)) return
        try {
            val from = target.townUuid?.let { townName(it) }
            // Leaving the old town first releases the plots owned there, as a normal leave does.
            if (from != null) residentManager.clearTown(target.uuid)
            placeInTown(target, town, TownRank.RESIDENT)
            notifyTarget(target, "admin.notify-added", town.name ?: args[1])
            plugin.messageManager.sendMessage(
                sender, "admin.resident-moved",
                "player", target.name ?: args[2],
                "from", from ?: plugin.messageManager.getPlainMessage(sender, "common.none"),
                "town", town.name ?: args[1]
            )
        } catch (e: TowniaException) {
            sendFailure(sender, e)
        }
    }

    /** `/ta kickresident <player>`: removes [player] from their town. */
    private fun handleKickResident(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val target = resolveTargetResident(args[1])
        if (target == null || target.uuid == null) {
            plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", args[1])
            return
        }
        dropMissingTown(target)
        if (target.townUuid == null) {
            plugin.messageManager.sendMessage(sender, "error.player-not-in-town", "player", target.name ?: args[1])
            return
        }
        if (rejectIfMayor(sender, target)) return
        val from = townName(target.townUuid)
        residentManager.clearTown(target.uuid)
        notifyTarget(target, "admin.notify-removed", from)
        plugin.messageManager.sendMessage(sender, "admin.resident-kicked", "player", target.name ?: args[1], "town", from)
    }

    /** `/ta mergeresident <from> <into>`: from/into are a name or a UUID. */
    private fun handleMergeResident(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            plugin.messageManager.sendMessage(sender, "admin.merge-usage")
            return
        }
        fun find(key: String) = runCatching { UUID.fromString(key) }.getOrNull()
            ?.let { residentManager.getResident(it).orElse(null) }
            ?: residentManager.getResidentByName(key).orElse(null)
        val from = find(args[1])
        val into = find(args[2])
        if (from == null || into == null) {
            plugin.messageManager.sendMessage(sender, "admin.merge-not-found", "player", if (from == null) args[1] else args[2])
            return
        }
        if (!residentManager.mergeResidents(from, into)) {
            plugin.messageManager.sendMessage(sender, "admin.merge-not-found", "player", args[1])
            return
        }
        plugin.messageManager.sendMessage(sender, "admin.merge-done", "from", args[1], "into", into.name ?: args[2])
    }

    /** `/ta dedupe` previews, `/ta dedupe confirm` merges offline-UUID duplicate residents. */
    private fun handleDedupe(sender: CommandSender, args: Array<out String>) {
        if (args.getOrNull(1)?.equals("confirm", ignoreCase = true) != true) {
            val count = residentManager.findOfflineDuplicates().size
            plugin.messageManager.sendMessage(sender, "admin.dedupe-preview", "count", count.toString())
            return
        }
        val started = residentManager.mergeOfflineDuplicates { merged, failed ->
            plugin.messageManager.sendMessage(sender, "admin.dedupe-done", "merged", merged.toString(), "failed", failed.toString())
        }
        plugin.messageManager.sendMessage(sender, "admin.dedupe-started", "count", started.toString())
    }

    private fun handleMigrate(sender: CommandSender, args: Array<out String>) {
        if (args.size > 1 && args[1].lowercase(Locale.getDefault()) == "config") {
            net.azisaba.townia.migration.TownyConfigMigrator.migrate(plugin, sender)
        } else if (args.size > 1 && args[1].lowercase(Locale.getDefault()) in setOf("diagnose", "debug")) {
            diagnoseTownyResident(sender, args.getOrNull(2))
        } else if (args.size > 1 && args[1].lowercase(Locale.getDefault()) == "repair") {
            net.azisaba.townia.migration.TownyFlatfileMigrator.repair(plugin, sender, args.getOrNull(2).equals("confirm", ignoreCase = true))
        } else if (args.size > 1 && args[1].lowercase(Locale.getDefault()) == "audit") {
            net.azisaba.townia.migration.TownyAudit.run(plugin, sender)
        } else if (args.size > 1 && args[1].lowercase(Locale.getDefault()) == "only") {
            migrateOnly(sender, args.drop(2))
        } else if (args.size > 1 && args[1].lowercase(Locale.getDefault()) == "data") {
            migrateTownyData(sender, parseMigrationParts(args.drop(2)))
        } else {
            if (args.size == 1) {
                // Default to data migration if no sub-args provided
                migrateTownyData(sender, emptySet())
            } else {
                val candidateParts = parseMigrationParts(args.drop(1))
                val validParts = setOf("all", "towns", "nations", "residents", "townblocks", "plots", "jails", "outlaws", "relations", "flatfile", "api", "skip-profiles", "missing-only", "prune", "include-deleted")
                if (candidateParts.isNotEmpty() && candidateParts.any { it in validParts }) {
                    migrateTownyData(sender, candidateParts)
                } else {
                    plugin.messageManager.sendMessage(sender, "admin.migrate-usage")
                }
            }
        }
    }

    /**
     * `/ta migrate only town <name> [resident <name>] [settings-only] [missing-only]`:
     * brings one town and/or one resident over from Towny's flat files without touching
     * anything else Townia already has.
     */
    private fun migrateOnly(sender: CommandSender, tokens: List<String>) {
        var town: String? = null
        var resident: String? = null
        var settingsOnly = false
        var missingOnly = false
        var index = 0
        while (index < tokens.size) {
            when (tokens[index].lowercase(Locale.getDefault())) {
                "town", "t" -> town = tokens.getOrNull(++index)
                "resident", "res", "user", "player" -> resident = tokens.getOrNull(++index)
                "settings-only", "settings" -> settingsOnly = true
                "missing-only" -> missingOnly = true
                else -> {
                    plugin.messageManager.sendMessage(sender, "admin.migrate-only-usage")
                    return
                }
            }
            index++
        }
        if (town.isNullOrBlank() && resident.isNullOrBlank()) {
            plugin.messageManager.sendMessage(sender, "admin.migrate-only-usage")
            return
        }
        net.azisaba.townia.migration.TownyFlatfileMigrator.migrateOnly(plugin, sender, town, resident, settingsOnly, missingOnly)
    }

    /**
     * Prints the complete UUID-to-town resolution path for one online player.
     * This is intentionally separate from migration so a live server can prove
     * whether the mismatch originates in Towny, Townia's cache, or name lookup.
     */
    private fun diagnoseTownyResident(sender: CommandSender, playerName: String?) {
        if (!plugin.server.pluginManager.isPluginEnabled("Towny")) {
            plugin.messageManager.sendMessage(sender, "admin.diagnose-towny-disabled")
            return
        }

        val target = when {
            !playerName.isNullOrBlank() -> plugin.server.getPlayerExact(playerName)
            sender is Player -> sender
            else -> null
        }
        if (target == null) {
            plugin.messageManager.sendMessage(sender, "admin.diagnose-target-offline")
            return
        }

        val minecraftUuid = target.uniqueId
        plugin.messageManager.sendMessage(sender, "admin.diagnose-header", "{player}", target.name)
        plugin.messageManager.sendMessage(sender, "admin.diagnose-uuid", "{uuid}", minecraftUuid.toString())

        val townyResident = runCatching { TownyUniverse.getInstance().getResident(minecraftUuid) }.getOrNull()
        if (townyResident == null) {
            plugin.messageManager.sendMessage(sender, "admin.diagnose-towny-api-no-resident")
        } else {
            val townyTown = runCatching { if (townyResident.hasTown()) townyResident.town else null }.getOrNull()
            val townyNation = runCatching { if (townyTown?.hasNation() == true) townyTown.nation else null }.getOrNull()
            plugin.messageManager.sendMessage(sender, "admin.diagnose-towny-api-resident", "{name}", townyResident.name, "{uuid}", townyResident.uuid.toString())
            plugin.messageManager.sendMessage(sender, "admin.diagnose-towny-api-town", "{town}", townyTown?.name ?: "<none>", "{uuid}", townyTown?.uuid?.toString() ?: "<none>")
            plugin.messageManager.sendMessage(sender, "admin.diagnose-towny-api-nation", "{nation}", townyNation?.name ?: "<none>", "{uuid}", townyNation?.uuid?.toString() ?: "<none>")
        }

        val towniaResident = residentManager.getResident(minecraftUuid).orElse(null)
        val towniaTown = towniaResident?.townUuid?.let { townManager.getTown(it).orElse(null) }
        val towniaNation = towniaTown?.nationUuid?.let { nationManager.getNation(it).orElse(null) }
        plugin.messageManager.sendMessage(sender, "admin.diagnose-townia-lookup", 
            "{name}", towniaResident?.name ?: "<none>", 
            "{town}", towniaTown?.name ?: "<none>", 
            "{uuid}", towniaResident?.townUuid?.toString() ?: "<none>",
            "{nation}", towniaNation?.name ?: "<none>",
            "{nation_uuid}", towniaNation?.id?.toString() ?: "<none>"
        )

        val nameMatch = residentManager.getResidentByName(target.name).orElse(null)
        if (nameMatch != null && nameMatch.uuid != minecraftUuid) {
            val nameMatchTown = nameMatch.townUuid?.let { townManager.getTown(it).orElse(null) }
            val nameMatchNation = nameMatchTown?.nationUuid?.let { nationManager.getNation(it).orElse(null) }
            plugin.messageManager.sendMessage(sender, "admin.diagnose-townia-name-lookup", 
                "{uuid}", nameMatch.uuid.toString(), 
                "{town}", nameMatchTown?.name ?: "<none>", 
                "{town_uuid}", nameMatch.townUuid?.toString() ?: "<none>",
                "{nation}", nameMatchNation?.name ?: "<none>",
                "{nation_uuid}", nameMatchNation?.id?.toString() ?: "<none>"
            )
        }
        plugin.messageManager.sendMessage(sender, "admin.diagnose-footer")
    }

    private fun migrateTownyData(sender: CommandSender, parts: Set<String>) {
        val forceApi = parts.contains("api")
        val forceFlatfile = parts.contains("flatfile")
        val filteredParts = parts - setOf("api", "flatfile")

        val flatfileDir = net.azisaba.townia.migration.TownyFlatfileMigrator.findTownyDataDir(plugin)
        val townyEnabled = plugin.server.pluginManager.isPluginEnabled("Towny")
        // A server that moved Towny to MySQL keeps its old flatfile folder. Reading
        // that folder migrates stale data (e.g. nations without their current spawn).
        val townyLoadType = net.azisaba.townia.migration.TownyFlatfileMigrator.findTownyDatabaseLoadType(plugin)
        val flatfileIsCurrent = townyLoadType == null || townyLoadType.equals("flatfile", ignoreCase = true)
        if (!forceApi && (forceFlatfile || (flatfileDir != null && flatfileIsCurrent))) {
            net.azisaba.townia.migration.TownyFlatfileMigrator.migrate(plugin, sender, filteredParts)
        } else if (townyEnabled) {
            if (!flatfileIsCurrent && !forceApi) {
                plugin.logger.info("Towny loads its data from '$townyLoadType'; migrating through the Towny API instead of the flatfile folder.")
            }
            net.azisaba.townia.migration.TownyMigrator.migrate(plugin, sender, filteredParts)
        } else if (!flatfileIsCurrent) {
            plugin.logger.warning(
                "Towny loads its data from '$townyLoadType', so the flatfile folder is out of date. " +
                    "Enable Towny to migrate through its API, or add 'flatfile' to migrate the folder anyway."
            )
            plugin.messageManager.sendMessage(sender, "admin.migration_failed")
        } else {
            net.azisaba.townia.migration.TownyFlatfileMigrator.migrate(plugin, sender, filteredParts)
        }
    }

    private fun parseMigrationParts(raw: List<String>): Set<String> {
        return raw.flatMap { it.split(',', ';') }
            .map { it.trim().lowercase(Locale.getDefault()) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun sendHelp(sender: CommandSender) {
        CommandHelp.sendList(plugin.messageManager, sender, "towniaadmin", 1)
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
        if (!net.azisaba.townia.permission.Perm.isAdmin(sender)) return mutableListOf<String>()

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
                    "migrate",
                    "newday",
                    "checkperm",
                    "town",
                    "nation",
                    "plot",
                    "set",
                    "dedupe",
                    "mergeresident",
                    "setmayor",
                    "addresident",
                    "setsubmayor",
                    "setassistant",
                    "setrank",
                    "moveresident",
                    "kickresident"
                ),
                completions
            )
        } else if (args.size == 2 && args[0].lowercase(Locale.getDefault()) == "migrate") {
            StringUtil.copyPartialMatches(
                args[1],
                mutableListOf("data", "only", "audit", "repair", "config", "diagnose", "all", "towns", "nations", "residents", "townblocks", "jails", "outlaws", "relations", "flatfile", "api"),
                completions
            )
        } else if (args.size == 2 && args[0].lowercase(Locale.getDefault()) == "dedupe") {
            StringUtil.copyPartialMatches(args[1], mutableListOf("confirm"), completions)
        } else if (args.size >= 3 && args[0].lowercase(Locale.getDefault()) == "migrate" && args[1].lowercase(Locale.getDefault()) == "only") {
            when (args[args.size - 2].lowercase(Locale.getDefault())) {
                "town", "t" -> StringUtil.copyPartialMatches(args.last(), net.azisaba.townia.migration.TownyFlatfileMigrator.townyTownNames(plugin), completions)
                "resident", "res", "user", "player" -> StringUtil.copyPartialMatches(args.last(), plugin.server.onlinePlayers.map { it.name }, completions)
                else -> StringUtil.copyPartialMatches(args.last(), mutableListOf("town", "resident", "settings-only", "missing-only"), completions)
            }
        } else if (args.size == 3 && args[0].lowercase(Locale.getDefault()) == "migrate" && args[1].lowercase(Locale.getDefault()) in setOf("diagnose", "debug")) {
            StringUtil.copyPartialMatches(args[2], plugin.server.onlinePlayers.map { it.name }, completions)
        } else if (args.size >= 3 && args[0].lowercase(Locale.getDefault()) == "migrate" && (args[1].lowercase(Locale.getDefault()) == "data" || args[1].lowercase(Locale.getDefault()) in setOf("all", "towns", "nations", "residents", "townblocks", "plots", "jails", "outlaws", "relations", "flatfile", "api"))) {
            StringUtil.copyPartialMatches(
                args.last(),
                mutableListOf("all", "towns", "nations", "residents", "townblocks", "plots", "jails", "outlaws", "relations", "flatfile", "api", "skip-profiles", "missing-only", "prune", "include-deleted"),
                completions
            )
        } else if (args[0].equals("plot", ignoreCase = true) && args.size in 2..4) {
            val options = when (args.size) {
                2 -> listOf("claim", "owner")
                3 -> residentManager.allResidents.mapNotNull { it.name } + if (args[1].equals("owner", ignoreCase = true)) listOf("none") else emptyList()
                else -> plugin.server.worlds.map { it.name }
            }
            StringUtil.copyPartialMatches(args.last(), options, completions)
        } else if (args.size == 2) {
            when (args[0].lowercase(Locale.getDefault())) {
                "forceclaim", "deletetown", "givebonus", "setmayor", "addresident",
                "setsubmayor", "setassistant", "setrank", "moveresident" -> {
                    val townNames = townManager.allTowns.stream()
                        .map { it.name }.toList()
                    StringUtil.copyPartialMatches(args[1], townNames, completions)
                }

                "kickresident" -> {
                    val names = residentManager.allResidents.mapNotNull { it.name }
                    StringUtil.copyPartialMatches(args[1], names, completions)
                }

                "deletenation" -> {
                    val nationNames = nationManager.allNations.stream()
                        .map { it.name }.toList()
                    StringUtil.copyPartialMatches(args[1], nationNames, completions)
                }
            }
        } else if (args.size == 3 && args[0].lowercase(Locale.getDefault()) in setOf("setmayor", "addresident", "setsubmayor", "setassistant", "setrank", "moveresident")) {
            val names = residentManager.allResidents.mapNotNull { it.name }
            StringUtil.copyPartialMatches(args[2], names, completions)
        } else if (args.size == 4 && args[0].equals("setrank", ignoreCase = true)) {
            StringUtil.copyPartialMatches(args[3], listOf("resident", "assistant", "sub_mayor", "mayor"), completions)
        }
        return completions
    }

    companion object {
        private val bypassSet: MutableSet<UUID> = Collections.synchronizedSet(HashSet())

        const val BYPASS_PERMISSION = "townia.admin.bypass"

        fun isBypassing(uuid: UUID): Boolean {
            return bypassSet.contains(uuid)
        }

        /** Staff holding [BYPASS_PERMISSION] always bypass; the `/ta bypass` toggle still works for anyone else. */
        fun isBypassing(player: Player): Boolean {
            return bypassSet.contains(player.uniqueId) || player.hasPermission(BYPASS_PERMISSION)
        }
    }
}
