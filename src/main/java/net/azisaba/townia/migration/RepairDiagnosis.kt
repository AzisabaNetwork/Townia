package net.azisaba.townia.migration

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaPlayer
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

/**
 * Read-only findings written into the repair report: what Towny's files look like, and where the
 * live data differs from them. Nothing here changes anything; it exists so that one run on the
 * live server answers what would otherwise take guessing: which Towny wrote the files, whether
 * residents, plot owners and mayors still match, and why a plot that had an owner has none now.
 */
internal class RepairDiagnosis(private val plugin: Townia) {
    private val counts = LinkedHashMap<String, Int>()
    private val samples = LinkedHashMap<String, MutableList<String>>()
    private val residentFilesByName = HashMap<String, Int>()

    /** The town Towny has each resident in, by lower-case name (null: none). */
    private val townyTownByName = HashMap<String, UUID?>()

    /** The name Towny had for each resident UUID in its files. */
    val townyNameByUuid = HashMap<UUID, String>()

    private fun count(path: String, by: Int = 1) {
        counts.merge(path, by, Int::plus)
    }

    private fun sample(path: String, text: String) {
        val list = samples.getOrPut(path) { ArrayList() }
        if (list.size < SAMPLES) list.add(text)
    }

    private fun both(path: String, text: String) {
        count(path)
        sample(path, text)
    }

    private fun townName(id: UUID?): String =
        if (id == null) "(no town)" else plugin.townManager.getTown(id).orElse(null)?.name ?: "(a town Townia does not have)"

    /** One Towny resident file, with the town it resolves to and Townia's record of that player. */
    fun resident(
        fileNamedByUuid: Boolean,
        hasUuidKey: Boolean,
        name: String,
        townyUuid: UUID,
        record: TowniaPlayer?,
        town: UUID?,
        fileTown: UUID?,
        listedTown: UUID?,
        staleFile: Boolean,
        staleList: Boolean
    ) {
        count("format.resident-files")
        if (hasUuidKey) count("format.resident-files-with-uuid-key")
        if (fileNamedByUuid) count("format.resident-files-named-by-uuid")
        count(if (townyUuid.version() == 3) "format.resident-uuids-offline-v3" else "format.resident-uuids-online-v4")
        townyNameByUuid[townyUuid] = name
        townyTownByName[name.lowercase()] = town
        residentFilesByName.merge(name.lowercase(), 1, Int::plus)

        if (staleList) both("towny-files.on-an-old-member-list-of-another-town", "$name: own file says ${townName(fileTown)}, list of ${townName(listedTown)}")
        if (staleFile) both("towny-files.old-file-names-a-town-that-does-not-list-them", "$name: ${townName(fileTown)}")

        if (record == null) {
            if (town != null) both("residents.in-a-town-in-towny-but-no-townia-record", "$name: ${townName(town)}")
            return
        }
        val current = record.townUuid
        when {
            current == town && town == null -> count("residents.townless-in-both")
            current == town -> count("residents.same-town")
            current == null -> both("residents.townless-in-townia-but-in-a-town-in-towny", "$name: Towny ${townName(town)}")
            town == null -> both("residents.in-a-town-in-townia-but-townless-in-towny", "$name: Townia ${townName(current)}")
            else -> both("residents.different-town", "$name: Townia ${townName(current)}, Towny ${townName(town)}")
        }
    }

