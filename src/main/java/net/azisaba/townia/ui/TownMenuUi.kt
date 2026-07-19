package net.azisaba.townia.ui

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Town
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

object TownMenuUi {
    private val mini = MiniMessage.miniMessage()

    fun open(plugin: Townia, player: Player) {
        val resident = plugin.residentManager.getResident(player.uniqueId).orElse(null)
        val town = if (resident?.townUuid != null) plugin.townManager.getTown(resident.townUuid).orElse(null) else null
        val holder = TownMenuHolder()
        val inv = plugin.server.createInventory(holder, 27, Component.text(plugin.messageManager.getPlainMessage(player, "ui.town-menu.title")))
        holder.menuInventory = inv
        if (town == null) {
            inv.setItem(11, item(plugin, player, Material.OAK_SIGN, "ui.town-menu.create-town.name", listOf("ui.town-menu.create-town.lore"), "town new "))
            inv.setItem(13, item(plugin, player, Material.MAP, "ui.town-menu.town-list.name", listOf("ui.town-menu.town-list.lore"), "town list"))
            inv.setItem(15, item(plugin, player, Material.COMPASS, "ui.town-menu.nearby-map.name", listOf("ui.town-menu.nearby-map.lore"), "townia map"))
        } else {
            fillTownMenu(plugin, player, inv, town)
        }
        player.openInventory(inv)
    }

    fun handleClick(plugin: Townia, event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.topInventory.holder !is TownMenuHolder) return
        event.isCancelled = true
        val action = event.currentItem?.itemMeta?.persistentDataContainer?.get(actionKey(plugin), PersistentDataType.STRING) ?: return
        player.closeInventory()
        if (action.endsWith(" ")) {
            plugin.messageManager.sendMessage(player, "ui.town-menu.requires-value", "command", "/$action<value>")
        } else {
            player.performCommand(action)
        }
    }

    private fun fillTownMenu(plugin: Townia, player: Player, inv: Inventory, town: Town) {
        val mayor = plugin.residentManager.getResident(town.mayorUuid).map { it.name ?: "Unknown" }.orElse("Unknown")
        val claims = plugin.townManager.getClaimCount(town.id)
        val residents = plugin.residentManager.getResidentsByTown(town.id!!).size
        val none = plugin.messageManager.getPlainMessage(player, "common.none")
        val nation = if (town.nationUuid != null) plugin.nationManager.getNation(town.nationUuid).map { it.name ?: none }.orElse(none) else none
        inv.setItem(
            4,
            rawItem(
                plugin,
                Material.BELL,
                "<gold>${town.name}",
                listOf(
                    plugin.messageManager.getRawMessage(player, "ui.town-menu.status.mayor", "mayor", mayor),
                    plugin.messageManager.getRawMessage(player, "ui.town-menu.status.nation", "nation", nation),
                    plugin.messageManager.getRawMessage(player, "ui.town-menu.status.residents", "count", residents.toString()),
                    plugin.messageManager.getRawMessage(player, "ui.town-menu.status.claims", "claims", claims.toString(), "max", town.totalClaimLimit.toString())
                ),
                "town info ${town.name}"
            )
        )
        inv.setItem(10, item(plugin, player, Material.ENDER_PEARL, "ui.town-menu.spawn.name", listOf("ui.town-menu.spawn.lore"), "town spawn"))
        inv.setItem(11, item(plugin, player, Material.MAP, "ui.town-menu.map.name", listOf("ui.town-menu.map.lore"), "townia map"))
        inv.setItem(12, item(plugin, player, Material.CHEST, "ui.town-menu.bank.name", listOf("ui.town-menu.bank.balance", "ui.town-menu.bank.lore"), "town bankhistory", "balance", formatMoney(plugin, town.balance)))
        inv.setItem(13, item(plugin, player, Material.PAPER, "ui.town-menu.residents.name", listOf("ui.town-menu.residents.lore"), "town reslist"))
        inv.setItem(14, item(plugin, player, Material.GRASS_BLOCK, "ui.town-menu.plots.name", listOf("ui.town-menu.plots.lore-1", "ui.town-menu.plots.lore-2"), "plot info"))
        inv.setItem(15, item(plugin, player, Material.IRON_BARS, "ui.town-menu.jail.name", listOf("ui.town-menu.jail.set", "ui.town-menu.jail.cells"), "town jail list", "state", boolText(plugin, player, town.hasJail()), "count", town.jailCells.size.toString()))
        inv.setItem(16, item(plugin, player, Material.SKELETON_SKULL, "ui.town-menu.outlaws.name", listOf("ui.town-menu.outlaws.lore"), "town outlaw list", "count", town.outlaws.size.toString()))
        inv.setItem(22, item(plugin, player, Material.COMPARATOR, "ui.town-menu.settings.name", listOf("ui.town-menu.settings.public", "ui.town-menu.settings.open", "ui.town-menu.settings.pvp"), "town set perm", "public", boolText(plugin, player, town.isPublic), "open", boolText(plugin, player, town.isOpen), "pvp", boolText(plugin, player, town.hasPvp())))
    }

    private fun item(plugin: Townia, player: Player, material: Material, nameKey: String, loreKeys: List<String>, action: String, vararg replacements: String): ItemStack {
        return rawItem(
            plugin,
            material,
            plugin.messageManager.getRawMessage(player, nameKey, *replacements),
            loreKeys.map { plugin.messageManager.getRawMessage(player, it, *replacements) },
            action
        )
    }

    private fun rawItem(plugin: Townia, material: Material, name: String, lore: List<String>, action: String): ItemStack {
        val stack = ItemStack(material)
        val meta = stack.itemMeta
        meta.displayName(mini.deserialize(name))
        meta.lore(lore.map { mini.deserialize(it) })
        meta.persistentDataContainer.set(actionKey(plugin), PersistentDataType.STRING, action)
        stack.itemMeta = meta
        return stack
    }

    private fun actionKey(plugin: Townia): NamespacedKey = NamespacedKey(plugin, "town_menu_action")

    private fun formatMoney(plugin: Townia, amount: Double): String {
        return if (plugin.hasEconomy()) plugin.economy!!.format(amount) else amount.toString()
    }

    private fun boolText(plugin: Townia, player: Player, value: Boolean): String {
        return plugin.messageManager.getPlainMessage(player, if (value) "common.yes" else "common.no")
    }

    private class TownMenuHolder : InventoryHolder {
        lateinit var menuInventory: Inventory

        override fun getInventory(): Inventory = menuInventory
    }
}
