package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.Town
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.scheduler.BukkitRunnable
import java.util.*

/**
 * Shows the "~ Town - owner - PvP" line. The text is built when a player enters another plot
 * and is then only re-sent, so the periodic task does no parsing for players who stand still.
 */
class ActionBarTask(private val plugin: Townia) : BukkitRunnable() {
    companion object {
        private const val MAX_PARSED = 512

        /** What each player currently has on screen and until when (0 = until they move on). */
        private class Shown(val component: Component, val expiresAt: Long)

        private val shown = HashMap<UUID, Shown>()

        /** MiniMessage parsing is the costly part; identical lines are parsed once. */
        private val parsed = object : LinkedHashMap<String, Component>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Component>?): Boolean = size > MAX_PARSED
        }

        private fun parse(plugin: Townia, text: String): Component =
            parsed.getOrPut(text) { plugin.messageManager.miniMessage.deserialize(text) }

        fun forget(playerId: UUID) {
            shown.remove(playerId)
        }

        fun clearCache() {
            parsed.clear()
            shown.clear()
        }

        fun sendActionBar(plugin: Townia, player: org.bukkit.entity.Player) {
            sendActionBar(plugin, player, player.location)
        }

        fun sendActionBar(plugin: Townia, player: org.bukkit.entity.Player, location: Location) {
            val text = buildText(plugin, player, location)
            if (text == null) {
                shown.remove(player.uniqueId)
                return
            }
            val component = parse(plugin, text)
            player.sendActionBar(component)
            val seconds = plugin.towniaConfig.actionBarDuration
            val expiresAt = if (seconds <= 0) 0L else System.currentTimeMillis() + seconds * 1000L
            shown[player.uniqueId] = Shown(component, expiresAt)
        }

        /** The MiniMessage line for [location], or null when nothing is shown there. */
        private fun buildText(plugin: Townia, player: org.bukkit.entity.Player, location: Location): String? {
            if (!plugin.towniaConfig.isWorldAllowed(location.world.name)) {
                return null
            }

            val x = location.blockX shr 4
            val z = location.blockZ shr 4
            val plotOpt: Optional<Plot> = plugin.plotManager.getPlot(location.world.name, x, z)
            val resident = plugin.residentManager.getResident(player.uniqueId).orElse(null)
            if (resident?.isJailed == true) {
                val remaining = if (resident.jailReleaseAt > 0L) ((resident.jailReleaseAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L) else 0L
                val minutes = remaining / 60L
                val seconds = remaining % 60L
                return plugin.messageManager.getRawMessage(player, "jail.actionbar", "minutes", minutes.toString(), "seconds", seconds.toString())
            }

            if (plotOpt.isEmpty) {
                // Wilderness
                val isPvp = true // In Azisaba, wilderness is usually PvP enabled, or let's just show it.
                val pvpStr = if (isPvp) " <gray>- " + plugin.messageManager.getRawMessage(player, "town.actionbar-pvp") else " <gray>- " + plugin.messageManager.getRawMessage(player, "town.actionbar-nopvp")
                val wildernessName = plugin.messageManager.getRawMessage(player, "town.actionbar-wilderness-name")
                return "<gold>~ <dark_green>$wildernessName$pvpStr"
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
                                net.azisaba.townia.data.TownRank.SUB_MAYOR -> plugin.messageManager.getRawMessage(player, "town.actionbar-submayor")
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
                    return text.trim()
                }
                return null
            }
        }
    }

    override fun run() {
        val always = plugin.towniaConfig.actionBarDuration <= 0
        val now = System.currentTimeMillis()
        for (player in Bukkit.getOnlinePlayers()) {
            val id = player.uniqueId
            // The jail countdown changes every second, so jailed players are always rebuilt.
            if (always || plugin.residentManager.getResident(id).orElse(null)?.isJailed == true) {
                sendActionBar(plugin, player)
                continue
            }
            val current = shown[id] ?: continue
            if (current.expiresAt in 1..now) {
                shown.remove(id)
            } else {
                player.sendActionBar(current.component)
            }
        }
        if (shown.size > Bukkit.getOnlinePlayers().size) {
            shown.keys.removeIf { Bukkit.getPlayer(it) == null }
        }
    }
}
