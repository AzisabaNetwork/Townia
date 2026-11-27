package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.azisaba.townia.TowniaException
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.Town
import net.azisaba.townia.database.DatabaseManager
import net.azisaba.townia.util.ChunkKey
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import java.sql.SQLException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

class PlotManager(private val plugin: Townia, private val db: DatabaseManager, private val townManager: TownManager) {

    private val cache: ConcurrentHashMap<ChunkKey, Plot> = ConcurrentHashMap<ChunkKey, Plot>()

    /**
     * Plots of each town, kept in step with [cache] so counting a town's land never scans every plot.
     * Keyed by chunk rather than by the plot object: a plot's fields change while it sits in here.
     */
    private val byTown: ConcurrentHashMap<UUID, ConcurrentHashMap<ChunkKey, Plot>> = ConcurrentHashMap()

    init {
        loadAll()
    }

    private fun put(key: ChunkKey, plot: Plot) {
        val previous = cache.put(key, plot)
        if (previous != null && previous.townUuid != plot.townUuid) unindex(key, previous)
        plot.townUuid?.let { byTown.computeIfAbsent(it) { ConcurrentHashMap() }[key] = plot }
    }

    private fun drop(key: ChunkKey?): Plot? {
        val previous = cache.remove(key ?: return null) ?: return null
        unindex(key, previous)
        return previous
    }

    private fun unindex(key: ChunkKey, plot: Plot) {
        val town = plot.townUuid ?: return
        val plots = byTown[town] ?: return
        plots.remove(key)
        if (plots.isEmpty()) byTown.remove(town, plots)
    }

    fun cachePlot(plot: Plot) {
        val key = ChunkKey(plot.worldName, plot.chunkX, plot.chunkZ)
        put(key, plot)
    }

