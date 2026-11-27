package net.azisaba.townia

import net.azisaba.townia.command.PlotCommand
import net.azisaba.townia.data.TownRank
import org.bukkit.Chunk
import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path
import java.util.UUID

/** The per-town plot index follows forced claims and removals; Towny's max plots per resident holds. */
class PlotIndexAndLimitTest {

    private fun TowniaTestEnv.chunk(x: Int): Chunk = mock<Chunk>().also {
        whenever(it.world).thenReturn(world)
        whenever(it.x).thenReturn(x)
        whenever(it.z).thenReturn(0)
    }

    @Test
    fun `forcing a chunk to another town, unclaiming and removing it keep the town lists right`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val a = env.townManager.createTown("alpha", UUID.randomUUID()).id!!
            val b = env.townManager.createTown("beta", UUID.randomUUID()).id!!
            env.claimChunk(0, 0, a)
            env.claimChunk(1, 0, a)
            env.plotManager.forceClaimChunk(b, env.chunk(1))
            assertEquals(listOf(0), env.plotManager.getPlotsByTown(a).map { it.chunkX })
            assertEquals(listOf(1), env.plotManager.getPlotsByTown(b).map { it.chunkX })
            assertEquals(b, env.plotManager.getPlot("world", 1, 0).get().townUuid)

            env.plotManager.forceUnclaimChunk(env.chunk(1))
            assertTrue(env.plotManager.getPlotsByTown(b).isEmpty())
            assertFalse(env.plotManager.getPlot("world", 1, 0).isPresent)

            assertTrue(env.plotManager.removePlot("world", 0, 0))
            assertTrue(env.plotManager.getPlotsByTown(a).isEmpty())

            // And all of it survives a restart.
            env.databaseManager.flush()
            env.reloadManagers()
            assertTrue(env.plotManager.getPlotsByTown(a).isEmpty() && env.plotManager.getPlotsByTown(b).isEmpty())
        }
    }

    @Test
    fun `a resident cannot buy more plots than the server allows`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            env.config["claiming.max-plots-per-resident"] = 1
            env.towniaConfig.reload()
            val economy = mock<net.milkbowl.vault.economy.Economy>()
            whenever(economy.has(any<org.bukkit.OfflinePlayer>(), any<Double>())).thenReturn(true)
            whenever(economy.withdrawPlayer(any<org.bukkit.OfflinePlayer>(), any<Double>()))
                .thenReturn(net.milkbowl.vault.economy.EconomyResponse(0.0, 0.0, net.milkbowl.vault.economy.EconomyResponse.ResponseType.SUCCESS, ""))
            whenever(env.plugin.economy).thenReturn(economy)
            whenever(env.plugin.hasEconomy()).thenReturn(true)
            val mayor = env.mockPlayer(UUID.randomUUID(), "Mayor")
            val buyer = env.mockPlayer(UUID.randomUUID(), "Buyer", Location(env.world, 21.0, 64.0, 5.0))
            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(buyer)
            val town = env.townManager.createTown("alpha", mayor.uniqueId).id!!
            env.residentManager.setTown(buyer.uniqueId, town, TownRank.RESIDENT)
            env.claimChunk(0, 0, town)
            env.claimChunk(1, 0, town)
            env.plotManager.transferOwnership("world", 0, 0, buyer.uniqueId)
            env.plotManager.getPlot("world", 1, 0).get().let {
                it.isForSale = true
                it.price = 0.0
                env.plotManager.persistPlot(it)
            }
            val here = env.chunk(1)
            whenever(env.world.getChunkAt(any<Location>())).thenReturn(here)
            env.sentMessages.clear()
            PlotCommand(env.plugin).onCommand(buyer, env.dummyCommand("plot"), "plot", arrayOf("claim"))
            assertEquals(listOf("plot.max-plots"), env.sentMessages.filter { it.recipient === buyer }.map { it.key })
            assertNull(env.plotManager.getPlot("world", 1, 0).get().ownerUuid)
        }
    }
}
