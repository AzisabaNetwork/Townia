package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.azisaba.townia.data.PlotType
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable

class DailyTask(private val plugin: Townia) : BukkitRunnable() {
    private val economy: Economy? = plugin.economy

    override fun run() {
        val logger = plugin.logger
        logger.info("[Townia] Running daily upkeep and taxes collection...")

        collectResidentTaxes()
        collectPlotTaxes()
        collectNationTaxes()
        collectTownUpkeep()

        plugin.resetNextUpkeepTime()
        logger.info("[Townia] Daily tasks completed.")
    }

    /** Taxes owed by a resident to their town. The mayor is exempt, as in Towny. */
    private fun collectResidentTaxes() {
        val eco = economy ?: return
        for (resident in plugin.residentManager.allResidents) {
            if (!resident.isInTown || resident.isMayor) continue
            val town = plugin.townManager.getTown(resident.townUuid).orElse(null) ?: continue
            if (town.taxes <= 0) continue

            val offlinePlayer = Bukkit.getOfflinePlayer(resident.uuid ?: continue)
            val amount = if (town.isTaxPercent) {
                eco.getBalance(offlinePlayer) * town.taxes.coerceAtMost(100.0) / 100.0
            } else {
                town.taxes
            }
            if (amount <= 0) continue

            if (eco.has(offlinePlayer, amount) && eco.withdrawPlayer(offlinePlayer, amount).transactionSuccess()) {
                town.balance += amount
                plugin.townManager.saveTown(town)
            } else {
                plugin.logger.info("[Townia] " + resident.name + " could not pay taxes and was kicked from " + town.name)
                plugin.residentManager.clearTown(resident.uuid)
            }
        }
    }

    /** Taxes owed by plot owners; a plot whose owner cannot pay goes back to the town. */
    private fun collectPlotTaxes() {
        val eco = economy ?: return
        for (town in plugin.townManager.allTowns) {
            for (plot in plugin.plotManager.getPlotsByTown(town.id ?: continue)) {
                val ownerUuid = plot.ownerUuid ?: continue
                val tax = when (plot.plotType) {
                    PlotType.SHOP -> town.shopTax
                    PlotType.EMBASSY -> town.embassyTax
                    else -> town.plotTax
                }
                if (tax <= 0) continue

                val owner = Bukkit.getOfflinePlayer(ownerUuid)
                if (eco.has(owner, tax) && eco.withdrawPlayer(owner, tax).transactionSuccess()) {
                    town.balance += tax
                } else {
                    plot.ownerUuid = null
                    plot.isForSale = false
                    plot.price = 0.0
                    plugin.plotManager.persistPlot(plot)
                }
            }
            plugin.townManager.saveTown(town)
        }
    }

    /** Towns pay the nation tax; the capital is exempt so a nation can never lose its capital this way. */
    private fun collectNationTaxes() {
        for (town in plugin.townManager.allTowns) {
            if (!town.isInNation) continue
            val nation = plugin.nationManager.getNation(town.nationUuid).orElse(null) ?: continue
            if (nation.capitalTownUuid == town.id) continue
            val nationTax = nation.taxes
            if (nationTax <= 0) continue

            if (town.balance >= nationTax) {
                town.balance -= nationTax
                nation.balance += nationTax
                plugin.townManager.saveTown(town)
                plugin.nationManager.saveNation(nation)
            } else {
                plugin.logger.info("[Townia] Town " + town.name + " could not pay nation taxes and was kicked from " + nation.name)
                town.nationUuid = null
                plugin.townManager.saveTown(town)
            }
        }
    }

    private fun collectTownUpkeep() {
        val ruined = ArrayList<net.azisaba.townia.data.Town>()
        for (town in plugin.townManager.allTowns) {
            val upkeep = town.dailyUpkeep
            if (upkeep <= 0) continue
            if (town.balance >= upkeep) {
                town.balance -= upkeep
                plugin.townManager.saveTown(town)
            } else {
                ruined.add(town)
            }
        }
        // Towns are deleted after the loop so no collection is modified while it is being walked.
        for (town in ruined) {
            plugin.logger.info("[Townia] Town " + town.name + " fell into ruin because it could not pay its daily upkeep.")
            try {
                plugin.townManager.deleteTown(town.id!!)
            } catch (e: Exception) {
                plugin.logger.severe("Failed to delete town " + town.name + " due to unpaid upkeep: " + e.message)
            }
        }
    }
}
