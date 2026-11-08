package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Past player names, read from EssentialsX's `usermap.csv` (`name,uuid` per
 * line, old names included). Townia itself only ever knows a player's current
 * name, so a resident migrated under a name the player has since changed
 * could not be linked back to that player without it.
 */
class NameHistory(private val plugin: Townia) {
    @Volatile
    private var uuidByName: Map<String, UUID> = emptyMap()

    @Volatile
    private var namesByUuid: Map<UUID, Set<String>> = emptyMap()

    init {
        reload()
    }

    fun reload() {
        val file = File(plugin.dataFolder.parentFile, "Essentials/usermap.csv")
        if (!file.isFile) {
            plugin.logger.info("Essentials usermap.csv not found; renamed players are only linked by their current name.")
            uuidByName = emptyMap()
            namesByUuid = emptyMap()
            return
        }
        val byName = HashMap<String, UUID>()
        val byUuid = HashMap<UUID, MutableSet<String>>()
        runCatching {
            file.useLines { lines ->
                lines.forEach { line ->
                    val comma = line.lastIndexOf(',')
                    if (comma <= 0) return@forEach
                    val name = line.substring(0, comma).trim().lowercase(Locale.ROOT)
                    val uuid = runCatching { UUID.fromString(line.substring(comma + 1).trim()) }.getOrNull() ?: return@forEach
                    if (name.isEmpty()) return@forEach
                    byName[name] = uuid
                    byUuid.getOrPut(uuid) { HashSet() }.add(name)
                }
            }
        }.onFailure { plugin.logger.warning("Could not read Essentials usermap.csv: ${it.message}") }
        uuidByName = byName
        namesByUuid = byUuid
        plugin.logger.info("Loaded ${byName.size} names (${byUuid.size} players) from Essentials usermap.csv.")
    }

    fun uuidForName(name: String): UUID? = uuidByName[name.lowercase(Locale.ROOT)]

    /** Every name (lower case) the player has been seen with, the current one included. */
    fun namesForUuid(uuid: UUID): Set<String> = namesByUuid[uuid] ?: emptySet()
}