    /** One Towny plot that had an owner. [owner] is Townia's record of that owner, however it was found. */
    fun plot(
        world: String,
        x: Int,
        z: Int,
        ownerRef: String,
        owner: TowniaPlayer?,
        plot: Plot?,
        townyTown: UUID?,
        plannedTown: Map<UUID, UUID>
    ) {
        val where = "$world $x,$z"
        count("plots.owned-in-towny")
        count(if (runCatching { UUID.fromString(ownerRef) }.isSuccess) "format.plot-owners-written-as-uuid" else "format.plot-owners-written-as-name")
        val who = owner?.name ?: ownerRef
        when {
            plot == null -> both("plots.not-claimed-in-townia", "$where: $who")
            townyTown != null && plot.townUuid != townyTown ->
                both("plots.in-another-town-in-townia", "$where: Townia ${townName(plot.townUuid)}, Towny ${townName(townyTown)}")
            plot.ownerUuid != null && owner != null && plot.ownerUuid == owner.uuid -> count("plots.same-owner")
            plot.ownerUuid != null -> {
                val now = plugin.residentManager.getResident(plot.ownerUuid).orElse(null)?.name ?: plot.ownerUuid.toString()
                both("plots.another-owner-in-townia", "$where (${townName(plot.townUuid)}): Towny $who, Townia $now")
            }
            plot.isForSale -> count("plots.no-owner-in-townia.for-sale-now")
            owner == null -> both("plots.no-owner-in-townia.owner-has-no-townia-record", "$where (${townName(plot.townUuid)}): $who")
            (plannedTown[owner.uuid] ?: owner.townUuid) != plot.townUuid ->
                both(
                    "plots.no-owner-in-townia.owner-is-not-in-that-town-now",
                    "$where (${townName(plot.townUuid)}): $who is in ${townName(owner.townUuid)}"
                )
            else -> count("plots.no-owner-in-townia.restorable")
        }
    }

    /** Towns and residents as Townia holds them. [townyMayorByTown]: town -> the mayor's name or UUID in Towny, lower case. */
    fun finish(townFiles: Int, townFilesWithMemberList: Int, townyMayorByTown: Map<UUID, String>) {
        counts["format.town-files"] = townFiles
        counts["format.town-files-with-member-list"] = townFilesWithMemberList
        residentFilesByName.filter { it.value > 1 }.forEach { (name, files) ->
            both("towny-files.names-with-several-resident-files", "$name: $files files")
        }

        val residents = plugin.residentManager.allResidents
        counts["townia.residents"] = residents.size
        counts["townia.residents-in-a-town"] = residents.count { it.townUuid != null }
        val offline = residents.filter { it.uuid?.version() == 3 }
        counts["townia.residents-under-an-offline-uuid"] = offline.size
        counts["townia.residents-under-an-offline-uuid-in-a-town"] = offline.count { it.townUuid != null }
        // A record that goes by a UUID instead of a name: nobody can log in as it or be found by name.
        val unnamed = residents.filter { r -> r.name?.let { runCatching { UUID.fromString(it) }.isSuccess } == true }
        counts["townia.residents-whose-name-is-a-uuid"] = unnamed.size
        counts["townia.residents-whose-name-is-a-uuid-in-a-town"] = unnamed.count { it.townUuid != null }
        unnamed.filter { it.townUuid != null }.forEach { sample("townia.residents-whose-name-is-a-uuid-in-a-town", "${it.name}: ${townName(it.townUuid)} (${it.rank})") }
        // Records under an offline UUID that the Towny files do not have were made here, by a
        // player who reached this server without their real UUID.
        counts["townia.offline-uuid-records-towny-does-not-have"] = offline.count { it.uuid !in townyNameByUuid.keys }
        val monthAgo = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        counts["townia.offline-uuid-records-seen-in-the-last-30-days"] = offline.count { it.lastSeen > monthAgo }
        counts["townia.real-uuid-records-seen-in-the-last-30-days"] = residents.count { it.uuid?.version() != 3 && it.lastSeen > monthAgo }

        // One player, two records: which one holds what decides what a merge would do to them.
        for ((legacy, current) in plugin.residentManager.findOfflineDuplicates()) {
            count("pairs.total")
            if (legacy.registeredAt > 0 && legacy.registeredAt == current.registeredAt) count("pairs.registered-at-the-same-moment")
            if (legacy.lastSeen > current.lastSeen) count("pairs.offline-record-seen-later-than-the-real-one")
            val towny = townyTownByName[current.name?.lowercase()]
            val text = "${current.name}: offline ${townName(legacy.townUuid)} (${legacy.rank}), real ${townName(current.townUuid)} (${current.rank}), Towny ${townName(towny)}"
            // What "/towniaadmin dedupe confirm" (and the player's next login) would do: the record
            // used last keeps its town and rank. Only these pairs change the real record.
            if (legacy.lastSeen > current.lastSeen && (legacy.townUuid != current.townUuid || legacy.rank != current.rank)) {
                both("pairs.merge-would-take-the-town-and-rank-of-the-offline-record", text)
            }
            when {
                legacy.townUuid == null && current.townUuid == null -> count("pairs.both-townless")
                legacy.townUuid == current.townUuid && legacy.rank == current.rank -> count("pairs.same-town-and-rank")
                legacy.townUuid == current.townUuid -> both("pairs.same-town-another-rank", text)
                current.townUuid == null -> both(
                    if (legacy.townUuid == towny) "pairs.real-townless.offline-in-its-towny-town" else "pairs.real-townless.offline-in-a-town-towny-does-not-say",
                    text
                )
                legacy.townUuid == null -> both("pairs.offline-townless-real-in-a-town", text)
                else -> both("pairs.two-different-towns", text)
            }
        }

        val mayorRanks = residents.filter { it.rank == TownRank.MAYOR && it.townUuid != null }.groupBy { it.townUuid }
        for (town in plugin.townManager.allTowns) {
            val holders = mayorRanks[town.id].orEmpty()
            if (holders.size > 1) both("towns.several-residents-with-the-mayor-rank", "${town.name}: ${holders.joinToString { it.name ?: "?" }}")
            val mayor = plugin.residentManager.getResident(town.mayorUuid).orElse(null)
            when {
                mayor == null -> both("towns.mayor-has-no-record", "${town.name}: ${town.mayorUuid}")
                mayor.townUuid != town.id -> both("towns.mayor-is-not-in-the-town", "${town.name}: ${mayor.name} is in ${townName(mayor.townUuid)}")
                else -> {
                    val towny = townyMayorByTown[town.id] ?: continue
                    val same = mayor.name?.lowercase() == towny || mayor.uuid.toString() == towny || townyNameByUuid.entries.any {
                        it.key.toString() == towny && it.value.equals(mayor.name, ignoreCase = true)
                    }
                    if (same) count("towns.same-mayor") else both("towns.another-mayor-than-in-towny", "${town.name}: Townia ${mayor.name}, Towny $towny")
                }
            }
        }
    }

