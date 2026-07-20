package net.azisaba.townia.api

import net.azisaba.townia.Townia
import net.azisaba.townia.data.*
import org.bukkit.Chunk
import org.bukkit.Location
import java.util.Optional
import java.util.UUID

internal class TowniaApiImpl(private val plugin: Townia) : TowniaAPI {
    override fun getTown(id: UUID): Optional<Town> = plugin.townManager.getTown(id)
    override fun getTown(name: String): Optional<Town> = plugin.townManager.getTownByName(name)
    override fun getTownOfPlayer(playerId: UUID): Optional<Town> = plugin.townManager.getTownOfPlayer(playerId)
    override fun towns(): List<Town> = plugin.townManager.allTowns.toList()

    override fun getNation(id: UUID): Optional<Nation> = plugin.nationManager.getNation(id)
    override fun getNation(name: String): Optional<Nation> = plugin.nationManager.getNationByName(name)
    override fun nations(): List<Nation> = plugin.nationManager.allNations.toList()

    override fun getResident(id: UUID): Optional<TowniaPlayer> = plugin.residentManager.getResident(id)
    override fun getResident(name: String): Optional<TowniaPlayer> = plugin.residentManager.getResidentByName(name)
    override fun residents(townId: UUID): List<TowniaPlayer> = plugin.residentManager.getResidentsByTown(townId).toList()
    override fun residentsInNation(nationId: UUID): List<TowniaPlayer> = plugin.residentManager.getResidentsByNation(nationId).toList()

    override fun getPlot(chunk: Chunk): Optional<Plot> = plugin.plotManager.getPlot(chunk)
    override fun getPlot(world: String, chunkX: Int, chunkZ: Int): Optional<Plot> = plugin.plotManager.getPlot(world, chunkX, chunkZ)
    override fun isClaimed(chunk: Chunk): Boolean = plugin.plotManager.isClaimed(chunk)
    override fun plots(townId: UUID): List<Plot> = plugin.plotManager.getPlotsByTown(townId).toList()
    override fun plotsOwnedBy(playerId: UUID): List<Plot> = plugin.plotManager.getPlotsByOwner(playerId).toList()

    override fun createTown(name: String, mayorId: UUID): Town = plugin.townManager.createTown(name, mayorId)
    override fun deleteTown(townId: UUID) = plugin.townManager.deleteTown(townId)
    override fun renameTown(townId: UUID, name: String) = plugin.townManager.renameTown(townId, name)
    override fun setTownSpawn(townId: UUID, location: Location) = plugin.townManager.setSpawn(townId, location)
    override fun setTownPublic(townId: UUID, isPublic: Boolean) = plugin.townManager.setPublic(townId, isPublic)
    override fun setTownMayor(townId: UUID, residentId: UUID) = plugin.townManager.setMayor(townId, residentId)
    override fun addTownBalance(townId: UUID, amount: Double) {
        requirePositiveAmount(amount)
        plugin.townManager.addBalance(townId, amount)
    }
    override fun subtractTownBalance(townId: UUID, amount: Double) {
        requirePositiveAmount(amount)
        plugin.townManager.subtractBalance(townId, amount)
    }

    override fun createNation(name: String, capitalTownId: UUID, leaderId: UUID): Nation {
        plugin.nationManager.createNation(name, capitalTownId, leaderId)
        return plugin.nationManager.getNationByName(name).orElseThrow { IllegalStateException("Created nation was not cached") }
    }
    override fun deleteNation(nationId: UUID) = plugin.nationManager.deleteNation(nationId)
    override fun addTownToNation(nationId: UUID, townId: UUID) = plugin.nationManager.addTownToNation(nationId, townId)
    override fun removeTownFromNation(nationId: UUID, townId: UUID) = plugin.nationManager.removeTownFromNation(nationId, townId)
    override fun setNationLeader(nationId: UUID, residentId: UUID) = plugin.nationManager.setLeader(nationId, residentId)

    override fun claim(townId: UUID, chunk: Chunk) = plugin.plotManager.claimChunk(townId, chunk)
    override fun unclaim(townId: UUID, chunk: Chunk) = plugin.plotManager.unclaimChunk(townId, chunk)
    override fun setPlotType(world: String, chunkX: Int, chunkZ: Int, type: PlotType) = plugin.plotManager.setPlotType(world, chunkX, chunkZ, type)
    override fun setPlotForSale(world: String, chunkX: Int, chunkZ: Int, forSale: Boolean, price: Double) = plugin.plotManager.setForSale(world, chunkX, chunkZ, forSale, price)
    override fun setPlotOwner(world: String, chunkX: Int, chunkZ: Int, ownerId: UUID?) = plugin.plotManager.transferOwnership(world, chunkX, chunkZ, ownerId)

    override fun setResidentTown(residentId: UUID, townId: UUID, rank: TownRank) {
        requireResident(residentId)
        plugin.townManager.getTown(townId).orElseThrow { net.azisaba.townia.TowniaException("error.town-not-found") }
        plugin.residentManager.setTown(residentId, townId, rank)
    }
    override fun clearResidentTown(residentId: UUID) {
        requireResident(residentId)
        plugin.residentManager.clearTown(residentId)
    }

    private fun requireResident(residentId: UUID) {
        plugin.residentManager.getResident(residentId)
            .orElseThrow { net.azisaba.townia.TowniaException("error.resident-not-found") }
    }

    private fun requirePositiveAmount(amount: Double) {
        require(amount.isFinite() && amount > 0.0) { "amount must be a positive finite value" }
    }
}
