package net.azisaba.townia.command;

import net.azisaba.townia.Townia;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

public class TowniaWorldCommand implements CommandExecutor, TabCompleter {

    private final Townia plugin;

    public TowniaWorldCommand(Townia plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("townia.admin.world")) {
            plugin.getMessageManager().sendMessage(sender, "error.no-permission");
            return true;
        }

        if (args.length == 0) {
            handleList(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list"   -> handleList(sender);
            case "add"    -> {
                if (args.length < 2) {
                    plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
                    return true;
                }
                handleAdd(sender, args[1]);
            }
            case "remove" -> {
                if (args.length < 2) {
                    plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
                    return true;
                }
                handleRemove(sender, args[1]);
            }
            default -> handleList(sender);
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        List<String> worlds = plugin.getTowniaConfig().getAllowedWorlds();
        plugin.getMessageManager().sendMessage(sender, "world.list-header",
                "count", String.valueOf(worlds.size()));
        for (String world : worlds) {
            plugin.getMessageManager().sendMessage(sender, "world.list-entry", "world", world);
        }
    }

    private void handleAdd(CommandSender sender, String worldName) {
        if (plugin.getTowniaConfig().isWorldAllowed(worldName)) {
            plugin.getMessageManager().sendMessage(sender, "world.already-allowed", "world", worldName);
            return;
        }

        List<String> current = new ArrayList<>(plugin.getTowniaConfig().getAllowedWorlds());
        current.add(worldName);

        plugin.getConfig().set("allowed-worlds", current);
        plugin.saveConfig();
        plugin.getTowniaConfig().reload();

        plugin.getMessageManager().sendMessage(sender, "world.added", "world", worldName);
    }

    private void handleRemove(CommandSender sender, String worldName) {
        if (!plugin.getTowniaConfig().isWorldAllowed(worldName)) {
            plugin.getMessageManager().sendMessage(sender, "world.not-allowed", "world", worldName);
            return;
        }

        List<String> current = new ArrayList<>(plugin.getTowniaConfig().getAllowedWorlds());
        current.remove(worldName);

        plugin.getConfig().set("allowed-worlds", current);
        plugin.saveConfig();
        plugin.getTowniaConfig().reload();

        plugin.getMessageManager().sendMessage(sender, "world.removed", "world", worldName);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("townia.admin.world")) return new ArrayList<>();

        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("list", "add", "remove"), completions);
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("add")) {
                List<String> worldNames = new ArrayList<>();
                for (World w : Bukkit.getWorlds()) worldNames.add(w.getName());
                StringUtil.copyPartialMatches(args[1], worldNames, completions);
            } else if (args[0].equalsIgnoreCase("remove")) {
                StringUtil.copyPartialMatches(args[1],
                        plugin.getTowniaConfig().getAllowedWorlds(), completions);
            }
        }
        return completions;
    }
}
