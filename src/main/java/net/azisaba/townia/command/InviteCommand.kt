package net.azisaba.townia.command

import net.azisaba.townia.Townia
import net.azisaba.townia.TowniaException
import net.azisaba.townia.data.Invite
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.database.DatabaseManager
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

class InviteCommand(private val plugin: Townia) : CommandExecutor, TabCompleter {
    private val databaseManager: DatabaseManager?
    private val residentManager: ResidentManager
    private val townManager: TownManager

    init {
        this.databaseManager = plugin.databaseManager
        this.residentManager = plugin.residentManager
        this.townManager = plugin.townManager
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = requirePlayer(sender)
        if (player == null) return true

        val playerUuid = player.getUniqueId()

        if (args.size == 0 || args[0].equals("list", ignoreCase = true)) {
            handleList(player, playerUuid)
            return true
        }

        when (args[0]!!.lowercase(Locale.getDefault())) {
            "accept" -> {
                if (args.size < 2) {
                    handleAcceptOldest(player, playerUuid)
                } else {
                    handleAcceptByTown(player, playerUuid, args[1]!!)
                }
            }

            "decline" -> {
                if (args.size < 2) {
                    plugin.messageManager!!.sendMessage(player, "error.invalid-args")
                    return true
                }
                handleDecline(player, playerUuid, args[1]!!)
            }

            else -> handleList(player, playerUuid)
        }
        return true
    }

    private fun handleList(player: Player, playerUuid: UUID) {
        try {
            val invites: MutableList<Invite> = databaseManager!!.getInvitesByTarget(playerUuid) as MutableList<Invite>
            val timeoutSecs = plugin.towniaConfig!!.inviteTimeout

            if (invites.isEmpty()) {
                plugin.messageManager!!.sendMessage(player, "invite.no-pending")
                return
            }

            plugin.messageManager!!.sendMessage(player, "invite.list-header")
            for (invite in invites) {
                if (invite.isExpired(timeoutSecs)) {
                    databaseManager.deleteInvite(invite.id)
                    continue
                }
                val townOpt = townManager.getTown(invite.townUuid)
                val townName = townOpt.map { it.name ?: "Unknown" }.orElse("Unknown")
                val inviterOpt = residentManager.getResident(invite.inviterUuid)
                val inviterName = inviterOpt.map { it.name ?: "Unknown" }.orElse("Unknown")
                plugin.messageManager!!.sendMessage(
                    player,
                    "invite.list-entry",
                    "town",
                    townName,
                    "inviter",
                    inviterName
                )
            }
        } catch (e: SQLException) {
            plugin.getLogger().log(Level.SEVERE, "DB error listing invites", e)
            plugin.messageManager!!.sendMessage(player, "error.database")
        }
    }

    private fun handleAcceptOldest(player: Player, playerUuid: UUID) {
        try {
            val invites: MutableList<Invite> = databaseManager!!.getInvitesByTarget(playerUuid) as MutableList<Invite>
            val timeoutSecs = plugin.towniaConfig!!.inviteTimeout

            var toAccept: Invite? = null
            for (invite in invites) {
                if (invite.isExpired(timeoutSecs)) {
                    databaseManager.deleteInvite(invite.id)
                } else {
                    toAccept = invite
                    break
                }
            }

            if (toAccept == null) {
                plugin.messageManager!!.sendMessage(player, "invite.no-pending")
                return
            }

            acceptInvite(player, playerUuid, toAccept)
        } catch (e: SQLException) {
            handleError(player, e)
        } catch (e: TowniaException) {
            handleError(player, e)
        }
    }

