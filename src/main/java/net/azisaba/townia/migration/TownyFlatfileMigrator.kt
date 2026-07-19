package net.azisaba.townia.migration

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TowniaJailCell
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaPlayer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.logging.Level

object TownyFlatfileMigrator {
    private val allParts = setOf("towns", "nations", "residents", "townblocks", "jails")

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
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "resident UUID map", "count", "0")
                val residentsByName = loadResidentIds(dataDir)
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "resident UUID map", "count", residentsByName.size.toString())

                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "nations", "count", "0")
                val nationsByName = loadNations(plugin, dataDir, residentsByName, parts.contains("nations"))
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "nations", "count", if (parts.contains("nations")) nationsByName.size.toString() else "0")

                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "towns", "count", "0")
                val townsByName = loadTowns(plugin, dataDir, residentsByName, nationsByName, parts.contains("towns"))
                if (parts.contains("nations")) repairNationCapitals(plugin, dataDir, townsByName, nationsByName)
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "towns", "count", if (parts.contains("towns")) townsByName.size.toString() else "0")

                val townMemberships = loadTownMemberships(dataDir, townsByName)
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "residents", "count", "0")
                val residents = if (parts.contains("residents")) loadResidents(plugin, dataDir, residentsByName, townsByName, townMemberships) else 0
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "residents", "count", residents.toString())

                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "townblocks", "count", "0")
                val plots = if (parts.contains("townblocks")) loadTownBlocks(plugin, dataDir, townsByName, residentsByName) else 0
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "townblocks", "count", plots.toString())

                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "jails", "count", "0")
                val jails = if (parts.contains("jails")) loadJails(plugin, dataDir) else 0
                plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", "jails", "count", jails.toString())
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
                "data" -> result.addAll(allParts)
            }
        }
        return if (result.isEmpty()) allParts else result
    }

    private fun findTownyDataDir(plugin: Townia): File? {
        val pluginsDir = plugin.dataFolder.parentFile ?: return null
        val townyDir = File(pluginsDir, "Towny")
        return listOf(
            File(townyDir, "data"),
            townyDir
        ).firstOrNull { File(it, "towns").isDirectory || File(it, "residents").isDirectory }
    }

    private fun loadResidentIds(dataDir: File): MutableMap<String, UUID> {
        val result = LinkedHashMap<String, UUID>()
        val dir = File(dataDir, "residents")
        dir.listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
            val values = readValues(file)
            val name = values.first("name", "resident") ?: file.nameWithoutExtension
            val uuid = values.uuid("uuid", "player_uuid") ?: Bukkit.getOfflinePlayer(name).uniqueId
            result[name.lowercase(Locale.getDefault())] = uuid
        }
        return result
    }

    private fun loadNations(plugin: Townia, dataDir: File, residentsByName: Map<String, UUID>, save: Boolean): MutableMap<String, UUID> {
        val result = LinkedHashMap<String, UUID>()
        val dir = File(dataDir, "nations")
        dir.listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
            val values = readValues(file)
            val name = values.first("name", "nation") ?: file.nameWithoutExtension
            val uuid = values.uuid("uuid") ?: file.nameWithoutExtension.toUuidOrNull() ?: stableUuid("towny-nation", name)
            val leaderValue = values.first("king", "kingname", "leader", "leadername", "mayor", "mayorname", "capital", "capitalname") ?: ""
            val leaderUuid = resolveResidentUuid(leaderValue, residentsByName) ?: stableUuid("towny-resident", leaderValue.ifBlank { "$name-leader" })
            val spawn = parseLocation(values.first("spawn", "nationspawn", "nation_spawn"))
            val nation = Nation(
                uuid,
                name,
                values.uuid("capital", "capitaltown") ?: stableUuid("towny-town", values.first("capitalname", "capital_town_name") ?: name),
                leaderUuid,
                values.double("balance", "bank", "account"),
                values.first("board", "nationboard", "nation_board") ?: "",
                values.double("taxes", "tax"),
                spawn?.world,
                spawn?.x ?: 0.0,
                spawn?.y ?: 0.0,
                spawn?.z ?: 0.0,
                spawn?.yaw ?: 0f,
                spawn?.pitch ?: 0f
            )
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
        val dir = File(dataDir, "nations")
        dir.listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
            val values = readValues(file)
            val nationName = values.first("name", "nation") ?: file.nameWithoutExtension
            val nationUuid = values.uuid("uuid") ?: file.nameWithoutExtension.toUuidOrNull()
                ?: nationsByName[nationName.lowercase(Locale.getDefault())]
                ?: return@forEach
            val capitalUuid = resolveGovernmentUuid(values.first("capital", "capitaltown", "capital_town"), townsByName)
                ?: resolveGovernmentUuid(values.first("capitalname", "capital_town_name"), townsByName)
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

    private fun loadTowns(
        plugin: Townia,
        dataDir: File,
        residentsByName: Map<String, UUID>,
        nationsByName: Map<String, UUID>,
        save: Boolean
    ): MutableMap<String, UUID> {
        val result = LinkedHashMap<String, UUID>()
        val dir = File(dataDir, "towns")
        dir.listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
            val values = readValues(file)
            val name = values.first("name", "town") ?: file.nameWithoutExtension
            val uuid = values.uuid("uuid") ?: file.nameWithoutExtension.toUuidOrNull() ?: stableUuid("towny-town", name)
            val mayorValue = values.first("mayor", "mayorname", "king", "kingname") ?: ""
            val mayorUuid = resolveResidentUuid(mayorValue, residentsByName) ?: stableUuid("towny-resident", mayorValue.ifBlank { "$name-mayor" })
            val nationUuid = resolveGovernmentUuid(values.first("nation", "nationuuid", "nation_uuid"), nationsByName)
                ?: resolveGovernmentUuid(values.first("nationname", "nation_name"), nationsByName)
            val spawn = parseLocation(values.first("spawn", "spawnloc", "spawn_location"))
            val homeBlock = parseChunk(values.first("homeblock", "home_block"))
            val town = Town(
                uuid,
                name,
                mayorUuid,
                nationUuid,
                values.double("balance", "bank", "account"),
                values.int("maxblocks", "max_town_blocks", "claimlimit", "claim_limit"),
                values.int("bonusblocks", "bonus_blocks", "bonus"),
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
        val dir = File(dataDir, "residents")
        dir.listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
            val values = readValues(file)
            val name = values.first("name", "resident") ?: file.nameWithoutExtension
            val uuid = residentsByName[name.lowercase(Locale.getDefault())] ?: Bukkit.getOfflinePlayer(name).uniqueId
            val residentKey = name.lowercase(Locale.getDefault())
            val uuidKey = uuid.toString().lowercase(Locale.getDefault())
            val townUuid = resolveGovernmentUuid(values.first("town", "townuuid", "town_uuid"), townsByName)
                ?: resolveGovernmentUuid(values.first("townname", "town_name"), townsByName)
                ?: townMemberships.townByResident[residentKey]
                ?: townMemberships.townByResident[uuidKey]
            val rank = when {
                values.boolean("mayor") -> TownRank.MAYOR
                hasAnyRank(values, "co-mayor", "comayor", "submayor") ||
                    townMemberships.rankByResident[residentKey] == TownRank.CO_MAYOR ||
                    townMemberships.rankByResident[uuidKey] == TownRank.CO_MAYOR -> TownRank.CO_MAYOR
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
            plugin.databaseManager.saveResident(resident)
            plugin.residentManager.cacheResident(resident)
            count++
        }
        return count
    }

    private fun loadTownMemberships(dataDir: File, townsByName: Map<String, UUID>): TownMemberships {
        val townByResident = LinkedHashMap<String, UUID>()
        val rankByResident = LinkedHashMap<String, TownRank>()
        val dir = File(dataDir, "towns")
        dir.listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
            val values = readValues(file)
            val townName = values.first("name", "town") ?: file.nameWithoutExtension
            val townUuid = townsByName[townName.lowercase(Locale.getDefault())] ?: return@forEach
            val mayor = values.first("mayor", "mayorname", "king", "kingname")
            for (keyValue in listOfNotNull(mayor, values.first("mayorname", "kingname")).distinct()) {
                val key = keyValue.lowercase(Locale.getDefault())
                townByResident[key] = townUuid
                rankByResident[key] = TownRank.MAYOR
            }
            values.list("residents", "resident", "members", "trusted").forEach {
                townByResident.putIfAbsent(it.lowercase(Locale.getDefault()), townUuid)
            }
            values.list("assistants", "assistant").forEach {
                val key = it.lowercase(Locale.getDefault())
                townByResident[key] = townUuid
                rankByResident[key] = maxRank(rankByResident[key], TownRank.ASSISTANT)
            }
            values.list("comayors", "co_mayors", "co-mayors", "submayors", "sub_mayors").forEach {
                val key = it.lowercase(Locale.getDefault())
                townByResident[key] = townUuid
                rankByResident[key] = maxRank(rankByResident[key], TownRank.CO_MAYOR)
            }
        }
        return TownMemberships(townByResident, rankByResident)
    }

    private fun loadTownBlocks(plugin: Townia, dataDir: File, townsByName: Map<String, UUID>, residentsByName: Map<String, UUID>): Int {
        var count = 0
        count += loadTownBlocksFromDirectory(plugin, File(dataDir, "townblocks"), townsByName, residentsByName)
        val townDir = File(dataDir, "towns")
        townDir.listFiles { file -> isTownyDataFile(file) }?.forEach { file ->
            val values = readValues(file)
            val townName = values.first("name", "town") ?: file.nameWithoutExtension
            val townUuid = townsByName[townName.lowercase(Locale.getDefault())] ?: return@forEach
            values.list("townblocks", "town_blocks", "plots").mapNotNull { parseChunk(it) }.forEach { chunk ->
                savePlot(plugin, chunk.world, chunk.x, chunk.z, townUuid, null, PlotType.DEFAULT, false, 0.0, null)
                count++
            }
        }
        return count
    }

    private fun loadTownBlocksFromDirectory(plugin: Townia, dir: File, townsByName: Map<String, UUID>, residentsByName: Map<String, UUID>): Int {
        if (!dir.isDirectory) return 0
        var count = 0
        dir.walkTopDown().filter { isTownyDataFile(it) }.forEach { file ->
            val values = readValues(file)
            val townName = values.first("town") ?: return@forEach
            val townUuid = resolveGovernmentUuid(townName, townsByName) ?: return@forEach
            if (plugin.townManager.getTown(townUuid).isEmpty) return@forEach
            val chunk = parseTownBlockChunk(values, file)
                ?: return@forEach
            val ownerUuid = resolveResidentUuid(values.first("resident", "residentuuid", "resident_uuid", "owner", "owneruuid", "owner_uuid"), residentsByName)
            val type = parsePlotType(values.first("type"))
            val price = values.double("plotprice", "price")
            savePlot(plugin, chunk.world, chunk.x, chunk.z, townUuid, ownerUuid, type, price > 0.0, price, values.first("name"))
            count++
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

    private fun parseLocation(value: String?): FlatLocation? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 4) return null
        return FlatLocation(
            parts[0],
            parts[1].toDoubleOrNull() ?: return null,
            parts[2].toDoubleOrNull() ?: return null,
            parts[3].toDoubleOrNull() ?: return null,
            parts.getOrNull(4)?.toFloatOrNull() ?: 0f,
            parts.getOrNull(5)?.toFloatOrNull() ?: 0f
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
    private data class TownMemberships(val townByResident: Map<String, UUID>, val rankByResident: Map<String, TownRank>)

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
        val unresolvedTownResidents = plugin.residentManager.allResidents.count { it.townUuid != null && plugin.townManager.getTown(it.townUuid).isEmpty }
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
}