    fun writeTo(report: YamlConfiguration) {
        counts.forEach { (path, value) -> report.set("diagnosis.$path", value) }
        samples.forEach { (path, list) -> report.set("diagnosis-samples.$path", list) }
    }

    /** The lines worth showing in chat; the rest is in the file. */
    fun summary(): List<String> = listOf(
        "Towny files: ${counts["format.resident-files-with-uuid-key"] ?: 0}/${counts["format.resident-files"] ?: 0} resident files written by a newer Towny, " +
            "${counts["format.town-files-with-member-list"] ?: 0}/${counts["format.town-files"] ?: 0} town files still with a member list",
        "residents vs Towny: ${counts["residents.same-town"] ?: 0} same town, ${counts["residents.different-town"] ?: 0} in another town, " +
            "${counts["residents.townless-in-townia-but-in-a-town-in-towny"] ?: 0} townless now, " +
            "${counts["residents.in-a-town-in-townia-but-townless-in-towny"] ?: 0} in a town only now",
        "plots owned in Towny: ${counts["plots.owned-in-towny"] ?: 0}, same owner ${counts["plots.same-owner"] ?: 0}, " +
            "no owner now ${counts.filterKeys { it.startsWith("plots.no-owner-in-townia.") }.values.sum()}, another owner ${counts["plots.another-owner-in-townia"] ?: 0}"
    )

    private companion object {
        const val SAMPLES = 40
    }
}
