package net.azisaba.townia.config;

import net.azisaba.townia.Townia;

import java.util.List;

/** Typed wrapper around the plugin's config.yml. Reload via {@link #reload()}. */
public class TowniaConfig {

    private final Townia plugin;

    private String prefix;
    private String defaultLanguage;
    private List<String> allowedWorlds;
    private int defaultClaimLimit;
    private int claimsPerResident;
    private int maxBonusClaims;
    private int inviteTimeout;
    private double townCreationCost;
    private double nationCreationCost;
    private double townUpkeep;
    private double defaultTownTax;
    private double defaultNationTax;

    public TowniaConfig(Townia plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        prefix           = plugin.getConfig().getString("prefix", "<gray>[<green>Townia<gray>]<reset> ");
        defaultLanguage  = plugin.getConfig().getString("default-language", "ja");
        allowedWorlds    = plugin.getConfig().getStringList("allowed-worlds");
        defaultClaimLimit= plugin.getConfig().getInt("default-claim-limit", 8);
        claimsPerResident= plugin.getConfig().getInt("claims-per-resident", 1);
        maxBonusClaims   = plugin.getConfig().getInt("max-bonus-claims", 100);
        inviteTimeout    = plugin.getConfig().getInt("invite-timeout", 120);
        townCreationCost = plugin.getConfig().getDouble("town-creation-cost", 0.0);
        nationCreationCost = plugin.getConfig().getDouble("nation-creation-cost", 0.0);
        townUpkeep       = plugin.getConfig().getDouble("town-upkeep", 0.0);
        defaultTownTax   = plugin.getConfig().getDouble("default-town-tax", 0.0);
        defaultNationTax = plugin.getConfig().getDouble("default-nation-tax", 0.0);

        if (allowedWorlds.isEmpty()) {
            allowedWorlds = List.of("world");
        }
    }

    public boolean isPvpEnabled(String plotTypeName) {
        return plugin.getConfig().getBoolean("pvp." + plotTypeName.toLowerCase(), false);
    }

    public String getPrefix() { return prefix; }
    public String getDefaultLanguage() { return defaultLanguage; }
    public List<String> getAllowedWorlds() { return allowedWorlds; }
    public boolean isWorldAllowed(String worldName) { return allowedWorlds.contains(worldName); }
    public int getDefaultClaimLimit() { return defaultClaimLimit; }
    public int getClaimsPerResident() { return claimsPerResident; }
    public int getMaxBonusClaims() { return maxBonusClaims; }
    public int getInviteTimeout() { return inviteTimeout; }
    public double getTownCreationCost() { return townCreationCost; }
    public double getNationCreationCost() { return nationCreationCost; }
    public double getTownUpkeep() { return townUpkeep; }
    public double getDefaultTownTax() { return defaultTownTax; }
    public double getDefaultNationTax() { return defaultNationTax; }
}
