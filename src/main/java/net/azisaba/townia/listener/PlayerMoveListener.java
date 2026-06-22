package net.azisaba.townia.listener;

import net.azisaba.townia.Townia;
import net.azisaba.townia.data.Plot;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.manager.PlotManager;
import net.azisaba.townia.manager.TownManager;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Objects;
import java.util.Optional;

public class PlayerMoveListener implements Listener {

    private final Townia plugin;
    private final PlotManager plotManager;
    private final TownManager townManager;

    public PlayerMoveListener(Townia plugin) {
        this.plugin = plugin;
        this.plotManager = plugin.getPlotManager();
        this.townManager = plugin.getTownManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        
        Chunk fromChunk = event.getFrom().getChunk();
        Chunk toChunk = event.getTo().getChunk();
        
        if (fromChunk.getX() == toChunk.getX() && fromChunk.getZ() == toChunk.getZ()) {
            return;
        }

        Player player = event.getPlayer();
        
        Optional<Plot> toPlotOpt = plotManager.getPlot(toChunk);
        Optional<Plot> fromPlotOpt = plotManager.getPlot(fromChunk);

        if (toPlotOpt.isEmpty()) {
            plugin.getMessageManager().sendActionBar(player, "town.actionbar-wilderness");
        } else {
            Optional<Town> townOpt = townManager.getTown(toPlotOpt.get().getTownUuid());
            if (townOpt.isPresent()) {
                Town town = townOpt.get();
                String mayorName = plugin.getResidentManager().getResident(town.getMayorUuid())
                        .map(r -> r.getName())
                        .orElse("Unknown");
                plugin.getMessageManager().sendActionBar(player, "town.actionbar-town", 
                        "town", town.getName(), 
                        "mayor", mayorName);
            }
        }
    }
}
