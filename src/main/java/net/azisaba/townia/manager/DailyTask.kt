package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.azisaba.townia.data.PlotType
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable

class DailyTask(private val plugin: Townia) : BukkitRunnable() {
    private val economy: Economy? = plugin.economy

    companion object {
        /** When the first new day at [time] after [after] begins (Towny's new_day_time). */
        fun nextNewDay(time: java.time.LocalTime, after: java.time.ZonedDateTime): java.time.ZonedDateTime {
            val next = after.with(time)
            return if (next.isAfter(after)) next else next.plusDays(1)
        }
    }

    override fun run() {
        val logger = plugin.logger
        logger.info("[Townia] Running daily upkeep and taxes collection...")

        collectResidentTaxes()
        collectPlotTaxes()
        collectNationTaxes()
        collectTownUpkeep()
        collectNationUpkeep()

        if (withoutAccount > 0) {
            logger.info("[Townia] $withoutAccount tax payment(s) skipped: the resident has no economy account under their UUID.")
            withoutAccount = 0
        }
        plugin.resetNextUpkeepTime()
        logger.info("[Townia] Daily tasks completed.")
    }

    /** Towny's towny.tax_exempt: by default the mayor and assistants pay neither town nor plot tax. */
    private fun isTaxExempt(resident: net.azisaba.townia.data.TowniaPlayer): Boolean =
        runCatching { plugin.townyPerms.permsFor(resident)["towny.tax_exempt"] == true }.getOrDefault(false)

    private var withoutAccount = 0

    private fun hasAccount(eco: Economy, player: org.bukkit.OfflinePlayer): Boolean =
        runCatching { eco.hasAccount(player) }.getOrDefault(true)

    /** Taxes owed by a resident to their town. The mayor is exempt, as in Towny. */
    private fun collectResidentTaxes() {
        val eco = economy ?: return
        for (resident in plugin.residentManager.allResidents) {
            if (!resident.isInTown || resident.isMayor) continue
            val town = plugin.townManager.getTown(resident.townUuid).orElse(null) ?: continue
            // The town says who its mayor is; a rank that has not caught up must not get them taxed.
            if (town.mayorUuid == resident.uuid) continue
            if (town.taxes <= 0) continue
            // Asked last: it works out the resident's whole permission set.
            if (isTaxExempt(resident)) continue

            val offlinePlayer = Bukkit.getOfflinePlayer(resident.uuid ?: continue)
            // No account at all is not "cannot pay": it is a record nobody plays under this UUID
            // (a resident who has not logged in since the move from Towny, whose money sits under
            // their real UUID). Kicking them took their membership and their plots for nothing.
            if (!hasAccount(eco, offlinePlayer)) {
                withoutAccount++
                continue
            }
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
                if (plugin.residentManager.getResident(ownerUuid).map(::isTaxExempt).orElse(false)) continue

                val owner = Bukkit.getOfflinePlayer(ownerUuid)
                if (!hasAccount(eco, owner)) {
                    withoutAccount++
                    continue
                }
                if (eco.has(owner, tax) && eco.withdrawPlayer(owner, tax).transactionSuccess()) {
                    town.balance += tax
                } else {
                    plot.changeOwner(null)
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

    /**
     * Towny: a nation pays a daily upkeep and, while neutral, a daily neutrality fee.
     * One that cannot pay its upkeep is disbanded; one that cannot pay the fee stops being neutral.
     */
    private fun collectNationUpkeep() {
        val rules = plugin.towniaConfig.rules
        if (rules.nationUpkeep <= 0.0 && rules.neutralityCost <= 0.0) return
        val broke = ArrayList<net.azisaba.townia.data.Nation>()
        for (nation in plugin.nationManager.allNations) {
            var changed = false
            if (rules.nationUpkeep > 0.0) {
                if (nation.balance < rules.nationUpkeep) {
                    broke.add(nation)
                    continue
                }
                nation.balance -= rules.nationUpkeep
                changed = true
            }
            if (rules.neutralityCost > 0.0 && nation.isNeutral) {
                if (nation.balance >= rules.neutralityCost) {
                    nation.balance -= rules.neutralityCost
                } else {
                    nation.isNeutral = false
                    plugin.logger.info("[Townia] Nation " + nation.name + " could not pay for neutrality and is no longer neutral.")
                }
                changed = true
            }
            if (changed) plugin.nationManager.saveNation(nation)
        }
        for (nation in broke) {
            plugin.logger.info("[Townia] Nation " + nation.name + " was disbanded because it could not pay its daily upkeep.")
            try {
                plugin.nationManager.deleteNation(nation.id!!)
            } catch (e: Exception) {
                plugin.logger.severe("Failed to delete nation " + nation.name + " due to unpaid upkeep: " + e.message)
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
