package net.azisaba.townia.command;

import net.azisaba.townia.Townia;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.data.TowniaPlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TownConfigCommand implements CommandExecutor, TabCompleter {

    private final Townia plugin;

    public TownConfigCommand(Townia plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendMessage(sender, "error.player-only");
            return true;
        }

        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }

        Optional<TowniaPlayer> resOpt = plugin.getResidentManager().getResident(player.getUniqueId());
        if (resOpt.isEmpty() || !resOpt.get().isInTown()) {
            plugin.getMessageManager().sendMessage(sender, "error.not-in-town");
            return true;
        }
        TowniaPlayer res = resOpt.get();

        if (!res.isMayor()) {
            plugin.getMessageManager().sendMessage(sender, "town.not-mayor");
            return true;
        }

        Optional<Town> townOpt = plugin.getTownManager().getTown(res.getTownUuid());
        if (townOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.town-not-found", "town", "Unknown");
            return true;
        }
        Town town = townOpt.get();

        String key = args[0].toLowerCase();
        String valueStr = args[1].toLowerCase();

        switch (key) {
            case "allowinvisibility" -> {
                boolean val = parseBoolean(valueStr, true);
                town.setAllowInvisibility(val);
                plugin.getTownManager().saveTown(town);
                plugin.getMessageManager().sendMessage(sender, "townconfig.set",
                        "key", "allowInvisibility", "value", String.valueOf(val));
            }
            case "allowsit" -> {
                boolean val = parseBoolean(valueStr, true);
                town.setAllowSit(val);
                plugin.getTownManager().saveTown(town);
                plugin.getMessageManager().sendMessage(sender, "townconfig.set",
                        "key", "allowSit", "value", String.valueOf(val));
            }
            case "allowpetpickup" -> {
                boolean val = parseBoolean(valueStr, true);
                town.setAllowPetPickup(val);
                plugin.getTownManager().saveTown(town);
                plugin.getMessageManager().sendMessage(sender, "townconfig.set",
                        "key", "allowPetPickup", "value", String.valueOf(val));
            }
            case "allowpassenger" -> {
                boolean val = parseBoolean(valueStr, true);
                town.setAllowPassenger(val);
                plugin.getTownManager().saveTown(town);
                plugin.getMessageManager().sendMessage(sender, "townconfig.set",
                        "key", "allowPassenger", "value", String.valueOf(val));
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private boolean parseBoolean(String s, boolean def) {
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes") || s.equals("1"))  return true;
        if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("no") || s.equals("0")) return false;
        return def;
    }

    private void sendHelp(CommandSender sender) {
        plugin.getMessageManager().sendMessage(sender, "townconfig.help");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0],
                    List.of("allowInvisibility", "allowSit", "allowPetPickup", "allowPassenger"),
                    completions);
        } else if (args.length == 2) {
            StringUtil.copyPartialMatches(args[1], List.of("true", "false"), completions);
        }
        return completions;
    }
}
