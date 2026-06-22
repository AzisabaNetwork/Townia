package net.azisaba.townia.command;

import net.azisaba.townia.Townia;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
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
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        plugin.getMessageManager().sendMessage(sender, "admin.help");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("townia.admin.reload")) options.add("reload");
            options.add("info");
            options.add("map");
            for (String opt : options) {
                if (opt.startsWith(args[0].toLowerCase())) completions.add(opt);
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
