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
        townCreationCost   = config.getDouble("town-creation-cost", 0.0)
        nationCreationCost = config.getDouble("nation-creation-cost", 0.0)
        townUpkeep         = config.getDouble("town-upkeep", 0.0)
        defaultTownTax     = config.getDouble("default-town-tax", 0.0)
        defaultNationTax   = config.getDouble("default-nation-tax", 0.0)

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
}