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
import net.azisaba.townia.util.ChunkKey
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.Material
import org.bukkit.Tag
import io.papermc.paper.event.player.PlayerOpenSignEvent
import org.bukkit.block.Block
import org.bukkit.block.data.type.Lectern as LecternData
import org.bukkit.event.player.PlayerTakeLecternBookEvent
import net.azisaba.townia.data.PlotType
import org.bukkit.entity.AbstractVillager
import org.bukkit.entity.Animals
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.WaterMob
import org.bukkit.entity.Hanging
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.block.data.Directional
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Vehicle
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import java.util.*

class PlotProtectionListener(private val plugin: Townia) : Listener {
    private val plotManager: PlotManager = plugin.plotManager
    private val residentManager: ResidentManager = plugin.residentManager
    private val townManager: TownManager = plugin.townManager
    private val nationManager: NationManager = plugin.nationManager

    enum class ActionType(val id: Char) {
        BUILD('B'), DESTROY('D'), SWITCH('S'), ITEM('I')
    }

    enum class Relationship {
        RESIDENT, ALLY, NATION, OUTSIDER
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (handleAction(event.player, event.getBlock(), ActionType.DESTROY)) {
            event.isCancelled = true
            plugin.messageManager.sendMessage(event.player, "protection.destroy-denied")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (handleAction(event.getPlayer(), event.getBlock(), ActionType.BUILD)) {
            event.isCancelled = true
            plugin.messageManager.sendMessage(event.getPlayer(), "protection.build-denied")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action == Action.PHYSICAL) {
            val block = event.clickedBlock
            if (block != null && block.type == Material.FARMLAND) {
                if (handleAction(event.getPlayer(), block, ActionType.DESTROY)) {
                    event.setCancelled(true)
                }
            } else if (block != null && (Tag.PRESSURE_PLATES.isTagged(block.type) || block.type == Material.TRIPWIRE)) {
                if (handleAction(event.getPlayer(), block, ActionType.SWITCH)) {
                    event.setCancelled(true)
                }
            }
            return
        }

        val block = event.clickedBlock
        if (block == null) {
            if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
                if (event.item != null && ITEM_USE_MATERIALS.contains(event.item!!.type)) {
                    if (handleAction(event.getPlayer(), event.getPlayer().location, ActionType.ITEM)) {
                        event.setCancelled(true)
                        plugin.messageManager.sendMessage(event.getPlayer(), "protection.item-denied")
                    }
                }
            }
            return
        }

        // Breaking is covered by BlockBreakEvent; only right clicks are "use" actions.
        // Without this, merely punching a chest or door would show the interact-denied message.
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        if (Tag.ALL_SIGNS.isTagged(block.type)) {
            if (handleAction(event.player, block, ActionType.BUILD)) {
                event.isCancelled = true
                plugin.messageManager.sendMessage(event.player, "protection.build-denied")
                return
            }
        }

        if (block.type == Material.LECTERN && event.action == Action.RIGHT_CLICK_BLOCK) {
            val lecternData = block.blockData as? LecternData
            if (lecternData != null && !lecternData.hasBook()) {
                val itemType = event.item?.type
                if (itemType == Material.WRITABLE_BOOK || itemType == Material.WRITTEN_BOOK) {
                    if (handleAction(event.player, block, ActionType.BUILD)) {
                        event.isCancelled = true
                        plugin.messageManager.sendMessage(event.player, "protection.build-denied")
                        return
                    }
                }
            }
        }

