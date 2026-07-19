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
        return getOrCreate(player.uniqueId, player.name)
    }

    fun getOrCreate(uuid: UUID, name: String): TowniaPlayer {
        val existing: TowniaPlayer? = cache[uuid]
        if (existing != null) {
            // A Towny upgrade can leave its data on an old UUID while this
            // server already created a blank record for the player's current
            // Minecraft UUID. Merge that legacy record instead of returning
            // the blank one and losing the migrated town membership.
            val legacyUuid = findLegacyUuid(name, uuid)
            if (legacyUuid != null && legacyUuid != uuid) {
                val legacy = cache[legacyUuid]
                if (legacy != null) {
                    mergeLegacyResident(legacyUuid, legacy, existing)
                }
            }
            if (!existing.name.equals(name)) {
                nameIndex.remove(existing.name!!.lowercase(Locale.getDefault()))
                existing.name = name
                nameIndex[name.lowercase(Locale.getDefault())] = uuid
                persist(existing)
            }
            return existing
        }

        val oldUuid = findLegacyUuid(name, uuid)
        if (oldUuid != null && oldUuid != uuid) {
            val oldExisting = cache[oldUuid]
            if (oldExisting != null) {
                plugin.logger.info("Migrating UUID for $name from $oldUuid to $uuid")
                oldExisting.uuid = uuid
                cache.remove(oldUuid)
                cache[uuid] = oldExisting
                nameIndex[name.lowercase(Locale.getDefault())] = uuid

                val tOpt = plugin.townManager.getTown(oldExisting.townUuid)
                if (tOpt.isPresent) {
                    val town = tOpt.get()
                    if (town.mayorUuid == oldUuid) {
                        town.mayorUuid = uuid
                        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { plugin.databaseManager.saveTown(town) })
                    }
                    if (town.nationUuid != null) {
                        val nOpt = plugin.nationManager.getNation(town.nationUuid)
                        if (nOpt.isPresent) {
                            val nation = nOpt.get()
                            if (nation.leaderUuid == oldUuid) {
                                nation.leaderUuid = uuid
                                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { plugin.databaseManager.saveNation(nation) })
                            }
                        }
                    }
                }

                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    try {
                        plugin.databaseManager.connection.use { conn ->
                            conn.prepareStatement("UPDATE residents SET uuid=? WHERE uuid=?").use { stmt ->
                                stmt.setString(1, uuid.toString())
                                stmt.setString(2, oldUuid.toString())
                                stmt.executeUpdate()
                            }
                            conn.prepareStatement("UPDATE towns SET mayor_uuid=? WHERE mayor_uuid=?").use { stmt ->
                                stmt.setString(1, uuid.toString())
                                stmt.setString(2, oldUuid.toString())
                                stmt.executeUpdate()
                            }
                            conn.prepareStatement("UPDATE nations SET leader_uuid=? WHERE leader_uuid=?").use { stmt ->
                                stmt.setString(1, uuid.toString())
                                stmt.setString(2, oldUuid.toString())
                                stmt.executeUpdate()
                            }
                            conn.prepareStatement("UPDATE plots SET owner_uuid=? WHERE owner_uuid=?").use { stmt ->
                                stmt.setString(1, uuid.toString())
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
            uuid,
            name,
            null,
            TownRank.RESIDENT,
            System.currentTimeMillis(),
            null
        )
        newPlayer.registeredAt = System.currentTimeMillis()
        newPlayer.defaultPermsFriend = plugin.towniaConfig.defaultResidentPermsFriend
        newPlayer.defaultPermsAlly = plugin.towniaConfig.defaultResidentPermsAlly
        newPlayer.defaultPermsOutsider = plugin.towniaConfig.defaultResidentPermsOutsider
        newPlayer.defaultPermsResident = plugin.towniaConfig.defaultResidentPermsResident
        newPlayer.uuid?.let { cache.put(it, newPlayer) }
        newPlayer.uuid?.let { nameIndex.put(newPlayer.name!!.lowercase(Locale.getDefault()), it) }
        persist(newPlayer)
        return newPlayer
    }

    private fun mergeLegacyResident(legacyUuid: UUID, legacy: TowniaPlayer, current: TowniaPlayer) {
        plugin.logger.info("Merging legacy UUID $legacyUuid into current UUID ${current.uuid} for ${current.name}")
        if (current.townUuid == null && legacy.townUuid != null) {
            current.townUuid = legacy.townUuid
            current.rank = legacy.rank
        }
        if (current.jailedTownUuid == null && legacy.jailedTownUuid != null) {
            current.jailedTownUuid = legacy.jailedTownUuid
            current.jailReleaseAt = legacy.jailReleaseAt
            current.jailBail = legacy.jailBail
        }
        current.registeredAt = listOf(current.registeredAt, legacy.registeredAt)
            .filter { it > 0L }
            .minOrNull() ?: current.registeredAt
        legacy.friends?.filterNotNull()?.forEach { friend ->
            if (current.friends?.contains(friend) != true) current.friends?.add(friend)
        }

        cache.remove(legacyUuid)
        current.uuid?.let { nameIndex[current.name!!.lowercase(Locale.getDefault())] = it }
        updateGovernmentReferences(legacyUuid, current.uuid!!)

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                // Save the current UUID first; the old row can then be safely removed.
                db.saveResident(current)
                plugin.databaseManager.connection.use { conn ->
                    conn.prepareStatement("UPDATE towns SET mayor_uuid=? WHERE mayor_uuid=?").use { stmt ->
                        stmt.setString(1, current.uuid.toString())
                        stmt.setString(2, legacyUuid.toString())
                        stmt.executeUpdate()
                    }
                    conn.prepareStatement("UPDATE nations SET leader_uuid=? WHERE leader_uuid=?").use { stmt ->
                        stmt.setString(1, current.uuid.toString())
                        stmt.setString(2, legacyUuid.toString())
                        stmt.executeUpdate()
                    }
                    conn.prepareStatement("UPDATE plots SET owner_uuid=? WHERE owner_uuid=?").use { stmt ->
                        stmt.setString(1, current.uuid.toString())
                        stmt.setString(2, legacyUuid.toString())
                        stmt.executeUpdate()
                    }
                    conn.prepareStatement("DELETE FROM residents WHERE uuid=?").use { stmt ->
                        stmt.setString(1, legacyUuid.toString())
                        stmt.executeUpdate()
                    }
                }
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Failed to merge legacy resident UUID for ${current.name}", e)
            }
        })
    }

    private fun findLegacyUuid(name: String, currentUuid: UUID): UUID? {
        // Do not rely only on nameIndex: after an interrupted migration both
        // UUID rows can have the same name, and map iteration order decides
        // which row nameIndex happens to retain.
        return cache.entries.firstOrNull { (uuid, resident) ->
            uuid != currentUuid && resident.name.equals(name, ignoreCase = true) && resident.townUuid != null
        }?.key ?: nameIndex[name.lowercase(Locale.getDefault())]?.takeIf { it != currentUuid }
    }

    private fun updateGovernmentReferences(oldUuid: UUID, newUuid: UUID) {
        plugin.townManager.allTowns.forEach { town ->
            if (town.mayorUuid == oldUuid) town.mayorUuid = newUuid
        }
        plugin.nationManager.allNations.forEach { nation ->
            if (nation.leaderUuid == oldUuid) nation.leaderUuid = newUuid
        }
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
