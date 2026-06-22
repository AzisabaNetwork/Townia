package net.azisaba.townia.manager;

import net.azisaba.townia.Townia;
import net.azisaba.townia.TowniaException;
import net.azisaba.townia.data.Nation;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.data.TownRank;
import net.azisaba.townia.data.TowniaPlayer;
import net.azisaba.townia.database.DatabaseManager;
import org.bukkit.Location;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class TownManager {

    private final Townia plugin;
    private final DatabaseManager db;
    private final ResidentManager residentManager;

    private final Map<UUID, Town> cache = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();

    public TownManager(Townia plugin, DatabaseManager db, ResidentManager residentManager) {
        this.plugin = plugin;
        this.db = db;
        this.residentManager = residentManager;
        loadAll();
    }

    public void cacheTown(Town town) { cache.put(town.getId(), town); nameIndex.put(town.getName().toLowerCase(), town.getId()); }

    private void loadAll() {
        cache.clear();
        nameIndex.clear();
        try {
            for (Town t : db.getAllTowns()) {
                cache.put(t.getId(), t);
                nameIndex.put(t.getName().toLowerCase(), t.getId());
            }
            plugin.getLogger().info("Loaded " + cache.size() + " towns.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load towns from database", e);
        }
    }

    public Optional<Town> getTown(UUID id) {
        return Optional.ofNullable(cache.get(id));
    }

    public Optional<Town> getTownByName(String name) {
        UUID id = nameIndex.get(name.toLowerCase());
        if (id == null) return Optional.empty();
        return Optional.ofNullable(cache.get(id));
    }

    public Optional<Town> getTownOfPlayer(UUID playerUuid) {
        return residentManager.getResident(playerUuid)
                .filter(TowniaPlayer::isInTown)
                .flatMap(p -> getTown(p.getTownUuid()));
    }

    public List<Town> getAllTowns() {
        List<Town> list = new ArrayList<>(cache.values());
        list.sort(Comparator.comparing(Town::getName));
        return list;
    }

    public boolean townExists(String name) {
        return nameIndex.containsKey(name.toLowerCase());
    }

    public int getClaimCount(UUID townId) {
        try {
            return db.countPlotsByTown(townId);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to count plots for town " + townId, e);
            return 0;
        }
    }

    public Town createTown(String name, UUID mayorUuid) throws TowniaException {
        if (townExists(name)) {
            throw new TowniaException("town.already-exists", "{town}", name);
        }
        Optional<TowniaPlayer> resident = residentManager.getResident(mayorUuid);
        if (resident.isPresent() && resident.get().isInTown()) {
            throw new TowniaException("error.already-in-town");
        }

        Town town = new Town(
                UUID.randomUUID(), name, mayorUuid, null,
                0.0, plugin.getTowniaConfig().getDefaultClaimLimit(), 0,
                false, System.currentTimeMillis(),
                null, plugin.getTowniaConfig().getDefaultTownTax(), 0.0, false, false, false, false,
                null, 0.0, 0.0, 0.0, 0.0f, 0.0f
        );
        town.setDailyUpkeep(plugin.getTowniaConfig().getTownUpkeep());
        persist(town);
        residentManager.setTown(mayorUuid, town.getId(), TownRank.MAYOR);
        return town;
    }

    public void deleteTown(UUID townId) throws TowniaException {
        Town town = cache.get(townId);
        if (town == null) throw new TowniaException("error.town-not-found");

        for (TowniaPlayer p : residentManager.getResidentsByTown(townId)) {
            residentManager.clearTown(p.getUuid());
        }
        if (town.isInNation()) {
            Optional<Nation> nation = plugin.getNationManager().getNation(town.getNationUuid());
            nation.ifPresent(n -> {
                try {
                    plugin.getNationManager().removeTownFromNation(town.getNationUuid(), townId);
                } catch (TowniaException ignored) {}
            });
        }

        cache.remove(townId);
        nameIndex.remove(town.getName().toLowerCase());
        try {
            db.deleteTown(townId);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete town " + town.getName(), e);
            throw new TowniaException("error.database");
        }
    }

    public void renameTown(UUID townId, String newName) throws TowniaException {
        if (townExists(newName)) throw new TowniaException("town.already-exists", "{town}", newName);
        Town town = requireTown(townId);
        nameIndex.remove(town.getName().toLowerCase());
        town.setName(newName);
        nameIndex.put(newName.toLowerCase(), townId);
        persist(town);
    }
    
    public void saveTown(Town town) {
        persist(town);
    }

    public void setSpawn(UUID townId, Location loc) throws TowniaException {
        Town town = requireTown(townId);
        town.setSpawn(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        persist(town);
    }

    public void setPublic(UUID townId, boolean isPublic) throws TowniaException {
        Town town = requireTown(townId);
        town.setPublic(isPublic);
        persist(town);
    }

    public void setMayor(UUID townId, UUID newMayorUuid) throws TowniaException {
        Town town = requireTown(townId);
        UUID oldMayor = town.getMayorUuid();
        town.setMayorUuid(newMayorUuid);
        persist(town);
        // Demote old mayor to resident
        residentManager.setRank(oldMayor, TownRank.RESIDENT);
        residentManager.setRank(newMayorUuid, TownRank.MAYOR);
    }

    public void addBalance(UUID townId, double amount) throws TowniaException {
        Town town = requireTown(townId);
        town.setBalance(town.getBalance() + amount);
        persist(town);
    }

    public void subtractBalance(UUID townId, double amount) throws TowniaException {
        Town town = requireTown(townId);
        if (town.getBalance() < amount) throw new TowniaException("town.withdraw-insufficient");
        town.setBalance(town.getBalance() - amount);
        persist(town);
    }

    public void setBonusClaims(UUID townId, int bonusClaims) throws TowniaException {
        Town town = requireTown(townId);
        int capped = Math.min(bonusClaims, plugin.getTowniaConfig().getMaxBonusClaims());
        town.setBonusClaims(capped);
        persist(town);
    }

    public void setNation(UUID townId, UUID nationId) throws TowniaException {
        Town town = requireTown(townId);
        town.setNationUuid(nationId);
        persist(town);
    }

    public void clearNation(UUID townId) throws TowniaException {
        Town town = requireTown(townId);
        town.setNationUuid(null);
        persist(town);
    }

    private Town requireTown(UUID townId) throws TowniaException {
        Town town = cache.get(townId);
        if (town == null) throw new TowniaException("error.town-not-found");
        return town;
    }

    private void persist(Town town) {
        cache.put(town.getId(), town);
        nameIndex.put(town.getName().toLowerCase(), town.getId());
        try {
            db.saveTown(town);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save town " + town.getName(), e);
        }
    }
}

