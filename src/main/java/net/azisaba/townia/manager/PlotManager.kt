package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.azisaba.townia.TowniaException
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.Town
import net.azisaba.townia.database.DatabaseManager
import net.azisaba.townia.util.ChunkKey
import org.bukkit.Chunk
import java.sql.SQLException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

class PlotManager(private val plugin: Townia, private val db: DatabaseManager, private val townManager: TownManager) {

    private val cache: ConcurrentHashMap<ChunkKey, Plot> = ConcurrentHashMap<ChunkKey, Plot>()

    init {
        loadAll()
    }

    fun cachePlot(plot: Plot) {
        val key = ChunkKey(plot.worldName, plot.chunkX, plot.chunkZ)
        cache[key] = plot
    }

    private fun loadAll() {
        cache.clear()
        try {
            for (p in db.allPlots) {
            if (p == null) continue
                cache[ChunkKey(p.worldName, p.chunkX, p.chunkZ)] = p
            }
            plugin.logger.info("Loaded " + cache.size + " plots.")
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to load plots from database", e)
        }
    }

    fun getPlot(chunk: Chunk?): Optional<Plot> {
        return Optional.ofNullable<Plot?>(cache.get(ChunkKey.of(chunk!!)))
    }

    fun getPlot(worldName: String?, chunkX: Int, chunkZ: Int): Optional<Plot> {
        return Optional.ofNullable<Plot?>(cache.get(ChunkKey.of(worldName, chunkX, chunkZ)))
    }

    fun isClaimed(chunk: Chunk?): Boolean {
        return cache.containsKey(ChunkKey.of(chunk!!))
    }

    fun isClaimed(worldName: String?, chunkX: Int, chunkZ: Int): Boolean {
        return cache.containsKey(ChunkKey.of(worldName, chunkX, chunkZ))
    }

    fun getPlotsByTown(townId: UUID): MutableList<Plot?> {
        val list: MutableList<Plot?> = ArrayList<Plot?>()
        for (p in cache.values) {
            if (townId == p.townUuid) list.add(p)
        }
        return list
    }

    fun countPlotsByTown(townId: UUID): Int {
        return cache.values.stream().filter { p: Plot -> townId == p.townUuid }.count().toInt()
    }

    fun countPlotsByOwner(ownerUuid: UUID): Int {
        return cache.values.stream().filter { p: Plot -> ownerUuid == p.ownerUuid }.count().toInt()
    }


    @Throws(TowniaException::class)
    fun claimChunk(townId: UUID, chunk: Chunk) {
        val worldName = chunk.world.name
        if (!plugin.towniaConfig.isWorldAllowed(worldName)) {
            throw TowniaException("error.wrong-world", "{world}", worldName)
        }

        val key: ChunkKey = ChunkKey.of(chunk)
        if (cache.containsKey(key)) {
            throw TowniaException("town.already-claimed")
        }

        val town: Town = townManager.getTown(townId)
            .orElseThrow { TowniaException("error.town-not-found") }

        val currentClaims = countPlotsByTown(townId)
        val limit: Int = (town.totalClaimLimit
                + (plugin.towniaConfig.claimsPerResident
                * plugin.residentManager.getResidentsByTown(townId).size))
        if (currentClaims >= limit) {
            throw TowniaException(
                "town.claim-limit-reached",
                "{current}", currentClaims.toString(),
                "{max}", limit.toString()
            )
        }

        val plot = Plot(
            worldName, chunk.x, chunk.z,
            townId, null, PlotType.DEFAULT, false, 0.0,
            null, pvp = false, mobs = false, explosions = false, fire = false
        )
        cache[key] = plot
        try {
            db.savePlot(plot)
        } catch (e: SQLException) {
            cache.remove(key)
            plugin.logger.log(Level.SEVERE, "Failed to save plot", e)
            throw TowniaException("error.database")
        }
    }

    @Throws(TowniaException::class)
    fun forceClaimChunk(townId: UUID?, chunk: Chunk) {
        val key: ChunkKey = ChunkKey.of(chunk)
        if (cache.containsKey(key)) {
            try {
                db.deletePlot(chunk.world.name, chunk.x, chunk.z)
            } catch (e: SQLException) {
                plugin.logger.log(Level.WARNING, "Error clearing old plot", e)
            }
            cache.remove(key)
        }
        val plot = Plot(
            chunk.world.name, chunk.x, chunk.z,
            townId, null, PlotType.DEFAULT, false, 0.0,
            null, pvp = false, mobs = false, explosions = false, fire = false
        )
        cache[key] = plot
        try {
            db.savePlot(plot)
        } catch (_: SQLException) {
            cache.remove(key)
            throw TowniaException("error.database")
        }
    }

    @Throws(TowniaException::class)
    fun unclaimChunk(townId: UUID, chunk: Chunk) {
        val key: ChunkKey = ChunkKey.of(chunk)
        val plot: Plot = cache[key] ?: throw TowniaException("town.chunk-not-claimed")
        if (townId != plot.townUuid) throw TowniaException("town.chunk-not-owned")

        cache.remove(key)
        try {
            db.deletePlot(chunk.world.name, chunk.x, chunk.z)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to delete plot", e)
            throw TowniaException("error.database")
        }
    }

    @Throws(TowniaException::class)
    fun forceUnclaimChunk(chunk: Chunk) {
        val key: ChunkKey? = ChunkKey.of(chunk)
        if (!cache.containsKey(key)) throw TowniaException("town.chunk-not-claimed")
        cache.remove(key)
        try {
            db.deletePlot(chunk.world.name, chunk.x, chunk.z)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to delete plot", e)
            throw TowniaException("error.database")
        }
    }

    @Throws(TowniaException::class)
    fun setPlotType(worldName: String?, chunkX: Int, chunkZ: Int, type: PlotType?) {
        val key: ChunkKey = ChunkKey.of(worldName, chunkX, chunkZ)
        val plot: Plot = requirePlot(key)
        plot.plotType = type
        persistPlot(plot)
    }

    @Throws(TowniaException::class)
    fun setForSale(worldName: String?, chunkX: Int, chunkZ: Int, forSale: Boolean, price: Double) {
        val key: ChunkKey = ChunkKey.of(worldName, chunkX, chunkZ)
        val plot: Plot = requirePlot(key)
        plot.isForSale = forSale
        plot.price = price
        persistPlot(plot)
    }

    @Throws(TowniaException::class)
    fun transferOwnership(worldName: String?, chunkX: Int, chunkZ: Int, newOwnerUuid: UUID?) {
        val key: ChunkKey = ChunkKey.of(worldName, chunkX, chunkZ)
        val plot: Plot = requirePlot(key)
        plot.ownerUuid = newOwnerUuid
        plot.isForSale = false
        plot.price = 0.toDouble()
        persistPlot(plot)
    }

    @Throws(TowniaException::class)
    private fun requirePlot(key: ChunkKey?): Plot {
        val p: Plot = cache[key] ?: throw TowniaException("plot.no-plot-here")
        return p
    }

    fun persistPlot(plot: Plot) {
        cache[ChunkKey(plot.worldName, plot.chunkX, plot.chunkZ)] = plot
        try {
            db.savePlot(plot)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to save plot", e)
        }
    }
}