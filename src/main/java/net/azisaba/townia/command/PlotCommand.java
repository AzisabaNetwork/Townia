package net.azisaba.townia.command;

import net.azisaba.townia.Townia;
import net.azisaba.townia.TowniaException;
import net.azisaba.townia.data.Plot;
import net.azisaba.townia.data.PlotType;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.data.TowniaPlayer;
import net.azisaba.townia.manager.PlotManager;
import net.azisaba.townia.manager.ResidentManager;
import net.azisaba.townia.manager.TownManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PlotCommand implements CommandExecutor, TabCompleter {

    private final Townia plugin;
    private final PlotManager plotManager;
    private final ResidentManager residentManager;
    private final TownManager townManager;

    public PlotCommand(Townia plugin) {
        this.plugin = plugin;
        this.plotManager = plugin.getPlotManager();
        this.residentManager = plugin.getResidentManager();
        this.townManager = plugin.getTownManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return true;

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "info", "perm" -> handleInfo(player);
            case "set" -> {
                if (args.length < 2) {
                    sendHelp(player);
                    return true;
                }
                if (args[1].equalsIgnoreCase("type") && args.length >= 3) {
                    handleSetType(player, args[2]);
                } else if (args[1].equalsIgnoreCase("name") && args.length >= 3) {
                    handleSetName(player, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                } else if (args[1].equalsIgnoreCase("reset")) {
                    handleSetReset(player);
                } else if (args[1].equalsIgnoreCase("perm")) {
                    if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
                        handleSetReset(player);
                    } else {
                        handleSetPerm(player, Arrays.copyOfRange(args, 2, args.length));
                    }
                } else {
                    sendHelp(player);
                }
            }
            case "toggle" -> handleToggle(player, args);
            case "forsale", "fs" -> {
                if (args.length < 2) {
                    sendHelp(player);
                    return true;
                }
                handleForSale(player, args[1]);
            }
            case "notforsale", "nfs" -> handleNotForSale(player);
            case "buy", "claim" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("auto")) {
                    handleClaimAuto(player);
                } else {
                    handleBuy(player);
                }
            }
            case "clear" -> handleClear(player);
            case "evict" -> handleEvict(player);
            case "?", "help" -> sendHelp(player);
            default -> sendHelp(player);
        }
        return true;
    }

    private void handleInfo(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        Optional<Plot> plotOpt = plotManager.getPlot(chunk);
        if (plotOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(player, "plot.no-plot-here");
            return;
        }
        Plot plot = plotOpt.get();
        Optional<Town> townOpt = townManager.getTown(plot.getTownUuid());
        String townName = townOpt.map(Town::getName).orElse("Unknown");
        String owner = plot.isTownOwned() ? townName : resolveOwnerName(plot.getOwnerUuid());
        String forSale = plot.isForSale() ? "Yes (" + formatMoney(plot.getPrice()) + ")" : "No";
        plugin.getMessageManager().sendMessage(player, "plot.info",
                "world", chunk.getWorld().getName(),
                "town", townName,
                "type", plot.getPlotType().name(),
                "owner", owner,
                "for_sale", forSale,
                "x", String.valueOf(chunk.getX()),
                "z", String.valueOf(chunk.getZ()));
    }

    private String resolveOwnerName(UUID ownerUuid) {
        if (ownerUuid == null) return "Town";
        Optional<TowniaPlayer> res = residentManager.getResident(ownerUuid);
        return res.map(TowniaPlayer::getName).orElse("Unknown");
    }

    private void handleSetType(Player player, String typeName) {
        PlotType plotType = PlotType.fromString(typeName);
        // fromString returns DEFAULT as fallback  Echeck if the input was actually valid
        boolean validInput = java.util.Arrays.stream(PlotType.values())
                .anyMatch(t -> t.name().equalsIgnoreCase(typeName));
        if (!validInput) {
            plugin.getMessageManager().sendMessage(player, "error.invalid-args",
                    "{usage}", "/plot set type <DEFAULT|SHOP|ARENA|EMBASSY|FARM|INN>");
            return;
        }

        Chunk chunk = player.getLocation().getChunk();
        if (plotManager.isClaimed(chunk)) {
            plugin.getMessageManager().sendMessage(player, "plot.no-plot-here");
            return;
        }

        if (isAssistantOrHigherInPlotTown(player, chunk)) {
            plugin.getMessageManager().sendMessage(player, "town.not-assistant");
            return;
        }

        try {
            plotManager.setPlotType(
                    chunk.getWorld().getName(),
                    chunk.getX(),
                    chunk.getZ(),
                    plotType);
            plugin.getMessageManager().sendMessage(player, "plot.set-type", "{type}", plotType.name());
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(player, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleForSale(Player player, String priceStr) {
        double price;
        try {
            price = Double.parseDouble(priceStr);
            if (price < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.getMessageManager().sendMessage(player, "error.invalid-amount");
            return;
        }

        Chunk chunk = player.getLocation().getChunk();
        if (plotManager.isClaimed(chunk)) {
            plugin.getMessageManager().sendMessage(player, "plot.no-plot-here");
            return;
        }

        if (isAssistantOrHigherInPlotTown(player, chunk)) {
            plugin.getMessageManager().sendMessage(player, "town.not-assistant");
            return;
        }

        try {
            plotManager.setForSale(
                    chunk.getWorld().getName(),
                    chunk.getX(),
                    chunk.getZ(),
                    true, price);
            plugin.getMessageManager().sendMessage(player, "plot.for-sale", "{price}", formatMoney(price));
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(player, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleNotForSale(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        if (plotManager.isClaimed(chunk)) {
            plugin.getMessageManager().sendMessage(player, "plot.no-plot-here");
            return;
        }

        if (isAssistantOrHigherInPlotTown(player, chunk)) {
            plugin.getMessageManager().sendMessage(player, "town.not-assistant");
            return;
        }

        Optional<Plot> plotOpt = plotManager.getPlot(chunk);
        if (plotOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(player, "plot.no-plot-here");
            return;
        }

        try {
            plotManager.setForSale(plotOpt.get().getWorldName(), plotOpt.get().getChunkX(), plotOpt.get().getChunkZ(), false, 0);
            plugin.getMessageManager().sendMessage(player, "plot.not-for-sale");
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(player, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleSetName(Player player, String name) {
        Plot plot = plotManager.getPlot(player.getLocation().getChunk()).orElse(null);
        if (plot == null) {
            plugin.getMessageManager().sendMessage(player, "plot.no-plot-here");
            return;
        }

        TowniaPlayer res = requireMayorOrOwner(player, plot);
        if (res == null) return;

        plot.setName(name);
        try {
            plugin.getDatabaseManager().savePlot(plot);
            plugin.getMessageManager().sendMessage(player, "plot.name-set", "name", name);
        } catch (java.sql.SQLException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to update plot name", e);
            plugin.getMessageManager().sendMessage(player, "error.database");
        }
    }

    private void handleToggle(Player player, String[] args) {
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "error.invalid-args");
            return;
        }

        Plot plot = plotManager.getPlot(player.getLocation().getChunk()).orElse(null);
        if (plot == null) {
            plugin.getMessageManager().sendMessage(player, "plot.no-plot-here");
            return;
        }

        TowniaPlayer res = requireMayorOrOwner(player, plot);
        if (res == null) return;

        switch (args[1].toLowerCase()) {
            case "pvp" -> {
                plot.setPvp(!plot.hasPvp());
                savePlotToggle(player, plot, "PvP", plot.hasPvp());
            }
            case "mobs" -> {
                plot.setMobs(!plot.hasMobs());
                savePlotToggle(player, plot, "Mobs", plot.hasMobs());
            }
            case "explosions" -> {
                plot.setExplosions(!plot.hasExplosions());
                savePlotToggle(player, plot, "Explosions", plot.hasExplosions());
            }
            case "fire" -> {
                plot.setFire(!plot.hasFire());
                savePlotToggle(player, plot, "Fire", plot.hasFire());
            }
            default -> plugin.getMessageManager().sendMessage(player, "error.invalid-args");
        }
    }

    private void savePlotToggle(Player player, Plot plot, String setting, boolean state) {
        try {
            plugin.getDatabaseManager().savePlot(plot);
            plugin.getMessageManager().sendMessage(player, "plot.toggled", "setting", setting, "state", state ? "ON" : "OFF");
        } catch (java.sql.SQLException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to save plot toggle", e);
            plugin.getMessageManager().sendMessage(player, "error.database");
        }
    }

    private void handleBuy(Player player) {
        if (!plugin.hasEconomy()) {
            plugin.getMessageManager().sendMessage(player, "error.no-vault");
            return;
        }

        Chunk chunk = player.getLocation().getChunk();
        Optional<Plot> plotOpt = plotManager.getPlot(chunk);
        if (plotOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(player, "plot.no-plot-here");
            return;
        }

        Plot plot = plotOpt.get();
        if (!plot.isForSale()) {
            plugin.getMessageManager().sendMessage(player, "plot.not-for-sale-error");
            return;
        }

        double price = plot.getPrice();
        Economy eco = plugin.getEconomy();

        if (!eco.has(player, price)) {
            plugin.getMessageManager().sendMessage(player, "error.insufficient-funds",
                    "amount", formatMoney(price));
            return;
        }

        try {
            EconomyResponse resp = eco.withdrawPlayer(player, price);
            if (!resp.transactionSuccess()) {
                plugin.getMessageManager().sendMessage(player, "error.insufficient-funds",
                        "amount", formatMoney(price));
                return;
            }

            townManager.addBalance(plot.getTownUuid(), price);
            plotManager.transferOwnership(
                    chunk.getWorld().getName(),
                    chunk.getX(),
                    chunk.getZ(),
                    player.getUniqueId());

            plotManager.setForSale(
                    chunk.getWorld().getName(),
                    chunk.getX(),
                    chunk.getZ(),
                    false, 0.0);

            plugin.getMessageManager().sendMessage(player, "plot.bought", "{price}", formatMoney(price));
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(player, e.getMessageKey(), e.getReplacements());
        }
    }

    private boolean isAssistantOrHigherInPlotTown(Player player, Chunk chunk) {
        Optional<Plot> plotOpt = plotManager.getPlot(chunk);
        if (plotOpt.isEmpty()) return true;
        UUID plotTownId = plotOpt.get().getTownUuid();

        Optional<TowniaPlayer> resOpt = residentManager.getResident(player.getUniqueId());
        if (resOpt.isEmpty()) return true;
        TowniaPlayer res = resOpt.get();
        if (!res.isInTown()) return true;
        if (!plotTownId.equals(res.getTownUuid())) return true;
        return !res.isAssistantOrHigher();
    }

    private String formatMoney(double amount) {
        if (plugin.hasEconomy()) return plugin.getEconomy().format(amount);
        return String.format("%.2f", amount);
    }

    private void sendHelp(Player player) {
        plugin.getMessageManager().sendMessage(player, "plot.help");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0],
                    List.of("info", "set", "forsale", "notforsale", "buy", "perm"), completions);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            StringUtil.copyPartialMatches(args[1], List.of("type", "name", "reset", "perm"), completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("type")) {
            List<String> types = new ArrayList<>();
            for (PlotType pt : PlotType.values()) types.add(pt.name().toLowerCase());
            StringUtil.copyPartialMatches(args[2], types, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("perm")) {
            StringUtil.copyPartialMatches(args[2], List.of("resident", "ally", "outsider", "nation", "build", "destroy", "switch", "itemuse", "on", "off"), completions);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("perm")) {
            if (!args[2].equalsIgnoreCase("on") && !args[2].equalsIgnoreCase("off")) {
                StringUtil.copyPartialMatches(args[3], List.of("build", "destroy", "switch", "itemuse", "on", "off"), completions);
            }
        } else if (args.length == 5 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("perm")) {
            if (!args[3].equalsIgnoreCase("on") && !args[3].equalsIgnoreCase("off")) {
                StringUtil.copyPartialMatches(args[4], List.of("on", "off"), completions);
            }
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

    private TowniaPlayer requireMayorOrOwner(Player player, Plot plot) {
        TowniaPlayer res = residentManager.getResident(player.getUniqueId()).orElse(null);
        if (res == null) {
            plugin.getMessageManager().sendMessage(player, "error.player-not-found");
            return null;
        }
        if (plot.getOwnerUuid() != null && plot.getOwnerUuid().equals(player.getUniqueId())) {
            return res;
        }
        if (plot.getTownUuid().equals(res.getTownUuid()) && res.isMayorOrHigher()) {
            return res;
        }
        plugin.getMessageManager().sendMessage(player, "error.no-permission");
        return null;
    }

    private void handleSetReset(Player player) {
        org.bukkit.Chunk chunk = player.getLocation().getChunk();
        java.util.Optional<Plot> plotOpt = plotManager.getPlot(chunk);
        if (plotOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(player, "plot.no-plot-here");
            return;
        }
        Plot plot = plotOpt.get();
        TowniaPlayer res = requireMayorOrOwner(player, plot);
        if (res == null) return;

        plot.setPvp(false);
        plot.setMobs(false);
        plot.setExplosions(false);
        plot.setFire(false);
        plot.setName(null);
        try {
            plugin.getDatabaseManager().savePlot(plot);
        } catch (java.sql.SQLException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to save plot reset", e);
        }
        plugin.getMessageManager().sendMessage(player, "plot.reset");
    }

    private void handleSetPerm(Player player, String[] args) {
        org.bukkit.Chunk chunk = player.getLocation().getChunk();
        net.azisaba.townia.data.Plot plot = plugin.getPlotManager().getPlot(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()).orElse(null);
        if (plot == null) {
            plugin.getMessageManager().sendMessage(player, "plot.not-owned-by-your-town");
            return;
        }
        net.azisaba.townia.data.TowniaPlayer res = plugin.getResidentManager().getResident(player.getUniqueId()).orElse(null);
        if (res == null || res.getTownUuid() == null || !res.getTownUuid().equals(plot.getTownUuid())) {
            plugin.getMessageManager().sendMessage(player, "plot.not-owned-by-your-town");
            return;
        }

        if (plot.getOwnerUuid() != null) {
            if (!plot.getOwnerUuid().equals(res.getUuid()) && !res.isAssistantOrHigher()) {
                plugin.getMessageManager().sendMessage(player, "error.no-permission");
                return;
            }
        } else {
            if (!res.isAssistantOrHigher()) {
                plugin.getMessageManager().sendMessage(player, "error.no-permission");
                return;
            }
        }

        if (args.length < 1) {
            plugin.getMessageManager().sendMessage(player, "error.invalid-args");
            return;
        }

        String groupStr = null;
        String actionStr = null;
        String stateStr = null;

        if (args.length == 1) {
            stateStr = args[0];
        } else if (args.length == 2) {
            String first = args[0].toLowerCase();
            if (first.equals("resident") || first.equals("ally") || first.equals("outsider") || first.equals("nation")) {
                groupStr = args[0];
            } else {
                actionStr = args[0];
            }
            stateStr = args[1];
        } else if (args.length >= 3) {
            groupStr = args[0];
            actionStr = args[1];
            stateStr = args[2];
        }

        boolean state = stateStr.equalsIgnoreCase("on") || stateStr.equalsIgnoreCase("true");

        java.util.List<String> groups = new java.util.ArrayList<>();
        if (groupStr == null) {
            groups.addAll(java.util.Arrays.asList("resident", "ally", "outsider", "nation"));
        } else {
            if (groupStr.equalsIgnoreCase("resident")) groups.add("resident");
            else if (groupStr.equalsIgnoreCase("ally")) groups.add("ally");
            else if (groupStr.equalsIgnoreCase("outsider")) groups.add("outsider");
            else if (groupStr.equalsIgnoreCase("nation")) groups.add("nation");
            else {
                plugin.getMessageManager().sendMessage(player, "error.invalid-args");
                return;
            }
        }

        java.util.List<Character> actions = new java.util.ArrayList<>();
        if (actionStr == null) {
            actions.addAll(java.util.Arrays.asList('B', 'D', 'S', 'I'));
        } else {
            if (actionStr.equalsIgnoreCase("build")) actions.add('B');
            else if (actionStr.equalsIgnoreCase("destroy")) actions.add('D');
            else if (actionStr.equalsIgnoreCase("switch")) actions.add('S');
            else if (actionStr.equalsIgnoreCase("item") || actionStr.equalsIgnoreCase("itemuse")) actions.add('I');
            else {
                plugin.getMessageManager().sendMessage(player, "error.invalid-args");
                return;
            }
        }

        for (String group : groups) {
            String current = "";
            if (group.equals("resident")) current = plot.getPermsResident() != null ? plot.getPermsResident() : "";
            else if (group.equals("ally")) current = plot.getPermsAlly() != null ? plot.getPermsAlly() : "";
            else if (group.equals("outsider")) current = plot.getPermsOutsider() != null ? plot.getPermsOutsider() : "";
            else if (group.equals("nation")) current = plot.getPermsNation() != null ? plot.getPermsNation() : "";

            for (char action : actions) {
                current = net.azisaba.townia.data.PermissionMatrix.setPerm(current, action, state);
            }

            if (group.equals("resident")) plot.setPermsResident(current);
            else if (group.equals("ally")) plot.setPermsAlly(current);
            else if (group.equals("outsider")) plot.setPermsOutsider(current);
            else if (group.equals("nation")) plot.setPermsNation(current);
        }
        
        try {
            plugin.getDatabaseManager().savePlot(plot);
        } catch (java.sql.SQLException e) {
            e.printStackTrace();
            plugin.getMessageManager().sendMessage(player, "error.database");
            return;
        }
        plugin.getMessageManager().sendMessage(player, "town.perm-set", "perm", (groupStr!=null?groupStr+" ":"")+(actionStr!=null?actionStr:"all"), "state", state?"ON":"OFF");
    }

    private void handleClaimAuto(Player player) {
        plugin.getMessageManager().sendMessage(player, "error.not-implemented");
    }

    private void handleClear(Player player) {
        plugin.getMessageManager().sendMessage(player, "error.not-implemented");
    }

    private void handleEvict(Player player) {
        plugin.getMessageManager().sendMessage(player, "error.not-implemented");
    }
}
