package net.azisaba.townia.listener

import net.azisaba.townia.Townia
import net.azisaba.townia.manager.ResidentManager
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

class PlayerJoinListener(private val plugin: Townia) : Listener {
    private val residentManager: ResidentManager = plugin.residentManager

    @EventHandler(priority = EventPriority.NORMAL)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.getPlayer()
        val uuid = player.uniqueId
        val name = player.name
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val resident = residentManager.getOrCreate(uuid, name)
                if (resident.jailedTownUuid != null) {
                    if (resident.jailReleaseAt > 0L && resident.jailReleaseAt <= System.currentTimeMillis()) {
                        resident.jailedTownUuid = null
                        resident.jailReleaseAt = 0L
                        resident.jailBail = 0.0
                        residentManager.saveResident(resident)
                    } else {
                        plugin.server.scheduler.runTask(plugin, Runnable {
                            val town = plugin.townManager.getTown(resident.jailedTownUuid).orElse(null)
                            val world = if (town?.jailWorld != null) plugin.server.getWorld(town.jailWorld!!) else null
                            if (town != null && world != null) {
                                val online = plugin.server.getPlayer(uuid)
                                if (online != null && online.isOnline) {
                                    online.teleport(Location(world, town.jailX, town.jailY, town.jailZ, town.jailYaw, town.jailPitch))
                                    plugin.messageManager.sendMessage(online, "jail.still-jailed")
                                }
                            }
                        })
                    }
                }
            } catch (e: Exception) {
                plugin.logger.severe("Failed to load/create resident for $name: ${e.message}")
            }
        })
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.getPlayer()
        val uuid: UUID = player.uniqueId
        val name = player.name
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                residentManager.updateLastSeen(uuid)
            } catch (e: Exception) {
                plugin.logger.severe("Failed to update last-seen for $name: ${e.message}")
            }
        })
    }
}
