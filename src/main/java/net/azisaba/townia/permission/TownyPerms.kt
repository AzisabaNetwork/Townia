package net.azisaba.townia.permission

import net.azisaba.townia.Townia
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.data.TownRank
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.permissions.PermissionDefault
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * The rank-to-permission table of townyperms.yml, as Towny 0.96 applied it.
 *
 * Each player's nodes are worked out from where they stand (townless, town member, mayor,
 * town ranks, nation member, king, nation ranks) and handed to Bukkit through one
 * attachment. The result is cached and only rebuilt when that standing changes, so a
 * permission check is a map lookup.
 */
class TownyPerms(private val plugin: Townia) {

    private var nomad: List<String> = emptyList()
    private var townDefault: List<String> = emptyList()
    private var townMayor: List<String> = emptyList()
    private var townRanks: Map<String, List<String>> = emptyMap()
    private var nationDefault: List<String> = emptyList()
    private var nationKing: List<String> = emptyList()
    private var nationRanks: Map<String, List<String>> = emptyMap()

    /** Parent node -> children it switches on (true) or off (false). */
    private var children: Map<String, Map<String, Boolean>> = emptyMap()
    private var defaultTrue: Set<String> = emptySet()

    /** Bumped on every reload so cached results are thrown away. */
    @Volatile
    private var generation = 0

    private class State(
        val generation: Int,
        val townId: UUID?,
        val mayor: Boolean,
        val townRanks: String,
        val townName: String?,
        val nationId: UUID?,
        val king: Boolean,
        val nationRanks: String,
        val nationName: String?,
        val perms: Map<String, Boolean>
    ) {
        var attachment: PermissionAttachment? = null
        var bukkitNode: String? = null
    }

    private val states = ConcurrentHashMap<UUID, State>()

    /** One Bukkit permission per distinct set of nodes, shared by every player who has that set. */
    private val bundles = ConcurrentHashMap<Map<String, Boolean>, String>()

    init {
        load()
    }

    fun townRankNames(): Set<String> = townRanks.keys

    fun nationRankNames(): Set<String> = nationRanks.keys

    /** Reads townyperms.yml (creating it from the bundled copy when missing) and the node layout. */
    fun load() {
        loadNodeLayout()
        val file = File(plugin.dataFolder, FILE_NAME)
        if (!file.isFile) {
            runCatching {
                file.parentFile?.mkdirs()
                resource(FILE_NAME)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            }.onFailure { plugin.logger.log(Level.WARNING, "Could not create $FILE_NAME", it) }
        }
        val root: Map<*, *> = runCatching {
            if (file.isFile) file.reader(Charsets.UTF_8).use { Yaml().load<Any?>(it) as? Map<*, *> }
            else resource(FILE_NAME)?.reader(Charsets.UTF_8)?.use { Yaml().load<Any?>(it) as? Map<*, *> }
        }.onFailure {
            plugin.logger.log(Level.SEVERE, "$FILE_NAME could not be read; using the built-in permissions", it)
        }.getOrNull() ?: resource(FILE_NAME)?.reader(Charsets.UTF_8)?.use { Yaml().load<Any?>(it) as? Map<*, *> } ?: emptyMap<Any, Any>()

        val towns = root["towns"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val nations = root["nations"] as? Map<*, *> ?: emptyMap<Any, Any>()
        nomad = nodes(root["nomad"])
        townDefault = nodes(towns["default"])
        townMayor = nodes(towns["mayor"])
        townRanks = withBuiltInRanks(ranks(towns["ranks"]))
        nationDefault = nodes(nations["default"])
        nationKing = nodes(nations["king"])
        nationRanks = ranks(nations["ranks"])
        generation++
        states.clear()
        refreshAll()
    }

    /**
     * A townyperms.yml brought over from Towny knows nothing of Townia's own sub_mayor rank;
     * without this its holders would silently lose every permission the rank carries.
     */
    private fun withBuiltInRanks(loaded: Map<String, List<String>>): Map<String, List<String>> {
        if (BUILT_IN_RANK in loaded) return loaded
        // The server's own definition wins, whatever spelling its townyperms.yml used.
        for (alias in SUB_MAYOR_ALIASES) {
            val own = loaded[alias] ?: continue
            return LinkedHashMap(loaded).apply {
                remove(alias)
                put(BUILT_IN_RANK, own)
            }
        }
        val bundled = runCatching {
            resource(FILE_NAME)?.reader(Charsets.UTF_8)?.use { Yaml().load<Any?>(it) as? Map<*, *> }
        }.getOrNull()
        val fallback = ranks((bundled?.get("towns") as? Map<*, *>)?.get("ranks"))[BUILT_IN_RANK] ?: return loaded
        return LinkedHashMap(loaded).apply { put(BUILT_IN_RANK, fallback) }
    }

    private fun loadNodeLayout() {
        val root = runCatching {
            resource(NODES_FILE)?.reader(Charsets.UTF_8)?.use { Yaml().load<Any?>(it) as? Map<*, *> }
        }.getOrNull() ?: emptyMap<Any, Any>()
        defaultTrue = nodes(root["default-true"]).toSet()
        val result = LinkedHashMap<String, Map<String, Boolean>>()
        (root["children"] as? Map<*, *>)?.forEach { (parent, list) ->
            val map = LinkedHashMap<String, Boolean>()
            nodes(list).forEach { child ->
                if (child.startsWith("-")) map[child.substring(1)] = false else map[child] = true
            }
            result[parent.toString()] = map
        }
        children = result
    }

    private fun resource(name: String) = TownyPerms::class.java.classLoader.getResourceAsStream(name)

    private fun nodes(raw: Any?): List<String> =
        (raw as? Collection<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) } ?: emptyList()

