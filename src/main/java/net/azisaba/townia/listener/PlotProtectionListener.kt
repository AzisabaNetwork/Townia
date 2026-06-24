package net.azisaba.townia.listener;

import net.azisaba.townia.Townia;
import net.azisaba.townia.command.TowniaAdminCommand;
import net.azisaba.townia.data.Plot;
import net.azisaba.townia.data.Town;
import net.azisaba.townia.data.TowniaPlayer;
import net.azisaba.townia.manager.PlotManager;
import net.azisaba.townia.manager.ResidentManager;
import net.azisaba.townia.manager.TownManager;
import net.azisaba.townia.manager.NationManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

public class PlotProtectionListener implements Listener {

    private static final Set<Material> CONTAINER_MATERIALS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.HOPPER, Material.DROPPER, Material.DISPENSER,
            Material.SHULKER_BOX,
            Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX, Material.MAGENTA_SHULKER_BOX,
            Material.LIGHT_BLUE_SHULKER_BOX, Material.YELLOW_SHULKER_BOX, Material.LIME_SHULKER_BOX,
            Material.PINK_SHULKER_BOX, Material.GRAY_SHULKER_BOX, Material.LIGHT_GRAY_SHULKER_BOX,
            Material.CYAN_SHULKER_BOX, Material.PURPLE_SHULKER_BOX, Material.BLUE_SHULKER_BOX,
            Material.BROWN_SHULKER_BOX, Material.GREEN_SHULKER_BOX, Material.RED_SHULKER_BOX,
            Material.BLACK_SHULKER_BOX,
            Material.ENDER_CHEST, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.ENCHANTING_TABLE, Material.BREWING_STAND, Material.BEACON,
            Material.CRAFTING_TABLE, Material.LOOM, Material.CARTOGRAPHY_TABLE,
            Material.FLETCHING_TABLE, Material.SMITHING_TABLE, Material.GRINDSTONE,
            Material.STONECUTTER
    );

    private static final Set<Material> DOOR_MATERIALS = EnumSet.of(
            Material.OAK_DOOR, Material.SPRUCE_DOOR, Material.BIRCH_DOOR,
            Material.JUNGLE_DOOR, Material.ACACIA_DOOR, Material.DARK_OAK_DOOR,
            Material.MANGROVE_DOOR, Material.CHERRY_DOOR, Material.BAMBOO_DOOR,
            Material.CRIMSON_DOOR, Material.WARPED_DOOR,
            Material.IRON_DOOR,
            Material.OAK_TRAPDOOR, Material.SPRUCE_TRAPDOOR, Material.BIRCH_TRAPDOOR,
            Material.JUNGLE_TRAPDOOR, Material.ACACIA_TRAPDOOR, Material.DARK_OAK_TRAPDOOR,
            Material.MANGROVE_TRAPDOOR, Material.CHERRY_TRAPDOOR, Material.BAMBOO_TRAPDOOR,
            Material.CRIMSON_TRAPDOOR, Material.WARPED_TRAPDOOR,
            Material.IRON_TRAPDOOR,
            Material.OAK_FENCE_GATE, Material.SPRUCE_FENCE_GATE, Material.BIRCH_FENCE_GATE,
            Material.JUNGLE_FENCE_GATE, Material.ACACIA_FENCE_GATE, Material.DARK_OAK_FENCE_GATE,
            Material.MANGROVE_FENCE_GATE, Material.CHERRY_FENCE_GATE, Material.BAMBOO_FENCE_GATE,
            Material.CRIMSON_FENCE_GATE, Material.WARPED_FENCE_GATE,
            Material.LEVER, Material.STONE_BUTTON, Material.OAK_BUTTON, Material.SPRUCE_BUTTON,
            Material.BIRCH_BUTTON, Material.JUNGLE_BUTTON, Material.ACACIA_BUTTON,
            Material.DARK_OAK_BUTTON, Material.MANGROVE_BUTTON, Material.CHERRY_BUTTON,
            Material.BAMBOO_BUTTON, Material.CRIMSON_BUTTON, Material.WARPED_BUTTON,
            Material.POLISHED_BLACKSTONE_BUTTON
    );
    
    private static final Set<Material> ITEM_USE_MATERIALS = EnumSet.of(
            Material.FLINT_AND_STEEL, Material.FIRE_CHARGE, Material.BONE_MEAL,
            Material.LAVA_BUCKET, Material.WATER_BUCKET, Material.COD_BUCKET,
            Material.SALMON_BUCKET, Material.PUFFERFISH_BUCKET, Material.TROPICAL_FISH_BUCKET,
            Material.AXOLOTL_BUCKET, Material.TADPOLE_BUCKET, Material.POWDER_SNOW_BUCKET,
            Material.BUCKET, Material.MINECART, Material.CHEST_MINECART,
            Material.FURNACE_MINECART, Material.TNT_MINECART, Material.HOPPER_MINECART,
            Material.ARMOR_STAND, Material.OAK_BOAT, Material.SPRUCE_BOAT, Material.BIRCH_BOAT,
            Material.JUNGLE_BOAT, Material.ACACIA_BOAT, Material.DARK_OAK_BOAT,
            Material.MANGROVE_BOAT, Material.CHERRY_BOAT, Material.BAMBOO_RAFT,
            Material.OAK_CHEST_BOAT, Material.SPRUCE_CHEST_BOAT, Material.BIRCH_CHEST_BOAT,
            Material.JUNGLE_CHEST_BOAT, Material.ACACIA_CHEST_BOAT, Material.DARK_OAK_CHEST_BOAT,
            Material.MANGROVE_CHEST_BOAT, Material.CHERRY_CHEST_BOAT, Material.BAMBOO_CHEST_RAFT,
            Material.ENDER_PEARL, Material.CHORUS_FRUIT
    );

    private final Townia plugin;
    private final PlotManager plotManager;
    private final ResidentManager residentManager;
    private final TownManager townManager;
    private final NationManager nationManager;

    public enum ActionType { BUILD('B'), DESTROY('D'), SWITCH('S'), ITEM('I'); final char id; ActionType(char id) { this.id = id; } }
    public enum Relationship { RESIDENT, ALLY, NATION, OUTSIDER }

    public PlotProtectionListener(Townia plugin) {
        this.plugin = plugin;
        this.plotManager = plugin.getPlotManager();
        this.residentManager = plugin.getResidentManager();
        this.townManager = plugin.getTownManager();
        this.nationManager = plugin.getNationManager();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (handleAction(event.getPlayer(), event.getBlock().getChunk(), ActionType.DESTROY)) {
            event.setCancelled(true);
            plugin.getMessageManager().sendMessage(event.getPlayer(), "protection.build-denied");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (handleAction(event.getPlayer(), event.getBlock().getChunk(), ActionType.BUILD)) {
            event.setCancelled(true);
            plugin.getMessageManager().sendMessage(event.getPlayer(), "protection.build-denied");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL) {
            Block block = event.getClickedBlock();
            if (block != null && block.getType() == Material.FARMLAND) {
                if (handleAction(event.getPlayer(), block.getChunk(), ActionType.DESTROY)) {
                    event.setCancelled(true);
                }
            }
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                if (event.getItem() != null && ITEM_USE_MATERIALS.contains(event.getItem().getType())) {
                    if (handleAction(event.getPlayer(), event.getPlayer().getLocation().getChunk(), ActionType.ITEM)) {
                        event.setCancelled(true);
                        plugin.getMessageManager().sendMessage(event.getPlayer(), "protection.item-denied");
                    }
                }
            }
            return;
        }

        Material type = block.getType();
        if (CONTAINER_MATERIALS.contains(type) || DOOR_MATERIALS.contains(type)) {
            if (handleAction(event.getPlayer(), block.getChunk(), ActionType.SWITCH)) {
                event.setCancelled(true);
                plugin.getMessageManager().sendMessage(event.getPlayer(), "protection.interact-denied");
            }
        } else if (event.getItem() != null && ITEM_USE_MATERIALS.contains(event.getItem().getType())) {
            if (handleAction(event.getPlayer(), block.getChunk(), ActionType.ITEM)) {
                event.setCancelled(true);
                plugin.getMessageManager().sendMessage(event.getPlayer(), "protection.item-denied");
            }
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (handleAction(event.getPlayer(), event.getRightClicked().getLocation().getChunk(), ActionType.SWITCH)) {
            event.setCancelled(true);
            plugin.getMessageManager().sendMessage(event.getPlayer(), "protection.interact-denied");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        Chunk chunk = victim.getLocation().getChunk();
        if (plotManager.isClaimed(chunk)) return;
        if (TowniaAdminCommand.isBypassing(attacker.getUniqueId())) return;

        Optional<Plot> plotOpt = plotManager.getPlot(chunk);
        if (plotOpt.isEmpty()) return;

        Plot plot = plotOpt.get();
        Town town = townManager.getTown(plot.getTownUuid()).orElse(null);
        boolean pvpAllowed = false;
        if (town != null) pvpAllowed = town.hasPvp();
        if (plot.hasPvp()) pvpAllowed = true;

        if (!pvpAllowed) {
            event.setCancelled(true);
            plugin.getMessageManager().sendMessage(attacker, "protection.pvp-denied");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> canExplode(block.getChunk()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> canExplode(block.getChunk()));
    }

    private boolean canExplode(Chunk chunk) {
        Optional<Plot> plotOpt = plotManager.getPlot(chunk);
        if (plotOpt.isEmpty()) return false;
        Plot plot = plotOpt.get();
        if (plot.hasExplosions()) return false;
        var town = townManager.getTown(plot.getTownUuid()).orElse(null);
        return town == null || !town.hasExplosions();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (canFire(event.getBlock().getChunk())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (canFire(event.getBlock().getChunk())) {
            event.setCancelled(true);
        }
    }

    private boolean canFire(Chunk chunk) {
        Optional<Plot> plotOpt = plotManager.getPlot(chunk);
        if (plotOpt.isEmpty()) return false;
        Plot plot = plotOpt.get();
        if (plot.hasFire()) return false;
        var town = townManager.getTown(plot.getTownUuid()).orElse(null);
        return town == null || !town.hasFire();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            if (event.getEntity() instanceof org.bukkit.entity.Monster) {
                Chunk chunk = event.getLocation().getChunk();
                Optional<Plot> plotOpt = plotManager.getPlot(chunk);
                if (plotOpt.isPresent()) {
                    Plot plot = plotOpt.get();
                    boolean mobsAllowed = false;
                    var town = townManager.getTown(plot.getTownUuid()).orElse(null);
                    if (town != null) mobsAllowed = town.hasMobs();
                    if (plot.hasMobs()) mobsAllowed = true;
                    if (!mobsAllowed) {
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    private boolean handleAction(Player player, Chunk chunk, ActionType type) {
        if (TowniaAdminCommand.isBypassing(player.getUniqueId())) return false;
        
        Optional<Plot> plotOpt = plotManager.getPlot(chunk);
        if (plotOpt.isEmpty()) return false;
        
        Plot plot = plotOpt.get();
        Town town = townManager.getTown(plot.getTownUuid()).orElse(null);
        if (town == null) return false;

        if (town.getMayorUuid().equals(player.getUniqueId())) return false;

        if (player.getUniqueId().equals(plot.getOwnerUuid())) return false;

        Relationship rel = getRelationship(player, plot, town);
        
        String perms;
        if (plot.getOwnerUuid() != null) {
            perms = switch (rel) {
                case RESIDENT -> plot.getPermsResident();
                case ALLY -> plot.getPermsAlly();
                case NATION -> plot.getPermsNation();
                default -> plot.getPermsOutsider();
            };
            if (perms == null) perms = getTownPerms(town, rel);
        } else {
            perms = getTownPerms(town, rel);
        }
        
        if (perms == null) perms = "";
        return !net.azisaba.townia.data.PermissionMatrix.hasPerm(perms, type.id);
    }
    
    private String getTownPerms(Town town, Relationship rel) {
        return switch (rel) {
            case RESIDENT -> town.getPermsResident();
            case ALLY -> town.getPermsAlly();
            case NATION -> town.getPermsNation();
            default -> town.getPermsOutsider();
        };
    }

    private Relationship getRelationship(Player player, Plot plot, Town town) {
        Optional<TowniaPlayer> resOpt = residentManager.getResident(player.getUniqueId());
        if (resOpt.isEmpty()) return Relationship.OUTSIDER;
        TowniaPlayer resident = resOpt.get();

        if (plot.getOwnerUuid() != null) {
            if (town.getId().equals(resident.getTownUuid())) return Relationship.RESIDENT;

            TowniaPlayer owner = residentManager.getResident(plot.getOwnerUuid()).orElse(null);
            if (owner != null && owner.getFriends().contains(player.getUniqueId().toString())) {
                return Relationship.ALLY;
            }

            if (town.isInNation() && resident.isInTown()) {
                Town resTown = townManager.getTown(resident.getTownUuid()).orElse(null);
                if (resTown != null && town.getNationUuid().equals(resTown.getNationUuid())) {
                    return Relationship.NATION;
                }
            }
            
            return Relationship.OUTSIDER;
        } else {
            if (town.getId().equals(resident.getTownUuid())) return Relationship.RESIDENT;
            
            if (town.isInNation() && resident.isInTown()) {
                Town resTown = townManager.getTown(resident.getTownUuid()).orElse(null);
                if (resTown != null && town.getNationUuid().equals(resTown.getNationUuid())) {
                    return Relationship.NATION;
                }
            }

            return Relationship.OUTSIDER;
        }
    }
}
