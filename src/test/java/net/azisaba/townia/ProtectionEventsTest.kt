package net.azisaba.townia

import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.listener.PlotProtectionListener
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Boat
import org.bukkit.entity.Cow
import org.bukkit.entity.Enderman
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Minecart
import org.bukkit.entity.Painting
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.UUID

/** The protection added since 1.3.3, one Bukkit event at a time. */
class ProtectionEventsTest {

    private class Fixture(val env: TowniaTestEnv) {
        val ownerUuid: UUID = UUID.randomUUID()
        val owner: Player
        val member: Player
        val outsider: Player
        val mayor: Player
        val alpha: UUID
        val beta: UUID
        val listener: PlotProtectionListener

        init {
            mayor = player("Mayor", 5)
            owner = env.mockPlayer(ownerUuid, "Owner", at(0))
            env.residentManager.getOrCreate(owner)
            member = player("Member", 0)
            outsider = player("Outsider", 0)
            alpha = env.townManager.createTown("alpha", mayor.uniqueId).id!!
            env.residentManager.setTown(ownerUuid, alpha, TownRank.RESIDENT)
            env.residentManager.setTown(member.uniqueId, alpha, TownRank.RESIDENT)
            beta = env.townManager.createTown("beta", outsider.uniqueId).id!!
            env.claimChunk(0, 0, alpha)   // Owner's plot
            env.claimChunk(1, 0, alpha)   // the town's own land
            env.claimChunk(3, 0, beta)
            env.plotManager.transferOwnership("world", 0, 0, ownerUuid)
            listener = PlotProtectionListener(env.plugin)
        }

        fun at(chunkX: Int) = Location(env.world, chunkX * 16 + 5.0, 64.0, 5.0)

        fun player(name: String, chunkX: Int): Player {
            val p = env.mockPlayer(UUID.randomUUID(), name, at(chunkX))
            env.residentManager.getOrCreate(p)
            return p
        }

        fun move(p: Player, chunkX: Int) {
            whenever(p.location).thenReturn(at(chunkX))
        }

        fun block(x: Int, z: Int, type: Material = Material.STONE, y: Int = 64): Block {
            val b = mock<Block>()
            whenever(b.world).thenReturn(env.world)
            whenever(b.x).thenReturn(x)
            whenever(b.y).thenReturn(y)
            whenever(b.z).thenReturn(z)
            whenever(b.type).thenReturn(type)
            whenever(b.location).thenReturn(Location(env.world, x.toDouble(), y.toDouble(), z.toDouble()))
            whenever(b.getRelative(any<BlockFace>())).doAnswer { inv ->
                val f = inv.getArgument<BlockFace>(0)
                block(x + f.modX, z + f.modZ, Material.AIR, y + f.modY)
            }
            return b
        }

        inline fun <reified T : Entity> entity(chunkX: Int): T {
            val e = mock<T>()
            whenever(e.location).thenReturn(at(chunkX))
            whenever(e.world).thenReturn(env.world)
            return e
        }

        fun told(p: Player) = env.sentMessages.filter { it.recipient === p }.map { it.key }
    }

    @Test
    fun `buckets need the item permission in someone else's plot`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            fun empty(who: Player, chunkX: Int): PlayerBucketEmptyEvent = f.block(chunkX * 16 + 5, 5).let { b -> mock<PlayerBucketEmptyEvent>().also {
                whenever(it.player).thenReturn(who)
                whenever(it.block).thenReturn(b)
                whenever(it.bucket).thenReturn(Material.WATER_BUCKET)
                f.listener.onBucketEmpty(it)
            } }
            verify(empty(f.outsider, 0)).isCancelled = true
            assertEquals(listOf("protection.item-denied"), f.told(f.outsider))
            verify(empty(f.owner, 0), never()).isCancelled = true
            verify(empty(f.outsider, 6), never()).isCancelled = true   // wilderness allows item use by default