    private fun handleAcceptByTown(player: Player, playerUuid: UUID, townName: String) {
        try {
            val townOpt = townManager.getTownByName(townName)
            if (townOpt.isEmpty()) {
                plugin.messageManager!!.sendMessage(player, "error.town-not-found", "town", "Unknown")
                return
            }
            val townUuid = townOpt.get().id
            val inviteOpt: Optional<Invite> = databaseManager!!.getInvite(playerUuid, townUuid!!)
            if (inviteOpt.isEmpty()) {
                plugin.messageManager!!.sendMessage(player, "invite.no-pending")
                return
            }
            val invite = inviteOpt.get()
            val timeoutSecs = plugin.towniaConfig!!.inviteTimeout
            if (invite.isExpired(timeoutSecs)) {
                databaseManager.deleteInvite(invite.id)
                plugin.messageManager!!.sendMessage(player, "invite.expired")
                return
            }
            acceptInvite(player, playerUuid, invite)
        } catch (e: SQLException) {
            handleError(player, e)
        } catch (e: TowniaException) {
            handleError(player, e)
        }
    }

    @Throws(SQLException::class, TowniaException::class)
    private fun acceptInvite(player: Player, playerUuid: UUID, invite: Invite) {
        val residentOpt = residentManager.getResident(playerUuid)
        if (residentOpt.isPresent() && residentOpt.get().isInTown) {
            plugin.messageManager!!.sendMessage(player, "error.already-in-town")
            return
        }

        val townUuid = invite.townUuid
        val townOpt = townManager.getTown(townUuid)
        if (townOpt.isEmpty()) {
            databaseManager!!.deleteInvite(invite.id)
            plugin.messageManager!!.sendMessage(player, "error.town-not-found", "town", "Unknown")
            return
        }

        residentManager.setTown(playerUuid, townUuid, TownRank.RESIDENT)
        databaseManager!!.deleteInvitesByTarget(playerUuid)

        val townName: String = townOpt.get().name!!
        plugin.messageManager!!.sendMessage(player, "invite.accept-success", "town", townName)
        plugin.messageManager!!.sendMessage(player, "town.joined", "town", townName)
    }

    private fun handleDecline(player: Player, playerUuid: UUID, townName: String) {
        try {
            val townOpt = townManager.getTownByName(townName)
            if (townOpt.isEmpty()) {
                plugin.messageManager!!.sendMessage(player, "error.town-not-found", "town", "Unknown")
                return
            }
            val townUuid = townOpt.get().id
            val inviteOpt: Optional<Invite> = databaseManager!!.getInvite(playerUuid, townUuid!!)
            if (inviteOpt.isEmpty()) {
                plugin.messageManager!!.sendMessage(player, "invite.no-pending")
                return
            }
            databaseManager.deleteInvite(inviteOpt.get().id)
            plugin.messageManager!!.sendMessage(
                player, "invite.decline-success",
                "town", townOpt.get().name!!
            )
        } catch (e: SQLException) {
            plugin.getLogger().log(Level.SEVERE, "DB error declining invite", e)
            plugin.messageManager!!.sendMessage(player, "error.database")
        }
    }

    private fun handleError(player: Player, e: Exception?) {
        if (e is TowniaException) {
            plugin.messageManager!!.sendMessage(player, e.messageKey!!, *(e.replacements as? Array<out String> ?: emptyArray()))
        } else if (e is SQLException) {
            plugin.getLogger().log(Level.SEVERE, "DB error in InviteCommand", e)
            plugin.messageManager!!.sendMessage(player, "error.database")
        }
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
                mutableListOf<String?>("list", "accept", "decline"),
                completions
            )
        } else if (args.size == 2 && (args[0].equals("accept", ignoreCase = true) || args[0].equals(
                "decline",
                ignoreCase = true
            ))
        ) {
            townManager.allTowns.stream()
                .map<String?>(Town::name)
                .filter { n: String? ->
                    n!!.lowercase(Locale.getDefault()).startsWith(args[1]!!.lowercase(Locale.getDefault()))
                }
                .forEach { e: String? -> completions.add(e) }
        }
        return completions
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender !is Player) {
            plugin.messageManager!!.sendMessage(sender, "error.player-only")
            return null
        }
        return sender
    }
}