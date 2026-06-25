package net.azisaba.townia.command

import net.azisaba.townia.data.Invite
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Town
import net.azisaba.townia.Townia
import net.azisaba.townia.TowniaException
import net.azisaba.townia.data.PermissionMatrix
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaOutpost
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.database.DatabaseManager
import net.azisaba.townia.manager.NationManager
import net.azisaba.townia.manager.PlotManager
import net.azisaba.townia.manager.ResidentManager
import net.azisaba.townia.manager.TownManager
import net.kyori.adventure.text.minimessage.MiniMessage
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.util.StringUtil

import java.sql.SQLException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.function.Consumer
import java.util.logging.Level
import kotlin.Array
import kotlin.Boolean
import kotlin.Char
import kotlin.CharSequence
import kotlin.Double
import kotlin.Exception
import kotlin.Int
import kotlin.NumberFormatException
import kotlin.also
import kotlin.arrayOf
import kotlin.collections.ArrayList
import kotlin.collections.MutableIterator
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.text.StringBuilder
import kotlin.text.equals
import kotlin.text.format
import kotlin.text.indexOf
import kotlin.text.lowercase
import kotlin.text.toDouble
import kotlin.toString

class TownCommand
    (private val plugin: Townia) : CommandExecutor, TabCompleter {
    private val townManager: TownManager = plugin.townManager
    private val residentManager: ResidentManager = plugin.residentManager
    private val plotManager: PlotManager = plugin.plotManager
    private val nationManager: NationManager = plugin.nationManager
    private val databaseManager: DatabaseManager = plugin.databaseManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            this.handleInfo(sender, arrayOf("info"))
            return true
        }
        when (args[0].lowercase(Locale.getDefault())) {
            "new" -> {
                this.handleNew(sender, args)
            }

            "claim" -> {
                this.handleClaim(sender, args)
            }

            "outpost" -> {
                this.handleOutpost(sender, args)
            }

            "unclaim" -> {
                this.handleUnclaim(sender, args)
            }

            "invite", "add" -> {
                this.handleInvite(sender, args)
            }

            "kick" -> {
                this.handleKick(sender, args)
            }

            "leave" -> {
                this.handleLeave(sender)
            }

            "join" -> {
                this.handleJoin(sender, args)
            }

            "reslist" -> {
                this.handleResList(sender, args)
            }

            "spawn" -> {
                this.handleSpawn(sender, args)
            }

            "set" -> {
                this.handleSet(sender, args)
            }

            "toggle" -> {
                this.handleToggle(sender, args)
            }

            "deposit" -> {
                this.handleDeposit(sender, args)
            }

            "withdraw" -> {
                this.handleWithdraw(sender, args)
            }

            "rank" -> {
                this.handleRank(sender, args)
            }

            "info" -> {
                this.handleInfo(sender, args)
            }

            "here" -> {
                this.handleHere(sender)
            }

            "list" -> {
                this.handleList(sender)
            }

            "delete" -> {
                this.handleDelete(sender, args)
            }

            "map" -> {
                this.handleMap(sender)
            }

            "?", "help" -> {
                this.handleHelp(sender)
            }

            else -> {
                this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
                this.handleHelp(sender)
            }
        }
        return true
    }

    private fun handleNew(sender: CommandSender, args: Array<out String>) {
        val player: Player = this.requirePlayer(sender) ?: return
        if (args.size < 2) {
            this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val resOpt: Optional<TowniaPlayer> = this.residentManager.getResident(player.uniqueId)
        if (resOpt.isPresent && resOpt.get().isInTown) {
            this.plugin.messageManager.sendMessage(sender, "error.already-in-town")
            return
        }
        if (!this.plugin.towniaConfig.isWorldAllowed(player.world.name)) {
            this.plugin.messageManager
                .sendMessage(sender, "error.wrong-world", "world", player.world.name)
            return
        }
        val cost: Double = this.plugin.towniaConfig.townCreationCost
        if (cost > 0.0) {
            if (!this.plugin.hasEconomy()) {
                this.plugin.messageManager.sendMessage(sender, "error.no-vault")
                return
            }
            val eco: Economy = this.plugin.economy!!
            if (!eco.has(player as OfflinePlayer, cost)) {
                this.plugin.messageManager
                    .sendMessage(sender, "error.insufficient-funds", "amount", (this.formatMoney(cost) ?: "0"))
                return
            }
            eco.withdrawPlayer(player as OfflinePlayer, cost)
        }
        val name = args[1]
        try {
            val chunk = player.location.chunk
            if (this.plugin.plotManager.getPlot(chunk.world.name, chunk.x, chunk.z)
                    .isPresent
            ) {
                throw TowniaException("town.already-claimed")
            }
            val town: Town = this.townManager.createTown(name, player.uniqueId)
            this.plugin.plotManager.claimChunk(town.id!!, chunk)
            town.setHomeBlock(chunk.world.name, chunk.x, chunk.z)
            val loc = player.location
            town.setSpawn(loc.getWorld().name, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
            this.townManager.saveTown(town)
            this.plugin.messageManager.sendMessage(sender, "town.created", "town", name)
        } catch (e: TowniaException) {
            if (cost > 0.0 && this.plugin.hasEconomy()) {
                this.plugin.economy!!.depositPlayer(player as OfflinePlayer, cost)
            }
            this.plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun handleClaim(sender: CommandSender, args: Array<out String>) {
        val player: Player = this.requirePlayer(sender) ?: return
        if (!this.plugin.towniaConfig.isWorldAllowed(player.world.name)) {
            this.plugin.messageManager
                .sendMessage(sender, "error.wrong-world", "world", player.world.name)
            return
        }
        val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
        if (!res.isAssistantOrHigher) {
            this.plugin.messageManager.sendMessage(sender, "town.not-assistant")
            return
        }
        val isOutpost = args.size > 1 && args[1].toString().equals("outpost", ignoreCase = true)
        try {
            if (isOutpost) {
                this.plotManager.claimChunk(res.townUuid!!, player.location.chunk)
                val plotOpt: Optional<Plot> = this.plotManager.getPlot(player.location.chunk)
                plotOpt.ifPresent(Consumer { p: Plot? ->
                    try {
                        p!!.isOutpost = true
                        this.plugin.databaseManager.savePlot(p)
                    } catch (e: SQLException) {
                        this.plugin.logger.log(Level.SEVERE, "Failed to save outpost", e)
                    }
                })
                val townOpt: Optional<Town> = this.plugin.townManager.getTown(res.townUuid)
                townOpt.ifPresent(Consumer { t: Town? ->
                    t!!.outposts.add(
                        TowniaOutpost(
                            0,
                            player.world.name,
                            player.location.x,
                            player.location.y,
                            player.location.z,
                            player.location.yaw,
                            player.location.pitch,
                            false
                        )
                    )
                    this.plugin.townManager.saveTown(t)
                    this.plugin.messageManager.sendMessage(
                        sender,
                        "town.outpost-claimed",
                        "x",
                        player.location.chunk.x.toString(),
                        "z",
                        player.location.chunk.z.toString(),
                        "town",
                        (t.name ?: "")
                    )
                })
            } else {
                this.plotManager.claimChunk(res.townUuid!!, player.location.chunk)
                val townOpt: Optional<Town> = this.plugin.townManager.getTown(res.townUuid)
                this.plugin.messageManager.sendMessage(
                    sender,
                    "town.claimed",
                    "x",
                    player.location.chunk.x.toString(),
                    "z",
                    player.location.chunk.z.toString(),
                    "town",
                    townOpt.map { it.name ?: "Unknown" }.orElse("Unknown")
                )
            }
        } catch (e: TowniaException) {
            this.plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun handleUnclaim(sender: CommandSender, args: Array<out String>) {
        val player: Player = this.requirePlayer(sender) ?: return
        val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
        if (!res.isAssistantOrHigher) {
            this.plugin.messageManager.sendMessage(sender, "town.not-assistant")
            return
        }
        try {
            if (args.size > 1 && args[1].toString().equals("all", ignoreCase = true)) {
                val plots: MutableList<Plot> = this.plotManager.getPlotsByTown(res.townUuid!!) as MutableList<Plot>
                var count = 0
                for (plot in plots) {
                    val world: World? = this.plugin.server.getWorld((plot.worldName ?: ""))
                    if (world == null) continue
                    this.plotManager.unclaimChunk(
                        res.townUuid!!,
                        world.getChunkAt(plot.chunkX, plot.chunkZ)
                    )
                    ++count
                }
                this.plugin.messageManager.sendMessage(sender, "town.unclaimed-all", "count", count.toString())
                return
            }
            val chunk = player.location.chunk
            this.plotManager.unclaimChunk(res.townUuid!!, chunk)
            this.plugin.messageManager
                .sendMessage(sender, "town.unclaimed", "x", chunk.getX().toString(), "z", chunk.z.toString())
        } catch (e: TowniaException) {
            this.plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun handleOutpost(sender: CommandSender, args: Array<out String>) {
        val player: Player = this.requirePlayer(sender) ?: return
        val res: TowniaPlayer? = this.plugin.residentManager.getResident(player.uniqueId).orElse(null)
        
        if (args.size < 2) {
            // Implicit tp to outpost 1
            if (res?.townUuid == null) {
                this.plugin.messageManager.sendMessage(sender, "error.not-in-town")
                return
            }
            teleportToOutpost(sender, player, res, res.townUuid, 1)
            return
        }

        val subCmd = args[1].lowercase()

        when (subCmd) {
            "list" -> {
                val targetTownName = if (args.size >= 3) args[2] else null
                val targetTown = if (targetTownName != null) {
                    this.plugin.townManager.getTownByName(targetTownName).orElse(null)
                } else {
                    if (res?.townUuid == null) {
                        this.plugin.messageManager.sendMessage(sender, "error.not-in-town")
                        return
                    }
                    this.plugin.townManager.getTown(res.townUuid).orElse(null)
                }

                if (targetTown == null) {
                    if (targetTownName != null) {
                        this.plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", targetTownName)
                    }
                    return
                }

                this.plugin.messageManager.sendMessage(sender, "town.outpost-list-header", "town", targetTown.name ?: "")
                var hasOutposts = false
                targetTown.outposts.forEachIndexed { i, outpost ->
                    if (outpost != null) {
                        hasOutposts = true
                        val status = if (outpost.isPublic) {
                            this.plugin.messageManager.getRawMessage(sender, "town.outpost-public")
                        } else {
                            this.plugin.messageManager.getRawMessage(sender, "town.outpost-private")
                        }
                        this.plugin.messageManager.sendMessage(
                            sender, "town.outpost-list-entry",
                            "index", (i + 1).toString(),
                            "x", outpost.x.toInt().toString(),
                            "y", outpost.y.toInt().toString(),
                            "z", outpost.z.toInt().toString(),
                            "status", status
                        )
                    }
                }
                if (!hasOutposts) {
                    this.plugin.messageManager.sendMessage(sender, "town.outpost-list-empty")
                }
            }
            "tp" -> {
                if (args.size >= 4) {
                    val townName = args[2]
                    val indexStr = args[3]
                    val targetTown = this.plugin.townManager.getTownByName(townName).orElse(null)
                    if (targetTown == null) {
                        this.plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", townName)
                        return
                    }
                    try {
                        teleportToOutpost(sender, player, res, targetTown.id, indexStr.toInt())
                    } catch (_: NumberFormatException) {
                        this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
                    }
                } else if (args.size >= 3) {
                    if (res?.townUuid == null) {
                        this.plugin.messageManager.sendMessage(sender, "error.not-in-town")
                        return
                    }
                    try {
                        teleportToOutpost(sender, player, res, res.townUuid, args[2].toInt())
                    } catch (e: NumberFormatException) {
                        this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
                    }
                } else {
                    this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
                }
            }
            "togglepublic" -> {
                if (res?.townUuid == null) {
                    this.plugin.messageManager.sendMessage(sender, "error.not-in-town")
                    return
                }
                if (!res.isAssistantOrHigher) {
                    this.plugin.messageManager.sendMessage(sender, "town.not-assistant")
                    return
                }
                if (args.size < 3) {
                    this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
                    return
                }
                val indexStr = args[2]
                try {
                    val outpostIndex = indexStr.toInt()
                    val targetTown = this.plugin.townManager.getTown(res.townUuid).orElse(null) ?: return
                    if (outpostIndex < 1 || outpostIndex > targetTown.outposts.size) {
                        this.plugin.messageManager.sendMessage(sender, "error.outpost-not-found", "index", outpostIndex.toString())
                        return
                    }
                    val outpost = targetTown.outposts[outpostIndex - 1]!!
                    val newOutpost = outpost.copy(isPublic = !outpost.isPublic)
                    targetTown.outposts[outpostIndex - 1] = newOutpost
                    this.plugin.databaseManager.saveTownOutpost(targetTown.id!!, newOutpost)
                    
                    if (newOutpost.isPublic) {
                        this.plugin.messageManager.sendMessage(sender, "town.outpost-toggled-public", "index", outpostIndex.toString())
                    } else {
                        this.plugin.messageManager.sendMessage(sender, "town.outpost-toggled-private", "index", outpostIndex.toString())
                    }
                } catch (e: NumberFormatException) {
                    this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
                }
            }
            else -> {
                // If subCmd is just a number, treat it as /town outpost <number>
                try {
                    val index = subCmd.toInt()
                    if (res?.townUuid == null) {
                        this.plugin.messageManager.sendMessage(sender, "error.not-in-town")
                        return
                    }
                    teleportToOutpost(sender, player, res, res.townUuid, index)
                } catch (e: NumberFormatException) {
                    this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
                }
            }
        }
    }

    private fun teleportToOutpost(sender: CommandSender, player: Player, res: TowniaPlayer?, townId: UUID?, outpostIndex: Int) {
        val targetTown = if (townId != null) this.plugin.townManager.getTown(townId).orElse(null) else null
        if (targetTown == null) return

        if (outpostIndex < 1 || outpostIndex > targetTown.outposts.size) {
            this.plugin.messageManager.sendMessage(sender, "error.outpost-not-found", "index", outpostIndex.toString())
            return
        }
        val outpost = targetTown.outposts[outpostIndex - 1]!!
        if (targetTown.id != res?.townUuid && !outpost.isPublic) {
            this.plugin.messageManager.sendMessage(sender, "error.outpost-not-public")
            return
        }
        val world = this.plugin.server.getWorld(outpost.world ?: "") ?: return
        val loc = Location(world, outpost.x, outpost.y, outpost.z, outpost.yaw, outpost.pitch)
        player.teleport(loc)
        this.plugin.messageManager.sendMessage(
            sender,
            "town.teleport-outpost",
            "index",
            outpostIndex.toString(),
            "town",
            targetTown.name ?: ""
        )
    }

    private fun handleJoin(sender: CommandSender, args: Array<out String>) {
        val player: Player = this.requirePlayer(sender) ?: return
        val res: TowniaPlayer = this.plugin.residentManager.getResident(player.getUniqueId()).orElse(null)
        if (res.isInTown) {
            this.plugin.messageManager.sendMessage(sender, "error.already-in-town")
            return
        }
        if (args.size < 2) {
            this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val townName = args[1]
        val townOpt: Optional<Town> = this.plugin.townManager.getTownByName(townName)
        if (townOpt.isEmpty) {
            this.plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", townName)
            return
        }
        val town = townOpt.get()
        if (!town.isOpen) {
            this.plugin.messageManager.sendMessage(sender, "error.town-not-open")
            return
        }
        res.townUuid = town.id
        this.plugin.residentManager.saveResident(res)
        this.plugin.messageManager.sendMessage(sender, "town.joined", "town", (town.name ?: ""))
    }

    private fun handleResList(sender: CommandSender, args: Array<out String>) {
        val player: Player = this.requirePlayer(sender) ?: return
        var targetTown: Town? = null
        if (args.size >= 2) {
            targetTown = this.plugin.townManager.getTownByName(args[1]).orElse(null)
            if (targetTown == null) {
                this.plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", args[1])
                return
            }
        } else {
            val res: TowniaPlayer? = this.requireInTown(sender, player)
            if (res != null) {
                targetTown = this.plugin.townManager.getTown(res.townUuid).orElse(null)
            }
        }
        if (targetTown == null) {
            return
        }
        val residents: MutableList<TowniaPlayer> =
            this.plugin.residentManager.getResidentsByTown(targetTown.id!!)
        val onlineNames = ArrayList<String?>()
        val offlineNames = ArrayList<String?>()
        for (r in residents) {
            val p: Player? = this.plugin.server.getPlayer(r.uuid!!)
            if (p != null && p.isOnline) {
                onlineNames.add((("<green>" + r.name + "</green>")))
                continue
            }
            offlineNames.add((("<gray>" + r.name + "</gray>")))
        }
        this.plugin.messageManager
            .sendMessageWithoutPrefix(sender, "town.reslist", "town", (targetTown.name ?: ""), "count", residents.size.toString())
        if (!onlineNames.isEmpty()) {
            player.sendMessage(
                MiniMessage.miniMessage().deserialize(("Online: " + java.lang.String.join(", " as CharSequence, onlineNames)))
            )
        }
        if (!offlineNames.isEmpty()) {
            player.sendMessage(
                MiniMessage.miniMessage().deserialize(("Offline: " + java.lang.String.join(", " as CharSequence, offlineNames)))
            )
        }
    }

    private fun handleInvite(sender: CommandSender, args: Array<out String>) {
        val player: Player = this.requirePlayer(sender) ?: return
        if (args.size < 2) {
            this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
        if (!res.isAssistantOrHigher) {
            this.plugin.messageManager.sendMessage(sender, "town.not-assistant")
            return
        }
        val targetName = args[1]
        val targetOpt: Optional<TowniaPlayer> = this.residentManager.getResidentByName(targetName)
        if (targetOpt.isEmpty) {
            this.plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", targetName)
            return
        }
        val target: TowniaPlayer = targetOpt.get()
        if (target.isInTown) {
            this.plugin.messageManager.sendMessage(sender, "error.already-in-town")
            return
        }
        val townUuid: UUID? = res.townUuid
        try {
            val existingOpt: Optional<Invite> = this.databaseManager.getInvite(target.uuid!!, townUuid!!)
            if (existingOpt.isPresent) {
                this.plugin.messageManager
                    .sendMessage(sender, "town.invite-sent", "player", "Unknown", "town", "Unknown")
                return
            }
            val invite = Invite(0, target.uuid, townUuid, player.uniqueId, System.currentTimeMillis())
            this.databaseManager.addInvite(invite)
            val townOpt: Optional<Town> = this.townManager.getTown(townUuid)
            val townName = townOpt.map { it.name ?: "Unknown" }.orElse("Unknown")
            this.plugin.messageManager
                .sendMessage(sender, "town.invite-sent", "player", (target.name ?: "Unknown"), "town", townName)
            val targetPlayer = Bukkit.getPlayer((target.uuid as UUID?)!!)
            if (targetPlayer != null) {
                this.plugin.messageManager.sendMessage(
                    targetPlayer as CommandSender,
                    "town.invite-received",
                    "town",
                    townName,
                    "inviter",
                    player.name
                )
            }
        } catch (e: SQLException) {
            this.plugin.logger.log(Level.SEVERE, "DB error handling invite", e)
            this.plugin.messageManager.sendMessage(sender, "error.database")
        }
    }

    private fun handleKick(sender: CommandSender, args: Array<out String>) {
        val player: Player = this.requirePlayer(sender) ?: return
        if (args.size < 2) {
            this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
        if (!res.isMayorOrHigher) {
            this.plugin.messageManager.sendMessage(sender, "town.not-mayor")
            return
        }
        val targetName = args[1]
        val targetOpt: Optional<TowniaPlayer> = this.residentManager.getResidentByName(targetName)
        if (targetOpt.isEmpty) {
            this.plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", targetName)
            return
        }
        val target: TowniaPlayer = targetOpt.get()
        if (!res.townUuid.toString().equals(target.townUuid)) {
            this.plugin.messageManager.sendMessage(sender, "error.not-same-town")
            return
        }
        if (target.isMayor) {
            this.plugin.messageManager
                .sendMessage(sender, "error.cannot-kick-mayor")
            return
        }
        val townOpt: Optional<Town> = this.townManager.getTown(res.townUuid)
        val townName = townOpt.map { it.name ?: "Unknown" }.orElse("Unknown")
        this.residentManager.clearTown(target.uuid)
        this.plugin.messageManager.sendMessage(sender, "town.kicked", "player", (target.name ?: "Unknown"), "town", townName)
        val targetPlayer = Bukkit.getPlayer(target.uuid!!)
        if (targetPlayer != null) {
            this.plugin.messageManager.sendMessage(
                targetPlayer as CommandSender,
                "town.kicked-broadcast",
                "town",
                townName,
                "kicker",
                player.name
            )
        }
    }

    private fun handleLeave(sender: CommandSender) {
        val members: MutableList<TowniaPlayer>?
        val player: Player = this.requirePlayer(sender) ?: return
        val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
        if (res.isMayor && (this.residentManager.getResidentsByTown(res.townUuid!!)
                .also { members = it }).size > 1
        ) {
            this.plugin.messageManager.sendMessage(sender, "town.not-mayor")
            return
        }
        val townOpt: Optional<Town> = this.townManager.getTown(res.townUuid)
        val townName = townOpt.map { it.name ?: "Unknown" }.orElse("Unknown")
        this.residentManager.clearTown(player.uniqueId)
        this.plugin.messageManager.sendMessage(sender, "town.left", "town", townName)
        val remaining: MutableList<TowniaPlayer> = this.residentManager.getResidentsByTown(res.townUuid!!)
        for (member in remaining) {
            val memberPlayer = Bukkit.getPlayer(member.uuid!!) ?: continue
            this.plugin.messageManager.sendMessage(
                memberPlayer as CommandSender,
                "town.left-broadcast",
                "player",
                player.name,
                "town",
                townName
            )
        }
    }

    private fun handleSpawn(sender: CommandSender, args: Array<out String>) {
        val town: Town?
        val player: Player = this.requirePlayer(sender) ?: return
        if (args.size >= 2) {
            val townOpt: Optional<Town> = this.townManager.getTownByName(args[1])
            if (townOpt.isEmpty) {
                this.plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
                return
            }
            town = townOpt.get()
            val playerRes = this.residentManager.getResident(player.uniqueId).orElse(null)
            val isMember = playerRes?.isInTown == true && town.id == playerRes.townUuid
            var isSameNationOrAlly = false
            if (playerRes?.isInTown == true && town.nationUuid != null) {
                val playerTown = this.townManager.getTown(playerRes.townUuid).orElse(null)
                if (playerTown != null && playerTown.nationUuid == town.nationUuid) {
                    isSameNationOrAlly = true
                } else if (playerTown != null && playerTown.nationUuid != null) {
                    val targetNation = this.plugin.nationManager.getNation(town.nationUuid!!).orElse(null)
                    if (targetNation != null && targetNation.allies.contains(playerTown.nationUuid)) {
                        isSameNationOrAlly = true
                    }
                }
            }

            if (!town.isPublic && !isMember && !isSameNationOrAlly && !player.hasPermission("townia.admin")) {
                this.plugin.messageManager.sendMessage(sender, "error.no-permission")
                return
            }
        } else {
            val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
            val townOpt: Optional<Town> = this.townManager.getTown(res.townUuid)
            if (townOpt.isEmpty) {
                this.plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
                return
            }
            town = townOpt.get()
        }
        if (!town.hasSpawn()) {
            this.plugin.messageManager.sendMessage(sender, "town.spawn-not-set")
            return
        }
        val world: World? = Bukkit.getWorld((town.spawnWorld ?: ""))
        if (world == null) {
            this.plugin.messageManager.sendMessage(sender, "error.wrong-world", "world", (town.spawnWorld ?: ""))
            return
        }
        val loc = Location(
            world,
            town.spawnX,
            town.spawnY,
            town.spawnZ,
            town.spawnYaw,
            town.spawnPitch
        )
        this.plugin.messageManager.sendMessage(sender, "town.teleporting", "town", (town.name ?: ""))
        player.teleport(loc)
    }

    private fun handleSet(sender: CommandSender, args: Array<out String>) {
        val player: Player = this.requirePlayer(sender) ?: return
        if (args.isEmpty()) {
            this.handleInfo(sender, arrayOf("info"))
            return
        }
        val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
        when (args[1].lowercase(Locale.getDefault())) {
            "spawn" -> {
                if (!res.isAssistantOrHigher) {
                    this.plugin.messageManager
                        .sendMessage(sender, "town.not-assistant")
                    return
                }
                val c = player.location.chunk
                val plotOpt: Optional<Plot> =
                    this.plugin.plotManager.getPlot(c.world.name, c.x, c.z)
                if (plotOpt.isEmpty || !plotOpt.get().townUuid.toString().equals(res.townUuid)) {
                    this.plugin.messageManager
                        .sendMessage(sender, "town.not-owned-plot")
                    return
                }
                try {
                    this.townManager.setSpawn(res.townUuid, player.location)
                    this.plugin.messageManager
                        .sendMessage(sender, "town.spawn-set")
                } catch (e: TowniaException) {
                    this.plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                        .toTypedArray()
                    )
                }
            }

            "homeblock" -> {
                if (!res.isAssistantOrHigher) {
                    this.plugin.messageManager
                        .sendMessage(sender, "town.not-assistant")
                    return
                }
                val c = player.location.chunk
                val plotOpt: Optional<Plot> =
                    this.plugin.plotManager.getPlot(c.world.name, c.x, c.z)
                if (plotOpt.isEmpty || !plotOpt.get().townUuid.toString().equals(res.townUuid)) {
                    this.plugin.messageManager
                        .sendMessage(sender, "town.not-owned-plot")
                    return
                }
                val town: Town = this.townManager.getTown(res.townUuid).orElse(null) ?: return
                town.setHomeBlock(c.world.name, c.x, c.z)
                this.townManager.saveTown(town)
                this.plugin.messageManager
                    .sendMessage(sender, "town.homeblock-set")
            }

            "name" -> {
                if (!res.isMayorOrHigher) {
                    this.plugin.messageManager
                        .sendMessage(sender, "town.not-mayor")
                    return
                }
                if (args.size < 3) {
                    this.plugin.messageManager
                        .sendMessage(sender, "error.invalid-args")
                    return
                }
                val newName = args[2]
                try {
                    val oldName: String? = this.townManager.getTown(res.townUuid).get().name
                    this.townManager.renameTown(res.townUuid, newName)
                    this.plugin.messageManager.sendMessage(sender, "town.renamed", "old", (oldName ?: ""), "new", newName)
                } catch (e: TowniaException) {
                    this.plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                        .toTypedArray()
                    )
                }
            }

            "perm" -> {
                val state: Boolean
                val t2: Town = this.townManager.getTown(res.townUuid).orElse(null) ?: return
                if (args.size < 3) {
                    this.plugin.messageManager.sendMessageWithoutPrefix(
                        sender,
                        "town.info-perms",
                        "resident",
                        (t2.permsResident ?: ""),
                        "ally",
                        (t2.permsAlly ?: ""),
                        "outsider",
                        (t2.permsOutsider ?: ""),
                        "nation",
                        (t2.permsNation ?: "")
                    )
                    return
                }
                if (!res.isAssistantOrHigher) {
                    this.plugin.messageManager
                        .sendMessage(sender, "town.not-assistant")
                    return
                }
                var groupStr: String? = null
                var actionStr: String? = null
                var stateStr: String? = null
                if (args.size == 3) {
                    stateStr = args[2]
                } else if (args.size == 4) {
                    groupStr = args[2]
                    stateStr = args[3]
                } else if (args.size >= 5) {
                    groupStr = args[2]
                    actionStr = args[3]
                    stateStr = args[4]
                }
                state = stateStr.toString().equals("on", ignoreCase = true) || stateStr.toString().equals("true", ignoreCase = true)
                val bl = state
                if (groupStr == null) {
                    val p = if (state) "BDSI" else ""
                    t2.permsResident = p
                    t2.permsAlly = p
                    t2.permsOutsider = p
                    t2.permsNation = p
                } else {
                    val groups = ArrayList<String>()
                    if (groupStr.equals("resident", ignoreCase = true)) {
                        groups.add("resident")
                    } else if (groupStr.equals("ally", ignoreCase = true) || groupStr.equals(
                            "town",
                            ignoreCase = true
                        )
                    ) {
                        groups.add("ally")
                    } else if (groupStr.equals("outsider", ignoreCase = true)) {
                        groups.add("outsider")
                    } else if (groupStr.equals("nation", ignoreCase = true)) {
                        groups.add("nation")
                    } else {
                        this.plugin.messageManager
                            .sendMessage(sender, "error.invalid-args")
                        return
                    }
                    val actions = ArrayList<Char?>()
                    if (actionStr == null) {
                        actions.addAll(
                            listOf<Char?>(
                                Character.valueOf('B'),
                                Character.valueOf('D'),
                                Character.valueOf('S'),
                                Character.valueOf('I')
                            )
                        )
                    } else if (actionStr.equals("build", ignoreCase = true)) {
                        actions.add(Character.valueOf('B'))
                    } else if (actionStr.equals("destroy", ignoreCase = true)) {
                        actions.add(Character.valueOf('D'))
                    } else if (actionStr.equals("switch", ignoreCase = true)) {
                        actions.add(Character.valueOf('S'))
                    } else if (actionStr.equals("item", ignoreCase = true) || actionStr.equals(
                            "itemuse",
                            ignoreCase = true
                        )
                    ) {
                        actions.add(Character.valueOf('I'))
                    } else {
                        this.plugin.messageManager
                            .sendMessage(sender, "error.invalid-args")
                        return
                    }
                    for (group in groups) {
                        var current: String? = ""
                        if (group == "resident") {
                            current = t2.permsResident
                        } else if (group == "ally") {
                            current = t2.permsAlly
                        } else if (group == "outsider") {
                            current = t2.permsOutsider
                        } else if (group == "nation") {
                            current = (t2.permsNation ?: "")
                        }
                        val iterator: MutableIterator<*> = actions.iterator()
                        while (iterator.hasNext()) {
                            val action = (iterator.next() as Char)
                            current = PermissionMatrix.setPerm(current, action, state)
                        }
                        if (group == "resident") {
                            t2.permsResident = current
                            continue
                        }
                        if (group == "ally") {
                            t2.permsAlly = current
                            continue
                        }
                        if (group == "outsider") {
                            t2.permsOutsider = current
                            continue
                        }
                        if (group != "nation") continue
                        t2.permsNation = current
                    }
                }
                this.plugin.townManager.saveTown(t2)
                this.plugin.messageManager.sendMessage(sender, "town.perm-set")
            }

            "public" -> {
                if (!res.isMayorOrHigher) {
                    this.plugin.messageManager
                        .sendMessage(sender, "town.not-mayor")
                    return
                }
                try {
                    this.townManager.setPublic(res.townUuid, true
)
                    this.plugin.messageManager
                        .sendMessage(sender, "town.set-public")
                } catch (e: TowniaException) {
                    this.plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                        .toTypedArray()
                    )
                }
            }

            "taxes" -> {
                val amount: Double
                if (!res.isMayorOrHigher) {
                    this.plugin.messageManager
                        .sendMessage(sender, "town.not-mayor")
                    return
                }
                if (args.size < 3) {
                    this.plugin.messageManager
                        .sendMessage(sender, "error.invalid-args")
                    return
                }
                try {
                    amount = args[2].toDouble()
                    if (amount < 0.0) {
                        throw NumberFormatException()
                    }
                } catch (_: NumberFormatException) {
                    this.plugin.messageManager
                        .sendMessage(sender, "error.invalid-amount")
                    return
                }
                this.townManager.getTown(res.townUuid).ifPresent({ t ->
                    t.taxes = amount
                    this.plugin.messageManager
                        .sendMessage(sender, "town.taxes-set", "amount", (this.formatMoney(amount) ?: "0"))
                })
            }

            "private" -> {
                if (!res.isMayorOrHigher) {
                    this.plugin.messageManager
                        .sendMessage(sender, "town.not-mayor")
                    return
                }
                try {
                    this.townManager.setPublic(res.townUuid, false
)
                    this.plugin.messageManager
                        .sendMessage(sender, "town.set-private")
                } catch (e: TowniaException) {
                    this.plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                        .toTypedArray()
                    )
                }
            }

            "board" -> {
                if (!res.isMayorOrHigher) {
                    this.plugin.messageManager
                        .sendMessage(sender, "town.not-mayor")
                    return
                }
                if (args.size < 3) {
                    this.plugin.messageManager
                        .sendMessage(sender, "error.invalid-args")
                    return
                }
                val board = java.lang.String.join(" " as CharSequence, *args.copyOfRange(2, args.size))
                this.townManager.getTown(res.townUuid).ifPresent { t ->
                    t.board = board
                    this.plugin.messageManager.sendMessage(sender, "town.board-set", "board", board)
                }
            }

            "plotprice" -> {
                if (!res.isMayorOrHigher) {
                    this.plugin.messageManager
                        .sendMessage(sender, "town.not-mayor")
                    return
                }
                if (args.size < 3) {
                    this.plugin.messageManager
                        .sendMessage(sender, "error.invalid-args")
                    return
                }
                try {
                    val price = args[2].toDouble()
                    this.townManager.getTown(res.townUuid).ifPresent({ t ->
                        t.plotPrice = price
                        this.plugin.messageManager
                            .sendMessage(sender, "town.plotprice-set", "amount", price.toString())
                    })
                } catch (_: NumberFormatException) {
                    this.plugin.messageManager
                        .sendMessage(sender, "error.invalid-amount")
                }
            }

            else -> {
                this.handleHelp(sender)
            }
        }
    }

    private fun handleToggle(sender: CommandSender, args: Array<out String>) {
        val player: Player = this.requirePlayer(sender) ?: return
        val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
        if (!res.isMayorOrHigher) {
            this.plugin.messageManager.sendMessage(sender, "town.not-mayor")
            return
        }
        if (args.size < 2) {
            this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        this.townManager.getTown(res.townUuid).ifPresent({ t ->
            when (args[1].lowercase(Locale.getDefault())) {
                "pvp" -> {
                    t.setPvp(!t.hasPvp())
                    this.plugin.messageManager
                        .sendMessage(sender, "town.toggled", "setting", "PvP", "state", if (t.hasPvp()) "ON" else "OFF")
                }

                "mobs" -> {
                    t.setMobs(!t.hasMobs())
                    this.plugin.messageManager.sendMessage(
                        sender,
                        "town.toggled",
                        "setting",
                        "Mobs",
                        "state",
                        if (t.hasMobs()) "ON" else "OFF"
                    )
                }

                "explosions" -> {
                    t.setExplosions(!t.hasExplosions())
                    this.plugin.messageManager.sendMessage(
                        sender,
                        "town.toggled",
                        "setting",
                        "Explosions",
                        "state",
                        if (t.hasExplosions()) "ON" else "OFF"
                    )
                }

                "fire" -> {
                    t.setFire(!t.hasFire())
                    this.plugin.messageManager.sendMessage(
                        sender,
                        "town.toggled",
                        "setting",
                        "Fire",
                        "state",
                        if (t.hasFire()) "ON" else "OFF"
                    )
                }

                "open" -> {
                    t.isOpen = !t.isOpen
                    this.plugin.messageManager.sendMessage(
                        sender,
                        "town.toggled",
                        "setting",
                        "Open",
                        "state",
                        if (t.isOpen) "ON" else "OFF"
                    )
                }

                "public" -> {
                    t.isPublic = !t.isPublic
                    this.plugin.messageManager.sendMessage(
                        sender,
                        "town.toggled",
                        "setting",
                        "Public",
                        "state",
                        if (t.isPublic) "ON" else "OFF"
                    )
                }

                "taxpercent" -> {
                    t.isTaxPercent = !t.isTaxPercent
                    this.plugin.messageManager.sendMessage(
                        sender,
                        "town.toggled",
                        "setting",
                        "TaxPercent",
                        "state",
                        if (t.isTaxPercent) "ON" else "OFF"
                    )
                }

                else -> {
                    this.plugin.messageManager
                        .sendMessage(sender, "error.invalid-args")
                }
            }
        })
    }

    private fun handleDeposit(sender: CommandSender, args: Array<out String>) {
        val amount: Double
        val player: Player = this.requirePlayer(sender) ?: return
        if (!this.plugin.hasEconomy()) {
            this.plugin.messageManager.sendMessage(sender, "error.no-vault")
            return
        }
        if (args.size < 2) {
            this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
        try {
            amount = args[1].toDouble()
            if (amount <= 0.0) {
                throw NumberFormatException()
            }
        } catch (_: NumberFormatException) {
            this.plugin.messageManager.sendMessage(sender, "error.invalid-amount")
            return
        }
        val eco: Economy = this.plugin.economy!!
        if (!eco.has(player as OfflinePlayer, amount)) {
            this.plugin.messageManager
                .sendMessage(sender, "error.insufficient-funds", "amount", (this.formatMoney(amount) ?: "0"))
            return
        }
        try {
            val resp = eco.withdrawPlayer(player as OfflinePlayer, amount)
            if (!resp.transactionSuccess()) {
                this.plugin.messageManager
                    .sendMessage(sender, "error.insufficient-funds", "amount", (this.formatMoney(amount) ?: "0"))
                return
            }
            this.townManager.addBalance(res.townUuid, amount)
            val townOpt: Optional<Town> = this.townManager.getTown(res.townUuid)
            val newBalance = if (townOpt.isPresent) townOpt.get().balance else 0.0
            this.plugin.messageManager.sendMessage(
                sender,
                "town.deposit-success",
                "amount",
                (this.formatMoney(amount) ?: "0"),
                "balance",
                (this.formatMoney(newBalance) ?: "0")
            )
        } catch (e: TowniaException) {
            this.plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun handleWithdraw(sender: CommandSender, args: Array<out String>) {
        val amount: Double
        val player: Player = this.requirePlayer(sender) ?: return
        if (!this.plugin.hasEconomy()) {
            this.plugin.messageManager.sendMessage(sender, "error.no-vault")
            return
        }
        if (args.size < 2) {
            this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
        if (!res.isMayorOrHigher) {
            this.plugin.messageManager.sendMessage(sender, "town.not-mayor")
            return
        }
        try {
            amount = args[1].toDouble()
            if (amount <= 0.0) {
                throw NumberFormatException()
            }
        } catch (e: NumberFormatException) {
            this.plugin.messageManager.sendMessage(sender, "error.invalid-amount")
            return
        }
        try {
            val townOpt: Optional<Town> = this.townManager.getTown(res.townUuid)
            if (townOpt.isEmpty) {
                this.plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
                return
            }
            val town = townOpt.get()
            if (town.balance < amount) {
                this.plugin.messageManager.sendMessage(
                    sender,
                    "town.withdraw-insufficient",
                    "balance",
                    (this.formatMoney(town.balance) ?: "0"),
                    "amount",
                    (this.formatMoney(amount) ?: "0")
                )
                return
            }
            this.townManager.subtractBalance(res.townUuid, amount)
            this.plugin.economy!!.depositPlayer(player as OfflinePlayer, amount)
            val newTownOpt: Optional<Town> = this.townManager.getTown(res.townUuid)
            val newBalance = if (newTownOpt.isPresent) newTownOpt.get().balance else 0.0
            this.plugin.messageManager.sendMessage(
                sender,
                "town.withdraw-success",
                "amount",
                (this.formatMoney(amount) ?: "0"),
                "balance",
                (this.formatMoney(newBalance) ?: "0")
            )
        } catch (e: TowniaException) {
            this.plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        }
    }

    private fun handleRank(sender: CommandSender, args: Array<out String>) {
        val player: Player = this.requirePlayer(sender) ?: return
        if (args.size < 3) {
            this.handleHelp(sender)
            return
        }
        val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
        if (!res.isMayorOrHigher) {
            this.plugin.messageManager.sendMessage(sender, "town.not-mayor")
            return
        }
        val subAction = args[1].lowercase(Locale.getDefault())
        val targetName = args[2]
        val targetOpt: Optional<TowniaPlayer> = this.residentManager.getResidentByName(targetName)
        if (targetOpt.isEmpty) {
            this.plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", args[2])
            return
        }
        val target: TowniaPlayer = targetOpt.get()
        if (!res.townUuid.toString().equals(target.townUuid)) {
            this.plugin.messageManager.sendMessage(sender, "error.not-same-town")
            return
        }
        when (subAction) {
            "add" -> {
                if (args.size < 4) {
                    this.handleHelp(sender)
                    return
                }
                val rankOpt: Optional<TownRank> =
                    Arrays.stream(TownRank.entries.toTypedArray()).filter { r ->
                        r.name.equals(
                            args[3],
                            ignoreCase = true
                        )
                    }.findFirst()
                if (rankOpt.isEmpty) {
                    this.plugin.messageManager.sendMessage(sender, "error.rank-not-found", "{rank}", args[3])
                    return
                }
                val rank: TownRank = rankOpt.get()
                if (rank === TownRank.MAYOR) {
                    this.plugin.messageManager
                        .sendMessage(sender, "error.no-permission")
                    return
                }
                this.residentManager.setRank(target.uuid, rank)
                this.plugin.messageManager
                    .sendMessage(sender, "town.rank-set", "player", (target.name ?: "Unknown"), "rank", rank.name)
            }

            "remove" -> {
                this.residentManager.setRank(target.uuid, TownRank.RESIDENT)
                this.plugin.messageManager
                    .sendMessage(sender, "town.rank-set", "player", (target.name ?: "Unknown"), "rank", TownRank.RESIDENT.name)
            }

            else -> {
                this.handleHelp(sender)
            }
        }
    }

    private fun handleHere(sender: CommandSender) {
        val player: Player = this.requirePlayer(sender) ?: return
        val chunk = player.location.chunk
        val plotOpt: Optional<Plot> =
            this.plugin.plotManager.getPlot(chunk.world.name, chunk.x, chunk.z)
        if (plotOpt.isEmpty) {
            this.plugin.messageManager
                .sendMessage(sender, "town.chunk-not-claimed")
            return
        }
        val townOpt: Optional<Town> = this.plugin.townManager.getTown(plotOpt.get().townUuid)
        if (townOpt.isEmpty) {
            this.plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
            return
        }
        this.handleInfo(sender, arrayOf("info", townOpt.get().name ?: ""))
    }

    private fun handleInfo(sender: CommandSender, args: Array<out String>) {
        val town: Town?
        if (args.size >= 2) {
            val townOpt: Optional<Town> = this.townManager.getTownByName(args[1])
            if (townOpt.isEmpty) {
                this.plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
                return
            }
            town = townOpt.get()
        } else {
            val player: Player = this.requirePlayer(sender) ?: return
            val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
            val townOpt: Optional<Town> = this.townManager.getTown(res.townUuid)
            if (townOpt.isEmpty) {
                this.plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
                return
            }
            town = townOpt.get()
        }
        val residentCount: Int = this.residentManager.getResidentsByTown(town.id!!).size
        val claims: Int = this.plotManager.countPlotsByTown(town.id)
        val claimLimit: Int = town.claimLimit
        val bonusClaims: Int = town.bonusClaims
        val mayorOpt: Optional<TowniaPlayer> = this.residentManager.getResident(town.mayorUuid!!)
        val mayorName: String = mayorOpt.map { it.name }.orElse("Unknown") ?: "Unknown"
        var mayorRegistered = "Unknown"
        var mayorLastSeen = "Unknown"
        if (mayorOpt.isPresent) {
            val m = mayorOpt.get()
            val offlineMayor = Bukkit.getOfflinePlayer(m.uuid!!)
            mayorRegistered = DATE_FMT.format(Instant.ofEpochMilli(offlineMayor.firstPlayed.let { if (it == 0L) System.currentTimeMillis() else it }))
            mayorLastSeen = if (offlineMayor.isOnline) "Online" else DATE_FMT.format(Instant.ofEpochMilli(m.lastSeen))
        }

        var nationName = "None"
        if (town.isInNation) {
            val nationOpt: Optional<Nation> = this.nationManager.getNation(town.nationUuid)
            nationName = nationOpt.map({ it.name ?: "None" }).orElse("None")
        }
        val created = DATE_FMT.format(Instant.ofEpochMilli(town.createdAt))
        val balance: String = (this.formatMoney(town.balance) ?: "0")
        val upkeep: String = (this.formatMoney(town.dailyUpkeep) ?: "0")
        val taxes: String = (this.formatMoney(town.taxes) ?: "0")
        val townResidents: MutableList<TowniaPlayer> = this.residentManager.getResidentsByTown(town.id)
        val assistants = ArrayList<String>()
        val submayors = ArrayList<String>()
        val residentNames = ArrayList<String>()
        for (r in townResidents) {
            residentNames.add((r.name ?: ""))
            if (r.rank === TownRank.ASSISTANT) {
                assistants.add((r.name ?: ""))
            }
            if (r.rank !== TownRank.CO_MAYOR) continue
            submayors.add((r.name ?: ""))
        }
        this.plugin.messageManager.sendMessageWithoutPrefix(
            sender,
            "town.info",
            "town",
            (town.name ?: ""),
            "board",
            if (town.board != null) (town.board ?: "None") else "None",
            "founded",
            created,
            "claims",
            claims.toString(),
            "max_claims",
            claimLimit.toString(),
            "nation_bonus",
            bonusClaims.toString(),
            "home_x",
            if (town.hasHomeBlock()) town.homeBlockX.toString() else "N/A",
            "home_z",
            if (town.hasHomeBlock()) town.homeBlockZ.toString() else "N/A",
            "outposts",
            this.getOutpostCount(town).toString(),
            "perms_build",
            this.formatPerm(town, 'B'),
            "perms_destroy",
            this.formatPerm(town, 'D'),
            "perms_switch",
            this.formatPerm(town, 'S'),
            "perms_item",
            this.formatPerm(town, 'I'),
            "pvp",
            if (town.hasPvp()) "ON" else "OFF",
            "explosions",
            if (town.hasExplosions()) "ON" else "OFF",
            "fire",
            if (town.hasFire()) "ON" else "OFF",
            "mobs",
            if (town.hasMobs()) "ON" else "OFF",
            "balance",
            balance,
            "upkeep",
            upkeep,
            "taxes",
            taxes,
            "mayor",
            mayorName,
            "mayor_registered",
            mayorRegistered,
            "mayor_last_seen",
            mayorLastSeen,
            "assistant_count",
            assistants.size.toString(),
            "assistants",
            if (assistants.isEmpty()) "None" else java.lang.String.join(", " as CharSequence, assistants),
            "submayor_count",
            submayors.size.toString(),
            "submayors",
            if (submayors.isEmpty()) "None" else java.lang.String.join(", " as CharSequence, submayors),
            "nation",
            nationName,
            "residents",
            java.lang.String.join(", " as CharSequence, residentNames)
        )
    }

    private fun formatPerm(town: Town, action: Char): String {
        val sb = StringBuilder()
        sb.append(if ((town.permsResident?.indexOf(action) ?: -1) >= 0) "R" else "-")
        sb.append(if ((town.permsAlly?.indexOf(action) ?: -1) >= 0) "A" else "-")
        sb.append(if ((town.permsNation?.indexOf(action) ?: -1) >= 0) "N" else "-")
        sb.append(if ((town.permsOutsider?.indexOf(action) ?: -1) >= 0) "O" else "-")
        return sb.toString()
    }

    private fun getOutpostCount(town: Town): Int {
        var count = 0
        try {
            for (plot in this.plotManager.getPlotsByTown(town.id!!)) {
                if (!plot!!.isOutpost) continue
                ++count
            }
        } catch (_: Exception) {
        }
        return count
    }

    private fun handleList(sender: CommandSender) {
        val towns: MutableList<Town> = this.townManager.allTowns
        this.plugin.messageManager.sendMessageWithoutPrefix(sender, "town.list-header", "count", towns.size.toString())
        for (town in towns) {
            val residents: Int = this.residentManager.getResidentsByTown(town.id!!).size
            val mayorOpt: Optional<TowniaPlayer> = this.residentManager.getResident(town.mayorUuid!!)
            val mayorName: String = mayorOpt.map { it.name ?: "Unknown" }.orElse("None")
            this.plugin.messageManager.sendMessageWithoutPrefix(
                sender,
                "town.list-entry",
                "town",
                (town.name ?: ""),
                "mayor",
                mayorName,
                "residents",
                residents.toString()
            )
        }
    }

    private fun handleDelete(sender: CommandSender, args: Array<out String>) {
        val player: Player = this.requirePlayer(sender) ?: return
        val res: TowniaPlayer = this.requireInTown(sender, player) ?: return
        if (!res.isMayor) {
            this.plugin.messageManager.sendMessage(sender, "town.not-mayor")
            return
        }
        if (args.size < 2 || !args[1].toString().equals("confirm", ignoreCase = true)) {
            this.plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val townUuid: UUID? = res.townUuid
        try {
            val townOpt: Optional<Town> = this.townManager.getTown(townUuid)
            val townName = townOpt.map({ it.name ?: "Unknown" }).orElse("Unknown")
            val members: MutableList<TowniaPlayer> = this.residentManager.getResidentsByTown(townUuid!!)
            for (member in members) {
                this.residentManager.clearTown(member.uuid)
            }
            this.databaseManager.deleteInvitesByTown(townUuid)
            this.townManager.deleteTown(townUuid)
            this.plugin.messageManager.sendMessage(sender, "town.deleted", "town", townName)
        } catch (e: TowniaException) {
            this.plugin.messageManager.sendMessage(sender, (e.messageKey ?: ""), *e.replacements.filterNotNull()
                .toTypedArray()
            )
        } catch (e: SQLException) {
            this.plugin.logger.log(Level.SEVERE, "DB error deleting invites", e)
            this.plugin.messageManager.sendMessage(sender, "error.database")
        }
    }

    private fun handleHelp(sender: CommandSender) {
        this.plugin.messageManager.sendMessage(sender, "town.help")
    }

    private fun handleMap(sender: CommandSender) {
        val player: Player = this.requirePlayer(sender) ?: return
        val center = player.location.chunk
        val cx = center.x
        val cz = center.z
        val worldName = center.world.name
        val resOpt: TowniaPlayer = this.residentManager.getResident(player.getUniqueId()).orElse(null)
        val playerTownUuid: UUID? = resOpt.townUuid
        player.sendMessage("\u00a78================ \u00a76Townia Map \u00a78================")
        for (z in cz - 5..cz + 5) {
            val row = StringBuilder()
            for (x in cx - 15..cx + 15) {
                val symbol: String
                val plot: Plot? = this.plugin.plotManager.getPlot(worldName, x, z).orElse(null)
                symbol = if (plot == null) "-" else "+"
                val string = symbol
                if (x == cx && z == cz) {
                    row.append("\u00a7e").append(symbol)
                    continue
                }
                if (plot == null) {
                    row.append("\u00a77").append(symbol)
                    continue
                }
                if (playerTownUuid != null && plot.townUuid.toString().equals(playerTownUuid)) {
                    row.append("\u00a7a").append(symbol)
                    continue
                }
                row.append("\u00a7c").append(symbol)
            }
            player.sendMessage(row.toString())
        }
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender !is Player) {
            this.plugin.messageManager.sendMessage(sender, "error.player-only")
            return null
        }
        return sender
    }

    private fun requireInTown(sender: CommandSender, player: Player): TowniaPlayer? {
        val resOpt: Optional<TowniaPlayer> = this.residentManager.getResident(player.uniqueId)
        if (resOpt.isEmpty || !resOpt.get().isInTown) {
            this.plugin.messageManager.sendMessage(sender, "error.not-in-town")
            return null
        }
        return resOpt.get()
    }

    private fun formatMoney(amount: Double): String? {
        if (this.plugin.hasEconomy()) {
            return this.plugin.economy!!.format(amount).replace("[^\\d.,-]".toRegex(), "")
        }
        return String.format("%.2f", amount)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): MutableList<String>? {
        val completions = ArrayList<String?>()
        if (args.size == 1) {
            StringUtil.copyPartialMatches<ArrayList<String?>?>(
                args[0],
                mutableListOf<String?>(
                    "new",
                    "claim",
                    "unclaim",
                    "invite",
                    "kick",
                    "leave",
                    "spawn",
                    "set",
                    "deposit",
                    "withdraw",
                    "rank",
                    "info",
                    "list",
                    "delete",
                    "toggle",
                    "outpost"
                ),
                completions
            )
        } else if (args.size == 2) {
            when (args[0].lowercase(Locale.getDefault())) {
                "spawn", "info" -> {
                    val townNames: MutableList<String?> =
                        this.townManager.allTowns.stream().map(Town::name).toList()
                    StringUtil.copyPartialMatches<ArrayList<String?>?>(args[1], townNames, completions)
                }

                "invite", "kick" -> {
                    val players = ArrayList<String>()
                    for (p in this.plugin.server.onlinePlayers) {
                        players.add(p.name)
                    }
                    StringUtil.copyPartialMatches<ArrayList<String?>?>(args[1], players, completions)
                }

                "set" -> {
                    StringUtil.copyPartialMatches<ArrayList<String?>?>(
                        args[1],
                        mutableListOf<String?>(
                            "spawn",
                            "name",
                            "public",
                            "private",
                            "board",
                            "taxes",
                            "plotprice",
                            "perm"
                        ),
                        completions
                    )
                }

                "toggle" -> {
                    StringUtil.copyPartialMatches<ArrayList<String?>?>(
                        args[1],
                        mutableListOf<String?>(
                            "pvp",
                            "mobs",
                            "explosions",
                            "fire",
                            "open",
                            "public",
                            "taxpercent"
                        ),
                        completions
                    )
                }

                "rank" -> {
                    StringUtil.copyPartialMatches<ArrayList<String?>?>(
                        args[1],
                        mutableListOf<String?>("add", "remove"),
                        completions
                    )
                }

                "delete" -> {
                    StringUtil.copyPartialMatches<ArrayList<String?>?>(
                        args[1],
                        mutableListOf<String?>("confirm"),
                        completions
                    )
                }

                "outpost" -> {
                    StringUtil.copyPartialMatches<ArrayList<String?>?>(
                        args[1],
                        mutableListOf<String?>("list", "tp", "togglepublic"),
                        completions
                    )
                }
            }
        } else if (args.size == 3) {
            if (args[0].toString().equals("rank", ignoreCase = true)) {
                val players = ArrayList<String>()
                for (p in this.plugin.server.onlinePlayers) {
                    players.add(p.name)
                }
                StringUtil.copyPartialMatches<ArrayList<String?>?>(args[2], players, completions)
            } else if (args[0].toString().equals("set", ignoreCase = true) && args[1].toString().equals("perm", ignoreCase = true)) {
                StringUtil.copyPartialMatches<ArrayList<String?>?>(
                    args[2],
                    mutableListOf<String?>(
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
            } else if (args[0].equals("outpost", ignoreCase = true) && (args[1].equals("list", ignoreCase = true) || args[1].equals("tp", ignoreCase = true))) {
                val townNames: MutableList<String?> = this.townManager.allTowns.stream().map(Town::name).toList()
                StringUtil.copyPartialMatches<ArrayList<String?>?>(args[2], townNames, completions)
            }
        } else if (args.size == 4) {
            if (args[0].toString().equals("rank", ignoreCase = true) && args[1].toString().equals("add", ignoreCase = true)) {
                val ranks = ArrayList<String>()
                for (rank in TownRank.entries) {
                    if (rank === TownRank.MAYOR) continue
                    ranks.add(rank.name.lowercase(Locale.getDefault()))
                }
                StringUtil.copyPartialMatches<ArrayList<String?>?>(args[3], ranks, completions)
            } else if (args[0].toString().equals("set", ignoreCase = true) && args[1].toString().equals(
                    "perm",
                    ignoreCase = true
                ) && !args[2].toString().equals("on", ignoreCase = true) && !args[2].toString().equals("off", ignoreCase = true)
            ) {
                StringUtil.copyPartialMatches<ArrayList<String?>?>(
                    args[3],
                    mutableListOf<String?>("build", "destroy", "switch", "itemuse", "on", "off"),
                    completions
                )
            }
        } else if (args.size == 5 && args[0].toString().equals("set", ignoreCase = true) && args[1].toString().equals(
                "perm",
                ignoreCase = true
            ) && !args[3].toString().equals("on", ignoreCase = true) && !args[3].toString().equals("off", ignoreCase = true)
        ) {
            StringUtil.copyPartialMatches<ArrayList<String?>?>(
                args[4],
                mutableListOf<String?>("on", "off"),
                completions
            )
        }
        return completions as MutableList<String>?
    }

    companion object {
        private val DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}