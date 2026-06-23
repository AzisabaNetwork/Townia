package net.azisaba.townia.command;

import net.azisaba.townia.Townia;
import net.azisaba.townia.TowniaException;
import net.azisaba.townia.data.Nation;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.data.TowniaPlayer;
import net.azisaba.townia.manager.NationManager;
import net.azisaba.townia.manager.PlotManager;
import net.azisaba.townia.manager.ResidentManager;
import net.azisaba.townia.manager.TownManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.*;
public class NationCommand implements CommandExecutor, TabCompleter {

    private final Map<UUID, UUID> pendingNationInvites = new HashMap<>();

    private final Townia plugin;
    private final NationManager nationManager;
    private final TownManager townManager;
    private final ResidentManager residentManager;
    private final PlotManager plotManager;

    public NationCommand(Townia plugin) {
        this.plugin = plugin;
        this.nationManager = plugin.getNationManager();
        this.townManager = plugin.getTownManager();
        this.residentManager = plugin.getResidentManager();
        this.plotManager = plugin.getPlotManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "new"      -> handleNew(sender, args);
            case "invite", "add" -> handleInvite(sender, args);
            case "join"     -> handleJoin(sender, args);
            case "leave"    -> handleLeave(sender);
            case "kick"     -> handleKick(sender, args);
            case "deposit"  -> handleDeposit(sender, args);
            case "withdraw" -> handleWithdraw(sender, args);
            case "set"      -> handleSet(sender, args);
            case "ally"     -> handleAlly(sender, args);
            case "enemy"    -> handleEnemy(sender, args);
            case "info"     -> handleInfo(sender, args);
            case "list"     -> handleList(sender);
            case "spawn"    -> handleSpawn(sender);
            case "setspawn" -> handleSetSpawn(sender);
            case "delete"   -> handleDelete(sender, args);
            case "online"   -> handleOnline(sender);
            case "toggle"   -> handleToggle(sender, args);
            case "?", "help"-> sendHelp(sender);
            default         -> sendHelp(sender);
        }
        return true;
    }

    private void handleNew(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        TowniaPlayer res = requireMayorInTown(sender, player);
        if (res == null) return;

        UUID townUuid = res.getTownUuid();
        Optional<Town> townOpt = townManager.getTown(townUuid);
        if (townOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", "Unknown");
            return;
        }
        Town town = townOpt.get();

        if (town.isInNation()) {
            plugin.getMessageManager().sendMessage(sender, "town.already-in-nation");
            return;
        }

        double cost = plugin.getTowniaConfig().getNationCreationCost();
        if (cost > 0) {
            if (!plugin.hasEconomy()) {
                plugin.getMessageManager().sendMessage(sender, "error.no-vault");
                return;
            }
            Economy eco = plugin.getEconomy();
            if (!eco.has(player, cost)) {
                plugin.getMessageManager().sendMessage(sender, "error.insufficient-funds",
                        "amount", formatMoney(cost));
                return;
            }
            eco.withdrawPlayer(player, cost);
        }

        String name = args[1];
        try {
            nationManager.createNation(name, townUuid, player.getUniqueId());
            plugin.getMessageManager().sendMessage(sender, "nation.created", "nation", "{nation}", name);
        } catch (TowniaException e) {
            if (cost > 0 && plugin.hasEconomy()) plugin.getEconomy().depositPlayer(player, cost);
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleInvite(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        TowniaPlayer res = requireNationLeader(sender, player);
        if (res == null) return;

        Optional<Town> leaderTownOpt = townManager.getTown(res.getTownUuid());
        if (leaderTownOpt.isEmpty() || !leaderTownOpt.get().isInNation()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-in-nation");
            return;
        }
        UUID nationUuid = leaderTownOpt.get().getNationUuid();

        String targetTownName = args[1];
        Optional<Town> targetTownOpt = townManager.getTownByName(targetTownName);
        if (targetTownOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", "Unknown");
            return;
        }
        Town targetTown = targetTownOpt.get();
        if (targetTown.isInNation()) {
            plugin.getMessageManager().sendMessage(sender, "town.already-in-nation");
            return;
        }

        pendingNationInvites.put(targetTown.getId(), nationUuid);

        Optional<Nation> nationOpt = nationManager.getNation(nationUuid);
        String nationName = nationOpt.map(Nation::getName).orElse("Unknown");

        plugin.getMessageManager().sendMessage(sender, "nation.invite-sent",
                "town", targetTownName, "nation", nationName);

        UUID targetMayorUuid = targetTown.getMayorUuid();
        Player targetMayor = Bukkit.getPlayer(targetMayorUuid);
        if (targetMayor != null) {
            plugin.getMessageManager().sendMessage(targetMayor, "nation.invite-received",
                    "nation", nationName, "inviter", player.getName());
        }
    }

    private void handleJoin(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        TowniaPlayer res = requireMayorInTown(sender, player);
        if (res == null) return;

        UUID townUuid = res.getTownUuid();
        Optional<Town> townOpt = townManager.getTown(townUuid);
        if (townOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", "Unknown");
            return;
        }
        Town town = townOpt.get();

        if (town.isInNation()) {
            plugin.getMessageManager().sendMessage(sender, "town.already-in-nation");
            return;
        }

        UUID pendingNationUuid = pendingNationInvites.get(townUuid);
        if (pendingNationUuid == null) {
            plugin.getMessageManager().sendMessage(sender, "invite.no-pending");
            return;
        }

        String nationName = args[1];
        Optional<Nation> nationOpt = nationManager.getNationByName(nationName);
        if (nationOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.nation-not-found", "nation", "Unknown");
            return;
        }
        Nation nation = nationOpt.get();
        if (!nation.getId().equals(pendingNationUuid)) {
            plugin.getMessageManager().sendMessage(sender, "invite.no-pending");
            return;
        }

        try {
            nationManager.addTownToNation(nation.getId(), townUuid);
            pendingNationInvites.remove(townUuid);
            plugin.getMessageManager().sendMessage(sender, "nation.joined", "nation", "{nation}", nation.getName());
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleLeave(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        TowniaPlayer res = requireMayorInTown(sender, player);
        if (res == null) return;

        UUID townUuid = res.getTownUuid();
        Optional<Town> townOpt = townManager.getTown(townUuid);
        if (townOpt.isEmpty() || !townOpt.get().isInNation()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-in-nation");
            return;
        }
        Town town = townOpt.get();
        UUID nationUuid = town.getNationUuid();

        Optional<Nation> nationOpt = nationManager.getNation(nationUuid);
        if (nationOpt.isPresent() && nationOpt.get().getCapitalTownUuid().equals(townUuid)) {
            plugin.getMessageManager().sendMessage(sender, "nation.not-leader");
            return;
        }

        try {
            nationManager.removeTownFromNation(nationUuid, townUuid);
            String nationName = nationOpt.map(Nation::getName).orElse("Unknown");
            plugin.getMessageManager().sendMessage(sender, "nation.left", "nation", "{nation}", nationName);
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleKick(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        TowniaPlayer res = requireNationLeader(sender, player);
        if (res == null) return;

        Optional<Town> leaderTownOpt = townManager.getTown(res.getTownUuid());
        if (leaderTownOpt.isEmpty() || !leaderTownOpt.get().isInNation()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-in-nation");
            return;
        }
        UUID nationUuid = leaderTownOpt.get().getNationUuid();

        String targetTownName = args[1];
        Optional<Town> targetOpt = townManager.getTownByName(targetTownName);
        if (targetOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", "Unknown");
            return;
        }
        Town targetTown = targetOpt.get();
        if (!nationUuid.equals(targetTown.getNationUuid())) {
            plugin.getMessageManager().sendMessage(sender, "error.not-same-town");
            return;
        }
        Optional<Nation> nationOpt = nationManager.getNation(nationUuid);
        if (nationOpt.isPresent() && nationOpt.get().getCapitalTownUuid().equals(targetTown.getId())) {
            plugin.getMessageManager().sendMessage(sender, "error.cannot-kick-mayor");
            return;
        }

        try {
            nationManager.removeTownFromNation(nationUuid, targetTown.getId());
            String nationName = nationOpt.map(Nation::getName).orElse("Unknown");
            plugin.getMessageManager().sendMessage(sender, "nation.kicked",
                    "{town}", targetTownName, "{nation}", nationName);
            Player targetMayor = Bukkit.getPlayer(targetTown.getMayorUuid());
            if (targetMayor != null) {
                plugin.getMessageManager().sendMessage(targetMayor, "nation.kicked",
                        "{town}", targetTownName, "{nation}", nationName);
            }
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleDeposit(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (!plugin.hasEconomy()) {
            plugin.getMessageManager().sendMessage(sender, "error.no-vault");
            return;
        }

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        TowniaPlayer res = requireMayorInTown(sender, player);
        if (res == null) return;

        Optional<Town> townOpt = townManager.getTown(res.getTownUuid());
        if (townOpt.isEmpty() || !townOpt.get().isInNation()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-in-nation");
            return;
        }
        UUID nationUuid = townOpt.get().getNationUuid();

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-amount");
            return;
        }

        Economy eco = plugin.getEconomy();
        if (!eco.has(player, amount)) {
            plugin.getMessageManager().sendMessage(sender, "error.insufficient-funds",
                    "amount", formatMoney(amount));
            return;
        }

        try {
            EconomyResponse resp = eco.withdrawPlayer(player, amount);
            if (!resp.transactionSuccess()) {
                plugin.getMessageManager().sendMessage(sender, "error.insufficient-funds",
                        "amount", formatMoney(amount));
                return;
            }
            nationManager.addBalance(nationUuid, amount);
            Optional<Nation> nationOpt2 = nationManager.getNation(nationUuid);
            String balance = nationOpt2.map(n -> formatMoney(n.getBalance())).orElse("?");
            plugin.getMessageManager().sendMessage(sender, "nation.deposit-success",
                    "{amount}", formatMoney(amount), "{balance}", balance);
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleWithdraw(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (!plugin.hasEconomy()) {
            plugin.getMessageManager().sendMessage(sender, "error.no-vault");
            return;
        }

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        TowniaPlayer res = requireNationLeader(sender, player);
        if (res == null) return;

        Optional<Town> leaderTownOpt = townManager.getTown(res.getTownUuid());
        if (leaderTownOpt.isEmpty() || !leaderTownOpt.get().isInNation()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-in-nation");
            return;
        }
        UUID nationUuid = leaderTownOpt.get().getNationUuid();

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-amount");
            return;
        }

        try {
            Optional<Nation> nationOpt = nationManager.getNation(nationUuid);
            if (nationOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(sender, "error.nation-not-found", "nation", "Unknown");
                return;
            }
            Nation nation = nationOpt.get();
            if (nation.getBalance() < amount) {
                plugin.getMessageManager().sendMessage(sender, "nation.withdraw-insufficient",
                        "{balance}", formatMoney(nation.getBalance()),
                        "{amount}", formatMoney(amount));
                return;
            }
            nationManager.subtractBalance(nationUuid, amount);
            plugin.getEconomy().depositPlayer(player, amount);
            Nation updated = nationManager.getNation(nationUuid).orElse(nation);
            plugin.getMessageManager().sendMessage(sender, "nation.withdraw-success",
                    "{amount}", formatMoney(amount), "{balance}", formatMoney(updated.getBalance()));
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleSet(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        TowniaPlayer res = requireMayorInTown(sender, player);
        if (res == null) return;

        Town town = townManager.getTown(res.getTownUuid()).orElse(null);
        if (town == null || town.getNationUuid() == null) {
            plugin.getMessageManager().sendMessage(sender, "nation.not-in-nation");
            return;
        }

        Nation nation = nationManager.getNation(town.getNationUuid()).orElse(null);
        if (nation == null) return;

        if (!nation.getLeaderUuid().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(sender, "nation.not-leader");
            return;
        }

        if (args.length < 3) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        switch (args[1].toLowerCase()) {
            case "board" -> {
                String board = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                nation.setBoard(board);
                plugin.getMessageManager().sendMessage(sender, "nation.board-set", "board", board);
            }
            case "taxes" -> {
                try {
                    double taxes = Double.parseDouble(args[2]);
                    nation.setTaxes(taxes);
                    plugin.getMessageManager().sendMessage(sender, "nation.taxes-set", "amount", String.valueOf(taxes));
                } catch (NumberFormatException e) {
                    plugin.getMessageManager().sendMessage(sender, "error.invalid-amount");
                }
            }
            case "king" -> {
                plugin.getMessageManager().sendMessage(sender, "error.not-implemented");
            }
            case "capital" -> {
                plugin.getMessageManager().sendMessage(sender, "error.not-implemented");
            }
            case "title" -> {
                plugin.getMessageManager().sendMessage(sender, "error.not-implemented");
            }
            case "surname" -> {
                plugin.getMessageManager().sendMessage(sender, "error.not-implemented");
            }
            default -> plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
        }
    }

    private void handleAlly(CommandSender sender, String[] args) {
        handleRelation(sender, args, "ALLY");
    }

    private void handleEnemy(CommandSender sender, String[] args) {
        handleRelation(sender, args, "ENEMY");
    }

    private void handleRelation(CommandSender sender, String[] args, String relationType) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        TowniaPlayer res = requireMayorInTown(sender, player);
        if (res == null) return;

        Town town = townManager.getTown(res.getTownUuid()).orElse(null);
        if (town == null || town.getNationUuid() == null) {
            plugin.getMessageManager().sendMessage(sender, "nation.not-in-nation");
            return;
        }

        Nation nation = nationManager.getNation(town.getNationUuid()).orElse(null);
        if (nation == null) return;

        if (!nation.getLeaderUuid().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(sender, "nation.not-leader");
            return;
        }

        if (args.length < 3) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        String action = args[1].toLowerCase();
        String targetName = args[2];

        Nation targetNation = nationManager.getNationByName(targetName).orElse(null);
        if (targetNation == null) {
            plugin.getMessageManager().sendMessage(sender, "error.nation-not-found", "nation", "Unknown");
            return;
        }

        if (nation.getId().equals(targetNation.getId())) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        try {
            if (action.equals("add")) {
                if (relationType.equals("ALLY")) {
                    nation.getAllies().add(targetNation.getId());
                    nation.getEnemies().remove(targetNation.getId());
                } else {
                    nation.getEnemies().add(targetNation.getId());
                    nation.getAllies().remove(targetNation.getId());
                }
                plugin.getDatabaseManager().addNationRelation(nation.getId(), targetNation.getId(), relationType);
                plugin.getMessageManager().sendMessage(sender, "nation.relation-added", "nation", targetNation.getName(), "relation", relationType.toLowerCase());
            } else if (action.equals("remove")) {
                if (relationType.equals("ALLY")) {
                    nation.getAllies().remove(targetNation.getId());
                } else {
                    nation.getEnemies().remove(targetNation.getId());
                }
                plugin.getDatabaseManager().removeNationRelation(nation.getId(), targetNation.getId());
                plugin.getMessageManager().sendMessage(sender, "nation.relation-removed", "nation", targetNation.getName(), "relation", relationType.toLowerCase());
            } else {
                plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to update nation relations", e);
            plugin.getMessageManager().sendMessage(sender, "error.database");
        }
    }

    private void handleInfo(CommandSender sender, String[] args) {
        Nation nation;
        if (args.length >= 2) {
            Optional<Nation> nationOpt = nationManager.getNationByName(args[1]);
            if (nationOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(sender, "error.nation-not-found", "nation", "Unknown");
                return;
            }
            nation = nationOpt.get();
        } else {
            // Show own nation
            Player player = requirePlayer(sender);
            if (player == null) return;
            TowniaPlayer res = requireInTown(sender, player);
            if (res == null) return;

            Optional<Town> townOpt = townManager.getTown(res.getTownUuid());
            if (townOpt.isEmpty() || !townOpt.get().isInNation()) {
                plugin.getMessageManager().sendMessage(sender, "town.not-in-nation");
                return;
            }
            Optional<Nation> nationOpt = nationManager.getNation(townOpt.get().getNationUuid());
            if (nationOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(sender, "error.nation-not-found", "nation", "Unknown");
                return;
            }
            nation = nationOpt.get();
        }

        List<Town> towns = nationManager.getTownsInNation(nation.getId());
        int totalResidents = towns.stream()
                .mapToInt(t -> residentManager.getResidentsByTown(t.getId()).size())
                .sum();

        Optional<Town> capitalOpt = townManager.getTown(nation.getCapitalTownUuid());
        String capitalName = capitalOpt.map(Town::getName).orElse("Unknown");

        Optional<TowniaPlayer> leaderOpt = residentManager.getResident(nation.getLeaderUuid());
        String leaderName = leaderOpt.map(TowniaPlayer::getName).orElse("Unknown");

        plugin.getMessageManager().sendMessage(sender, "nation.info",
                "nation", nation.getName(),
                "leader", leaderName,
                "capital", capitalName,
                "towns", String.valueOf(towns.size()),
                "residents", String.valueOf(totalResidents),
                "balance", formatMoney(nation.getBalance()));
    }

    private void handleSpawn(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return;

        Optional<Town> townOpt = townManager.getTown(res.getTownUuid());
        if (townOpt.isEmpty() || !townOpt.get().isInNation()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-in-nation");
            return;
        }

        Nation nation = nationManager.getNation(townOpt.get().getNationUuid()).orElse(null);
        if (nation == null) return;

        if (!nation.hasSpawn()) {
            plugin.getMessageManager().sendMessage(sender, "nation.spawn-not-set");
            return;
        }

        org.bukkit.World world = Bukkit.getWorld(nation.getSpawnWorld());
        if (world == null) {
            plugin.getMessageManager().sendMessage(sender, "nation.spawn-not-set");
            return;
        }

        org.bukkit.Location loc = new org.bukkit.Location(world, nation.getSpawnX(), nation.getSpawnY(), nation.getSpawnZ(), nation.getSpawnYaw(), nation.getSpawnPitch());
        plugin.getMessageManager().sendMessage(sender, "nation.teleporting", "nation", nation.getName());
        player.teleport(loc);
    }

    private void handleSetSpawn(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        TowniaPlayer res = requireNationLeader(sender, player);
        if (res == null) return;

        Optional<Town> leaderTownOpt = townManager.getTown(res.getTownUuid());
        if (leaderTownOpt.isEmpty() || !leaderTownOpt.get().isInNation()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-in-nation");
            return;
        }

        UUID nationUuid = leaderTownOpt.get().getNationUuid();
        Nation nation = nationManager.getNation(nationUuid).orElse(null);
        if (nation == null) return;

        org.bukkit.Location loc = player.getLocation();
        nation.setSpawn(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        nationManager.saveNation(nation);

        plugin.getMessageManager().sendMessage(sender, "nation.spawn-set");
    }

    private void handleList(CommandSender sender) {
        List<Nation> nations = nationManager.getAllNations();
        plugin.getMessageManager().sendMessage(sender, "nation.list-header",
                "count", String.valueOf(nations.size()));
        for (Nation nation : nations) {
            int townCount = nationManager.getTownsInNation(nation.getId()).size();
            Optional<TowniaPlayer> leaderOpt = residentManager.getResident(nation.getLeaderUuid());
            String leaderName = leaderOpt.map(TowniaPlayer::getName).orElse("Unknown");
            plugin.getMessageManager().sendMessage(sender, "nation.list-entry",
                    "nation", nation.getName(),
                    "leader", leaderName,
                    "towns", String.valueOf(townCount));
        }
    }

    private void handleDelete(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        TowniaPlayer res = requireNationLeader(sender, player);
        if (res == null) return;

        Optional<Town> leaderTownOpt = townManager.getTown(res.getTownUuid());
        if (leaderTownOpt.isEmpty() || !leaderTownOpt.get().isInNation()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-in-nation");
            return;
        }
        UUID nationUuid = leaderTownOpt.get().getNationUuid();

        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        try {
            Optional<Nation> nationOpt = nationManager.getNation(nationUuid);
            String nationName = nationOpt.map(Nation::getName).orElse("Unknown");
            nationManager.deleteNation(nationUuid);
            plugin.getMessageManager().sendMessage(sender, "nation.deleted", "nation", nationName);
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void sendHelp(CommandSender sender) {
        plugin.getMessageManager().sendMessage(sender, "nation.help");
    }

    private Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendMessage(sender, "error.player-only");
            return null;
        }
        return player;
    }

    private TowniaPlayer requireInTown(CommandSender sender, Player player) {
        Optional<TowniaPlayer> resOpt = residentManager.getResident(player.getUniqueId());
        if (resOpt.isEmpty() || !resOpt.get().isInTown()) {
            plugin.getMessageManager().sendMessage(sender, "error.not-in-town");
            return null;
        }
        return resOpt.get();
    }

    private TowniaPlayer requireMayorInTown(CommandSender sender, Player player) {
        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return null;
        if (!res.isMayorOrHigher()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
            return null;
        }
        return res;
    }

    private TowniaPlayer requireNationLeader(CommandSender sender, Player player) {
        TowniaPlayer res = requireMayorInTown(sender, player);
        if (res == null) return null;

        Optional<Town> townOpt = townManager.getTown(res.getTownUuid());
        if (townOpt.isEmpty() || !townOpt.get().isInNation()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-in-nation");
            return null;
        }
        Town town = townOpt.get();
        UUID nationUuid = town.getNationUuid();
        Optional<Nation> nationOpt = nationManager.getNation(nationUuid);
        if (nationOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.nation-not-found", "nation", "Unknown");
            return null;
        }
        Nation nation = nationOpt.get();
        if (!nation.getCapitalTownUuid().equals(town.getId())) {
            plugin.getMessageManager().sendMessage(sender, "nation.not-leader");
            return null;
        }
        return res;
    }

    private String formatMoney(double amount) {
        if (plugin.hasEconomy()) return plugin.getEconomy().format(amount);
        return String.format("%.2f", amount);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0],
                    List.of("new", "invite", "join", "leave", "kick", "deposit", "withdraw",
                            "info", "list", "delete"),
                    completions);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "invite", "kick" -> {
                    List<String> townNames = townManager.getAllTowns().stream()
                            .map(Town::getName).toList();
                    StringUtil.copyPartialMatches(args[1], townNames, completions);
                }
                case "join", "info" -> {
                    List<String> nationNames = nationManager.getAllNations().stream()
                            .map(Nation::getName).toList();
                    StringUtil.copyPartialMatches(args[1], nationNames, completions);
                }
                case "delete" -> StringUtil.copyPartialMatches(args[1], List.of("confirm"), completions);
            }
        }
        return completions;
    }


    private void handleOnline(CommandSender sender) {
        plugin.getMessageManager().sendMessage(sender, "error.not-implemented");
    }

    private void handleToggle(CommandSender sender, String[] args) {
        plugin.getMessageManager().sendMessage(sender, "error.not-implemented");
    }
}
