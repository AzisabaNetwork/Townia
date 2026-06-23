package net.azisaba.townia.command;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import net.azisaba.townia.Townia;
import net.azisaba.townia.TowniaException;
import net.azisaba.townia.data.Invite;
import net.azisaba.townia.data.Nation;
import net.azisaba.townia.data.PermissionMatrix;
import net.azisaba.townia.data.Plot;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.data.TownRank;
import net.azisaba.townia.data.TowniaOutpost;
import net.azisaba.townia.data.TowniaPlayer;
import net.azisaba.townia.database.DatabaseManager;
import net.azisaba.townia.manager.NationManager;
import net.azisaba.townia.manager.PlotManager;
import net.azisaba.townia.manager.ResidentManager;
import net.azisaba.townia.manager.TownManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

public class TownCommand
implements CommandExecutor,
TabCompleter {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
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

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            this.handleInfo(sender, new String[]{"info"});
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "new": {
                this.handleNew(sender, args);
                break;
            }
            case "claim": {
                this.handleClaim(sender, args);
                break;
            }
            case "outpost": {
                this.handleOutpost(sender, args);
                break;
            }
            case "unclaim": {
                this.handleUnclaim(sender, args);
                break;
            }
            case "invite": 
            case "add": {
                this.handleInvite(sender, args);
                break;
            }
            case "kick": {
                this.handleKick(sender, args);
                break;
            }
            case "leave": {
                this.handleLeave(sender);
                break;
            }
            case "join": {
                this.handleJoin(sender, args);
                break;
            }
            case "reslist": {
                this.handleResList(sender, args);
                break;
            }
            case "spawn": {
                this.handleSpawn(sender, args);
                break;
            }
            case "set": {
                this.handleSet(sender, args);
                break;
            }
            case "toggle": {
                this.handleToggle(sender, args);
                break;
            }
            case "deposit": {
                this.handleDeposit(sender, args);
                break;
            }
            case "withdraw": {
                this.handleWithdraw(sender, args);
                break;
            }
            case "rank": {
                this.handleRank(sender, args);
                break;
            }
            case "info": {
                this.handleInfo(sender, args);
                break;
            }
            case "here": {
                this.handleHere(sender);
                break;
            }
            case "list": {
                this.handleList(sender);
                break;
            }
            case "delete": {
                this.handleDelete(sender, args);
                break;
            }
            case "map": {
                this.handleMap(sender);
                break;
            }
            case "?": 
            case "help": {
                this.handleHelp(sender);
                break;
            }
            default: {
                this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
                this.handleHelp(sender);
            }
        }
        return true;
    }

    private void handleNew(CommandSender sender, String[] args) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
            return;
        }
        Optional<TowniaPlayer> resOpt = this.residentManager.getResident(player.getUniqueId());
        if (resOpt.isPresent() && resOpt.get().isInTown()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.already-in-town", new String[0]);
            return;
        }
        if (!this.plugin.getTowniaConfig().isWorldAllowed(player.getWorld().getName())) {
            this.plugin.getMessageManager().sendMessage(sender, "error.wrong-world", "world", player.getWorld().getName());
            return;
        }
        double cost = this.plugin.getTowniaConfig().getTownCreationCost();
        if (cost > 0.0) {
            if (!this.plugin.hasEconomy()) {
                this.plugin.getMessageManager().sendMessage(sender, "error.no-vault", new String[0]);
                return;
            }
            Economy eco = this.plugin.getEconomy();
            if (!eco.has((OfflinePlayer)player, cost)) {
                this.plugin.getMessageManager().sendMessage(sender, "error.insufficient-funds", "amount", this.formatMoney(cost));
                return;
            }
            eco.withdrawPlayer((OfflinePlayer)player, cost);
        }
        String name = args[1];
        try {
            Chunk chunk = player.getLocation().getChunk();
            if (this.plugin.getPlotManager().getPlot(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()).isPresent()) {
                throw new TowniaException("town.already-claimed", new String[0]);
            }
            Town town = this.townManager.createTown(name, player.getUniqueId());
            this.plugin.getPlotManager().claimChunk(town.getId(), chunk);
            town.setHomeBlock(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
            Location loc = player.getLocation();
            town.setSpawn(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
            this.townManager.saveTown(town);
            this.plugin.getMessageManager().sendMessage(sender, "town.created", "town", name);
        }
        catch (TowniaException e) {
            if (cost > 0.0 && this.plugin.hasEconomy()) {
                this.plugin.getEconomy().depositPlayer((OfflinePlayer)player, cost);
            }
            this.plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleClaim(CommandSender sender, String[] args) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (!this.plugin.getTowniaConfig().isWorldAllowed(player.getWorld().getName())) {
            this.plugin.getMessageManager().sendMessage(sender, "error.wrong-world", "world", player.getWorld().getName());
            return;
        }
        TowniaPlayer res = this.requireInTown(sender, player);
        if (res == null) {
            return;
        }
        if (!res.isAssistantOrHigher()) {
            this.plugin.getMessageManager().sendMessage(sender, "town.not-assistant", new String[0]);
            return;
        }
        boolean isOutpost = args.length > 1 && args[1].equalsIgnoreCase("outpost");
        try {
            if (isOutpost) {
                this.plotManager.claimChunk(res.getTownUuid(), player.getLocation().getChunk());
                Optional<Plot> plotOpt = this.plotManager.getPlot(player.getLocation().getChunk());
                plotOpt.ifPresent(p -> {
                    try {
                        p.setOutpost(true);
                        this.plugin.getDatabaseManager().savePlot((Plot)p);
                    }
                    catch (SQLException e) {
                        this.plugin.getLogger().log(Level.SEVERE, "Failed to save outpost", e);
                    }
                });
                Optional<Town> townOpt = this.plugin.getTownManager().getTown(res.getTownUuid());
                townOpt.ifPresent(t -> {
                    t.getOutposts().add(new TowniaOutpost(0, player.getWorld().getName(), player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ(), player.getLocation().getYaw(), player.getLocation().getPitch(), false));
                    this.plugin.getTownManager().saveTown((Town)t);
                    this.plugin.getMessageManager().sendMessage(sender, "town.outpost-claimed", "x", String.valueOf(player.getLocation().getChunk().getX()), "z", String.valueOf(player.getLocation().getChunk().getZ()), "town", t.getName());
                });
            } else {
                this.plotManager.claimChunk(res.getTownUuid(), player.getLocation().getChunk());
                Optional<Town> townOpt = this.plugin.getTownManager().getTown(res.getTownUuid());
                this.plugin.getMessageManager().sendMessage(sender, "town.claimed", "x", String.valueOf(player.getLocation().getChunk().getX()), "z", String.valueOf(player.getLocation().getChunk().getZ()), "town", townOpt.map(Town::getName).orElse("Unknown"));
            }
        }
        catch (TowniaException e) {
            this.plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleUnclaim(CommandSender sender, String[] args) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        TowniaPlayer res = this.requireInTown(sender, player);
        if (res == null) {
            return;
        }
        if (!res.isAssistantOrHigher()) {
            this.plugin.getMessageManager().sendMessage(sender, "town.not-assistant", new String[0]);
            return;
        }
        try {
            if (args.length > 1 && args[1].equalsIgnoreCase("all")) {
                List<Plot> plots = this.plotManager.getPlotsByTown(res.getTownUuid());
                int count = 0;
                for (Plot plot : plots) {
                    World world = this.plugin.getServer().getWorld(plot.getWorldName());
                    if (world == null) continue;
                    this.plotManager.unclaimChunk(res.getTownUuid(), world.getChunkAt(plot.getChunkX(), plot.getChunkZ()));
                    ++count;
                }
                this.plugin.getMessageManager().sendMessage(sender, "town.unclaimed-all", "count", String.valueOf(count));
                return;
            }
            Chunk chunk = player.getLocation().getChunk();
            this.plotManager.unclaimChunk(res.getTownUuid(), chunk);
            this.plugin.getMessageManager().sendMessage(sender, "town.unclaimed", "x", String.valueOf(chunk.getX()), "z", String.valueOf(chunk.getZ()));
        }
        catch (TowniaException e) {
            this.plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleOutpost(CommandSender sender, String[] args) {
        Town targetTown;
        int outpostIndex;
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
            return;
        }
        TowniaPlayer res = this.requireInTown(sender, player);
        if (res == null) {
            return;
        }
        try {
            if (args.length >= 3) {
                String townName = args[1];
                outpostIndex = Integer.parseInt(args[2]);
                targetTown = this.plugin.getTownManager().getTownByName(townName).orElse(null);
                if (targetTown == null) {
                    this.plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", townName);
                    return;
                }
            } else {
                outpostIndex = Integer.parseInt(args[1]);
                targetTown = this.plugin.getTownManager().getTown(res.getTownUuid()).orElse(null);
                if (targetTown == null) {
                    return;
                }
            }
        }
        catch (NumberFormatException e) {
            this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
            return;
        }
        if (outpostIndex < 1 || outpostIndex > targetTown.getOutposts().size()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.outpost-not-found", "index", String.valueOf(outpostIndex));
            return;
        }
        TowniaOutpost outpost = targetTown.getOutposts().get(outpostIndex - 1);
        if (!targetTown.getId().equals(res.getTownUuid()) && !outpost.isPublic()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.outpost-not-public", new String[0]);
            return;
        }
        World world = this.plugin.getServer().getWorld(outpost.world());
        if (world == null) {
            return;
        }
        Location loc = new Location(world, outpost.x(), outpost.y(), outpost.z(), outpost.yaw(), outpost.pitch());
        player.teleport(loc);
        this.plugin.getMessageManager().sendMessage(sender, "town.teleport-outpost", "index", String.valueOf(outpostIndex), "town", targetTown.getName());
    }

    private void handleJoin(CommandSender sender, String[] args) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        TowniaPlayer res = this.plugin.getResidentManager().getResident(player.getUniqueId()).orElse(null);
        if (res == null) {
            return;
        }
        if (res.isInTown()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.already-in-town", new String[0]);
            return;
        }
        if (args.length < 2) {
            this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
            return;
        }
        String townName = args[1];
        Optional<Town> townOpt = this.plugin.getTownManager().getTownByName(townName);
        if (townOpt.isEmpty()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", townName);
            return;
        }
        Town town = townOpt.get();
        if (!town.isOpen()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.town-not-open", new String[0]);
            return;
        }
        res.setTownUuid(town.getId());
        this.plugin.getResidentManager().saveResident(res);
        this.plugin.getMessageManager().sendMessage(sender, "town.joined", "town", town.getName());
    }

    private void handleResList(CommandSender sender, String[] args) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        Town targetTown = null;
        if (args.length >= 2) {
            targetTown = this.plugin.getTownManager().getTownByName(args[1]).orElse(null);
            if (targetTown == null) {
                this.plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", args[1]);
                return;
            }
        } else {
            TowniaPlayer res = this.requireInTown(sender, player);
            if (res != null) {
                targetTown = this.plugin.getTownManager().getTown(res.getTownUuid()).orElse(null);
            }
        }
        if (targetTown == null) {
            return;
        }
        List<TowniaPlayer> residents = this.plugin.getResidentManager().getResidentsByTown(targetTown.getId());
        ArrayList<String> onlineNames = new ArrayList<String>();
        ArrayList<String> offlineNames = new ArrayList<String>();
        for (TowniaPlayer r : residents) {
            Player p = this.plugin.getServer().getPlayer(r.getUuid());
            if (p != null && p.isOnline()) {
                onlineNames.add((("<green>" + r.getName() + "</green>")));
                continue;
            }
            offlineNames.add((("<gray>" + r.getName() + "</gray>")));
        }
        this.plugin.getMessageManager().sendMessage(sender, "town.reslist", "town", targetTown.getName(), "count", String.valueOf(residents.size()));
        if (!onlineNames.isEmpty()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(("Online: " + String.join((CharSequence)", ", onlineNames))));
        }
        if (!offlineNames.isEmpty()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(("Offline: " + String.join((CharSequence)", ", offlineNames))));
        }
    }

    private void handleInvite(CommandSender sender, String[] args) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
            return;
        }
        TowniaPlayer res = this.requireInTown(sender, player);
        if (res == null) {
            return;
        }
        if (!res.isAssistantOrHigher()) {
            this.plugin.getMessageManager().sendMessage(sender, "town.not-assistant", new String[0]);
            return;
        }
        String targetName = args[1];
        Optional<TowniaPlayer> targetOpt = this.residentManager.getResidentByName(targetName);
        if (targetOpt.isEmpty()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.player-not-found", "player", targetName);
            return;
        }
        TowniaPlayer target = targetOpt.get();
        if (target.isInTown()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.already-in-town", new String[0]);
            return;
        }
        UUID townUuid = res.getTownUuid();
        try {
            Optional<Invite> existingOpt = this.databaseManager.getInvite(target.getUuid(), townUuid);
            if (existingOpt.isPresent()) {
                this.plugin.getMessageManager().sendMessage(sender, "town.invite-sent", "player", "Unknown", "town", "Unknown");
                return;
            }
            Invite invite = new Invite(0, target.getUuid(), townUuid, player.getUniqueId(), System.currentTimeMillis());
            this.databaseManager.addInvite(invite);
            Optional<Town> townOpt = this.townManager.getTown(townUuid);
            String townName = townOpt.map(Town::getName).orElse("Unknown");
            this.plugin.getMessageManager().sendMessage(sender, "town.invite-sent", "player", target.getName(), "town", townName);
            Player targetPlayer = Bukkit.getPlayer((UUID)target.getUuid());
            if (targetPlayer != null) {
                this.plugin.getMessageManager().sendMessage((CommandSender)targetPlayer, "town.invite-received", "town", townName, "inviter", player.getName());
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "DB error handling invite", e);
            this.plugin.getMessageManager().sendMessage(sender, "error.database", new String[0]);
        }
    }

    private void handleKick(CommandSender sender, String[] args) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
            return;
        }
        TowniaPlayer res = this.requireInTown(sender, player);
        if (res == null) {
            return;
        }
        if (!res.isMayorOrHigher()) {
            this.plugin.getMessageManager().sendMessage(sender, "town.not-mayor", new String[0]);
            return;
        }
        String targetName = args[1];
        Optional<TowniaPlayer> targetOpt = this.residentManager.getResidentByName(targetName);
        if (targetOpt.isEmpty()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.player-not-found", "player", targetName);
            return;
        }
        TowniaPlayer target = targetOpt.get();
        if (!res.getTownUuid().equals(target.getTownUuid())) {
            this.plugin.getMessageManager().sendMessage(sender, "error.not-same-town", new String[0]);
            return;
        }
        if (target.isMayor()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.cannot-kick-mayor", new String[0]);
            return;
        }
        Optional<Town> townOpt = this.townManager.getTown(res.getTownUuid());
        String townName = townOpt.map(Town::getName).orElse("Unknown");
        this.residentManager.clearTown(target.getUuid());
        this.plugin.getMessageManager().sendMessage(sender, "town.kicked", "player", target.getName(), "town", townName);
        Player targetPlayer = Bukkit.getPlayer((UUID)target.getUuid());
        if (targetPlayer != null) {
            this.plugin.getMessageManager().sendMessage((CommandSender)targetPlayer, "town.kicked-broadcast", "town", townName, "kicker", player.getName());
        }
    }

    private void handleLeave(CommandSender sender) {
        List<TowniaPlayer> members;
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        TowniaPlayer res = this.requireInTown(sender, player);
        if (res == null) {
            return;
        }
        if (res.isMayor() && (members = this.residentManager.getResidentsByTown(res.getTownUuid())).size() > 1) {
            this.plugin.getMessageManager().sendMessage(sender, "town.not-mayor", new String[0]);
            return;
        }
        Optional<Town> townOpt = this.townManager.getTown(res.getTownUuid());
        String townName = townOpt.map(Town::getName).orElse("Unknown");
        this.residentManager.clearTown(player.getUniqueId());
        this.plugin.getMessageManager().sendMessage(sender, "town.left", "town", townName);
        List<TowniaPlayer> remaining = this.residentManager.getResidentsByTown(res.getTownUuid());
        for (TowniaPlayer member : remaining) {
            Player memberPlayer = Bukkit.getPlayer((UUID)member.getUuid());
            if (memberPlayer == null) continue;
            this.plugin.getMessageManager().sendMessage((CommandSender)memberPlayer, "town.left-broadcast", "player", player.getName(), "town", townName);
        }
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        Town town;
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length >= 2) {
            Optional<Town> townOpt = this.townManager.getTownByName(args[1]);
            if (townOpt.isEmpty()) {
                this.plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", "Unknown");
                return;
            }
            town = townOpt.get();
            boolean isMember = this.residentManager.getResident(player.getUniqueId()).map(r -> r.isInTown() && town.getId().equals(r.getTownUuid())).orElse(false);
            if (!town.isPublic() && !isMember && !player.hasPermission("townia.admin")) {
                this.plugin.getMessageManager().sendMessage((CommandSender)sender, "error.no-permission");
                return;
            }
        } else {
            TowniaPlayer res = this.requireInTown(sender, player);
            if (res == null) {
                return;
            }
            Optional<Town> townOpt = this.townManager.getTown(res.getTownUuid());
            if (townOpt.isEmpty()) {
                this.plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", "Unknown");
                return;
            }
            town = townOpt.get();
        }
        if (!town.hasSpawn()) {
            this.plugin.getMessageManager().sendMessage(sender, "town.spawn-not-set", new String[0]);
            return;
        }
        World world = Bukkit.getWorld(town.getSpawnWorld());
        if (world == null) {
            this.plugin.getMessageManager().sendMessage(sender, "error.wrong-world", "world", town.getSpawnWorld());
            return;
        }
        Location loc = new Location(world, town.getSpawnX(), town.getSpawnY(), town.getSpawnZ(), town.getSpawnYaw(), town.getSpawnPitch());
        this.plugin.getMessageManager().sendMessage(sender, "town.teleporting", "town", town.getName());
        player.teleport(loc);
    }

    private void handleSet(CommandSender sender, String[] args) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length == 0) {
            this.handleInfo(sender, new String[]{"info"});
            return;
        }
        TowniaPlayer res = this.requireInTown(sender, player);
        if (res == null) {
            return;
        }
        switch (args[1].toLowerCase()) {
            case "spawn": {
                if (!res.isAssistantOrHigher()) {
                    this.plugin.getMessageManager().sendMessage(sender, "town.not-assistant", new String[0]);
                    return;
                }
                Chunk c = player.getLocation().getChunk();
                Optional<Plot> plotOpt = this.plugin.getPlotManager().getPlot(c.getWorld().getName(), c.getX(), c.getZ());
                if (plotOpt.isEmpty() || !plotOpt.get().getTownUuid().equals(res.getTownUuid())) {
                    this.plugin.getMessageManager().sendMessage(sender, "town.not-owned-plot", new String[0]);
                    return;
                }
                try {
                    this.townManager.setSpawn(res.getTownUuid(), player.getLocation());
                    this.plugin.getMessageManager().sendMessage(sender, "town.spawn-set", new String[0]);
                }
                catch (TowniaException e) {
                    this.plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
                }
                break;
            }
            case "homeblock": {
                if (!res.isAssistantOrHigher()) {
                    this.plugin.getMessageManager().sendMessage(sender, "town.not-assistant", new String[0]);
                    return;
                }
                Chunk c = player.getLocation().getChunk();
                Optional<Plot> plotOpt = this.plugin.getPlotManager().getPlot(c.getWorld().getName(), c.getX(), c.getZ());
                if (plotOpt.isEmpty() || !plotOpt.get().getTownUuid().equals(res.getTownUuid())) {
                    this.plugin.getMessageManager().sendMessage(sender, "town.not-owned-plot", new String[0]);
                    return;
                }
                Town town = this.townManager.getTown(res.getTownUuid()).orElse(null);
                if (town == null) break;
                town.setHomeBlock(c.getWorld().getName(), c.getX(), c.getZ());
                this.townManager.saveTown(town);
                this.plugin.getMessageManager().sendMessage(sender, "town.homeblock-set", new String[0]);
                break;
            }
            case "name": {
                if (!res.isMayorOrHigher()) {
                    this.plugin.getMessageManager().sendMessage(sender, "town.not-mayor", new String[0]);
                    return;
                }
                if (args.length < 3) {
                    this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
                    return;
                }
                String newName = args[2];
                try {
                    String oldName = this.townManager.getTown(res.getTownUuid()).get().getName();
                    this.townManager.renameTown(res.getTownUuid(), newName);
                    this.plugin.getMessageManager().sendMessage(sender, "town.renamed", "old", oldName, "new", newName);
                }
                catch (TowniaException e) {
                    this.plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
                }
                break;
            }
            case "perm": {
                boolean state;
                Town t2 = this.townManager.getTown(res.getTownUuid()).orElse(null);
                if (t2 == null) {
                    return;
                }
                if (args.length < 3) {
                    this.plugin.getMessageManager().sendMessage(sender, "town.info-perms", "resident", t2.getPermsResident(), "ally", t2.getPermsAlly(), "outsider", t2.getPermsOutsider(), "nation", t2.getPermsNation());
                    return;
                }
                if (!res.isAssistantOrHigher()) {
                    this.plugin.getMessageManager().sendMessage(sender, "town.not-assistant", new String[0]);
                    return;
                }
                String groupStr = null;
                String actionStr = null;
                String stateStr = null;
                if (args.length == 3) {
                    stateStr = args[2];
                } else if (args.length == 4) {
                    groupStr = args[2];
                    stateStr = args[3];
                } else if (args.length >= 5) {
                    groupStr = args[2];
                    actionStr = args[3];
                    stateStr = args[4];
                }
                boolean bl = state = stateStr.equalsIgnoreCase("on") || stateStr.equalsIgnoreCase("true");
                if (groupStr == null) {
                    String p = state ? "BDSI" : "";
                    t2.setPermsResident(p);
                    t2.setPermsAlly(p);
                    t2.setPermsOutsider(p);
                    t2.setPermsNation(p);
                } else {
                    ArrayList<String> groups = new ArrayList<String>();
                    if (groupStr.equalsIgnoreCase("resident")) {
                        groups.add("resident");
                    } else if (groupStr.equalsIgnoreCase("ally") || groupStr.equalsIgnoreCase("town")) {
                        groups.add("ally");
                    } else if (groupStr.equalsIgnoreCase("outsider")) {
                        groups.add("outsider");
                    } else if (groupStr.equalsIgnoreCase("nation")) {
                        groups.add("nation");
                    } else {
                        this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
                        return;
                    }
                    ArrayList<Character> actions = new ArrayList<Character>();
                    if (actionStr == null) {
                        actions.addAll(Arrays.asList(Character.valueOf('B'), Character.valueOf('D'), Character.valueOf('S'), Character.valueOf('I')));
                    } else if (actionStr.equalsIgnoreCase("build")) {
                        actions.add(Character.valueOf('B'));
                    } else if (actionStr.equalsIgnoreCase("destroy")) {
                        actions.add(Character.valueOf('D'));
                    } else if (actionStr.equalsIgnoreCase("switch")) {
                        actions.add(Character.valueOf('S'));
                    } else if (actionStr.equalsIgnoreCase("item") || actionStr.equalsIgnoreCase("itemuse")) {
                        actions.add(Character.valueOf('I'));
                    } else {
                        this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
                        return;
                    }
                    for (String group : groups) {
                        String current = "";
                        if (group.equals("resident")) {
                            current = t2.getPermsResident();
                        } else if (group.equals("ally")) {
                            current = t2.getPermsAlly();
                        } else if (group.equals("outsider")) {
                            current = t2.getPermsOutsider();
                        } else if (group.equals("nation")) {
                            current = t2.getPermsNation();
                        }
                        Iterator iterator = actions.iterator();
                        while (iterator.hasNext()) {
                            char action = ((Character)iterator.next()).charValue();
                            current = PermissionMatrix.setPerm(current, action, state);
                        }
                        if (group.equals("resident")) {
                            t2.setPermsResident(current);
                            continue;
                        }
                        if (group.equals("ally")) {
                            t2.setPermsAlly(current);
                            continue;
                        }
                        if (group.equals("outsider")) {
                            t2.setPermsOutsider(current);
                            continue;
                        }
                        if (!group.equals("nation")) continue;
                        t2.setPermsNation(current);
                    }
                }
                this.plugin.getTownManager().saveTown(t2);
                this.plugin.getMessageManager().sendMessage(sender, "town.perm-set", new String[0]);
                break;
            }
            case "public": {
                if (!res.isMayorOrHigher()) {
                    this.plugin.getMessageManager().sendMessage(sender, "town.not-mayor", new String[0]);
                    return;
                }
                try {
                    this.townManager.setPublic(res.getTownUuid(), true);
                    this.plugin.getMessageManager().sendMessage(sender, "town.set-public", new String[0]);
                }
                catch (TowniaException e) {
                    this.plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
                }
                break;
            }
            case "taxes": {
                double amount;
                if (!res.isMayorOrHigher()) {
                    this.plugin.getMessageManager().sendMessage(sender, "town.not-mayor", new String[0]);
                    return;
                }
                if (args.length < 3) {
                    this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
                    return;
                }
                try {
                    amount = Double.parseDouble(args[2]);
                    if (amount < 0.0) {
                        throw new NumberFormatException();
                    }
                }
                catch (NumberFormatException e) {
                    this.plugin.getMessageManager().sendMessage(sender, "error.invalid-amount", new String[0]);
                    return;
                }
                this.townManager.getTown(res.getTownUuid()).ifPresent(t -> {
                    t.setTaxes(amount);
                    this.plugin.getMessageManager().sendMessage(sender, "town.taxes-set", "amount", this.formatMoney(amount));
                });
                break;
            }
            case "private": {
                if (!res.isMayorOrHigher()) {
                    this.plugin.getMessageManager().sendMessage(sender, "town.not-mayor", new String[0]);
                    return;
                }
                try {
                    this.townManager.setPublic(res.getTownUuid(), false);
                    this.plugin.getMessageManager().sendMessage(sender, "town.set-private", new String[0]);
                }
                catch (TowniaException e) {
                    this.plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
                }
                break;
            }
            case "board": {
                if (!res.isMayorOrHigher()) {
                    this.plugin.getMessageManager().sendMessage(sender, "town.not-mayor", new String[0]);
                    return;
                }
                if (args.length < 3) {
                    this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
                    return;
                }
                String board = String.join((CharSequence)" ", Arrays.copyOfRange(args, 2, args.length));
                this.townManager.getTown(res.getTownUuid()).ifPresent(t -> {
                    t.setBoard(board);
                    this.plugin.getMessageManager().sendMessage(sender, "town.board-set", "board", board);
                });
                break;
            }
            case "plotprice": {
                if (!res.isMayorOrHigher()) {
                    this.plugin.getMessageManager().sendMessage(sender, "town.not-mayor", new String[0]);
                    return;
                }
                if (args.length < 3) {
                    this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
                    return;
                }
                try {
                    double price = Double.parseDouble(args[2]);
                    this.townManager.getTown(res.getTownUuid()).ifPresent(t -> {
                        t.setPlotPrice(price);
                        this.plugin.getMessageManager().sendMessage(sender, "town.plotprice-set", "amount", String.valueOf(price));
                    });
                }
                catch (NumberFormatException e) {
                    this.plugin.getMessageManager().sendMessage(sender, "error.invalid-amount", new String[0]);
                }
                break;
            }
            default: {
                this.handleHelp(sender);
            }
        }
    }

    private void handleToggle(CommandSender sender, String[] args) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        TowniaPlayer res = this.requireInTown(sender, player);
        if (res == null) {
            return;
        }
        if (!res.isMayorOrHigher()) {
            this.plugin.getMessageManager().sendMessage(sender, "town.not-mayor", new String[0]);
            return;
        }
        if (args.length < 2) {
            this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
            return;
        }
        this.townManager.getTown(res.getTownUuid()).ifPresent(t -> {
            switch (args[1].toLowerCase()) {
                case "pvp": {
                    t.setPvp(!t.hasPvp());
                    this.plugin.getMessageManager().sendMessage(sender, "town.toggled", "setting", "PvP", "state", t.hasPvp() ? "ON" : "OFF");
                    break;
                }
                case "mobs": {
                    t.setMobs(!t.hasMobs());
                    this.plugin.getMessageManager().sendMessage(sender, "town.toggled", "setting", "Mobs", "state", t.hasMobs() ? "ON" : "OFF");
                    break;
                }
                case "explosions": {
                    t.setExplosions(!t.hasExplosions());
                    this.plugin.getMessageManager().sendMessage(sender, "town.toggled", "setting", "Explosions", "state", t.hasExplosions() ? "ON" : "OFF");
                    break;
                }
                case "fire": {
                    t.setFire(!t.hasFire());
                    this.plugin.getMessageManager().sendMessage(sender, "town.toggled", "setting", "Fire", "state", t.hasFire() ? "ON" : "OFF");
                    break;
                }
                case "open": {
                    t.setOpen(!t.isOpen());
                    this.plugin.getMessageManager().sendMessage(sender, "town.toggled", "setting", "Open", "state", t.isOpen() ? "ON" : "OFF");
                    break;
                }
                case "public": {
                    t.setPublic(!t.isPublic());
                    this.plugin.getMessageManager().sendMessage(sender, "town.toggled", "setting", "Public", "state", t.isPublic() ? "ON" : "OFF");
                    break;
                }
                case "taxpercent": {
                    t.setTaxPercent(!t.isTaxPercent());
                    this.plugin.getMessageManager().sendMessage(sender, "town.toggled", "setting", "TaxPercent", "state", t.isTaxPercent() ? "ON" : "OFF");
                    break;
                }
                default: {
                    this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
                }
            }
        });
    }

    private void handleDeposit(CommandSender sender, String[] args) {
        double amount;
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (!this.plugin.hasEconomy()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.no-vault", new String[0]);
            return;
        }
        if (args.length < 2) {
            this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
            return;
        }
        TowniaPlayer res = this.requireInTown(sender, player);
        if (res == null) {
            return;
        }
        try {
            amount = Double.parseDouble(args[1]);
            if (amount <= 0.0) {
                throw new NumberFormatException();
            }
        }
        catch (NumberFormatException e) {
            this.plugin.getMessageManager().sendMessage(sender, "error.invalid-amount", new String[0]);
            return;
        }
        Economy eco = this.plugin.getEconomy();
        if (!eco.has((OfflinePlayer)player, amount)) {
            this.plugin.getMessageManager().sendMessage(sender, "error.insufficient-funds", "amount", this.formatMoney(amount));
            return;
        }
        try {
            EconomyResponse resp = eco.withdrawPlayer((OfflinePlayer)player, amount);
            if (!resp.transactionSuccess()) {
                this.plugin.getMessageManager().sendMessage(sender, "error.insufficient-funds", "amount", this.formatMoney(amount));
                return;
            }
            this.townManager.addBalance(res.getTownUuid(), amount);
            Optional<Town> townOpt = this.townManager.getTown(res.getTownUuid());
            double newBalance = townOpt.isPresent() ? townOpt.get().getBalance() : 0.0;
            this.plugin.getMessageManager().sendMessage(sender, "town.deposit-success", "amount", this.formatMoney(amount), "balance", this.formatMoney(newBalance));
        }
        catch (TowniaException e) {
            this.plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleWithdraw(CommandSender sender, String[] args) {
        double amount;
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (!this.plugin.hasEconomy()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.no-vault", new String[0]);
            return;
        }
        if (args.length < 2) {
            this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
            return;
        }
        TowniaPlayer res = this.requireInTown(sender, player);
        if (res == null) {
            return;
        }
        if (!res.isMayorOrHigher()) {
            this.plugin.getMessageManager().sendMessage(sender, "town.not-mayor", new String[0]);
            return;
        }
        try {
            amount = Double.parseDouble(args[1]);
            if (amount <= 0.0) {
                throw new NumberFormatException();
            }
        }
        catch (NumberFormatException e) {
            this.plugin.getMessageManager().sendMessage(sender, "error.invalid-amount", new String[0]);
            return;
        }
        try {
            Optional<Town> townOpt = this.townManager.getTown(res.getTownUuid());
            if (townOpt.isEmpty()) {
                this.plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", "Unknown");
                return;
            }
            Town town = townOpt.get();
            if (town.getBalance() < amount) {
                this.plugin.getMessageManager().sendMessage(sender, "town.withdraw-insufficient", "balance", this.formatMoney(town.getBalance()), "amount", this.formatMoney(amount));
                return;
            }
            this.townManager.subtractBalance(res.getTownUuid(), amount);
            this.plugin.getEconomy().depositPlayer((OfflinePlayer)player, amount);
            Optional<Town> newTownOpt = this.townManager.getTown(res.getTownUuid());
            double newBalance = newTownOpt.isPresent() ? newTownOpt.get().getBalance() : 0.0;
            this.plugin.getMessageManager().sendMessage(sender, "town.withdraw-success", "amount", this.formatMoney(amount), "balance", this.formatMoney(newBalance));
        }
        catch (TowniaException e) {
            this.plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleRank(CommandSender sender, String[] args) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 3) {
            this.handleHelp(sender);
            return;
        }
        TowniaPlayer res = this.requireInTown(sender, player);
        if (res == null) {
            return;
        }
        if (!res.isMayorOrHigher()) {
            this.plugin.getMessageManager().sendMessage(sender, "town.not-mayor", new String[0]);
            return;
        }
        String subAction = args[1].toLowerCase();
        String targetName = args[2];
        Optional<TowniaPlayer> targetOpt = this.residentManager.getResidentByName(targetName);
        if (targetOpt.isEmpty()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.player-not-found", "player", args[2]);
            return;
        }
        TowniaPlayer target = targetOpt.get();
        if (!res.getTownUuid().equals(target.getTownUuid())) {
            this.plugin.getMessageManager().sendMessage(sender, "error.not-same-town", new String[0]);
            return;
        }
        switch (subAction) {
            case "add": {
                if (args.length < 4) {
                    this.handleHelp(sender);
                    return;
                }
                Optional<TownRank> rankOpt = Arrays.stream(TownRank.values()).filter(r -> r.name().equalsIgnoreCase(args[3])).findFirst();
                if (rankOpt.isEmpty()) {
                    this.plugin.getMessageManager().sendMessage(sender, "error.rank-not-found", "{rank}", args[3]);
                    return;
                }
                TownRank rank = rankOpt.get();
                if (rank == TownRank.MAYOR) {
                    this.plugin.getMessageManager().sendMessage(sender, "error.no-permission", new String[0]);
                    return;
                }
                this.residentManager.setRank(target.getUuid(), rank);
                this.plugin.getMessageManager().sendMessage(sender, "town.rank-set", "player", target.getName(), "rank", rank.name());
                break;
            }
            case "remove": {
                this.residentManager.setRank(target.getUuid(), TownRank.RESIDENT);
                this.plugin.getMessageManager().sendMessage(sender, "town.rank-set", "player", target.getName(), "rank", TownRank.RESIDENT.name());
                break;
            }
            default: {
                this.handleHelp(sender);
            }
        }
    }

    private void handleHere(CommandSender sender) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        Chunk chunk = player.getLocation().getChunk();
        Optional<Plot> plotOpt = this.plugin.getPlotManager().getPlot(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (plotOpt.isEmpty()) {
            this.plugin.getMessageManager().sendMessage(sender, "town.chunk-not-claimed", new String[0]);
            return;
        }
        Optional<Town> townOpt = this.plugin.getTownManager().getTown(plotOpt.get().getTownUuid());
        if (townOpt.isEmpty()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", "Unknown");
            return;
        }
        this.handleInfo(sender, new String[]{"info", townOpt.get().getName()});
    }

    private void handleInfo(CommandSender sender, String[] args) {
        Town town;
        if (args.length >= 2) {
            Optional<Town> townOpt = this.townManager.getTownByName(args[1]);
            if (townOpt.isEmpty()) {
                this.plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", "Unknown");
                return;
            }
            town = townOpt.get();
        } else {
            Player player = this.requirePlayer(sender);
            if (player == null) {
                return;
            }
            TowniaPlayer res = this.requireInTown(sender, player);
            if (res == null) {
                return;
            }
            Optional<Town> townOpt = this.townManager.getTown(res.getTownUuid());
            if (townOpt.isEmpty()) {
                this.plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", "Unknown");
                return;
            }
            town = townOpt.get();
        }
        int residentCount = this.residentManager.getResidentsByTown(town.getId()).size();
        int claims = this.plotManager.countPlotsByTown(town.getId());
        int claimLimit = town.getClaimLimit();
        int bonusClaims = town.getBonusClaims();
        Optional<TowniaPlayer> mayorOpt = this.residentManager.getResident(town.getMayorUuid());
        String mayorName = mayorOpt.map(TowniaPlayer::getName).orElse("Unknown");
        String nationName = "None";
        if (town.isInNation()) {
            Optional<Nation> nationOpt = this.nationManager.getNation(town.getNationUuid());
            nationName = nationOpt.map(Nation::getName).orElse("None");
        }
        String created = DATE_FMT.format(Instant.ofEpochMilli(town.getCreatedAt()));
        String balance = this.formatMoney(town.getBalance());
        String upkeep = this.formatMoney(town.getDailyUpkeep());
        String taxes = this.formatMoney(town.getTaxes());
        List<TowniaPlayer> townResidents = this.residentManager.getResidentsByTown(town.getId());
        ArrayList<String> assistants = new ArrayList<String>();
        ArrayList<String> submayors = new ArrayList<String>();
        ArrayList<String> residentNames = new ArrayList<String>();
        for (TowniaPlayer r : townResidents) {
            residentNames.add(r.getName());
            if (r.getRank() == TownRank.ASSISTANT) {
                assistants.add(r.getName());
            }
            if (r.getRank() != TownRank.CO_MAYOR) continue;
            submayors.add(r.getName());
        }
        this.plugin.getMessageManager().sendMessage(sender, "town.info", "town", town.getName(), "board", town.getBoard() != null ? town.getBoard() : "None", "founded", created, "claims", String.valueOf(claims), "max_claims", String.valueOf(claimLimit), "nation_bonus", String.valueOf(bonusClaims), "home_x", town.hasHomeBlock() ? String.valueOf(town.getHomeBlockX()) : "N/A", "home_z", town.hasHomeBlock() ? String.valueOf(town.getHomeBlockZ()) : "N/A", "outposts", String.valueOf(this.getOutpostCount(town)), "perms_build", this.formatPerm(town, 'B'), "perms_destroy", this.formatPerm(town, 'D'), "perms_switch", this.formatPerm(town, 'S'), "perms_item", this.formatPerm(town, 'I'), "explosions", town.hasExplosions() ? "ON" : "OFF", "fire", town.hasFire() ? "ON" : "OFF", "mobs", town.hasMobs() ? "ON" : "OFF", "balance", balance, "upkeep", upkeep, "taxes", taxes, "mayor", mayorName, "assistant_count", String.valueOf(assistants.size()), "assistants", assistants.isEmpty() ? "None" : String.join((CharSequence)", ", assistants), "submayor_count", String.valueOf(submayors.size()), "submayors", submayors.isEmpty() ? "None" : String.join((CharSequence)", ", submayors), "nation", nationName, "residents", String.join((CharSequence)", ", residentNames));
    }

    private String formatPerm(Town town, char action) {
        StringBuilder sb = new StringBuilder();
        sb.append(town.getPermsResident() != null && town.getPermsResident().indexOf(action) >= 0 ? "R" : "-");
        sb.append(town.getPermsAlly() != null && town.getPermsAlly().indexOf(action) >= 0 ? "A" : "-");
        sb.append(town.getPermsNation() != null && town.getPermsNation().indexOf(action) >= 0 ? "N" : "-");
        sb.append(town.getPermsOutsider() != null && town.getPermsOutsider().indexOf(action) >= 0 ? "O" : "-");
        return sb.toString();
    }

    private int getOutpostCount(Town town) {
        int count = 0;
        try {
            for (Plot plot : this.plotManager.getPlotsByTown(town.getId())) {
                if (!plot.isOutpost()) continue;
                ++count;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return count;
    }

    private void handleList(CommandSender sender) {
        List<Town> towns = this.townManager.getAllTowns();
        this.plugin.getMessageManager().sendMessage(sender, "town.list-header", "count", String.valueOf(towns.size()));
        for (Town town : towns) {
            int residents = this.residentManager.getResidentsByTown(town.getId()).size();
            Optional<TowniaPlayer> mayorOpt = this.residentManager.getResident(town.getMayorUuid());
            String mayorName = mayorOpt.map(TowniaPlayer::getName).orElse("None");
            this.plugin.getMessageManager().sendMessage(sender, "town.list-entry", "town", town.getName(), "mayor", mayorName, "residents", String.valueOf(residents));
        }
    }

    private void handleDelete(CommandSender sender, String[] args) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        TowniaPlayer res = this.requireInTown(sender, player);
        if (res == null) {
            return;
        }
        if (!res.isMayor()) {
            this.plugin.getMessageManager().sendMessage(sender, "town.not-mayor", new String[0]);
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            this.plugin.getMessageManager().sendMessage(sender, "error.invalid-args", new String[0]);
            return;
        }
        UUID townUuid = res.getTownUuid();
        try {
            Optional<Town> townOpt = this.townManager.getTown(townUuid);
            String townName = townOpt.map(Town::getName).orElse("Unknown");
            List<TowniaPlayer> members = this.residentManager.getResidentsByTown(townUuid);
            for (TowniaPlayer member : members) {
                this.residentManager.clearTown(member.getUuid());
            }
            this.databaseManager.deleteInvitesByTown(townUuid);
            this.townManager.deleteTown(townUuid);
            this.plugin.getMessageManager().sendMessage(sender, "town.deleted", "town", townName);
        }
        catch (TowniaException e) {
            this.plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
        catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "DB error deleting invites", e);
            this.plugin.getMessageManager().sendMessage(sender, "error.database", new String[0]);
        }
    }

    private void handleHelp(CommandSender sender) {
        this.plugin.getMessageManager().sendMessage(sender, "town.help", new String[0]);
    }

    private void handleMap(CommandSender sender) {
        Player player = this.requirePlayer(sender);
        if (player == null) {
            return;
        }
        Chunk center = player.getLocation().getChunk();
        int cx = center.getX();
        int cz = center.getZ();
        String worldName = center.getWorld().getName();
        TowniaPlayer resOpt = this.residentManager.getResident(player.getUniqueId()).orElse(null);
        UUID playerTownUuid = resOpt != null ? resOpt.getTownUuid() : null;
        player.sendMessage("\u00a78================ \u00a76Townia Map \u00a78================");
        for (int z = cz - 5; z <= cz + 5; ++z) {
            StringBuilder row = new StringBuilder();
            for (int x = cx - 15; x <= cx + 15; ++x) {
                String symbol;
                Plot plot = this.plugin.getPlotManager().getPlot(worldName, x, z).orElse(null);
                String string = symbol = plot == null ? "-" : "+";
                if (x == cx && z == cz) {
                    row.append("\u00a7e").append(symbol);
                    continue;
                }
                if (plot == null) {
                    row.append("\u00a77").append(symbol);
                    continue;
                }
                if (playerTownUuid != null && plot.getTownUuid().equals(playerTownUuid)) {
                    row.append("\u00a7a").append(symbol);
                    continue;
                }
                row.append("\u00a7c").append(symbol);
            }
            player.sendMessage(row.toString());
        }
    }

    private Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player)) {
            this.plugin.getMessageManager().sendMessage(sender, "error.player-only", new String[0]);
            return null;
        }
        Player player = (Player)sender;
        return player;
    }

    private TowniaPlayer requireInTown(CommandSender sender, Player player) {
        Optional<TowniaPlayer> resOpt = this.residentManager.getResident(player.getUniqueId());
        if (resOpt.isEmpty() || !resOpt.get().isInTown()) {
            this.plugin.getMessageManager().sendMessage(sender, "error.not-in-town", new String[0]);
            return null;
        }
        return resOpt.get();
    }

    private String formatMoney(double amount) {
        if (this.plugin.hasEconomy()) {
            return this.plugin.getEconomy().format(amount);
        }
        return String.format("%.2f", amount);
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        ArrayList<String> completions = new ArrayList<String>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("new", "claim", "unclaim", "invite", "kick", "leave", "spawn", "set", "deposit", "withdraw", "rank", "info", "list", "delete", "toggle"), completions);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "spawn": 
                case "info": {
                    List<String> townNames = this.townManager.getAllTowns().stream().map(Town::getName).toList();
                    StringUtil.copyPartialMatches(args[1], townNames, completions);
                    break;
                }
                case "invite": 
                case "kick": {
                    ArrayList<String> players = new ArrayList<String>();
                    for (Player p : this.plugin.getServer().getOnlinePlayers()) {
                        players.add(p.getName());
                    }
                    StringUtil.copyPartialMatches(args[1], players, completions);
                    break;
                }
                case "set": {
                    StringUtil.copyPartialMatches(args[1], List.of("spawn", "name", "public", "private", "board", "taxes", "plotprice", "perm"), completions);
                    break;
                }
                case "toggle": {
                    StringUtil.copyPartialMatches(args[1], List.of("pvp", "mobs", "explosions", "fire", "open", "public", "taxpercent"), completions);
                    break;
                }
                case "rank": {
                    StringUtil.copyPartialMatches(args[1], List.of("add", "remove"), completions);
                    break;
                }
                case "delete": {
                    StringUtil.copyPartialMatches(args[1], List.of("confirm"), completions);
                }
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("rank")) {
                ArrayList<String> players = new ArrayList<String>();
                for (Player p : this.plugin.getServer().getOnlinePlayers()) {
                    players.add(p.getName());
                }
                StringUtil.copyPartialMatches(args[2], players, completions);
            } else if (args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("perm")) {
                StringUtil.copyPartialMatches(args[2], List.of("resident", "ally", "outsider", "nation", "build", "destroy", "switch", "itemuse", "on", "off"), completions);
            }
        } else if (args.length == 4) {
            if (args[0].equalsIgnoreCase("rank") && args[1].equalsIgnoreCase("add")) {
                ArrayList<String> ranks = new ArrayList<String>();
                for (TownRank rank : TownRank.values()) {
                    if (rank == TownRank.MAYOR) continue;
                    ranks.add(rank.name().toLowerCase());
                }
                StringUtil.copyPartialMatches(args[3], ranks, completions);
            } else if (args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("perm") && !args[2].equalsIgnoreCase("on") && !args[2].equalsIgnoreCase("off")) {
                StringUtil.copyPartialMatches(args[3], List.of("build", "destroy", "switch", "itemuse", "on", "off"), completions);
            }
        } else if (args.length == 5 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("perm") && !args[3].equalsIgnoreCase("on") && !args[3].equalsIgnoreCase("off")) {
            StringUtil.copyPartialMatches(args[4], List.of("on", "off"), completions);
        }
        return completions;
    }
}
