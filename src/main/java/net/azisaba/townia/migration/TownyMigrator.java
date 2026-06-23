package net.azisaba.townia.migration;

import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.TownBlock;
import net.azisaba.townia.Townia;
import net.azisaba.townia.data.Plot;
import net.azisaba.townia.data.PlotType;
import net.azisaba.townia.data.TownRank;
import net.azisaba.townia.data.TowniaPlayer;
import org.bukkit.command.CommandSender;

import java.util.UUID;
import java.util.logging.Level;

public class TownyMigrator {

    public static void migrate(Townia plugin, CommandSender sender) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Towny")) {
            plugin.getMessageManager().sendMessage(sender, "admin.migration_failed", "{0}", "Towny is not installed or enabled!");
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getMessageManager().sendMessage(sender, "admin.migration_start");
            
            int towns = 0, nations = 0, residents = 0, plots = 0;
            
            try {
                for (Town tTown : TownyUniverse.getInstance().getTowns()) {
                    UUID nationUuid = tTown.hasNation() ? tTown.getNation().getUUID() : null;
                    double balance = 0;
                    try { balance = tTown.getAccount().getCachedBalance(); } catch (Exception ignored) {}
                    
                    String spawnWorld = null;
                    double spawnX = 0, spawnY = 0, spawnZ = 0;
                    float spawnYaw = 0, spawnPitch = 0;
                    if (tTown.hasSpawn()) {
                        spawnWorld = tTown.getSpawn().getWorld().getName();
                        spawnX = tTown.getSpawn().getX();
                        spawnY = tTown.getSpawn().getY();
                        spawnZ = tTown.getSpawn().getZ();
                        spawnYaw = tTown.getSpawn().getYaw();
                        spawnPitch = tTown.getSpawn().getPitch();
                    } else if (tTown.hasHomeBlock()) {
                        try {
                            spawnWorld = tTown.getHomeBlock().getWorld().getName();
                            spawnX = tTown.getHomeBlock().getX() * 16 + 8.5;
                            spawnZ = tTown.getHomeBlock().getZ() * 16 + 8.5;
                            org.bukkit.World bWorld = org.bukkit.Bukkit.getWorld(spawnWorld);
                            if (bWorld != null) {
                                spawnY = bWorld.getHighestBlockYAt((int)spawnX, (int)spawnZ) + 1;
                            } else {
                                spawnY = 64;
                            }
                        } catch (Exception ignored) {}
                    }
                    
                    net.azisaba.townia.data.Town ourTown = new net.azisaba.townia.data.Town(
                        tTown.getUUID(),
                        tTown.getName(),
                        tTown.getMayor().getUUID(),
                        nationUuid,
                        balance,
                        tTown.getMaxTownBlocks(),
                        tTown.getBonusBlocks(),
                        tTown.isPublic(),
                        tTown.getRegistered(),
                        tTown.getBoard(),
                        tTown.getTaxes(),
                        tTown.getPlotTax(),
                        tTown.getPermissions().pvp,
                        tTown.getPermissions().mobs,
                        tTown.getPermissions().explosion,
                        tTown.getPermissions().fire,
                        spawnWorld, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch
                    );
                    
                    ourTown.setOpen(tTown.isOpen());
                    if (tTown.hasHomeBlock()) {
                        try {
                            ourTown.setHomeBlock(tTown.getHomeBlock().getWorld().getName(), tTown.getHomeBlock().getX(), tTown.getHomeBlock().getZ());
                        } catch (Exception ignored) {}
                    }
                    
                    plugin.getDatabaseManager().saveTown(ourTown);
                    plugin.getTownManager().cacheTown(ourTown);
                    towns++;
                }

                for (Nation tNation : TownyUniverse.getInstance().getNations()) {
                    double balance = 0;
                    try { balance = tNation.getAccount().getCachedBalance(); } catch (Exception ignored) {}
                    
                    net.azisaba.townia.data.Nation ourNation = new net.azisaba.townia.data.Nation(
                        tNation.getUUID(),
                        tNation.getName(),
                        tNation.getCapital().getUUID(),
                        tNation.getKing().getUUID(),
                        balance,
                        tNation.getBoard(),
                        tNation.getTaxes()
                    );
                    
                    plugin.getDatabaseManager().saveNation(ourNation);
                    plugin.getNationManager().cacheNation(ourNation);
                    nations++;
                }

                for (Resident tRes : TownyUniverse.getInstance().getResidents()) {
                    UUID townUuid = tRes.hasTown() ? tRes.getTown().getUUID() : null;
                    TownRank rank = TownRank.RESIDENT;
                    if (tRes.isMayor()) {
                        rank = TownRank.MAYOR;
                    } else if (tRes.hasTownRank("assistant")) {
                        rank = TownRank.ASSISTANT;
                    }
                    
                    TowniaPlayer player = new TowniaPlayer(
                        tRes.getUUID(),
                        tRes.getName(),
                        townUuid,
                        rank,
                        tRes.getLastOnline(),
                        null
                    );
                    
                    plugin.getDatabaseManager().saveResident(player);
                    plugin.getResidentManager().cacheResident(player);
                    residents++;
                }

                for (TownBlock tb : TownyUniverse.getInstance().getTownBlocks().values()) {
                    if (!tb.hasTown()) continue;
                    
                    UUID ownerUuid = tb.hasResident() ? tb.getResident().getUUID() : null;
                    
                    PlotType type = PlotType.DEFAULT;
                    try {
                        String typeName = tb.getType().getName().toUpperCase();
                        if (typeName.equals("COMMERCIAL")) typeName = "SHOP";
                        if (typeName.equals("JAIL") || typeName.equals("WILDS")) typeName = "DEFAULT";
                        type = PlotType.valueOf(typeName);
                    } catch (Exception ignored) {}
                    
                    boolean forSale = tb.getPlotPrice() >= 0;
                    double price = Math.max(0, tb.getPlotPrice());
                    
                    Plot plot = new Plot(
                        tb.getWorld().getName(),
                        tb.getX(),
                        tb.getZ(),
                        tb.getTown().getUUID(),
                        ownerUuid,
                        type,
                        forSale,
                        price,
                        tb.getName(),
                        tb.getPermissions().pvp,
                        tb.getPermissions().mobs,
                        tb.getPermissions().explosion,
                        tb.getPermissions().fire
                    );
                    
                    plugin.getDatabaseManager().savePlot(plot);
                    plugin.getPlotManager().cachePlot(plot);
                    plots++;
                }

                plugin.getMessageManager().sendMessage(sender, "admin.migration_success",
                    "{0}", String.valueOf(towns),
                    "{1}", String.valueOf(nations),
                    "{2}", String.valueOf(residents),
                    "{3}", String.valueOf(plots)
                );
                plugin.getLogger().info("Towny Migration successful.");
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Migration failed", e);
                plugin.getMessageManager().sendMessage(sender, "admin.migration_failed", "{0}", "Migration encountered an error. Check console.");
            }
        });
    }
}
