package net.azisaba.townia.listener

import net.azisaba.townia.Townia
import net.azisaba.townia.command.TowniaAdminCommand
import net.azisaba.townia.data.PermissionMatrix.hasPerm
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.manager.NationManager
import net.azisaba.townia.manager.PlotManager
import net.azisaba.townia.manager.ResidentManager
import net.azisaba.townia.manager.TownManager
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import java.util.*

class PlotProtectionListener(private val plugin: Townia) : Listener {
    private val plotManager: PlotManager
    private val residentManager: ResidentManager
    private val townManager: TownManager
    private val nationManager: NationManager

    enum class ActionType(val id: Char) {
        BUILD('B'), DESTROY('D'), SWITCH('S'), ITEM('I')
    }

    enum class Relationship {
        RESIDENT, ALLY, NATION, OUTSIDER
    }

    init {
        this.plotManager = plugin.plotManager
        this.residentManager = plugin.residentManager
        this.townManager = plugin.townManager
        this.nationManager = plugin.nationManager
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (handleAction(event.getPlayer(), event.getBlock().getChunk(), ActionType.DESTROY)) {
            event.setCancelled(true)
            plugin.messageManager!!.sendMessage(event.getPlayer(), "protection.build-denied")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (handleAction(event.getPlayer(), event.getBlock().getChunk(), ActionType.BUILD)) {
            event.setCancelled(true)
            plugin.messageManager!!.sendMessage(event.getPlayer(), "protection.build-denied")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.getAction() == Action.PHYSICAL) {
            val block = event.getClickedBlock()
            if (block != null && block.getType() == Material.FARMLAND) {
                if (handleAction(event.getPlayer(), block.getChunk(), ActionType.DESTROY)) {
                    event.setCancelled(true)
                }
            }
            return
        }

        val block = event.getClickedBlock()
        if (block == null) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                if (event.getItem() != null && ITEM_USE_MATERIALS.contains(event.getItem()!!.getType())) {
                    if (handleAction(event.getPlayer(), event.getPlayer().getLocation().getChunk(), ActionType.ITEM)) {
                        event.setCancelled(true)
                        plugin.messageManager!!.sendMessage(event.getPlayer(), "protection.item-denied")
                    }
                }
            }
            return
        }

