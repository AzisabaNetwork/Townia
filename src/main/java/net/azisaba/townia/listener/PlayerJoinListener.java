package net.azisaba.townia.listener;

import net.azisaba.townia.Townia;
import net.azisaba.townia.manager.ResidentManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerJoinListener implements Listener {

    private final Townia plugin;
    private final ResidentManager residentManager;

    public PlayerJoinListener(Townia plugin) {
        this.plugin = plugin;
        this.residentManager = plugin.getResidentManager();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                residentManager.getOrCreate(player);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load/create resident for " + player.getName() + ": " + e.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                residentManager.updateLastSeen(player.getUniqueId());
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to update last-seen for " + player.getName() + ": " + e.getMessage());
            }
        });
    }
}
