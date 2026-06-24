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

class ResidentManager(private val plugin: Townia, db: DatabaseManager) {
    private val db: DatabaseManager

    private val cache: MutableMap<UUID?, TowniaPlayer> = ConcurrentHashMap<UUID?, TowniaPlayer>()
    private val nameIndex: MutableMap<String?, UUID?> = ConcurrentHashMap<String?, UUID?>()

    init {
        this.db = db
        loadAll()
    }

    fun cacheResident(res: TowniaPlayer) {
        cache.put(res.uuid, res)
        nameIndex.put(res.name!!.lowercase(java.util.Locale.getDefault()), res.uuid)
    }

    private fun loadAll() {
        cache.clear()
        nameIndex.clear()
        try {
            for (p in db.allResidents) {
                if (p == null) continue
                cache.put(p.uuid, p)
                nameIndex.put(p.name!!.lowercase(java.util.Locale.getDefault()), p.uuid)
            }
            plugin.getLogger().info("Loaded " + cache.size + " residents.")
        } catch (e: SQLException) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load residents from database", e)
        }
    }

    fun getResident(uuid: UUID?): Optional<TowniaPlayer> {
        return Optional.ofNullable(cache.get(uuid))
    }

    fun getResidentByName(name: String): Optional<TowniaPlayer> {
        val uuid = nameIndex.get(name.lowercase(Locale.getDefault()))
        if (uuid == null) return Optional.empty<TowniaPlayer>()
        return Optional.ofNullable(cache.get(uuid))
    }

    val allResidents: MutableList<TowniaPlayer>
        get() = ArrayList(cache.values.filterNotNull())

    fun getResidentsByTown(townUuid: UUID): MutableList<TowniaPlayer> {
        val list: MutableList<TowniaPlayer> = ArrayList<TowniaPlayer>()
        for (p in cache.values) {
            if (townUuid == p.townUuid) list.add(p)
        }
        list.sortBy { it.name }
        return list
    }

    fun isResident(uuid: UUID?): Boolean {
        return cache.containsKey(uuid)
    }

    fun getOrCreate(player: Player): TowniaPlayer? {
        val existing: TowniaPlayer? = cache.get(player.getUniqueId())
        if (existing != null) {
            // Refresh name in case of rename
            if (!existing.name.equals(player.name)) {
                nameIndex.remove(existing.name!!.lowercase(java.util.Locale.getDefault()))
                existing.name = player.name
                nameIndex.put(player.name.lowercase(Locale.getDefault()), player.getUniqueId())
                persist(existing)
            }
            return existing
        }

        val newPlayer: TowniaPlayer = TowniaPlayer(
            player.getUniqueId(),
            player.name,
            null,
            TownRank.RESIDENT,
            System.currentTimeMillis(),
            null
        )
        cache.put(newPlayer.uuid, newPlayer)
        nameIndex.put(newPlayer.name!!.lowercase(java.util.Locale.getDefault()), newPlayer.uuid)
        persist(newPlayer)
        return newPlayer
    }

    fun setTown(playerUuid: UUID?, townUuid: UUID?, rank: TownRank?) {
        val p: TowniaPlayer? = cache.get(playerUuid)
        if (p == null) return
        p.townUuid = townUuid
        p.rank = rank ?: TownRank.RESIDENT
        persist(p)
    }

    fun clearTown(playerUuid: UUID?) {
        val p: TowniaPlayer? = cache.get(playerUuid)
        if (p == null) return
        p.townUuid = null
        p.rank = TownRank.RESIDENT
        persist(p)
    }

    fun setRank(playerUuid: UUID?, rank: TownRank?) {
        val p: TowniaPlayer? = cache.get(playerUuid)
        if (p == null) return
        p.rank = rank ?: TownRank.RESIDENT
        persist(p)
    }

    fun updateLastSeen(playerUuid: UUID?) {
        val p: TowniaPlayer? = cache.get(playerUuid)
        if (p == null) return
        p.lastSeen = System.currentTimeMillis()
        persist(p)
    }

    fun saveResident(player: TowniaPlayer) {
        persist(player)
    }

    private fun persist(player: TowniaPlayer) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                db.saveResident(player)
            } catch (e: SQLException) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save resident " + player.name, e)
            }
        })
    }

    fun addFriend(player: TowniaPlayer, friend: TowniaPlayer) {
        if (!player.friends!!.contains(friend.uuid.toString())) {
            player.friends!!.add(friend.uuid.toString())
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, Runnable {
                try {
                    db.addFriend(player.uuid!!, friend.uuid!!)
                } catch (e: SQLException) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to add friend for resident " + player.name, e)
                }
            })
        }
    }

    fun removeFriend(player: TowniaPlayer, friend: TowniaPlayer) {
        if (player.friends!!.contains(friend.uuid.toString())) {
            player.friends!!.remove(friend.uuid.toString())
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, Runnable {
                try {
                    db.removeFriend(player.uuid!!, friend.uuid!!)
                } catch (e: SQLException) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to remove friend for resident " + player.name, e)
                }
            })
        }
    }
}