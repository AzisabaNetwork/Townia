package net.azisaba.townia.config

import net.azisaba.townia.Townia

class TowniaConfig(private val plugin: Townia) {

    var prefix: String = ""
        private set
    var defaultLanguage: String = ""
        private set
    var allowedWorlds: List<String> = emptyList()
        private set
    var defaultClaimLimit: Int = 0
        private set
    var claimsPerResident: Int = 0
        private set
    var maxBonusClaims: Int = 0
        private set
    var inviteTimeout: Int = 0
        private set
    var nationBonusClaims: Int = 0
        private set
    var townCreationCost: Double = 0.0
        private set
    var nationCreationCost: Double = 0.0
        private set
    var townUpkeep: Double = 0.0
        private set
    var defaultTownTax: Double = 0.0
        private set
    var defaultNationTax: Double = 0.0
        private set
    var townLevels: List<TownLevel> = emptyList()
        private set
    var nationLevels: List<NationLevel> = emptyList()
        private set

    val claimCost: Double
        get() = plugin.config.getDouble("claim-cost", 0.0)

    init {
        reload()
    }

    fun reload() {
        plugin.reloadConfig()
        var config = plugin.config

        val internalConfigStream = plugin.getResource("config.yml")
        if (internalConfigStream != null) {
            java.io.InputStreamReader(internalConfigStream, Charsets.UTF_8).use { reader ->
                val internalConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader)
                val internalVersion = internalConfig.getInt("version", 1)
                val currentVersion = config.getInt("version", 0)

                if (currentVersion < internalVersion) {
                    plugin.logger.info("Outdated config.yml detected (v$currentVersion < v$internalVersion). Updating...")
                    val configFile = java.io.File(plugin.dataFolder, "config.yml")
                    if (configFile.exists()) {
                        val bakFile = java.io.File(plugin.dataFolder, "config.yml.v$currentVersion.bak")
                        configFile.renameTo(bakFile)
                    }
                    plugin.saveResource("config.yml", true)
                    plugin.reloadConfig()
                    config = plugin.config
                }
            }
        }

        prefix             = config.getString("prefix", "<gray>[<green>Townia<gray>]<reset> ") ?: ""
        defaultLanguage    = config.getString("default-language", "ja") ?: "ja"
        allowedWorlds      = config.getStringList("allowed-worlds")
        defaultClaimLimit  = config.getInt("default-claim-limit", 8)
        claimsPerResident  = config.getInt("claims-per-resident", 1)
        maxBonusClaims     = config.getInt("max-bonus-claims", 100)
        inviteTimeout      = config.getInt("invite-timeout", 120)
        nationBonusClaims  = config.getInt("nation-bonus-claims", 10)
        townCreationCost   = config.getDouble("town-creation-cost", 0.0)
        nationCreationCost = config.getDouble("nation-creation-cost", 0.0)
        townUpkeep         = config.getDouble("town-upkeep", 0.0)
        defaultTownTax     = config.getDouble("default-town-tax", 0.0)
        defaultNationTax   = config.getDouble("default-nation-tax", 0.0)

        // Parse Town Levels
        val tLevels = mutableListOf<TownLevel>()
        val townLevelMapList = config.getMapList("town_level")
        for (map in townLevelMapList) {
            tLevels.add(
                TownLevel(
                    numResidents = (map["numResidents"] as? Number)?.toInt() ?: 0,
                    namePrefix = map["namePrefix"] as? String ?: "",
                    namePostfix = map["namePostfix"] as? String ?: "",
                    mayorPrefix = map["mayorPrefix"] as? String ?: "",
                    mayorPostfix = map["mayorPostfix"] as? String ?: "",
                    townBlockLimit = (map["townBlockLimit"] as? Number)?.toInt() ?: 0,
                    townOutpostLimit = (map["townOutpostLimit"] as? Number)?.toInt() ?: 0
                )
            )
        }
        // Sort descending so we can just find the first one that matches
        tLevels.sortByDescending { it.numResidents }
        townLevels = tLevels

        // Parse Nation Levels
        val nLevels = mutableListOf<NationLevel>()
        val nationLevelMapList = config.getMapList("nation_level")
        for (map in nationLevelMapList) {
            nLevels.add(
                NationLevel(
                    numResidents = (map["numResidents"] as? Number)?.toInt() ?: 0,
                    namePrefix = map["namePrefix"] as? String ?: "",
                    namePostfix = map["namePostfix"] as? String ?: "",
                    kingPrefix = map["kingPrefix"] as? String ?: "",
                    kingPostfix = map["kingPostfix"] as? String ?: "",
                    capitalPrefix = map["capitalPrefix"] as? String ?: "",
                    capitalPostfix = map["capitalPostfix"] as? String ?: "",
                    townBlockLimitBonus = (map["townBlockLimitBonus"] as? Number)?.toInt() ?: 0,
                    nationBonusOutpostLimit = (map["nationBonusOutpostLimit"] as? Number)?.toInt() ?: 0
                )
            )
        }
        nLevels.sortByDescending { it.numResidents }
        nationLevels = nLevels

        if (allowedWorlds.isEmpty()) {
            allowedWorlds = listOf("world")
        }
    }

    fun isPvpEnabled(plotTypeName: String): Boolean {
        return plugin.config.getBoolean("pvp.${plotTypeName.lowercase()}", false)
    }

    fun isWorldAllowed(worldName: String): Boolean {
        return allowedWorlds.contains(worldName)
    }

    fun getTownLevel(numResidents: Int): TownLevel? {
        return townLevels.firstOrNull { numResidents >= it.numResidents }
    }

    fun getNationLevel(numResidents: Int): NationLevel? {
        return nationLevels.firstOrNull { numResidents >= it.numResidents }
    }
}