package net.azisaba.townia.migration

import net.azisaba.townia.Townia
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

object TownyConfigMigrator {
    fun migrate(plugin: Townia, sender: CommandSender) {
        val townyConfig = findTownyConfig(plugin)
        if (townyConfig == null || !townyConfig.exists()) {
            plugin.messageManager.sendMessage(sender, "admin.config-migration-not-found")
            return
        }

        val source = YamlConfiguration.loadConfiguration(townyConfig)
        val targetFile = File(plugin.dataFolder, "config.yml")
        if (!targetFile.exists()) plugin.saveDefaultConfig()
        val target = YamlConfiguration.loadConfiguration(targetFile)
        val changed = ArrayList<String>()

        applyMappings(source, target, changed)
        copyCompatValues(source, target, changed)

        target.set("towny-compat.enabled", true)
        target.set("towny-compat.source-config", "towny-compat/config.yml")
        target.set("towny-compat.migration-report", "towny-compat/migration-report.yml")
        target.set("towny-compat.chat.enabled", false)
        target.set("towny-compat.chat.note", "Towny chat config is preserved but not activated by Townia.")

        val language = source.getString("language.language") ?: source.getString("language")
        if (language != null) {
            target.set("default-language", if (language.contains("japanese", ignoreCase = true) || language.contains("ja", ignoreCase = true)) "ja" else "en")
            changed.add("default-language")
        }

        val worlds = source.getStringList("allowed-worlds").ifEmpty {
            source.getStringList("new_world_settings.default_worlds")
        }
        if (worlds.isNotEmpty()) {
            target.set("allowed-worlds", worlds)
            changed.add("allowed-worlds")
        }
        migrateWorlds(plugin, target, changed)

        migrateTownyPerms(plugin, townyConfig, changed)
        migrateDatabaseConfig(plugin, townyConfig, target, changed)
        alignConfigVersion(plugin, target, changed)
        target.save(targetFile)
        saveCompatFiles(plugin, townyConfig, source, changed)
        saveMigrationReport(plugin, townyConfig, source, changed)
        plugin.reloadConfig()
        plugin.towniaConfig.reload()
        // The migrated config may have switched health regen or mob removal on; without this they
        // would stay off until the next /towniaadmin reload.
        plugin.townTimers.restart()
        plugin.messageManager.sendMessage(sender, "admin.config-migration-success", "file", townyConfig.path, "count", changed.size.toString())
        if (changed.isNotEmpty()) plugin.messageManager.sendMessage(sender, "admin.config-migration-updated", "keys", changed.joinToString(", "))
    }

    private fun alignConfigVersion(plugin: Townia, target: YamlConfiguration, changed: MutableList<String>) {
        val internalVersion = plugin.getResource("config.yml")?.use { input ->
            input.reader(Charsets.UTF_8).use { reader ->
                YamlConfiguration.loadConfiguration(reader).getInt("version", target.getInt("version", 1))
            }
        } ?: return
        if (target.getInt("version", 0) < internalVersion) {
            target.set("version", internalVersion)
            changed.add("version")
        }
    }

    /**
     * Brings Towny's townyperms.yml (ranks and who may use which command) over as it is: Townia
     * reads the same format. The file it replaces is kept as townyperms.yml.bak.
     */
    private fun migrateTownyPerms(plugin: Townia, townyConfig: File, changed: MutableList<String>) {
        val source = listOf(
            File(townyConfig.parentFile, "townyperms.yml"),
            File(plugin.dataFolder.parentFile, "Towny/settings/townyperms.yml")
        ).firstOrNull { it.isFile } ?: return
        val target = File(plugin.dataFolder, net.azisaba.townia.permission.TownyPerms.FILE_NAME)
        try {
            if (target.isFile) {
                if (target.readBytes().contentEquals(source.readBytes())) return
                target.copyTo(File(plugin.dataFolder, target.name + ".bak"), overwrite = true)
            }
            source.copyTo(target, overwrite = true)
            changed.add("townyperms.yml")
            plugin.townyPerms.load()
        } catch (e: Exception) {
            plugin.logger.warning("Could not copy Towny's townyperms.yml: ${e.message}")
        }
    }

