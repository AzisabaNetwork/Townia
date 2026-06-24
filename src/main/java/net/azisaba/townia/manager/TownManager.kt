package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.azisaba.townia.TowniaException
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.database.DatabaseManager
import org.bukkit.Location
import java.sql.SQLException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import java.util.logging.Level
import kotlin.math.min

class TownManager(private val plugin: Townia, private val db: DatabaseManager,
                  private val residentManager: ResidentManager
) {

    private val cache: ConcurrentHashMap<UUID, Town> = ConcurrentHashMap<UUID, Town>()
    private val nameIndex: ConcurrentHashMap<String, UUID> = ConcurrentHashMap<String, UUID>()

    init {
        loadAll()
    }

    fun cacheTown(town: Town) {
        town.id?.let { cache.put(it, town) }
        town.id?.let { nameIndex.put(town.name!!.lowercase(Locale.getDefault()), it) }
    }

    private fun loadAll() {
        cache.clear()
        nameIndex.clear()
        try {
            for (t in db.allTowns) {
                if (t == null) continue
                db.loadTownOutposts(t)
                t.id?.let { cache.put(it, t) }
                t.id?.let { nameIndex.put(t.name!!.lowercase(Locale.getDefault()), it) }
            }
            plugin.logger.info("Loaded " + cache.size + " towns.")
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to load towns from database", e)
        }
    }

    fun getTown(id: UUID?): Optional<Town> {
        return Optional.ofNullable(cache[id])
    }

    fun getTownByName(name: String): Optional<Town> {
        val id = nameIndex[name.lowercase(Locale.getDefault())] ?: return Optional.empty()
        return Optional.ofNullable(cache[id])
    }

    fun getTownOfPlayer(playerUuid: UUID?): Optional<Town> {
        return residentManager.getResident(playerUuid)
            .filter { it.isInTown }
            .flatMap { getTown(it.townUuid) }
    }

    val allTowns: MutableList<Town>
        get() {
            val list = ArrayList(cache.values)
            list.sortBy { it.name }
            return list
        }

    fun getTownsByNation(nationId: UUID): MutableList<Town> {
        return cache.values.filter { nationId == it.nationUuid }
            .sortedBy { it.name }
            .toMutableList()
    }


    fun townExists(name: String): Boolean {
        return nameIndex.containsKey(name.lowercase(Locale.getDefault()))
    }

    fun getClaimCount(townId: UUID?): Int {
        try {
            return db.countPlotsByTown(townId!!)
        } catch (e: SQLException) {
            plugin.logger.log(Level.WARNING, "Failed to count plots for town $townId", e)
            return 0
        }
    }

    @Throws(TowniaException::class)
    fun createTown(name: String, mayorUuid: UUID?): Town {
        if (townExists(name)) {
            throw TowniaException("town.already-exists", "{town}", name)
        }
        val resident: Optional<TowniaPlayer> = residentManager.getResident(mayorUuid)
        if (resident.isPresent && resident.get().isInTown) {
            throw TowniaException("error.already-in-town")
        }

        val town: Town = Town(
            UUID.randomUUID(), name, mayorUuid, null,
            0.0, plugin.towniaConfig.defaultClaimLimit, 0,
            false, System.currentTimeMillis(),
            null, plugin.towniaConfig.defaultTownTax, 0.0, pvp = false, mobs = false, explosions = false, fire = false,
            spawnWorld = null, spawnX = 0.0, spawnY = 0.0, spawnZ = 0.0, spawnYaw = 0.0f, spawnPitch = 0.0f
        )
        town.dailyUpkeep = plugin.towniaConfig.townUpkeep
        persist(town)
        residentManager.setTown(mayorUuid, town.id, TownRank.MAYOR)
        return town
    }

    @Throws(TowniaException::class)
    fun deleteTown(townId: UUID) {
        val town: Town = cache[townId] ?: throw TowniaException("error.town-not-found")

        for (p in residentManager.getResidentsByTown(townId)) {
            residentManager.clearTown(p.uuid!!)
        }
        if (town.isInNation) {
            val nation: Optional<Nation> = plugin.nationManager.getNation(town.nationUuid)
            nation.ifPresent(Consumer { _: Nation? ->
                try {
                    plugin.nationManager.removeTownFromNation(town.nationUuid!!, townId)
                } catch (_: TowniaException) {
                }
            })
        }

        cache.remove(townId)
        nameIndex.remove(town.name!!.lowercase(Locale.getDefault()))
        try {
            db.deleteTown(townId)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to delete town " + town.name, e)
            throw TowniaException("error.database")
        }
    }

    @Throws(TowniaException::class)
    fun renameTown(townId: UUID?, newName: String) {
        if (townExists(newName)) throw TowniaException("town.already-exists", "{town}", newName)
        val town: Town = requireTown(townId)
        nameIndex.remove(town.name!!.lowercase(Locale.getDefault()))
        town.name = newName
        townId?.let { nameIndex.put(newName.lowercase(Locale.getDefault()), it) }
        persist(town)
    }

    fun saveTown(town: Town) {
        persist(town)
    }

    @Throws(TowniaException::class)
    fun setSpawn(townId: UUID?, loc: Location) {
        val town: Town = requireTown(townId)
        town.setSpawn(loc.getWorld().name, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        persist(town)
    }

    @Throws(TowniaException::class)
    fun setPublic(townId: UUID?, isPublic: Boolean) {
        val town: Town = requireTown(townId)
        town.isPublic = isPublic
        persist(town)
    }

    @Throws(TowniaException::class)
    fun setMayor(townId: UUID?, newMayorUuid: UUID?) {
        val town: Town = requireTown(townId)
        val oldMayor: UUID? = town.mayorUuid
        town.mayorUuid = newMayorUuid
        persist(town)
        residentManager.setRank(oldMayor, TownRank.RESIDENT
)
        residentManager.setRank(newMayorUuid, TownRank.MAYOR
)
    }

    @Throws(TowniaException::class)
    fun addBalance(townId: UUID?, amount: Double) {
        val town: Town = requireTown(townId)
        town.balance += amount
        persist(town)
    }

    @Throws(TowniaException::class)
    fun subtractBalance(townId: UUID?, amount: Double) {
        val town: Town = requireTown(townId)
        if (town.balance < amount) throw TowniaException("town.withdraw-insufficient")
        town.balance -= amount
        persist(town)
    }

    @Throws(TowniaException::class)
    fun setBonusClaims(townId: UUID?, bonusClaims: Int) {
        val town: Town = requireTown(townId)
        val capped = min(bonusClaims, plugin.towniaConfig.maxBonusClaims)
        town.bonusClaims = capped
        persist(town)
    }

    @Throws(TowniaException::class)
    fun setNation(townId: UUID?, nationId: UUID?) {
        val town: Town = requireTown(townId)
        town.nationUuid = nationId
        persist(town)
    }

    @Throws(TowniaException::class)
    fun clearNation(townId: UUID?) {
        val town: Town = requireTown(townId)
        town.nationUuid = null
        persist(town)
    }

    @Throws(TowniaException::class)
    private fun requireTown(townId: UUID?): Town {
        return cache[townId] ?: throw TowniaException("error.town-not-found")
    }

    @Throws(TowniaException::class)
    fun addTownOutpost(townId: UUID?, outpost: net.azisaba.townia.data.TowniaOutpost) {
        val town: Town = requireTown(townId)
        try {
            db.saveTownOutpost(townId!!, outpost)
            db.loadTownOutposts(town)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to save outpost", e)
            throw TowniaException("error.database")
        }
    }

    @Throws(TowniaException::class)
    fun removeTownOutpost(townId: UUID?, outpostId: Int) {
        val town: Town = requireTown(townId)
        try {
            db.deleteTownOutpost(outpostId)
            town.outposts.removeIf { it?.id == outpostId }
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to delete outpost", e)
            throw TowniaException("error.database")
        }
    }

    private fun persist(town: Town) {
        town.id?.let { cache.put(it, town) }
        town.id?.let { nameIndex.put(town.name!!.lowercase(Locale.getDefault()), it) }
        try {
            db.saveTown(town)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to save town " + town.name, e)
        }
    }
}