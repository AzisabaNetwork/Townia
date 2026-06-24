package net.azisaba.townia.command;

import net.azisaba.townia.Townia;
import net.azisaba.townia.data.Nation;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.data.TowniaPlayer;
import net.azisaba.townia.manager.NationManager;
import net.azisaba.townia.manager.ResidentManager;
import net.azisaba.townia.manager.TownManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ResidentCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Townia plugin;
    private final ResidentManager residentManager;
    private final TownManager townManager;
    private final NationManager nationManager;

    public ResidentCommand(Townia plugin) {
        this.plugin = plugin;
        this.residentManager = plugin.getResidentManager();
        this.townManager = plugin.getTownManager();
        this.nationManager = plugin.getNationManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            Player player = requirePlayer(sender);
            if (player == null) return true;
            showResidentInfo(sender, player.getUniqueId().toString(), player.getName());
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> showList(sender);
            case "set" -> {
                if (args.length >= 3 && args[1].equalsIgnoreCase("mode") && args[2].equalsIgnoreCase("map")) {
                    if (sender instanceof Player p) {
                        p.performCommand("townia map");
                    } else {
                        plugin.getMessageManager().sendMessage(sender, "error.player-only");
                    }
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("perm")) {
                    handleSetPerm(sender, args);
                } else {
                    plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
                }
            }
            case "toggle" -> handleToggle(sender, args);
            case "spawn" -> handleSpawn(sender, args);
            case "tax" -> handleTax(sender, args);
            case "friend" -> handleFriend(sender, args);
            case "?", "help" -> plugin.getMessageManager().sendMessage(sender, "townia.help");
            default -> {
                String targetName = args[0];
                Optional<TowniaPlayer> targetOpt = residentManager.getResidentByName(targetName);
                if (targetOpt.isEmpty()) {
                    plugin.getMessageManager().sendMessage(sender, "error.player-not-found", "player", targetName);
                    return true;
                }
                TowniaPlayer target = targetOpt.get();
                showResidentInfo(sender, target.getUuid().toString(), target.getName());
            }
        }
        return true;
    }

    private void handleFriend(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(sender, "resident.friend.help");
            return;
        }

        TowniaPlayer res = residentManager.getOrCreate(player);

        switch (args[1].toLowerCase()) {
            case "add" -> {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
                    return;
                }
                Optional<TowniaPlayer> targetOpt = residentManager.getResidentByName(args[2]);
                if (targetOpt.isEmpty()) {
                    plugin.getMessageManager().sendMessage(sender, "error.player-not-found", "player", args[2]);
                    return;
                }
                if (targetOpt.get().getUuid().equals(player.getUniqueId())) {
                    plugin.getMessageManager().sendMessage(sender, "resident.friend.cannot-add-self");
                    return;
                }
                TowniaPlayer target = targetOpt.get();
                if (res.getFriends().contains(target.getUuid().toString())) {
                    plugin.getMessageManager().sendMessage(sender, "resident.friend.already-friend", "player", target.getName());
                    return;
                }
                residentManager.addFriend(res, target);
                plugin.getMessageManager().sendMessage(sender, "resident.friend.added", "player", target.getName());
            }
            case "remove" -> {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
                    return;
                }
                Optional<TowniaPlayer> targetOpt = residentManager.getResidentByName(args[2]);
                if (targetOpt.isEmpty()) {
                    plugin.getMessageManager().sendMessage(sender, "error.player-not-found", "player", args[2]);
                    return;
                }
                TowniaPlayer target = targetOpt.get();
                if (!res.getFriends().contains(target.getUuid().toString())) {
                    plugin.getMessageManager().sendMessage(sender, "resident.friend.not-friend", "player", target.getName());
                    return;
                }
                residentManager.removeFriend(res, target);
                plugin.getMessageManager().sendMessage(sender, "resident.friend.removed", "player", target.getName());
            }
            case "list" -> {
                if (res.getFriends().isEmpty()) {
                    plugin.getMessageManager().sendMessage(sender, "resident.friend.list-empty");
                    return;
                }
                List<String> friendNames = new ArrayList<>();
                for (String friendUuidStr : res.getFriends()) {
                    residentManager.getResident(java.util.UUID.fromString(friendUuidStr)).ifPresent(f -> friendNames.add(f.getName()));
                }
                plugin.getMessageManager().sendMessage(sender, "resident.friend.list", "friends", String.join(", ", friendNames));
            }
            case "clear" -> {
                List<String> friendsCopy = new ArrayList<>(res.getFriends());
                for (String friendUuidStr : friendsCopy) {
                    residentManager.getResident(java.util.UUID.fromString(friendUuidStr)).ifPresent(f -> residentManager.removeFriend(res, f));
                }
                plugin.getMessageManager().sendMessage(sender, "resident.friend.cleared");
            }
            default -> plugin.getMessageManager().sendMessage(sender, "resident.friend.help");
        }
    }

    private void showResidentInfo(CommandSender sender, String uuidStr, String name) {
        java.util.UUID uuid = java.util.UUID.fromString(uuidStr);
        Optional<TowniaPlayer> resOpt = residentManager.getResident(uuid);
        if (resOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.player-not-found", "player", uuid.toString());
            return;
        }

        TowniaPlayer res = resOpt.get();

        String townName = "None";
        String rankName = "None";
        String nationName = "None";

        if (res.isInTown()) {
            Optional<Town> townOpt = townManager.getTown(res.getTownUuid());
            if (townOpt.isPresent()) {
                Town town = townOpt.get();
                townName = town.getName();
                rankName = res.getRank() != null ? res.getRank().name() : "RESIDENT";

                if (town.isInNation()) {
                    Optional<Nation> nationOpt = nationManager.getNation(town.getNationUuid());
                    nationName = nationOpt.map(Nation::getName).orElse("None");
                }
            }
        }

        String lastSeen = DATE_FMT.format(Instant.ofEpochMilli(res.getLastSeen()));
        String friends = res.getFriends().isEmpty() ? "None" : String.valueOf(res.getFriends().size());

        String balance = "0";
        if (plugin.hasEconomy()) {
            org.bukkit.OfflinePlayer offlinePlayer = org.bukkit.Bukkit.getOfflinePlayer(uuid);
            balance = String.format("%.2f", plugin.getEconomy().getBalance(offlinePlayer));
        }

        plugin.getMessageManager().sendMessage(sender, "resident.info",
                "player", res.getName(),
                "town", townName,
                "rank", rankName,
                "nation", nationName,
                "last_seen", lastSeen,
                "balance", balance,
                "friends", friends);
    }

    private void showList(CommandSender sender) {
        List<TowniaPlayer> all = residentManager.getAllResidents();
        plugin.getMessageManager().sendMessage(sender, "resident.list-header",
                "count", String.valueOf(all.size()));
        for (TowniaPlayer res : all) {
            String townName = "None";
            if (res.isInTown()) {
                Optional<Town> townOpt = townManager.getTown(res.getTownUuid());
                townName = townOpt.map(Town::getName).orElse("None");
            }
            plugin.getMessageManager().sendMessage(sender, "resident.list-entry",
                    "player", res.getName(),
                    "town", townName);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("list");
            options.add("friend");
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                options.add(p.getName());
            }
            StringUtil.copyPartialMatches(args[0], options, completions);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("friend")) {
            List<String> options = List.of("add", "remove", "list", "clear");
            StringUtil.copyPartialMatches(args[1], options, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("friend") && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            List<String> options = new ArrayList<>();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                options.add(p.getName());
            }
            StringUtil.copyPartialMatches(args[2], options, completions);
        }
        return completions;
    }

    private Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendMessage(sender, "error.player-only");
            return null;
        }
        return player;
    }


    private void handleSetPerm(CommandSender sender, String[] args) {
        plugin.getMessageManager().sendMessage(sender, "error.not-implemented");
    }

    private void handleToggle(CommandSender sender, String[] args) {
        plugin.getMessageManager().sendMessage(sender, "error.not-implemented");
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        plugin.getMessageManager().sendMessage(sender, "error.not-implemented");
    }

    private void handleTax(CommandSender sender, String[] args) {
        plugin.getMessageManager().sendMessage(sender, "error.not-implemented");
    }
}
