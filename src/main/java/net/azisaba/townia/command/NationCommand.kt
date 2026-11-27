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
import net.azisaba.townia.permission.Perm
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.Location
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
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.logging.Level
import kotlin.Array
import kotlin.Boolean
import kotlin.Double
import kotlin.Int
import kotlin.NumberFormatException
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.mutableListOf
import kotlin.text.equals
import kotlin.text.lowercase

class NationCommand(private val plugin: Townia) : CommandExecutor, TabCompleter {
    private val pendingNationInvites: MutableMap<UUID?, UUID?> = HashMap()

    private val nationManager: NationManager = plugin.nationManager
    private val townManager: TownManager = plugin.townManager
    private val residentManager: ResidentManager = plugin.residentManager
    private val plotManager: PlotManager = plugin.plotManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean =
        CommandHelp.withCommand("nation", args) { execute(sender, command, label, args) }

    private fun execute(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        val gate = TOP_NODES[args[0].lowercase(Locale.getDefault())]
        if (gate != null && !Perm.has(sender, gate)) {
            plugin.messageManager.sendMessage(sender, "error.no-permission")
            return true
        }
        when (args[0].lowercase(Locale.getDefault())) {
            "new" -> handleNew(sender, args)
            "rank" -> handleRank(sender, args)
            "say" -> handleSay(sender, args)
            "allylist" -> handleRelationList(sender, args, allies = true)
            "enemylist" -> handleRelationList(sender, args, allies = false)
            "invite", "add" -> handleInvite(sender, args)
            "join" -> handleJoin(sender, args)
            "leave" -> handleLeave(sender)
            "kick" -> handleKick(sender, args)
            "deposit" -> handleDeposit(sender, args)
            "withdraw" -> handleWithdraw(sender, args)
            "bankhistory" -> handleBankHistory(sender, args)
            "set" -> handleSet(sender, args)
            "ally" -> handleAlly(sender, args)
            "enemy" -> handleEnemy(sender, args)
            "info" -> handleInfo(sender, args)
            "list" -> handleList(sender, args)
            "spawn" -> handleSpawn(sender, args)
            "setspawn" -> handleSetSpawn(sender)
            "delete" -> handleDelete(sender, args)
            "online" -> handleOnline(sender)
            "toggle" -> handleToggle(sender, args)
            "?", "help" -> CommandHelp.handleHelp(plugin.messageManager, sender, "nation", args.drop(1))
            else -> CommandHelp.sendUnknown(plugin.messageManager, sender, "nation", args[0])
        }
        return true
    }

