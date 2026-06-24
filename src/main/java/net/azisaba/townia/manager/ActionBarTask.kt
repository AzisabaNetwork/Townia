package net.azisaba.townia.manager;

import net.azisaba.townia.Townia;
import net.azisaba.townia.data.Plot;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.data.TowniaPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Optional;

public class ActionBarTask extends BukkitRunnable {

    private final Townia plugin;

    public ActionBarTask(Townia plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Chunk chunk = player.getLocation().getChunk();
            Optional<Plot> plotOpt = plugin.getPlotManager().getPlot(chunk);
            
            if (plotOpt.isEmpty()) {
                plugin.getMessageManager().sendActionBar(player, "town.actionbar-wilderness");
            } else {
                Optional<Town> townOpt = plugin.getTownManager().getTown(plotOpt.get().getTownUuid());
                if (townOpt.isPresent()) {
                    Town town = townOpt.get();
                    String mayorName = plugin.getResidentManager().getResident(town.getMayorUuid())
                            .map(TowniaPlayer::getName)
                            .orElse("Unknown");
                    plugin.getMessageManager().sendActionBar(player, "town.actionbar-town", 
                            "town", town.getName(), 
                            "mayor", mayorName);
                }
            }
        }
    }
}
