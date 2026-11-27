package net.azisaba.townia.permission

import net.azisaba.townia.Townia
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Permission checks with Towny's rules: operators and the console may do everything, a node
 * that is set decides, and otherwise a `parent.*` node grants what lies under it.
 */
object Perm {
    const val ADMIN = "towny.admin"

    /** Does [sender] hold [node]? */
    fun has(sender: CommandSender?, node: String): Boolean {
        if (sender !is Player) return true
        if (sender.isOp || sender.hasPermission(ADMIN) || sender.hasPermission("townia.admin")) return true
        return holds(sender, node)
    }

    /**
     * Like [has] but without the operator/admin shortcut: is the node really granted?
     * Used for the build overrides, where admins use `/towniaadmin bypass` instead.
     */
    fun holds(sender: Player, node: String): Boolean {
        val own = ownNodes(sender)
        // Bukkit knows what the permission plugin and Townia's attachment say together.
        if (sender.isPermissionSet(node)) return sender.hasPermission(node)
        own?.get(node)?.let { return it }

        var end = node.indexOf('.')
        if (hasWildcard(sender, own, "*")) return true
        while (end >= 0) {
            if (hasWildcard(sender, own, node.substring(0, end + 1) + "*")) return true
            end = node.indexOf('.', end + 1)
        }
        return false
    }

    /**
     * Only a wildcard that was really granted counts. Bukkit answers "yes" to any node nobody
     * registered when the player is an operator, so asking hasPermission alone let operators
     * build in every town without `/towniaadmin bypass`.
     */
    private fun hasWildcard(player: Player, own: Map<String, Boolean>?, wildcard: String): Boolean =
        own?.get(wildcard) == true || (player.isPermissionSet(wildcard) && player.hasPermission(wildcard))

    /** Admins: operators and holders of towny.admin (or Townia's own townia.admin). */
    fun isAdmin(sender: CommandSender?): Boolean {
        if (sender !is Player) return true
        return sender.isOp || sender.hasPermission(ADMIN) || sender.hasPermission("townia.admin")
    }

    private fun ownNodes(player: Player): Map<String, Boolean>? =
        runCatching { Townia.instance.townyPerms.effective(player) }.getOrNull()
}
