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

    private class MemberIndex(val version: Long, val byTown: Map<UUID, List<TowniaPlayer>>)

    @Volatile
    private var memberIndex: MemberIndex? = null

    init {
        loadAll()
    }

    /** The set of records changed: the member lists must be rebuilt. */
    private fun touch() {
        TowniaPlayer.membershipVersion.incrementAndGet()
    }

    /**
     * Members per town. Walking every resident for each lookup made "/town list" cost a second on
     * a large server; the lists are now rebuilt only after someone joined or left a town.
     * A change made while a rebuild runs leaves the version behind, so the next call rebuilds again.
     */
    private fun membersByTown(): Map<UUID, List<TowniaPlayer>> {
        val version = TowniaPlayer.membershipVersion.get()
        memberIndex?.let { if (it.version == version) return it.byTown }
        val grouped = HashMap<UUID, MutableList<TowniaPlayer>>()
        for (p in cache.values) grouped.getOrPut(p.townUuid ?: continue) { ArrayList() }.add(p)
        memberIndex = MemberIndex(version, grouped)
        return grouped
    }

    fun cacheResident(res: TowniaPlayer) {
        res.uuid?.let { cache.put(it, res) }
        res.uuid?.let { nameIndex.put(res.name!!.lowercase(Locale.getDefault()), it) }
        touch()
    }

    private fun loadAll() {
        cache.clear()
        nameIndex.clear()
        try {
            for (p in db.allResidents) {
                if (p == null) continue
                val uuid = p.uuid ?: continue
                cache[uuid] = p
                // Several rows can share a name (re-registered names, migration leftovers).
                // Index the one seen most recently, which is the account currently using it.
                val key = p.name!!.lowercase(Locale.getDefault())
                val indexed = nameIndex[key]?.let { cache[it] }
                if (indexed == null || p.lastSeen > indexed.lastSeen) nameIndex[key] = uuid
            }
            touch()
            plugin.logger.info("Loaded " + cache.size + " residents.")
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Failed to load residents from database", e)
        }
    }

    fun getResident(uuid: UUID?): Optional<TowniaPlayer> {
        return Optional.ofNullable(cache[uuid])
    }

    fun getResidentByName(name: String): Optional<TowniaPlayer> {
        // The online player owns the name right now. The name index can still point at another
        // record with the same name (an account whose name was re-registered, or a Towny
        // migration leftover under an old UUID); using that record sent invites and outlaw
        // entries to a UUID nobody is logged in with, so the target never noticed them.
        val online = plugin.server.getPlayerExact(name)
        if (online != null) {
            return Optional.of(getOrCreate(online))
        }
        val uuid = nameIndex[name.lowercase(Locale.getDefault())] ?: return Optional.empty<TowniaPlayer>()
        return Optional.ofNullable(cache[uuid])
    }

    val allResidents: MutableList<TowniaPlayer>
        get() = ArrayList(cache.values.toList())

    fun getResidentsByTown(townUuid: UUID): MutableList<TowniaPlayer> {
        val list: MutableList<TowniaPlayer> = ArrayList(membersByTown()[townUuid] ?: emptyList())
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
        for (p in membersByTown()[townUuid] ?: return 0) {
            val name = p.name
            if (name.isNullOrBlank()) unnamed++ else names.add(name.lowercase(Locale.ROOT))
        }
        return names.size + unnamed
    }

    fun getResidentsByNation(nationUuid: UUID): MutableList<TowniaPlayer> {
        val list: MutableList<TowniaPlayer> = ArrayList<TowniaPlayer>()
        val members = membersByTown()
        for (town in plugin.townManager.allTowns) {
            if (town.nationUuid == nationUuid) members[town.id]?.let(list::addAll)
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
                val oldKey = existing.name!!.lowercase(Locale.getDefault())
                if (nameIndex[oldKey] == uuid) nameIndex.remove(oldKey)
                existing.name = name
                persist(existing)
            }
            // Whoever is logged in with this name owns it, even if another record shares it.
            nameIndex[name.lowercase(Locale.getDefault())] = uuid
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
            touch()
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
        touch()
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
        // Two kinds of legacy record reach this point, and they need opposite treatment.
        //
        // A stranger's first login: [current] is a record made here a moment (or a while) ago, and
        // [legacy] is the resident who came over from Towny. Their town is the legacy one.
        //
        // A copy: [current] already is that resident (the record moved to the real UUID at an
        // earlier login, which keeps the registration time), and [legacy] is a second record of the
        // same resident left behind or imported again since. A copy holds an old state. Taking its
        // town "because the player has none" is what put players who had left a town back into it.
        // Of two records of one resident, the one used last is the one that is right.
        val sameResident = legacy.registeredAt > 0L && legacy.registeredAt == current.registeredAt
        val legacyTownExists = legacy.townUuid == null || plugin.townManager.getTown(legacy.townUuid).isPresent
        if (sameResident) {
            if (legacy.lastSeen > current.lastSeen && legacyTownExists) {
                current.townUuid = legacy.townUuid
                current.rank = legacy.rank
                current.townRanks.clear()
                current.townRanks.addAll(legacy.townRanks)
                current.nationRanks.clear()
                current.nationRanks.addAll(legacy.nationRanks)
                current.lastSeen = legacy.lastSeen
            }
        } else if (current.townUuid == null && legacy.townUuid != null && legacyTownExists) {
            // Only a town that still exists is carried over; a dangling reference would leave the
            // player "in" a town nobody can find.
            current.townUuid = legacy.townUuid
            current.rank = legacy.rank
        }
        if (current.jailedTownUuid == null && legacy.jailedTownUuid != null) {
            current.jailedTownUuid = legacy.jailedTownUuid
            current.jailReleaseAt = legacy.jailReleaseAt
            current.jailBail = legacy.jailBail
        }
        if (current.about.isNullOrBlank() && !legacy.about.isNullOrBlank()) current.about = legacy.about
        current.registeredAt = listOf(current.registeredAt, legacy.registeredAt)
            .filter { it > 0L }
            .minOrNull() ?: current.registeredAt
        legacy.friends?.filterNotNull()?.forEach { friend ->
            if (friend != currentUuid.toString() && current.friends?.contains(friend) != true) current.friends?.add(friend)
        }

        cache.remove(legacyUuid)
        touch()
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
        // A record stored under this name's offline UUID belongs to this player whatever it is
        // called: an import once named such records after their UUID, so the name never matched
        // and the player joined as a stranger, outside their town and without their plots.
        val byOfflineUuid = offlineUuid(name)
        if (byOfflineUuid != currentUuid && cache[byOfflineUuid]?.name?.equals(name, ignoreCase = true) == false) {
            return byOfflineUuid
        }
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

    /**
     * Runs [repairMayor] for every town's mayor, then takes the mayor rank away from anyone who
     * holds it without being the mayor their town names. A town has exactly one mayor; an import
     * that put a mayor into the wrong town left that town with two.
     * The rank is only taken where the town does have its mayor among its members. Where the
     * town names nobody, or somebody who is not there, the holder of the rank may be the only
     * one who can still run the town, and which of the two records is wrong is for staff to say.
     * Returns how many residents were changed.
     */
    fun repairMayors(): Int {
        var repaired = 0
        for (town in plugin.townManager.allTowns) {
            val mayor = cache[town.mayorUuid ?: continue] ?: continue
            if (repairMayor(mayor)) repaired++
        }
        for (resident in cache.values) {
            if (resident.rank != TownRank.MAYOR) continue
            val town = resident.townUuid?.let { plugin.townManager.getTown(it).orElse(null) }
            if (town != null && town.mayorUuid == resident.uuid) continue
            // The mayor the town names can be a second record of this very player (same name, the
            // other UUID). They are one person; merging the two records settles who holds the rank.
            val named = town?.mayorUuid?.let { cache[it] }
            if (town != null && named?.name.equals(resident.name, ignoreCase = true)) continue
            if (town != null && named?.townUuid != town.id) {
                plugin.logger.warning("${resident.name} holds the mayor rank in ${town.name}, but the town names " +
                    (named?.name ?: "nobody known") + " as its mayor, who is not one of its members. Left as it is; " +
                    "settle it with /towniaadmin setmayor ${town.name} <player>.")
                continue
            }
            plugin.logger.info("Removed the mayor rank from ${resident.name}: " +
                (if (town == null) "not in a town" else "the mayor of ${town.name} is someone else"))
            resident.rank = TownRank.RESIDENT
            persist(resident)
            repaired++
        }
        return repaired
    }

    fun setTown(playerUuid: UUID?, townUuid: UUID?, rank: TownRank?) {
        val p: TowniaPlayer = cache[playerUuid] ?: return
        if (p.townUuid != townUuid) {
            // Ranks belong to the town that granted them.
            p.townRanks.clear()
            p.nationRanks.clear()
        }
        p.townUuid = townUuid
        p.rank = rank ?: TownRank.RESIDENT
        persist(p)
    }

    fun clearTown(playerUuid: UUID?) {
        val p: TowniaPlayer = cache[playerUuid] ?: return
        val leftTown = p.townUuid
        p.townUuid = null
        p.rank = TownRank.RESIDENT
        p.townRanks.clear()
        p.nationRanks.clear()
        persist(p)
        // A resident who leaves or is kicked loses the plots they held in that town (as in Towny).
        // Only that town's: a record that sat in the wrong town must not cost the player the plots
        // of the town they really belong to when it is moved out of the wrong one.
        if (playerUuid != null && leftTown != null) plugin.plotManager.releaseOwnedPlots(playerUuid, leftTown)
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

    /** Gives a record another name. A record that already goes by that name keeps being found by it. */
    fun renameRecord(resident: TowniaPlayer, name: String) {
        val uuid = resident.uuid ?: return
        resident.name?.lowercase(Locale.getDefault())?.let { if (nameIndex[it] == uuid) nameIndex.remove(it) }
        resident.name = name
        nameIndex.putIfAbsent(name.lowercase(Locale.getDefault()), uuid)
        persist(resident)
    }

    /**
     * Runs a write. With the write queue (the default) it is recorded at once: handing it to
     * another thread first let two saves of one resident overtake each other, saved whatever the
     * resident looked like a moment later, and dropped the write when the server was stopping.
     * Without the queue the write really talks to the database, so it leaves the main thread.
     */
    private fun write(what: String, action: () -> Unit) {
        val guarded = Runnable {
            try {
                action()
            } catch (e: SQLException) {
                plugin.logger.log(Level.SEVERE, "Failed to $what", e)
            }
        }
        if (db.writesAreQueued) guarded.run() else plugin.server.scheduler.runTaskAsynchronously(plugin, guarded)
    }

    private fun persist(player: TowniaPlayer) {
        write("save resident " + player.name) { db.saveResident(player) }
    }

    fun addFriend(player: TowniaPlayer, friend: TowniaPlayer) {
        if (!player.friends!!.contains(friend.uuid.toString())) {
            player.friends!!.add(friend.uuid.toString())
            write("add friend for resident " + player.name) { db.addFriend(player.uuid!!, friend.uuid!!) }
        }
    }

    fun removeFriend(player: TowniaPlayer, friend: TowniaPlayer) {
        if (player.friends!!.contains(friend.uuid.toString())) {
            player.friends!!.remove(friend.uuid.toString())
            write("remove friend for resident " + player.name) { db.removeFriend(player.uuid!!, friend.uuid!!) }
        }
    }
}
