package net.azisaba.townia.migration

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TowniaJailCell
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaOutpost
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.util.ChunkKey
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.logging.Level

object TownyFlatfileMigrator {
    private val allParts = setOf("towns", "nations", "residents", "townblocks", "jails", "outlaws", "relations")

    /** Names Townia already has a resident for; set at the start of each run. */
    @Volatile
    private var knownUuids: Map<String, UUID> = emptyMap()

    /** Resident files that named a town which does not list them (counted per run, for the log). */
    @Volatile
    private var staleTownReferences = 0

    /** Residents a town file still listed although their own, newer file names another town or none. */
    @Volatile
    private var staleTownLists = 0

    /** Plots whose owner could not be matched to any resident (counted per run, for the log). */
    @Volatile
    private var lostPlotOwners = 0

    private val SUB_MAYOR_NAMES = setOf("co_mayor", "comayor", "submayor", "sub_mayor")

    /** Town / nation files to migrate, chosen once per run (see [selectGovernmentFiles]). */
    @Volatile
    private var townFiles: List<File> = emptyList()

    @Volatile
    private var nationFiles: List<File> = emptyList()

    /**
     * Limits a run to one town and/or one resident (`/ta migrate only ...`).
     * [townUuid] is filled once the town file has been found.
     */
    private class Selection(val townName: String?, val residentName: String?, val settingsOnly: Boolean) {
        var townUuid: UUID? = null
        var residentUuid: UUID? = null

        fun wantsResident(name: String, uuid: UUID, residentTown: UUID?): Boolean {
            if (residentName != null && (name.equals(residentName, ignoreCase = true) || uuid == residentUuid)) return true
            return townName != null && !settingsOnly && residentTown != null && residentTown == townUuid
        }

        fun wantsPlot(plotTown: UUID?, owner: UUID?): Boolean {
            if (settingsOnly) return false
            if (townName != null && plotTown != null && plotTown == townUuid) return true
            return residentName != null && owner != null && owner == residentUuid
        }
    }

    @Volatile
    private var selection: Selection? = null

    /**
     * Migrates one town and/or one resident from Towny's flat files, leaving every other
     * record Townia already has untouched. A town brings its settings, members, land,
     * outlaws and jails; a resident brings their own settings, friends and the plots they
     * own. With [settingsOnly] only the town/resident record itself is written.
     */
    fun migrateOnly(
        plugin: Townia,
        sender: CommandSender,
        townName: String?,
        residentName: String?,
        settingsOnly: Boolean,
        missingOnly: Boolean
    ) {
        val dataDir = findTownyDataDir(plugin)
        if (dataDir == null) {
            plugin.messageManager.sendMessage(sender, "admin.migration_failed")
            plugin.logger.warning("No Towny flatfile data directory was found (plugins/Towny/data).")
            return
        }
        BulkRead.start(plugin, sender, "migrate only", Runnable {
            val only = Selection(townName, residentName, settingsOnly)
            plugin.messageManager.sendMessage(sender, "admin.migration_start")
            val label = listOfNotNull(townName?.let { "town $it" }, residentName?.let { "resident $it" }).joinToString(" + ")
            plugin.messageManager.sendMessage(sender, "admin.migration-source", "source", "Towny flatfile: ${dataDir.path} ($label)")
            try {
                knownUuids = MigrationSupport.knownUuidsByName(plugin)
                towniaUuidByTownyUuid.clear()
                selectGovernmentFiles(plugin, dataDir, true)
                val residentsByName = loadResidentIds(dataDir)
                val nationsByName = loadNations(plugin, dataDir, residentsByName, false)
                // Every town is read (not saved) so names in other files still resolve.
                val townsByName = loadTowns(plugin, dataDir, residentsByName, nationsByName, false)

                if (residentName != null) {
                    only.residentUuid = residentsByName[residentName.lowercase(Locale.getDefault())]
                        ?: residentName.toUuidOrNull()
                    if (only.residentUuid == null) {
                        plugin.messageManager.sendMessage(sender, "admin.migrate-only-not-found", "kind", "resident", "name", residentName)
                        return@Runnable
                    }
                }
                val allTownFiles = townFiles
                // Who belongs where is decided by every town's member list, not only the chosen town's.
                val memberships = loadTownMemberships(dataDir, townsByName)
                var towns = 0
                if (townName != null) {
                    val wanted = townName.lowercase(Locale.getDefault())
                    val file = allTownFiles.firstOrNull { f ->
                        val values = readValues(f)
                        (values.first("name", "town") ?: fileDisplayName(f)).lowercase(Locale.getDefault()) == wanted ||
                            f.nameWithoutExtension.lowercase(Locale.getDefault()) == wanted
                    }
                    if (file == null) {
                        plugin.messageManager.sendMessage(sender, "admin.migrate-only-not-found", "kind", "town", "name", townName)
                        return@Runnable
                    }
                    val fileValues = readValues(file)
                    val fileTownName = (fileValues.first("name", "town") ?: fileDisplayName(file)).lowercase(Locale.getDefault())
                    only.townUuid = townsByName[fileTownName]
                        ?: fileValues.uuid("uuid") ?: file.nameWithoutExtension.toUuidOrNull()
                    // From here on the per-town loaders only see the chosen town.
                    townFiles = listOf(file)
                    selection = only
                    loadTowns(plugin, dataDir, residentsByName, nationsByName, true)
                    towns = 1
                    MigrationSupport.progress(plugin, sender, "towns", "1")
                    if (!settingsOnly) {
                        MigrationSupport.progress(plugin, sender, "outlaws", loadTownOutlaws(plugin, dataDir, townsByName, residentsByName).toString())
                    }
                } else {
                    townFiles = emptyList()
                }
                selection = only

                val residents = loadResidents(plugin, dataDir, residentsByName, townsByName, memberships, missingOnly)
                MigrationSupport.progress(plugin, sender, "residents", residents.toString())

                var plots = 0
                if (!settingsOnly) {
                    plots = loadTownBlocks(plugin, dataDir, townsByName, residentsByName, missingOnly, HashSet())
                    MigrationSupport.progress(plugin, sender, "townblocks", plots.toString())
                    if (townName != null) {
                        MigrationSupport.progress(plugin, sender, "jails", loadJails(plugin, dataDir).toString())
                    }
                }
                plugin.residentManager.repairMayors()
                plugin.messageManager.sendMessage(
                    sender, "admin.migration_success",
                    "towns", towns.toString(), "nations", "0", "residents", residents.toString(), "plots", plots.toString()
                )
                plugin.logger.info("Towny flatfile migration of $label finished: $residents resident(s), $plots plot(s).")
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Towny flatfile migration of $label failed", e)
                plugin.messageManager.sendMessage(sender, "admin.migration_failed")
            } finally {
                selection = null
            }
        })
    }