            val fillBlock = f.block(5, 5)
            val fill = mock<PlayerBucketFillEvent>()
            whenever(fill.player).thenReturn(f.member)
            whenever(fill.block).thenReturn(fillBlock)
            whenever(fill.bucket).thenReturn(Material.BUCKET)
            f.listener.onBucketFill(fill)
            verify(fill).isCancelled = true   // another member of the town, but not the plot's owner or friend
        }
    }

    @Test
    fun `minecarts and chest boats are kept from strangers, plain boats are not watched`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            fun destroy(vehicle: org.bukkit.entity.Vehicle, by: Entity?): VehicleDestroyEvent = mock<VehicleDestroyEvent>().also {
                whenever(it.vehicle).thenReturn(vehicle)
                whenever(it.attacker).thenReturn(by)
                f.listener.onVehicleDestroy(it)
            }
            verify(destroy(f.entity<Minecart>(0), f.outsider)).isCancelled = true
            assertEquals(listOf("protection.destroy-denied"), f.told(f.outsider))
            verify(destroy(f.entity<Minecart>(0), f.owner), never()).isCancelled = true
            verify(destroy(f.entity<Minecart>(6), f.outsider), never()).isCancelled = true
            verify(destroy(f.entity<Boat>(0), f.outsider), never()).isCancelled = true
            verify(destroy(f.entity<Minecart>(0), null), never()).isCancelled = true
            // An arrow counts as its shooter.
            val arrow = mock<org.bukkit.entity.Arrow>()
            whenever(arrow.shooter).thenReturn(f.outsider)
            verify(destroy(f.entity<Minecart>(0), arrow)).isCancelled = true
        }
    }

    @Test
    fun `explosions spare frames, armour stands and animals where explosions are off`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            fun hurt(entity: Entity, cause: EntityDamageEvent.DamageCause): EntityDamageEvent = mock<EntityDamageEvent>().also {
                whenever(it.entity).thenReturn(entity)
                whenever(it.cause).thenReturn(cause)
                f.listener.onExplosionDamage(it)
            }
            verify(hurt(f.entity<ItemFrame>(1), EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)).isCancelled = true
            verify(hurt(f.entity<Cow>(0), EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)).isCancelled = true
            verify(hurt(f.entity<Zombie>(1), EntityDamageEvent.DamageCause.ENTITY_EXPLOSION), never()).isCancelled = true
            verify(hurt(f.entity<ItemFrame>(1), EntityDamageEvent.DamageCause.FIRE), never()).isCancelled = true

            env.townManager.getTown(f.alpha).get().setExplosions(true)
            verify(hurt(f.entity<ItemFrame>(1), EntityDamageEvent.DamageCause.ENTITY_EXPLOSION), never()).isCancelled = true

            fun breakHanging(chunkX: Int, cause: HangingBreakEvent.RemoveCause): HangingBreakEvent = f.entity<Painting>(chunkX).let { painting -> mock<HangingBreakEvent>().also {
                whenever(it.entity).thenReturn(painting)
                whenever(it.cause).thenReturn(cause)
                f.listener.onHangingBreak(it)
            } }
            verify(breakHanging(3, HangingBreakEvent.RemoveCause.EXPLOSION)).isCancelled = true
            verify(breakHanging(3, HangingBreakEvent.RemoveCause.ENTITY), never()).isCancelled = true
            verify(breakHanging(1, HangingBreakEvent.RemoveCause.EXPLOSION), never()).isCancelled = true   // alpha allows them now
        }
    }

    @Test
    fun `with friendly fire off, members and allies cannot hurt each other outside an arena`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            env.config["combat.friendly-fire"] = false
            env.towniaConfig.reload()
            val f = Fixture(env)
            env.townManager.getTown(f.alpha).get().setPvp(true)
            env.townManager.getTown(f.beta).get().setPvp(true)
            fun hit(by: Player, victim: Player): EntityDamageByEntityEvent = mock<EntityDamageByEntityEvent>().also {
                whenever(it.damager).thenReturn(by)
                whenever(it.entity).thenReturn(victim)
                f.listener.onEntityDamageByEntity(it)
            }
            f.move(f.member, 1)
            f.move(f.owner, 1)
            verify(hit(f.member, f.owner)).isCancelled = true
            assertEquals(listOf("protection.friendly-fire"), f.told(f.member))

            // Strangers fight where PvP is on.
            f.move(f.outsider, 1)
            verify(hit(f.outsider, f.member), never()).isCancelled = true

            // Allied nations are friends too.
            env.nationManager.createNation("north", f.alpha, f.mayor.uniqueId)
            env.nationManager.createNation("south", f.beta, f.outsider.uniqueId)
            val north = env.nationManager.getNationByName("north").get()
            val south = env.nationManager.getNationByName("south").get()
            north.allies.add(south.id)
            south.allies.add(north.id)
            verify(hit(f.outsider, f.member)).isCancelled = true

            // An arena is for fighting.
            env.plotManager.getPlot("world", 1, 0).get().let {
                it.plotType = PlotType.ARENA
                env.plotManager.persistPlot(it)
            }
            verify(hit(f.member, f.owner), never()).isCancelled = true
        }
    }

    @Test
    fun `no PvP at all in the worlds where it is switched off`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            env.config["combat.pvp-disabled-worlds"] = listOf("world")
            env.towniaConfig.reload()
            val f = Fixture(env)
            f.move(f.member, 6)
            f.move(f.outsider, 6)
            val event = mock<EntityDamageByEntityEvent>()
            whenever(event.damager).thenReturn(f.outsider)
            whenever(event.entity).thenReturn(f.member)
            f.listener.onEntityDamageByEntity(event)
            verify(event).isCancelled = true
            assertEquals(listOf("protection.pvp-denied"), f.told(f.outsider))
        }
    }

    @Test
    fun `stripping a log needs the destroy permission and endermen keep their hands off towns`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            fun change(by: Entity, chunkX: Int, from: Material, to: Material): EntityChangeBlockEvent = f.block(chunkX * 16 + 5, 5, from).let { b -> mock<EntityChangeBlockEvent>().also {
                whenever(it.entity).thenReturn(by)
                whenever(it.block).thenReturn(b)
                whenever(it.to).thenReturn(to)
                f.listener.onEntityChangeBlock(it)
            } }
            verify(change(f.outsider, 0, Material.OAK_LOG, Material.STRIPPED_OAK_LOG)).isCancelled = true
            verify(change(f.owner, 0, Material.OAK_LOG, Material.STRIPPED_OAK_LOG), never()).isCancelled = true
            verify(change(f.outsider, 0, Material.CAKE, Material.CAKE), never()).isCancelled = true
            verify(change(f.outsider, 0, Material.GRASS_BLOCK, Material.FARMLAND)).isCancelled = true
            verify(change(f.entity<Enderman>(1), 1, Material.GRASS_BLOCK, Material.AIR)).isCancelled = true
            verify(change(f.entity<Enderman>(6), 6, Material.GRASS_BLOCK, Material.AIR), never()).isCancelled = true
        }
    }

    @Test
    fun `pistons do not push across a claim border either way`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            // Chunk 1 is alpha's (x 16..31), chunk 2 is wilderness.
            fun extend(pistonX: Int, moved: List<Block>): BlockPistonExtendEvent = f.block(pistonX, 5).let { b -> mock<BlockPistonExtendEvent>().also {
                whenever(it.block).thenReturn(b)
                whenever(it.blocks).thenReturn(moved)
                whenever(it.direction).thenReturn(BlockFace.EAST)
                f.listener.onPistonExtend(it)
            } }
            verify(extend(31, emptyList())).isCancelled = true                     // the head reaches the wilderness
            verify(extend(29, listOf(f.block(30, 5))), never()).isCancelled = true  // all inside alpha
            verify(extend(30, listOf(f.block(31, 5)))).isCancelled = true            // the block is pushed out

            fun retract(pistonX: Int, moved: List<Block>): BlockPistonRetractEvent = f.block(pistonX, 5).let { b -> mock<BlockPistonRetractEvent>().also {
                whenever(it.block).thenReturn(b)
                whenever(it.blocks).thenReturn(moved)
                whenever(it.direction).thenReturn(BlockFace.WEST)
                f.listener.onPistonRetract(it)
            } }
            verify(retract(31, listOf(f.block(33, 5)))).isCancelled = true          // pulled in from the wilderness
            verify(retract(31, emptyList()), never()).isCancelled = true             // a piston on the edge may still retract
        }
    }

    @Test
    fun `right-clicking an entity in someone's plot needs the switch permission`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val f = Fixture(env)
            fun click(who: Player, target: Entity): PlayerInteractEntityEvent = mock<PlayerInteractEntityEvent>().also {
                whenever(it.player).thenReturn(who)
                whenever(it.rightClicked).thenReturn(target)
                f.listener.onPlayerInteractEntity(it)
            }
            verify(click(f.outsider, f.entity<ItemFrame>(0))).isCancelled = true
            assertEquals(listOf("protection.interact-denied"), f.told(f.outsider))
            verify(click(f.owner, f.entity<ItemFrame>(0)), never()).isCancelled = true
            verify(click(f.outsider, f.member), never()).isCancelled = true   // players are not protected this way
        }
    }
}
