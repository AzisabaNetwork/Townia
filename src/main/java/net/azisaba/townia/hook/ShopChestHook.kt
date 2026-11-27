package net.azisaba.townia.hook

import de.epiceric.shopchest.ShopChest
import de.epiceric.shopchest.event.ShopCreateEvent
import net.azisaba.townia.Townia
import net.azisaba.townia.command.TowniaAdminCommand
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.listener.PlotProtectionListener
import net.azisaba.townia.listener.PlotProtectionListener.ActionType
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

object ShopChestHook {
    @Volatile
    internal var shopCheckerOverride: ((Location) -> Boolean)? = null

    fun isShopChestBlock(location: Location): Boolean {
        shopCheckerOverride?.let { return it(location) }
        if (!Bukkit.getPluginManager().isPluginEnabled("ShopChest")) return false
        return ShopChest.getInstance().shopUtils.getShop(location) != null
    }

    fun canCreateShop(
        plugin: Townia,
        player: Player,
        location: Location,
        protectionListener: PlotProtectionListener
    ): Boolean {
        val plot = plugin.plotManager.getPlotAt(location)
        if (plot == null) {
            if (protectionListener.handleAction(player, location, ActionType.BUILD)) {
                plugin.messageManager.sendMessage(player, "protection.build-denied")
                return false
            }
            return true
        }

        if (TowniaAdminCommand.isBypassing(player) || player.hasPermission("shopchest.create.protected")) {
            return true
        }

        if (plot.plotType != PlotType.SHOP) {
            plugin.messageManager.sendMessage(player, "protection.shop-plot-required")
            return false
        }

        if (protectionListener.handleAction(player, location, ActionType.BUILD)) {
            plugin.messageManager.sendMessage(player, "protection.build-denied")
            return false
        }
        return true
    }

    fun register(plugin: Townia, protectionListener: PlotProtectionListener) {
        if (plugin.server.pluginManager.isPluginEnabled("ShopChest")) {
            hook(plugin, protectionListener)
            return
        }
        // On a full server start ShopChest is enabled after Townia (the softdepend does not win
        // against the order the server picks there), so SHOP plots were only wired up after a
        // manual reload of Townia. A task scheduled now runs once every plugin is enabled.
        if (plugin.server.pluginManager.getPlugin("ShopChest") == null) return
        runCatching {
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (plugin.server.pluginManager.isPluginEnabled("ShopChest")) hook(plugin, protectionListener)
            })
        }
    }

    private fun hook(plugin: Townia, protectionListener: PlotProtectionListener) {
        plugin.server.pluginManager.registerEvents(ShopChestListener(plugin, protectionListener), plugin)
        plugin.logger.info("Hooked into ShopChest for SHOP plot integration.")
    }

    private class ShopChestListener(
        private val plugin: Townia,
        private val protectionListener: PlotProtectionListener
    ) : Listener {
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        fun onShopCreate(event: ShopCreateEvent) {
            val player = event.player as? Player ?: return
            if (!canCreateShop(plugin, player, event.shop.location, protectionListener)) {
                event.isCancelled = true
            }
        }
    }
}
