package net.azisaba.townia.migration

import net.azisaba.townia.Townia
import org.bukkit.command.CommandSender

object TownyConfigMigrator {
    fun migrate(plugin: Townia, sender: CommandSender) {
        plugin.messageManager.sendMessage(sender, "error.not-implemented")
    }
}