        val type = block.type
        if (CONTAINER_MATERIALS.contains(type) || DOOR_MATERIALS.contains(type)) {
            if (handleAction(event.getPlayer(), block, ActionType.SWITCH)) {
                event.setCancelled(true)
                plugin.messageManager.sendMessage(event.getPlayer(), "protection.interact-denied")
            }
        } else if (event.item != null && ITEM_USE_MATERIALS.contains(event.item!!.type)) {
            if (handleAction(event.getPlayer(), block, ActionType.ITEM)) {
                event.setCancelled(true)
                plugin.messageManager.sendMessage(event.getPlayer(), "protection.item-denied")
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerOpenSign(event: PlayerOpenSignEvent) {
        if (handleAction(event.player, event.sign.block, ActionType.BUILD)) {
            event.isCancelled = true
            plugin.messageManager.sendMessage(event.player, "protection.build-denied")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        if (handleAction(event.player, event.block, ActionType.BUILD)) {
            event.isCancelled = true
            plugin.messageManager.sendMessage(event.player, "protection.build-denied")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerTakeLecternBook(event: PlayerTakeLecternBookEvent) {
        if (handleAction(event.player, event.lectern.block, ActionType.DESTROY)) {
            event.isCancelled = true
            plugin.messageManager.sendMessage(event.player, "protection.destroy-denied")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHangingBreakByEntity(event: HangingBreakByEntityEvent) {
        val remover = event.remover ?: return
        val player = resolvePlayerDamager(remover)
        if (player != null) {
            if (handleAction(player, event.entity.location, ActionType.DESTROY)) {
                event.isCancelled = true
                plugin.messageManager.sendMessage(player, "protection.destroy-denied")
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        if (handleAction(event.getPlayer(), event.rightClicked.location, ActionType.SWITCH)) {
            event.isCancelled = true
            plugin.messageManager.sendMessage(event.getPlayer(), "protection.interact-denied")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val damagerPlayer = resolvePlayerDamager(event.damager)
        val entity = event.entity

        // 額縁、絵画、防具立てなどの保護
        if (entity is Hanging || entity is ArmorStand) {
            if (damagerPlayer != null) {
                if (handleAction(damagerPlayer, entity.location, ActionType.DESTROY)) {
                    event.isCancelled = true
                    plugin.messageManager.sendMessage(damagerPlayer, "protection.destroy-denied")
                    return
                }
            }
            return
        }

        // Passive animals, villagers and the like are protected like blocks (Towny "protected mobs").
        if (entity !is Player) {
            if (damagerPlayer != null && isProtectedMob(entity)) {
                if (handleAction(damagerPlayer, entity.location, ActionType.DESTROY)) {
                    event.isCancelled = true
                    plugin.messageManager.sendMessage(damagerPlayer, "protection.destroy-denied")
                }
            }
            return
        }

        val defender = entity
        val damager = damagerPlayer ?: return
        if (TowniaAdminCommand.isBypassing(damager.uniqueId)) return

        // Like Towny, PvP must be allowed both where the victim stands and where the attacker stands.
        if (!isPvpAllowedAt(defender.location, defender, damager) ||
            !isPvpAllowedAt(damager.location, defender, damager)
        ) {
            event.isCancelled = true
            plugin.messageManager.sendMessage(damager, "protection.pvp-denied")
        }
    }

    private fun isProtectedMob(entity: org.bukkit.entity.Entity): Boolean {
        return entity is Animals || entity is WaterMob || entity is AbstractVillager ||
                entity is Vehicle || entity is EnderCrystal
    }

    private fun isPvpAllowedAt(location: Location, defender: Player, damager: Player): Boolean {
        val plot = plotManager.getPlotAt(location)
        if (plot == null) {
            return !plugin.towniaConfig.isWorldAllowed(location.world.name) || plugin.towniaConfig.wildernessPvp
        }
        if (plot.plotType == PlotType.ARENA) return true

        val town = townManager.getTown(plot.townUuid).orElse(null)
        if (town != null && town.outlaws.contains(defender.uniqueId)) {
            val attacker = residentManager.getResident(damager.uniqueId).orElse(null)
            if (attacker != null && attacker.townUuid == town.id) return true
        }
        return (town != null && town.hasPvp()) || plot.hasPvp()
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        if (handleAction(player, event.entity.location, ActionType.BUILD)) {
            event.isCancelled = true
            plugin.messageManager.sendMessage(player, "protection.build-denied")
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
        if (handleAction(event.player, event.rightClicked.location, ActionType.SWITCH)) {
            event.isCancelled = true
            plugin.messageManager.sendMessage(event.player, "protection.interact-denied")
        }
    }

    /** Endermen, withers, ravagers etc. may only change blocks where mobs are allowed. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val entity = event.entity
        if (entity is Player || entity is FallingBlock || entity !is Monster) return
        val block = event.block
        val plot = plotManager.getPlotAt(block.world, block.x, block.z) ?: return
        val town = townManager.getTown(plot.townUuid).orElse(null)
        if (!plot.hasMobs() && (town == null || !town.hasMobs())) event.isCancelled = true
    }

    /** Liquids may not flow across a claim border (wilderness <-> town, or between two towns). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockFromTo(event: BlockFromToEvent) {
        val from = event.block
        val to = event.toBlock
        if (from.x shr 4 == to.x shr 4 && from.z shr 4 == to.z shr 4) return
        if (ownerTown(from) != ownerTown(to)) event.isCancelled = true
    }

    /** Pistons may not push or pull blocks across a claim border. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (crossesBorder(event.block, event.blocks, event.direction)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (crossesBorder(event.block, event.blocks, event.direction)) event.isCancelled = true
    }

    /**
     * Hoppers, droppers and dispensers must not move items between containers that belong to different
     * owners (another town, wilderness, or a different plot owner), otherwise a hopper placed on one's own
     * land could drain or fill a chest across the border.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        // Fires every hopper tick: use Inventory#getLocation (no holder snapshot) and bail out early.
        val from = event.source.location ?: return
        val to = event.destination.location ?: return
        val fromWorld = from.world ?: return
        val toWorld = to.world ?: return
        if (fromWorld == toWorld && from.blockX shr 4 == to.blockX shr 4 && from.blockZ shr 4 == to.blockZ shr 4) return

        val fromPlot = plotManager.getPlotAt(fromWorld, from.blockX, from.blockZ)
        val toPlot = plotManager.getPlotAt(toWorld, to.blockX, to.blockZ)
        if (fromPlot === toPlot) return
        if (fromPlot == null || toPlot == null ||
            fromPlot.townUuid != toPlot.townUuid || fromPlot.ownerUuid != toPlot.ownerUuid
        ) {
            event.isCancelled = true
        }
    }

    /**
     * A dispenser may not place blocks, liquids, fire, boats, minecarts etc. into land owned by someone else
     * (another town, wilderness or a different plot owner). Plain item dropping is not restricted.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockDispense(event: BlockDispenseEvent) {
        val type = event.item.type
        if (!(type in ITEM_USE_MATERIALS || type == Material.TNT || Tag.SHULKER_BOXES.isTagged(type))) return
        val dispenser = event.block
        val facing = (dispenser.blockData as? Directional)?.facing ?: return
        val target = dispenser.getRelative(facing)
        if (dispenser.x shr 4 == target.x shr 4 && dispenser.z shr 4 == target.z shr 4) return

        val fromPlot = plotManager.getPlotAt(dispenser.world, dispenser.x, dispenser.z)
        val toPlot = plotManager.getPlotAt(target.world, target.x, target.z)
        if (fromPlot === toPlot) return
        if (fromPlot == null || toPlot == null ||
            fromPlot.townUuid != toPlot.townUuid || fromPlot.ownerUuid != toPlot.ownerUuid
        ) {
            event.isCancelled = true
        }
    }

    private fun ownerTown(block: Block): UUID? = plotManager.getPlotAt(block.world, block.x, block.z)?.townUuid

    private fun crossesBorder(piston: Block, moved: List<Block>, direction: org.bukkit.block.BlockFace): Boolean {
        val owner = ownerTown(piston)
        // The piston head / pushed blocks end up one step further in the push direction.
        // (Retracting pulls blocks the opposite way, but they stay within the head's neighbourhood.)
        val head = piston.getRelative(direction)
        if (ownerTown(head) != owner) return true
        for (block in moved) {
            if (ownerTown(block) != owner) return true
            if (ownerTown(block.getRelative(direction)) != owner) return true
        }
        return false
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        removeProtectedBlocks(event.blockList())
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        removeProtectedBlocks(event.blockList())
    }

    /** A big explosion touches many blocks but only a few chunks, so each chunk is evaluated once. */
    private fun removeProtectedBlocks(blocks: MutableList<Block>) {
        if (blocks.isEmpty()) return
        val verdicts = HashMap<ChunkKey, Boolean>()
        blocks.removeIf { block ->
            verdicts.getOrPut(ChunkKey(block.world.name, block.x shr 4, block.z shr 4)) { isExplosionProtected(block) }
        }
    }

    /** True when explosions must not damage the block at this position. */
    private fun isExplosionProtected(block: Block): Boolean {
        val plot = plotManager.getPlotAt(block.world, block.x, block.z)
            ?: return plugin.towniaConfig.isWorldAllowed(block.world.name) && !plugin.towniaConfig.wildernessExplosions
        if (plot.hasExplosions()) return false
        val town = townManager.getTown(plot.townUuid).orElse(null)
        return town == null || !town.hasExplosions()
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockIgnite(event: BlockIgniteEvent) {
        // Ignition by a player (flint and steel, fire charge) is governed by the item permission in
        // onPlayerInteract; the fire flag only controls spread, lava and lightning like in Towny.
        if (event.player != null) return
        if (isFireProtected(event.getBlock())) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        if (isFireProtected(event.getBlock())) {
            event.isCancelled = true
        }
    }

    private fun isFireProtected(block: Block): Boolean {
        val plot = plotManager.getPlotAt(block.world, block.x, block.z)
            ?: return plugin.towniaConfig.isWorldAllowed(block.world.name) && !plugin.towniaConfig.wildernessFire
        if (plot.hasFire()) return false
        val town = townManager.getTown(plot.townUuid).orElse(null)
        return town == null || !town.hasFire()
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (event.spawnReason == CreatureSpawnEvent.SpawnReason.NATURAL || event.spawnReason == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            if (event.getEntity() is Monster) {
                val location = event.location
                val plot = plotManager.getPlotAt(location)
                if (plot == null) {
                    if (plugin.towniaConfig.isWorldAllowed(location.world.name) && !plugin.towniaConfig.wildernessMobs) {
                        event.isCancelled = true
                    }
                } else {
                    var mobsAllowed = false
                    val town = townManager.getTown(plot.townUuid).orElse(null)
                    if (town != null) mobsAllowed = town.hasMobs()
                    if (plot.hasMobs()) mobsAllowed = true
                    if (!mobsAllowed) {
                        event.isCancelled = true
                    }
                }
            }
        }
    }

    private fun handleAction(player: Player, block: Block, type: ActionType): Boolean =
        handleAction(player, block.world, block.x, block.z, type)

    private fun handleAction(player: Player, location: Location, type: ActionType): Boolean =
        handleAction(player, location.world, location.blockX, location.blockZ, type)

    private fun handleAction(player: Player, world: World, blockX: Int, blockZ: Int, type: ActionType): Boolean {
        val plot = plotManager.getPlotAt(world, blockX, blockZ)
        if (plot == null) {
            // Nothing to protect: resolve the cheap checks before the bypass lookup.
            if (!plugin.towniaConfig.isWorldAllowed(world.name)) return false
            if (TowniaAdminCommand.isBypassing(player.uniqueId)) return false
            return !plugin.towniaConfig.isWildernessActionAllowed(
                when (type) {
                    ActionType.BUILD -> "build"
                    ActionType.DESTROY -> "destroy"
                    ActionType.SWITCH -> "switch"
                    ActionType.ITEM -> "item"
                }
            )
        }
        if (TowniaAdminCommand.isBypassing(player.uniqueId)) return false
        val town = townManager.getTown(plot.townUuid).orElse(null) ?: return false

        if (town.mayorUuid == player.uniqueId) return false

        if (player.uniqueId == plot.ownerUuid) return false

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
        val resOpt: Optional<TowniaPlayer> = residentManager.getResident(player.uniqueId)
        if (resOpt.isEmpty) return Relationship.OUTSIDER
        val resident = resOpt.get()

        if (plot.ownerUuid != null) {
            val owner = residentManager.getResident(plot.ownerUuid).orElse(null)
            if (owner?.friends?.contains(player.uniqueId.toString()) == true) {
                return Relationship.ALLY
            }

            if (town.id == resident.townUuid) return Relationship.RESIDENT

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

    private fun resolvePlayerDamager(damager: org.bukkit.entity.Entity): Player? {
        if (damager is Player) return damager
        if (damager is Projectile) {
            val shooter = damager.shooter
            if (shooter is Player) return shooter
        }
        return null
    }

    companion object {
        private val CONTAINER_MATERIALS: MutableSet<Material?> = EnumSet.of(
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

        private val DOOR_MATERIALS: MutableSet<Material?> = EnumSet.of(
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

        private val ITEM_USE_MATERIALS: MutableSet<Material?> = EnumSet.of(
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
