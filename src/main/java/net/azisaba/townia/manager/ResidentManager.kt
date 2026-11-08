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

    /**
     * Counts players, not rows: an unmerged offline-UUID duplicate of a member
     * must not inflate the claim limit or the town level.
     */
    fun countResidentsByTown(townUuid: UUID): Int {
        val names = HashSet<String>()
        var unnamed = 0
        for (p in cache.values) {
            if (townUuid != p.townUuid) continue
            val name = p.name
            if (name.isNullOrBlank()) unnamed++ else names.add(name.lowercase(Locale.ROOT))
        }
        return names.size + unnamed
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
            absorbRenamedRecords(existing)
            repairMayor(existing)
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
            absorbRenamedRecords(legacy)
            repairMayor(legacy)
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
        absorbRenamedRecords(newPlayer)
        repairMayor(newPlayer)
        return newPlayer
    }

    /**
     * A migrated resident whose name could not be resolved was stored under the
     * offline UUID of that name. If the player has since renamed, neither the
     * UUID nor the name matches when they join, and they appeared to have been
     * kicked from their town. EssentialsX's name history links the old name to
     * their real UUID, so such a record is merged into theirs.
     */
    private fun absorbRenamedRecords(current: TowniaPlayer) {
        val currentUuid = current.uuid ?: return
        for (oldName in plugin.nameHistory.namesForUuid(currentUuid)) {
            if (oldName.equals(current.name, ignoreCase = true)) continue
            val candidates = LinkedHashSet<TowniaPlayer>()
            nameIndex[oldName]?.let { uuid -> cache[uuid]?.let { candidates.add(it) } }
            cache[offlineUuid(oldName)]?.let { candidates.add(it) }
            for (legacy in candidates) {
                val legacyUuid = legacy.uuid ?: continue
                if (legacyUuid == currentUuid) continue
                // Only offline-mode records: a real UUID is another account.
                if (legacyUuid != offlineUuid(legacy.name ?: continue)) continue
                plugin.logger.info("Linking ${legacy.name} ($legacyUuid) to renamed player ${current.name} ($currentUuid)")
                mergeLegacyResident(legacyUuid, legacy, current)
            }
        }
    }

    /**
     * Admin repair: merges [from] into [into] (town, rank, jail, friends, plots,
     * mayor/leader references). [into] keeps its own town if it already has one.
     */
    fun mergeResidents(from: TowniaPlayer, into: TowniaPlayer): Boolean {
        val fromUuid = from.uuid ?: return false
        if (fromUuid == into.uuid || cache[fromUuid] !== from) return false
        mergeLegacyResident(fromUuid, from, into)
        repairMayor(into)
        return true
    }

    private fun mergeLegacyResident(legacyUuid: UUID, legacy: TowniaPlayer, current: TowniaPlayer) {
        val currentUuid = current.uuid ?: return
        plugin.logger.info("Merging legacy UUID $legacyUuid into current UUID $currentUuid for ${current.name}")
        absorbLegacy(legacyUuid, legacy, current)
        moveReferencesInMemory(legacyUuid, currentUuid)
        persistUuidRemap(legacyUuid, current)
    }

    /** Copies what [current] lacks from [legacy] and drops [legacy] from the cache. References are not moved. */
    private fun absorbLegacy(legacyUuid: UUID, legacy: TowniaPlayer, current: TowniaPlayer) {
        val currentUuid = current.uuid ?: return
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
    }

    /**
     * Offline-mode duplicates: a row whose UUID is the offline UUID derived
     * from its own name, next to a row with a real UUID for the same name.
     * Towny migrations create them whenever a name could not be resolved, and
     * they are otherwise only merged when that player joins again.
     * Returns legacy -> current pairs; nothing is changed.
     */
    fun findOfflineDuplicates(): List<Pair<TowniaPlayer, TowniaPlayer>> {
        val result = ArrayList<Pair<TowniaPlayer, TowniaPlayer>>()
        cache.values.filter { !it.name.isNullOrBlank() && it.uuid != null }
            .groupBy { it.name!!.lowercase(Locale.ROOT) }
            .values
            .filter { it.size > 1 }
            .forEach { group ->
                val (offline, real) = group.partition { it.uuid == offlineUuid(it.name!!) }
                if (offline.isEmpty() || real.isEmpty()) return@forEach
                // Two real UUIDs under one name are two accounts; only the offline row is merged.
                val current = real.maxWith(compareBy<TowniaPlayer>({ it.townUuid != null }, { it.rank.level }, { it.lastSeen }))
                offline.forEach { result.add(it to current) }
            }
        return result
    }

    /**
     * Merges every offline-mode duplicate into the player's real record.
     * Memory is updated at once; the database work runs as one sequential
     * background job (one async task per row would start thousands of threads).
     */
    fun mergeOfflineDuplicates(onDone: (merged: Int, failed: Int) -> Unit): Int {
        val pairs = findOfflineDuplicates()
        val remaps = ArrayList<Pair<UUID, TowniaPlayer>>()
        for ((legacy, current) in pairs) {
            val legacyUuid = legacy.uuid ?: continue
            if (cache[legacyUuid] !== legacy || cache[current.uuid] !== current) continue
            absorbLegacy(legacyUuid, legacy, current)
            remaps.add(legacyUuid to current)
        }
        // One pass over towns, nations, plots and friend lists for all pairs,
        // instead of one full scan of ~100k plots per merged resident.
        moveReferencesInMemory(remaps.associate { (old, resident) -> old to resident.uuid!! })
        remaps.forEach { repairMayor(it.second) }
        plugin.logger.info("Merging ${remaps.size} offline-UUID duplicate residents...")
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            var merged = 0
            var failed = 0
            for ((oldUuid, resident) in remaps) {
                try {
                    db.remapResidentUuid(oldUuid, resident)
                    merged++
                } catch (e: Exception) {
                    failed++
                    plugin.logger.log(Level.SEVERE, "Failed to merge resident $oldUuid into ${resident.uuid} (${resident.name})", e)
                }
                if ((merged + failed) % 500 == 0) plugin.logger.info("Merged ${merged + failed}/${remaps.size} duplicate residents")
            }
            plugin.logger.info("Duplicate resident merge finished: $merged merged, $failed failed.")
            plugin.server.scheduler.runTask(plugin, Runnable { onDone(merged, failed) })
        })
        return remaps.size
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

    private fun moveReferencesInMemory(oldUuid: UUID, newUuid: UUID) = moveReferencesInMemory(mapOf(oldUuid to newUuid))

    private fun moveReferencesInMemory(moves: Map<UUID, UUID>) {
        if (moves.isEmpty()) return
        plugin.townManager.allTowns.forEach { town ->
            town.mayorUuid?.let { old -> moves[old]?.let { town.mayorUuid = it } }
            // An outlaw must stay an outlaw after a UUID change.
            moves.forEach { (old, new) -> if (town.outlaws.remove(old)) town.outlaws.add(new) }
        }
        plugin.nationManager.allNations.forEach { nation ->
            nation.leaderUuid?.let { old -> moves[old]?.let { nation.leaderUuid = it } }
            // Same precedence as the database copy: a value already set on the new UUID wins.
            moves.forEach { (old, new) ->
                nation.titles.remove(old)?.let { nation.titles.putIfAbsent(new, it) }
                nation.surnames.remove(old)?.let { nation.surnames.putIfAbsent(new, it) }
            }
        }
        // Plots are read from this cache; leaving the old owner here would hide
        // the player's plots until restart and write the old UUID back on save.
        plugin.plotManager.allPlots.forEach { plot -> plot.ownerUuid?.let { old -> moves[old]?.let { plot.ownerUuid = it } } }
        val byString = moves.entries.associate { (old, new) -> old.toString() to new.toString() }
        cache.values.forEach { resident ->
            val friends = resident.friends ?: return@forEach
            if (friends.none { it in byString }) return@forEach
            val updated = LinkedHashSet<String?>()
            for (friend in friends) {
                val mapped = byString[friend] ?: friend
                if (mapped != resident.uuid?.toString()) updated.add(mapped)
            }
            friends.clear()
            friends.addAll(updated)
        }
    }

    /**
     * A town's mayor is stored on the town (mayor_uuid) and, separately, as the
     * MAYOR rank on the resident. The two can drift apart (Towny flatfile import,
     * UUID remaps, ranks saved under an old name), which leaves a player listed
     * as mayor but treated as a plain resident by every rank check. The town
     * record is authoritative, so make the resident agree with it.
     */
    fun repairMayor(resident: TowniaPlayer): Boolean {
        val uuid = resident.uuid ?: return false
        val town = plugin.townManager.allTowns.firstOrNull { it.mayorUuid == uuid } ?: return false
        var changed = false
        if (resident.townUuid == null) {
            resident.townUuid = town.id
            changed = true
        } else if (resident.townUuid != town.id) {
            return false
        }
        if (resident.rank != TownRank.MAYOR) {
            resident.rank = TownRank.MAYOR
            changed = true
        }
        if (changed) {
            plugin.logger.info("Repaired mayor record of ${resident.name} for town ${town.name}")
            persist(resident)
        }
        return changed
    }

    /** Runs [repairMayor] for every town's mayor. Returns how many residents were changed. */
    fun repairMayors(): Int {
        var repaired = 0
        for (town in plugin.townManager.allTowns) {
            val mayor = cache[town.mayorUuid ?: continue] ?: continue
            if (repairMayor(mayor)) repaired++
        }
        return repaired
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
