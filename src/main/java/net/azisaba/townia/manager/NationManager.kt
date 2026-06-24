package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.azisaba.townia.TowniaException
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Town
import net.azisaba.townia.database.DatabaseManager
import java.sql.SQLException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

class NationManager(private val plugin: Townia, private val db: DatabaseManager, private val townManager: TownManager) {

    private val cache: ConcurrentHashMap<UUID, Nation> = ConcurrentHashMap<UUID, Nation>()
    private val nameIndex: ConcurrentHashMap<String, UUID> = ConcurrentHashMap<String, UUID>()

    init {
        loadAll()
    }

    fun cacheNation(nation: Nation) {
        nation.id?.let { cache.put(it, nation) }
        nation.id?.let { nameIndex.put(nation.name!!.lowercase(Locale.getDefault()), it) }
    }

    private fun loadAll() {
        cache.clear()
        nameIndex.clear()
        try {
            for (n in db.allNations) {
                if (n != null) db.loadNationTitles(n)
                n!!.id?.let { cache.put(it, n) }
                n.id?.let { nameIndex.put(n.name!!.lowercase(Locale.getDefault()), it) }
            }
            val relations: MutableMap<UUID?, MutableMap<UUID?, String?>?> = db.allNationRelations
            for (entry in relations.entries) {
                val n: Nation? = cache[entry.key]
                if (n != null) {
                    for (rel in entry.value!!.entries) {
                        if ("ALLY".equals(rel.value, ignoreCase = true)) {
                            n.allies.add(rel.key)
                        } else if ("ENEMY".equals(rel.value, ignoreCase = true)) {
                            n.enemies.add(rel.key)
                        }
                    }
                }
            }
            plugin.logger.info("Loaded " + cache.size + " nations and relations.")
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to load nations from database", e)
        }
    }

    fun getNation(id: UUID?): Optional<Nation> {
        return Optional.ofNullable<Nation?>(cache.get(id))
    }

    fun getNationByName(name: String): Optional<Nation> {
        val id = nameIndex[name.lowercase(Locale.getDefault())] ?: return Optional.empty<Nation?>()
        return Optional.ofNullable<Nation?>(cache.get(id))
    }

    val allNations: MutableList<Nation>
        get() {
            val list: MutableList<Nation> = ArrayList(cache.values.toList())
            list.sortBy { it.name }
            return list
        }

    fun nationExists(name: String): Boolean {
        return nameIndex.containsKey(name.lowercase(Locale.getDefault()))
    }

    fun getTownsInNation(nationId: UUID): MutableList<Town> {
        val list: MutableList<Town> = ArrayList<Town>()
        for (t in townManager.allTowns) {
            if (nationId == t.nationUuid) list.add(t)
        }
        return list
    }

    @Throws(TowniaException::class)
    fun createNation(name: String, capitalTownUuid: UUID?, leaderUuid: UUID?) {
        if (nationExists(name)) throw TowniaException("nation.already-exists", "{nation}", name)

        val capital: Town = townManager.getTown(capitalTownUuid)
            .orElseThrow({ TowniaException("error.town-not-found") })
        if (capital.isInNation) throw TowniaException("town.already-in-nation")

        val nation = Nation(
            UUID.randomUUID(),
            name,
            capitalTownUuid,
            leaderUuid,
            0.0,
            null,
            plugin.towniaConfig.defaultNationTax
        )
        persist(nation)
        townManager.setNation(capitalTownUuid, nation.id)
    }

    @Throws(TowniaException::class)
    fun deleteNation(nationId: UUID) {
        val nation: Nation = requireNation(nationId)
        for (town in getTownsInNation(nationId)) {
            try {
                townManager.clearNation(town.id)
            } catch (_: TowniaException) {
            }
        }
        cache.remove(nationId)
        nameIndex.remove(nation.name!!.lowercase(Locale.getDefault()))
        try {
            db.deleteNation(nationId)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to delete nation " + nation.name, e)
            throw TowniaException("error.database")
        }
    }

    @Throws(TowniaException::class)
    fun addTownToNation(nationId: UUID?, townId: UUID?) {
        requireNation(nationId)
        val town: Town = townManager.getTown(townId).orElseThrow { TowniaException("error.town-not-found") }
        if (town.isInNation) throw TowniaException("town.already-in-nation")
        townManager.setNation(townId, nationId)
    }

    @Throws(TowniaException::class)
    fun removeTownFromNation(nationId: UUID, townId: UUID) {
        val nation: Nation = requireNation(nationId)
        val town: Town = townManager.getTown(townId).orElseThrow({ TowniaException("error.town-not-found") })
        if (nationId != town.nationUuid) throw TowniaException("town.not-in-nation")
        townManager.clearNation(townId)

        if (townId == nation.capitalTownUuid) {
            deleteNation(nationId)
        }
    }

    @Throws(TowniaException::class)
    fun addBalance(nationId: UUID?, amount: Double) {
        val nation: Nation = requireNation(nationId)
        nation.balance += amount
        persist(nation)
    }

    @Throws(TowniaException::class)
    fun subtractBalance(nationId: UUID?, amount: Double) {
        val nation: Nation = requireNation(nationId)
        if (nation.balance < amount) throw TowniaException("nation.withdraw-insufficient")
        nation.balance -= amount
        persist(nation)
    }

    @Throws(TowniaException::class)
    private fun requireNation(nationId: UUID?): Nation {
        val n: Nation = cache[nationId] ?: throw TowniaException("error.nation-not-found")
        return n
    }

    fun saveNation(nation: Nation) {
        persist(nation)
    }

    private fun persist(nation: Nation) {
        nation.id?.let { cache.put(it, nation) }
        nation.id?.let { nameIndex.put(nation.name!!.lowercase(Locale.getDefault()), it) }
        try {
            db.saveNation(nation)
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to save nation " + nation.name, e)
        }
    }
}