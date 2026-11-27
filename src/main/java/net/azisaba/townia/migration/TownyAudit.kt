package net.azisaba.townia.migration

import net.azisaba.townia.Townia
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TreeMap
import java.util.logging.Level

/**
 * `/towniaadmin migrate audit`: reads a Towny installation (plugins/Towny) without changing
 * anything and writes down what that server actually used, so it is clear what a migration
 * carries over and what Townia has no counterpart for.
 *
 * The report goes to plugins/Townia/towny-compat/audit.yml; a short summary is sent to the caller.
 */
object TownyAudit {

    /** Towny switches Townia does not implement. Reported when a server has them switched on. */
    private val unsupportedSwitches: List<Triple<String, String, (String) -> Boolean>> = listOf(
        Triple("global_nation_settings.nationzone.enable", "nation zones", ::isTrue),
        Triple("economy.closed_economy.enabled", "closed economy (server account)", ::isTrue),
        Triple("resident_settings.delete_old_residents.enable", "automatic deletion of inactive residents", ::isTrue),
        Triple("bank.is_banking_limited_to_bank_plots", "banking only on bank plots", ::isTrue),
        Triple("economy.banks.disallow_bank_actions_outside_town", "banking only inside the town", ::isTrue),
        Triple("economy.banks.town_bank_cap", "town bank cap", ::isPositive),
        Triple("economy.banks.nation_bank_cap", "nation bank cap", ::isPositive),
        Triple("jail.is_jailing_attacking_enemies", "jailing enemies who die attacking", ::isTrue),
        Triple("jail.is_jailing_attacking_outlaws", "jailing outlaws who die attacking", ::isTrue),
        Triple("jail.jail_denies_town_leave", "jailed residents cannot leave their town", ::isTrue),
        Triple("global_town_settings.keep_inventory_on_death_in_town", "keep inventory when dying in a town", ::isTrue),
        Triple("global_town_settings.keep_experience_on_death_in_town", "keep experience when dying in a town", ::isTrue),
        Triple("global_town_settings.town_respawn", "respawn at the town spawn", ::isTrue),
        Triple("resident_settings.deny_bed_use", "beds only usable on own plots", ::isTrue),
        Triple("economy.daily_taxes.town_plotbased_upkeep", "town upkeep by number of plots", ::isTrue),
        Triple("economy.daily_taxes.nation_pertown_upkeep", "nation upkeep by number of towns", ::isTrue),
        Triple("economy.daily_taxes.price_town_overclaimed_upkeep_penalty", "upkeep penalty for overclaimed towns", ::isPositive),
        Triple("global_town_settings.max_residents_per_town", "maximum residents per town", ::isPositive),
        Triple("global_nation_settings.max_towns_per_nation", "maximum towns per nation", ::isPositive),
        Triple("global_town_settings.required_number_residents_create_nation", "residents required to create a nation", ::isPositive),
        Triple("global_town_settings.required_number_residents_join_nation", "residents required to join a nation", ::isPositive),
        Triple("global_town_settings.nation_requires_proximity", "nation membership limited by distance", ::isPositive),
        Triple("global_town_settings.minimum_amount_of_residents_in_town_for_outpost", "residents required for an outpost", ::isPositive),
        Triple("town.max_distance_between_homeblocks", "maximum distance between home blocks", ::isPositive),
        Triple("economy.town_rename_cost", "town rename cost", ::isPositive),
        Triple("economy.nation_rename_cost", "nation rename cost", ::isPositive),
        Triple("economy.new_expand.price_claim_townblock_refund", "refund when unclaiming", { it.toDoubleOrNull()?.let { v -> v != 0.0 } == true }),
        Triple("invite_system.cooldowntime", "invite cooldown", { net.azisaba.townia.config.TownyRules.seconds(it, 0) > 0 }),
        Triple("invite_system.maximum_distance_from_town_spawn", "invites limited by distance", ::isPositive),
        Triple("new_world_settings.plot_management.revert_on_unclaim.enabled", "terrain revert on unclaim", ::isTrue),
        Triple("new_world_settings.plot_management.wild_revert_on_mob_explosion.enabled", "wilderness explosion revert", ::isTrue),
        Triple("new_world_settings.plot_management.block_delete.enabled", "block deletion on unclaim", ::isTrue),
        Triple("global_town_settings.outsiders_prevent_pvp_toggle", "outsiders block the PvP toggle", ::isTrue),
        Triple("global_town_settings.homeblocks_prevent_forcepvp", "home blocks exempt from forced PvP", ::isTrue),
        Triple("protection.town_prevent_villager_breeding", "villager breeding blocked in towns", ::isTrue)
    )

