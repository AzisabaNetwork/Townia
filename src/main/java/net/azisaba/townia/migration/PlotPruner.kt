package net.azisaba.townia.migration

import net.azisaba.townia.Townia
import net.azisaba.townia.util.ChunkKey

/**
 * Removes Townia plots that Towny no longer claims, so a re-migration brings
 * the land back to Towny's final state instead of keeping chunks a town gave
 * up after an older migration snapshot.
 */
internal object PlotPruner {
    fun prune(plugin: Townia, townyChunks: Set<ChunkKey>): Int {
        if (townyChunks.isEmpty()) {
            plugin.logger.warning("Towny migration: no Towny town blocks were read, so no plot was pruned.")
            return 0
        }
        // Only worlds Towny has data for: a world Towny never knew may hold Townia-only claims.
        val worlds = townyChunks.mapNotNull { it.world }.toSet()
        val inWorlds = plugin.plotManager.allPlots.filter { it.worldName in worlds }
        val stale = inWorlds.filter { ChunkKey(it.worldName, it.chunkX, it.chunkZ) !in townyChunks }
        // A wrong data folder or an unreadable format would make almost every plot look stale.
        if (stale.size * 2 > inWorlds.size) {
            plugin.logger.warning(
                "Towny migration: ${stale.size} of ${inWorlds.size} plots are missing from the Towny data; " +
                    "that looks like the wrong data, so pruning was skipped."
            )
            return 0
        }
        var removed = 0
        for (plot in stale) {
            val world = plot.worldName ?: continue
            if (plugin.plotManager.removePlot(world, plot.chunkX, plot.chunkZ)) removed++
        }
        plugin.logger.info("Towny migration: pruned $removed plots that Towny no longer claims (worlds: ${worlds.joinToString()}).")
        return removed
    }
}
