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

    /** Town / nation files to migrate, chosen once per run (see [selectGovernmentFiles]). */
    @Volatile
    private var townFiles: List<File> = emptyList()

    @Volatile
    private var nationFiles: List<File> = emptyList()

    fun migrate(plugin: Townia, sender: CommandSender, requestedParts: Set<String> = emptySet()) {
        val dataDir = findTownyDataDir(plugin)
        if (dataDir == null) {
            plugin.messageManager.sendMessage(sender, "admin.migration_failed")
            plugin.logger.warning("Towny is not enabled and no Towny flatfile data directory was found.")
            return
        }
        val parts = normalizeParts(requestedParts)

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            plugin.messageManager.sendMessage(sender, "admin.migration_start")
            plugin.messageManager.sendMessage(sender, "admin.migration-source", "source", "Towny flatfile: ${dataDir.path} (${parts.joinToString(",")})")
            try {
                knownUuids = MigrationSupport.knownUuidsByName(plugin)
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
                val residents = if (parts.contains("residents")) loadResidents(plugin, dataDir, residentsByName, townsByName, townMemberships) else 0
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
            nationFiles = pickGovernmentFiles(plugin, File(dataDir, "nations"), "nation") { false }
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
        nationFiles = pickGovernmentFiles(plugin, File(dataDir, "nations"), "nation") { gov ->
            gov.name.lowercase(Locale.getDefault()) in nationRefs ||
                (gov.uuid != null && gov.uuid.toString() in nationRefs)
        }
    }

    private fun pickGovernmentFiles(
        plugin: Townia,
        dir: File,
        kind: String,
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
            .sortedWith(compareByDescending<GovernmentFile> { it.live }.thenByDescending { it.file.lastModified() })
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
    private fun residentIdentity(values: FlatValues, file: File): Pair<String, UUID> {
        val fileUuid = file.nameWithoutExtension.toUuidOrNull()
        val uuid = values.uuid("uuid", "player_uuid") ?: fileUuid
        val explicitName = values.first("name", "resident")
        if (explicitName != null && explicitName.toUuidOrNull() == null) {
            return explicitName to (uuid ?: MigrationSupport.resolveUuidByName(explicitName, knownUuids))
        }
        if (uuid != null) {
            return (MigrationSupport.nameForUuid(uuid) ?: uuid.toString()) to uuid
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
                values.double("plottax", "plot_tax"),
                values.boolean("pvp"),
                values.boolean("mobs"),
                values.boolean("explosion", "explosions"),
                values.boolean("fire"),
                spawn?.world,
                spawn?.x ?: 0.0,
                spawn?.y ?: 0.0,
                spawn?.z ?: 0.0,
                spawn?.yaw ?: 0f,
                spawn?.pitch ?: 0f
            )
            town.isOpen = values.boolean("open", "is_open")
            applyTownProtection(values, town)
            if (homeBlock != null) town.setHomeBlock(homeBlock.world, homeBlock.x, homeBlock.z)

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
                        if (save) {
                            runCatching {
                                plugin.databaseManager.saveTownOutpost(town.id!!, outpost)
                                town.outposts.add(outpost)
                            }
                        }
                    }
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
        townMemberships: TownMemberships
    ): Int {
        var count = 0
        val migratedNames = HashSet<String>()
        val dir = File(dataDir, "residents")
        dir.listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
            try {
            val values = readValues(file)
            val (name, fileUuid) = residentIdentity(values, file)
            val uuid = residentsByName[name.lowercase(Locale.getDefault())] ?: fileUuid
            val residentKey = name.lowercase(Locale.getDefault())
            val uuidKey = uuid.toString().lowercase(Locale.getDefault())
            val townUuid = resolveGovernmentUuid(values.first("town", "townuuid", "town_uuid"), townsByName)
                ?: resolveGovernmentUuid(values.first("townname", "town_name"), townsByName)
                ?: townMemberships.townByResident[residentKey]
                ?: townMemberships.townByResident[uuidKey]
            val rank = when {
                // Towny resident files have no "mayor" flag: the mayor is named in the town file.
                values.boolean("mayor") ||
                    townMemberships.rankByResident[residentKey] == TownRank.MAYOR ||
                    townMemberships.rankByResident[uuidKey] == TownRank.MAYOR -> TownRank.MAYOR
                hasAnyRank(values, "co-mayor", "co_mayor", "comayor", "submayor", "sub_mayor", "sub-mayor") ||
                    townMemberships.rankByResident[residentKey] == TownRank.SUB_MAYOR ||
                    townMemberships.rankByResident[uuidKey] == TownRank.SUB_MAYOR -> TownRank.SUB_MAYOR
                hasAnyRank(values, "assistant") ||
                    townMemberships.rankByResident[residentKey] == TownRank.ASSISTANT ||
                    townMemberships.rankByResident[uuidKey] == TownRank.ASSISTANT -> TownRank.ASSISTANT
                else -> TownRank.RESIDENT
            }
            val resident = TowniaPlayer(
                uuid,
                name,
                townUuid,
                rank,
                values.long("lastonline", "last_online", "lastonlineat"),
                null,
                values.list("friends").mapNotNull { friend ->
                    friend.toUuidOrNull() ?: residentsByName[friend.lowercase(Locale.getDefault())]
                }.map { it.toString() }.toMutableList()
            )
            resident.registeredAt = values.long("registered", "registeredat", "createdat")
            applyResidentProtection(values, resident)

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
            migratedNames.add(residentKey)
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
            try {
                val uuid = residentsByName[key] ?: MigrationSupport.resolveUuidByName(displayName, knownUuids)
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
        return count
    }

    private fun loadTownMemberships(dataDir: File, townsByName: Map<String, UUID>): TownMemberships {
        val townByResident = LinkedHashMap<String, UUID>()
        val rankByResident = LinkedHashMap<String, TownRank>()
        val displayNames = LinkedHashMap<String, String>()
        townFiles.forEach { file ->
            val values = readValues(file)
            val townName = values.first("name", "town") ?: fileDisplayName(file)
            val townUuid = townsByName[townName.lowercase(Locale.getDefault())] ?: return@forEach
            val mayor = values.first("mayor", "mayorname", "king", "kingname")
            for (keyValue in listOfNotNull(mayor, values.first("mayorname", "kingname")).distinct()) {
                val key = keyValue.lowercase(Locale.getDefault())
                townByResident[key] = townUuid
                rankByResident[key] = TownRank.MAYOR
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
                rankByResident[key] = maxRank(rankByResident[key], TownRank.ASSISTANT)
            }
            values.list("comayors", "co_mayors", "co-mayors", "submayors", "sub_mayors").forEach {
                val key = it.lowercase(Locale.getDefault())
                townByResident[key] = townUuid
                rankByResident[key] = maxRank(rankByResident[key], TownRank.SUB_MAYOR)
            }
        }
        return TownMemberships(townByResident, rankByResident, displayNames)
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
            val ownerUuid = resolveResidentUuid(values.first("resident", "residentuuid", "resident_uuid", "owner", "owneruuid", "owner_uuid"), residentsByName)
            val type = parsePlotType(values.first("type"))
            val price = values.double("plotprice", "price")
            savePlot(plugin, chunk.world, chunk.x, chunk.z, townUuid, ownerUuid, type, price > 0.0, price, values.first("name"))
            count++
            } catch (e: Exception) {
                // One bad record must not abort the whole migration (and leave every later stage unrun).
                plugin.logger.log(Level.WARNING, "Towny flatfile: skipped town block ${file.name}: ${e.message}")
            }
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
        name: String?
    ) {
        val plot = Plot(world, x, z, townUuid, ownerUuid, type, forSale, price, name, false, false, false, false)
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
        val bytes = file.readBytes()
        return runCatching { String(bytes, StandardCharsets.UTF_8) }
            .getOrElse { String(bytes, Charset.forName("MS932")) }
    }

    private fun parsePlotType(value: String?): PlotType {
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
        return value.toUuidOrNull() ?: residentsByName[value.lowercase(Locale.getDefault())]
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
        val displayNames: Map<String, String>
    )

    private class FlatValues(private val values: Map<String, List<String>>) {
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