    /** Towny keys a mapping actually read, collected for the audit. */
    private val consulted = LinkedHashSet<String>()

    /** Every value Townia takes over from Towny's config.yml. */
    private fun applyMappings(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>) {
        copyDouble(source, target, changed, "town-creation-cost", "economy.new_expand.price_new_town", "price_new_town", "new_expand.price_new_town")
        copyDouble(source, target, changed, "nation-creation-cost", "economy.new_expand.price_new_nation", "price_new_nation", "new_expand.price_new_nation")
        copyDouble(source, target, changed, "claim-cost", "economy.new_expand.price_claim_townblock", "price_claim_townblock", "new_expand.price_claim_townblock")
        copyDouble(source, target, changed, "town-upkeep", "economy.daily_taxes.price_town_upkeep", "daily_taxes.price_town_upkeep", "town_upkeep")
        copyDouble(source, target, changed, "default-town-tax", "economy.daily_taxes.town_tax", "daily_taxes.town_tax", "town_tax")
        copyDouble(source, target, changed, "default-nation-tax", "economy.daily_taxes.nation_tax", "daily_taxes.nation_tax", "nation_tax")
        // Towny's town_block_limit is a hard cap and town_block_ratio replaces the level limit;
        // neither is a default limit or a per-resident bonus on top of the levels.
        copyInt(source, target, changed, "default-claim-limit", "global_town_settings.default_town_block_limit", "default_town_block_limit")
        copyInt(source, target, changed, "town-block-ratio", "town.town_block_ratio", "claiming.town_block_ratio", "global_town_settings.town_block_ratio", "town_block_ratio")
        copyInt(source, target, changed, "town-block-limit", "town.town_block_limit", "claiming.town_block_limit", "global_town_settings.town_block_limit", "town_block_limit")
        copyInt(source, target, changed, "max-bonus-claims", "global_town_settings.max_purchased_blocks", "max_purchased_blocks", "townBlockBuyBonusLimit")
        copyInt(source, target, changed, "invite-timeout", "invite_system.invite_timeout", "invite_timeout", "invite_timeout_time", "global_town_settings.invite_timeout")
        copyBoolean(source, target, changed, "wilderness.pvp", "new_world_settings.pvp.world_pvp", "new_world_settings.pvp.force_pvp_on", "wilderness_pvp", "pvp")
        copyBoolean(source, target, changed, "wilderness.build", "unclaimed.unclaimed_zone_build", "wilderness_build")
        copyBoolean(source, target, changed, "wilderness.destroy", "unclaimed.unclaimed_zone_destroy", "wilderness_destroy")
        copyBoolean(source, target, changed, "wilderness.switch", "unclaimed.unclaimed_zone_switch", "wilderness_switch")
        copyBoolean(source, target, changed, "wilderness.item-use", "unclaimed.unclaimed_zone_item_use", "wilderness_item_use")
        copyBoolean(source, target, changed, "wilderness.mobs", "new_world_settings.mobs.world_monsters_on", "new_world_settings.wilderness.wilderness_monsters_on", "new_world_settings.wilderness_monsters_on", "wilderness_mobs")
        copyBoolean(source, target, changed, "wilderness.explosions", "new_world_settings.explosions.world_explosions_enabled", "new_world_settings.explosion_revert.wilderness_explosion_revert", "wilderness_explosions")
        copyBoolean(source, target, changed, "wilderness.fire", "new_world_settings.fire.world_firespread_enabled", "new_world_settings.fire.fire_spread", "wilderness_fire")
        copyBoolean(source, target, changed, "town-defaults.pvp", "default_perm_flags.town.default.pvp")
        copyBoolean(source, target, changed, "town-defaults.mobs", "default_perm_flags.town.default.mobs")
        copyBoolean(source, target, changed, "town-defaults.explosions", "default_perm_flags.town.default.explosion")
        copyBoolean(source, target, changed, "town-defaults.fire", "default_perm_flags.town.default.fire")
        copyPermMatrix(source, target, changed, "town-defaults.perms.resident", "default_perm_flags.town.resident")
        copyPermMatrix(source, target, changed, "town-defaults.perms.nation", "default_perm_flags.town.nation")
        copyPermMatrix(source, target, changed, "town-defaults.perms.ally", "default_perm_flags.town.ally")
        copyPermMatrix(source, target, changed, "town-defaults.perms.outsider", "default_perm_flags.town.outsider")
        copyPermMatrix(source, target, changed, "resident-defaults.perms.friend", "default_perm_flags.resident.friend")
        copyPermMatrix(source, target, changed, "resident-defaults.perms.resident", "default_perm_flags.resident.town")
        copyPermMatrix(source, target, changed, "resident-defaults.perms.ally", "default_perm_flags.resident.ally")
        copyPermMatrix(source, target, changed, "resident-defaults.perms.outsider", "default_perm_flags.resident.outsider")
        migrateTownyRules(source, target, changed)
        copyLevelList(source, target, changed, "town_level", "levels.town_level")
        copyLevelList(source, target, changed, "nation_level", "levels.nation_level")
    }

