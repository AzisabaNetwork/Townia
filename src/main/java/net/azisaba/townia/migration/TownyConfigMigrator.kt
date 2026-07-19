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

        copyDouble(source, target, changed, "town-creation-cost", "economy.new_expand.price_new_town", "price_new_town", "new_expand.price_new_town")
        copyDouble(source, target, changed, "nation-creation-cost", "economy.new_expand.price_new_nation", "price_new_nation", "new_expand.price_new_nation")
        copyDouble(source, target, changed, "claim-cost", "economy.new_expand.price_claim_townblock", "price_claim_townblock", "new_expand.price_claim_townblock")
        copyDouble(source, target, changed, "town-upkeep", "economy.daily_taxes.price_town_upkeep", "daily_taxes.price_town_upkeep", "town_upkeep")
        copyDouble(source, target, changed, "default-town-tax", "economy.daily_taxes.town_tax", "daily_taxes.town_tax", "town_tax")
        copyDouble(source, target, changed, "default-nation-tax", "economy.daily_taxes.nation_tax", "daily_taxes.nation_tax", "nation_tax")
        copyInt(source, target, changed, "default-claim-limit", "global_town_settings.town_block_limit", "global_town_settings.default_town_block_limit", "default_town_block_limit", "town_block_limit")
        copyInt(source, target, changed, "claims-per-resident", "global_town_settings.town_block_ratio", "town_block_ratio", "town_block_ratio_bonus")
        copyInt(source, target, changed, "max-bonus-claims", "global_town_settings.max_purchased_blocks", "max_purchased_blocks", "townBlockBuyBonusLimit")
        copyInt(source, target, changed, "invite-timeout", "invite_system.invite_timeout", "invite_timeout", "invite_timeout_time", "global_town_settings.invite_timeout")
        copyBoolean(source, target, changed, "wilderness.pvp", "new_world_settings.pvp.force_pvp_on", "wilderness_pvp", "pvp")
        copyBoolean(source, target, changed, "wilderness.build", "unclaimed.unclaimed_zone_build", "wilderness_build")
        copyBoolean(source, target, changed, "wilderness.destroy", "unclaimed.unclaimed_zone_destroy", "wilderness_destroy")
        copyBoolean(source, target, changed, "wilderness.switch", "unclaimed.unclaimed_zone_switch", "wilderness_switch")
        copyBoolean(source, target, changed, "wilderness.item-use", "unclaimed.unclaimed_zone_item_use", "wilderness_item_use")
        copyBoolean(source, target, changed, "wilderness.mobs", "new_world_settings.wilderness.wilderness_monsters_on", "new_world_settings.wilderness_monsters_on", "wilderness_mobs")
        copyBoolean(source, target, changed, "wilderness.explosions", "new_world_settings.explosion_revert.wilderness_explosion_revert", "wilderness_explosions")
        copyBoolean(source, target, changed, "wilderness.fire", "new_world_settings.fire.fire_spread", "wilderness_fire")
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
        copyLevelList(source, target, changed, "town_level", "levels.town_level")
        copyLevelList(source, target, changed, "nation_level", "levels.nation_level")
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

        alignConfigVersion(plugin, target, changed)
        target.save(targetFile)
        saveCompatFiles(plugin, townyConfig, source, changed)
        saveMigrationReport(plugin, townyConfig, source, changed)
        plugin.reloadConfig()
        plugin.towniaConfig.reload()
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
        target.set(targetPath, source.getDouble(path))
        changed.add(targetPath)
    }

    private fun copyInt(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>, targetPath: String, vararg sourcePaths: String) {
        val path = sourcePaths.firstOrNull { source.contains(it) } ?: return
        target.set(targetPath, source.getInt(path))
        changed.add(targetPath)
    }

    private fun copyBoolean(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>, targetPath: String, vararg sourcePaths: String) {
        val path = sourcePaths.firstOrNull { source.contains(it) } ?: return
        target.set(targetPath, source.getBoolean(path))
        changed.add(targetPath)
    }

    private fun copyPermMatrix(source: YamlConfiguration, target: YamlConfiguration, changed: MutableList<String>, targetPath: String, sourcePath: String) {
        if (!source.contains(sourcePath)) return
        var perms = ""
        if (source.getBoolean("$sourcePath.build")) perms += "B"
        if (source.getBoolean("$sourcePath.destroy")) perms += "D"
        if (source.getBoolean("$sourcePath.switch")) perms += "S"
        if (source.getBoolean("$sourcePath.item_use")) perms += "I"
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

    private fun saveCompatFiles(plugin: Townia, townyConfig: File, source: YamlConfiguration, changed: MutableList<String>) {
        val townyDir = townyConfig.parentFile ?: return
        val townyRoot = if (townyDir.name.equals("settings", ignoreCase = true)) townyDir.parentFile ?: townyDir else townyDir
        val compat = File(plugin.dataFolder, "towny-compat")
        if (!compat.exists()) compat.mkdirs()
        townyConfig.copyTo(File(compat, "config.yml"), overwrite = true)
        changed.add("towny-compat/config.yml")
        listOf("ChatConfig.yml", "Channels.yml", "townyperms.yml", "japanese.yml").forEach { name ->
            val file = listOf(File(townyDir, name), File(townyRoot, name)).firstOrNull { it.exists() }
            if (file != null) {
                file.copyTo(File(compat, name), overwrite = true)
                changed.add("towny-compat/$name")
            }
        }
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

    private fun saveMigrationReport(plugin: Townia, townyConfig: File, source: YamlConfiguration, changed: List<String>) {
        val compat = File(plugin.dataFolder, "towny-compat")
        if (!compat.exists()) compat.mkdirs()
        val report = YamlConfiguration()
        report.set("source.file", townyConfig.path)
        report.set("source.version", source.getString("version.version") ?: source.getString("version"))
        report.set("active-targets", changed.filterNot { it.startsWith("towny-compat/") }.sorted())
        report.set("preserved.raw-config", "config.yml")
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
