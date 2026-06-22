package net.azisaba.townia.command;

import net.azisaba.townia.Townia;
import net.azisaba.townia.TowniaException;
import net.azisaba.townia.data.Invite;
import net.azisaba.townia.data.Nation;
import net.azisaba.townia.data.Plot;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.data.TowniaPlayer;
import net.azisaba.townia.data.TownRank;
import net.azisaba.townia.database.DatabaseManager;
import net.azisaba.townia.manager.NationManager;
import net.azisaba.townia.manager.PlotManager;
import net.azisaba.townia.manager.ResidentManager;
import net.azisaba.townia.manager.TownManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class TownCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Townia plugin;
    private final TownManager townManager;
    private final ResidentManager residentManager;
    private final PlotManager plotManager;
    private final NationManager nationManager;
    private final DatabaseManager databaseManager;

    public TownCommand(Townia plugin) {
        this.plugin = plugin;
        this.townManager = plugin.getTownManager();
        this.residentManager = plugin.getResidentManager();
        this.plotManager = plugin.getPlotManager();
        this.nationManager = plugin.getNationManager();
        this.databaseManager = plugin.getDatabaseManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            handleInfo(sender, new String[]{"info"});
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "new"      -> handleNew(sender, args);
            case "claim", "outpost"    -> handleClaim(sender);
            case "unclaim"  -> handleUnclaim(sender);
            case "invite"   -> handleInvite(sender, args);
            case "kick"     -> handleKick(sender, args);
            case "leave"    -> handleLeave(sender);
            case "spawn"    -> handleSpawn(sender, args);
            case "set"      -> handleSet(sender, args);
            case "toggle"   -> handleToggle(sender, args);
            case "deposit"  -> handleDeposit(sender, args);
            case "withdraw" -> handleWithdraw(sender, args);
            case "rank"     -> handleRank(sender, args);
            case "info"     -> handleInfo(sender, args);
            case "here"     -> handleHere(sender);
            case "list"     -> handleList(sender);
            case "delete"   -> handleDelete(sender, args);
            case "map"      -> handleMap(sender);
            default         -> handleHelp(sender);
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

        Optional<TowniaPlayer> resOpt = residentManager.getResident(player.getUniqueId());
        if (resOpt.isPresent() && resOpt.get().isInTown()) {
            plugin.getMessageManager().sendMessage(sender, "error.already-in-town");
            return;
        }

        if (!plugin.getTowniaConfig().isWorldAllowed(player.getWorld().getName())) {
            plugin.getMessageManager().sendMessage(sender, "error.wrong-world", "world", player.getWorld().getName());
            return;
        }

        double cost = plugin.getTowniaConfig().getTownCreationCost();
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
            org.bukkit.Chunk chunk = player.getLocation().getChunk();
            if (plugin.getPlotManager().getPlot(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()).isPresent()) {
                throw new TowniaException("town.already-claimed");
            }
            net.azisaba.townia.data.Town town = townManager.createTown(name, player.getUniqueId());
            plugin.getPlotManager().claimChunk(town.getId(), chunk);
            town.setHomeBlock(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
            org.bukkit.Location loc = player.getLocation();
            town.setSpawn(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
            townManager.saveTown(town);
            plugin.getMessageManager().sendMessage(sender, "town.created", "town", name);
        } catch (TowniaException e) {
            if (cost > 0 && plugin.hasEconomy()) {
                plugin.getEconomy().depositPlayer(player, cost);
            }
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleClaim(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (!plugin.getTowniaConfig().isWorldAllowed(player.getWorld().getName())) {
            plugin.getMessageManager().sendMessage(sender, "error.wrong-world", "world", player.getWorld().getName());
            return;
        }

        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return;

        if (!res.isAssistantOrHigher()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-assistant");
            return;
        }

        try {
            plotManager.claimChunk(res.getTownUuid(), player.getLocation().getChunk());
            Optional<net.azisaba.townia.data.Town> townOpt = plugin.getTownManager().getTown(res.getTownUuid());
            plugin.getMessageManager().sendMessage(sender, "town.claimed", "x", String.valueOf(player.getLocation().getChunk().getX()), "z", String.valueOf(player.getLocation().getChunk().getZ()), "town", townOpt.map(net.azisaba.townia.data.Town::getName).orElse("Unknown"));
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());}
    }

    private void handleUnclaim(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return;

        if (!res.isAssistantOrHigher()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-assistant");
            return;
        }

        try {
            plotManager.unclaimChunk(res.getTownUuid(), player.getLocation().getChunk());
            plugin.getMessageManager().sendMessage(sender, "town.unclaimed", "x", String.valueOf(player.getLocation().getChunk().getX()), "z", String.valueOf(player.getLocation().getChunk().getZ()));
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());}
    }

    private void handleInvite(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return;

        if (!res.isAssistantOrHigher()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-assistant");
            return;
        }

        String targetName = args[1];
        Optional<TowniaPlayer> targetOpt = residentManager.getResidentByName(targetName);
        if (targetOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.player-not-found", "player", targetName);
            return;
        }

        TowniaPlayer target = targetOpt.get();
        if (target.isInTown()) {
            plugin.getMessageManager().sendMessage(sender, "error.already-in-town");
            return;
        }

        UUID townUuid = res.getTownUuid();

        try {
            Optional<Invite> existingOpt = databaseManager.getInvite(target.getUuid(), townUuid);
            if (existingOpt.isPresent()) {
                plugin.getMessageManager().sendMessage(sender, "town.invite-sent");
                return;
            }

            Invite invite = new Invite(0, target.getUuid(), townUuid, player.getUniqueId(),
                    System.currentTimeMillis());
            databaseManager.addInvite(invite);

            Optional<Town> townOpt = townManager.getTown(townUuid);
            String townName = townOpt.map(Town::getName).orElse("Unknown");

            plugin.getMessageManager().sendMessage(sender, "town.invite-sent",
                    "player", target.getName(), "town", townName);

            Player targetPlayer = Bukkit.getPlayer(target.getUuid());
            if (targetPlayer != null) {
                plugin.getMessageManager().sendMessage(targetPlayer, "town.invite-received",
                        "town", townName, "inviter", player.getName());
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "DB error handling invite", e);
            plugin.getMessageManager().sendMessage(sender, "error.database");
        }
    }

    private void handleKick(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return;

        if (!res.isMayorOrHigher()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
            return;
        }

        String targetName = args[1];
        Optional<TowniaPlayer> targetOpt = residentManager.getResidentByName(targetName);
        if (targetOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.player-not-found", "player", targetName);
            return;
        }

        TowniaPlayer target = targetOpt.get();

        if (!res.getTownUuid().equals(target.getTownUuid())) {
            plugin.getMessageManager().sendMessage(sender, "error.not-same-town");
            return;
        }

        if (target.isMayor()) {
            plugin.getMessageManager().sendMessage(sender, "error.cannot-kick-mayor");
            return;
        }

        Optional<Town> townOpt = townManager.getTown(res.getTownUuid());
        String townName = townOpt.map(Town::getName).orElse("Unknown");

        residentManager.clearTown(target.getUuid());
        plugin.getMessageManager().sendMessage(sender, "town.kicked",
                "player", target.getName(), "town", townName);

        Player targetPlayer = Bukkit.getPlayer(target.getUuid());
        if (targetPlayer != null) {
            plugin.getMessageManager().sendMessage(targetPlayer, "town.kicked-broadcast",
                    "town", townName, "kicker", player.getName());
        }
    }

    private void handleLeave(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return;

        if (res.isMayor()) {
            List<TowniaPlayer> members = residentManager.getResidentsByTown(res.getTownUuid());
            if (members.size() > 1) {
                plugin.getMessageManager().sendMessage(sender, "town.not-mayor"); // use appropriate key
                return;
            }
        }

        Optional<Town> townOpt = townManager.getTown(res.getTownUuid());
        String townName = townOpt.map(Town::getName).orElse("Unknown");

        residentManager.clearTown(player.getUniqueId());
        plugin.getMessageManager().sendMessage(sender, "town.left", "town", townName);

        List<TowniaPlayer> remaining = residentManager.getResidentsByTown(res.getTownUuid());
        for (TowniaPlayer member : remaining) {
            Player memberPlayer = Bukkit.getPlayer(member.getUuid());
            if (memberPlayer != null) {
                plugin.getMessageManager().sendMessage(memberPlayer, "town.left-broadcast",
                        "player", player.getName(), "town", townName);
            }
        }
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        Town town;
        if (args.length >= 2) {
            Optional<Town> townOpt = townManager.getTownByName(args[1]);
            if (townOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(sender, "error.town-not-found");
                return;
            }
            town = townOpt.get();
            boolean isMember = residentManager.getResident(player.getUniqueId())
                    .map(r -> r.isInTown() && town.getId().equals(r.getTownUuid()))
                    .orElse(false);
            if (!town.isPublic() && !isMember) {
                plugin.getMessageManager().sendMessage(sender, "error.no-permission");
                return;
            }
        } else {
            TowniaPlayer res = requireInTown(sender, player);
            if (res == null) return;
            Optional<Town> townOpt = townManager.getTown(res.getTownUuid());
            if (townOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(sender, "error.town-not-found");
                return;
            }
            town = townOpt.get();
        }

        if (!town.hasSpawn()) {
            plugin.getMessageManager().sendMessage(sender, "town.spawn-not-set"); // no spawn set
            return;
        }

        org.bukkit.World world = Bukkit.getWorld(town.getSpawnWorld());
        if (world == null) {
            plugin.getMessageManager().sendMessage(sender, "error.wrong-world", "world", town.getSpawnWorld());
            return;
        }

        Location loc = new Location(world,
                town.getSpawnX(), town.getSpawnY(), town.getSpawnZ(),
                town.getSpawnYaw(), town.getSpawnPitch());

        plugin.getMessageManager().sendMessage(sender, "town.teleporting", "town", town.getName());
        player.teleport(loc);
    }

    private void handleSet(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (args.length == 0) {
            handleInfo(sender, new String[]{"info"});
            return;
        }

        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return;

        switch (args[1].toLowerCase()) {
            case "spawn" -> {
                if (!res.isAssistantOrHigher()) {
                    plugin.getMessageManager().sendMessage(sender, "town.not-assistant");
                    return;
                }
                org.bukkit.Chunk c = player.getLocation().getChunk();
                Optional<net.azisaba.townia.data.Plot> plotOpt = plugin.getPlotManager().getPlot(c.getWorld().getName(), c.getX(), c.getZ());
                if (plotOpt.isEmpty() || !plotOpt.get().getTownUuid().equals(res.getTownUuid())) {
                    plugin.getMessageManager().sendMessage(sender, "town.not-owned-plot");
                    return;
                }
                try {
                    townManager.setSpawn(res.getTownUuid(), player.getLocation());
                    plugin.getMessageManager().sendMessage(sender, "town.spawn-set");
                } catch (TowniaException e) {
                    plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
                }
            }
            case "homeblock" -> {
                if (!res.isAssistantOrHigher()) {
                    plugin.getMessageManager().sendMessage(sender, "town.not-assistant");
                    return;
                }
                org.bukkit.Chunk c = player.getLocation().getChunk();
                Optional<net.azisaba.townia.data.Plot> plotOpt = plugin.getPlotManager().getPlot(c.getWorld().getName(), c.getX(), c.getZ());
                if (plotOpt.isEmpty() || !plotOpt.get().getTownUuid().equals(res.getTownUuid())) {
                    plugin.getMessageManager().sendMessage(sender, "town.not-owned-plot");
                    return;
                }
                Town town = townManager.getTown(res.getTownUuid()).orElse(null);
                if (town != null) {
                    town.setHomeBlock(c.getWorld().getName(), c.getX(), c.getZ());
                    townManager.saveTown(town);
                    plugin.getMessageManager().sendMessage(sender, "town.homeblock-set");
                }
            }
            case "name" -> {
                if (!res.isMayorOrHigher()) {
                    plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
                    return;
                }
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
                    return;
                }
                String newName = args[2];
                try {
                    String oldName = townManager.getTown(res.getTownUuid()).get().getName();
                    townManager.renameTown(res.getTownUuid(), newName);
            plugin.getMessageManager().sendMessage(sender, "town.renamed", "old", oldName, "new", newName);
                } catch (TowniaException e) {
                    plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());}
            }
            case "public" -> {
                if (!res.isMayorOrHigher()) {
                    plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
                    return;
                }
                try {
                    townManager.setPublic(res.getTownUuid(), true);
                    plugin.getMessageManager().sendMessage(sender, "town.set-public");
                } catch (TowniaException e) {
                    plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());}
            }
            case "taxes" -> {
                if (!res.isMayorOrHigher()) {
                    plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
                    return;
                }
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
                    return;
                }
                double amount;
                try {
                    amount = Double.parseDouble(args[2]);
                    if (amount < 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    plugin.getMessageManager().sendMessage(sender, "error.invalid-amount");
                    return;
                }
                townManager.getTown(res.getTownUuid()).ifPresent(t -> {
                    t.setTaxes(amount);
                    plugin.getMessageManager().sendMessage(sender, "town.taxes-set", "amount", formatMoney(amount));
                });
            }
            case "private" -> {
                if (!res.isMayorOrHigher()) {
                    plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
                    return;
                }
                try {
                    townManager.setPublic(res.getTownUuid(), false);
                    plugin.getMessageManager().sendMessage(sender, "town.set-private");
                } catch (TowniaException e) {
                    plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());}
            }
            case "board" -> {
                if (!res.isMayorOrHigher()) {
                    plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
                    return;
                }
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
                    return;
                }
                String board = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                townManager.getTown(res.getTownUuid()).ifPresent(t -> {
                    t.setBoard(board);
                    plugin.getMessageManager().sendMessage(sender, "town.board-set", "board", board);
                });
            }
            case "plotprice" -> {
                if (!res.isMayorOrHigher()) {
                    plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
                    return;
                }
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
                    return;
                }
                try {
                    double price = Double.parseDouble(args[2]);
                    townManager.getTown(res.getTownUuid()).ifPresent(t -> {
                        t.setPlotPrice(price);
                        plugin.getMessageManager().sendMessage(sender, "town.plotprice-set", "amount", String.valueOf(price));
                    });
                } catch (NumberFormatException e) {
                    plugin.getMessageManager().sendMessage(sender, "error.invalid-amount");
                }
            }
            default -> handleHelp(sender);
        }
    }

    private void handleToggle(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return;

        if (!res.isMayorOrHigher()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
            return;
        }

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        townManager.getTown(res.getTownUuid()).ifPresent(t -> {
            switch (args[1].toLowerCase()) {
                case "pvp" -> {
                    t.setPvp(!t.hasPvp());
                    plugin.getMessageManager().sendMessage(sender, "town.toggled", "setting", "PvP", "state", t.hasPvp() ? "ON" : "OFF");
                }
                case "mobs" -> {
                    t.setMobs(!t.hasMobs());
                    plugin.getMessageManager().sendMessage(sender, "town.toggled", "setting", "Mobs", "state", t.hasMobs() ? "ON" : "OFF");
                }
                case "explosions" -> {
                    t.setExplosions(!t.hasExplosions());
                    plugin.getMessageManager().sendMessage(sender, "town.toggled", "setting", "Explosions", "state", t.hasExplosions() ? "ON" : "OFF");
                }
                case "fire" -> {
                    t.setFire(!t.hasFire());
                    plugin.getMessageManager().sendMessage(sender, "town.toggled", "setting", "Fire", "state", t.hasFire() ? "ON" : "OFF");
                }
                default -> plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            }
        });
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

        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return;

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
            townManager.addBalance(res.getTownUuid(), amount);
            Optional<net.azisaba.townia.data.Town> townOpt = townManager.getTown(res.getTownUuid());
            double newBalance = townOpt.isPresent() ? townOpt.get().getBalance() : 0.0;
            plugin.getMessageManager().sendMessage(sender, "town.deposit-success",
                    "amount", formatMoney(amount), "balance", formatMoney(newBalance));
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

        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return;

        if (!res.isMayorOrHigher()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-amount");
            return;
        }

        try {
            Optional<Town> townOpt = townManager.getTown(res.getTownUuid());
            if (townOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(sender, "error.town-not-found");
                return;
            }

            Town town = townOpt.get();
            if (town.getBalance() < amount) {
                plugin.getMessageManager().sendMessage(sender, "town.withdraw-insufficient",
                        "balance", formatMoney(town.getBalance()),
                        "amount", formatMoney(amount));
                return;
            }

            townManager.subtractBalance(res.getTownUuid(), amount);
            plugin.getEconomy().depositPlayer(player, amount);
            Optional<net.azisaba.townia.data.Town> newTownOpt = townManager.getTown(res.getTownUuid());
            double newBalance = newTownOpt.isPresent() ? newTownOpt.get().getBalance() : 0.0;
            plugin.getMessageManager().sendMessage(sender, "town.withdraw-success",
                    "amount", formatMoney(amount), "balance", formatMoney(newBalance));
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleRank(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (args.length < 3) {
            handleHelp(sender);
            return;
        }

        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return;

        if (!res.isMayorOrHigher()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
            return;
        }

        String subAction = args[1].toLowerCase();
        String targetName = args[2];

        Optional<TowniaPlayer> targetOpt = residentManager.getResidentByName(targetName);
        if (targetOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.player-not-found", "player", args[2]);
            return;
        }
        TowniaPlayer target = targetOpt.get();
        if (!res.getTownUuid().equals(target.getTownUuid())) {
            plugin.getMessageManager().sendMessage(sender, "error.not-same-town");
            return;
        }

        switch (subAction) {
            case "add" -> {
                if (args.length < 4) {
                    handleHelp(sender);
                    return;
                }
                Optional<TownRank> rankOpt = java.util.Arrays.stream(TownRank.values())
                        .filter(r -> r.name().equalsIgnoreCase(args[3]))
                        .findFirst();
                if (rankOpt.isEmpty()) {
                    plugin.getMessageManager().sendMessage(sender, "error.rank-not-found",
                            "{rank}", args[3]);
                    return;
                }
                TownRank rank = rankOpt.get();
                if (rank == TownRank.MAYOR) {
                    plugin.getMessageManager().sendMessage(sender, "error.no-permission");
                    return;
                }
                residentManager.setRank(target.getUuid(), rank);
                plugin.getMessageManager().sendMessage(sender, "town.rank-set",
                        "player", target.getName(), "rank", rank.name());
            }
            case "remove" -> {
                residentManager.setRank(target.getUuid(), TownRank.RESIDENT);
                plugin.getMessageManager().sendMessage(sender, "town.rank-set",
                        "player", target.getName(), "rank", TownRank.RESIDENT.name());
            }
            default -> handleHelp(sender);
        }
    }

    private void handleHere(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        org.bukkit.Chunk chunk = player.getLocation().getChunk();
        Optional<net.azisaba.townia.data.Plot> plotOpt = plugin.getPlotManager().getPlot(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (plotOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "town.chunk-not-claimed");
            return;
        }
        Optional<net.azisaba.townia.data.Town> townOpt = plugin.getTownManager().getTown(plotOpt.get().getTownUuid());
        if (townOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", "Unknown");
            return;
        }
        handleInfo(sender, new String[]{"info", townOpt.get().getName()});
    }

    private void handleInfo(CommandSender sender, String[] args) {
        Town town;
        if (args.length >= 2) {
            Optional<Town> townOpt = townManager.getTownByName(args[1]);
            if (townOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(sender, "error.town-not-found");
                return;
            }
            town = townOpt.get();
        } else {
            Player player = requirePlayer(sender);
            if (player == null) return;
            TowniaPlayer res = requireInTown(sender, player);
            if (res == null) return;
            Optional<Town> townOpt = townManager.getTown(res.getTownUuid());
            if (townOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(sender, "error.town-not-found");
                return;
            }
            town = townOpt.get();
        }

        int residentCount = residentManager.getResidentsByTown(town.getId()).size();
        int claims = plotManager.countPlotsByTown(town.getId());
        int claimLimit = town.getClaimLimit();
        int bonusClaims = town.getBonusClaims();

        Optional<TowniaPlayer> mayorOpt = residentManager.getResident(town.getMayorUuid());
        String mayorName = mayorOpt.map(TowniaPlayer::getName).orElse("Unknown");

        String nationName = "None";
        if (town.isInNation()) {
            Optional<Nation> nationOpt = nationManager.getNation(town.getNationUuid());
            nationName = nationOpt.map(Nation::getName).orElse("None");
        }

        String created = DATE_FMT.format(Instant.ofEpochMilli(town.getCreatedAt()));
        String balance = formatMoney(town.getBalance());
        String upkeep = formatMoney(town.getDailyUpkeep());
        String taxes = formatMoney(town.getTaxes());
        
        List<TowniaPlayer> townResidents = residentManager.getResidentsByTown(town.getId());
        List<String> assistants = new ArrayList<>();
        List<String> submayors = new ArrayList<>();
        List<String> residentNames = new ArrayList<>();
        
        for (TowniaPlayer r : townResidents) {
            residentNames.add(r.getName());
            if (r.getRank() == net.azisaba.townia.data.TownRank.ASSISTANT) assistants.add(r.getName());
            if (r.getRank() == net.azisaba.townia.data.TownRank.CO_MAYOR) submayors.add(r.getName());
        }

        plugin.getMessageManager().sendMessage(sender, "town.info",
                "town", town.getName(),
                "board", town.getBoard() != null ? town.getBoard() : "None",
                "founded", created,
                "claims", String.valueOf(claims),
                "max_claims", String.valueOf(claimLimit),
                "nation_bonus", String.valueOf(bonusClaims),
                "home_x", town.hasHomeBlock() ? String.valueOf(town.getHomeBlockX()) : "N/A",
                "home_z", town.hasHomeBlock() ? String.valueOf(town.getHomeBlockZ()) : "N/A",
                "outposts", String.valueOf(getOutpostCount(town)),
                "perms_build", formatPerm(town, 'B'),
                "perms_destroy", formatPerm(town, 'D'),
                "perms_switch", formatPerm(town, 'S'),
                "perms_item", formatPerm(town, 'I'),
                "explosions", town.hasExplosions() ? "ON" : "OFF",
                "fire", town.hasFire() ? "ON" : "OFF",
                "mobs", town.hasMobs() ? "ON" : "OFF",
                "balance", balance,
                "upkeep", upkeep,
                "taxes", taxes,
                "mayor", mayorName,
                "assistant_count", String.valueOf(assistants.size()),
                "assistants", assistants.isEmpty() ? "None" : String.join(", ", assistants),
                "submayor_count", String.valueOf(submayors.size()),
                "submayors", submayors.isEmpty() ? "None" : String.join(", ", submayors),
                "nation", nationName,
                "residents", String.join(", ", residentNames)
        );
    }
    
    private String formatPerm(Town town, char action) {
        StringBuilder sb = new StringBuilder();
        sb.append((town.getPermsResident() != null && town.getPermsResident().indexOf(action) >= 0) ? "R" : "-");
        sb.append((town.getPermsAlly() != null && town.getPermsAlly().indexOf(action) >= 0) ? "A" : "-");
        sb.append((town.getPermsNation() != null && town.getPermsNation().indexOf(action) >= 0) ? "N" : "-");
        sb.append((town.getPermsOutsider() != null && town.getPermsOutsider().indexOf(action) >= 0) ? "O" : "-");
        return sb.toString();
    }
    
    private int getOutpostCount(Town town) {
        int count = 0;
        try {
            for (net.azisaba.townia.data.Plot plot : plotManager.getPlotsByTown(town.getId())) {
                if (plot.isOutpost()) count++;
            }
        } catch (Exception ignored) {}
        return count;
    }

    private void handleList(CommandSender sender) {
        List<Town> towns = townManager.getAllTowns();
        plugin.getMessageManager().sendMessage(sender, "town.list-header",
                "count", String.valueOf(towns.size()));
        for (Town town : towns) {
            int residents = residentManager.getResidentsByTown(town.getId()).size();
            Optional<TowniaPlayer> mayorOpt = residentManager.getResident(town.getMayorUuid());
            String mayorName = mayorOpt.map(TowniaPlayer::getName).orElse("None");
            plugin.getMessageManager().sendMessage(sender, "town.list-entry",
                    "town", town.getName(),
                    "mayor", mayorName,
                    "residents", String.valueOf(residents));
        }
    }

    private void handleDelete(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        TowniaPlayer res = requireInTown(sender, player);
        if (res == null) return;

        if (!res.isMayor()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
            return;
        }

        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        UUID townUuid = res.getTownUuid();
        try {
            Optional<Town> townOpt = townManager.getTown(townUuid);
            String townName = townOpt.map(Town::getName).orElse("Unknown");

            List<TowniaPlayer> members = residentManager.getResidentsByTown(townUuid);
            for (TowniaPlayer member : members) {
                residentManager.clearTown(member.getUuid());
            }

            databaseManager.deleteInvitesByTown(townUuid);

            townManager.deleteTown(townUuid);

            plugin.getMessageManager().sendMessage(sender, "town.deleted", "town", townName);
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "DB error deleting invites", e);
            plugin.getMessageManager().sendMessage(sender, "error.database");
        }
    }

    private void handleHelp(CommandSender sender) {
        plugin.getMessageManager().sendMessage(sender, "town.help");
    }

    private void handleMap(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        org.bukkit.Chunk center = player.getLocation().getChunk();
        int cx = center.getX();
        int cz = center.getZ();
        String worldName = center.getWorld().getName();

        net.azisaba.townia.data.TowniaPlayer resOpt = residentManager.getResident(player.getUniqueId()).orElse(null);
        java.util.UUID playerTownUuid = resOpt != null ? resOpt.getTownUuid() : null;

        player.sendMessage("§8================ §6Townia Map §8================");
        for (int z = cz - 5; z <= cz + 5; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = cx - 15; x <= cx + 15; x++) {
                net.azisaba.townia.data.Plot plot = plugin.getPlotManager().getPlot(worldName, x, z).orElse(null);
                String symbol = (plot == null) ? "-" : "+";

                if (x == cx && z == cz) {
                    row.append("§e").append(symbol);
                } else if (plot == null) {
                    row.append("§7").append(symbol);
                } else if (playerTownUuid != null && plot.getTownUuid().equals(playerTownUuid)) {
                    row.append("§a").append(symbol);
                } else {
                    row.append("§c").append(symbol);
                }
            }
            player.sendMessage(row.toString());
        }
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

    private String formatMoney(double amount) {
        if (plugin.hasEconomy()) return plugin.getEconomy().format(amount);
        return String.format("%.2f", amount);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0],
                    List.of("new", "claim", "unclaim", "invite", "kick", "leave",
                            "spawn", "set", "deposit", "withdraw", "rank", "info", "list", "delete"),
                    completions);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "spawn", "info" -> {
                    List<String> townNames = townManager.getAllTowns().stream()
                            .map(Town::getName).toList();
                    StringUtil.copyPartialMatches(args[1], townNames, completions);
                }
                case "invite", "kick" -> {
                    List<String> players = new ArrayList<>();
                    for (Player p : plugin.getServer().getOnlinePlayers()) players.add(p.getName());
                    StringUtil.copyPartialMatches(args[1], players, completions);
                }
                case "set" -> StringUtil.copyPartialMatches(args[1],
                        List.of("spawn", "name", "public", "private"), completions);
                case "rank" -> StringUtil.copyPartialMatches(args[1],
                        List.of("add", "remove"), completions);
                case "delete" -> StringUtil.copyPartialMatches(args[1], List.of("confirm"), completions);
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("rank")) {
                List<String> players = new ArrayList<>();
                for (Player p : plugin.getServer().getOnlinePlayers()) players.add(p.getName());
                StringUtil.copyPartialMatches(args[2], players, completions);
            }
        } else if (args.length == 4) {
            if (args[0].equalsIgnoreCase("rank") && args[1].equalsIgnoreCase("add")) {
                List<String> ranks = new ArrayList<>();
                for (TownRank rank : TownRank.values()) {
                    if (rank != TownRank.MAYOR) ranks.add(rank.name().toLowerCase());
                }
                StringUtil.copyPartialMatches(args[3], ranks, completions);
            }
        }
        return completions;
    }
}

