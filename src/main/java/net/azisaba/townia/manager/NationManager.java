package net.azisaba.townia.manager;

import net.azisaba.townia.Townia;
import net.azisaba.townia.TowniaException;
import net.azisaba.townia.data.Nation;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.database.DatabaseManager;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class NationManager {

    private final Townia plugin;
    private final DatabaseManager db;
    private final TownManager townManager;

    private final Map<UUID, Nation> cache = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();

    public NationManager(Townia plugin, DatabaseManager db, TownManager townManager) {
        this.plugin = plugin;
        this.db = db;
        this.townManager = townManager;
        loadAll();
    }

    public void cacheNation(Nation nation) { cache.put(nation.getId(), nation); nameIndex.put(nation.getName().toLowerCase(), nation.getId()); }

    private void loadAll() {
        cache.clear();
        nameIndex.clear();
        try {
            for (Nation n : db.getAllNations()) {
                cache.put(n.getId(), n);
                nameIndex.put(n.getName().toLowerCase(), n.getId());
            }
            Map<UUID, Map<UUID, String>> relations = db.getAllNationRelations();
            for (Map.Entry<UUID, Map<UUID, String>> entry : relations.entrySet()) {
                Nation n = cache.get(entry.getKey());
                if (n != null) {
                    for (Map.Entry<UUID, String> rel : entry.getValue().entrySet()) {
                        if ("ALLY".equalsIgnoreCase(rel.getValue())) {
                            n.getAllies().add(rel.getKey());
                        } else if ("ENEMY".equalsIgnoreCase(rel.getValue())) {
                            n.getEnemies().add(rel.getKey());
                        }
                    }
                }
            }
            plugin.getLogger().info("Loaded " + cache.size() + " nations and relations.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load nations from database", e);
        }
    }

    public Optional<Nation> getNation(UUID id) {
        return Optional.ofNullable(cache.get(id));
    }

    public Optional<Nation> getNationByName(String name) {
        UUID id = nameIndex.get(name.toLowerCase());
        if (id == null) return Optional.empty();
        return Optional.ofNullable(cache.get(id));
    }

    public List<Nation> getAllNations() {
        List<Nation> list = new ArrayList<>(cache.values());
        list.sort(Comparator.comparing(Nation::getName));
        return list;
    }

    public boolean nationExists(String name) {
        return nameIndex.containsKey(name.toLowerCase());
    }

    public List<Town> getTownsInNation(UUID nationId) {
        List<Town> list = new ArrayList<>();
        for (Town t : townManager.getAllTowns()) {
            if (nationId.equals(t.getNationUuid())) list.add(t);
        }
        return list;
    }

    public void createNation(String name, UUID capitalTownUuid, UUID leaderUuid) throws TowniaException {
        if (nationExists(name)) throw new TowniaException("nation.already-exists", "{nation}", name);

        Town capital = townManager.getTown(capitalTownUuid)
                .orElseThrow(() -> new TowniaException("error.town-not-found"));
        if (capital.isInNation()) throw new TowniaException("town.already-in-nation");

        Nation nation = new Nation(UUID.randomUUID(), name, capitalTownUuid, leaderUuid, 0.0, null, plugin.getTowniaConfig().getDefaultNationTax());
        persist(nation);
        townManager.setNation(capitalTownUuid, nation.getId());
    }

    public void deleteNation(UUID nationId) throws TowniaException {
        Nation nation = requireNation(nationId);
        for (Town town : getTownsInNation(nationId)) {
            try { townManager.clearNation(town.getId()); } catch (TowniaException ignored) {}
        }
        cache.remove(nationId);
        nameIndex.remove(nation.getName().toLowerCase());
        try {
            db.deleteNation(nationId);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete nation " + nation.getName(), e);
            throw new TowniaException("error.database");
        }
    }

    public void addTownToNation(UUID nationId, UUID townId) throws TowniaException {
        requireNation(nationId);
        Town town = townManager.getTown(townId).orElseThrow(() -> new TowniaException("error.town-not-found"));
        if (town.isInNation()) throw new TowniaException("town.already-in-nation");
        townManager.setNation(townId, nationId);
    }

    public void removeTownFromNation(UUID nationId, UUID townId) throws TowniaException {
        Nation nation = requireNation(nationId);
        Town town = townManager.getTown(townId).orElseThrow(() -> new TowniaException("error.town-not-found"));
        if (!nationId.equals(town.getNationUuid())) throw new TowniaException("town.not-in-nation");
        townManager.clearNation(townId);

        if (townId.equals(nation.getCapitalTownUuid())) {
            deleteNation(nationId);
        }
    }

    public void addBalance(UUID nationId, double amount) throws TowniaException {
        Nation nation = requireNation(nationId);
        nation.setBalance(nation.getBalance() + amount);
        persist(nation);
    }

    public void subtractBalance(UUID nationId, double amount) throws TowniaException {
        Nation nation = requireNation(nationId);
        if (nation.getBalance() < amount) throw new TowniaException("nation.withdraw-insufficient");
        nation.setBalance(nation.getBalance() - amount);
        persist(nation);
    }

    private Nation requireNation(UUID nationId) throws TowniaException {
        Nation n = cache.get(nationId);
        if (n == null) throw new TowniaException("error.nation-not-found");
        return n;
    }

    public void saveNation(Nation nation) {
        persist(nation);
    }

    private void persist(Nation nation) {
        cache.put(nation.getId(), nation);
        nameIndex.put(nation.getName().toLowerCase(), nation.getId());
        try {
            db.saveNation(nation);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save nation " + nation.getName(), e);
        }
    }
}

