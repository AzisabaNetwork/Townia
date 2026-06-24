package net.azisaba.townia.listener

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.Town
import net.azisaba.townia.manager.PlotManager
import net.azisaba.townia.manager.TownManager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import java.util.*

class PlayerMoveListener(private val plugin: Townia) : Listener {
    private val plotManager: PlotManager
    private val townManager: TownManager

    init {
        this.plotManager = plugin.plotManager
        this.townManager = plugin.townManager
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        if (event.getTo() == null) return

        val fromChunk = event.getFrom().getChunk()
        val toChunk = event.getTo().getChunk()

        if (fromChunk.getX() == toChunk.getX() && fromChunk.getZ() == toChunk.getZ()) {
            return
        }

        val player = event.getPlayer()

        val toPlotOpt: Optional<Plot> = plotManager.getPlot(toChunk)
        val fromPlotOpt: Optional<Plot> = plotManager.getPlot(fromChunk)

        if (toPlotOpt.isEmpty()) {
            plugin.messageManager!!.sendActionBar(player, "town.actionbar-wilderness")
        } else {
            val townOpt: Optional<Town> = townManager.getTown(toPlotOpt.get().townUuid)
            if (townOpt.isPresent()) {
                val town: Town = townOpt.get()
                val mayorName: String? = plugin.residentManager.getResident(town.mayorUuid)
                    .map({ r -> r.name })
                    .orElse("Unknown")
                plugin.messageManager!!.sendActionBar(
                    player, "town.actionbar-town",
                    "town", town.name!!,
                    "mayor", mayorName!!
                )
            }
        }
    }
}