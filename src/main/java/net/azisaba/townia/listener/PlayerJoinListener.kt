package net.azisaba.townia.listener

import net.azisaba.townia.Townia
import net.azisaba.townia.manager.ResidentManager
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*

class PlayerJoinListener(private val plugin: Townia) : Listener {
    private val residentManager: ResidentManager = plugin.residentManager

    @EventHandler(priority = EventPriority.NORMAL)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.getPlayer()
        val uuid = player.uniqueId
        val name = player.name
        // getOrCreate only touches in-memory caches (database writes are queued asynchronously by the
        // manager), so run it on the main thread: the resident then exists before any other handler or
        // command looks it up, which previously raced with an asynchronous creation.
        try {
            val resident = residentManager.getOrCreate(uuid, name)
            // A rejoining player is a new Player object: drop the old attachment, then grant afresh.
            plugin.townyPerms.forget(player)
            plugin.townyPerms.refresh(player)
            if (resident.jailedTownUuid != null) {
                if (resident.jailReleaseAt > 0L && resident.jailReleaseAt <= System.currentTimeMillis()) {
                    resident.jailedTownUuid = null
                    resident.jailReleaseAt = 0L
                    resident.jailBail = 0.0
                    residentManager.saveResident(resident)
                } else {
                    val town = plugin.townManager.getTown(resident.jailedTownUuid).orElse(null)
                    val world = if (town?.jailWorld != null) plugin.server.getWorld(town.jailWorld!!) else null
                    if (town != null && world != null) {
                        player.teleport(Location(world, town.jailX, town.jailY, town.jailZ, town.jailYaw, town.jailPitch))
                        plugin.messageManager.sendMessage(player, "jail.still-jailed")
                    }
                }
            }
            PlayerMoveListener.evictIfOutlawed(plugin, player)
            if (plugin.towniaConfig.rules.boardOnLogin) showBoards(player, resident)
        } catch (e: Exception) {
            plugin.logger.severe("Failed to load/create resident for $name: ${e.message}")
        }
    }

    /** Towny's display_board_onlogin: the town's and the nation's board greet their members. */
    private fun showBoards(player: org.bukkit.entity.Player, resident: net.azisaba.townia.data.TowniaPlayer) {
        val town = resident.townUuid?.let { plugin.townManager.getTown(it).orElse(null) } ?: return
        town.board?.takeIf { it.isNotBlank() }?.let {
            plugin.messageManager.sendMessage(player, "town.board-login", "town", town.name ?: "", "board", it)
        }
        val nation = town.nationUuid?.let { plugin.nationManager.getNation(it).orElse(null) } ?: return
        nation.board?.takeIf { it.isNotBlank() }?.let {
            plugin.messageManager.sendMessage(player, "nation.board-login", "nation", nation.name ?: "", "board", it)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.getPlayer()
        val uuid: UUID = player.uniqueId
        val name = player.name
        net.azisaba.townia.manager.ActionBarTask.forget(uuid)
        plugin.townyPerms.forget(player)
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                residentManager.updateLastSeen(uuid)
            } catch (e: Exception) {
                plugin.logger.severe("Failed to update last-seen for $name: ${e.message}")
            }
        })
    }
}
