package net.azisaba.townia.manager;

import net.azisaba.townia.Townia;
import net.azisaba.townia.data.TownRank;
import net.azisaba.townia.data.TowniaPlayer;
import net.azisaba.townia.database.DatabaseManager;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ResidentManager {

    private final Townia plugin;
    private final DatabaseManager db;

    private final Map<UUID, TowniaPlayer> cache = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();

    public ResidentManager(Townia plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
        loadAll();
    }

    public void cacheResident(TowniaPlayer res) { cache.put(res.getUuid(), res); nameIndex.put(res.getName().toLowerCase(), res.getUuid()); }

    private void loadAll() {
        cache.clear();
        nameIndex.clear();
        try {
            for (TowniaPlayer p : db.getAllResidents()) {
                cache.put(p.getUuid(), p);
                nameIndex.put(p.getName().toLowerCase(), p.getUuid());
            }
            plugin.getLogger().info("Loaded " + cache.size() + " residents.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load residents from database", e);
        }
    }

    public Optional<TowniaPlayer> getResident(UUID uuid) {
        return Optional.ofNullable(cache.get(uuid));
    }

    public Optional<TowniaPlayer> getResidentByName(String name) {
        UUID uuid = nameIndex.get(name.toLowerCase());
        if (uuid == null) return Optional.empty();
        return Optional.ofNullable(cache.get(uuid));
    }

    public List<TowniaPlayer> getAllResidents() {
        return new ArrayList<>(cache.values());
    }

    public List<TowniaPlayer> getResidentsByTown(UUID townUuid) {
        List<TowniaPlayer> list = new ArrayList<>();
        for (TowniaPlayer p : cache.values()) {
            if (townUuid.equals(p.getTownUuid())) list.add(p);
        }
        list.sort(Comparator.comparing(TowniaPlayer::getName));
        return list;
    }

    public boolean isResident(UUID uuid) {
        return cache.containsKey(uuid);
    }

    public TowniaPlayer getOrCreate(Player player) {
        TowniaPlayer existing = cache.get(player.getUniqueId());
        if (existing != null) {
            // Refresh name in case of rename
            if (!existing.getName().equals(player.getName())) {
                nameIndex.remove(existing.getName().toLowerCase());
                existing.setName(player.getName());
                nameIndex.put(player.getName().toLowerCase(), player.getUniqueId());
                persist(existing);
            }
            return existing;
        }

        TowniaPlayer newPlayer = new TowniaPlayer(
                player.getUniqueId(),
                player.getName(),
                null,
                TownRank.RESIDENT,
                System.currentTimeMillis(),
                null
        );
        cache.put(newPlayer.getUuid(), newPlayer);
        nameIndex.put(newPlayer.getName().toLowerCase(), newPlayer.getUuid());
        persist(newPlayer);
        return newPlayer;
    }

    public void setTown(UUID playerUuid, UUID townUuid, TownRank rank) {
        TowniaPlayer p = cache.get(playerUuid);
        if (p == null) return;
        p.setTownUuid(townUuid);
        p.setRank(rank);
        persist(p);
    }

    public void clearTown(UUID playerUuid) {
        TowniaPlayer p = cache.get(playerUuid);
        if (p == null) return;
        p.setTownUuid(null);
        p.setRank(TownRank.RESIDENT);
        persist(p);
    }

    public void setRank(UUID playerUuid, TownRank rank) {
        TowniaPlayer p = cache.get(playerUuid);
        if (p == null) return;
        p.setRank(rank);
        persist(p);
    }

    public void updateLastSeen(UUID playerUuid) {
        TowniaPlayer p = cache.get(playerUuid);
        if (p == null) return;
        p.setLastSeen(System.currentTimeMillis());
        persist(p);
    }

    public void saveResident(TowniaPlayer player) {
        persist(player);
    }

    private void persist(TowniaPlayer player) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.saveResident(player);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save resident " + player.getName(), e);
            }
        });
    }

    public void addFriend(TowniaPlayer player, TowniaPlayer friend) {
        if (!player.getFriends().contains(friend.getUuid().toString())) {
            player.getFriends().add(friend.getUuid().toString());
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.addFriend(player.getUuid(), friend.getUuid());
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to add friend for resident " + player.getName(), e);
                }
            });
        }
    }

    public void removeFriend(TowniaPlayer player, TowniaPlayer friend) {
        if (player.getFriends().contains(friend.getUuid().toString())) {
            player.getFriends().remove(friend.getUuid().toString());
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.removeFriend(player.getUuid(), friend.getUuid());
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to remove friend for resident " + player.getName(), e);
                }
            });
        }
    }
}
