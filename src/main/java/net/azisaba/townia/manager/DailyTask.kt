package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable

class DailyTask(private val plugin: Townia) : BukkitRunnable() {
    private val economy: Economy? = plugin.economy

    override fun run() {
        val logger = plugin.logger
        logger.info("[Townia] Running daily upkeep and taxes collection...")

        for (resident in plugin.residentManager.allResidents) {
            if (resident.isInTown) {
                plugin.townManager.getTown(resident.townUuid).ifPresent { town ->
                    val townTax: Double = town.taxes
                    if (townTax > 0 && economy != null) {
                        val offlinePlayer: org.bukkit.OfflinePlayer = Bukkit.getOfflinePlayer(resident.uuid!!)
                        if (economy.has(offlinePlayer, townTax)) {
                            economy.withdrawPlayer(offlinePlayer, townTax)
                            town.balance += townTax
                            plugin.townManager.saveTown(town)
                        } else {
                            logger.info("[Townia] " + resident.name + " could not pay taxes and was kicked from " + town.name)
                            resident.townUuid = null
                            plugin.residentManager.saveResident(resident)
                        }
                    }
                }
            }
        }

        for (town in plugin.townManager.allTowns) {
            if (town.isInNation) {
                plugin.nationManager.getNation(town.nationUuid).ifPresent { nation ->
                    val nationTax: Double = nation.taxes
                    if (nationTax > 0) {
                        if (town.balance >= nationTax) {
                            town.balance -= nationTax
                            nation.balance += nationTax
                            plugin.townManager.saveTown(town)
                            plugin.nationManager.saveNation(nation)
                        } else {
                            logger.info("[Townia] Town " + town.name + " could not pay nation taxes and was kicked from " + nation.name)
                            town.nationUuid = null
                            plugin.townManager.saveTown(town)
                        }
                    }
                }
            }
        }

        for (town in plugin.townManager.allTowns) {
            val upkeep: Double = town.dailyUpkeep
            if (upkeep > 0) {
                if (town.balance >= upkeep) {
                    town.balance -= upkeep
                    plugin.townManager.saveTown(town)
                } else {
                    logger.info("[Townia] Town " + town.name + " fell into ruin because it could not pay its daily upkeep.")
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        try {
                            plugin.townManager.deleteTown(town.id!!)
                        } catch (e: Exception) {
                            logger.severe("Failed to delete town " + town.name + " due to unpaid upkeep: " + e.message)
                        }
                    })
                }
            }
        }

        logger.info("[Townia] Daily tasks completed.")
    }
}