        val type = block.getType()
        if (CONTAINER_MATERIALS.contains(type) || DOOR_MATERIALS.contains(type)) {
            if (handleAction(event.getPlayer(), block.getChunk(), ActionType.SWITCH)) {
                event.setCancelled(true)
                plugin.messageManager!!.sendMessage(event.getPlayer(), "protection.interact-denied")
            }
        } else if (event.getItem() != null && ITEM_USE_MATERIALS.contains(event.getItem()!!.getType())) {
            if (handleAction(event.getPlayer(), block.getChunk(), ActionType.ITEM)) {
                event.setCancelled(true)
                plugin.messageManager!!.sendMessage(event.getPlayer(), "protection.item-denied")
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        if (handleAction(event.getPlayer(), event.getRightClicked().getLocation().getChunk(), ActionType.SWITCH)) {
            event.setCancelled(true)
            plugin.messageManager!!.sendMessage(event.getPlayer(), "protection.interact-denied")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val defender = event.entity as? Player ?: return
        val damager = event.damager as? Player ?: return

        val chunk: Chunk = defender.getLocation().getChunk()
        if (plotManager.isClaimed(chunk)) return
        if (TowniaAdminCommand.isBypassing(damager.getUniqueId())) return

        val plotOpt: Optional<Plot> = plotManager.getPlot(chunk)
        if (plotOpt.isEmpty()) return

        val plot = plotOpt.get()
        val town = townManager.getTown(plot.townUuid).orElse(null)
        var pvpAllowed = false
        if (town != null) pvpAllowed = town.hasPvp()
        if (plot.hasPvp()) pvpAllowed = true

        if (!pvpAllowed) {
            event.setCancelled(true)
            plugin.messageManager!!.sendMessage(damager, "protection.pvp-denied")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { block: Block? -> canExplode(block!!.getChunk()) }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { block: Block? -> canExplode(block!!.getChunk()) }
    }

    private fun canExplode(chunk: Chunk?): Boolean {
        val plotOpt: Optional<Plot> = plotManager.getPlot(chunk)
        if (plotOpt.isEmpty()) return false
        val plot = plotOpt.get()
        if (plot.hasExplosions()) return false
        val town = townManager.getTown(plot.townUuid).orElse(null)
        return town == null || !town.hasExplosions()
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockIgnite(event: BlockIgniteEvent) {
        if (canFire(event.getBlock().getChunk())) {
            event.setCancelled(true)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        if (canFire(event.getBlock().getChunk())) {
            event.setCancelled(true)
        }
    }

    private fun canFire(chunk: Chunk?): Boolean {
        val plotOpt: Optional<Plot> = plotManager.getPlot(chunk)
        if (plotOpt.isEmpty()) return false
        val plot = plotOpt.get()
        if (plot.hasFire()) return false
        val town = townManager.getTown(plot.townUuid).orElse(null)
        return town == null || !town.hasFire()
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            if (event.getEntity() is Monster) {
                val chunk = event.getLocation().getChunk()
                val plotOpt: Optional<Plot> = plotManager.getPlot(chunk)
                if (plotOpt.isPresent()) {
                    val plot = plotOpt.get()
                    var mobsAllowed = false
                    val town = townManager.getTown(plot.townUuid).orElse(null)
                    if (town != null) mobsAllowed = town.hasMobs()
                    if (plot.hasMobs()) mobsAllowed = true
                    if (!mobsAllowed) {
                        event.setCancelled(true)
                    }
                }
            }
        }
    }

    private fun handleAction(player: Player, chunk: Chunk?, type: ActionType): Boolean {
        if (TowniaAdminCommand.isBypassing(player.getUniqueId())) return false

        val plotOpt: Optional<Plot> = plotManager.getPlot(chunk)
        if (plotOpt.isEmpty()) return false

        val plot = plotOpt.get()
        val town = townManager.getTown(plot.townUuid).orElse(null)
        if (town == null) return false

        if (town.mayorUuid == player.getUniqueId()) return false

        if (player.getUniqueId() == plot.ownerUuid) return false

        val rel = getRelationship(player, plot, town)

        var perms: String?
        if (plot.ownerUuid != null) {
            perms = when (rel) {
                Relationship.RESIDENT -> plot.permsResident
                Relationship.ALLY -> plot.permsAlly
                Relationship.NATION -> plot.permsNation
                else -> plot.permsOutsider
            }
            if (perms == null) perms = getTownPerms(town, rel)
        } else {
            perms = getTownPerms(town, rel)
        }

        if (perms == null) perms = ""
        return !hasPerm(perms, type.id)
    }

    private fun getTownPerms(town: Town, rel: Relationship): String? {
        return when (rel) {
            Relationship.RESIDENT -> town.permsResident
            Relationship.ALLY -> town.permsAlly
            Relationship.NATION -> town.permsNation
            else -> town.permsOutsider
        }
    }

    private fun getRelationship(player: Player, plot: Plot, town: Town): Relationship {
        val resOpt: Optional<TowniaPlayer> = residentManager.getResident(player.getUniqueId())
        if (resOpt.isEmpty()) return Relationship.OUTSIDER
        val resident = resOpt.get()

        if (plot.ownerUuid != null) {
            if (town.id == resident.townUuid) return Relationship.RESIDENT

            val owner = residentManager.getResident(plot.ownerUuid).orElse(null)
            if (owner != null && owner.friends!!.contains(player.getUniqueId().toString())) {
                return Relationship.ALLY
            }

            if (town.isInNation && resident.isInTown) {
                val resTown = townManager.getTown(resident.townUuid).orElse(null)
                if (resTown != null && town.nationUuid == resTown.nationUuid) {
                    return Relationship.NATION
                }
            }

            return Relationship.OUTSIDER
        } else {
            if (town.id == resident.townUuid) return Relationship.RESIDENT

            if (town.isInNation && resident.isInTown) {
                val resTown = townManager.getTown(resident.townUuid).orElse(null)
                if (resTown != null && town.nationUuid == resTown.nationUuid) {
                    return Relationship.NATION
                }
            }

            return Relationship.OUTSIDER
        }
    }

    companion object {
        private val CONTAINER_MATERIALS: MutableSet<Material?> = EnumSet.of<Material?>(
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
        )

        private val DOOR_MATERIALS: MutableSet<Material?> = EnumSet.of<Material?>(
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
        )

        private val ITEM_USE_MATERIALS: MutableSet<Material?> = EnumSet.of<Material?>(
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
        )
    }
}