package net.azisaba.townia.listener

import net.azisaba.townia.Townia
import net.azisaba.townia.command.TowniaAdminCommand
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.Town
import net.azisaba.townia.manager.ActionBarTask
import net.azisaba.townia.manager.PlotManager
import net.azisaba.townia.manager.TownManager
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.*

class PlayerMoveListener(private val plugin: Townia) : Listener {
    private val plotManager: PlotManager = plugin.plotManager
    private val townManager: TownManager = plugin.townManager

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {

        val fromX = event.from.blockX shr 4
        val fromZ = event.from.blockZ shr 4
        val toX = event.to.blockX shr 4
        val toZ = event.to.blockZ shr 4

        if (event.from.world.name == event.to.world.name && fromX == toX && fromZ == toZ) {
            return
        }

        if (!plugin.towniaConfig.isWorldAllowed(event.to.world.name)) {
            return
        }

        val player = event.getPlayer()
        val resident = plugin.residentManager.getResident(player.uniqueId).orElse(null)

        val toPlotOpt: Optional<Plot> = plotManager.getPlot(event.to.world.name, toX, toZ)
        val fromPlotOpt: Optional<Plot> = plotManager.getPlot(event.from.world.name, fromX, fromZ)

        if (resident != null && resident.jailedTownUuid != null) {
            if (resident.jailReleaseAt > 0L && resident.jailReleaseAt <= System.currentTimeMillis()) {
                resident.jailedTownUuid = null
                resident.jailReleaseAt = 0L
                resident.jailBail = 0.0
                plugin.residentManager.saveResident(resident)
                plugin.messageManager.sendMessage(player, "jail.released")
            } else {
                val toTownIdForJail = if (toPlotOpt.isPresent) toPlotOpt.get().townUuid else null
                if (toTownIdForJail != resident.jailedTownUuid) {
                    event.isCancelled = true
                    val jailTown = townManager.getTown(resident.jailedTownUuid).orElse(null)
                    val world = if (jailTown?.jailWorld != null) plugin.server.getWorld(jailTown.jailWorld!!) else null
                    if (jailTown != null && world != null) {
                        player.teleport(Location(world, jailTown.jailX, jailTown.jailY, jailTown.jailZ, jailTown.jailYaw, jailTown.jailPitch))
                    }
                    plugin.messageManager.sendMessage(player, "jail.cannot-leave")
                    return
                }
            }
        }

        if (toPlotOpt.isPresent) {
            val toTown = getOutlawedTown(plugin, player, toPlotOpt.get())
            if (toTown != null) {
                event.isCancelled = true
                val alreadyInsideTown = fromPlotOpt.isPresent && fromPlotOpt.get().townUuid == toTown.id
                if (alreadyInsideTown) {
                    player.teleport(player.world.spawnLocation)
                } else {
                    player.teleport(event.from)
                }
                plugin.messageManager.sendMessage(player, "outlaw.entry-denied", "town", toTown.name ?: "Unknown")
                return
            }
        }

        val fromTownId = if (fromPlotOpt.isPresent) fromPlotOpt.get().townUuid else null
        val toTownId = if (toPlotOpt.isPresent) toPlotOpt.get().townUuid else null

        if (fromTownId != toTownId || fromPlotOpt.orElse(null) != toPlotOpt.orElse(null)) {
            ActionBarTask.sendActionBar(plugin, player, event.to)
        }
    }

    /** PlayerMoveEvent does not fire for teleports (separate HandlerList), so outlaws are also stopped here. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val to = event.to
        if (!plugin.towniaConfig.isWorldAllowed(to.world.name)) return
        val plot = plotManager.getPlot(to.world.name, to.blockX shr 4, to.blockZ shr 4).orElse(null) ?: return
        val town = getOutlawedTown(plugin, event.player, plot) ?: return
        event.isCancelled = true
        plugin.messageManager.sendMessage(event.player, "outlaw.entry-denied", "town", town.name ?: "Unknown")
    }

    companion object {
        const val OUTLAW_BYPASS_PERMISSION = "townia.outlaw.bypass"

        /** The town owning [plot] if [player] is an outlaw there and may not enter it, otherwise null. */
        fun getOutlawedTown(plugin: Townia, player: Player, plot: Plot): Town? {
            val town = plugin.townManager.getTown(plot.townUuid).orElse(null) ?: return null
            if (!town.outlaws.contains(player.uniqueId)) return null
            val resident = plugin.residentManager.getResident(player.uniqueId).orElse(null)
            if (resident?.townUuid == town.id) return null
            // A town may still put its outlaws in its own jail.
            if (resident?.jailedTownUuid == town.id) return null
            if (TowniaAdminCommand.isBypassing(player) || player.hasPermission(OUTLAW_BYPASS_PERMISSION)) return null
            return town
        }

        /** Teleports [player] to their world spawn if they are currently standing inside an outlawed town. */
        fun evictIfOutlawed(plugin: Townia, player: Player, targetTownId: UUID? = null): Boolean {
            val loc = player.location
            val world = loc.world ?: return false
            if (!plugin.towniaConfig.isWorldAllowed(world.name)) return false
            val plot = plugin.plotManager.getPlot(world.name, loc.blockX shr 4, loc.blockZ shr 4).orElse(null) ?: return false
            if (targetTownId != null && plot.townUuid != targetTownId) return false
            val town = getOutlawedTown(plugin, player, plot) ?: return false
            // Paper refuses to teleport a player who is riding something (boat, horse, minecart).
            if (player.isInsideVehicle) player.leaveVehicle()
            if (!player.teleport(world.spawnLocation)) {
                plugin.logger.warning("Could not evict outlaw ${player.name} from ${town.name}: teleport was cancelled")
            }
            plugin.messageManager.sendMessage(player, "outlaw.entry-denied", "town", town.name ?: "Unknown")
            return true
        }
    }
}
