package net.azisaba.townia.migration

import com.palmergames.bukkit.towny.TownyUniverse
import net.azisaba.townia.Townia
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaPlayer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.util.*
import java.util.logging.Level
import kotlin.math.max

object TownyMigrator {
    fun migrate(plugin: Townia, sender: CommandSender) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Towny")) {
            plugin.messageManager!!.sendMessage(
                sender,
                "admin.migration_failed",
                "{0}",
                "Towny is not installed or enabled!"
            )
            return
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.messageManager!!.sendMessage(sender, "admin.migration_start")
            var towns = 0
            var nations = 0
            var residents = 0
            var plots = 0
            try {
                for (tTown in TownyUniverse.getInstance().getTowns()) {
                    val nationUuid = if (tTown.hasNation()) tTown.getNation().uuid else null
                    var balance = 0.0
                    try {
                        balance = tTown.getAccount().getCachedBalance()
                    } catch (ignored: Exception) {
                    }

                    var spawnWorld: String? = null
                    var spawnX = 0.0
                    var spawnY = 0.0
                    var spawnZ = 0.0
                    var spawnYaw = 0f
                    var spawnPitch = 0f
                    if (tTown.hasSpawn()) {
                        spawnWorld = tTown.getSpawn().getWorld().name
                        spawnX = tTown.getSpawn().getX()
                        spawnY = tTown.getSpawn().getY()
                        spawnZ = tTown.getSpawn().getZ()
                        spawnYaw = tTown.getSpawn().getYaw()
                        spawnPitch = tTown.getSpawn().getPitch()
                    } else if (tTown.hasHomeBlock()) {
                        try {
                            spawnWorld = tTown.getHomeBlock().getWorld().name
                            spawnX = tTown.getHomeBlock().getX() * 16 + 8.5
                            spawnZ = tTown.getHomeBlock().getZ() * 16 + 8.5
                            val bWorld = Bukkit.getWorld(spawnWorld)
                            if (bWorld != null) {
                                spawnY = (bWorld.getHighestBlockYAt(spawnX.toInt(), spawnZ.toInt()) + 1).toDouble()
                            } else {
                                spawnY = 64.0
                            }
                        } catch (ignored: Exception) {
                        }
                    }

                    val ourTown: Town = Town(
                        tTown.uuid,
                        tTown.name,
                        tTown.getMayor().uuid,
                        nationUuid,
                        balance,
                        tTown.getMaxTownBlocks(),
                        tTown.getBonusBlocks(),
                        tTown.isPublic(),
                        tTown.getRegistered(),
                        tTown.board,
                        tTown.taxes,
                        tTown.getPlotTax(),
                        tTown.getPermissions().pvp,
                        tTown.getPermissions().mobs,
                        tTown.getPermissions().explosion,
                        tTown.getPermissions().fire,
                        spawnWorld, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch
                    )

                    ourTown.isOpen = tTown.isOpen
                    if (tTown.hasHomeBlock()) {
                        try {
                            ourTown.setHomeBlock(
                                tTown.getHomeBlock().getWorld().name,
                                tTown.getHomeBlock().getX(),
                                tTown.getHomeBlock().getZ()
                            )
                        } catch (ignored: Exception) {
                        }
                    }

                    plugin.databaseManager.saveTown(ourTown)
                    plugin.townManager.cacheTown(ourTown)
                    towns++
                }

                for (tNation in TownyUniverse.getInstance().getNations()) {
                    var balance = 0.0
                    try {
                        balance = tNation.getAccount().getCachedBalance()
                    } catch (ignored: Exception) {
                    }

                    val ourNation: Nation = Nation(
                        tNation.uuid,
                        tNation.name,
                        tNation.getCapital().uuid,
                        tNation.getKing().uuid,
                        balance,
                        tNation.board,
                        tNation.taxes
                    )

                    plugin.databaseManager.saveNation(ourNation)
                    plugin.nationManager.cacheNation(ourNation)
                    nations++
                }

                for (tRes in TownyUniverse.getInstance().getResidents()) {
                    val townUuid = if (tRes.hasTown()) tRes.getTown().uuid else null
                    var rank: TownRank = TownRank.RESIDENT
                    if (tRes.isMayor) {
                        rank = TownRank.MAYOR
                    } else if (tRes.hasTownRank("assistant")) {
                        rank = TownRank.ASSISTANT
                    }

                    val player: TowniaPlayer = TowniaPlayer(
                        tRes.uuid,
                        tRes.name,
                        townUuid,
                        rank,
                        tRes.getLastOnline(),
                        null
                    )

                    plugin.databaseManager.saveResident(player)
                    plugin.residentManager.cacheResident(player)
                    residents++
                }

                for (tb in TownyUniverse.getInstance().getTownBlocks().values) {
                    if (!tb.hasTown()) continue

                    val ownerUuid = if (tb.hasResident()) tb.getResident().uuid else null

                    var type: PlotType? = PlotType.DEFAULT
                    try {
                        var typeName = tb.getType().name.uppercase(Locale.getDefault())
                        if (typeName == "COMMERCIAL") typeName = "SHOP"
                        if (typeName == "JAIL" || typeName == "WILDS") typeName = "DEFAULT"
                        type = PlotType.valueOf(typeName)
                    } catch (ignored: Exception) {
                    }

                    val forSale = tb.plotPrice >= 0
                    val price = max(0.0, tb.plotPrice)

                    val plot: Plot = Plot(
                        tb.getWorld().name,
                        tb.getX(),
                        tb.getZ(),
                        tb.getTown().uuid,
                        ownerUuid,
                        type,
                        forSale,
                        price,
                        tb.name,
                        tb.getPermissions().pvp,
                        tb.getPermissions().mobs,
                        tb.getPermissions().explosion,
                        tb.getPermissions().fire
                    )

                    plugin.databaseManager.savePlot(plot)
                    plugin.plotManager.cachePlot(plot)
                    plots++
                }

                plugin.messageManager!!.sendMessage(
                    sender, "admin.migration_success",
                    "{0}", towns.toString(),
                    "{1}", nations.toString(),
                    "{2}", residents.toString(),
                    "{3}", plots.toString()
                )
                plugin.getLogger().info("Towny Migration successful.")
            } catch (e: Exception) {
                plugin.getLogger().log(Level.SEVERE, "Migration failed", e)
                plugin.messageManager!!.sendMessage(
                    sender,
                    "admin.migration_failed",
                    "{0}",
                    "Migration encountered an error. Check console."
                )
            }
        })
    }
}