    private fun loadAll() {
        cache.clear()
        byTown.clear()
        try {
            for (p in db.allPlots) {
            if (p == null) continue
                put(ChunkKey(p.worldName, p.chunkX, p.chunkZ), p)
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

    /** Looks up the plot containing a block position without materialising a [Chunk] object. */
    fun getPlotAt(world: World, blockX: Int, blockZ: Int): Plot? {
        return cache[ChunkKey(world.name, blockX shr 4, blockZ shr 4)]
    }

    fun getPlotAt(location: Location): Plot? = getPlotAt(location.world, location.blockX, location.blockZ)

    fun isClaimed(chunk: Chunk?): Boolean {
        return cache.containsKey(ChunkKey.of(chunk!!))
    }

    fun isClaimed(worldName: String?, chunkX: Int, chunkZ: Int): Boolean {
        return cache.containsKey(ChunkKey.of(worldName, chunkX, chunkZ))
    }

    fun getPlotsByTown(townId: UUID): MutableList<Plot> = ArrayList(byTown[townId]?.values ?: emptyList())

    fun countPlotsByTown(townId: UUID): Int = byTown[townId]?.size ?: 0

    fun countPlotsByOwner(ownerUuid: UUID): Int {
        var n = 0
        for (p in cache.values) if (ownerUuid == p.ownerUuid) n++
        return n
    }

    fun getPlotsByOwner(ownerUuid: UUID): MutableList<Plot> {
        val list = ArrayList<Plot>()
        for (plot in cache.values) {
            if (ownerUuid == plot.ownerUuid) list.add(plot)
        }
        return list
    }


    @Throws(TowniaException::class)
    fun claimChunk(townId: UUID, chunk: Chunk, outpost: Boolean = false) {
        val worldName = chunk.world.name
        if (!plugin.towniaConfig.isWorldAllowed(worldName)) {
            throw TowniaException("error.wrong-world", "{world}", worldName)
        }

        val key: ChunkKey = ChunkKey.of(chunk)
        if (cache.containsKey(key)) {
            throw TowniaException("town.already-claimed")
        }

        val town: Town = townManager.getTown(townId)
            .orElseThrow { TowniaException("error.town-not-found", "town", townId.toString()) }

        checkClaimDistance(townId, town, worldName, chunk.x, chunk.z, outpost)

        // Towny requires a normal claim to touch the town's existing land; outposts and the first claim are exempt.
        if (!outpost && !isAdjacentToTown(townId, worldName, chunk.x, chunk.z) && countPlotsByTown(townId) > 0) {
            throw TowniaException("town.claim-not-adjacent")
        }

        val currentClaims = countPlotsByTown(townId)
        val limit: Int = town.totalClaimLimit
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
        put(key, plot)
        try {
            db.savePlot(plot)
        } catch (e: SQLException) {
            drop(key)
            plugin.logger.log(Level.SEVERE, "Failed to save plot", e)
            throw TowniaException("error.database")
        }
    }

    /**
     * Towny's spacing rules, in chunks: land may not come too close to another town's land or
     * home block, and an outpost may not sit close to any town's land (its own included).
     * Only the chunks within reach are looked at, so the cost does not grow with the server.
     */
    @Throws(TowniaException::class)
    private fun checkClaimDistance(townId: UUID, town: Town, worldName: String, x: Int, z: Int, outpost: Boolean) {
        val rules = plugin.towniaConfig.rules
        val reach = maxOf(rules.minDistanceFromTownPlot, if (outpost) rules.minDistanceForOutpost else 0)
        if (reach > 0) {
            for (dx in -reach..reach) {
                for (dz in -reach..reach) {
                    val other = cache[ChunkKey.of(worldName, x + dx, z + dz)] ?: continue
                    val distance = Math.sqrt((dx * dx + dz * dz).toDouble())
                    val foreign = other.townUuid != townId
                    if (foreign && distance < rules.minDistanceFromTownPlot) {
                        throw TowniaException("town.claim-too-close-plot", "{distance}", rules.minDistanceFromTownPlot.toString())
                    }
                    if (outpost && distance < rules.minDistanceForOutpost) {
                        throw TowniaException("town.outpost-too-close", "{distance}", rules.minDistanceForOutpost.toString())
                    }
                }
            }
        }
        val homeReach = rules.minDistanceFromTownHomeblock
        if (homeReach > 0) {
            for (other in townManager.allTowns) {
                if (other.id == townId || !other.hasHomeBlock() || other.homeBlockWorld != worldName) continue
                val dx = (other.homeBlockX - x).toDouble()
                val dz = (other.homeBlockZ - z).toDouble()
                if (Math.sqrt(dx * dx + dz * dz) < homeReach) {
                    throw TowniaException("town.claim-too-close-homeblock", "{distance}", homeReach.toString())
                }
            }
        }
    }

    private fun isAdjacentToTown(townId: UUID, worldName: String, x: Int, z: Int): Boolean {
        return ADJACENT.any { (dx, dz) ->
            cache[ChunkKey.of(worldName, x + dx, z + dz)]?.townUuid == townId
        }
    }

    @Throws(TowniaException::class)
    fun forceClaimChunk(townId: UUID?, chunk: Chunk) {
        val key: ChunkKey = ChunkKey.of(chunk)
        val previous = cache[key]
        if (previous != null) {
            try {
                db.deletePlot(chunk.world.name, chunk.x, chunk.z)
            } catch (e: SQLException) {
                plugin.logger.log(Level.WARNING, "Error clearing old plot", e)
            }
            drop(key)
            cleanupTownReferences(previous.townUuid, chunk.world.name, chunk.x, chunk.z)
        }
        val plot = Plot(
            chunk.world.name, chunk.x, chunk.z,
            townId, null, PlotType.DEFAULT, false, 0.0,
            null, pvp = false, mobs = false, explosions = false, fire = false
        )
        put(key, plot)
        try {
            db.savePlot(plot)
        } catch (_: SQLException) {
            drop(key)
            throw TowniaException("error.database")
        }
    }

    @Throws(TowniaException::class)
    fun unclaimChunk(townId: UUID, chunk: Chunk) {
        unclaimChunk(townId, chunk.world.name, chunk.x, chunk.z)
    }

    @Throws(TowniaException::class)
    fun unclaimChunk(townId: UUID, worldName: String, chunkX: Int, chunkZ: Int) {
        val key: ChunkKey = ChunkKey.of(worldName, chunkX, chunkZ)
        val plot: Plot = cache[key] ?: throw TowniaException("town.chunk-not-claimed")
        if (townId != plot.townUuid) throw TowniaException("town.chunk-not-owned")

        drop(key)
        try {
            db.deletePlot(worldName, chunkX, chunkZ)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to delete plot", e)
            throw TowniaException("error.database")
        }
        cleanupTownReferences(townId, worldName, chunkX, chunkZ)
    }

    private fun cleanupTownReferences(townId: UUID?, worldName: String?, chunkX: Int, chunkZ: Int) {
        val town = townManager.getTown(townId).orElse(null) ?: return
        if (town.homeBlockWorld == worldName && town.homeBlockX == chunkX && town.homeBlockZ == chunkZ) {
            town.setHomeBlock(null, 0, 0)
            townManager.saveTown(town)
        }
        for (outpost in town.outposts.filterNotNull()) {
            if (outpost.world == worldName && Math.floorDiv(outpost.x.toInt(), 16) == chunkX &&
                Math.floorDiv(outpost.z.toInt(), 16) == chunkZ
            ) {
                try {
                    townManager.removeTownOutpost(town.id, outpost.id)
                } catch (_: TowniaException) {
                }
            }
        }
    }

    val allPlots: List<Plot>
        get() = ArrayList(cache.values)

    /** Deletes a plot whatever town owns it (used by migration pruning). Returns false if there was none. */
    fun removePlot(worldName: String, chunkX: Int, chunkZ: Int): Boolean {
        val key = ChunkKey.of(worldName, chunkX, chunkZ)
        val plot = cache[key] ?: return false
        try {
            db.deletePlot(worldName, chunkX, chunkZ)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to delete plot $worldName ($chunkX, $chunkZ)", e)
            return false
        }
        drop(key)
        cleanupTownReferences(plot.townUuid, worldName, chunkX, chunkZ)
        return true
    }

    fun removeTownPlots(townId: UUID) {
        for (plot in getPlotsByTown(townId)) drop(ChunkKey(plot.worldName, plot.chunkX, plot.chunkZ))
        byTown.remove(townId)
    }

    /** Hands back the plots [ownerUuid] holds, in [townId] only when one is given. */
    fun releaseOwnedPlots(ownerUuid: UUID, townId: UUID? = null) {
        // One town's plots are at hand; only "everywhere" has to look at every plot.
        for (plot in if (townId != null) getPlotsByTown(townId) else getPlotsByOwner(ownerUuid)) {
            if (plot.ownerUuid != ownerUuid) continue
            plot.changeOwner(null)
            plot.isForSale = false
            plot.price = 0.0
            persistPlot(plot)
        }
    }

    @Throws(TowniaException::class)
    fun forceUnclaimChunk(chunk: Chunk) {
        val key: ChunkKey? = ChunkKey.of(chunk)
        if (!cache.containsKey(key)) throw TowniaException("town.chunk-not-claimed")
        val previousTown = cache[key]?.townUuid
        drop(key)
        try {
            db.deletePlot(chunk.world.name, chunk.x, chunk.z)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to delete plot", e)
            throw TowniaException("error.database")
        }
        cleanupTownReferences(previousTown, chunk.world.name, chunk.x, chunk.z)
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
        plot.changeOwner(newOwnerUuid)
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
        put(ChunkKey(plot.worldName, plot.chunkX, plot.chunkZ), plot)
        try {
            db.savePlot(plot)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to save plot", e)
        }
    }

    companion object {
        private val ADJACENT = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
