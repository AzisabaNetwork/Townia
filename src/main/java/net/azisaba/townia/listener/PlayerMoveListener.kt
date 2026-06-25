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
    private val plotManager: PlotManager = plugin.plotManager
    private val townManager: TownManager = plugin.townManager

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {

        val fromX = event.from.blockX shr 4
        val fromZ = event.from.blockZ shr 4
        val toX = event.to.blockX shr 4
        val toZ = event.to.blockZ shr 4

        if (fromX == toX && fromZ == toZ) {
            return
        }

        if (!plugin.towniaConfig.isWorldAllowed(event.to.world.name)) {
            return
        }

        val player = event.getPlayer()

        val toPlotOpt: Optional<Plot> = plotManager.getPlot(event.to.world.name, toX, toZ)
        val fromPlotOpt: Optional<Plot> = plotManager.getPlot(event.from.world.name, fromX, fromZ)

        val fromTownId = if (fromPlotOpt.isPresent) fromPlotOpt.get().townUuid else null
        val toTownId = if (toPlotOpt.isPresent) toPlotOpt.get().townUuid else null

        if (fromTownId == toTownId) {
            return
        }

        if (toPlotOpt.isEmpty()) {
            net.azisaba.townia.manager.ActionBarTask.sendActionBar(plugin, player)
        } else {
            val townOpt: Optional<Town> = townManager.getTown(toPlotOpt.get().townUuid)
            if (townOpt.isPresent) {
                val town: Town = townOpt.get()
                val mayorName: String? = plugin.residentManager.getResident(town.mayorUuid)
                    .map({ r -> r.name })
                    .orElse("Unknown")
                
                var nationPrefix = ""
                if (town.nationUuid != null) {
                    val nation = plugin.nationManager.getNation(town.nationUuid!!).orElse(null)
                    if (nation != null) {
                        nationPrefix = "[${nation.name}] "
                    }
                }

                val boardMsg = town.board ?: ""
                net.azisaba.townia.manager.ActionBarTask.sendActionBar(plugin, player)
            }
        }
    }
}