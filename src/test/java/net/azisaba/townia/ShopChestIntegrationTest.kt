package net.azisaba.townia

import net.azisaba.townia.command.PlotCommand
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.hook.ShopChestHook
import net.azisaba.townia.listener.PlotProtectionListener
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Tag
import org.mockito.kotlin.any
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.UUID

class ShopChestIntegrationTest {

    @Test
    fun `plot set shop and commercial set PlotType SHOP`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val loc = Location(env.world, 160.0, 64.0, 160.0)
            val chunk = mock<Chunk>()
            whenever(chunk.world).thenReturn(env.world)
            whenever(chunk.x).thenReturn(10)
            whenever(chunk.z).thenReturn(10)
            loc.chunk // just to be sure
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice", loc)
            val spyLoc = org.mockito.Mockito.spy(loc)
            whenever(spyLoc.chunk).thenReturn(chunk)
            whenever(mayor.location).thenReturn(spyLoc)

            env.residentManager.getOrCreate(mayor)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            env.claimChunk(10, 10, town.id!!)

            val plotCmd = PlotCommand(env.plugin)
            plotCmd.onCommand(mayor, env.dummyCommand("plot"), "plot", arrayOf("set", "shop"))
            assertEquals(PlotType.SHOP, env.plotManager.getPlot("world", 10, 10).orElseThrow().plotType)

            plotCmd.onCommand(mayor, env.dummyCommand("plot"), "plot", arrayOf("set", "default"))
            assertEquals(PlotType.DEFAULT, env.plotManager.getPlot("world", 10, 10).orElseThrow().plotType)

            plotCmd.onCommand(mayor, env.dummyCommand("plot"), "plot", arrayOf("set", "commercial"))
            assertEquals(PlotType.SHOP, env.plotManager.getPlot("world", 10, 10).orElseThrow().plotType)
        }
    }

    @Test
    fun `ShopChest creation requires SHOP plot and BUILD permission`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val outsiderUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val outsider = env.mockPlayer(outsiderUuid, "OutsiderBob")

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(outsider)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            env.claimChunk(10, 10, town.id!!)
            val loc = Location(env.world, 165.0, 64.0, 165.0)
            val emptyTag = object : Tag<Material> {
                override fun isTagged(item: Material) = false
                override fun getValues() = emptySet<Material>()
                override fun getKey() = org.bukkit.NamespacedKey.minecraft("empty")
            }
            whenever(env.server.getTag(any(), any(), any<Class<Material>>())).thenReturn(emptyTag)
            val serverField = Bukkit::class.java.getDeclaredField("server")
            serverField.isAccessible = true
            val prevServer = serverField.get(null)
            serverField.set(null, env.server)
            val listener = PlotProtectionListener(env.plugin)

            // DEFAULT plot: even mayor cannot create shop (Towny commercial plot rule)
            assertFalse(ShopChestHook.canCreateShop(env.plugin, mayor, loc, listener))
            assertTrue(env.sentMessages.any { it.recipient === mayor && it.key == "protection.shop-plot-required" })

            // SHOP plot: mayor can create shop, outsider without BUILD cannot
            env.plotManager.setPlotType("world", 10, 10, PlotType.SHOP)
            env.sentMessages.clear()
            assertTrue(ShopChestHook.canCreateShop(env.plugin, mayor, loc, listener))
            assertFalse(ShopChestHook.canCreateShop(env.plugin, outsider, loc, listener))
            assertTrue(env.sentMessages.any { it.recipient === outsider && it.key == "protection.build-denied" })
        }
    }

    @Test
    fun `outsider can interact with ShopChest on SHOP plot but not regular chests or as outlaw`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val outsiderUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val outsider = env.mockPlayer(outsiderUuid, "OutsiderBob")

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(outsider)
            val town = env.townManager.createTown("berry_town", mayorUuid)
            env.claimChunk(10, 10, town.id!!)
            env.plotManager.setPlotType("world", 10, 10, PlotType.SHOP)

            val chestLoc = Location(env.world, 165.0, 64.0, 165.0)
            val block = mock<Block>()
            whenever(block.world).thenReturn(env.world)
            whenever(block.x).thenReturn(165)
            whenever(block.y).thenReturn(64)
            whenever(block.z).thenReturn(165)
            whenever(block.location).thenReturn(chestLoc)
            whenever(block.type).thenReturn(Material.CHEST)

            val emptyTag = object : Tag<Material> {
                override fun isTagged(item: Material) = false
                override fun getValues() = emptySet<Material>()
                override fun getKey() = org.bukkit.NamespacedKey.minecraft("empty")
            }
            whenever(env.server.getTag(any(), any(), any<Class<Material>>())).thenReturn(emptyTag)
            val serverField = Bukkit::class.java.getDeclaredField("server")
            serverField.isAccessible = true
            val prevServer = serverField.get(null)
            serverField.set(null, env.server)
            val listener = PlotProtectionListener(env.plugin)
            try {
                // 1. Regular chest on SHOP plot: outsider is still blocked
                ShopChestHook.shopCheckerOverride = { false }
                val regularEvent = PlayerInteractEvent(outsider, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.NORTH)
                listener.onPlayerInteract(regularEvent)
                assertEquals(Event.Result.DENY, regularEvent.useInteractedBlock())

                // 2. ShopChest on SHOP plot: outsider is allowed to interact
                ShopChestHook.shopCheckerOverride = { true }
                val shopEvent = PlayerInteractEvent(outsider, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.NORTH)
                listener.onPlayerInteract(shopEvent)
                assertFalse(shopEvent.useInteractedBlock() == Event.Result.DENY)

                // 3. Outlaw on SHOP plot: still blocked even for ShopChest
                env.townManager.addOutlaw(town.id, outsiderUuid)
                val outlawEvent = PlayerInteractEvent(outsider, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.NORTH)
                listener.onPlayerInteract(outlawEvent)
                assertEquals(Event.Result.DENY, outlawEvent.useInteractedBlock())
            } finally {
                ShopChestHook.shopCheckerOverride = null
                serverField.set(null, prevServer)
            }
        }
    }
}