    private fun handleNew(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return

        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val res: TowniaPlayer = requireMayorInTown(sender, player) ?: return

        val townUuid: UUID? = res.townUuid
        val townOpt: Optional<Town> = townManager.getTown(townUuid)
        if (townOpt.isEmpty()) {
            plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
            return
        }
        val town: Town = townOpt.get()

        if (town.isInNation) {
            plugin.messageManager.sendMessage(sender, "town.already-in-nation")
            return
        }

        val cost: Double = plugin.towniaConfig.nationCreationCost
        if (cost > 0) {
            if (!plugin.hasEconomy()) {
                plugin.messageManager.sendMessage(sender, "error.no-vault")
                return
            }
            val eco: Economy = plugin.economy!!
            if (!eco.has(player, cost)) {
                plugin.messageManager.sendMessage(
                    sender, "error.insufficient-funds",
                    "amount", formatMoney(cost)
                )
                return
            }
            eco.withdrawPlayer(player, cost)
        }

        val name = args[1]
        try {
            nationManager.createNation(name, townUuid, player.uniqueId)
            plugin.messageManager.sendMessage(sender, "nation.created", "nation", name)
        } catch (e: TowniaException) {
            if (cost > 0 && plugin.hasEconomy()) plugin.economy!!.depositPlayer(player, cost)
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements.filterNotNull().toTypedArray()))
        }
    }

    private fun handleInvite(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return

        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val res: TowniaPlayer = requireInTown(sender, player) ?: return

        val leaderTownOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (leaderTownOpt.isEmpty || !leaderTownOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        val nationUuid: UUID? = leaderTownOpt.get().nationUuid

        val targetTownName = args[1]
        val targetTownOpt: Optional<Town> = townManager.getTownByName(targetTownName)
        if (targetTownOpt.isEmpty) {
            plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
            return
        }
        val targetTown: Town = targetTownOpt.get()
        if (targetTown.isInNation) {
            plugin.messageManager.sendMessage(sender, "nation.town-already-in-nation", "town", (targetTown.name ?: "Unknown"))
            return
        }

        pendingNationInvites[targetTown.id] = nationUuid

        val nationOpt: Optional<Nation> = nationManager.getNation(nationUuid)
        val nationName = nationOpt.map { it.name ?: "Unknown" }.orElse("Unknown")

        plugin.messageManager.sendMessage(
            sender, "nation.invite-sent",
            "town", targetTownName, "nation", nationName
        )

        val targetMayorUuid: UUID = targetTown.mayorUuid!!
        val targetMayor = Bukkit.getPlayer(targetMayorUuid)
        if (targetMayor != null) {
            plugin.messageManager.sendMessage(
                targetMayor, "nation.invite-received",
                "nation", nationName, "inviter", player.name
            )
        }
    }

    private fun handleJoin(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return

        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val res: TowniaPlayer = requireMayorInTown(sender, player) ?: return

        val townUuid: UUID? = res.townUuid
        val townOpt: Optional<Town> = townManager.getTown(townUuid)
        if (townOpt.isEmpty()) {
            plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
            return
        }
        val town: Town = townOpt.get()

        if (town.isInNation) {
            plugin.messageManager.sendMessage(sender, "town.already-in-nation")
            return
        }

        val pendingNationUuid = pendingNationInvites.get(townUuid)
        val nationName = args[1]
        val nationOpt: Optional<Nation> = nationManager.getNationByName(nationName)
        if (nationOpt.isEmpty) {
            plugin.messageManager.sendMessage(sender, if (pendingNationUuid == null) "invite.no-pending" else "error.nation-not-found", "nation", "Unknown")
            return
        }
        val nation: Nation = nationOpt.get()
        // An open nation takes any town; any other needs to have invited this one.
        if (!nation.isOpen && nation.id != pendingNationUuid) {
            plugin.messageManager.sendMessage(sender, "invite.no-pending")
            return
        }

        try {
            nationManager.addTownToNation(nation.id, townUuid)
            pendingNationInvites.remove(townUuid)
            plugin.messageManager.sendMessage(sender, "nation.joined", "nation", (nation.name ?: "Unknown"))
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements.filterNotNull().toTypedArray()))
        }
    }

    private fun handleLeave(sender: CommandSender) {
        val player = requirePlayer(sender) ?: return

        val res: TowniaPlayer = requireMayorInTown(sender, player) ?: return

        val townUuid: UUID? = res.townUuid
        val townOpt: Optional<Town> = townManager.getTown(townUuid)
        if (townOpt.isEmpty || !townOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        val town: Town = townOpt.get()
        val nationUuid: UUID? = town.nationUuid

        val nationOpt: Optional<Nation> = nationManager.getNation(nationUuid)
        if (nationOpt.isPresent && nationOpt.get().capitalTownUuid == townUuid) {
            plugin.messageManager.sendMessage(sender, "nation.not-leader")
            return
        }

        try {
            nationManager.removeTownFromNation(nationUuid!!, townUuid!!)
            val nationName = nationOpt.map { it.name ?: "Unknown" }.orElse("Unknown")
            plugin.messageManager.sendMessage(sender, "nation.left", "nation", nationName)
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements.filterNotNull().toTypedArray()))
        }
    }

    private fun handleKick(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return

        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val res: TowniaPlayer = requireInTown(sender, player) ?: return

        val leaderTownOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (leaderTownOpt.isEmpty || !leaderTownOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        val nationUuid: UUID = leaderTownOpt.get().nationUuid!!

        val targetTownName = args[1]
        val targetOpt: Optional<Town> = townManager.getTownByName(targetTownName)
        if (targetOpt.isEmpty) {
            plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
            return
        }
        val targetTown: Town = targetOpt.get()
        if (nationUuid != targetTown.nationUuid) {
            plugin.messageManager.sendMessage(sender, "nation.town-not-member", "town", (targetTown.name ?: "Unknown"))
            return
        }
        val nationOpt: Optional<Nation> = nationManager.getNation(nationUuid)
        if (nationOpt.isPresent && nationOpt.get().capitalTownUuid!! == targetTown.id) {
            plugin.messageManager.sendMessage(sender, "nation.cannot-kick-capital", "town", (targetTown.name ?: "Unknown"))
            return
        }

        try {
            nationManager.removeTownFromNation(nationUuid, targetTown.id!!)
            val nationName = nationOpt.map({ it.name ?: "Unknown" }).orElse("Unknown")
            plugin.messageManager.sendMessage(
                sender, "nation.kicked",
                "{town}", targetTownName, "{nation}", nationName
            )
            val targetMayor: Player? = Bukkit.getPlayer(targetTown.mayorUuid!!)
            if (targetMayor != null) {
                plugin.messageManager.sendMessage(
                    targetMayor, "nation.kicked",
                    "{town}", targetTownName, "{nation}", nationName
                )
            }
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements.filterNotNull().toTypedArray()))
        }
    }

    private fun handleDeposit(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return

        if (!plugin.hasEconomy()) {
            plugin.messageManager.sendMessage(sender, "error.no-vault")
            return
        }

        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val res: TowniaPlayer = requireInTown(sender, player) ?: return

        val townOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (townOpt.isEmpty || !townOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        val nationUuid: UUID? = townOpt.get().nationUuid

        val amount: Double
        try {
            amount = args[1].toDouble()
            if (amount <= 0) throw NumberFormatException()
        } catch (_: NumberFormatException) {
            plugin.messageManager.sendMessage(sender, "error.invalid-amount")
            return
        }

        val eco: Economy = plugin.economy!!
        if (!eco.has(player, amount)) {
            plugin.messageManager.sendMessage(
                sender, "error.insufficient-funds",
                "amount", formatMoney(amount)
            )
            return
        }

        try {
            val resp = eco.withdrawPlayer(player, amount)
            if (!resp.transactionSuccess()) {
                plugin.messageManager.sendMessage(
                    sender, "error.insufficient-funds",
                    "amount", formatMoney(amount)
                )
                return
            }
            nationManager.addBalance(nationUuid, amount)
            val nationOpt2: Optional<Nation> = nationManager.getNation(nationUuid)
            val balance = nationOpt2.map({ n: Nation? -> formatMoney(n?.balance ?: 0.0) }).orElse("?")
            val balanceValue = nationOpt2.map({ n: Nation? -> n?.balance ?: 0.0 }).orElse(0.0)
            plugin.databaseManager.recordBankTransaction(
                "NATION",
                nationUuid!!,
                player.uniqueId,
                amount,
                "deposit",
                balanceValue
            )
            plugin.messageManager.sendMessage(
                sender, "nation.deposit-success",
                "{amount}", formatMoney(amount), "{balance}", balance
            )
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements.filterNotNull().toTypedArray()))
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to record nation deposit transaction", e)
            plugin.messageManager.sendMessage(sender, "error.database")
        }
    }

    private fun handleWithdraw(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return

        if (!plugin.hasEconomy()) {
            plugin.messageManager.sendMessage(sender, "error.no-vault")
            return
        }

        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val res: TowniaPlayer = requireInTown(sender, player) ?: return

        val leaderTownOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (leaderTownOpt.isEmpty || !leaderTownOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        val nationUuid: UUID? = leaderTownOpt.get().nationUuid

        val amount: Double
        try {
            amount = args[1].toDouble()
            if (amount <= 0) throw NumberFormatException()
        } catch (_: NumberFormatException) {
            plugin.messageManager.sendMessage(sender, "error.invalid-amount")
            return
        }

        try {
            val nationOpt: Optional<Nation> = nationManager.getNation(nationUuid)
            if (nationOpt.isEmpty) {
                plugin.messageManager.sendMessage(sender, "error.nation-not-found", "nation", "Unknown")
                return
            }
            val nation: Nation = nationOpt.get()
            if (nation.balance < amount) {
                plugin.messageManager.sendMessage(
                    sender, "nation.withdraw-insufficient",
                    "{balance}", formatMoney(nation.balance),
                    "{amount}", formatMoney(amount)
                )
                return
            }
            nationManager.subtractBalance(nationUuid, amount)
            plugin.economy!!.depositPlayer(player, amount)
            val updated: Nation = nationManager.getNation(nationUuid).orElse(nation)
            plugin.databaseManager.recordBankTransaction(
                "NATION",
                nationUuid!!,
                player.uniqueId,
                -amount,
                "withdraw",
                updated.balance
            )
            plugin.messageManager.sendMessage(
                sender, "nation.withdraw-success",
                "{amount}", formatMoney(amount), "{balance}", formatMoney(updated.balance)
            )
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements.filterNotNull().toTypedArray()))
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to record nation withdraw transaction", e)
            plugin.messageManager.sendMessage(sender, "error.database")
        }
    }

    private fun handleBankHistory(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return
        val res: TowniaPlayer = requireInTown(sender, player) ?: return
        val townOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (townOpt.isEmpty || !townOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        val limit = parseHistoryLimit(args)
        try {
            val entries = plugin.databaseManager.getBankTransactions("NATION", townOpt.get().nationUuid!!, limit)
            plugin.messageManager.sendMessage(sender, "bank.nation-history-header", "count", entries.size.toString())
            for (entry in entries) {
                val sign = if (entry.type.name == "DEPOSIT") "+" else "-"
                plugin.messageManager.sendMessageWithoutPrefix(
                    sender,
                    "bank.history-entry",
                    "time",
                    formatHistoryTime(entry.createdAt),
                    "sign",
                    sign,
                    "amount",
                    formatMoney(entry.amount),
                    "reason",
                    entry.reason
                )
            }
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to read nation bank history", e)
            plugin.messageManager.sendMessage(sender, "error.database")
        }
    }

    private fun handleSet(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return

        val res: TowniaPlayer = requireInTown(sender, player) ?: return

        val town: Town? = townManager.getTown(res.townUuid).orElse(null)
        if (town == null || town.nationUuid == null) {
            plugin.messageManager.sendMessage(sender, "nation.not-in-nation")
            return
        }

        val nation: Nation = nationManager.getNation(town.nationUuid).orElse(null) ?: return

        if (args.size < 3) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        // Towny spells the capital's node "capitol".
        val setting = args[1].lowercase(Locale.getDefault()).let { if (it == "capital") "capitol" else it }
        if (!Perm.has(sender, "towny.command.nation.set.$setting")) {
            plugin.messageManager.sendMessage(sender, "nation.not-leader")
            return
        }

        when (args[1].lowercase(Locale.getDefault())) {
            "board" -> {
                val board = args.copyOfRange(2, args.size).joinToString(" ")
                nation.board = board
                nationManager.saveNation(nation)
                plugin.messageManager.sendMessage(sender, "nation.board-set", "board", board)
            }

            "name" -> {
                val newName = args[2]
                try {
                    nationManager.renameNation(nation.id, newName)
                    plugin.messageManager.sendMessage(sender, "nation.renamed", "nation", newName)
                } catch (e: TowniaException) {
                    plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *e.replacements.filterNotNull().toTypedArray())
                }
            }

            "taxes" -> {
                try {
                    val taxes = args[2].toDouble()
                    nation.taxes = taxes
                    nationManager.saveNation(nation)
                    plugin.messageManager.sendMessage(sender, "nation.taxes-set", "amount", taxes.toString())
                } catch (_: NumberFormatException) {
                    plugin.messageManager.sendMessage(sender, "error.invalid-amount")
                }
            }

            "king" -> {
                val target = residentManager.getResidentByName(args[2]).orElse(null)
                if (target == null || target.uuid == null) {
                    plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", args[2])
                    return
                }
                val targetTown = if (target.townUuid != null) townManager.getTown(target.townUuid).orElse(null) else null
                if (targetTown == null || targetTown.nationUuid != nation.id) {
                    plugin.messageManager.sendMessage(sender, "town.not-in-nation")
                    return
                }
                nationManager.setLeader(nation.id, target.uuid)
                plugin.messageManager.sendMessage(sender, "nation.leader-set", "player", target.name ?: "Unknown")
            }

            "capital" -> {
                val targetTown = townManager.getTownByName(args[2]).orElse(null)
                if (targetTown == null) {
                    plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", args[2])
                    return
                }
                try {
                    nationManager.setCapital(nation.id, targetTown.id)
                    plugin.messageManager.sendMessage(sender, "nation.capital-set", "town", targetTown.name ?: "Unknown")
                } catch (e: TowniaException) {
                    plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *e.replacements.filterNotNull().toTypedArray())
                }
            }

            "title" -> {
                setNationTitle(sender, nation, args, true)
            }

            "surname" -> {
                setNationTitle(sender, nation, args, false)
            }

            "tag" -> {
                val raw = args[2]
                val tag = if (raw.equals("clear", ignoreCase = true) || raw.equals("none", ignoreCase = true)) null else raw
                if (tag != null && (tag.length > 4 || !Regex("^[\\p{L}\\p{N}_-]+$").matches(tag))) {
                    plugin.messageManager.sendMessage(sender, "town.tag-invalid", "max", "4")
                    return
                }
                nation.tag = tag
                nationManager.saveNation(nation)
                plugin.messageManager.sendMessage(sender, "nation.tag-set", "tag", tag ?: "-")
            }

            "spawncost" -> {
                val amount = args[2].toDoubleOrNull()
                val cap = plugin.towniaConfig.rules.spawnPricePublic
                if (amount == null || amount < 0.0 || amount.isNaN() || amount.isInfinite()) {
                    plugin.messageManager.sendMessage(sender, "error.invalid-amount")
                    return
                }
                if (amount > cap) {
                    plugin.messageManager.sendMessage(sender, "town.taxes-too-high", "max", formatMoney(cap))
                    return
                }
                nation.spawnCost = amount
                nationManager.saveNation(nation)
                plugin.messageManager.sendMessage(sender, "town.value-set", "setting", "spawncost", "amount", formatMoney(amount))
            }

            else -> plugin.messageManager.sendMessage(sender, "error.invalid-args")
        }
    }

    private fun handleAlly(sender: CommandSender, args: Array<out String>) {
        handleRelation(sender, args, "ALLY")
    }

    private fun handleEnemy(sender: CommandSender, args: Array<out String>) {
        handleRelation(sender, args, "ENEMY")
    }

    private fun handleRelation(sender: CommandSender, args: Array<out String>, relationType: kotlin.String) {
        val player = requirePlayer(sender) ?: return

        val res: TowniaPlayer = requireInTown(sender, player) ?: return

        val town: Town? = townManager.getTown(res.townUuid).orElse(null)
        if (town == null || town.nationUuid == null) {
            plugin.messageManager.sendMessage(sender, "nation.not-in-nation")
            return
        }

        val nation: Nation = nationManager.getNation(town.nationUuid).orElse(null) ?: return

        val allyAction = args.getOrNull(1)?.lowercase(Locale.getDefault())
        val relationNode = if (relationType == "ALLY") {
            "towny.command.nation.ally." + (if (allyAction in setOf("remove", "accept", "deny", "sent", "received")) allyAction else "add")
        } else {
            "towny.command.nation.enemy"
        }
        if (!Perm.has(sender, relationNode)) {
            plugin.messageManager.sendMessage(sender, "nation.not-leader")
            return
        }

        if (relationType == "ALLY" && (allyAction == "sent" || allyAction == "received")) {
            val now = System.currentTimeMillis()
            val ids = if (allyAction == "received") {
                allyRequests[nation.id]?.filterValues { now - it < ALLY_REQUEST_MILLIS }?.keys ?: emptySet()
            } else {
                allyRequests.filterValues { requests -> requests[nation.id]?.let { now - it < ALLY_REQUEST_MILLIS } == true }.keys
            }
            val names = ids.mapNotNull { nationManager.getNation(it).orElse(null)?.name }
            plugin.messageManager.sendMessage(
                sender, "nation.ally-requests-$allyAction",
                "nations", if (names.isEmpty()) plugin.messageManager.getPlainMessage(sender, "common.none") else names.joinToString(", ")
            )
            return
        }

        if (args.size < 3) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val action = args[1].lowercase(Locale.getDefault())
        val targetName = args[2]

        val targetNation: Nation? = nationManager.getNationByName(targetName).orElse(null)
        if (targetNation == null) {
            plugin.messageManager.sendMessage(sender, "error.nation-not-found", "nation", "Unknown")
            return
        }

        if (nation.id == targetNation.id) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        try {
            val needsConsent = relationType == "ALLY" && plugin.towniaConfig.rules.allyApproval
            if (needsConsent || action == "accept" || action == "deny") {
                if (relationType != "ALLY") {
                    plugin.messageManager.sendMessage(sender, "error.invalid-args")
                    return
                }
                val now = System.currentTimeMillis()
                when (action) {
                    "add" -> {
                        if (nation.allies.contains(targetNation.id)) {
                            plugin.messageManager.sendMessage(sender, "nation.ally-already", "nation", targetNation.name ?: "Unknown")
                            return
                        }
                        allyRequests.computeIfAbsent(targetNation.id!!) { java.util.concurrent.ConcurrentHashMap() }[nation.id!!] = now
                        plugin.messageManager.sendMessage(sender, "nation.ally-request-sent", "nation", targetNation.name ?: "Unknown")
                        targetNation.leaderUuid?.let { Bukkit.getPlayer(it) }?.let {
                            plugin.messageManager.sendMessage(it, "nation.ally-request-received", "nation", nation.name ?: "Unknown")
                        }
                    }
                    "accept", "deny" -> {
                        val asked = allyRequests[nation.id]?.remove(targetNation.id)
                        if (asked == null || now - asked >= ALLY_REQUEST_MILLIS) {
                            plugin.messageManager.sendMessage(sender, "nation.ally-no-request", "nation", targetNation.name ?: "Unknown")
                            return
                        }
                        if (action == "deny") {
                            plugin.messageManager.sendMessage(sender, "nation.ally-denied", "nation", targetNation.name ?: "Unknown")
                            return
                        }
                        // Consent makes the alliance hold in both directions.
                        nation.allies.add(targetNation.id)
                        nation.enemies.remove(targetNation.id)
                        targetNation.allies.add(nation.id)
                        targetNation.enemies.remove(nation.id)
                        plugin.databaseManager.addNationRelation(nation.id!!, targetNation.id!!, "ALLY")
                        plugin.databaseManager.addNationRelation(targetNation.id!!, nation.id!!, "ALLY")
                        plugin.messageManager.sendMessage(sender, "nation.relation-added", "nation", targetNation.name ?: "Unknown", "relation", "ally")
                        targetNation.leaderUuid?.let { Bukkit.getPlayer(it) }?.let {
                            plugin.messageManager.sendMessage(it, "nation.relation-added", "nation", nation.name ?: "Unknown", "relation", "ally")
                        }
                    }
                    "remove" -> {
                        nation.allies.remove(targetNation.id)
                        targetNation.allies.remove(nation.id)
                        plugin.databaseManager.removeNationRelation(nation.id!!, targetNation.id!!)
                        plugin.databaseManager.removeNationRelation(targetNation.id!!, nation.id!!)
                        plugin.messageManager.sendMessage(sender, "nation.relation-removed", "nation", targetNation.name ?: "Unknown", "relation", "ally")
                    }
                    else -> plugin.messageManager.sendMessage(sender, "error.invalid-args")
                }
                return
            }
            when (action) {
                "add" -> {
                    if (relationType == "ALLY") {
                        nation.allies.add(targetNation.id)
                        nation.enemies.remove(targetNation.id)
                    } else {
                        nation.enemies.add(targetNation.id)
                        nation.allies.remove(targetNation.id)
                    }
                    plugin.databaseManager.addNationRelation(nation.id!!, targetNation.id!!, relationType)
                    plugin.messageManager.sendMessage(
                        sender,
                        "nation.relation-added",
                        "nation",
                        (targetNation.name ?: "Unknown"),
                        "relation",
                        relationType.lowercase(Locale.getDefault())
                    )
                }
                "remove" -> {
                    if (relationType == "ALLY") {
                        nation.allies.remove(targetNation.id)
                    } else {
                        nation.enemies.remove(targetNation.id)
                    }
                    plugin.databaseManager.removeNationRelation(nation.id!!, targetNation.id!!)
                    plugin.messageManager.sendMessage(
                        sender,
                        "nation.relation-removed",
                        "nation",
                        (targetNation.name ?: "Unknown"),
                        "relation",
                        relationType.lowercase(Locale.getDefault())
                    )
                }
                else -> {
                    plugin.messageManager.sendMessage(sender, "error.invalid-args")
                }
            }
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to update nation relations", e)
            plugin.messageManager.sendMessage(sender, "error.database")
        }
    }

    private fun handleInfo(sender: CommandSender, args: Array<out String>) {
        val nation: Nation?
        if (args.size >= 2) {
            val nationOpt: Optional<Nation> = runCatching { UUID.fromString(args[1]) }
                .map { nationManager.getNation(it) }
                .getOrElse { nationManager.getNationByName(args[1]) }
            if (nationOpt.isEmpty) {
                plugin.messageManager.sendMessage(sender, "error.nation-not-found", "nation", "Unknown")
                return
            }
            nation = nationOpt.get()
        } else {
            val player = requirePlayer(sender) ?: return
            val res: TowniaPlayer = requireInTown(sender, player) ?: return

            val townOpt: Optional<Town> = townManager.getTown(res.townUuid)
            if (townOpt.isEmpty || !townOpt.get().isInNation) {
                plugin.messageManager.sendMessage(sender, "town.not-in-nation")
                return
            }
            val nationOpt: Optional<Nation> = nationManager.getNation(townOpt.get().nationUuid)
            if (nationOpt.isEmpty) {
                plugin.messageManager.sendMessage(sender, "error.nation-not-found", "nation", "Unknown")
                return
            }
            nation = nationOpt.get()
        }

        val towns: MutableList<Town> = nationManager.getTownsInNation(nation.id!!)
        val totalResidents = towns.stream()
            .mapToInt { t: Town? -> residentManager.getResidentsByTown(t!!.id!!).size }
            .sum()

        val capitalOpt: Optional<Town> = townManager.getTown(nation.capitalTownUuid)
        val capitalName = capitalOpt.map { it.name ?: "Unknown" }.orElse("Unknown")

        val leaderOpt: Optional<TowniaPlayer> = residentManager.getResident(nation.leaderUuid)
        val leaderName: String = leaderOpt.map { it.name }.orElse("Unknown") ?: "Unknown"

        var leaderRegistered = "Unknown"
        var leaderLastSeen = "Unknown"
        if (leaderOpt.isPresent) {
            val l = leaderOpt.get()
            val offlineLeader = Bukkit.getOfflinePlayer(l.uuid!!)
            leaderRegistered = DATE_FMT.format(Instant.ofEpochMilli(offlineLeader.firstPlayed.let { if (it == 0L) System.currentTimeMillis() else it }))
            leaderLastSeen = if (offlineLeader.isOnline) "Online" else DATE_FMT.format(Instant.ofEpochMilli(l.lastSeen))
        }

        val created = "Unknown"

        plugin.messageManager.sendMessageWithoutPrefix(
            sender, "nation.info",
            "nation", nation.getFormattedName(sender),
            "board", (nation.board ?: "None"),
            "leader", nation.getKingPrefix(sender) + leaderName + nation.getKingPostfix(sender),
            "leader_registered", leaderRegistered,
            "leader_last_seen", leaderLastSeen,
            "founded", created,
            "capital", capitalName,
            "town_count", towns.size.toString(),
            "towns", if (towns.isEmpty()) "None" else towns.joinToString(", ") { clickableTownName(it) },
            "residents", totalResidents.toString(),
            "balance", formatMoney(nation.balance),
            "taxes", formatMoney(nation.taxes)
        )
    }

    private fun handleSpawn(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return
        val isAdmin = player.hasPermission("townia.admin")

        // The caller's own nation (may be null: an explicit target does not require membership).
        val res: TowniaPlayer? = residentManager.getResident(player.uniqueId).orElse(null)
        val playerTown: Town? = res?.takeIf { it.isInTown }?.let { townManager.getTown(it.townUuid).orElse(null) }
        val nation: Nation? = playerTown?.nationUuid?.let { nationManager.getNation(it).orElse(null) }

        if (args.size >= 2) {
            // "/nation spawn <name>": nation names take priority over town names, since a nation
            // and a town (often its capital) can share the same name.
            val targetNation = runCatching { UUID.fromString(args[1]) }
                .map { nationManager.getNation(it) }
                .getOrElse { nationManager.getNationByName(args[1]) }
                .orElse(null)
            if (targetNation != null) {
                val capital = targetNation.capitalTownUuid?.let { townManager.getTown(it).orElse(null) }
                val ownNation = nation != null && targetNation.id == nation.id
                val allied = nation != null && nation.allies.contains(targetNation.id)
                val publicCapital = capital?.isPublic == true || targetNation.isPublic
                if (!ownNation && capital != null && capital.outlaws.contains(player.uniqueId) && !isAdmin) {
                    plugin.messageManager.sendMessage(sender, "outlaw.entry-denied", "town", capital.name ?: "Unknown")
                    return
                }
                if (!ownNation && !allied && !publicCapital && !isAdmin) {
                    plugin.messageManager.sendMessage(sender, "error.town-not-public", "town", capital?.name ?: (targetNation.name ?: "Unknown"))
                    return
                }
                // Towny: travelling to a nation's spawn is a permission of its own.
                val nationSpawnNode = "towny.nation.spawn." + (if (ownNation) "nation" else if (allied) "ally" else "public")
                if (!Perm.has(sender, nationSpawnNode)) {
                    plugin.messageManager.sendMessage(sender, "error.no-permission")
                    return
                }
                val nationLoc = resolveNationSpawn(targetNation)
                if (nationLoc == null) {
                    plugin.messageManager.sendMessage(sender, "nation.spawn-not-set")
                    return
                }
                val nationKind = if (ownNation) net.azisaba.townia.manager.SpawnTravel.Kind.NATION else if (allied) net.azisaba.townia.manager.SpawnTravel.Kind.ALLY else net.azisaba.townia.manager.SpawnTravel.Kind.PUBLIC
                net.azisaba.townia.manager.SpawnTravel.go(plugin, player, nationKind, capital, nationLoc, targetNation.spawnCost) {
                    plugin.messageManager.sendMessage(sender, "nation.teleporting", "nation", targetNation.name ?: "Unknown")
                }
                return
            }

            val targetTown: Town? = runCatching { UUID.fromString(args[1]) }
                .map { townManager.getTown(it) }
                .getOrElse { townManager.getTownByName(args[1]) }
                .orElse(null)
            if (targetTown == null) {
                plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", args[1])
                return
            }

            val isSameNation = nation != null && targetTown.nationUuid != null && targetTown.nationUuid == nation.id
            val isAlly = nation != null && targetTown.nationUuid != null && nation.allies.contains(targetTown.nationUuid)
            if (targetTown.outlaws.contains(player.uniqueId) && !isAdmin) {
                plugin.messageManager.sendMessage(sender, "outlaw.entry-denied", "town", targetTown.name ?: "Unknown")
                return
            }
            if (!isSameNation && !isAlly && !targetTown.isPublic && !isAdmin) {
                plugin.messageManager.sendMessage(sender, "error.town-not-public", "town", targetTown.name ?: "Unknown")
                return
            }
            val townSpawnNode = "towny.town.spawn." + when {
                playerTown != null && playerTown.id == targetTown.id -> "town"
                isSameNation -> "nation"
                isAlly -> "ally"
                else -> "public"
            }
            if (!Perm.has(sender, townSpawnNode)) {
                plugin.messageManager.sendMessage(sender, "error.no-permission")
                return
            }
            if (!targetTown.hasSpawn()) {
                plugin.messageManager.sendMessage(sender, "town.spawn-not-set")
                return
            }
            val world: World? = Bukkit.getWorld(targetTown.spawnWorld ?: "")
            if (world == null) {
                plugin.messageManager.sendMessage(sender, "error.wrong-world", "world", targetTown.spawnWorld ?: "")
                return
            }
            val loc = Location(
                world,
                targetTown.spawnX,
                targetTown.spawnY,
                targetTown.spawnZ,
                targetTown.spawnYaw,
                targetTown.spawnPitch
            )
            val townKind = when {
                playerTown != null && playerTown.id == targetTown.id -> net.azisaba.townia.manager.SpawnTravel.Kind.TOWN
                isSameNation -> net.azisaba.townia.manager.SpawnTravel.Kind.NATION
                isAlly -> net.azisaba.townia.manager.SpawnTravel.Kind.ALLY
                else -> net.azisaba.townia.manager.SpawnTravel.Kind.PUBLIC
            }
            net.azisaba.townia.manager.SpawnTravel.go(plugin, player, townKind, targetTown, loc) {
                plugin.messageManager.sendMessage(sender, "town.teleporting", "town", targetTown.name ?: "")
            }
            return
        }

        // "/nation spawn" without a target: the caller's own nation.
        if (playerTown == null) {
            plugin.messageManager.sendMessage(sender, "error.not-in-town")
            return
        }
        if (nation == null) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        if (!Perm.has(sender, "towny.nation.spawn.nation")) {
            plugin.messageManager.sendMessage(sender, "error.no-permission")
            return
        }
        val loc = resolveNationSpawn(nation)
        if (loc == null) {
            plugin.messageManager.sendMessage(sender, "nation.spawn-not-set")
            return
        }

        val ownCapital = nation.capitalTownUuid?.let { townManager.getTown(it).orElse(null) }
        net.azisaba.townia.manager.SpawnTravel.go(plugin, player, net.azisaba.townia.manager.SpawnTravel.Kind.NATION, ownCapital, loc) {
            plugin.messageManager.sendMessage(sender, "nation.teleporting", "nation", (nation.name ?: "Unknown"))
        }
    }

    private fun resolveNationSpawn(target: Nation): Location? {
        if (target.hasSpawn()) {
            Bukkit.getWorld(target.spawnWorld ?: "world")?.let {
                return Location(it, target.spawnX, target.spawnY, target.spawnZ, target.spawnYaw, target.spawnPitch)
            }
        }
        val cap = target.capitalTownUuid?.let { townManager.getTown(it).orElse(null) }
        if (cap != null && cap.hasSpawn()) {
            Bukkit.getWorld(cap.spawnWorld ?: "")?.let {
                return Location(it, cap.spawnX, cap.spawnY, cap.spawnZ, cap.spawnYaw, cap.spawnPitch)
            }
        }
        return null
    }

    private fun handleSetSpawn(sender: CommandSender) {
        val player = requirePlayer(sender) ?: return

        val res: TowniaPlayer = requireInTown(sender, player) ?: return

        val leaderTownOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (leaderTownOpt.isEmpty || !leaderTownOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }

        val nationUuid: UUID? = leaderTownOpt.get().nationUuid
        val nation: Nation = nationManager.getNation(nationUuid).orElse(null) ?: return

        val loc = player.location
        nation.setSpawn(loc.getWorld().name, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        nationManager.saveNation(nation)

        plugin.messageManager.sendMessage(sender, "nation.spawn-set")
    }

    private fun handleList(sender: CommandSender, args: Array<out String>) {
        val nations: MutableList<Nation> = nationManager.allNations
        // Same forms as /town list: "/nation list [page]" and "/nation list by <sort> [page]".
        var page = args.getOrNull(1)?.toIntOrNull() ?: 1
        var sort = "name"
        if (args.size >= 3 && args[1].equals("by", ignoreCase = true)) {
            sort = args[2].lowercase(Locale.getDefault())
            page = args.getOrNull(3)?.toIntOrNull() ?: 1
        }
        when (sort) {
            "town", "towns" -> nations.sortByDescending { nationManager.getTownsInNation(it.id!!).size }
            "balance", "bank" -> nations.sortByDescending { it.balance }
            else -> nations.sortBy { it.name?.lowercase(Locale.getDefault()) ?: "" }
        }
        val pageSize = 10
        val maxPage = if (nations.isEmpty()) 1 else (nations.size + pageSize - 1) / pageSize
        page = page.coerceIn(1, maxPage)
        plugin.messageManager.sendMessageWithoutPrefix(
            sender, "nation.list-header",
            "count", nations.size.toString()
        )
        if (nations.isEmpty()) return
        val start = (page - 1) * pageSize
        for (nation in nations.subList(start, (start + pageSize).coerceAtMost(nations.size))) {
            val townCount: Int = nationManager.getTownsInNation(nation.id!!).size
            val leaderOpt: Optional<TowniaPlayer> = residentManager.getResident(nation.leaderUuid)
            val leaderName: String = leaderOpt.map { it.name }.orElse("Unknown") ?: "Unknown"
            plugin.messageManager.sendMessageWithoutPrefix(
                sender, "nation.list-entry",
                "nation", nation.getFormattedName(sender),
                "leader", nation.getKingPrefix(sender) + leaderName + nation.getKingPostfix(sender),
                "towns", clickableCount(townCount, "nation info ${nation.id}", "Click to view nation information")
            )
        }
        plugin.messageManager.sendMessageWithoutPrefix(sender, "nation.list-page", "page", page.toString(), "max", maxPage.toString())
    }

    private fun clickableCount(count: Int, command: String, hover: String): String {
        return "<click:run_command:'/$command'><hover:show_text:'$hover'>$count</hover></click>"
    }

    private fun clickableTownName(town: Town): String {
        val name = town.name ?: "Unknown"
        return "<click:run_command:'/town info ${town.id}'><hover:show_text:'Click to view town information'>$name</hover></click>"
    }

    private fun handleDelete(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return

        val res: TowniaPlayer = requireNationLeader(sender, player) ?: return

        val leaderTownOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (leaderTownOpt.isEmpty || !leaderTownOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        val nationUuid: UUID? = leaderTownOpt.get().nationUuid

        if (args.size < 2 || !args[1].equals("confirm", ignoreCase = true)) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        try {
            val nationOpt: Optional<Nation> = nationManager.getNation(nationUuid)
            val nationName = nationOpt.map { it.name ?: "Unknown" }.orElse("Unknown")
            nationManager.deleteNation(nationUuid!!)
            plugin.messageManager.sendMessage(sender, "nation.deleted", "nation", nationName)
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements.filterNotNull().toTypedArray()))
        }
    }

    private fun sendHelp(sender: CommandSender) {
        CommandHelp.sendList(plugin.messageManager, sender, "nation", 1)
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender !is Player) {
            plugin.messageManager.sendMessage(sender, "error.player-only")
            return null
        }
        return sender
    }

    private fun requireInTown(sender: CommandSender, player: Player): TowniaPlayer? {
        val resOpt: Optional<TowniaPlayer> = residentManager.getResident(player.uniqueId)
        if (resOpt.isEmpty || !resOpt.get().isInTown) {
            plugin.messageManager.sendMessage(sender, "error.not-in-town")
            return null
        }
        return resOpt.get()
    }

    private fun requireMayorInTown(sender: CommandSender, player: Player): TowniaPlayer? {
        val res: TowniaPlayer = requireInTown(sender, player) ?: return null
        if (!res.isMayorOrHigher) {
            plugin.messageManager.sendMessage(sender, "town.not-mayor")
            return null
        }
        return res
    }

    /**
     * `/nation rank add|remove <player> <rank>`. Nation ranks come from townyperms.yml and
     * granting one needs `towny.command.nation.rank.<rank>`.
     */
    private fun handleRank(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return
        val action = args.getOrNull(1)?.lowercase(Locale.getDefault())
        if (args.size < 4 || (action != "add" && action != "remove")) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val res: TowniaPlayer = requireInTown(sender, player) ?: return
        val nationId = townManager.getTown(res.townUuid).orElse(null)?.nationUuid
        if (nationId == null) {
            plugin.messageManager.sendMessage(sender, "nation.not-in-nation")
            return
        }
        val target = residentManager.getResidentByName(args[2]).orElse(null)
        if (target == null) {
            plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", args[2])
            return
        }
        val targetNation = target.townUuid?.let { townManager.getTown(it).orElse(null) }?.nationUuid
        if (targetNation != nationId) {
            plugin.messageManager.sendMessage(sender, "nation.rank-not-member", "player", target.name ?: args[2])
            return
        }
        val rank = args[3].trim().lowercase(Locale.ROOT)
        if (rank !in plugin.townyPerms.nationRankNames()) {
            plugin.messageManager.sendMessage(sender, "error.rank-not-found", "{rank}", rank)
            return
        }
        if (!Perm.has(sender, "towny.command.nation.rank.$rank")) {
            plugin.messageManager.sendMessage(sender, "error.no-permission")
            return
        }
        if (action == "add") target.nationRanks.add(rank) else target.nationRanks.remove(rank)
        residentManager.saveResident(target)
        plugin.messageManager.sendMessage(
            sender, if (action == "add") "town.rank-set" else "town.rank-removed",
            "player", target.name ?: args[2], "rank", rank
        )
        target.uuid?.let { org.bukkit.Bukkit.getPlayer(it) }?.let { plugin.townyPerms.refresh(it) }
    }

    /** `/nation say <message>`: a message to every member of the nation who is online. */
    private fun handleSay(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return
        val res = requireInTown(sender, player) ?: return
        val nation = townManager.getTown(res.townUuid).orElse(null)?.nationUuid?.let { nationManager.getNation(it).orElse(null) }
        if (nation == null) {
            plugin.messageManager.sendMessage(sender, "nation.not-in-nation")
            return
        }
        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val text = args.drop(1).joinToString(" ").replace("<", "\\<")
        for (member in residentManager.getResidentsByNation(nation.id!!)) {
            val online = member.uuid?.let { Bukkit.getPlayer(it) } ?: continue
            plugin.messageManager.sendMessage(online, "nation.say", "nation", nation.name ?: "", "player", player.name, "message", text)
        }
    }

    /** `/nation allylist [nation]` and `/nation enemylist [nation]`. */
    private fun handleRelationList(sender: CommandSender, args: Array<out String>, allies: Boolean) {
        val nation: Nation? = if (args.size >= 2) {
            nationManager.getNationByName(args[1]).orElse(null)
        } else {
            (sender as? Player)?.let { residentManager.getResident(it.uniqueId).orElse(null) }
                ?.townUuid?.let { townManager.getTown(it).orElse(null) }
                ?.nationUuid?.let { nationManager.getNation(it).orElse(null) }
        }
        if (nation == null) {
            plugin.messageManager.sendMessage(sender, if (args.size >= 2) "error.nation-not-found" else "nation.not-in-nation", "nation", args.getOrNull(1) ?: "")
            return
        }
        val names = (if (allies) nation.allies else nation.enemies)
            .mapNotNull { nationManager.getNation(it).orElse(null)?.name }.sorted()
        plugin.messageManager.sendMessage(
            sender, if (allies) "nation.allylist" else "nation.enemylist",
            "nation", nation.name ?: "",
            "nations", if (names.isEmpty()) plugin.messageManager.getPlainMessage(sender, "common.none") else names.joinToString(", ")
        )
    }

    private fun requireNationLeader(sender: CommandSender, player: Player): TowniaPlayer? {
        val res: TowniaPlayer = requireMayorInTown(sender, player) ?: return null

        val townOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (townOpt.isEmpty || !townOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return null
        }
        val town: Town = townOpt.get()
        val nationUuid: UUID? = town.nationUuid
        val nationOpt: Optional<Nation> = nationManager.getNation(nationUuid)
        if (nationOpt.isEmpty) {
            plugin.messageManager.sendMessage(sender, "error.nation-not-found", "nation", "Unknown")
            return null
        }
        val nation: Nation = nationOpt.get()
        if (nation.capitalTownUuid != town.id) {
            plugin.messageManager.sendMessage(sender, "nation.not-leader")
            return null
        }
        return res
    }

    private fun formatMoney(amount: Double): String {
        return if (plugin.hasEconomy()) {
            plugin.economy!!.format(amount).replace("¥", "").replace("\\", "")
        } else {
            amount.toString()
        }
    }

    private fun parseHistoryLimit(args: Array<out String>): Int {
        if (args.size < 2) return 10
        return try {
            args[1].toInt().coerceIn(1, 50)
        } catch (_: NumberFormatException) {
            10
        }
    }

    private fun formatHistoryTime(createdAt: Long): String {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(createdAt))
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): MutableList<String> {
        val completions: MutableList<String> = ArrayList()

        if (args.size == 1) {
            StringUtil.copyPartialMatches(
                args[0],
                mutableListOf(
                    "help", "new", "invite", "join", "leave", "kick", "deposit", "withdraw",
                    "bankhistory", "info", "list", "delete", "set", "online", "toggle", "spawn", "setspawn",
                    "ally", "enemy", "allylist", "enemylist", "rank", "say"
                ),
                completions
            )
        } else if (args.size == 2) {
            when (args[0].lowercase(Locale.getDefault())) {
                "invite", "kick" -> {
                    val townNames = townManager.allTowns.stream()
                        .map { it?.name ?: "" }.toList()
                    StringUtil.copyPartialMatches(args[1], townNames, completions)
                }

                "join", "info" -> {
                    val nationNames = nationManager.allNations.stream()
                        .map { it?.name ?: "" }.toList()
                    StringUtil.copyPartialMatches(args[1], nationNames, completions)
                }

                "delete" -> StringUtil.copyPartialMatches(
                    args[1],
                    mutableListOf("confirm"),
                    completions
                )

                "set" -> StringUtil.copyPartialMatches(
                    args[1],
                    mutableListOf("name", "board", "taxes", "king", "capital", "title", "surname", "tag", "spawncost"),
                    completions
                )

                "toggle" -> StringUtil.copyPartialMatches(
                    args[1],
                    mutableListOf("neutral", "public", "open"),
                    completions
                )

                "list" -> StringUtil.copyPartialMatches(
                    args[1],
                    mutableListOf("by"),
                    completions
                )

                "spawn" -> {
                    val names = townManager.allTowns.mapNotNull { it.name } +
                        nationManager.allNations.mapNotNull { it?.name }
                    StringUtil.copyPartialMatches(args[1], names, completions)
                }
            }
        } else if (args.size == 3) {
            if (args[0].equals("list", ignoreCase = true) && args[1].equals("by", ignoreCase = true)) {
                StringUtil.copyPartialMatches(
                    args[2],
                    mutableListOf("name", "towns", "balance"),
                    completions
                )
            } else if (args[0].equals("set", ignoreCase = true) && (args[1].equals("king", ignoreCase = true) || args[1].equals("title", ignoreCase = true) || args[1].equals("surname", ignoreCase = true))) {
                val names = residentManager.allResidents.mapNotNull { it.name }
                StringUtil.copyPartialMatches(args[2], names, completions)
            } else if (args[0].equals("set", ignoreCase = true) && args[1].equals("capital", ignoreCase = true)) {
                val names = townManager.allTowns.mapNotNull { it.name }
                StringUtil.copyPartialMatches(args[2], names, completions)
            }
        }
        return completions
    }


    private fun handleOnline(sender: CommandSender) {
        val player = requirePlayer(sender) ?: return
        val res = requireInTown(sender, player) ?: return
        val town = townManager.getTown(res.townUuid).orElse(null)
        if (town == null || town.nationUuid == null) {
            plugin.messageManager.sendMessage(sender, "nation.not-in-nation")
            return
        }
        val names = ArrayList<String>()
        for (resident in residentManager.getResidentsByNation(town.nationUuid!!)) {
            val online = Bukkit.getPlayer(resident.uuid!!)
            if (online != null && online.isOnline) names.add(online.name)
        }
        plugin.messageManager.sendMessage(
            sender,
            "nation.online",
            "count",
            names.size.toString(),
            "players",
            if (names.isEmpty()) plugin.messageManager.getPlainMessage(sender, "common.none") else names.joinToString(", ")
        )
    }

    private fun handleToggle(sender: CommandSender, args: Array<out String>?) {
        val player = requirePlayer(sender) ?: return
        val res = requireInTown(sender, player) ?: return
        if (args == null || args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        if (!Perm.has(sender, "towny.command.nation.toggle." + args[1].lowercase(Locale.getDefault()))) {
            plugin.messageManager.sendMessage(sender, "nation.not-leader")
            return
        }
        val town = townManager.getTown(res.townUuid).orElse(null) ?: return
        val nation = nationManager.getNation(town.nationUuid).orElse(null) ?: return
        when (args[1].lowercase(Locale.getDefault())) {
            "neutral" -> {
                nation.isNeutral = !nation.isNeutral
                nationManager.saveNation(nation)
                plugin.messageManager.sendMessage(sender, "nation.neutral-set", "state", if (nation.isNeutral) "ON" else "OFF")
            }
            "public" -> {
                nation.isPublic = !nation.isPublic
                nationManager.saveNation(nation)
                plugin.messageManager.sendMessage(sender, "nation.toggled", "setting", "public", "state", if (nation.isPublic) "ON" else "OFF")
            }
            "open" -> {
                nation.isOpen = !nation.isOpen
                nationManager.saveNation(nation)
                plugin.messageManager.sendMessage(sender, "nation.toggled", "setting", "open", "state", if (nation.isOpen) "ON" else "OFF")
            }
            else -> plugin.messageManager.sendMessage(sender, "error.invalid-args")
        }
    }

    private fun setNationTitle(sender: CommandSender, nation: Nation, args: Array<out String>, title: Boolean) {
        if (args.size < 3) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }
        val target = residentManager.getResidentByName(args[2]).orElse(null)
        if (target == null || target.uuid == null) {
            plugin.messageManager.sendMessage(sender, "error.player-not-found", "player", args[2])
            return
        }
        val value = if (args.size >= 4) args.copyOfRange(3, args.size).joinToString(" ") else null
        if (title) {
            if (value.isNullOrBlank()) nation.removeTitle(target.uuid) else nation.setTitle(target.uuid, value)
        } else {
            if (value.isNullOrBlank()) nation.removeSurname(target.uuid) else nation.setSurname(target.uuid, value)
        }
        nationManager.saveNation(nation)
        plugin.messageManager.sendMessage(sender, "nation.title-set", "player", target.name ?: "Unknown", "kind", if (title) "title" else "surname")
    }

    companion object {
        /** Subcommands guarded by one node. set, toggle, ally and rank check their own finer nodes. */
        private val TOP_NODES: Map<String, String> = mapOf(
            "new" to "towny.command.nation.new",
            "invite" to "towny.command.nation.invite.add",
            "add" to "towny.command.nation.invite.add",
            "join" to "towny.command.nation.join",
            "leave" to "towny.command.nation.leave",
            "kick" to "towny.command.nation.kick",
            "deposit" to "towny.command.nation.deposit",
            "withdraw" to "towny.command.nation.withdraw",
            "bankhistory" to "towny.command.nation.bankhistory",
            "enemy" to "towny.command.nation.enemy",
            "list" to "towny.command.nation.list",
            "setspawn" to "towny.command.nation.set.spawn",
            "delete" to "towny.command.nation.delete",
            "online" to "towny.command.nation.online",
            "say" to "towny.command.nation.say",
            "allylist" to "towny.command.nation.allylist",
            "enemylist" to "towny.command.nation.enemylist"
        )

        /** Alliance requests waiting for an answer: asked nation -> (asking nation -> when). Not kept over a restart, as in Towny. */
        private val allyRequests = java.util.concurrent.ConcurrentHashMap<UUID, MutableMap<UUID, Long>>()
        private const val ALLY_REQUEST_MILLIS = 24L * 60L * 60L * 1000L

        private val DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
