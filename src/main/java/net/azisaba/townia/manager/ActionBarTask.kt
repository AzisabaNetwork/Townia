package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TowniaPlayer
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable
import java.util.*

class ActionBarTask(private val plugin: Townia) : BukkitRunnable() {
    override fun run() {
        for (player in Bukkit.getOnlinePlayers()) {
            val chunk = player.location.chunk
            val plotOpt: Optional<Plot> = plugin.plotManager.getPlot(chunk)

            if (plotOpt.isEmpty) {
                plugin.messageManager.sendActionBar(player, "town.actionbar-wilderness")
            } else {
                val townOpt: Optional<Town> = plugin.townManager.getTown(plotOpt.get().townUuid)
                if (townOpt.isPresent) {
                    val town: Town = townOpt.get()
                    val mayorName: String? = plugin.residentManager.getResident(town.mayorUuid)
                        .map { it.name }
                        .orElse("Unknown")
                    plugin.messageManager.sendActionBar(
                        player, "town.actionbar-town",
                        "town", (town.name ?: ""),
                        "mayor", mayorName!!
                    )
                }
            }
        }
    }
}