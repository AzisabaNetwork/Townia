package net.azisaba.townia.command;

import net.azisaba.townia.Townia;
import net.azisaba.townia.TowniaException;
import net.azisaba.townia.data.Invite;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.data.TowniaPlayer;
import net.azisaba.townia.database.DatabaseManager;
import net.azisaba.townia.manager.ResidentManager;
import net.azisaba.townia.manager.TownManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class InviteCommand implements CommandExecutor, TabCompleter {

    private final Townia plugin;
    private final DatabaseManager databaseManager;
    private final ResidentManager residentManager;
    private final TownManager townManager;

    public InviteCommand(Townia plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDatabaseManager();
        this.residentManager = plugin.getResidentManager();
        this.townManager = plugin.getTownManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return true;

        UUID playerUuid = player.getUniqueId();

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            handleList(player, playerUuid);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "accept" -> {
                if (args.length < 2) {
                    handleAcceptOldest(player, playerUuid);
                } else {
                    handleAcceptByTown(player, playerUuid, args[1]);
                }
            }
            case "decline" -> {
                if (args.length < 2) {
                    plugin.getMessageManager().sendMessage(player, "error.invalid-args");
                    return true;
                }
                handleDecline(player, playerUuid, args[1]);
            }
            default -> handleList(player, playerUuid);
        }
        return true;
    }

    private void handleList(Player player, UUID playerUuid) {
        try {
            List<Invite> invites = databaseManager.getInvitesByTarget(playerUuid);
            int timeoutSecs = plugin.getTowniaConfig().getInviteTimeout();

            if (invites.isEmpty()) {
                plugin.getMessageManager().sendMessage(player, "invite.no-pending");
                return;
            }

            plugin.getMessageManager().sendMessage(player, "invite.list-header");
            for (Invite invite : invites) {
                if (invite.isExpired(timeoutSecs)) {
                    databaseManager.deleteInvite(invite.id());
                    continue;
                }
                Optional<Town> townOpt = townManager.getTown(invite.townUuid());
                String townName = townOpt.map(Town::getName).orElse("Unknown");
                Optional<TowniaPlayer> inviterOpt = residentManager.getResident(invite.inviterUuid());
                String inviterName = inviterOpt.map(TowniaPlayer::getName).orElse("Unknown");
                plugin.getMessageManager().sendMessage(player, "invite.list-entry", "town", townName, "inviter", inviterName);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "DB error listing invites", e);
            plugin.getMessageManager().sendMessage(player, "error.database");
        }
    }

    private void handleAcceptOldest(Player player, UUID playerUuid) {
        try {
            List<Invite> invites = databaseManager.getInvitesByTarget(playerUuid);
            int timeoutSecs = plugin.getTowniaConfig().getInviteTimeout();

            Invite toAccept = null;
            for (Invite invite : invites) {
                if (invite.isExpired(timeoutSecs)) {
                    databaseManager.deleteInvite(invite.id());
                } else {
                    toAccept = invite;
                    break;
                }
            }

            if (toAccept == null) {
                plugin.getMessageManager().sendMessage(player, "invite.no-pending");
                return;
            }

            acceptInvite(player, playerUuid, toAccept);
        } catch (SQLException | TowniaException e) {
            handleError(player, e);
        }
    }

    private void handleAcceptByTown(Player player, UUID playerUuid, String townName) {
        try {
            Optional<Town> townOpt = townManager.getTownByName(townName);
            if (townOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(player, "error.town-not-found", "town", "Unknown");
                return;
            }
            UUID townUuid = townOpt.get().getId();
            Optional<Invite> inviteOpt = databaseManager.getInvite(playerUuid, townUuid);
            if (inviteOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(player, "invite.no-pending");
                return;
            }
            Invite invite = inviteOpt.get();
            int timeoutSecs = plugin.getTowniaConfig().getInviteTimeout();
            if (invite.isExpired(timeoutSecs)) {
                databaseManager.deleteInvite(invite.id());
                plugin.getMessageManager().sendMessage(player, "invite.expired");
                return;
            }
            acceptInvite(player, playerUuid, invite);
        } catch (SQLException | TowniaException e) {
            handleError(player, e);
        }
    }

    private void acceptInvite(Player player, UUID playerUuid, Invite invite) throws SQLException, TowniaException {
        Optional<TowniaPlayer> residentOpt = residentManager.getResident(playerUuid);
        if (residentOpt.isPresent() && residentOpt.get().isInTown()) {
            plugin.getMessageManager().sendMessage(player, "error.already-in-town");
            return;
        }

        UUID townUuid = invite.townUuid();
        Optional<Town> townOpt = townManager.getTown(townUuid);
        if (townOpt.isEmpty()) {
            databaseManager.deleteInvite(invite.id());
            plugin.getMessageManager().sendMessage(player, "error.town-not-found", "town", "Unknown");
            return;
        }

        residentManager.setTown(playerUuid, townUuid, net.azisaba.townia.data.TownRank.RESIDENT);
        databaseManager.deleteInvitesByTarget(playerUuid);

        String townName = townOpt.get().getName();
        plugin.getMessageManager().sendMessage(player, "invite.accept-success", "town", townName);
        plugin.getMessageManager().sendMessage(player, "town.joined", "town", townName);
    }

    private void handleDecline(Player player, UUID playerUuid, String townName) {
        try {
            Optional<Town> townOpt = townManager.getTownByName(townName);
            if (townOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(player, "error.town-not-found", "town", "Unknown");
                return;
            }
            UUID townUuid = townOpt.get().getId();
            Optional<Invite> inviteOpt = databaseManager.getInvite(playerUuid, townUuid);
            if (inviteOpt.isEmpty()) {
                plugin.getMessageManager().sendMessage(player, "invite.no-pending");
                return;
            }
            databaseManager.deleteInvite(inviteOpt.get().id());
            plugin.getMessageManager().sendMessage(player, "invite.decline-success",
                    "town", townOpt.get().getName());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "DB error declining invite", e);
            plugin.getMessageManager().sendMessage(player, "error.database");
        }
    }

    private void handleError(Player player, Exception e) {
        if (e instanceof TowniaException te) {
            plugin.getMessageManager().sendMessage(player, te.getMessageKey(), te.getReplacements());
        } else if (e instanceof SQLException) {
            plugin.getLogger().log(Level.SEVERE, "DB error in InviteCommand", e);
            plugin.getMessageManager().sendMessage(player, "error.database");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("list", "accept", "decline"), completions);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("decline"))) {
            townManager.getAllTowns().stream()
                    .map(Town::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .forEach(completions::add);
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
}
