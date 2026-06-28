package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.database.DatabaseManager
import org.bukkit.entity.Player
import java.sql.SQLException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

class ResidentManager(private val plugin: Townia, private val db: DatabaseManager) {

    private val cache: ConcurrentHashMap<UUID, TowniaPlayer> = ConcurrentHashMap<UUID, TowniaPlayer>()
    private val nameIndex: ConcurrentHashMap<String, UUID> = ConcurrentHashMap<String, UUID>()

    init {
        loadAll()
    }

    fun cacheResident(res: TowniaPlayer) {
        res.uuid?.let { cache.put(it, res) }
        res.uuid?.let { nameIndex.put(res.name!!.lowercase(Locale.getDefault()), it) }
    }

    private fun loadAll() {
        cache.clear()
        nameIndex.clear()
        try {
            for (p in db.allResidents) {
                if (p == null) continue
                p.uuid?.let { cache.put(it, p) }
                p.uuid?.let { nameIndex.put(p.name!!.lowercase(Locale.getDefault()), it) }
            }
            plugin.logger.info("Loaded " + cache.size + " residents.")
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to load residents from database", e)
        }
    }

    fun getResident(uuid: UUID?): Optional<TowniaPlayer> {
        return Optional.ofNullable(cache[uuid])
    }

    fun getResidentByName(name: String): Optional<TowniaPlayer> {
        val uuid = nameIndex[name.lowercase(Locale.getDefault())] ?: return Optional.empty<TowniaPlayer>()
        return Optional.ofNullable(cache[uuid])
    }

    val allResidents: MutableList<TowniaPlayer>
        get() = ArrayList(cache.values.toList())

    fun getResidentsByTown(townUuid: UUID): MutableList<TowniaPlayer> {
        val list: MutableList<TowniaPlayer> = ArrayList<TowniaPlayer>()
        for (p in cache.values) {
            if (townUuid == p.townUuid) list.add(p)
        }
        list.sortBy { it.name }
        return list
    }

    fun getResidentsByNation(nationUuid: UUID): MutableList<TowniaPlayer> {
        val list: MutableList<TowniaPlayer> = ArrayList<TowniaPlayer>()
        for (p in cache.values) {
            val townUuid = p.townUuid ?: continue
            val townOpt = plugin.townManager.getTown(townUuid)
            if (townOpt.isPresent && townOpt.get().nationUuid == nationUuid) {
                list.add(p)
            }
        }
        list.sortBy { it.name }
        return list
    }

    fun isResident(uuid: UUID?): Boolean {
        return cache.containsKey(uuid)
    }

    fun getOrCreate(player: Player): TowniaPlayer {
        val existing: TowniaPlayer? = cache[player.uniqueId]
        if (existing != null) {
            if (!existing.name.equals(player.name)) {
                nameIndex.remove(existing.name!!.lowercase(Locale.getDefault()))
                existing.name = player.name
                nameIndex[player.name.lowercase(Locale.getDefault())] = player.uniqueId
                persist(existing)
            }
            return existing
        }

        val oldUuid = nameIndex[player.name.lowercase(Locale.getDefault())]
        if (oldUuid != null && oldUuid != player.uniqueId) {
            val oldExisting = cache[oldUuid]
            if (oldExisting != null) {
                plugin.logger.info("Migrating UUID for ${player.name} from $oldUuid to ${player.uniqueId}")
                oldExisting.uuid = player.uniqueId
                cache.remove(oldUuid)
                cache[player.uniqueId] = oldExisting
                nameIndex[player.name.lowercase(Locale.getDefault())] = player.uniqueId

                val tOpt = plugin.townManager.getTown(oldExisting.townUuid)
                if (tOpt.isPresent) {
                    val town = tOpt.get()
                    if (town.mayorUuid == oldUuid) {
                        town.mayorUuid = player.uniqueId
                        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { plugin.databaseManager.saveTown(town) })
                    }
                    if (town.nationUuid != null) {
                        val nOpt = plugin.nationManager.getNation(town.nationUuid)
                        if (nOpt.isPresent) {
                            val nation = nOpt.get()
                            if (nation.leaderUuid == oldUuid) {
                                nation.leaderUuid = player.uniqueId
                                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { plugin.databaseManager.saveNation(nation) })
                            }
                        }
                    }
                }

                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    try {
                        plugin.databaseManager.connection.use { conn ->
                            conn.prepareStatement("UPDATE residents SET uuid=? WHERE uuid=?").use { stmt ->
                                stmt.setString(1, player.uniqueId.toString())
                                stmt.setString(2, oldUuid.toString())
                                stmt.executeUpdate()
                            }
                            conn.prepareStatement("UPDATE towns SET mayor_uuid=? WHERE mayor_uuid=?").use { stmt ->
                                stmt.setString(1, player.uniqueId.toString())
                                stmt.setString(2, oldUuid.toString())
                                stmt.executeUpdate()
                            }
                            conn.prepareStatement("UPDATE nations SET leader_uuid=? WHERE leader_uuid=?").use { stmt ->
                                stmt.setString(1, player.uniqueId.toString())
                                stmt.setString(2, oldUuid.toString())
                                stmt.executeUpdate()
                            }
                            conn.prepareStatement("UPDATE plots SET owner_uuid=? WHERE owner_uuid=?").use { stmt ->
                                stmt.setString(1, player.uniqueId.toString())
                                stmt.setString(2, oldUuid.toString())
                                stmt.executeUpdate()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                })
                return oldExisting
            }
        }

