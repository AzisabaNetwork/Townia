package net.azisaba.townia.listener

import net.azisaba.townia.Townia
import net.azisaba.townia.manager.ResidentManager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class PlayerJoinListener(private val plugin: Townia) : Listener {
    private val residentManager: ResidentManager

    init {
        this.residentManager = plugin.residentManager
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.getPlayer()
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                residentManager.getOrCreate(player)
            } catch (e: Exception) {
                plugin.getLogger().severe("Failed to load/create resident for " + player.name + ": " + e.message)
            }
        })
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.getPlayer()
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                residentManager.updateLastSeen(player.getUniqueId())
            } catch (e: Exception) {
                plugin.getLogger().severe("Failed to update last-seen for " + player.name + ": " + e.message)
            }
        })
    }
}