    private fun ranks(raw: Any?): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        (raw as? Map<*, *>)?.forEach { (name, list) ->
            result[name.toString().lowercase(Locale.ROOT)] = nodes(list)
        }
        return result
    }

    /** The nodes [resident] holds, parents expanded to their children. Null means a player Townia has never seen. */
    fun permsFor(resident: TowniaPlayer?): Map<String, Boolean> = compute(resident).perms

    private fun compute(resident: TowniaPlayer?): State {
        val town = resident?.townUuid?.let { plugin.townManager.getTown(it).orElse(null) }
        val nation = town?.nationUuid?.let { plugin.nationManager.getNation(it).orElse(null) }
        val uuid = resident?.uuid
        val mayor = town != null && (resident.rank == TownRank.MAYOR || town.mayorUuid == uuid)
        val king = nation != null && nation.leaderUuid == uuid
        val heldTownRanks = if (town != null) resident.effectiveTownRanks() else emptySet()
        val heldNationRanks = if (nation != null) resident.nationRanks else emptySet()

        val raw = LinkedHashSet<String>(nomad)
        if (town != null) {
            raw.addAll(townDefault)
            if (mayor) raw.addAll(townMayor)
            heldTownRanks.forEach { rank -> townRanks[rank]?.let(raw::addAll) }
            if (nation != null) {
                raw.addAll(nationDefault)
                if (king) raw.addAll(nationKing)
                heldNationRanks.forEach { rank -> nationRanks[rank]?.let(raw::addAll) }
            }
        }

        // As in Towny, a node written in the file always beats one that is only implied by a
        // parent: first every parent hands its values down, then the listed nodes are applied.
        val perms = LinkedHashMap<String, Boolean>()
        defaultTrue.forEach { perms[it] = true }
        val listed = LinkedHashMap<String, Boolean>()
        for (entry in raw.sorted()) {
            var node = entry
            if (node.contains("{townname}")) {
                node = node.replace("{townname}", town?.name?.lowercase(Locale.ROOT) ?: continue)
            } else if (node.contains("{nationname}")) {
                node = node.replace("{nationname}", nation?.name?.lowercase(Locale.ROOT) ?: continue)
            }
            val value = !node.startsWith("-")
            if (!value) node = node.substring(1)
            listed[node] = value
        }
        listed.forEach { (node, value) -> expand(node, value, perms, 0) }
        perms.putAll(listed)
        for (alias in SUB_MAYOR_ALIASES) {
            perms["towny.command.town.rank.$alias"]?.let { perms.putIfAbsent("towny.command.town.rank.$BUILT_IN_RANK", it) }
        }
        return State(
            generation, town?.id, mayor, heldTownRanks.joinToString(","), town?.name,
            nation?.id, king, heldNationRanks.joinToString(","), nation?.name, perms
        )
    }

    /** Bukkit's child rule: a child takes its listed value when the parent is on, the opposite when it is off. */
    private fun expand(parent: String, parentValue: Boolean, into: MutableMap<String, Boolean>, depth: Int) {
        if (depth > 16) return
        val kids = children[parent] ?: return
        for ((child, listed) in kids) {
            val value = if (parentValue) listed else !listed
            into[child] = value
            expand(child, value, into, depth + 1)
        }
    }

    /** True when [state] still describes [resident]; compares a handful of fields, allocates little. */
    private fun isCurrent(state: State, resident: TowniaPlayer?): Boolean {
        if (state.generation != generation) return false
        val town = resident?.townUuid?.let { plugin.townManager.getTown(it).orElse(null) }
        if (state.townId != town?.id) return false
        if (town == null) return true
        if (state.townName != town.name) return false
        if (state.mayor != (resident.rank == TownRank.MAYOR || town.mayorUuid == resident.uuid)) return false
        if (state.townRanks != resident.effectiveTownRanks().joinToString(",")) return false
        val nation = town.nationUuid?.let { plugin.nationManager.getNation(it).orElse(null) }
        if (state.nationId != nation?.id) return false
        if (nation == null) return true
        return state.nationName == nation.name &&
            state.king == (nation.leaderUuid == resident.uuid) &&
            state.nationRanks == resident.nationRanks.joinToString(",")
    }

    /** The nodes townyperms.yml gives [player] right now. Rebuilt only if their standing changed. */
    fun effective(player: Player): Map<String, Boolean> {
        val id = player.uniqueId
        val resident = plugin.residentManager.getResident(id).orElse(null)
        val cached = states[id]
        if (cached != null && isCurrent(cached, resident)) return cached.perms
        val fresh = compute(resident)
        fresh.attachment = cached?.attachment
        fresh.bukkitNode = cached?.bukkitNode
        states[id] = fresh
        attach(player, fresh)
        return fresh.perms
    }

    /**
     * Hands the nodes to Bukkit so that other plugins (and nodes of other plugins listed in
     * townyperms.yml) see them. One permission holding the whole set is attached, which costs a
     * single recalculation however many nodes the set has.
     */
    private fun attach(player: Player, state: State) {
        runCatching {
            val bundle = bundles.computeIfAbsent(state.perms) { perms ->
                val name = "townia.bundle." + Integer.toHexString(perms.hashCode()) + "." + bundles.size
                runCatching {
                    Bukkit.getPluginManager().addPermission(Permission(name, "Townia rank permissions", PermissionDefault.FALSE, perms))
                }
                name
            }
            if (state.bukkitNode == bundle && state.attachment != null) return
            val attachment = state.attachment ?: player.addAttachment(plugin) ?: return
            state.bukkitNode?.let { attachment.unsetPermission(it) }
            attachment.setPermission(bundle, true)
            state.attachment = attachment
            state.bukkitNode = bundle
        }
    }

    fun refresh(player: Player) {
        effective(player)
    }

    fun refreshAll() {
        runCatching { Bukkit.getOnlinePlayers().forEach(::refresh) }
    }

    fun forget(player: Player) {
        val state = states.remove(player.uniqueId) ?: return
        runCatching { state.attachment?.remove() }
    }

    companion object {
        const val FILE_NAME = "townyperms.yml"
        private const val BUILT_IN_RANK = "sub_mayor"

        /** Other spellings of Townia's sub_mayor rank found in Towny servers' files. */
        val SUB_MAYOR_ALIASES = listOf("submayor", "co_mayor", "comayor", "co-mayor")
        private const val NODES_FILE = "towny-nodes.yml"
    }
}
