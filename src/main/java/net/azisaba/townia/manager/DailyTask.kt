package net.azisaba.townia.manager;

import net.azisaba.townia.Townia;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.data.TowniaPlayer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Logger;

public class DailyTask extends BukkitRunnable {

    private final Townia plugin;
    private final Economy economy;

    public DailyTask(Townia plugin) {
        this.plugin = plugin;
        this.economy = plugin.getEconomy();
    }

    @Override
    public void run() {
        Logger logger = plugin.getLogger();
        logger.info("[Townia] Running daily upkeep and taxes collection...");

        for (TowniaPlayer resident : plugin.getResidentManager().getAllResidents()) {
            if (resident.isInTown()) {
                plugin.getTownManager().getTown(resident.getTownUuid()).ifPresent(town -> {
                    double townTax = town.getTaxes();
                    if (townTax > 0 && economy != null) {
                        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(resident.getUuid());
                        if (economy.has(offlinePlayer, townTax)) {
                            economy.withdrawPlayer(offlinePlayer, townTax);
                            town.setBalance(town.getBalance() + townTax);
                            plugin.getTownManager().saveTown(town);
                        } else {
                            logger.info("[Townia] " + resident.getName() + " could not pay taxes and was kicked from " + town.getName());
                            resident.setTownUuid(null);
                            plugin.getResidentManager().saveResident(resident);
                        }
                    }
                });
            }
        }

        for (Town town : plugin.getTownManager().getAllTowns()) {
            if (town.isInNation()) {
                plugin.getNationManager().getNation(town.getNationUuid()).ifPresent(nation -> {
                    double nationTax = nation.getTaxes();
                    if (nationTax > 0) {
                        if (town.getBalance() >= nationTax) {
                            town.setBalance(town.getBalance() - nationTax);
                            nation.setBalance(nation.getBalance() + nationTax);
                            plugin.getTownManager().saveTown(town);
                            plugin.getNationManager().saveNation(nation);
                        } else {
                            logger.info("[Townia] Town " + town.getName() + " could not pay nation taxes and was kicked from " + nation.getName());
                            town.setNationUuid(null);
                            plugin.getTownManager().saveTown(town);
                        }
                    }
                });
            }
        }

        for (Town town : plugin.getTownManager().getAllTowns()) {
            double upkeep = town.getDailyUpkeep();
            if (upkeep > 0) {
                if (town.getBalance() >= upkeep) {
                    town.setBalance(town.getBalance() - upkeep);
                    plugin.getTownManager().saveTown(town);
                } else {
                    logger.info("[Townia] Town " + town.getName() + " fell into ruin because it could not pay its daily upkeep.");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            plugin.getTownManager().deleteTown(town.getId());
                        } catch (Exception e) {
                            logger.severe("Failed to delete town " + town.getName() + " due to unpaid upkeep: " + e.getMessage());
                        }
                    });
                }
            }
        }

        logger.info("[Townia] Daily tasks completed.");
    }
}