    private fun isTrue(value: String) = value.trim().equals("true", ignoreCase = true)
    private fun isPositive(value: String) = (value.trim().replace(",", "").toDoubleOrNull() ?: 0.0) > 0.0

    fun run(plugin: Townia, sender: CommandSender) {
        val townyDir = File(plugin.dataFolder.parentFile, "Towny")
        if (!townyDir.isDirectory) {
            plugin.messageManager.sendMessage(sender, "admin.config-migration-not-found")
            return
        }
        BulkRead.start(plugin, sender, "audit", Runnable {
            try {
                val report = YamlConfiguration()
                val summary = ArrayList<String>()
                auditConfig(townyDir, report, summary)
                auditPerms(plugin, townyDir, report, summary)
                auditData(plugin, townyDir, report, summary)
                val file = File(plugin.dataFolder, "towny-compat/audit.yml")
                file.parentFile.mkdirs()
                report.options().setHeader(
                    listOf(
                        "What this Towny installation used, as read by /towniaadmin migrate audit.",
                        "Nothing was changed. config.applied: carried over by /towniaadmin migrate config.",
                        "config.not-applied: Townia has no setting for it. unsupported-features: switched on in Towny, not in Townia."
                    )
                )
                report.save(file)
                summary.forEach { plugin.messageManager.sendMessage(sender, "admin.audit-line", "text", it.replace("<", "\\<")) }
                plugin.messageManager.sendMessage(sender, "admin.audit-done", "file", file.path)
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Towny audit failed", e)
                plugin.messageManager.sendMessage(sender, "admin.migration_failed")
            }
        })
    }

    // ------------------------------------------------------------------ config.yml

    private fun auditConfig(townyDir: File, report: YamlConfiguration, summary: MutableList<String>) {
        val file = listOf(File(townyDir, "settings/config.yml"), File(townyDir, "config.yml")).firstOrNull { it.isFile }
        if (file == null) {
            summary.add("config.yml: not found")
            return
        }
        val source = YamlConfiguration.loadConfiguration(file)
        val version = source.getString("version.version") ?: source.getString("version") ?: "?"
        report.set("towny-version", version)
        val applied = TownyConfigMigrator.appliedKeys(source)
        var notApplied = 0
        for (key in source.getKeys(true)) {
            if (source.isConfigurationSection(key)) continue
            if (key.startsWith("version") || key.startsWith("language") && key != "language.language" || key.startsWith("notification") ||
                key.startsWith("filters_colour_chat") || key.startsWith("war.") || key.startsWith("plugin.database")
            ) continue
            val value = source.get(key)
            val text = if (value is Collection<*>) "(list of ${value.size})" else value.toString().let { if (it.length > 120) it.take(117) + "..." else it }
            if (key in applied) {
                report.set("config.applied." + key.replace('.', '/'), text)
            } else {
                report.set("config.not-applied." + key.replace('.', '/'), text)
                notApplied++
            }
        }
        val unsupported = ArrayList<String>()
        for ((key, label, on) in unsupportedSwitches) {
            val raw = source.getString(key) ?: continue
            if (on(raw)) unsupported.add("$label ($key = $raw)")
        }
        val war = source.getKeys(true).any { it.startsWith("war.event") || it.startsWith("war.enemy") }
        report.set("unsupported-features", unsupported)
        report.set("war-settings-present", war)
        summary.add("Towny $version: ${applied.size} settings carried over, $notApplied without a Townia setting")
        if (unsupported.isEmpty()) summary.add("Switched on in Towny but not in Townia: none")
        else {
            summary.add("Switched on in Towny but NOT in Townia (${unsupported.size}):")
            unsupported.forEach { summary.add("  - $it") }
        }
    }

