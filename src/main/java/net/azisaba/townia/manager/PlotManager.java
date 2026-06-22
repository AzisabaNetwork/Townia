package net.azisaba.townia.manager;

import net.azisaba.townia.Townia;
import net.azisaba.townia.TowniaException;
import net.azisaba.townia.data.Plot;
import net.azisaba.townia.data.PlotType;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.database.DatabaseManager;
import net.azisaba.townia.util.ChunkKey;
import org.bukkit.Chunk;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class PlotManager {

    private final Townia plugin;
    private final DatabaseManager db;
    private final TownManager townManager;

    private final Map<ChunkKey, Plot> cache = new ConcurrentHashMap<>();

    public PlotManager(Townia plugin, DatabaseManager db, TownManager townManager) {
        this.plugin = plugin;
        this.db = db;
        this.townManager = townManager;
        loadAll();
    }

    public void cachePlot(Plot plot) { net.azisaba.townia.util.ChunkKey key = new net.azisaba.townia.util.ChunkKey(plot.getWorldName(), plot.getChunkX(), plot.getChunkZ()); cache.put(key, plot); }

    private void loadAll() {
        cache.clear();
        try {
            for (Plot p : db.getAllPlots()) {
                cache.put(new ChunkKey(p.getWorldName(), p.getChunkX(), p.getChunkZ()), p);
            }
            plugin.getLogger().info("Loaded " + cache.size() + " plots.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load plots from database", e);
        }
    }

    public Optional<Plot> getPlot(Chunk chunk) {
        return Optional.ofNullable(cache.get(ChunkKey.of(chunk)));
    }

    public Optional<Plot> getPlot(String worldName, int chunkX, int chunkZ) {
        return Optional.ofNullable(cache.get(ChunkKey.of(worldName, chunkX, chunkZ)));
    }

    public boolean isClaimed(Chunk chunk) {
        return !cache.containsKey(ChunkKey.of(chunk));
    }

    public boolean isClaimed(String worldName, int chunkX, int chunkZ) {
        return cache.containsKey(ChunkKey.of(worldName, chunkX, chunkZ));
    }

    public List<Plot> getPlotsByTown(UUID townId) {
        List<Plot> list = new ArrayList<>();
        for (Plot p : cache.values()) {
            if (townId.equals(p.getTownUuid())) list.add(p);
        }
        return list;
    }

    public int countPlotsByTown(UUID townId) {
        return (int) cache.values().stream().filter(p -> townId.equals(p.getTownUuid())).count();
    }

    public void claimChunk(UUID townId, Chunk chunk) throws TowniaException {
        String worldName = chunk.getWorld().getName();
        if (!plugin.getTowniaConfig().isWorldAllowed(worldName)) {
            throw new TowniaException("error.wrong-world", "{world}", worldName);
        }

        ChunkKey key = ChunkKey.of(chunk);
        if (cache.containsKey(key)) {
            throw new TowniaException("town.already-claimed");
        }

        Town town = townManager.getTown(townId)
                .orElseThrow(() -> new TowniaException("error.town-not-found"));

        int currentClaims = countPlotsByTown(townId);
        int limit = town.getTotalClaimLimit()
                + (plugin.getTowniaConfig().getClaimsPerResident()
                   * plugin.getResidentManager().getResidentsByTown(townId).size());
        if (currentClaims >= limit) {
            throw new TowniaException("town.claim-limit-reached",
                    "{current}", String.valueOf(currentClaims),
                    "{max}", String.valueOf(limit));
        }

        Plot plot = new Plot(worldName, chunk.getX(), chunk.getZ(),
                townId, null, PlotType.DEFAULT, false, 0.0,
                null, false, false, false, false);
        cache.put(key, plot);
        try {
            db.savePlot(plot);
        } catch (SQLException e) {
            cache.remove(key);
            plugin.getLogger().log(Level.SEVERE, "Failed to save plot", e);
            throw new TowniaException("error.database");
        }
    }

    public void forceClaimChunk(UUID townId, Chunk chunk) throws TowniaException {
        ChunkKey key = ChunkKey.of(chunk);
        if (cache.containsKey(key)) {
            try { db.deletePlot(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()); }
            catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Error clearing old plot", e); }
            cache.remove(key);
        }
        Plot plot = new Plot(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(),
                townId, null, PlotType.DEFAULT, false, 0.0,
                null, false, false, false, false);
        cache.put(key, plot);
        try { db.savePlot(plot); } catch (SQLException e) {
            cache.remove(key);
            throw new TowniaException("error.database");
        }
    }

    public void unclaimChunk(UUID townId, Chunk chunk) throws TowniaException {
        ChunkKey key = ChunkKey.of(chunk);
        Plot plot = cache.get(key);
        if (plot == null) throw new TowniaException("town.chunk-not-claimed");
        if (!townId.equals(plot.getTownUuid())) throw new TowniaException("town.chunk-not-owned");

        cache.remove(key);
        try {
            db.deletePlot(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete plot", e);
            throw new TowniaException("error.database");
        }
    }

    public void forceUnclaimChunk(Chunk chunk) throws TowniaException {
        ChunkKey key = ChunkKey.of(chunk);
        if (!cache.containsKey(key)) throw new TowniaException("town.chunk-not-claimed");
        cache.remove(key);
        try {
            db.deletePlot(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete plot", e);
            throw new TowniaException("error.database");
        }
    }

    public void setPlotType(String worldName, int chunkX, int chunkZ, PlotType type) throws TowniaException {
        ChunkKey key = ChunkKey.of(worldName, chunkX, chunkZ);
        Plot plot = requirePlot(key);
        plot.setPlotType(type);
        persistPlot(plot);
    }

    public void setForSale(String worldName, int chunkX, int chunkZ, boolean forSale, double price) throws TowniaException {
        ChunkKey key = ChunkKey.of(worldName, chunkX, chunkZ);
        Plot plot = requirePlot(key);
        plot.setForSale(forSale);
        plot.setPrice(price);
        persistPlot(plot);
    }

    public void transferOwnership(String worldName, int chunkX, int chunkZ, UUID newOwnerUuid) throws TowniaException {
        ChunkKey key = ChunkKey.of(worldName, chunkX, chunkZ);
        Plot plot = requirePlot(key);
        plot.setOwnerUuid(newOwnerUuid);
        plot.setForSale(false);
        plot.setPrice(0);
        persistPlot(plot);
    }

    private Plot requirePlot(ChunkKey key) throws TowniaException {
        Plot p = cache.get(key);
        if (p == null) throw new TowniaException("plot.no-plot-here");
        return p;
    }

    private void persistPlot(Plot plot) {
        cache.put(new ChunkKey(plot.getWorldName(), plot.getChunkX(), plot.getChunkZ()), plot);
        try {
            db.savePlot(plot);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save plot", e);
        }
    }
}

