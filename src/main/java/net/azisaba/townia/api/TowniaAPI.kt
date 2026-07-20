package net.azisaba.townia.api

import net.azisaba.townia.Townia
import net.azisaba.townia.TowniaException
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaPlayer
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import java.util.Optional
import java.util.UUID

/**
 * Public integration API for Townia.
 *
 * All methods must be called from the Bukkit main thread. Returned domain objects are live
 * objects; change persistent state through this API instead of assigning their public fields.
 */
interface TowniaAPI {
    fun getTown(id: UUID): Optional<Town>
    fun getTown(name: String): Optional<Town>
    fun getTownOfPlayer(playerId: UUID): Optional<Town>
    fun towns(): List<Town>

    fun getNation(id: UUID): Optional<Nation>
    fun getNation(name: String): Optional<Nation>
    fun nations(): List<Nation>

    fun getResident(id: UUID): Optional<TowniaPlayer>
    fun getResident(name: String): Optional<TowniaPlayer>
    fun residents(townId: UUID): List<TowniaPlayer>
    fun residentsInNation(nationId: UUID): List<TowniaPlayer>

    fun getPlot(chunk: Chunk): Optional<Plot>
    fun getPlot(world: String, chunkX: Int, chunkZ: Int): Optional<Plot>
    fun isClaimed(chunk: Chunk): Boolean
    fun plots(townId: UUID): List<Plot>
    fun plotsOwnedBy(playerId: UUID): List<Plot>

    @Throws(TowniaException::class)
    fun createTown(name: String, mayorId: UUID): Town
    @Throws(TowniaException::class)
    fun deleteTown(townId: UUID)
    @Throws(TowniaException::class)
    fun renameTown(townId: UUID, name: String)
    @Throws(TowniaException::class)
    fun setTownSpawn(townId: UUID, location: Location)
    @Throws(TowniaException::class)
    fun setTownPublic(townId: UUID, isPublic: Boolean)
    @Throws(TowniaException::class)
    fun setTownMayor(townId: UUID, residentId: UUID)
    @Throws(TowniaException::class)
    fun addTownBalance(townId: UUID, amount: Double)
    @Throws(TowniaException::class)
    fun subtractTownBalance(townId: UUID, amount: Double)

    @Throws(TowniaException::class)
    fun createNation(name: String, capitalTownId: UUID, leaderId: UUID): Nation
    @Throws(TowniaException::class)
    fun deleteNation(nationId: UUID)
    @Throws(TowniaException::class)
    fun addTownToNation(nationId: UUID, townId: UUID)
    @Throws(TowniaException::class)
    fun removeTownFromNation(nationId: UUID, townId: UUID)
    @Throws(TowniaException::class)
    fun setNationLeader(nationId: UUID, residentId: UUID)

    @Throws(TowniaException::class)
    fun claim(townId: UUID, chunk: Chunk)
    @Throws(TowniaException::class)
    fun unclaim(townId: UUID, chunk: Chunk)
    @Throws(TowniaException::class)
    fun setPlotType(world: String, chunkX: Int, chunkZ: Int, type: PlotType)
    @Throws(TowniaException::class)
    fun setPlotForSale(world: String, chunkX: Int, chunkZ: Int, forSale: Boolean, price: Double = 0.0)
    @Throws(TowniaException::class)
    fun setPlotOwner(world: String, chunkX: Int, chunkZ: Int, ownerId: UUID?)

    @Throws(TowniaException::class)
    fun setResidentTown(residentId: UUID, townId: UUID, rank: TownRank = TownRank.RESIDENT)
    @Throws(TowniaException::class)
    fun clearResidentTown(residentId: UUID)

    companion object {
        /** Returns the API registered by the currently enabled Townia plugin, or null when unavailable. */
        @JvmStatic
        fun get(): TowniaAPI? = Bukkit.getServicesManager().load(TowniaAPI::class.java)

        /** Returns Townia's plugin instance when it is enabled, or null otherwise. */
        @JvmStatic
        fun plugin(): Townia? = Bukkit.getPluginManager().getPlugin("Townia") as? Townia
    }
}
