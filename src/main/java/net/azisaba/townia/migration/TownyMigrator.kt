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
        if (!plugin.server.pluginManager.isPluginEnabled("Towny")) {
            plugin.messageManager.sendMessage(
                sender,
                "admin.migration_failed",
                "{0}",
                "Towny is not installed or enabled!"
            )
            return
        }

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            plugin.messageManager.sendMessage(sender, "admin.migration_start")
            var towns = 0
            var nations = 0
            var residents = 0
            var plots = 0
            try {
                for (tTown in TownyUniverse.getInstance().towns) {
                    val nationUuid = if (tTown.hasNation()) tTown.nation.uuid else null
                    var balance = 0.0
                    try {
                        balance = tTown.getAccount().getCachedBalance()
                    } catch (_: Exception) {
                    }

                    var spawnWorld: String? = null
                    var spawnX = 0.0
                    var spawnY = 0.0
                    var spawnZ = 0.0
                    var spawnYaw = 0f
                    var spawnPitch = 0f
                    if (tTown.hasSpawn()) {
                        spawnWorld = tTown.getSpawn().getWorld().name
                        spawnX = tTown.getSpawn().x
                        spawnY = tTown.getSpawn().y
                        spawnZ = tTown.getSpawn().z
                        spawnYaw = tTown.getSpawn().yaw
                        spawnPitch = tTown.getSpawn().pitch
                    } else if (tTown.hasHomeBlock()) {
                        try {
                            spawnWorld = tTown.homeBlock.world.name
                            spawnX = tTown.homeBlock.x * 16 + 8.5
                            spawnZ = tTown.homeBlock.z * 16 + 8.5
                            val bWorld = Bukkit.getWorld(spawnWorld)
                            if (bWorld != null) {
                                spawnY = (bWorld.getHighestBlockYAt(spawnX.toInt(), spawnZ.toInt()) + 1).toDouble()
                            } else {
                                spawnY = 64.0
                            }
                        } catch (_: Exception) {
                        }
                    }

                    val ourTown = Town(
                        tTown.uuid,
                        tTown.name,
                        tTown.mayor.uuid,
                        nationUuid,
                        balance,
                        tTown.maxTownBlocks,
                        tTown.bonusBlocks,
                        tTown.isPublic,
                        tTown.registered,
                        tTown.board,
                        tTown.taxes,
                        tTown.plotTax,
                        tTown.permissions.pvp,
                        tTown.permissions.mobs,
                        tTown.permissions.explosion,
                        tTown.permissions.fire,
                        spawnWorld, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch
                    )

                    ourTown.isOpen = tTown.isOpen
                    if (tTown.hasHomeBlock()) {
                        try {
                            ourTown.setHomeBlock(
                                tTown.homeBlock.world.name,
                                tTown.homeBlock.x,
                                tTown.homeBlock.z
                            )
                        } catch (_: Exception) {
                        }
                    }

                    plugin.databaseManager.saveTown(ourTown)
                    plugin.townManager.cacheTown(ourTown)
                    towns++
                }

                for (tNation in TownyUniverse.getInstance().nations) {
                    var balance = 0.0
                    try {
                        balance = tNation.getAccount().getCachedBalance()
                    } catch (_: Exception) {
                    }

                    val ourNation = Nation(
                        tNation.uuid,
                        tNation.name,
                        tNation.capital.uuid,
                        tNation.king.uuid,
                        balance,
                        tNation.board,
                        tNation.taxes
                    )

                    plugin.databaseManager.saveNation(ourNation)
                    plugin.nationManager.cacheNation(ourNation)
                    nations++
                }

                for (tRes in TownyUniverse.getInstance().residents) {
                    val townUuid = if (tRes.hasTown()) tRes.town.uuid else null
                    var rank: TownRank = TownRank.RESIDENT
                    if (tRes.isMayor) {
                        rank = TownRank.MAYOR
                    } else if (tRes.hasTownRank("assistant")) {
                        rank = TownRank.ASSISTANT
                    }

                    val player = TowniaPlayer(
                        tRes.uuid,
                        tRes.name,
                        townUuid,
                        rank,
                        tRes.lastOnline,
                        null
                    )

                    plugin.databaseManager.saveResident(player)
                    plugin.residentManager.cacheResident(player)
                    residents++
                }

                for (tb in TownyUniverse.getInstance().townBlocks.values) {
                    if (!tb.hasTown()) continue

                    val ownerUuid = if (tb.hasResident()) tb.getResident().uuid else null

                    var type: PlotType? = PlotType.DEFAULT
                    try {
                        var typeName = tb.type.name.uppercase(Locale.getDefault())
                        if (typeName == "COMMERCIAL") typeName = "SHOP"
                        if (typeName == "JAIL" || typeName == "WILDS") typeName = "DEFAULT"
                        type = PlotType.valueOf(typeName)
                    } catch (_: Exception) {
                    }

                    val forSale = tb.plotPrice >= 0
                    val price = max(0.0, tb.plotPrice)

                    val plot = Plot(
                        tb.world.name,
                        tb.x,
                        tb.z,
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

                plugin.messageManager.sendMessage(
                    sender, "admin.migration_success",
                    "{0}", towns.toString(),
                    "{1}", nations.toString(),
                    "{2}", residents.toString(),
                    "{3}", plots.toString()
                )
                plugin.logger.info("Towny Migration successful.")
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Migration failed", e)
                plugin.messageManager.sendMessage(
                    sender,
                    "admin.migration_failed",
                    "{0}",
                    "Migration encountered an error. Check console."
                )
            }
        })
    }
}