    /** The Towny config keys the migration understands among those [source] contains. */
    fun appliedKeys(source: YamlConfiguration): Set<String> {
        consulted.clear()
        applyMappings(source, YamlConfiguration(), ArrayList())
        listOf("language.language", "language", "new_world_settings.default_worlds").filter { source.contains(it) }.forEach(consulted::add)
        return LinkedHashSet(consulted)
    }

    /** The prices, limits and protection lists TownyRules reads. Towny stores every value as a string. */
    private fun migrateTownyRules(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>) {
        copyDouble(source, target, changed, "economy.claim-cost-increase", "economy.new_expand.price_claim_townblock_increase")
        copyDouble(source, target, changed, "economy.outpost-cost", "economy.new_expand.price_outpost")
        copyDouble(source, target, changed, "economy.bonus-block-cost", "economy.new_expand.price_purchased_bonus_townblock")
        copyDouble(source, target, changed, "economy.bonus-block-cost-increase", "economy.new_expand.price_purchased_bonus_townblock_increase")
        copyDouble(source, target, changed, "economy.nation-upkeep", "economy.daily_taxes.price_nation_upkeep")
        copyDouble(source, target, changed, "economy.neutrality-cost", "economy.price_nation_neutrality")
        copyDouble(source, target, changed, "economy.max-tax-amount", "economy.daily_taxes.max_tax_amount", "economy.daily_taxes.max_town_tax_amount")
        copyDouble(source, target, changed, "economy.max-tax-percent", "economy.daily_taxes.max_tax_percent", "economy.daily_taxes.max_town_tax_percent")
        source.getString("economy.death.price_death_type")?.let {
            consulted.add("economy.death.price_death_type")
            target.set("economy.death.type", if (it.equals("percentage", ignoreCase = true)) "percentage" else "fixed")
            changed.add("economy.death.type")
        }
        copyDouble(source, target, changed, "economy.death.percentage-cap", "economy.death.percentage_cap")
        copyBoolean(source, target, changed, "economy.death.pvp-only", "economy.death.price_death_pvp_only")
        copyDouble(source, target, changed, "economy.death.price", "economy.death.price_death")
        copyDouble(source, target, changed, "economy.death.town-price", "economy.death.price_death_town")
        copyDouble(source, target, changed, "economy.death.nation-price", "economy.death.price_death_nation")
        copyDouble(source, target, changed, "economy.spawn-travel.town", "economy.spawn_travel.price_town_spawn_travel")
        copyDouble(source, target, changed, "economy.spawn-travel.nation", "economy.spawn_travel.price_town_nation_spawn_travel")
        copyDouble(source, target, changed, "economy.spawn-travel.ally", "economy.spawn_travel.price_town_ally_spawn_travel")
        copyDouble(source, target, changed, "economy.spawn-travel.public", "economy.spawn_travel.price_town_public_spawn_travel")
        copyBoolean(source, target, changed, "economy.spawn-travel.paid-to-town", "economy.spawn_travel.town_spawn_cost_paid_to_town")

        copySeconds(source, target, changed, "spawn.cooldown", "global_town_settings.spawn_cooldown_time")
        copySeconds(source, target, changed, "spawn.cooldown", "spawning.town_spawn.spawning_cooldowns.town_spawn_cooldown_time")
        copySeconds(source, target, changed, "spawn.warmup", "global_town_settings.teleport_warmup_time")
        copySeconds(source, target, changed, "spawn.warmup", "spawning.spawning_warmups.teleport_warmup_time")
        copyBoolean(source, target, changed, "spawn.allow-travel", "global_town_settings.allow_town_spawn_travel", "spawning.town_spawn.allow_town_spawn_travel")
        copyBoolean(source, target, changed, "spawn.allow-travel-nation", "global_town_settings.allow_town_spawn_travel_nation", "spawning.town_spawn.allow_town_spawn_travel_nation")
        copyBoolean(source, target, changed, "spawn.allow-travel-ally", "global_town_settings.allow_town_spawn_travel_ally", "spawning.town_spawn.allow_town_spawn_travel_ally")
        copyNameList(source, target, changed, "spawn.prevent-in", "global_town_settings.prevent_town_spawn_in", lower = true)
        copyNameList(source, target, changed, "spawn.prevent-in", "spawning.town_spawn.prevent_town_spawn_in", lower = true)

        copyInt(source, target, changed, "claiming.min-distance-from-town-plot", "town.min_plot_distance_from_town_plot", "claiming.distance_rules.min_plot_distance_from_town_plot")
        copyInt(source, target, changed, "claiming.min-distance-from-town-homeblock", "town.min_distance_from_town_homeblock", "claiming.distance_rules.min_distance_from_town_homeblock")
        copyInt(source, target, changed, "claiming.min-distance-for-outpost", "town.min_distance_for_outpost_from_plot", "claiming.distance_rules.min_distance_for_outpost_from_plot")
        copyInt(source, target, changed, "claiming.max-claim-radius", "town.max_claim_radius_value", "claiming.max_claim_radius_value")
        copyInt(source, target, changed, "claiming.max-plots-per-resident", "town.max_plots_per_resident", "global_town_settings.max_plots_per_resident")

        copyBoolean(source, target, changed, "town-defaults.public", "town.default_public")
        copyBoolean(source, target, changed, "town-defaults.open", "town.default_open")
        copyBoolean(source, target, changed, "nation.require-ally-approval", "war.disallow_one_way_alliance", "disallow_one_way_alliance")
        copyBoolean(source, target, changed, "combat.friendly-fire", "global_town_settings.friendly_fire", "new_world_settings.pvp.friendly_fire_enabled")
        copySeconds(source, target, changed, "combat.pvp-toggle-cooldown", "global_town_settings.pvp_cooldown_time")

        copyNameList(source, target, changed, "protection.potion-types", "protection.potion_types")
        source.getString("protection.town_mob_removal_entities")?.let {
            consulted.add("protection.town_mob_removal_entities")
            target.set("protection.town-mob-removal.enabled", it.isNotBlank())
            changed.add("protection.town-mob-removal.enabled")
        }
        copySeconds(source, target, changed, "protection.town-mob-removal.interval", "protection.mob_removal_speed")
        copyBoolean(source, target, changed, "protection.town-mob-removal.skip-named", "protection.mob_removal_skips_named_mobs")
        copyBoolean(source, target, changed, "protection.health-regen.enabled", "global_town_settings.health_regen.enable")
        copySeconds(source, target, changed, "protection.health-regen.interval", "global_town_settings.health_regen.speed")

        copyNameList(source, target, changed, "wilderness.ignore", "unclaimed.unclaimed_zone_ignore")
        copyBoolean(source, target, changed, "board-on-login", "global_town_settings.display_board_onlogin")

        // "12h" after midnight -> "12:00"
        source.getString("plugin.day_timer.new_day_time")?.let { raw ->
            consulted.add("plugin.day_timer.new_day_time")
            val seconds = net.azisaba.townia.config.TownyRules.seconds(raw, -1)
            if (seconds in 0 until 86400) {
                target.set("new-day-time", String.format("%02d:%02d", seconds / 3600, seconds % 3600 / 60))
                changed.add("new-day-time")
            }
        }
    }