        val newPlayer: TowniaPlayer = TowniaPlayer(
            player.uniqueId,
            player.name,
            null,
            TownRank.RESIDENT,
            System.currentTimeMillis(),
            null
        )
        newPlayer.uuid?.let { cache.put(it, newPlayer) }
        newPlayer.uuid?.let { nameIndex.put(newPlayer.name!!.lowercase(Locale.getDefault()), it) }
        persist(newPlayer)
        return newPlayer
    }

    fun setTown(playerUuid: UUID?, townUuid: UUID?, rank: TownRank?) {
        val p: TowniaPlayer = cache[playerUuid] ?: return
        p.townUuid = townUuid
        p.rank = rank ?: TownRank.RESIDENT
        persist(p)
    }

    fun clearTown(playerUuid: UUID?) {
        val p: TowniaPlayer = cache[playerUuid] ?: return
        p.townUuid = null
        p.rank = TownRank.RESIDENT
        persist(p)
    }

    fun setRank(playerUuid: UUID?, rank: TownRank?) {
        val p: TowniaPlayer = cache[playerUuid] ?: return
        p.rank = rank ?: TownRank.RESIDENT
        persist(p)
    }

    fun updateLastSeen(playerUuid: UUID?) {
        val p: TowniaPlayer = cache.get(playerUuid) ?: return
        p.lastSeen = System.currentTimeMillis()
        persist(p)
    }

    fun saveResident(player: TowniaPlayer) {
        persist(player)
    }

    private fun persist(player: TowniaPlayer) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                db.saveResident(player)
            } catch (e: SQLException) {
                plugin.logger.log(Level.SEVERE, "Failed to save resident " + player.name, e)
            }
        })
    }

    fun addFriend(player: TowniaPlayer, friend: TowniaPlayer) {
        if (!player.friends!!.contains(friend.uuid.toString())) {
            player.friends!!.add(friend.uuid.toString())
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                try {
                    db.addFriend(player.uuid!!, friend.uuid!!)
                } catch (e: SQLException) {
                    plugin.logger.log(Level.SEVERE, "Failed to add friend for resident " + player.name, e)
                }
            })
        }
    }

    fun removeFriend(player: TowniaPlayer, friend: TowniaPlayer) {
        if (player.friends!!.contains(friend.uuid.toString())) {
            player.friends!!.remove(friend.uuid.toString())
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                try {
                    db.removeFriend(player.uuid!!, friend.uuid!!)
                } catch (e: SQLException) {
                    plugin.logger.log(Level.SEVERE, "Failed to remove friend for resident " + player.name, e)
                }
            })
        }
    }
}