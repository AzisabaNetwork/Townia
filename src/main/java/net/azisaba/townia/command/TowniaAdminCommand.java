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
import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class TowniaAdminCommand implements CommandExecutor, TabCompleter {

    private static final Set<UUID> bypassSet = Collections.synchronizedSet(new HashSet<>());

    public static boolean isBypassing(UUID uuid) {
        return bypassSet.contains(uuid);
    }

    private final Townia plugin;
    private final TownManager townManager;
    private final NationManager nationManager;
    private final ResidentManager residentManager;
    private final PlotManager plotManager;

    public TowniaAdminCommand(Townia plugin) {
        this.plugin = plugin;
        this.townManager = plugin.getTownManager();
        this.nationManager = plugin.getNationManager();
        this.residentManager = plugin.getResidentManager();
        this.plotManager = plugin.getPlotManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("townia.admin")) {
            plugin.getMessageManager().sendMessage(sender, "error.no-permission");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload"        -> handleReload(sender);
            case "bypass"        -> handleBypass(sender);
            case "forceclaim"    -> handleForceClaim(sender, args);
            case "forceunclaim"  -> handleForceUnclaim(sender);
            case "deletetown"    -> handleDeleteTown(sender, args);
            case "deletenation"  -> handleDeleteNation(sender, args);
            case "givebonus"     -> handleGiveBonus(sender, args);
            case "migrate"       -> net.azisaba.townia.migration.TownyMigrator.migrate(plugin, sender);
            default              -> sendHelp(sender);
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        plugin.getTowniaConfig().reload();
        plugin.getMessageManager().loadAllMessages();
        plugin.getMessageManager().sendMessage(sender, "admin.reloaded");
    }

    private void handleBypass(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        UUID uuid = player.getUniqueId();
        if (bypassSet.contains(uuid)) {
            bypassSet.remove(uuid);
            plugin.getMessageManager().sendMessage(sender, "admin.bypass-off");
        } else {
            bypassSet.add(uuid);
            plugin.getMessageManager().sendMessage(sender, "admin.bypass-on");
        }
    }

    private void handleForceClaim(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        Chunk chunk = player.getLocation().getChunk();
        UUID townUuid;

        if (args.length >= 2) {
            Optional<Town> townOpt = townManager.getTownByName(args[1]);
            if (townOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(sender, "error.town-not-found");
                return;
            }
            townUuid = townOpt.get().getId();
        } else {
            Optional<TowniaPlayer> resOpt = residentManager.getResident(player.getUniqueId());
            if (resOpt.isEmpty() || !resOpt.get().isInTown()) {
                plugin.getMessageManager().sendMessage(sender, "error.not-in-town");
                return;
            }
            townUuid = resOpt.get().getTownUuid();
        }

        try {
            plotManager.forceClaimChunk(townUuid, chunk);
            plugin.getMessageManager().sendMessage(sender, "admin.force-claimed");
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleForceUnclaim(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        Chunk chunk = player.getLocation().getChunk();
        if (plotManager.isClaimed(chunk)) {
            plugin.getMessageManager().sendMessage(sender, "town.chunk-not-claimed");
            return;
        }

        try {
            plotManager.forceUnclaimChunk(chunk);
            plugin.getMessageManager().sendMessage(sender, "admin.force-unclaimed");
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleDeleteTown(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        String townName = args[1];
        Optional<Town> townOpt = townManager.getTownByName(townName);
        if (townOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.town-not-found");
            return;
        }
        Town town = townOpt.get();
        UUID townUuid = town.getId();

        try {
            // Clear residents
            residentManager.getResidentsByTown(townUuid)
                    .forEach(res -> residentManager.clearTown(res.getUuid()));

            // Delete invites
            plugin.getDatabaseManager().deleteInvitesByTown(townUuid);

            // Delete town
            townManager.deleteTown(townUuid);
            plugin.getMessageManager().sendMessage(sender, "admin.town-deleted", "town", townName);
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "DB error deleting town via admin", e);
            plugin.getMessageManager().sendMessage(sender, "error.database");
        }
    }

    private void handleDeleteNation(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        String nationName = args[1];
        Optional<Nation> nationOpt = nationManager.getNationByName(nationName);
        if (nationOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.nation-not-found");
            return;
        }

        try {
            nationManager.deleteNation(nationOpt.get().getId());
            plugin.getMessageManager().sendMessage(sender, "admin.nation-deleted", "nation", nationName);
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void handleGiveBonus(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-args");
            return;
        }

        Optional<Town> townOpt = townManager.getTownByName(args[1]);
        if (townOpt.isEmpty()) {
            plugin.getMessageManager().sendMessage(sender, "error.town-not-found");
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
            if (amount < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.getMessageManager().sendMessage(sender, "error.invalid-amount");
            return;
        }

        try {
            townManager.setBonusClaims(townOpt.get().getId(), amount);
            plugin.getMessageManager().sendMessage(sender, "admin.bonus-given",
                    "{town}", args[1], "{amount}", String.valueOf(amount));
        } catch (TowniaException e) {
            plugin.getMessageManager().sendMessage(sender, e.getMessageKey(), e.getReplacements());
        }
    }

    private void sendHelp(CommandSender sender) {
        plugin.getMessageManager().sendMessage(sender, "admin.help");
    }

    private Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendMessage(sender, "error.player-only");
            return null;
        }
        return player;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("townia.admin")) return Collections.emptyList();

        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0],
                    List.of("reload", "bypass", "forceclaim", "forceunclaim",
                            "deletetown", "deletenation", "givebonus", "migrate"),
                    completions);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "forceclaim", "deletetown", "givebonus" -> {
                    List<String> townNames = townManager.getAllTowns().stream()
                            .map(Town::getName).toList();
                    StringUtil.copyPartialMatches(args[1], townNames, completions);
                }
                case "deletenation" -> {
                    List<String> nationNames = nationManager.getAllNations().stream()
                            .map(Nation::getName).toList();
                    StringUtil.copyPartialMatches(args[1], nationNames, completions);
                }
            }
        }
        return completions;
    }
}
