package net.azisaba.townia.command;

import net.azisaba.townia.Townia;
import net.azisaba.townia.data.Nation;
import net.azisaba.townia.data.Town;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TowniaCommand implements CommandExecutor, TabCompleter {

    private final Townia plugin;

    public TowniaCommand(Townia plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!sender.hasPermission("townia.admin.reload")) {
                    plugin.getMessageManager().sendMessage(sender, "error.no-permission");
                    return true;
                }
                plugin.getTowniaConfig().reload();
                plugin.getMessageManager().loadAllMessages();
                plugin.getMessageManager().sendMessage(sender, "admin.reloaded");
            }
            case "info" -> {
                String version = plugin.getDescription().getVersion();
                String authors = String.join(", ", plugin.getDescription().getAuthors());
                plugin.getMessageManager().sendMessage(sender, "admin.help",
                        "version", version,
                        "authors", authors);
            }
            case "map" -> {
                Player player = requirePlayer(sender);
                if (player != null) {
                    sendMap(player);
                }
            }
            case "price" -> handlePrice(sender);
            case "time"  -> handleTime(sender);
            case "top"   -> handleTop(sender, args);
            case "?"     -> sendHelp(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        plugin.getMessageManager().sendMessage(sender, "townia.help");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("map", "price", "time", "top", "?", "info"));
            if (sender.hasPermission("townia.admin.reload")) options.add("reload");
            StringUtil.copyPartialMatches(args[0], options, completions);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            StringUtil.copyPartialMatches(args[1], List.of("residents", "land"), completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("top")) {
            StringUtil.copyPartialMatches(args[2], List.of("all", "town", "nation", "resident"), completions);
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

    private void handlePrice(CommandSender sender) {
        double townCreate  = plugin.getTowniaConfig().getTownCreationCost();
        double claimCost   = plugin.getTowniaConfig().getClaimCost();
        double nationCreate = plugin.getTowniaConfig().getNationCreationCost();
        plugin.getMessageManager().sendMessage(sender, "townia.price",
                "town_create",  formatMoney(townCreate),
                "claim",        formatMoney(claimCost),
                "nation_create", formatMoney(nationCreate));
    }

    private void handleTime(CommandSender sender) {
        long now   = System.currentTimeMillis();
        long nextMs = plugin.getNextUpkeepTime();
        long diffMs = Math.max(0, nextMs - now);
        long hours  = diffMs / 3600000;
        long mins   = (diffMs % 3600000) / 60000;
        plugin.getMessageManager().sendMessage(sender, "townia.time",
                "hours", String.valueOf(hours), "minutes", String.valueOf(mins));
    }

    private void handleTop(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }
        String category = args[1].toLowerCase();
        String scope    = args[2].toLowerCase();

        switch (category) {
            case "residents" -> handleTopResidents(sender, scope);
            case "land"      -> handleTopLand(sender, scope);
            default          -> plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
        }
    }

    private void handleTopResidents(CommandSender sender, String scope) {
        List<Town>   towns   = plugin.getTownManager().getAllTowns();
        List<Nation> nations = plugin.getNationManager().getAllNations();

        switch (scope) {
            case "all" -> {
                plugin.getMessageManager().sendMessage(sender, "townia.top-header", "type", "住民数 (全体)");
                towns.stream()
                        .sorted(Comparator.comparingInt(
                                t -> -plugin.getResidentManager().getResidentsByTown(t.getId()).size()))
                        .limit(10)
                        .forEach(t -> {
                            int cnt = plugin.getResidentManager().getResidentsByTown(t.getId()).size();
                            plugin.getMessageManager().sendMessage(sender, "townia.top-entry-town",
                                    "name", t.getName(), "count", String.valueOf(cnt));
                        });
            }
            case "town" -> {
                plugin.getMessageManager().sendMessage(sender, "townia.top-header", "type", "住民数 (町)");
                towns.stream()
                        .sorted(Comparator.comparingInt(
                                t -> -plugin.getResidentManager().getResidentsByTown(t.getId()).size()))
                        .limit(10)
                        .forEach(t -> {
                            int cnt = plugin.getResidentManager().getResidentsByTown(t.getId()).size();
                            plugin.getMessageManager().sendMessage(sender, "townia.top-entry-town",
                                    "name", t.getName(), "count", String.valueOf(cnt));
                        });
            }
            case "nation" -> {
                plugin.getMessageManager().sendMessage(sender, "townia.top-header", "type", "住民数 (国)");
                nations.stream()
                        .sorted(Comparator.comparingInt(n -> {
                            int total = 0;
                            for (Town t : plugin.getTownManager().getTownsByNation(n.getId())) {
                                total += plugin.getResidentManager().getResidentsByTown(t.getId()).size();
                            }
                            return -total;
                        }))
                        .limit(10)
                        .forEach(n -> {
                            int total = 0;
                            for (Town t : plugin.getTownManager().getTownsByNation(n.getId())) {
                                total += plugin.getResidentManager().getResidentsByTown(t.getId()).size();
                            }
                            plugin.getMessageManager().sendMessage(sender, "townia.top-entry-nation",
                                    "name", n.getName(), "count", String.valueOf(total));
                        });
            }
            default -> plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
        }
    }

    private void handleTopLand(CommandSender sender, String scope) {
        List<Town>   towns   = plugin.getTownManager().getAllTowns();
        List<Nation> nations = plugin.getNationManager().getAllNations();

        switch (scope) {
            case "all", "town" -> {
                String label = scope.equals("all") ? "土地の所有数 (全体)" : "土地の所有数 (町)";
                plugin.getMessageManager().sendMessage(sender, "townia.top-header", "type", label);
                towns.stream()
                        .sorted(Comparator.comparingInt(
                                t -> -plugin.getPlotManager().countPlotsByTown(t.getId())))
                        .limit(10)
                        .forEach(t -> {
                            int cnt = plugin.getPlotManager().countPlotsByTown(t.getId());
                            plugin.getMessageManager().sendMessage(sender, "townia.top-entry-town",
                                    "name", t.getName(), "count", String.valueOf(cnt));
                        });
            }
            case "nation" -> {
                plugin.getMessageManager().sendMessage(sender, "townia.top-header", "type", "土地の所有数 (国)");
                nations.stream()
                        .sorted(Comparator.comparingInt(n -> {
                            int total = 0;
                            for (Town t : plugin.getTownManager().getTownsByNation(n.getId())) {
                                total += plugin.getPlotManager().countPlotsByTown(t.getId());
                            }
                            return -total;
                        }))
                        .limit(10)
                        .forEach(n -> {
                            int total = 0;
                            for (Town t : plugin.getTownManager().getTownsByNation(n.getId())) {
                                total += plugin.getPlotManager().countPlotsByTown(t.getId());
                            }
                            plugin.getMessageManager().sendMessage(sender, "townia.top-entry-nation",
                                    "name", n.getName(), "count", String.valueOf(total));
                        });
            }
            case "resident" -> {
                plugin.getMessageManager().sendMessage(sender, "townia.top-header", "type", "土地の所有数 (住人)");
                plugin.getResidentManager().getAllResidents().stream()
                        .sorted(Comparator.comparingInt(r ->
                                -plugin.getPlotManager().countPlotsByOwner(r.getUuid())))
                        .limit(10)
                        .forEach(r -> {
                            int cnt = plugin.getPlotManager().countPlotsByOwner(r.getUuid());
                            plugin.getMessageManager().sendMessage(sender, "townia.top-entry-resident",
                                    "name", r.getName(), "count", String.valueOf(cnt));
                        });
            }
            default -> plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
        }
    }

    private String formatMoney(double amount) {
        if (plugin.hasEconomy()) return plugin.getEconomy().format(amount);
        return String.format("%.2f", amount);
    }



    private void sendMap(Player player) {
        org.bukkit.Chunk center = player.getLocation().getChunk();
        int cx = center.getX();
        int cz = center.getZ();
        String worldName = center.getWorld().getName();

        net.azisaba.townia.data.TowniaPlayer resOpt = plugin.getResidentManager().getResident(player.getUniqueId()).orElse(null);
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
}
