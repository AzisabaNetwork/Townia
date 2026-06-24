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
import java.util.*
import java.util.function.Function
import java.util.logging.Level
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.Double
import kotlin.Int
import kotlin.NumberFormatException
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.map
import kotlin.collections.mutableListOf
import kotlin.collections.remove
import kotlin.collections.toList
import kotlin.compareTo
import kotlin.map
import kotlin.sequences.map
import kotlin.sequences.toList
import kotlin.text.equals
import kotlin.text.format
import kotlin.text.lowercase
import kotlin.text.map
import kotlin.text.toList
import kotlin.toList
import kotlin.toString

class NationCommand(private val plugin: Townia) : CommandExecutor, TabCompleter {
    private val pendingNationInvites: MutableMap<UUID?, UUID?> = HashMap<UUID?, UUID?>()

    private val nationManager: NationManager
    private val townManager: TownManager
    private val residentManager: ResidentManager
    private val plotManager: PlotManager?

    init {
        this.nationManager = plugin.nationManager
        this.townManager = plugin.townManager
        this.residentManager = plugin.residentManager
        this.plotManager = plugin.plotManager
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.size == 0) {
            sendHelp(sender)
            return true
        }

        when (args[0]!!.lowercase(Locale.getDefault())) {
            "new" -> handleNew(sender, args)
            "invite", "add" -> handleInvite(sender, args)
            "join" -> handleJoin(sender, args)
            "leave" -> handleLeave(sender)
            "kick" -> handleKick(sender, args)
            "deposit" -> handleDeposit(sender, args)
            "withdraw" -> handleWithdraw(sender, args)
            "set" -> handleSet(sender, args)
            "ally" -> handleAlly(sender, args)
            "enemy" -> handleEnemy(sender, args)
            "info" -> handleInfo(sender, args)
            "list" -> handleList(sender)
            "spawn" -> handleSpawn(sender)
            "setspawn" -> handleSetSpawn(sender)
            "delete" -> handleDelete(sender, args)
            "online" -> handleOnline(sender)
            "toggle" -> handleToggle(sender, args)
            "?", "help" -> sendHelp(sender)
            else -> sendHelp(sender)
        }
        return true
    }

    private fun handleNew(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender)
        if (player == null) return

        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val res: TowniaPlayer? = requireMayorInTown(sender, player)
        if (res == null) return

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
            nationManager.createNation(name, townUuid, player.getUniqueId())
            plugin.messageManager.sendMessage(sender, "nation.created", "nation", "{nation}", name)
        } catch (e: TowniaException) {
            if (cost > 0 && plugin.hasEconomy()) plugin.economy!!.depositPlayer(player, cost)
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements as? Array<out String> ?: emptyArray()))
        }
    }

    private fun handleInvite(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender)
        if (player == null) return

        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val res: TowniaPlayer? = requireNationLeader(sender, player)
        if (res == null) return

        val leaderTownOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (leaderTownOpt.isEmpty() || !leaderTownOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        val nationUuid: UUID? = leaderTownOpt.get().nationUuid

        val targetTownName = args[1]
        val targetTownOpt: Optional<Town> = townManager.getTownByName(targetTownName)
        if (targetTownOpt.isEmpty()) {
            plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
            return
        }
        val targetTown: Town = targetTownOpt.get()
        if (targetTown.isInNation) {
            plugin.messageManager.sendMessage(sender, "town.already-in-nation")
            return
        }

        pendingNationInvites.put(targetTown.id, nationUuid)

        val nationOpt: Optional<Nation> = nationManager.getNation(nationUuid)
        val nationName = nationOpt.map<String?>({ it?.name ?: "Unknown" }).orElse("Unknown")

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
        val player = requirePlayer(sender)
        if (player == null) return

        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val res: TowniaPlayer? = requireMayorInTown(sender, player)
        if (res == null) return

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
        if (pendingNationUuid == null) {
            plugin.messageManager.sendMessage(sender, "invite.no-pending")
            return
        }

        val nationName = args[1]
        val nationOpt: Optional<Nation> = nationManager.getNationByName(nationName)
        if (nationOpt.isEmpty()) {
            plugin.messageManager.sendMessage(sender, "error.nation-not-found", "nation", "Unknown")
            return
        }
        val nation: Nation = nationOpt.get()
        if (nation.id != pendingNationUuid) {
            plugin.messageManager.sendMessage(sender, "invite.no-pending")
            return
        }

        try {
            nationManager.addTownToNation(nation.id, townUuid)
            pendingNationInvites.remove(townUuid)
            plugin.messageManager.sendMessage(sender, "nation.joined", "nation", "{nation}", (nation.name ?: "Unknown"))
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements as? Array<out String> ?: emptyArray()))
        }
    }

    private fun handleLeave(sender: CommandSender) {
        val player = requirePlayer(sender)
        if (player == null) return

        val res: TowniaPlayer? = requireMayorInTown(sender, player)
        if (res == null) return

        val townUuid: UUID? = res.townUuid
        val townOpt: Optional<Town> = townManager.getTown(townUuid)
        if (townOpt.isEmpty() || !townOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        val town: Town = townOpt.get()
        val nationUuid: UUID? = town.nationUuid

        val nationOpt: Optional<Nation> = nationManager.getNation(nationUuid)
        if (nationOpt.isPresent() && nationOpt.get()!!.capitalTownUuid == townUuid) {
            plugin.messageManager.sendMessage(sender, "nation.not-leader")
            return
        }

        try {
            nationManager.removeTownFromNation(nationUuid!!, townUuid!!)
            val nationName = nationOpt.map<String?>({ it?.name ?: "Unknown" }).orElse("Unknown")
            plugin.messageManager.sendMessage(sender, "nation.left", "nation", "{nation}", nationName)
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements as? Array<out String> ?: emptyArray()))
        }
    }

    private fun handleKick(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender)
        if (player == null) return

        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val res: TowniaPlayer? = requireNationLeader(sender, player)
        if (res == null) return

        val leaderTownOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (leaderTownOpt.isEmpty() || !leaderTownOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        val nationUuid: UUID = leaderTownOpt.get().nationUuid!!

        val targetTownName = args[1]
        val targetOpt: Optional<Town> = townManager.getTownByName(targetTownName)
        if (targetOpt.isEmpty()) {
            plugin.messageManager.sendMessage(sender, "error.town-not-found", "town", "Unknown")
            return
        }
        val targetTown: Town = targetOpt.get()
        if (nationUuid != targetTown.nationUuid) {
            plugin.messageManager.sendMessage(sender, "error.not-same-town")
            return
        }
        val nationOpt: Optional<Nation> = nationManager.getNation(nationUuid)
        if (nationOpt.isPresent() && nationOpt.get()!!.capitalTownUuid!!.equals(targetTown.id)) {
            plugin.messageManager.sendMessage(sender, "error.cannot-kick-mayor")
            return
        }

        try {
            nationManager.removeTownFromNation(nationUuid!!, targetTown.id!!)
            val nationName = nationOpt.map<String?>({ it?.name ?: "Unknown" }).orElse("Unknown")
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
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements as? Array<out String> ?: emptyArray()))
        }
    }

    private fun handleDeposit(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender)
        if (player == null) return

        if (!plugin.hasEconomy()) {
            plugin.messageManager.sendMessage(sender, "error.no-vault")
            return
        }

        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val res: TowniaPlayer? = requireMayorInTown(sender, player)
        if (res == null) return

        val townOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (townOpt.isEmpty() || !townOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        val nationUuid: UUID? = townOpt.get().nationUuid

        val amount: Double
        try {
            amount = args[1]!!.toDouble()
            if (amount <= 0) throw NumberFormatException()
        } catch (e: NumberFormatException) {
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
            val balance = nationOpt2.map<String?>({ n: Nation? -> formatMoney(n?.balance ?: 0.0) }).orElse("?")
            plugin.messageManager.sendMessage(
                sender, "nation.deposit-success",
                "{amount}", formatMoney(amount), "{balance}", balance
            )
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements as? Array<out String> ?: emptyArray()))
        }
    }

    private fun handleWithdraw(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender)
        if (player == null) return

        if (!plugin.hasEconomy()) {
            plugin.messageManager.sendMessage(sender, "error.no-vault")
            return
        }

        if (args.size < 2) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val res: TowniaPlayer? = requireNationLeader(sender, player)
        if (res == null) return

        val leaderTownOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (leaderTownOpt.isEmpty() || !leaderTownOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }
        val nationUuid: UUID? = leaderTownOpt.get().nationUuid

        val amount: Double
        try {
            amount = args[1]!!.toDouble()
            if (amount <= 0) throw NumberFormatException()
        } catch (e: NumberFormatException) {
            plugin.messageManager.sendMessage(sender, "error.invalid-amount")
            return
        }

        try {
            val nationOpt: Optional<Nation> = nationManager.getNation(nationUuid)
            if (nationOpt.isEmpty()) {
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
            plugin.messageManager.sendMessage(
                sender, "nation.withdraw-success",
                "{amount}", formatMoney(amount), "{balance}", formatMoney(updated.balance)
            )
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements as? Array<out String> ?: emptyArray()))
        }
    }

    private fun handleSet(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender)
        if (player == null) return

        val res: TowniaPlayer? = requireMayorInTown(sender, player)
        if (res == null) return

        val town: Town? = townManager.getTown(res.townUuid).orElse(null)
        if (town == null || town.nationUuid == null) {
            plugin.messageManager.sendMessage(sender, "nation.not-in-nation")
            return
        }

        val nation: Nation? = nationManager.getNation(town.nationUuid).orElse(null)
        if (nation == null) return

        if (nation.leaderUuid!! != player.uniqueId) {
            plugin.messageManager.sendMessage(sender, "nation.not-leader")
            return
        }

        if (args.size < 3) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        when (args[1]!!.lowercase(Locale.getDefault())) {
            "board" -> {
                val board = Arrays.copyOfRange(args, 2, args.size).joinToString(" ")
                nation.board = board
                plugin.messageManager.sendMessage(sender, "nation.board-set", "board", board)
            }

            "taxes" -> {
                try {
                    val taxes = args[2]!!.toDouble()
                    nation.taxes = taxes
                    plugin.messageManager.sendMessage(sender, "nation.taxes-set", "amount", taxes.toString())
                } catch (e: NumberFormatException) {
                    plugin.messageManager.sendMessage(sender, "error.invalid-amount")
                }
            }

            "king" -> {
                plugin.messageManager.sendMessage(sender, "error.not-implemented")
            }

            "capital" -> {
                plugin.messageManager.sendMessage(sender, "error.not-implemented")
            }

            "title" -> {
                plugin.messageManager.sendMessage(sender, "error.not-implemented")
            }

            "surname" -> {
                plugin.messageManager.sendMessage(sender, "error.not-implemented")
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
        val player = requirePlayer(sender)
        if (player == null) return

        val res: TowniaPlayer? = requireMayorInTown(sender, player)
        if (res == null) return

        val town: Town? = townManager.getTown(res.townUuid).orElse(null)
        if (town == null || town.nationUuid == null) {
            plugin.messageManager.sendMessage(sender, "nation.not-in-nation")
            return
        }

        val nation: Nation? = nationManager.getNation(town.nationUuid).orElse(null)
        if (nation == null) return

        if (nation.leaderUuid!! != player.uniqueId) {
            plugin.messageManager.sendMessage(sender, "nation.not-leader")
            return
        }

        if (args.size < 3) {
            plugin.messageManager.sendMessage(sender, "error.invalid-args")
            return
        }

        val action = args[1]!!.lowercase(Locale.getDefault())
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
            if (action == "add") {
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
            } else if (action == "remove") {
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
            } else {
                plugin.messageManager.sendMessage(sender, "error.invalid-args")
            }
        } catch (e: SQLException) {
            plugin.getLogger().log(Level.SEVERE, "Failed to update nation relations", e)
            plugin.messageManager.sendMessage(sender, "error.database")
        }
    }

    private fun handleInfo(sender: CommandSender, args: Array<out String>) {
        val nation: Nation?
        if (args.size >= 2) {
            val nationOpt: Optional<Nation> = nationManager.getNationByName(args[1])
            if (nationOpt.isEmpty()) {
                plugin.messageManager.sendMessage(sender, "error.nation-not-found", "nation", "Unknown")
                return
            }
            nation = nationOpt.get()
        } else {
            // Show own nation
            val player = requirePlayer(sender)
            if (player == null) return
            val res: TowniaPlayer? = requireInTown(sender, player)
            if (res == null) return

            val townOpt: Optional<Town> = townManager.getTown(res.townUuid)
            if (townOpt.isEmpty() || !townOpt.get().isInNation) {
                plugin.messageManager.sendMessage(sender, "town.not-in-nation")
                return
            }
            val nationOpt: Optional<Nation> = nationManager.getNation(townOpt.get().nationUuid)
            if (nationOpt.isEmpty()) {
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
        val leaderName: kotlin.String = leaderOpt.map { it.name }.orElse("Unknown") ?: "Unknown"

        plugin.messageManager.sendMessage(
            sender, "nation.info",
            "nation", (nation.name ?: "Unknown"),
            "leader", leaderName,
            "capital", capitalName,
            "towns", towns.size.toString(),
            "residents", totalResidents.toString(),
            "balance", formatMoney(nation.balance)
        )
    }

    private fun handleSpawn(sender: CommandSender) {
        val player = requirePlayer(sender)
        if (player == null) return

        val res: TowniaPlayer? = requireInTown(sender, player)
        if (res == null) return

        val townOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (townOpt.isEmpty() || !townOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }

        val nation: Nation? = nationManager.getNation(townOpt.get().nationUuid).orElse(null)
        if (nation == null) return

        if (!nation.hasSpawn()) {
            plugin.messageManager.sendMessage(sender, "nation.spawn-not-set")
            return
        }

        val world: World? = Bukkit.getWorld((nation.spawnWorld ?: "world"))
        if (world == null) {
            plugin.messageManager.sendMessage(sender, "nation.spawn-not-set")
            return
        }

        val loc = Location(
            world,
            nation.spawnX,
            nation.spawnY,
            nation.spawnZ,
            nation.spawnYaw,
            nation.spawnPitch
        )
        plugin.messageManager.sendMessage(sender, "nation.teleporting", "nation", (nation.name ?: "Unknown"))
        player.teleport(loc)
    }

    private fun handleSetSpawn(sender: CommandSender) {
        val player = requirePlayer(sender)
        if (player == null) return

        val res: TowniaPlayer? = requireNationLeader(sender, player)
        if (res == null) return

        val leaderTownOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (leaderTownOpt.isEmpty() || !leaderTownOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return
        }

        val nationUuid: UUID? = leaderTownOpt.get().nationUuid
        val nation: Nation? = nationManager.getNation(nationUuid).orElse(null)
        if (nation == null) return

        val loc = player.getLocation()
        nation.setSpawn(loc.getWorld().name, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch())
        nationManager.saveNation(nation)

        plugin.messageManager.sendMessage(sender, "nation.spawn-set")
    }

    private fun handleList(sender: CommandSender) {
        val nations: MutableList<Nation> = nationManager.allNations
        plugin.messageManager.sendMessage(
            sender, "nation.list-header",
            "count", nations.size.toString()
        )
        for (nation in nations) {
            val townCount: Int = nationManager.getTownsInNation(nation.id!!).size
            val leaderOpt: Optional<TowniaPlayer> = residentManager.getResident(nation.leaderUuid)
            val leaderName: kotlin.String = leaderOpt.map { it.name }.orElse("Unknown") ?: "Unknown"
            plugin.messageManager.sendMessage(
                sender, "nation.list-entry",
                "nation", (nation.name ?: "Unknown"),
                "leader", leaderName,
                "towns", townCount.toString()
            )
        }
    }

    private fun handleDelete(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender)
        if (player == null) return

        val res: TowniaPlayer? = requireNationLeader(sender, player)
        if (res == null) return

        val leaderTownOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (leaderTownOpt.isEmpty() || !leaderTownOpt.get().isInNation) {
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
            val nationName = nationOpt.map({ it?.name ?: "Unknown" }).orElse("Unknown")
            nationManager.deleteNation(nationUuid!!)
            plugin.messageManager.sendMessage(sender, "nation.deleted", "nation", nationName)
        } catch (e: TowniaException) {
            plugin.messageManager.sendMessage(sender, e.messageKey ?: "error.unknown", *(e.replacements as? Array<out String> ?: emptyArray()))
        }
    }

    private fun sendHelp(sender: CommandSender) {
        plugin.messageManager.sendMessage(sender, "nation.help")
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender !is Player) {
            plugin.messageManager.sendMessage(sender, "error.player-only")
            return null
        }
        return sender
    }

    private fun requireInTown(sender: CommandSender, player: Player): TowniaPlayer? {
        val resOpt: Optional<TowniaPlayer> = residentManager.getResident(player.getUniqueId())
        if (resOpt.isEmpty() || !resOpt.get().isInTown) {
            plugin.messageManager.sendMessage(sender, "error.not-in-town")
            return null
        }
        return resOpt.get()
    }

    private fun requireMayorInTown(sender: CommandSender, player: Player): TowniaPlayer? {
        val res: TowniaPlayer? = requireInTown(sender, player)
        if (res == null) return null
        if (!res.isMayorOrHigher) {
            plugin.messageManager.sendMessage(sender, "town.not-mayor")
            return null
        }
        return res
    }

    private fun requireNationLeader(sender: CommandSender, player: Player): TowniaPlayer? {
        val res: TowniaPlayer? = requireMayorInTown(sender, player)
        if (res == null) return null

        val townOpt: Optional<Town> = townManager.getTown(res.townUuid)
        if (townOpt.isEmpty() || !townOpt.get().isInNation) {
            plugin.messageManager.sendMessage(sender, "town.not-in-nation")
            return null
        }
        val town: Town = townOpt.get()
        val nationUuid: UUID? = town.nationUuid
        val nationOpt: Optional<Nation> = nationManager.getNation(nationUuid)
        if (nationOpt.isEmpty()) {
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

    private fun formatMoney(amount: Double): kotlin.String {
        if (plugin.hasEconomy()) return plugin.economy!!.format(amount)
        return kotlin.String.format("%.2f", amount)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: kotlin.String,
        args: Array<out String>
    ): MutableList<String> {
        val completions: MutableList<String> = ArrayList<String>()

        if (args.size == 1) {
            StringUtil.copyPartialMatches(
                args[0]!!,
                mutableListOf<String>(
                    "new", "invite", "join", "leave", "kick", "deposit", "withdraw",
                    "info", "list", "delete"
                ),
                completions
            )
        } else if (args.size == 2) {
            when (args[0]!!.lowercase(Locale.getDefault())) {
                "invite", "kick" -> {
                    val townNames = townManager.allTowns.stream()
                        .map { it?.name ?: "" }.toList()
                    StringUtil.copyPartialMatches(args[1]!!, townNames, completions)
                }

                "join", "info" -> {
                    val nationNames = nationManager.allNations.stream()
                        .map { it?.name ?: "" }.toList()
                    StringUtil.copyPartialMatches(args[1]!!, nationNames, completions)
                }

                "delete" -> StringUtil.copyPartialMatches(
                    args[1]!!,
                    mutableListOf<String>("confirm"),
                    completions
                )
            }
        }
        return completions
    }


    private fun handleOnline(sender: CommandSender) {
        plugin.messageManager.sendMessage(sender, "error.not-implemented")
    }

    private fun handleToggle(sender: CommandSender, args: Array<out String>?) {
        plugin.messageManager.sendMessage(sender, "error.not-implemented")
    }
}