package net.azisaba.townia.listener

import net.azisaba.townia.Townia
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent

class JailCommandListener(private val plugin: Townia) : Listener {
    private val messagingCommands = setOf("msg", "tell", "w", "whisper", "r", "reply")
    private val townInfoCommands = setOf("", "?", "help", "info", "list", "reslist", "residentlist", "online", "outlawlist", "outlowlist", "jail")
    private val towniaInfoCommands = setOf("", "?", "help", "info", "price", "time", "top", "map")

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val resident = plugin.residentManager.getResident(event.player.uniqueId).orElse(null) ?: return
        if (!resident.isJailed) return
        val parts = event.message.removePrefix("/").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val root = normalizeRoot(parts.firstOrNull() ?: return)
        val sub = parts.getOrNull(1)?.lowercase() ?: ""
        val allowed = when (root) {
            "resident", "res" -> sub == "" || sub == "jail" || sub == "?"
            "town", "t" -> sub in townInfoCommands
            "townia", "towny" -> sub in towniaInfoCommands
            in messagingCommands -> true
            else -> false
        }
        if (allowed) return
        event.isCancelled = true
        plugin.messageManager.sendMessage(event.player, "jail.command-denied")
    }

    private fun normalizeRoot(root: String): String {
        return root.substringAfter(':').lowercase()
    }
}
