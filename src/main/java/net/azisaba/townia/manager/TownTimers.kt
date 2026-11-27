package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Flying
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Monster
import org.bukkit.entity.Shulker
import org.bukkit.entity.Slime
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.scheduler.BukkitTask

/**
 * Towny's two repeating town tasks: residents heal while standing in their own (or an allied)
 * town, and hostile mobs that wander into a town with mobs off are removed.
 * Neither runs unless it is switched on in the config.
 */
class TownTimers(private val plugin: Townia) {
    private var regenTask: BukkitTask? = null
    private var mobTask: BukkitTask? = null

    /** (Re)starts the tasks to match the current config. */
    fun restart() {
        stop()
        val rules = plugin.towniaConfig.rules
        if (rules.healthRegen) {
            val period = rules.healthRegenSeconds * 20L
            regenTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable(::regen), period, period)
        }
        if (rules.townMobRemoval) {
            val period = rules.townMobRemovalSeconds * 20L
            mobTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable(::removeMobs), period, period)
        }
    }

    fun stop() {
        regenTask?.cancel()
        mobTask?.cancel()
        regenTask = null
        mobTask = null
    }

    private fun regen() {
        for (player in plugin.server.onlinePlayers) {
            if (player.isDead) continue
            val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: continue
            if (player.health >= max) continue
            val plot = plugin.plotManager.getPlotAt(player.location) ?: continue
            val home = plugin.residentManager.getResident(player.uniqueId).orElse(null)?.townUuid ?: continue
            if (home != plot.townUuid && !isAllied(home, plot.townUuid)) continue
            val event = EntityRegainHealthEvent(player, 1.0, EntityRegainHealthEvent.RegainReason.REGEN)
            plugin.server.pluginManager.callEvent(event)
            if (!event.isCancelled) player.health = (player.health + event.amount).coerceAtMost(max)
        }
    }

    private fun isAllied(townA: java.util.UUID?, townB: java.util.UUID?): Boolean {
        val a = plugin.townManager.getTown(townA).orElse(null)?.nationUuid ?: return false
        val b = plugin.townManager.getTown(townB).orElse(null)?.nationUuid ?: return false
        if (a == b) return true
        return plugin.nationManager.getNation(a).orElse(null)?.allies?.contains(b) == true
    }

    /** Looks at the hostile mobs that exist, not at every claimed chunk, so the cost follows the mob count. */
    private fun removeMobs() {
        val skipNamed = plugin.config.getBoolean("protection.town-mob-removal.skip-named", false)
        for (world in plugin.server.worlds) {
            if (!plugin.towniaConfig.isWorldAllowed(world.name)) continue
            for (entity in world.getEntitiesByClass(LivingEntity::class.java)) {
                if (entity !is Monster && entity !is Slime && entity !is Flying && entity !is Shulker) continue
                val plot = plugin.plotManager.getPlotAt(entity.location) ?: continue
                if (plot.hasMobs()) continue
                if (plugin.townManager.getTown(plot.townUuid).orElse(null)?.hasMobs() == true) continue
                if (skipNamed && entity.customName() != null) continue
                entity.remove()
            }
        }
    }
}