    // ------------------------------------------------------------------ townyperms.yml

    private fun auditPerms(plugin: Townia, townyDir: File, report: YamlConfiguration, summary: MutableList<String>) {
        val file = File(townyDir, "settings/townyperms.yml")
        if (!file.isFile) {
            summary.add("townyperms.yml: not found")
            return
        }
        val root = runCatching { file.reader(Charsets.UTF_8).use { Yaml().load<Any?>(it) as? Map<*, *> } }.getOrNull() ?: emptyMap<Any, Any>()
        fun ranks(section: String): List<String> =
            (((root[section] as? Map<*, *>)?.get("ranks")) as? Map<*, *>)?.keys?.map { it.toString() } ?: emptyList()
        val townRanks = ranks("towns")
        val nationRanks = ranks("nations")
        report.set("townyperms.town-ranks", townRanks)
        report.set("townyperms.nation-ranks", nationRanks)
        // The file may also grant nodes of other plugins; those keep working through Townia's attachment.
        val foreign = TreeMap<String, Int>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> node.values.forEach(::walk)
                is Collection<*> -> node.forEach { entry ->
                    val name = entry?.toString()?.trim()?.removePrefix("-") ?: return@forEach
                    if (!name.startsWith("towny.")) foreign.merge(name.substringBefore('.'), 1, Int::plus)
                }
            }
        }
        walk(root)
        report.set("townyperms.nodes-of-other-plugins", foreign.map { "${it.key} (${it.value})" })
        val bundled = plugin.townyPerms.townRankNames()
        summary.add("townyperms.yml: town ranks ${townRanks.joinToString(", ").ifEmpty { "-" }}; nation ranks ${nationRanks.joinToString(", ").ifEmpty { "-" }}")
        val own = townRanks.filter { it.lowercase(Locale.ROOT) !in bundled }
        if (own.isNotEmpty()) summary.add("  ranks Townia's current townyperms.yml lacks: ${own.joinToString(", ")} (run migrate config to copy the file)")
    }

    // ------------------------------------------------------------------ data folder

    private fun auditData(plugin: Townia, townyDir: File, report: YamlConfiguration, summary: MutableList<String>) {
        val dataDir = TownyFlatfileMigrator.findTownyDataDir(plugin)
        if (dataDir == null) {
            val type = TownyFlatfileMigrator.findTownyDatabaseLoadType(plugin)
            summary.add("data: no flat files found" + (if (type != null) " (Towny database_load = $type)" else ""))
            return
        }
        fun files(dir: String): List<File> =
            File(dataDir, dir).listFiles { f -> f.isFile && (f.extension.equals("txt", true) || f.extension.equals("data", true)) }?.toList() ?: emptyList()

        // residents
        val townRanks = TreeMap<String, Int>()
        val nationRanks = TreeMap<String, Int>()
        var titled = 0
        var jailed = 0
        var withFriends = 0
        var inTown = 0
        val residents = files("residents")
        for (file in residents) {
            val v = read(file)
            if (!v["town"].isNullOrBlank()) inTown++
            list(v["town-ranks"]).forEach { townRanks.merge(it.lowercase(Locale.ROOT), 1, Int::plus) }
            list(v["nation-ranks"]).forEach { nationRanks.merge(it.lowercase(Locale.ROOT), 1, Int::plus) }
            if (!v["title"].isNullOrBlank() || !v["surname"].isNullOrBlank()) titled++
            if (v["isJailed"].equals("true", ignoreCase = true)) jailed++
            if (list(v["friends"]).isNotEmpty()) withFriends++
        }
        report.set("data.residents.total", residents.size)
        report.set("data.residents.in-a-town", inTown)
        report.set("data.residents.town-ranks", townRanks.map { "${it.key}: ${it.value}" })
        report.set("data.residents.nation-ranks", nationRanks.map { "${it.key}: ${it.value}" })
        report.set("data.residents.with-title-or-surname", titled)
        report.set("data.residents.jailed", jailed)
        report.set("data.residents.with-friends", withFriends)

        // towns
        val towns = files("towns")
        var tagged = 0; var taxPercent = 0; var plotTax = 0; var shopOrEmbassy = 0; var spawnCost = 0
        var outposts = 0; var jails = 0; var outlaws = 0; var open = 0; var public = 0; var assistants = 0
        for (file in towns) {
            val v = read(file)
            if (!v["tag"].isNullOrBlank()) tagged++
            if (v["taxpercent"].equals("true", ignoreCase = true)) taxPercent++
            if (number(v["plotTax"]) > 0) plotTax++
            if (number(v["commercialPlotTax"]) > 0 || number(v["embassyPlotTax"]) > 0 || number(v["commercialPlotPrice"]) > 0 || number(v["embassyPlotPrice"]) > 0) shopOrEmbassy++
            if (number(v["spawnCost"]) > 0) spawnCost++
            if (!v["outpostspawns"].isNullOrBlank()) outposts++
            if (!v["jailspawns"].isNullOrBlank()) jails++
            if (list(v["outlaws"]).isNotEmpty()) outlaws++
            if (v["open"].equals("true", ignoreCase = true)) open++
            if (v["public"].equals("true", ignoreCase = true)) public++
            if (list(v["assistants"]).isNotEmpty()) assistants++
        }
        report.set("data.towns.total", towns.size)
        report.set("data.towns.with-tag", tagged)
        report.set("data.towns.taxing-by-percent", taxPercent)
        report.set("data.towns.with-plot-tax", plotTax)
        report.set("data.towns.with-shop-or-embassy-prices", shopOrEmbassy)
        report.set("data.towns.with-own-spawn-cost", spawnCost)
        report.set("data.towns.with-outposts", outposts)
        report.set("data.towns.with-jails", jails)
        report.set("data.towns.with-outlaws", outlaws)
        report.set("data.towns.open", open)
        report.set("data.towns.public", public)
        report.set("data.towns.with-assistants-line", assistants)

        // nations
        val nations = files("nations")
        var allied = 0; var hostile = 0; var neutral = 0; var nationTags = 0
        for (file in nations) {
            val v = read(file)
            if (list(v["allies"]).isNotEmpty()) allied++
            if (list(v["enemies"]).isNotEmpty()) hostile++
            if (v["neutral"].equals("true", ignoreCase = true)) neutral++
            if (!v["tag"].isNullOrBlank()) nationTags++
        }
        report.set("data.nations.total", nations.size)
        report.set("data.nations.with-allies", allied)
        report.set("data.nations.with-enemies", hostile)
        report.set("data.nations.neutral", neutral)
        report.set("data.nations.with-tag", nationTags)

        // town blocks
        val typeNames = mapOf(
            "0" to "default", "1" to "shop", "2" to "arena", "3" to "embassy", "4" to "wilds",
            "5" to "spleef", "6" to "inn", "7" to "jail", "8" to "farm", "9" to "bank"
        )
        val types = TreeMap<String, Int>()
        var blocks = 0; var owned = 0; var forSale = 0; var customPerms = 0; var grouped = 0
        File(dataDir, "townblocks").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension.equals("data", true) }?.forEach { file ->
            val v = read(file)
            blocks++
            val type = v["type"]?.trim().orEmpty()
            types.merge(typeNames[type] ?: type.lowercase(Locale.ROOT).ifEmpty { "default" }, 1, Int::plus)
            if (!v["resident"].isNullOrBlank()) owned++
            if (number(v["price"], -1.0) >= 0) forSale++
            if (v["changed"].equals("true", ignoreCase = true)) customPerms++
            if (!v["groupID"].isNullOrBlank()) grouped++
        }
        val groups = File(dataDir, "plotgroups").listFiles { f -> f.isFile }?.size ?: 0
        report.set("data.plots.total", blocks)
        report.set("data.plots.by-type", types.map { "${it.key}: ${it.value}" })
        report.set("data.plots.owned-by-a-resident", owned)
        report.set("data.plots.for-sale", forSale)
        report.set("data.plots.with-their-own-permissions", customPerms)
        report.set("data.plots.in-a-plot-group", grouped)
        report.set("data.plot-groups", groups)

        // worlds: which settings differ between worlds (Townia has one set for every world)
        val worldFiles = files("worlds")
        val perWorld = worldFiles.associate { it.nameWithoutExtension to read(it) }
        val differing = TreeMap<String, String>()
        val watched = listOf(
            "pvp", "forcepvp", "claimable", "worldmobs", "forcetownmobs", "firespread", "forcefirespread", "explosions", "forceexplosions",
            "usingTowny", "unclaimedZoneBuild", "unclaimedZoneDestroy", "unclaimedZoneSwitch", "unclaimedZoneItemUse", "unclaimedZoneIgnoreIds"
        )
        for (key in watched) {
            val values = perWorld.mapValues { it.value[key] ?: "" }
            if (values.values.toSet().size > 1) differing[key] = values.entries.joinToString(", ") { "${it.key}=${it.value.take(40)}" }
        }
        report.set("data.worlds.names", perWorld.keys.toList())
        report.set("data.worlds.not-using-towny", perWorld.filter { it.value["usingTowny"].equals("false", ignoreCase = true) }.keys.toList())
        report.set("data.worlds.not-claimable", perWorld.filter { it.value["claimable"].equals("false", ignoreCase = true) }.keys.toList())
        differing.forEach { (key, text) -> report.set("data.worlds.settings-that-differ.$key", text) }

        summary.add("data: ${residents.size} residents ($inTown in a town), ${towns.size} towns, ${nations.size} nations, $blocks plots")
        summary.add("  ranks in use: " + (townRanks.entries.joinToString(", ") { "${it.key} x${it.value}" }.ifEmpty { "-" }) +
            (if (nationRanks.isEmpty()) "" else " | nation: " + nationRanks.entries.joinToString(", ") { "${it.key} x${it.value}" }))
        summary.add("  plot types: " + types.entries.joinToString(", ") { "${it.key} x${it.value}" }.ifEmpty { "-" })
        summary.add("  plot groups: $groups, plots in a group: $grouped, titles/surnames: $titled, towns with a tag: $tagged")
        if (groups > 0 || grouped > 0) summary.add("  ! plot groups are in use; Townia has none, the plots migrate as single plots")
        if ((types["jail"] ?: 0) > 0) summary.add("  ! jail plots become normal plots; the jail spawns migrate with their town")
        if (differing.isEmpty()) summary.add("  worlds: ${perWorld.size}, all with the same settings")
        else summary.add("  ! worlds differ in: ${differing.keys.joinToString(", ")} - Townia applies one set of wilderness rules to every allowed world")
    }

    private fun read(file: File): Map<String, String> {
        BulkRead.fileRead()
        val bytes = file.readBytes()
        val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrElse { String(bytes, Charset.forName("MS932")) }
        val values = HashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val at = line.indexOf('=')
            if (at <= 0) return@forEach
            values[line.substring(0, at).trim()] = line.substring(at + 1).trim()
        }
        return values
    }

    private fun list(raw: String?): List<String> = raw?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun number(raw: String?, fallback: Double = 0.0): Double = raw?.trim()?.replace(",", "")?.toDoubleOrNull() ?: fallback
}