    /**
     * `/towniaadmin migrate repair [confirm]`: undoes what an earlier, faulty import left in a
     * server that is already live, using Towny's files as the reference.
     *
     * Only cases that carry the fault's own signature are touched, so nothing a player did since
     * the import is rolled back:
     *  - a resident who sits in the town that the stale side of Towny's files named (an old member
     *    list, or an old resident file; see [resolveMembership]) is moved to the town they belong to;
     *  - a plot that has no owner and is not for sale, while Towny names an owner who is a member
     *    of the plot's town today, gets that owner back;
     *  - whoever holds the mayor rank without being their town's mayor loses the rank.
     * Residents who sit in a town that no longer lists them are only reported.
     * Without [apply] nothing is changed; the findings go to towny-compat/repair-report.yml.
     */
    fun repair(plugin: Townia, sender: CommandSender, apply: Boolean) {
        val dataDir = findTownyDataDir(plugin)
        if (dataDir == null) {
            plugin.messageManager.sendMessage(sender, "admin.migration_failed")
            plugin.logger.warning("No Towny flatfile data directory was found (plugins/Towny/data).")
            return
        }
        BulkRead.start(plugin, sender, "repair", Runnable {
            try {
                knownUuids = MigrationSupport.knownUuidsByName(plugin)
                towniaUuidByTownyUuid.clear()
                selectGovernmentFiles(plugin, dataDir, false)
                val residentsByName = loadResidentIds(dataDir)
                val nationsByName = loadNations(plugin, dataDir, residentsByName, false)
                val townsByName = loadTowns(plugin, dataDir, residentsByName, nationsByName, false)
                val memberships = loadTownMemberships(dataDir, townsByName)
                val report = org.bukkit.configuration.file.YamlConfiguration()
                fun townName(id: UUID?): String = id?.let { plugin.townManager.getTown(it).orElse(null)?.name } ?: "-"

                // ---- residents
                val moved = ArrayList<String>()
                val notListed = ArrayList<String>()
                // Where each moved resident ends up, so a dry run counts the plots they get back too.
                val plannedTown = HashMap<UUID, UUID>()
                val diagnosis = RepairDiagnosis(plugin)
                val renamed = ArrayList<String>()
                File(dataDir, "residents").listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
                    val values = readValues(file)
                    val (name, fileUuid) = residentIdentity(values, file)
                    val key = name.lowercase(Locale.getDefault())
                    val found = plugin.residentManager.getResident(residentsByName[key] ?: fileUuid).orElse(null)
                        ?: plugin.residentManager.getResidentByName(name).orElse(null)
                    resolveMembership(values, key, (found?.uuid ?: fileUuid).toString().lowercase(Locale.getDefault()), memberships, townsByName).let {
                        diagnosis.resident(
                            file.nameWithoutExtension.toUuidOrNull() != null, values.has("uuid"), name, townyIdentity(values, file).second, found,
                            it.town, it.fileTown, it.listedTown, it.staleFile, it.staleList
                        )
                    }
                    val resident = found ?: return@forEach
                    // An earlier import named some records after their UUID (see residentIdentity).
                    if (resident.name?.toUuidOrNull() != null && name.toUuidOrNull() == null) {
                        renamed.add("${resident.name} -> $name")
                        if (apply) plugin.residentManager.renameRecord(resident, name)
                    }
                    val current = resident.townUuid ?: return@forEach
                    val uuidKey = resident.uuid.toString().lowercase(Locale.getDefault())
                    val membership = resolveMembership(values, key, uuidKey, memberships, townsByName)
                    val expected = membership.town
                    if (current == expected) return@forEach
                    // Only the fault's own signature: the resident sits in the town the stale side named.
                    // Anywhere else, a player or an admin put them there after the import.
                    if (current != membership.fileTown && current != membership.listedTown) return@forEach
                    if (expected == null) {
                        notListed.add("$name: in ${townName(current)}, which Towny no longer had them in")
                        return@forEach
                    }
                    if (plugin.townManager.getTown(expected).isEmpty) return@forEach
                    val fileRanksApply = membership.fileTown == null || membership.fileTown == expected
                    val rank = resolveRank(values, key, uuidKey, memberships, expected, fileRanksApply)
                    moved.add("$name: ${townName(current)} -> ${townName(expected)} ($rank)")
                    resident.uuid?.let { plannedTown[it] = expected }
                    if (apply) {
                        plugin.residentManager.setTown(resident.uuid, expected, rank)
                        if (fileRanksApply) {
                            resident.townRanks.addAll(extraTownRanks(values))
                            resident.nationRanks.addAll(TowniaPlayer.parseRanks(values.list("nationranks", "nation_ranks").joinToString(",")))
                            plugin.residentManager.saveResident(resident)
                        }
                    }
                }

                // ---- plot owners
                val restored = ArrayList<String>()
                File(dataDir, "townblocks").takeIf { it.isDirectory }?.walkTopDown()?.filter { isTownyDataFile(it) }?.forEach { file ->
                    val values = readValues(file)
                    val ownerName = values.first("resident")?.takeIf { it.isNotBlank() } ?: return@forEach
                    val chunk = parseTownBlockChunk(values, file) ?: return@forEach
                    val plot = plugin.plotManager.getPlot(chunk.world, chunk.x, chunk.z).orElse(null)
                    val townUuid = resolveGovernmentUuid(values.first("town"), townsByName)
                    // An owner who has logged in since had their record moved from the UUID Towny
                    // knew to their own, so that UUID finds nobody: look up the name Towny had for it.
                    val ownerUuid = resolveResidentUuid(ownerName, residentsByName)
                    val owner = ownerUuid?.let { plugin.residentManager.getResident(it).orElse(null) }
                        ?: (ownerUuid?.let { diagnosis.townyNameByUuid[it] } ?: ownerName.takeIf { it.toUuidOrNull() == null })
                            ?.let { plugin.residentManager.getResidentByName(it).orElse(null) }
                    diagnosis.plot(chunk.world, chunk.x, chunk.z, ownerName, owner, plot, townUuid, plannedTown)
                    if (plot == null || townUuid == null || owner == null) return@forEach
                    if (plot.ownerUuid != null || plot.isForSale) return@forEach
                    if (plot.townUuid != townUuid) return@forEach
                    // Whoever has left the town since gave the plot up on purpose.
                    if ((plannedTown[owner.uuid] ?: owner.townUuid) != townUuid) return@forEach
                    restored.add("${chunk.world} ${chunk.x},${chunk.z} (${townName(townUuid)}): ${owner.name ?: ownerName}")
                    if (apply) {
                        plot.changeOwner(owner.uuid)
                        plugin.plotManager.persistPlot(plot)
                    }
                }

                // ---- mayors
                val falseMayors = plugin.residentManager.allResidents.filter { resident ->
                    resident.rank == TownRank.MAYOR &&
                        resident.townUuid?.let { plugin.townManager.getTown(it).orElse(null) }?.mayorUuid != resident.uuid
                }.map { "${it.name}: ${townName(it.townUuid)}" }
                if (apply) plugin.residentManager.repairMayors()

                report.set("applied", apply)
                report.set("residents-moved-to-their-town", moved)
                report.set("plot-owners-restored", restored)
                report.set("mayor-rank-removed", falseMayors)
                report.set("residents-given-their-name-back", renamed)
                report.set("residents-in-a-town-towny-no-longer-had-them-in-NOT-changed", notListed)
                diagnosis.finish(
                    townFiles.size, memberships.townsWithMemberList.size,
                    memberships.mayorTownByResident.entries.associate { it.value to it.key }
                )
                diagnosis.writeTo(report)
                val file = File(plugin.dataFolder, "towny-compat/repair-report.yml")
                file.parentFile.mkdirs()
                report.save(file)
                val verb = if (apply) "fixed" else "would fix"
                for (line in listOf(
                    "$verb: ${moved.size} resident(s) in the town a stale Towny file named -> the town they belong to",
                    "$verb: ${restored.size} plot(s) without an owner -> the owner Towny names",
                    "$verb: ${falseMayors.size} resident(s) holding the mayor rank without being the mayor",
                    "$verb: ${renamed.size} resident record(s) named after their UUID -> their name",
                    "reported only: ${notListed.size} resident(s) in a town Towny no longer had them in"
                ) + diagnosis.summary()) plugin.messageManager.sendMessage(sender, "admin.audit-line", "text", line)
                plugin.messageManager.sendMessage(sender, if (apply) "admin.repair-done" else "admin.repair-dry-run", "file", file.path)
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Towny repair failed", e)
                plugin.messageManager.sendMessage(sender, "admin.migration_failed")
            }
        })
    }

    fun migrate(plugin: Townia, sender: CommandSender, requestedParts: Set<String> = emptySet()) {
        val dataDir = findTownyDataDir(plugin)
        if (dataDir == null) {
            plugin.messageManager.sendMessage(sender, "admin.migration_failed")
            plugin.logger.warning("Towny is not enabled and no Towny flatfile data directory was found.")
            return
        }
        val parts = normalizeParts(requestedParts)

        BulkRead.start(plugin, sender, "migrate", Runnable {
            plugin.messageManager.sendMessage(sender, "admin.migration_start")
            plugin.messageManager.sendMessage(sender, "admin.migration-source", "source", "Towny flatfile: ${dataDir.path} (${parts.joinToString(",")})")
            try {
                knownUuids = MigrationSupport.knownUuidsByName(plugin)
                towniaUuidByTownyUuid.clear()
                selectGovernmentFiles(plugin, dataDir, requestedParts.contains(MigrationSupport.INCLUDE_DELETED))
                MigrationSupport.progress(plugin, sender, "resident UUID map", "0")
                val residentsByName = loadResidentIds(dataDir)
                MigrationSupport.progress(plugin, sender, "resident UUID map", residentsByName.size.toString())

                MigrationSupport.progress(plugin, sender, "nations", "0")
                val nationsByName = loadNations(plugin, dataDir, residentsByName, parts.contains("nations"))
                MigrationSupport.progress(plugin, sender, "nations", if (parts.contains("nations")) nationsByName.size.toString() else "0")

                MigrationSupport.progress(plugin, sender, "towns", "0")
                val townsByName = loadTowns(plugin, dataDir, residentsByName, nationsByName, parts.contains("towns"))
                if (parts.contains("nations")) repairNationCapitals(plugin, dataDir, townsByName, nationsByName)
                MigrationSupport.progress(plugin, sender, "towns", if (parts.contains("towns")) townsByName.size.toString() else "0")

                if (parts.contains("outlaws")) {
                    MigrationSupport.progress(plugin, sender, "outlaws", "0")
                    val outlaws = loadTownOutlaws(plugin, dataDir, townsByName, residentsByName)
                    MigrationSupport.progress(plugin, sender, "outlaws", outlaws.toString())
                }

                if (parts.contains("relations")) {
                    MigrationSupport.progress(plugin, sender, "relations", "0")
                    val relations = loadNationRelations(plugin, dataDir, nationsByName)
                    MigrationSupport.progress(plugin, sender, "relations", relations.toString())
                }

                val townMemberships = loadTownMemberships(dataDir, townsByName)
                MigrationSupport.progress(plugin, sender, "residents", "0")
                val residents = if (parts.contains("residents")) loadResidents(plugin, dataDir, residentsByName, townsByName, townMemberships, requestedParts.contains(MigrationSupport.MISSING_ONLY)) else 0
                MigrationSupport.progress(plugin, sender, "residents", residents.toString())

                MigrationSupport.progress(plugin, sender, "townblocks", "0")
                val missingOnly = requestedParts.contains(MigrationSupport.MISSING_ONLY)
                val seenChunks = HashSet<ChunkKey>()
                val plots = if (parts.contains("townblocks")) loadTownBlocks(plugin, dataDir, townsByName, residentsByName, missingOnly, seenChunks) else 0
                MigrationSupport.progress(plugin, sender, "townblocks", plots.toString())
                if (parts.contains("townblocks") && requestedParts.contains(MigrationSupport.PRUNE)) {
                    val pruned = PlotPruner.prune(plugin, seenChunks)
                    MigrationSupport.progress(plugin, sender, "pruned plots", pruned.toString())
                }

                MigrationSupport.progress(plugin, sender, "jails", "0")
                val jails = if (parts.contains("jails")) loadJails(plugin, dataDir) else 0
                MigrationSupport.progress(plugin, sender, "jails", jails.toString())
                plugin.residentManager.repairMayors()
                sendValidation(plugin, sender)

                plugin.messageManager.sendMessage(
                    sender,
                    "admin.migration_success",
                    "towns",
                    if (parts.contains("towns")) townsByName.size.toString() else "0",
                    "nations",
                    if (parts.contains("nations")) nationsByName.size.toString() else "0",
                    "residents",
                    residents.toString(),
                    "plots",
                    plots.toString()
                )
                plugin.logger.info("Towny flatfile migration successful from ${dataDir.path}.")
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Towny flatfile migration failed", e)
                plugin.messageManager.sendMessage(sender, "admin.migration_failed")
            }
        })
    }

    private fun normalizeParts(requestedParts: Set<String>): Set<String> {
        if (requestedParts.isEmpty() || requestedParts.any { it == "all" || it == "*" }) return allParts
        val result = LinkedHashSet<String>()
        requestedParts.forEach { part ->
            when (part) {
                "town", "towns" -> result.add("towns")
                "nation", "nations" -> result.add("nations")
                "resident", "residents" -> result.add("residents")
                "plot", "plots", "townblock", "townblocks" -> result.add("townblocks")
                "jail", "jails" -> result.add("jails")
                "outlaw", "outlaws" -> result.add("outlaws")
                "relation", "relations", "allies", "enemies" -> result.add("relations")
                "data" -> result.addAll(allParts)
            }
        }
        return if (result.isEmpty()) allParts else result
    }

    /** Town names found in Towny's flat files, for tab completion. Reads file names only. */
    fun townyTownNames(plugin: Townia): List<String> {
        val dir = File(findTownyDataDir(plugin) ?: return emptyList(), "towns")
        return dir.listFiles { file -> isTownyDataFile(file) }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()
    }

    fun findTownyDataDir(plugin: Townia): File? {
        val pluginsDir = plugin.dataFolder.parentFile ?: return null
        val townyDir = File(pluginsDir, "Towny")
        return listOf(
            File(townyDir, "data"),
            townyDir
        ).firstOrNull { File(it, "towns").isDirectory || File(it, "residents").isDirectory || File(it, "nations").isDirectory }
    }

    /** Towny's `database.database_load` (flatfile/mysql), or null when database.yml is absent. */
    fun findTownyDatabaseLoadType(plugin: Townia): String? {
        val pluginsDir = plugin.dataFolder.parentFile ?: return null
        val file = File(pluginsDir, "Towny/settings/database.yml")
        if (!file.isFile) return null
        return runCatching {
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file).getString("database.database_load")
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private class GovernmentFile(val file: File, val name: String, val uuid: UUID?, val live: Boolean)

    /**
     * Picks the town and nation files to migrate. Towny moves files into
     * `deleted/` not only when a town is disbanded but also when it rewrites
     * its flatfile layout (name-named files -> UUID-named files); a rewrite
     * that never finished leaves most living towns only in `deleted/`.
     * A deleted town file is therefore used when a resident file still names
     * that town (a disbanded town has no residents left), and a deleted nation
     * when a chosen town still belongs to it. Per UUID and per name, a live
     * file wins over a deleted one, then the newest file wins.
     */
    private fun selectGovernmentFiles(plugin: Townia, dataDir: File, includeDeleted: Boolean) {
        if (!includeDeleted) {
            // deleted/ can hold files that are years old; only use it when explicitly asked.
            townFiles = pickGovernmentFiles(plugin, File(dataDir, "towns"), "town") { false }
            val named = nationNamesUsedByTowns()
            nationFiles = pickGovernmentFiles(plugin, File(dataDir, "nations"), "nation", { it.name.lowercase(Locale.getDefault()) in named }) { false }
            return
        }
        val townsWithResidents = HashSet<String>()
        File(dataDir, "residents").listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
            val values = readValues(file)
            listOfNotNull(values.first("town", "townuuid", "town_uuid"), values.first("townname", "town_name"))
                .forEach { townsWithResidents.add(it.lowercase(Locale.getDefault())) }
        }
        // Resident files can be missing too (the same unfinished rewrite), so land
        // still claimed in a town's name also counts as that town being alive.
        File(dataDir, "townblocks").takeIf { it.isDirectory }?.walkTopDown()?.filter { isTownyDataFile(it) }?.forEach { file ->
            readValues(file).first("town")?.let { townsWithResidents.add(it.lowercase(Locale.getDefault())) }
        }
        townFiles = pickGovernmentFiles(plugin, File(dataDir, "towns"), "town") { gov ->
            gov.name.lowercase(Locale.getDefault()) in townsWithResidents ||
                (gov.uuid != null && gov.uuid.toString() in townsWithResidents)
        }

        val nationRefs = HashSet<String>()
        townFiles.forEach { file ->
            val values = readValues(file)
            listOfNotNull(values.first("nation", "nationuuid", "nation_uuid"), values.first("nationname", "nation_name"))
                .forEach { nationRefs.add(it.lowercase(Locale.getDefault())) }
        }
        nationFiles = pickGovernmentFiles(plugin, File(dataDir, "nations"), "nation", { it.name.lowercase(Locale.getDefault()) in nationRefs }) { gov ->
            gov.name.lowercase(Locale.getDefault()) in nationRefs ||
                (gov.uuid != null && gov.uuid.toString() in nationRefs)
        }
    }

    /** The nation names town files point at: the names that are really in use. */
    private fun nationNamesUsedByTowns(): Set<String> {
        val names = HashSet<String>()
        townFiles.forEach { file ->
            val values = readValues(file)
            listOfNotNull(values.first("nation"), values.first("nationname", "nation_name"))
                .forEach { names.add(it.lowercase(Locale.getDefault())) }
        }
        return names
    }

    /**
     * [prefer] breaks ties between files of the same UUID. A renamed nation leaves its old file
     * behind (and a badly re-encoded copy can sit next to the real one); the file whose name the
     * towns still use is the current one.
     */
    private fun pickGovernmentFiles(
        plugin: Townia,
        dir: File,
        kind: String,
        prefer: (GovernmentFile) -> Boolean = { false },
        keepDeleted: (GovernmentFile) -> Boolean
    ): List<File> {
        fun read(folder: File, live: Boolean): List<GovernmentFile> =
            folder.listFiles { file -> isTownyDataFile(file) }.orEmpty().map { file ->
                val values = readValues(file)
                GovernmentFile(
                    file,
                    values.first("name", kind) ?: fileDisplayName(file),
                    values.uuid("uuid") ?: file.nameWithoutExtension.toUuidOrNull(),
                    live
                )
            }

        val live = read(dir, true)
        val restored = read(File(dir, "deleted"), false).filter(keepDeleted)
        val seenUuids = HashSet<UUID>()
        val seenNames = HashSet<String>()
        val chosen = (live + restored)
            .sortedWith(
                compareByDescending<GovernmentFile> { it.live }
                    .thenByDescending { prefer(it) }
                    .thenByDescending { it.file.lastModified() }
            )
            .filter { gov ->
                val newUuid = gov.uuid == null || seenUuids.add(gov.uuid)
                newUuid && seenNames.add(gov.name.lowercase(Locale.getDefault()))
            }
        val restoredCount = chosen.count { !it.live }
        plugin.logger.info(
            "Towny flatfile: using ${chosen.size} $kind files (${chosen.size - restoredCount} live, " +
                "$restoredCount restored from ${dir.name}/deleted)."
        )
        return chosen.map { it.file }
    }

    /**
     * Name and UUID of a resident file. Newer Towny names resident files by UUID
     * and may leave out `name=`; using the file name as the name then showed
     * UUIDs in every member list. Such names are looked up instead (EssentialsX
     * userdata, then the server's own player data, neither of which asks Mojang).
     */
    /** Towny's UUID for a resident -> the UUID of the record Townia already has for that player. */
    private val towniaUuidByTownyUuid = java.util.concurrent.ConcurrentHashMap<UUID, UUID>()

    /**
     * A Towny that ran on an offline-mode server gave every resident the offline UUID of their
     * name. Once such a player has logged in here, their record sits under their real UUID.
     * Importing the same file again under Towny's UUID gave that player a second record, still
     * showing the town, rank and plots they had in Towny; at their next login that copy was merged
     * into their real record and put them back into a town they had left.
     * So a resident whose Towny UUID is just their name's offline UUID is written to the record
     * Townia already holds under that name, and everything that refers to them by Towny's UUID
     * (plot owners, mayors, friends) follows through [towniaUuidByTownyUuid].
     */
    private fun ownRecord(name: String, townyUuid: UUID): UUID {
        if (townyUuid != MigrationSupport.offlineUuid(name)) return townyUuid
        val existing = runCatching { Townia.instance.residentManager.getResidentByName(name).orElse(null)?.uuid }.getOrNull()
        if (existing == null || existing == townyUuid) return townyUuid
        towniaUuidByTownyUuid[townyUuid] = existing
        return existing
    }

    private fun residentIdentity(values: FlatValues, file: File): Pair<String, UUID> {
        val (name, uuid) = townyIdentity(values, file)
        return name to ownRecord(name, uuid)
    }

    /** The name and UUID exactly as Towny's file gives them. */
    private fun townyIdentity(values: FlatValues, file: File): Pair<String, UUID> {
        val fileUuid = file.nameWithoutExtension.toUuidOrNull()
        val uuid = values.uuid("uuid", "player_uuid") ?: fileUuid
        val explicitName = values.first("name", "resident")
        if (explicitName != null && explicitName.toUuidOrNull() == null) {
            return explicitName to (uuid ?: MigrationSupport.resolveUuidByName(explicitName, knownUuids))
        }
        if (uuid != null) {
            // A file that carries uuid= but is still named after the player holds the name in its
            // file name. Asking the server for the name of that UUID fails for everyone who has not
            // joined since (Towny gave them an offline UUID), and the resident then went by the UUID
            // text: no town file knew them under that, so the mayor line and the member lists missed
            // them, and they were created a second time from an old list, over their own record.
            val nameFromFile = if (fileUuid == null) fileDisplayName(file) else null
            return (nameFromFile ?: MigrationSupport.nameForUuid(uuid) ?: uuid.toString()) to uuid
        }
        val name = fileDisplayName(file)
        return name to MigrationSupport.resolveUuidByName(name, knownUuids)
    }

    private fun loadResidentIds(dataDir: File): MutableMap<String, UUID> {
        val result = LinkedHashMap<String, UUID>()
        val dir = File(dataDir, "residents")
        dir.listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
            val values = readValues(file)
            val (name, uuid) = residentIdentity(values, file)
            result[name.lowercase(Locale.getDefault())] = uuid
        }
        return result
    }

    private fun loadNations(plugin: Townia, dataDir: File, residentsByName: Map<String, UUID>, save: Boolean): MutableMap<String, UUID> {
        val result = LinkedHashMap<String, UUID>()
        nationFiles.forEach { file ->
            val values = readValues(file)
            val name = values.first("name", "nation") ?: fileDisplayName(file)
            val uuid = values.uuid("uuid") ?: file.nameWithoutExtension.toUuidOrNull() ?: stableUuid("towny-nation", name)
            // Only player keys: "capital" holds a town name, which must never be
            // looked up as a player. repairNationCapitals() sets the leader from
            // the capital's mayor afterwards.
            val leaderValue = values.first("king", "kingname", "leader", "leadername", "mayor", "mayorname") ?: ""
            val leaderUuid = resolveGovernmentPlayerUuid(plugin, leaderValue, residentsByName, "nation $name leader")
                ?: stableUuid("towny-resident", "$name-leader")
            val rawSpawn = values.first("spawn", "nationspawn", "nation_spawn")
            val spawn = parseLocation(rawSpawn)
            if (spawn == null) {
                plugin.logger.warning(
                    if (rawSpawn == null) "Towny flatfile: nation $name has no nationSpawn in ${file.name}."
                    else "Towny flatfile: nation $name has an unreadable nationSpawn '$rawSpawn' in ${file.name}."
                )
            }
            val nation = Nation(
                uuid,
                name,
                values.uuid("capital", "capitaltown") ?: stableUuid("towny-town", values.first("capitalname", "capital_town_name") ?: name),
                leaderUuid,
                resolveFlatfileNationBalance(plugin, name, uuid, values.double("balance", "bank", "account")),
                values.first("board", "nationboard", "nation_board") ?: "",
                values.double("taxes", "tax"),
                spawn?.world,
                spawn?.x ?: 0.0,
                spawn?.y ?: 0.0,
                spawn?.z ?: 0.0,
                spawn?.yaw ?: 0f,
                spawn?.pitch ?: 0f
            )
            nation.isNeutral = values.boolean("neutral", "isneutral", "is_neutral")
            nation.tag = values.first("tag")
            nation.isPublic = values.boolean("ispublic", "is_public", "public")
            nation.isOpen = values.boolean("isopen", "is_open", "open")
            values.first("spawncost", "spawn_cost")?.replace(",", "")?.toDoubleOrNull()?.let { nation.spawnCost = it }
            if (save) {
                plugin.databaseManager.saveNation(nation)
                plugin.nationManager.cacheNation(nation)
            }
            result[name.lowercase(Locale.getDefault())] = uuid
        }
        return result
    }

    private fun repairNationCapitals(
        plugin: Townia,
        dataDir: File,
        townsByName: Map<String, UUID>,
        nationsByName: Map<String, UUID>
    ) {
        nationFiles.forEach { file ->
            val values = readValues(file)
            val nationName = values.first("name", "nation") ?: fileDisplayName(file)
            val nationUuid = values.uuid("uuid") ?: file.nameWithoutExtension.toUuidOrNull()
                ?: nationsByName[nationName.lowercase(Locale.getDefault())]
                ?: return@forEach
            val capitalName = values.first("capitalname", "capital_town_name")
            val capitalUuid = resolveGovernmentUuid(values.first("capital", "capitaltown", "capital_town"), townsByName)
                ?: resolveGovernmentUuid(capitalName, townsByName)
                ?: capitalName?.let { plugin.townManager.getTownByName(it).orElse(null)?.id }
                ?: return@forEach
            val nationOpt = plugin.nationManager.getNation(nationUuid)
            val townOpt = plugin.townManager.getTown(capitalUuid)
            if (nationOpt.isEmpty || townOpt.isEmpty) return@forEach
            val nation = nationOpt.get()
            val town = townOpt.get()
            nation.capitalTownUuid = town.id
            nation.leaderUuid = town.mayorUuid
            plugin.databaseManager.saveNation(nation)
            plugin.nationManager.cacheNation(nation)
        }
    }

    private fun loadTownOutlaws(
        plugin: Townia,
        dataDir: File,
        townsByName: Map<String, UUID>,
        residentsByName: Map<String, UUID>
    ): Int {
        var count = 0
        townFiles.forEach { file ->
            val values = readValues(file)
            val townName = values.first("name", "town") ?: fileDisplayName(file)
            val townUuid = values.uuid("uuid") ?: file.nameWithoutExtension.toUuidOrNull()
                ?: townsByName[townName.lowercase(Locale.getDefault())]
                ?: return@forEach
            val town = plugin.townManager.getTown(townUuid).orElse(null) ?: return@forEach
            val outlawList = values.list("outlaws", "outlaw")
            outlawList.forEach { raw ->
                val outlawUuid = resolveResidentUuid(raw, residentsByName) ?: return@forEach
                runCatching {
                    plugin.databaseManager.addTownOutlaw(town.id!!, outlawUuid)
                    town.outlaws.add(outlawUuid)
                    count++
                }
            }
        }
        return count
    }

    private fun loadNationRelations(
        plugin: Townia,
        dataDir: File,
        nationsByName: Map<String, UUID>
    ): Int {
        var count = 0
        nationFiles.forEach { file ->
            val values = readValues(file)
            val nationName = values.first("name", "nation") ?: fileDisplayName(file)
            val nationUuid = values.uuid("uuid") ?: file.nameWithoutExtension.toUuidOrNull()
                ?: nationsByName[nationName.lowercase(Locale.getDefault())]
                ?: return@forEach
            val nation = plugin.nationManager.getNation(nationUuid).orElse(null) ?: return@forEach
            val alliesList = values.list("allies", "ally")
            alliesList.forEach { raw ->
                val allyUuid = resolveGovernmentUuid(raw, nationsByName) ?: return@forEach
                runCatching {
                    nation.allies.add(allyUuid)
                    plugin.databaseManager.addNationRelation(nation.id!!, allyUuid, "ALLY")
                    count++
                }
            }
            val enemiesList = values.list("enemies", "enemy")
            enemiesList.forEach { raw ->
                val enemyUuid = resolveGovernmentUuid(raw, nationsByName) ?: return@forEach
                runCatching {
                    nation.enemies.add(enemyUuid)
                    plugin.databaseManager.addNationRelation(nation.id!!, enemyUuid, "ENEMY")
                    count++
                }
            }
            plugin.nationManager.cacheNation(nation)
        }
        return count
    }

    private fun loadTowns(
        plugin: Townia,
        dataDir: File,
        residentsByName: Map<String, UUID>,
        nationsByName: Map<String, UUID>,
        save: Boolean
    ): MutableMap<String, UUID> {
        val result = LinkedHashMap<String, UUID>()
        townFiles.forEach { file ->
            val values = readValues(file)
            val name = values.first("name", "town") ?: fileDisplayName(file)
            val uuid = values.uuid("uuid") ?: file.nameWithoutExtension.toUuidOrNull() ?: stableUuid("towny-town", name)
            val mayorValue = values.first("mayor", "mayorname", "king", "kingname") ?: ""
            val mayorUuid = resolveGovernmentPlayerUuid(plugin, mayorValue, residentsByName, "town $name mayor")
                ?: stableUuid("towny-resident", "$name-mayor")
            val nationUuid = resolveGovernmentUuid(values.first("nation", "nationuuid", "nation_uuid"), nationsByName)
                ?: resolveGovernmentUuid(values.first("nationname", "nation_name"), nationsByName)
            val spawn = parseLocation(values.first("spawn", "spawnloc", "spawn_location"))
            val homeBlock = parseChunk(values.first("homeblock", "home_block"))
            val town = Town(
                uuid,
                name,
                mayorUuid,
                nationUuid,
                resolveFlatfileTownBalance(plugin, name, uuid, values.double("balance", "bank", "account")),
                values.int("maxblocks", "max_town_blocks", "claimlimit", "claim_limit"),
                values.int("bonusblocks", "bonus_blocks", "bonus") + values.int("purchasedblocks", "purchased_blocks", "purchased_bonus_blocks"),
                values.boolean("public", "is_public"),
                values.long("registered", "registeredat", "createdat"),
                values.first("board", "townboard", "town_board") ?: "",
                values.double("taxes", "tax"),
                values.double("plotprice", "plot_price"),
                townFlag(values, "pvp"),
                townFlag(values, "mobs"),
                townFlag(values, "explosion", "explosions"),
                townFlag(values, "fire"),
                spawn?.world,
                spawn?.x ?: 0.0,
                spawn?.y ?: 0.0,
                spawn?.z ?: 0.0,
                spawn?.yaw ?: 0f,
                spawn?.pitch ?: 0f
            )
            town.isOpen = values.boolean("open", "is_open")
            town.tag = values.first("tag")
            town.isTaxPercent = values.boolean("taxpercent", "tax_percent")
            town.plotTax = values.double("plottax", "plot_tax")
            town.shopPrice = values.double("commercialplotprice", "commercial_plot_price", "shopprice")
            town.shopTax = values.double("commercialplottax", "commercial_plot_tax", "shoptax")
            town.embassyPrice = values.double("embassyplotprice", "embassy_plot_price", "embassyprice")
            town.embassyTax = values.double("embassyplottax", "embassy_plot_tax", "embassytax")
            // Towny charged visitors min(the town's spawn cost, the server's price); most old towns hold 0 = free.
            values.first("spawncost", "spawn_cost")?.replace(",", "")?.toDoubleOrNull()?.let { town.spawnCost = it }
            applyTownProtection(values, town)
            if (homeBlock != null) town.setHomeBlock(homeBlock.world, homeBlock.x, homeBlock.z)

            // What Townia already holds for this town and Towny's file cannot give back.
            val existing = if (save) plugin.townManager.getTown(uuid).orElse(null) else null
            if (existing != null) {
                town.outlaws.addAll(existing.outlaws)
                town.jailCells.addAll(existing.jailCells)
                town.outposts.addAll(existing.outposts)
                if (existing.hasJail() && !town.hasJail()) {
                    town.setJail(existing.jailWorld, existing.jailX, existing.jailY, existing.jailZ, existing.jailYaw, existing.jailPitch)
                }
                // Money moved since the first migration lives only in Townia; a single-town
                // re-run must never roll the bank back to the old Towny figure.
                if (selection != null) town.balance = existing.balance
                town.isAllowInvisibility = existing.isAllowInvisibility
                town.isAllowSit = existing.isAllowSit
                town.isAllowPetPickup = existing.isAllowPetPickup
                town.isAllowPassenger = existing.isAllowPassenger
            }
            if (save && selection != null && town.nationUuid != null && plugin.nationManager.getNation(town.nationUuid).isEmpty) {
                // A single-town run does not create nations; never point at one Townia does not have.
                plugin.logger.warning("Towny flatfile: nation of town $name is not in Townia; keeping ${existing?.nationUuid ?: "no nation"}.")
                town.nationUuid = existing?.nationUuid
            }
            // Outposts and jail cells reference the town row, so it has to exist before they are written.
            if (save) plugin.databaseManager.saveTown(town)

            val outpostLine = values.first("outpostspawns", "outpost_spawns")
            if (!outpostLine.isNullOrBlank()) {
                val spawns = outpostLine.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                spawns.forEachIndexed { index, rawSpawn ->
                    val spawnParts = rawSpawn.split(",").map { it.trim() }
                    if (spawnParts.size >= 6) {
                        val w = spawnParts[0]
                        val x = spawnParts[1].toDoubleOrNull() ?: return@forEachIndexed
                        val y = spawnParts[2].toDoubleOrNull() ?: return@forEachIndexed
                        val z = spawnParts[3].toDoubleOrNull() ?: return@forEachIndexed
                        val pitch = spawnParts[4].toFloatOrNull() ?: 0f
                        val yaw = spawnParts[5].toFloatOrNull() ?: 0f
                        val outpost = TowniaOutpost(0, w, x, y, z, yaw, pitch, false, "outpost-${index + 1}")
                        // A re-run must not add the same outposts again.
                        if (save && existing?.outposts.isNullOrEmpty()) {
                            runCatching {
                                plugin.databaseManager.saveTownOutpost(town.id!!, outpost)
                                town.outposts.add(outpost)
                            }
                        }
                    }
                }
            }

            // Towny 0.96 kept jail spawns in the town file (`jailspawns=world,x,y,z,pitch,yaw;...`).
            val jailSpawns = values.first("jailspawns", "jail_spawns")
                ?.split(';')?.mapNotNull { parseLocation(it) }.orEmpty()
            if (save && jailSpawns.isNotEmpty() && existing?.jailCells.isNullOrEmpty()) {
                jailSpawns.forEachIndexed { index, jail ->
                    val cell = TowniaJailCell(0, "jail-${index + 1}", jail.world, jail.x, jail.y, jail.z, jail.yaw, jail.pitch)
                    runCatching {
                        plugin.databaseManager.saveTownJailCell(town.id!!, cell)
                        town.jailCells.add(cell)
                    }
                    if (!town.hasJail()) town.setJail(jail.world, jail.x, jail.y, jail.z, jail.yaw, jail.pitch)
                }
            }

            if (save) {
                plugin.databaseManager.saveTown(town)
                plugin.townManager.cacheTown(town)
            }
            result[name.lowercase(Locale.getDefault())] = uuid
        }
        return result
    }

    private fun loadResidents(
        plugin: Townia,
        dataDir: File,
        residentsByName: Map<String, UUID>,
        townsByName: Map<String, UUID>,
        townMemberships: TownMemberships,
        missingOnly: Boolean = false
    ): Int {
        var count = 0
        staleTownReferences = 0
        staleTownLists = 0
        val migratedNames = HashSet<String>()
        val migratedUuids = HashSet<UUID>()
        val friendHolders = ArrayList<TowniaPlayer>()
        val dir = File(dataDir, "residents")
        dir.listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
            try {
            val values = readValues(file)
            val (name, fileUuid) = residentIdentity(values, file)
            val uuid = residentsByName[name.lowercase(Locale.getDefault())] ?: fileUuid
            // "Only what is missing" leaves a resident Townia already has alone: what they did here
            // since (left a town, handed the town over) is newer than the Towny file.
            if (missingOnly && plugin.residentManager.getResident(uuid).isPresent) {
                migratedNames.add(name.lowercase(Locale.getDefault()))
                migratedUuids.add(uuid)
                return@forEach
            }
            val residentKey = name.lowercase(Locale.getDefault())
            val uuidKey = uuid.toString().lowercase(Locale.getDefault())
            val membership = resolveMembership(values, residentKey, uuidKey, townMemberships, townsByName)
            val fileTown = membership.fileTown
            if (membership.staleFile) staleTownReferences++
            if (membership.staleList) staleTownLists++
            var townUuid = membership.town
            if (selection != null && townUuid != null && plugin.townManager.getTown(townUuid).isEmpty) {
                plugin.logger.warning("Towny flatfile: the town of $name is not in Townia; migrated without a town.")
                townUuid = null
            }
            // Ranks written in the resident file belong to the town that file names.
            val fileRanksApply = townUuid != null && (fileTown == null || fileTown == townUuid)
            if (selection?.wantsResident(name, uuid, townUuid) == false) return@forEach
            val rank = resolveRank(values, residentKey, uuidKey, townMemberships, townUuid, fileRanksApply)
            val resident = TowniaPlayer(
                uuid,
                name,
                townUuid,
                rank,
                values.long("lastonline", "last_online", "lastonlineat"),
                null,
                values.list("friends").mapNotNull { friend ->
                    friend.toUuidOrNull()?.let { towniaUuidByTownyUuid[it] ?: it } ?: residentsByName[friend.lowercase(Locale.getDefault())]
                }.map { it.toString() }.toMutableList()
            )
            resident.registeredAt = values.long("registered", "registeredat", "createdat")
            values.first("about")?.takeIf { it.isNotBlank() }?.let { resident.about = it.take(255) }
            applyResidentProtection(values, resident)
            // Every other rank (helper, sheriff, a server's own ones) only carries permission nodes.
            if (fileRanksApply) {
                extraTownRanks(values).forEach(resident.townRanks::add)
                resident.nationRanks.addAll(TowniaPlayer.parseRanks(values.list("nationranks", "nation_ranks").joinToString(",")))
            }

            val title = values.first("title")
            val surname = values.first("surname")
            if (!title.isNullOrBlank() || !surname.isNullOrBlank()) {
                val townOpt = townUuid?.let { plugin.townManager.getTown(it).orElse(null) }
                if (townOpt?.nationUuid != null) {
                    val nOpt = plugin.nationManager.getNation(townOpt.nationUuid).orElse(null)
                    if (nOpt != null) {
                        if (!title.isNullOrBlank()) nOpt.setTitle(uuid, title)
                        if (!surname.isNullOrBlank()) nOpt.setSurname(uuid, surname)
                        plugin.databaseManager.saveNationTitles(nOpt)
                    }
                }
            }

            plugin.databaseManager.saveResident(resident)
            plugin.residentManager.cacheResident(resident)
            if (!resident.friends.isNullOrEmpty()) friendHolders.add(resident)
            migratedNames.add(residentKey)
            migratedUuids.add(uuid)
            count++
            } catch (e: Exception) {
                // One bad record must not abort the whole migration (and leave every later stage unrun).
                plugin.logger.log(Level.WARNING, "Towny flatfile: skipped resident ${file.name}: ${e.message}")
            }
        }

        // Members a town file lists but who have no resident file would otherwise
        // silently drop out of their town.
        var fromTownLists = 0
        townMemberships.townByResident.forEach { (key, townUuid) ->
            if (key in migratedNames || key.toUuidOrNull() != null) return@forEach
            val displayName = townMemberships.displayNames[key] ?: return@forEach
            if (selection?.settingsOnly == true) return@forEach
            try {
                val uuid = residentsByName[key] ?: MigrationSupport.resolveUuidByName(displayName, knownUuids)
                // Never over a record this run has just written from the resident's own file.
                if (uuid in migratedUuids) return@forEach
                if (selection?.wantsResident(displayName, uuid, townUuid) == false) return@forEach
                val resident = TowniaPlayer(
                    uuid,
                    displayName,
                    townUuid,
                    townMemberships.rankByResident[key] ?: TownRank.RESIDENT,
                    0L,
                    null
                )
                resident.registeredAt = System.currentTimeMillis()
                plugin.databaseManager.saveResident(resident)
                plugin.residentManager.cacheResident(resident)
                fromTownLists++
                count++
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Towny flatfile: skipped town member $displayName: ${e.message}")
            }
        }
        if (fromTownLists > 0) {
            plugin.logger.info("Towny flatfile: created $fromTownLists residents listed by a town but without a resident file.")
        }
        if (staleTownLists > 0) {
            plugin.logger.info(
                "Towny flatfile: $staleTownLists resident(s) were still on the member list of a town file from an older Towny; " +
                    "their own, newer file decided where they belong."
            )
        }
        if (staleTownReferences > 0) {
            plugin.logger.info(
                "Towny flatfile: $staleTownReferences resident file(s) named a town that does not list them; " +
                    "those residents were left townless, as they were in Towny."
            )
        }

        // saveResident does not write friends, and the friend table has a foreign key to residents,
        // so persist the links only now that every resident exists.
        var friendLinks = 0
        friendHolders.forEach { holder ->
            val ownerUuid = holder.uuid ?: return@forEach
            val valid = ArrayList<String?>()
            holder.friends?.filterNotNull()?.distinct()?.forEach { friend ->
                val friendUuid = friend.toUuidOrNull() ?: return@forEach
                if (friendUuid == ownerUuid || plugin.residentManager.getResident(friendUuid).isEmpty) return@forEach
                try {
                    plugin.databaseManager.addFriend(ownerUuid, friendUuid)
                    valid.add(friend)
                    friendLinks++
                } catch (e: Exception) {
                    plugin.logger.log(Level.WARNING, "Towny flatfile: skipped friend $friend of ${holder.name}: ${e.message}")
                }
            }
            holder.friends = valid
        }
        if (friendLinks > 0) plugin.logger.info("Towny flatfile: migrated $friendLinks friend links.")
        return count
    }

    private fun loadTownMemberships(dataDir: File, townsByName: Map<String, UUID>): TownMemberships {
        val townByResident = LinkedHashMap<String, UUID>()
        val rankByResident = LinkedHashMap<String, TownRank>()
        val displayNames = LinkedHashMap<String, String>()
        val townsWithMemberList = HashSet<UUID>()
        val mayorTownByResident = HashMap<String, UUID>()
        val rankTownByResident = HashMap<String, UUID>()
        fun grant(key: String, townUuid: UUID, rank: TownRank) {
            if (maxRank(rankByResident[key], rank) == rankByResident[key]) return
            rankByResident[key] = rank
            rankTownByResident[key] = townUuid
        }
        townFiles.forEach { file ->
            val values = readValues(file)
            val townName = values.first("name", "town") ?: fileDisplayName(file)
            val townUuid = townsByName[townName.lowercase(Locale.getDefault())] ?: return@forEach
            if (values.has("residents", "resident", "members")) townsWithMemberList.add(townUuid)
            val mayor = values.first("mayor", "mayorname", "king", "kingname")
            for (keyValue in listOfNotNull(mayor, values.first("mayorname", "kingname")).distinct()) {
                val key = keyValue.lowercase(Locale.getDefault())
                townByResident[key] = townUuid
                mayorTownByResident[key] = townUuid
                rankByResident[key] = TownRank.MAYOR
                rankTownByResident[key] = townUuid
                displayNames.putIfAbsent(key, keyValue)
            }
            // "trusted" residents are outsiders the town trusts, not members.
            values.list("residents", "resident", "members").forEach {
                townByResident.putIfAbsent(it.lowercase(Locale.getDefault()), townUuid)
                displayNames.putIfAbsent(it.lowercase(Locale.getDefault()), it)
            }
            values.list("assistants", "assistant").forEach {
                val key = it.lowercase(Locale.getDefault())
                townByResident[key] = townUuid
                grant(key, townUuid, TownRank.ASSISTANT)
            }
            values.list("comayors", "co_mayors", "co-mayors", "submayors", "sub_mayors").forEach {
                val key = it.lowercase(Locale.getDefault())
                townByResident[key] = townUuid
                grant(key, townUuid, TownRank.SUB_MAYOR)
            }
        }
        return TownMemberships(townByResident, rankByResident, displayNames, townsWithMemberList, mayorTownByResident, rankTownByResident)
    }

    private fun loadTownBlocks(
        plugin: Townia,
        dataDir: File,
        townsByName: Map<String, UUID>,
        residentsByName: Map<String, UUID>,
        missingOnly: Boolean,
        seenChunks: MutableSet<ChunkKey>
    ): Int {
        var count = 0
        count += loadTownBlocksFromDirectory(plugin, File(dataDir, "townblocks"), townsByName, residentsByName, missingOnly, seenChunks)
        townFiles.forEach { file ->
            val values = readValues(file)
            val townName = values.first("name", "town") ?: fileDisplayName(file)
            val townUuid = townsByName[townName.lowercase(Locale.getDefault())] ?: return@forEach
            values.list("townblocks", "town_blocks", "plots").mapNotNull { parseChunk(it) }.forEach { chunk ->
                seenChunks.add(ChunkKey(chunk.world, chunk.x, chunk.z))
                // The townblocks folder was loaded first and carries the owner, type, price and
                // permissions; the town file only lists coordinates, so never overwrite a plot.
                if (plugin.plotManager.isClaimed(chunk.world, chunk.x, chunk.z)) return@forEach
                if (selection?.wantsPlot(townUuid, null) == false) return@forEach
                savePlot(plugin, chunk.world, chunk.x, chunk.z, townUuid, null, PlotType.DEFAULT, false, 0.0, null)
                count++
            }
        }
        return count
    }

    private fun loadTownBlocksFromDirectory(
        plugin: Townia,
        dir: File,
        townsByName: Map<String, UUID>,
        residentsByName: Map<String, UUID>,
        missingOnly: Boolean,
        seenChunks: MutableSet<ChunkKey>
    ): Int {
        if (!dir.isDirectory) return 0
        var count = 0
        lostPlotOwners = 0
        dir.walkTopDown().filter { isTownyDataFile(it) }.forEach { file ->
            try {
            val values = readValues(file)
            val townName = values.first("town") ?: return@forEach
            val chunk = parseTownBlockChunk(values, file)
                ?: return@forEach
            // Recorded before the town is resolved: prune must never delete a chunk Towny still claims.
            seenChunks.add(ChunkKey(chunk.world, chunk.x, chunk.z))
            if (missingOnly && plugin.plotManager.isClaimed(chunk.world, chunk.x, chunk.z)) return@forEach
            val townUuid = resolveGovernmentUuid(townName, townsByName) ?: return@forEach
            if (plugin.townManager.getTown(townUuid).isEmpty) return@forEach
            val ownerName = values.first("resident", "residentuuid", "resident_uuid", "owner", "owneruuid", "owner_uuid")
            // residentsByName only knows residents that have a file; a member the town merely lists
            // was created from that list, so look the name up among Townia's residents as well.
            // Without this the plot silently became town land again.
            val ownerUuid = resolveResidentUuid(ownerName, residentsByName)
                ?: ownerName?.takeIf { it.isNotBlank() }?.let { plugin.residentManager.getResidentByName(it).orElse(null)?.uuid }
            if (ownerUuid == null && !ownerName.isNullOrBlank()) lostPlotOwners++
            if (selection?.wantsPlot(townUuid, ownerUuid) == false) return@forEach
            val type = parsePlotType(values.first("type"))
            // Towny writes price=-1.0 for a plot that is not for sale; 0 is a free plot.
            val rawPrice = values.first("plotprice", "price")?.replace(",", "")?.toDoubleOrNull()
            val forSale = rawPrice != null && rawPrice >= 0.0
            val price = maxOf(0.0, rawPrice ?: 0.0)
            savePlot(plugin, chunk.world, chunk.x, chunk.z, townUuid, ownerUuid, type, forSale, price, values.first("name"), values)
            count++
            } catch (e: Exception) {
                // One bad record must not abort the whole migration (and leave every later stage unrun).
                plugin.logger.log(Level.WARNING, "Towny flatfile: skipped town block ${file.name}: ${e.message}")
            }
        }
        if (lostPlotOwners > 0) {
            plugin.logger.warning("Towny flatfile: $lostPlotOwners plot(s) name an owner Townia has no resident for; they were migrated without an owner.")
        }
        return count
    }

    private fun savePlot(
        plugin: Townia,
        world: String,
        x: Int,
        z: Int,
        townUuid: UUID,
        ownerUuid: UUID?,
        type: PlotType?,
        forSale: Boolean,
        price: Double,
        name: String?,
        values: FlatValues? = null
    ) {
        val plot = Plot(world, x, z, townUuid, ownerUuid, type, forSale, price, name, false, false, false, false)
        applyPlotProtection(values, plot)
        plugin.databaseManager.savePlot(plot)
        plugin.plotManager.cachePlot(plot)
    }

    private fun loadJails(plugin: Townia, dataDir: File): Int {
        val dir = File(dataDir, "jails")
        if (!dir.isDirectory) return 0
        var count = 0
        dir.listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
            val values = readValues(file)
            val jailBlock = parseChunk(values.first("townblock", "town_block")) ?: return@forEach
            val plot = plugin.plotManager.getPlot(jailBlock.world, jailBlock.x, jailBlock.z).orElse(null) ?: return@forEach
            val town = plugin.townManager.getTown(plot.townUuid).orElse(null) ?: return@forEach
            val spawns = values.first("spawns", "spawn")
                ?.split(';')
                ?.mapNotNull { parseLocation(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: return@forEach

            spawns.forEachIndexed { index, spawn ->
                val cell = TowniaJailCell(
                    0,
                    "jail-${file.nameWithoutExtension}-${index + 1}",
                    spawn.world,
                    spawn.x,
                    spawn.y,
                    spawn.z,
                    spawn.yaw,
                    spawn.pitch
                )
                plugin.databaseManager.saveTownJailCell(town.id!!, cell)
                town.jailCells.add(cell)
                if (!town.hasJail()) {
                    town.setJail(spawn.world, spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch)
                    plugin.databaseManager.saveTown(town)
                    plugin.townManager.cacheTown(town)
                }
                count++
            }
        }
        return count
    }

    private fun readValues(file: File): FlatValues {
        val text = readText(file)
        val values = LinkedHashMap<String, MutableList<String>>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val splitAt = listOf(line.indexOf('='), line.indexOf(':')).filter { it >= 0 }.minOrNull() ?: return@forEach
            val key = line.substring(0, splitAt).trim().lowercase(Locale.getDefault()).replace("-", "_")
            val value = line.substring(splitAt + 1).trim().trim('"')
            values.getOrPut(key) { ArrayList() }.add(value)
        }
        return FlatValues(values)
    }

    private fun readText(file: File): String {
        BulkRead.fileRead()
        val bytes = file.readBytes()
        return runCatching { String(bytes, StandardCharsets.UTF_8) }
            .getOrElse { String(bytes, Charset.forName("MS932")) }
    }

    private fun parsePlotType(value: String?): PlotType {
        // Towny 0.96 wrote the type's number; 7 (jail) is a normal plot here, jails are kept per town.
        when (value?.trim()) {
            "0", "5", "7" -> return PlotType.DEFAULT
            "1" -> return PlotType.SHOP
            "2" -> return PlotType.ARENA
            "3" -> return PlotType.EMBASSY
            "4" -> return PlotType.WILDS
            "6" -> return PlotType.INN
            "8" -> return PlotType.FARM
            "9" -> return PlotType.BANK
        }
        val name = value?.uppercase(Locale.getDefault()) ?: return PlotType.DEFAULT
        return runCatching {
            PlotType.valueOf(if (name == "COMMERCIAL" || name == "SHOP") "SHOP" else name)
        }.getOrDefault(PlotType.DEFAULT)
    }

    /** Parses Towny's Position format: `world,x,y,z,pitch,yaw` (pitch comes before yaw). */
    private fun parseLocation(value: String?): FlatLocation? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 4) return null
        return FlatLocation(
            parts[0],
            parts[1].toDoubleOrNull() ?: return null,
            parts[2].toDoubleOrNull() ?: return null,
            parts[3].toDoubleOrNull() ?: return null,
            yaw = parts.getOrNull(5)?.toFloatOrNull() ?: 0f,
            pitch = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
        )
    }

    private fun parseChunk(value: String?): FlatChunk? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(',', ';', ' ', ':').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        return FlatChunk(parts[0], parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
    }

    private fun parseTownBlockChunk(values: FlatValues, file: File): FlatChunk? {
        parseChunk(values.first("coord", "coords", "location"))?.let { return it }
        val explicitWorld = values.first("world")
        val explicitX = values.first("x")?.toIntOrNull()
        val explicitZ = values.first("z")?.toIntOrNull()
        if (!explicitWorld.isNullOrBlank() && explicitX != null && explicitZ != null) {
            return FlatChunk(explicitWorld, explicitX, explicitZ)
        }

        val world = file.parentFile?.name ?: return null
        val parts = file.nameWithoutExtension.split('_')
        if (parts.size < 2) return null
        val x = parts[0].toIntOrNull() ?: return null
        val z = parts[1].toIntOrNull() ?: return null
        return FlatChunk(world, x, z)
    }

    /**
     * The file name, used as the name when a Towny file has no `name=` line
     * (nation files, for one). Towny's backup zips store UTF-8 names without the
     * UTF-8 flag, so a plain `unzip` writes them as CP866 text: "式王国" becomes
     * "х╝ПчОЛхЫ╜". Undo that when the reverse decoding gives valid UTF-8.
     */
    private fun fileDisplayName(file: File): String {
        val raw = file.nameWithoutExtension
        if (raw.all { it.code < 0x80 }) return raw
        return runCatching {
            val bytes = raw.toByteArray(Charset.forName("IBM866"))
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            // Only a name made entirely of CP866-encodable characters can be such mojibake.
            if (String(bytes, Charset.forName("IBM866")) != raw) return raw
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrDefault(raw)
    }

    private fun stableUuid(prefix: String, value: String): UUID {
        return UUID.nameUUIDFromBytes("$prefix:${value.lowercase(Locale.getDefault())}".toByteArray(StandardCharsets.UTF_8))
    }

    private fun hasAnyRank(values: FlatValues, vararg ranks: String): Boolean {
        val found = values.list("townranks", "town_ranks", "ranks")
        return ranks.any { rank -> found.any { it.equals(rank, ignoreCase = true) } }
    }

    private fun maxRank(current: TownRank?, candidate: TownRank): TownRank {
        return if (current == null || candidate.level > current.level) candidate else current
    }

    /**
     * Mayor/leader names missing from the residents folder used to become a
     * synthetic UUID no player can ever log in with, so the town stayed
     * mayor-less forever. Resolve the name the same way resident files are
     * resolved instead; only a blank value falls back to a placeholder.
     */
    private fun resolveGovernmentPlayerUuid(
        plugin: Townia,
        value: String,
        residentsByName: Map<String, UUID>,
        role: String
    ): UUID? {
        if (value.isBlank()) return null
        resolveResidentUuid(value, residentsByName)?.let { return it }
        val uuid = MigrationSupport.resolveUuidByName(value, knownUuids)
        plugin.logger.warning("Towny flatfile: $role '$value' has no resident file; using UUID $uuid from the server's name lookup.")
        return uuid
    }

    private fun resolveResidentUuid(value: String?, residentsByName: Map<String, UUID>): UUID? {
        if (value.isNullOrBlank()) return null
        return value.toUuidOrNull()?.let { towniaUuidByTownyUuid[it] ?: it } ?: residentsByName[value.lowercase(Locale.getDefault())]
    }

    private fun resolveGovernmentUuid(value: String?, governmentsByName: Map<String, UUID>): UUID? {
        if (value.isNullOrBlank()) return null
        return value.toUuidOrNull() ?: governmentsByName[value.lowercase(Locale.getDefault())]
    }

    private fun isTownyDataFile(file: File): Boolean {
        return file.isFile && (
            file.extension.equals("txt", ignoreCase = true) ||
                file.extension.equals("data", ignoreCase = true)
            )
    }

    private fun applyResidentProtection(values: FlatValues, resident: TowniaPlayer) {
        val status = values.list("protectionstatus", "protection_status").map { it.lowercase(Locale.getDefault()) }.toSet()
        if (status.isEmpty()) return
        resident.defaultPermsFriend = permissionString(status, "resident")
        resident.defaultPermsResident = permissionString(status, "nation")
        resident.defaultPermsAlly = permissionString(status, "ally")
        resident.defaultPermsOutsider = permissionString(status, "outsider")
    }

    private fun applyPlotProtection(values: FlatValues?, plot: Plot) {
        val status = values?.list("permissions", "protectionstatus", "protection_status")
            ?.map { it.lowercase(Locale.getDefault()) }?.toSet() ?: return
        // Towny 0.96 marks a plot whose permissions were never set by hand with changed=false;
        // such a plot follows its town (or its owner's defaults), which is Townia's "unset".
        if (values.first("changed")?.equals("false", ignoreCase = true) == true) return
        if ("pvp" in status) plot.setPvp(true)
        if ("mobs" in status) plot.setMobs(true)
        if ("explosion" in status) plot.setExplosions(true)
        if ("fire" in status) plot.setFire(true)
        val hasFlags = status.any { it.endsWith("build") || it.endsWith("destroy") || it.endsWith("switch") || it.endsWith("itemuse") }
        if (!hasFlags) return
        if (plot.ownerUuid != null) {
            // Resident-owned plots use Towny's personal levels: "resident" = the owner's friends,
            // "nation" = the plot's town members, "ally" = nation members and allies.
            // Mapping "resident" to town members would open the plot to the whole town.
            plot.permsFriend = permissionString(status, "resident")
            plot.permsResident = permissionString(status, "nation")
            plot.permsNation = permissionString(status, "ally")
        } else {
            plot.permsResident = permissionString(status, "resident")
            plot.permsNation = permissionString(status, "nation")
        }
        plot.permsAlly = permissionString(status, "ally")
        plot.permsOutsider = permissionString(status, "outsider")
    }

    /** Towny 0.96 stores pvp/fire/explosion/mobs inside protectionStatus; newer files use their own key. */
    private fun townFlag(values: FlatValues, vararg keys: String): Boolean {
        if (values.boolean(*keys)) return true
        val status = values.list("protectionstatus", "protection_status")
        return keys.any { key -> status.any { it.equals(key, ignoreCase = true) } }
    }

    private fun applyTownProtection(values: FlatValues, town: Town) {
        val status = values.list("protectionstatus", "protection_status").map { it.lowercase(Locale.getDefault()) }.toSet()
        if (status.isEmpty()) return
        town.permsResident = permissionString(status, "resident")
        town.permsNation = permissionString(status, "nation")
        town.permsAlly = permissionString(status, "ally")
        town.permsOutsider = permissionString(status, "outsider")
    }

    private fun permissionString(status: Set<String>, group: String): String {
        val normalizedGroup = group.lowercase(Locale.getDefault())
        val sb = StringBuilder()
        if ("${normalizedGroup}build" in status) sb.append('B')
        if ("${normalizedGroup}destroy" in status) sb.append('D')
        if ("${normalizedGroup}switch" in status) sb.append('S')
        if ("${normalizedGroup}itemuse" in status || "${normalizedGroup}item_use" in status) sb.append('I')
        return sb.toString()
    }

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    private data class FlatLocation(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)
    private data class FlatChunk(val world: String, val x: Int, val z: Int)
    private data class TownMemberships(
        val townByResident: Map<String, UUID>,
        val rankByResident: Map<String, TownRank>,
        val displayNames: Map<String, String>,
        /** Towns whose file names its members. For these the list decides who belongs to the town. */
        val townsWithMemberList: Set<UUID> = emptySet(),
        /** The town whose file names this resident as its mayor. Every Towny rewrites that line at once. */
        val mayorTownByResident: Map<String, UUID> = emptyMap(),
        /** The town that [rankByResident] comes from: a rank only counts in that town. */
        val rankTownByResident: Map<String, UUID> = emptyMap()
    )

    /** Where a resident belongs and what the files said about it. */
    private class Membership(
        val town: UUID?,
        val fileTown: UUID?,
        val listedTown: UUID?,
        /** An old-format resident file named a town that does not list them. */
        val staleFile: Boolean,
        /** A town file still listed them although their own, newer file says otherwise. */
        val staleList: Boolean
    )

    /**
     * Decides a resident's town from Towny's files. The two places that can name it are not equally
     * fresh, and which one is depends on the Towny that last wrote the file:
     *  - Towny up to 0.96.1 kept the member list in the town file and loaded membership from it; a
     *    resident file could go on naming a town the player had left.
     *  - Later versions dropped that list and keep membership in the resident file only. They also
     *    began writing `uuid=` into resident files, so a resident file with that key is such a
     *    record, and a list still found in a town file that was never saved again is years old.
     * A data folder that an old server handed to a newer Towny holds both kinds side by side, so
     * the choice is made per resident file rather than once for the folder. Trusting the old
     * lists there is what put players back into towns they had left.
     * The mayor line of a town file is rewritten by every version the moment it changes, so it wins.
     */
    private fun resolveMembership(
        values: FlatValues,
        residentKey: String,
        uuidKey: String,
        memberships: TownMemberships,
        townsByName: Map<String, UUID>
    ): Membership {
        val listedTown = memberships.townByResident[residentKey] ?: memberships.townByResident[uuidKey]
        val rawFileTown = values.first("town", "townuuid", "town_uuid") ?: values.first("townname", "town_name")
        val fileTown = resolveGovernmentUuid(values.first("town", "townuuid", "town_uuid"), townsByName)
            ?: resolveGovernmentUuid(values.first("townname", "town_name"), townsByName)
        val mayorTown = memberships.mayorTownByResident[residentKey] ?: memberships.mayorTownByResident[uuidKey]
        // A newer file naming a town that cannot be found tells us nothing; fall back to the lists.
        val newerFile = values.has("uuid") && (fileTown != null || rawFileTown.isNullOrBlank())
        return when {
            mayorTown != null -> Membership(mayorTown, fileTown, listedTown, false, false)
            newerFile -> Membership(fileTown, fileTown, listedTown, false, listedTown != null && listedTown != fileTown)
            else -> {
                val staleFile = listedTown == null && fileTown != null && fileTown in memberships.townsWithMemberList
                Membership(listedTown ?: fileTown?.takeIf { !staleFile }, fileTown, listedTown, staleFile, false)
            }
        }
    }

    /** The built-in rank (mayor, sub-mayor, assistant) a resident holds in [townUuid]. */
    private fun resolveRank(
        values: FlatValues,
        residentKey: String,
        uuidKey: String,
        memberships: TownMemberships,
        townUuid: UUID?,
        fileRanksApply: Boolean
    ): TownRank {
        if (townUuid == null) return TownRank.RESIDENT
        // What a town file says about a resident only counts in that town.
        val key = listOf(residentKey, uuidKey).firstOrNull { memberships.rankTownByResident[it] == townUuid }
        val townSays = key?.let { memberships.rankByResident[it] }
        return when {
            // Towny resident files have no "mayor" flag: the mayor is named in the town file.
            (fileRanksApply && values.boolean("mayor")) || townSays == TownRank.MAYOR -> TownRank.MAYOR
            (fileRanksApply && hasAnyRank(values, "co-mayor", "co_mayor", "comayor", "submayor", "sub_mayor", "sub-mayor")) ||
                townSays == TownRank.SUB_MAYOR -> TownRank.SUB_MAYOR
            (fileRanksApply && hasAnyRank(values, "assistant")) || townSays == TownRank.ASSISTANT -> TownRank.ASSISTANT
            else -> TownRank.RESIDENT
        }
    }

    /** helper, sheriff and a server's own ranks from the resident file, without the built-in ones. */
    private fun extraTownRanks(values: FlatValues): List<String> =
        TowniaPlayer.parseRanks(values.list("townranks", "town_ranks", "ranks").joinToString(","))
            .map { it.replace('-', '_') }
            .filter { it != "assistant" && it !in SUB_MAYOR_NAMES && it != "mayor" }

    private class FlatValues(private val values: Map<String, List<String>>) {
        fun has(vararg keys: String): Boolean = keys.any { values.containsKey(it.lowercase(Locale.getDefault()).replace("-", "_")) }
        fun first(vararg keys: String): String? = keys.firstNotNullOfOrNull { values[it.lowercase(Locale.getDefault()).replace("-", "_")]?.firstOrNull() }?.takeIf { it.isNotBlank() }
        fun uuid(vararg keys: String): UUID? = first(*keys)?.toUuidOrNull()
        fun double(vararg keys: String): Double = first(*keys)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
        fun int(vararg keys: String): Int = first(*keys)?.toIntOrNull() ?: 0
        fun long(vararg keys: String): Long = first(*keys)?.toLongOrNull() ?: System.currentTimeMillis()
        fun boolean(vararg keys: String): Boolean = first(*keys)?.let { it.equals("true", true) || it.equals("yes", true) || it == "1" || it.equals("on", true) } ?: false
        fun list(vararg keys: String): List<String> = keys.flatMap { key ->
            values[key.lowercase(Locale.getDefault()).replace("-", "_")] ?: emptyList()
        }.flatMap { value ->
            value.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private fun sendValidation(plugin: Townia, sender: CommandSender) {
        val townResidents = plugin.residentManager.allResidents.count { it.townUuid != null }
        val unresolvedTownResidents =
            plugin.residentManager.allResidents.count { it.townUuid != null && plugin.townManager.getTown(it.townUuid).isEmpty }
        val ownedPlots = plugin.residentManager.allResidents.sumOf { resident ->
            resident.uuid?.let { uuid -> plugin.plotManager.countPlotsByOwner(uuid) } ?: 0
        }
        plugin.messageManager.sendMessage(
            sender,
            "admin.migration-validation",
            "town_residents",
            townResidents.toString(),
            "unresolved_town_residents",
            unresolvedTownResidents.toString(),
            "owned_plots",
            ownedPlots.toString()
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveFlatfileTownBalance(plugin: Townia, name: String, uuid: UUID, parsedBalance: Double): Double {
        if (parsedBalance > 0.0) return parsedBalance
        if (plugin.hasEconomy()) {
            val eco = plugin.economy!!
            val candidates = listOf("town-$name", "town-$uuid", name)
            for (cand in candidates) {
                runCatching {
                    if (eco.hasAccount(cand)) {
                        val b = eco.getBalance(cand)
                        if (b > 0.0) return b
                    }
                }
            }
        }
        return parsedBalance
    }

    @Suppress("DEPRECATION")
    private fun resolveFlatfileNationBalance(plugin: Townia, name: String, uuid: UUID, parsedBalance: Double): Double {
        if (parsedBalance > 0.0) return parsedBalance
        if (plugin.hasEconomy()) {
            val eco = plugin.economy!!
            val candidates = listOf("nation-$name", "nation-$uuid", name)
            for (cand in candidates) {
                runCatching {
                    if (eco.hasAccount(cand)) {
                        val b = eco.getBalance(cand)
                        if (b > 0.0) return b
                    }
                }
            }
        }
        return parsedBalance
    }
}