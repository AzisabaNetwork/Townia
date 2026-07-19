package net.azisaba.townia.migration

import net.azisaba.townia.Townia
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.Town
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
    fun migrate(plugin: Townia, sender: CommandSender) {
        val dataDir = findTownyDataDir(plugin)
        if (dataDir == null) {
            plugin.messageManager.sendMessage(sender, "admin.migration_failed")
            plugin.logger.warning("Towny is not enabled and no Towny flatfile data directory was found.")
            return
        }

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            plugin.messageManager.sendMessage(sender, "admin.migration_start")
            try {
                val residentsByName = loadResidentIds(dataDir)
                val nationsByName = loadNations(plugin, dataDir, residentsByName)
                val townsByName = loadTowns(plugin, dataDir, residentsByName, nationsByName)
                val residents = loadResidents(plugin, dataDir, residentsByName, townsByName)
                val plots = loadTownBlocks(plugin, dataDir, townsByName)

                plugin.messageManager.sendMessage(
                    sender,
                    "admin.migration_success",
                    "towns",
                    townsByName.size.toString(),
                    "nations",
                    nationsByName.size.toString(),
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
        dir.listFiles { file -> file.isFile && file.extension.equals("txt", ignoreCase = true) }?.forEach { file ->
            val values = readValues(file)
            val name = values.first("name", "resident") ?: file.nameWithoutExtension
            val uuid = values.uuid("uuid", "player_uuid") ?: Bukkit.getOfflinePlayer(name).uniqueId
            result[name.lowercase(Locale.getDefault())] = uuid
        }
        return result
    }

    private fun loadNations(plugin: Townia, dataDir: File, residentsByName: Map<String, UUID>): MutableMap<String, UUID> {
        val result = LinkedHashMap<String, UUID>()
        val dir = File(dataDir, "nations")
        dir.listFiles { file -> file.isFile && file.extension.equals("txt", ignoreCase = true) }?.forEach { file ->
            val values = readValues(file)
            val name = values.first("name", "nation") ?: file.nameWithoutExtension
            val uuid = values.uuid("uuid") ?: stableUuid("towny-nation", name)
            val leaderName = values.first("king", "leader", "mayor", "capital") ?: ""
            val leaderUuid = residentsByName[leaderName.lowercase(Locale.getDefault())] ?: stableUuid("towny-resident", leaderName.ifBlank { "$name-leader" })
            val nation = Nation(
                uuid,
                name,
                stableUuid("towny-town", values.first("capital", "capitaltown") ?: name),
                leaderUuid,
                values.double("balance", "bank", "account"),
                values.first("board") ?: "",
                values.double("taxes", "tax")
            )
            plugin.databaseManager.saveNation(nation)
            plugin.nationManager.cacheNation(nation)
            result[name.lowercase(Locale.getDefault())] = uuid
        }
        return result
    }

    private fun loadTowns(
        plugin: Townia,
        dataDir: File,
        residentsByName: Map<String, UUID>,
        nationsByName: Map<String, UUID>
    ): MutableMap<String, UUID> {
        val result = LinkedHashMap<String, UUID>()
        val dir = File(dataDir, "towns")
        dir.listFiles { file -> file.isFile && file.extension.equals("txt", ignoreCase = true) }?.forEach { file ->
            val values = readValues(file)
            val name = values.first("name", "town") ?: file.nameWithoutExtension
            val uuid = values.uuid("uuid") ?: stableUuid("towny-town", name)
            val mayorName = values.first("mayor", "king") ?: ""
            val mayorUuid = residentsByName[mayorName.lowercase(Locale.getDefault())] ?: stableUuid("towny-resident", mayorName.ifBlank { "$name-mayor" })
            val nationUuid = values.first("nation")?.lowercase(Locale.getDefault())?.let { nationsByName[it] }
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
                values.first("board") ?: "",
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
            if (homeBlock != null) town.setHomeBlock(homeBlock.world, homeBlock.x, homeBlock.z)
            plugin.databaseManager.saveTown(town)
            plugin.townManager.cacheTown(town)
            result[name.lowercase(Locale.getDefault())] = uuid
        }
        return result
    }

    private fun loadResidents(
        plugin: Townia,
        dataDir: File,
        residentsByName: Map<String, UUID>,
        townsByName: Map<String, UUID>
    ): Int {
        var count = 0
        val dir = File(dataDir, "residents")
        dir.listFiles { file -> file.isFile && file.extension.equals("txt", ignoreCase = true) }?.forEach { file ->
            val values = readValues(file)
            val name = values.first("name", "resident") ?: file.nameWithoutExtension
            val uuid = residentsByName[name.lowercase(Locale.getDefault())] ?: Bukkit.getOfflinePlayer(name).uniqueId
            val townUuid = values.first("town")?.lowercase(Locale.getDefault())?.let { townsByName[it] }
            val rank = when {
                values.boolean("mayor") -> TownRank.MAYOR
                values.list("townranks", "town_ranks", "ranks").any { it.equals("assistant", ignoreCase = true) } -> TownRank.ASSISTANT
                else -> TownRank.RESIDENT
            }
            val resident = TowniaPlayer(
                uuid,
                name,
                townUuid,
                rank,
                values.long("lastonline", "last_online", "lastonlineat"),
                null,
                values.list("friends").mapNotNull { it.toUuidOrNull() }.map { it.toString() }.toMutableList()
            )
            plugin.databaseManager.saveResident(resident)
            plugin.residentManager.cacheResident(resident)
            count++
        }
        return count
    }

    private fun loadTownBlocks(plugin: Townia, dataDir: File, townsByName: Map<String, UUID>): Int {
        var count = 0
        count += loadTownBlocksFromDirectory(plugin, File(dataDir, "townblocks"), townsByName)
        val townDir = File(dataDir, "towns")
        townDir.listFiles { file -> file.isFile && file.extension.equals("txt", ignoreCase = true) }?.forEach { file ->
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

    private fun loadTownBlocksFromDirectory(plugin: Townia, dir: File, townsByName: Map<String, UUID>): Int {
        if (!dir.isDirectory) return 0
        var count = 0
        dir.walkTopDown().filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }.forEach { file ->
            val values = readValues(file)
            val townName = values.first("town") ?: return@forEach
            val townUuid = townsByName[townName.lowercase(Locale.getDefault())] ?: return@forEach
            val chunk = parseChunk(values.first("coord", "coords", "location") ?: file.nameWithoutExtension)
                ?: parseChunk("${values.first("world") ?: file.parentFile?.name},${values.int("x")},${values.int("z")}")
                ?: return@forEach
            val ownerUuid = values.first("resident", "owner")?.let { Bukkit.getOfflinePlayer(it).uniqueId }
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

    private fun stableUuid(prefix: String, value: String): UUID {
        return UUID.nameUUIDFromBytes("$prefix:${value.lowercase(Locale.getDefault())}".toByteArray(StandardCharsets.UTF_8))
    }

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    private data class FlatLocation(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)
    private data class FlatChunk(val world: String, val x: Int, val z: Int)

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
            value.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
