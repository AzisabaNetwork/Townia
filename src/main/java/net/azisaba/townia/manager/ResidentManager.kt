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

    fun countResidentsByTown(townUuid: UUID): Int {
        var n = 0
        for (p in cache.values) if (townUuid == p.townUuid) n++
        return n
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
        val legacyUuid = findLegacyUuid(name, uuid)
        val legacy = legacyUuid?.let { cache[it] }
        val existing: TowniaPlayer? = cache[uuid]
        if (existing != null) {
            // A Towny upgrade can leave its data on an old UUID while this
            // server already created a blank record for the player's current
            // Minecraft UUID. Merge that legacy record instead of returning
            // the blank one and losing the migrated town membership.
            if (legacyUuid != null && legacy != null) {
                mergeLegacyResident(legacyUuid, legacy, existing)
            }
            if (!existing.name.equals(name)) {
                nameIndex.remove(existing.name!!.lowercase(Locale.getDefault()))
                existing.name = name
                nameIndex[name.lowercase(Locale.getDefault())] = uuid
                persist(existing)
            }
            return existing
        }

        if (legacyUuid != null && legacy != null) {
            plugin.logger.info("Migrating UUID for $name from $legacyUuid to $uuid")
            cache.remove(legacyUuid)
            legacy.name?.let { nameIndex.remove(it.lowercase(Locale.getDefault())) }
            legacy.uuid = uuid
            legacy.name = name
            cache[uuid] = legacy
            nameIndex[name.lowercase(Locale.getDefault())] = uuid
            moveReferencesInMemory(legacyUuid, uuid)
            persistUuidRemap(legacyUuid, legacy)
            return legacy
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
        val currentUuid = current.uuid ?: return
        plugin.logger.info("Merging legacy UUID $legacyUuid into current UUID $currentUuid for ${current.name}")
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
            if (friend != currentUuid.toString() && current.friends?.contains(friend) != true) current.friends?.add(friend)
        }

        cache.remove(legacyUuid)
        nameIndex[current.name!!.lowercase(Locale.getDefault())] = currentUuid
        moveReferencesInMemory(legacyUuid, currentUuid)
        persistUuidRemap(legacyUuid, current)
    }

    /**
     * Saves [resident] under its new UUID and moves friends, mayor/leader and
     * plot ownership away from [oldUuid] in one database transaction.
     */
    private fun persistUuidRemap(oldUuid: UUID, resident: TowniaPlayer) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                db.remapResidentUuid(oldUuid, resident)
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Failed to move resident UUID $oldUuid to ${resident.uuid} for ${resident.name}", e)
            }
        })
    }

    /**
     * Only a name match is available to link an old record to the joining
     * player, and Mojang names can be re-registered by a different account.
     * Two distinct online-mode UUIDs are therefore two different people, so a
     * record is only taken over when one side is the offline-mode UUID derived
     * from the name (offline-era data, or an offline-mode server where the
     * name is the identity anyway).
     */
    private fun findLegacyUuid(name: String, currentUuid: UUID): UUID? {
        // Do not rely only on nameIndex: after an interrupted migration both
        // UUID rows can have the same name, and map iteration order decides
        // which row nameIndex happens to retain.
        val candidates = cache.entries
            .filter { (uuid, resident) -> uuid != currentUuid && resident.name.equals(name, ignoreCase = true) }
            .sortedByDescending { it.value.townUuid != null }
        for ((uuid, resident) in candidates) {
            if (isSafeLegacyMatch(uuid, resident.name, currentUuid, name)) return uuid
            plugin.logger.warning(
                "Not merging resident $uuid (${resident.name}) into $currentUuid ($name): " +
                    "both are online-mode UUIDs, so the name was probably re-registered by another account."
            )
        }
        return null
    }

    private fun isSafeLegacyMatch(legacyUuid: UUID, legacyName: String?, currentUuid: UUID, currentName: String): Boolean {
        val offlineUuids = listOfNotNull(currentName, legacyName).map { offlineUuid(it) }
        return currentUuid in offlineUuids || legacyUuid in offlineUuids
    }

    private fun offlineUuid(name: String): UUID =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))

    private fun moveReferencesInMemory(oldUuid: UUID, newUuid: UUID) {
        plugin.townManager.allTowns.forEach { town ->
            if (town.mayorUuid == oldUuid) town.mayorUuid = newUuid
            // An outlaw must stay an outlaw after a UUID change.
            if (town.outlaws.remove(oldUuid)) town.outlaws.add(newUuid)
        }
        plugin.nationManager.allNations.forEach { nation ->
            if (nation.leaderUuid == oldUuid) nation.leaderUuid = newUuid
            // Same precedence as the database copy: a value already set on the new UUID wins.
            nation.titles.remove(oldUuid)?.let { nation.titles.putIfAbsent(newUuid, it) }
            nation.surnames.remove(oldUuid)?.let { nation.surnames.putIfAbsent(newUuid, it) }
        }
        // Plots are read from this cache; leaving the old owner here would hide
        // the player's plots until restart and write the old UUID back on save.
        plugin.plotManager.getPlotsByOwner(oldUuid).forEach { it.ownerUuid = newUuid }
        val old = oldUuid.toString()
        val new = newUuid.toString()
        cache.values.forEach { resident ->
            val friends = resident.friends ?: return@forEach
            if (!friends.remove(old)) return@forEach
            if (resident.uuid != newUuid && !friends.contains(new)) friends.add(new)
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
        // Plots are only owned by town members: a resident who leaves or is kicked loses them (as in Towny).
        playerUuid?.let { plugin.plotManager.releaseOwnedPlots(it) }
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
