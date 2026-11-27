package net.azisaba.townia.listener

import net.azisaba.townia.Townia
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.permission.Perm
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import kotlin.math.min

/**
 * Towny's death costs: dying takes money from the player and, when configured, from the
 * bank of their town and nation. Nothing happens in an arena or without an economy.
 */
class DeathCostListener(private val plugin: Townia) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val rules = plugin.towniaConfig.rules
        if (!rules.hasDeathCosts) return
        val economy = plugin.economy ?: return
        val player = event.entity
        if (rules.deathPvpOnly && player.killer == null) return
        if (!plugin.towniaConfig.isWorldAllowed(player.world.name)) return
        if (plugin.plotManager.getPlotAt(player.location)?.plotType == PlotType.ARENA) return
        if (Perm.holds(player, "towny.bypass_death_costs")) return

        /** A fixed price, or with the percentage type a share of [balance] (optionally capped). */
        fun amount(configured: Double, balance: Double): Double {
            if (configured <= 0.0 || balance <= 0.0) return 0.0
            if (!rules.deathPercentage) return min(configured, balance)
            val share = balance * configured
            return if (rules.deathPercentageCap > 0.0) min(share, rules.deathPercentageCap) else share
        }

        val personal = amount(rules.deathPrice, economy.getBalance(player))
        if (personal > 0.0 && economy.withdrawPlayer(player, personal).transactionSuccess()) {
            plugin.messageManager.sendMessage(player, "death.cost", "amount", economy.format(personal))
        }

        val resident = plugin.residentManager.getResident(player.uniqueId).orElse(null) ?: return
        val town = resident.townUuid?.let { plugin.townManager.getTown(it).orElse(null) } ?: return
        val townCost = amount(rules.deathTownPrice, town.balance)
        if (townCost > 0.0) {
            town.balance -= townCost
            plugin.townManager.saveTown(town)
            plugin.messageManager.sendMessage(player, "death.cost-town", "amount", economy.format(townCost))
        }
        val nation = town.nationUuid?.let { plugin.nationManager.getNation(it).orElse(null) } ?: return
        val nationCost = amount(rules.deathNationPrice, nation.balance)
        if (nationCost > 0.0) {
            nation.balance -= nationCost
            plugin.nationManager.saveNation(nation)
            plugin.messageManager.sendMessage(player, "death.cost-nation", "amount", economy.format(nationCost))
        }
    }
}
