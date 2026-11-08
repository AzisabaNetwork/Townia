package net.azisaba.townia.migration

import net.azisaba.townia.Townia
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import java.util.Locale
import java.util.UUID

/** Helpers shared by the Towny API and flatfile migrators. */
internal object MigrationSupport {
    /** Option: never overwrite a plot Townia already has; only add chunks it is missing. */
    const val MISSING_ONLY = "missing-only"

    /** Option: delete Townia plots whose chunk Towny no longer claims (only in worlds Towny has data for). */
    const val PRUNE = "prune"

    /**
     * Option (flatfile): also read towns/nations from Towny's deleted/ folders when
     * they still have residents or land. Those files can be years old; opt-in only.
     */
    const val INCLUDE_DELETED = "include-deleted"

    val options = setOf(MISSING_ONLY, PRUNE, INCLUDE_DELETED)

    /**
     * Progress used to reach only the player who ran the command, so a migration
     * cut short by a restart left no trace in the server log. Log it as well.
     */
    fun progress(plugin: Townia, sender: CommandSender, stage: String, count: String) {
        plugin.messageManager.sendMessage(sender, "admin.migration-progress", "stage", stage, "count", count)
        if (sender !is ConsoleCommandSender) plugin.logger.info("Towny migration: $stage - $count")
    }

    fun offlineUuid(name: String): UUID =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))

    /**
     * Name -> UUID of the residents Townia already knows, preferring a real
     * (online-mode) UUID over an offline-mode one derived from the name.
     */
    fun knownUuidsByName(plugin: Townia): Map<String, UUID> {
        val result = HashMap<String, UUID>(essentialsUuidsByName(plugin))
        for (resident in plugin.residentManager.allResidents) {
            val name = resident.name?.takeIf { it.isNotBlank() } ?: continue
            val uuid = resident.uuid ?: continue
            val key = name.lowercase(Locale.ROOT)
            val existing = result[key]
            if (existing == null || (existing == offlineUuid(name) && uuid != offlineUuid(name))) {
                result[key] = uuid
            }
        }
        return result
    }

    /**
     * Old Towny resident files carry no UUID, only the name. EssentialsX keeps
     * one `userdata/<uuid>.yml` per player who ever joined, with the name as
     * `last-account-name`, so it maps names to real UUIDs without asking Mojang.
     * For a name seen under several UUIDs, a real UUID beats an offline-mode
     * one, then the most recently written file wins.
     */
    /** UUID -> last known name, filled by [knownUuidsByName] from EssentialsX userdata. */
    @Volatile
    private var essentialsNames: Map<UUID, String> = emptyMap()

    /** A player's name from EssentialsX or the server's own player data; never asks Mojang. */
    fun nameForUuid(uuid: UUID): String? =
        essentialsNames[uuid] ?: runCatching { Bukkit.getOfflinePlayer(uuid).name }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun essentialsUuidsByName(plugin: Townia): Map<String, UUID> {
        val dir = java.io.File(plugin.dataFolder.parentFile, "Essentials/userdata")
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".yml") } ?: return emptyMap()
        val best = HashMap<String, Pair<UUID, Long>>()
        val names = HashMap<UUID, String>()
        for (file in files) {
            val uuid = runCatching { UUID.fromString(file.name.removeSuffix(".yml")) }.getOrNull() ?: continue
            val name = runCatching {
                file.useLines { lines ->
                    lines.firstOrNull { it.startsWith("last-account-name:") }
                        ?.substringAfter(':')?.trim()?.trim('\'', '"')
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            names[uuid] = name
            val key = name.lowercase(Locale.ROOT)
            val modified = file.lastModified()
            val current = best[key]
            val better = current == null ||
                (current.first == offlineUuid(name) && uuid != offlineUuid(name)) ||
                ((current.first == offlineUuid(name)) == (uuid == offlineUuid(name)) && modified > current.second)
            if (better) best[key] = uuid to modified
        }
        essentialsNames = names
        plugin.logger.info("Towny migration: ${best.size} player names resolved from Essentials userdata.")
        return best.mapValues { it.value.first }
    }

    /**
     * Resolves a player name without any network request. Bukkit's
     * getOfflinePlayer(name) asks Mojang for every unknown name; during a
     * migration that is tens of thousands of requests, Mojang rate-limits them,
     * and every failed one used to become a duplicate offline-UUID resident.
     * The offline UUID is still the last resort: such a record is merged into
     * the player's real one when they next join (ResidentManager.getOrCreate).
     */
    fun resolveUuidByName(name: String, known: Map<String, UUID>): UUID {
        Bukkit.getPlayerExact(name)?.let { return it.uniqueId }
        known[name.lowercase(Locale.ROOT)]?.let { return it }
        // Old names too: a Towny file can still carry a name the player has changed since.
        runCatching { Townia.instance.nameHistory.uuidForName(name) }.getOrNull()?.let { return it }
        Bukkit.getOfflinePlayerIfCached(name)?.let { return it.uniqueId }
        return offlineUuid(name)
    }
}
