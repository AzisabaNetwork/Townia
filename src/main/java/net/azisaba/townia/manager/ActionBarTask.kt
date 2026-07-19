package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.Town
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.scheduler.BukkitRunnable
import java.util.*

class ActionBarTask(private val plugin: Townia) : BukkitRunnable() {
    companion object {
        fun sendActionBar(plugin: Townia, player: org.bukkit.entity.Player) {
            sendActionBar(plugin, player, player.location)
        }

        fun sendActionBar(plugin: Townia, player: org.bukkit.entity.Player, location: Location) {
            if (!plugin.towniaConfig.isWorldAllowed(location.world.name)) {
                return
            }

            val x = location.blockX shr 4
            val z = location.blockZ shr 4
            val plotOpt: Optional<Plot> = plugin.plotManager.getPlot(location.world.name, x, z)
            val resident = plugin.residentManager.getResident(player.uniqueId).orElse(null)
            if (resident?.isJailed == true) {
                val remaining = if (resident.jailReleaseAt > 0L) ((resident.jailReleaseAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L) else 0L
                val minutes = remaining / 60L
                val seconds = remaining % 60L
                player.sendActionBar(plugin.messageManager.miniMessage.deserialize(plugin.messageManager.getRawMessage(player, "jail.actionbar", "minutes", minutes.toString(), "seconds", seconds.toString())))
                return
            }

            if (plotOpt.isEmpty) {
                // Wilderness
                val isPvp = true // In Azisaba, wilderness is usually PvP enabled, or let's just show it.
                val pvpStr = if (isPvp) " <gray>- " + plugin.messageManager.getRawMessage(player, "town.actionbar-pvp") else " <gray>- " + plugin.messageManager.getRawMessage(player, "town.actionbar-nopvp")
                val wildernessName = plugin.messageManager.getRawMessage(player, "town.actionbar-wilderness-name")
                val text = "<gold>~ <dark_green>$wildernessName$pvpStr"
                player.sendActionBar(plugin.messageManager.miniMessage.deserialize(text))
            } else {
                val townOpt: Optional<Town> = plugin.townManager.getTown(plotOpt.get().townUuid)
                if (townOpt.isPresent) {
                    val town: Town = townOpt.get()
                    val plot: Plot = plotOpt.get()

                    var text = "<gold>~ "

                    if (!plot.name.isNullOrBlank()) {
                        text += "<green>${plot.name}"
                    } else if (plot.ownerUuid != null) {
                        val ownerOpt = plugin.residentManager.getResident(plot.ownerUuid!!)
                        if (ownerOpt.isPresent) {
                            val owner = ownerOpt.get()
                            val rankStr = when (owner.rank) {
                                net.azisaba.townia.data.TownRank.MAYOR -> plugin.messageManager.getRawMessage(player, "town.actionbar-mayor")
                                net.azisaba.townia.data.TownRank.CO_MAYOR -> plugin.messageManager.getRawMessage(player, "town.actionbar-comayor")
                                net.azisaba.townia.data.TownRank.ASSISTANT -> plugin.messageManager.getRawMessage(player, "town.actionbar-assistant")
                                net.azisaba.townia.data.TownRank.RESIDENT -> ""
                            }
                            val nameColor = if (owner.rank == net.azisaba.townia.data.TownRank.MAYOR) "<gold>" else "<green>"
                            text += "$nameColor$rankStr${owner.name}"
                        } else {
                            text += "<green>Unknown"
                        }
                    } else {
                        if (plot.isForSale) {
                            text += "<green>" + plugin.messageManager.getRawMessage(player, "town.actionbar-unowned")
                        } else {
                            text += "<gold>${town.name}"
                        }
                    }

                    var suffix = ""
                    var addedHyphen = false

                    if (plot.isForSale) {
                        val priceStr = if (plugin.hasEconomy()) plugin.economy!!.format(plot.price).replace("[^\\d.,-]".toRegex(), "") else plot.price.toString()
                        suffix += "<gray>- " + plugin.messageManager.getRawMessage(player, "town.actionbar-forsale", "price", priceStr) + " "
                        addedHyphen = true
                    }

                    if (town.outlaws.contains(player.uniqueId)) {
                        if (!addedHyphen) {
                            suffix += "<gray>- "
                            addedHyphen = true
                        }
                        suffix += plugin.messageManager.getRawMessage(player, "town.actionbar-outlaw") + " "
                    }

                    if (town.hasHomeBlock() && plot.chunkX == town.homeBlockX && plot.chunkZ == town.homeBlockZ) {
                        if (!addedHyphen) {
                            suffix += "<gray>- "
                            addedHyphen = true
                        }
                        suffix += "<aqua>[Home] "
                    } else if (plot.plotType != null && plot.plotType != net.azisaba.townia.data.PlotType.DEFAULT) {
                        if (!addedHyphen) {
                            suffix += "<gray>- "
                            addedHyphen = true
                        }
                        val typeName = plot.plotType!!.name.lowercase().replaceFirstChar { it.uppercase() }
                        suffix += "<gold>[$typeName] "
                    }

                    if (!addedHyphen) {
                        suffix += "<gray>- "
                        addedHyphen = true
                    }
                    
                    if (plot.hasPvp() || town.hasPvp()) {
                        suffix += plugin.messageManager.getRawMessage(player, "town.actionbar-pvp")
                    } else {
                        suffix += plugin.messageManager.getRawMessage(player, "town.actionbar-nopvp")
                    }

                    text += " $suffix"
                    player.sendActionBar(plugin.messageManager.miniMessage.deserialize(text.trim()))
                }
            }
        }
    }

    override fun run() {
        for (player in Bukkit.getOnlinePlayers()) {
            sendActionBar(plugin, player)
        }
    }
}