    /**
     * Towny kept one file per world. Townia only needs to know in which worlds it is active
     * (Towny: usingTowny) and in which PvP is off altogether.
     */
    private fun migrateWorlds(plugin: Townia, target: YamlConfiguration, changed: MutableList<String>) {
        val dir = File(TownyFlatfileMigrator.findTownyDataDir(plugin) ?: return, "worlds")
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("txt", ignoreCase = true) } ?: return
        val active = ArrayList<String>()
        val noPvp = ArrayList<String>()
        for (file in files.sortedBy { it.name }) {
            val values = HashMap<String, String>()
            runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList()).forEach { line ->
                val at = line.indexOf('=')
                if (at > 0) values[line.substring(0, at).trim().lowercase()] = line.substring(at + 1).trim()
            }
            if (!values["usingtowny"].equals("true", ignoreCase = true)) continue
            active.add(file.nameWithoutExtension)
            if (values["pvp"].equals("false", ignoreCase = true) && !values["forcepvp"].equals("true", ignoreCase = true)) {
                noPvp.add(file.nameWithoutExtension)
            }
        }
        if (active.isEmpty()) return
        target.set("allowed-worlds", active)
        if ("allowed-worlds" !in changed) changed.add("allowed-worlds")
        target.set("combat.pvp-disabled-worlds", noPvp)
        changed.add("combat.pvp-disabled-worlds")
    }

    /** Towny durations such as "30", "3s" or "5m", stored as whole seconds. */
    private fun copySeconds(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>, targetPath: String, sourcePath: String) {
        val raw = source.getString(sourcePath) ?: return
        consulted.add(sourcePath)
        val seconds = net.azisaba.townia.config.TownyRules.seconds(raw, -1)
        if (seconds < 0) return
        target.set(targetPath, seconds)
        changed.add(targetPath)
    }

    /** Towny lists are one comma-separated string. */
    private fun copyNameList(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>, targetPath: String, sourcePath: String, lower: Boolean = false) {
        if (!source.contains(sourcePath)) return
        consulted.add(sourcePath)
        val names = source.getStringList(sourcePath).ifEmpty { source.getString(sourcePath)?.split(',') ?: emptyList() }
            .map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (lower) it.lowercase(java.util.Locale.ROOT) else it }
        target.set(targetPath, names)
        changed.add(targetPath)
    }

    private fun findTownyConfig(plugin: Townia): File? {
        val pluginsDir = plugin.dataFolder.parentFile ?: return null
        val townyDir = File(pluginsDir, "Towny")
        return listOf(
            File(townyDir, "settings/config.yml"),
            File(townyDir, "config.yml"),
            File(townyDir, "global.yml"),
            File(plugin.dataFolder, "towny-config.yml")
        ).firstOrNull { it.exists() }
    }

    private fun copyDouble(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>, targetPath: String, vararg sourcePaths: String) {
        val path = sourcePaths.firstOrNull { source.contains(it) } ?: return
        consulted.add(path)
        // Towny 0.96 quotes every value ('250.0'); Bukkit's getDouble would read a string as 0.
        val value = source.getString(path)?.trim()?.replace(",", "")?.toDoubleOrNull() ?: return
        target.set(targetPath, value)
        changed.add(targetPath)
    }

    private fun copyInt(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>, targetPath: String, vararg sourcePaths: String) {
        val path = sourcePaths.firstOrNull { source.contains(it) } ?: return
        consulted.add(path)
        val value = source.getString(path)?.trim()?.toDoubleOrNull()?.toInt() ?: return
        target.set(targetPath, value)
        changed.add(targetPath)
    }

    private fun copyBoolean(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>, targetPath: String, vararg sourcePaths: String) {
        val path = sourcePaths.firstOrNull { source.contains(it) } ?: return
        consulted.add(path)
        val value = when (source.getString(path)?.trim()?.lowercase()) {
            "true", "yes", "on" -> true
            "false", "no", "off" -> false
            else -> return
        }
        target.set(targetPath, value)
        changed.add(targetPath)
    }

    /** A Towny switch, which may be a real boolean or the quoted text 'true'. */
    private fun flag(source: YamlConfiguration, path: String): Boolean =
        source.getString(path)?.trim().equals("true", ignoreCase = true)

    private fun copyPermMatrix(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>, targetPath: String, sourcePath: String) {
        if (!source.contains(sourcePath)) return
        listOf("build", "destroy", "switch", "item_use").forEach { consulted.add("$sourcePath.$it") }
        var perms = ""
        if (flag(source, "$sourcePath.build")) perms += "B"
        if (flag(source, "$sourcePath.destroy")) perms += "D"
        if (flag(source, "$sourcePath.switch")) perms += "S"
        if (flag(source, "$sourcePath.item_use")) perms += "I"
        target.set(targetPath, perms)
        changed.add(targetPath)
    }

    private fun copyCompatValues(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>) {
        val paths = listOf(
            "version.version",
            "version.last_run_version",
            "economy.using_economy",
            "economy.new_expand.price_outpost",
            "economy.new_expand.price_reclaim_ruined_town",
            "economy.new_expand.price_purchased_bonus_townblock",
            "economy.new_expand.price_claim_townblock_increase",
            "economy.new_expand.max_price_claim_townblock",
            "economy.new_expand.price_claim_townblock_refund",
            "economy.daily_taxes.enabled",
            "economy.daily_taxes.price_nation_upkeep",
            "economy.daily_taxes.nation_per_town_upkeep",
            "economy.daily_taxes.town_plotbased_upkeep",
            "economy.daily_taxes.town_plotbased_upkeep_affected_by_town_level_modifier",
            "economy.daily_taxes.max_town_tax_amount",
            "economy.daily_taxes.max_nation_tax_amount",
            "economy.daily_taxes.max_town_tax_percent",
            "economy.daily_taxes.max_nation_tax_percent",
            "economy.daily_taxes.per_outpost_cost",
            "global_town_settings.allow_outposts",
            "global_town_settings.allow_town_spawn",
            "global_town_settings.allow_other_town_spawn",
            "global_town_settings.allow_town_spawn_travel",
            "global_town_settings.allow_nation_spawn",
            "global_town_settings.allow_public_town_spawn_travel",
            "global_town_settings.allow_town_spawn_if_in_own_town",
            "global_town_settings.allow_town_spawn_if_in_allied_town",
            "global_town_settings.allow_town_spawn_if_neutral",
            "global_town_settings.allow_town_spawn_if_enemy",
            "global_town_settings.town_respawn",
            "global_town_settings.is_unlimited",
            "global_town_settings.max_plots_per_resident",
            "global_town_settings.max_claim_radius_value",
            "global_town_settings.min_distance_from_town_homeblocks",
            "global_town_settings.min_distance_from_town_plotblocks",
            "global_town_settings.outpost_min_distance_from_town_homeblocks",
            "global_town_settings.outpost_min_distance_from_town_plotblocks",
            "global_town_settings.warzone_min_distance_from_town_homeblocks",
            "global_town_settings.warzone_min_distance_from_town_plotblocks",
            "global_town_settings.town_plot_management_delete_time",
            "global_town_settings.delete_townblock_on_town_delete",
            "global_town_settings.delete_town_on_mayor_delete",
            "global_town_settings.default_public",
            "global_town_settings.default_open",
            "global_town_settings.default_town_board",
            "global_town_settings.default_tag",
            "global_town_settings.max_tag_length",
            "global_town_settings.min_tag_length",
            "global_town_settings.over_claiming",
            "global_town_settings.ruined_towns",
            "global_town_settings.outlaw_teleport_warmup",
            "global_nation_settings.default_public",
            "global_nation_settings.default_open",
            "global_nation_settings.max_tag_length",
            "global_nation_settings.min_tag_length",
            "global_nation_settings.capital_immune_to_town_delete",
            "protection.player_protection.outlaw_damage",
            "protection.player_protection.prevent_friendly_fire",
            "protection.player_protection.prevent_town_spawn_pvp",
            "town_mob_removal_entities",
            "mob_removal_entities",
            "jail.is_jailing_attacking_outlaws",
            "jail.is_allowing_bail",
            "jail.max_jail_time"
        )
        for (path in paths) {
            if (!source.contains(path)) continue
            target.set("towny-compat.settings.$path", serializableValue(source, path))
            changed.add("towny-compat.settings.$path")
        }
    }

    private fun serializableValue(source: YamlConfiguration, path: String): Any? {
        val section = source.getConfigurationSection(path)
        if (section != null) return sectionToMap(section)
        return source.get(path)
    }

    private fun sectionToMap(section: ConfigurationSection): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        for (key in section.getKeys(false)) {
            val child = section.getConfigurationSection(key)
            map[key] = child?.let { sectionToMap(it) } ?: section.get(key)
        }
        return map
    }

    private fun copyLevelList(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>, targetPath: String, sourcePath: String) {
        val levels = source.getMapList(sourcePath)
        if (levels.isEmpty()) return
        consulted.add(sourcePath)
        val mapped = levels.map { level ->
            LinkedHashMap<String, Any?>().apply {
                put("numResidents", (level["numResidents"] as? Number)?.toInt() ?: level["numResidents"]?.toString()?.toIntOrNull() ?: 0)
                put("namePrefix", level["namePrefix"] ?: "")
                put("namePostfix", level["namePostfix"] ?: "")
                put("mayorPrefix", level["mayorPrefix"] ?: "")
                put("mayorPostfix", level["mayorPostfix"] ?: "")
                put("kingPrefix", level["kingPrefix"] ?: "")
                put("kingPostfix", level["kingPostfix"] ?: "")
                put("capitalPrefix", level["capitalPrefix"] ?: "")
                put("capitalPostfix", level["capitalPostfix"] ?: "")
                put("townBlockLimit", (level["townBlockLimit"] as? Number)?.toInt() ?: level["townBlockLimit"]?.toString()?.toIntOrNull() ?: 0)
                put("townOutpostLimit", (level["townOutpostLimit"] as? Number)?.toInt() ?: level["townOutpostLimit"]?.toString()?.toIntOrNull() ?: 0)
                put("townBlockLimitBonus", (level["townBlockLimitBonus"] as? Number)?.toInt() ?: level["townBlockLimitBonus"]?.toString()?.toIntOrNull() ?: 0)
                put("nationBonusOutpostLimit", (level["nationBonusOutpostLimit"] as? Number)?.toInt() ?: level["nationBonusOutpostLimit"]?.toString()?.toIntOrNull() ?: 0)
            }.filterValues { it != null }
        }
        target.set(targetPath, mapped)
        changed.add(targetPath)
    }

    private fun migrateDatabaseConfig(plugin: Townia, townyConfig: File, target: YamlConfiguration, changed: MutableList<String>) {
        val townyDir = townyConfig.parentFile ?: return
        val townyRoot = if (townyDir.name.equals("settings", ignoreCase = true)) townyDir.parentFile ?: townyDir else townyDir
        val databaseFile = listOf(File(townyDir, "database.yml"), File(townyRoot, "database.yml")).firstOrNull { it.exists() } ?: return
        val database = YamlConfiguration.loadConfiguration(databaseFile)
        val load = database.getString("database.database_load", "")
        val save = database.getString("database.database_save", "")
        target.set("towny-compat.database.source-file", databaseFile.path)
        target.set("towny-compat.database.database_load", load)
        target.set("towny-compat.database.database_save", save)
        changed.add("towny-compat.database")

        if (!load.equals("mysql", ignoreCase = true) && !save.equals("mysql", ignoreCase = true)) return

        target.set("database.type", "mysql")
        target.set("database.host", database.getString("database.sql.hostname", target.getString("database.host", "localhost")))
        target.set("database.port", database.getInt("database.sql.port", target.getInt("database.port", 3306)))
        target.set("database.database", database.getString("database.sql.dbname", target.getString("database.database", "townia")))
        target.set("database.username", database.getString("database.sql.username", target.getString("database.username", "root")))
        target.set("database.password", database.getString("database.sql.password", target.getString("database.password", "")))
        target.set("database.pool-size", database.getInt("database.sql.pooling.max_pool_size", target.getInt("database.pool-size", 10)))
        changed.add("database.type")
        changed.add("database.host")
        changed.add("database.port")
        changed.add("database.database")
        changed.add("database.username")
        changed.add("database.password")
        changed.add("database.pool-size")
    }

    private fun saveCompatFiles(plugin: Townia, townyConfig: File, source: YamlConfiguration, changed: MutableList<String>) {
        val townyDir = townyConfig.parentFile ?: return
        val townyRoot = if (townyDir.name.equals("settings", ignoreCase = true)) townyDir.parentFile ?: townyDir else townyDir
        val compat = File(plugin.dataFolder, "towny-compat")
        if (!compat.exists()) compat.mkdirs()
        townyConfig.copyTo(File(compat, "config.yml"), overwrite = true)
        changed.add("towny-compat/config.yml")
        listOf("ChatConfig.yml", "Channels.yml", "townyperms.yml", "japanese.yml", "english.yml", "database.yml").forEach { name ->
            val file = listOf(File(townyDir, name), File(townyRoot, name)).firstOrNull { it.exists() }
            if (file != null) {
                file.copyTo(File(compat, name), overwrite = true)
                changed.add("towny-compat/$name")
            }
        }
        copyCompatDirectory(File(townyDir, "lang/override"), File(compat, "lang/override"), changed, "towny-compat/lang/override")
        copyCompatDirectory(File(townyRoot, "lang/override"), File(compat, "lang/override"), changed, "towny-compat/lang/override")
        val chat = File(compat, "chat-summary.yml")
        val channels = listOf(File(townyDir, "Channels.yml"), File(townyRoot, "Channels.yml")).firstOrNull { it.exists() }
        if (channels != null) {
            val channelConfig = YamlConfiguration.loadConfiguration(channels)
            val summary = YamlConfiguration()
            summary.set("channels", channelConfig.getConfigurationSection("Channels")?.getKeys(false)?.toList() ?: emptyList<String>())
            summary.save(chat)
        }
        if (source.contains("permissions")) {
            val summary = File(compat, "migration-notes.txt")
            summary.writeText("Towny permissions/chat files are preserved here for manual LuckPerms/chat-plugin mapping.\n", Charsets.UTF_8)
        }
    }

    private fun copyCompatDirectory(source: File, target: File, changed: MutableList<String>, changedPrefix: String) {
        if (!source.isDirectory) return
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = source.toPath().relativize(file.toPath()).toString()
            val out = File(target, relative)
            out.parentFile?.mkdirs()
            file.copyTo(out, overwrite = true)
            changed.add("$changedPrefix/${relative.replace(File.separatorChar, '/')}")
        }
    }

    private fun saveMigrationReport(plugin: Townia, townyConfig: File, source: YamlConfiguration, changed: List<String>) {
        val compat = File(plugin.dataFolder, "towny-compat")
        if (!compat.exists()) compat.mkdirs()
        val report = YamlConfiguration()
        report.set("source.file", townyConfig.path)
        report.set("source.version", source.getString("version.version") ?: source.getString("version"))
        report.set("active-targets", changed.filterNot { it.startsWith("towny-compat/") }.sorted())
        report.set("preserved.raw-config", "config.yml")
        report.set("preserved.raw-files", changed.filter { it.startsWith("towny-compat/") }.sorted())
        report.set("preserved.keys", flattenKeys(source).sorted())
        report.set("chat.enabled", false)
        report.set("chat.note", "ChatConfig.yml and Channels.yml are copied when present, but chat behavior is intentionally not enabled.")
        report.save(File(compat, "migration-report.yml"))
    }

    private fun flattenKeys(section: ConfigurationSection, prefix: String = ""): List<String> {
        val keys = ArrayList<String>()
        for (key in section.getKeys(false)) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val child = section.getConfigurationSection(key)
            if (child == null) {
                keys.add(path)
            } else {
                keys.addAll(flattenKeys(child, path))
            }
        }
        return keys
    }
}
