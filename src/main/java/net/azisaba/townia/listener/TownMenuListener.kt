package net.azisaba.townia.listener

import net.azisaba.townia.Townia
import net.azisaba.townia.ui.TownMenuUi
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent

class TownMenuListener(private val plugin: Townia) : Listener {
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        TownMenuUi.handleClick(plugin, event)
    }
}
