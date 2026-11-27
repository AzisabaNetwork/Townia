package net.azisaba.townia.config

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.potion.PotionEffectType
import java.time.LocalTime
import java.util.EnumSet
import java.util.Locale

/**
 * Rules Towny kept in its config.yml (prices, spawn limits, claim distances, ...).
 *
 * Every default here means "off", so a server that never set these keys behaves exactly as
 * Townia did before. `/towniaadmin migrate config` fills them in from a Towny config.yml.
 */
class TownyRules(config: ConfigurationSection) {

    // ---- economy
    /** Each claim costs `claim-cost x increase^(plots the town already has)`. 1.0 keeps the price flat. */
    val claimCostIncrease: Double = config.getDouble("economy.claim-cost-increase", 1.0).takeIf { it > 0 } ?: 1.0
    /** Price of an outpost. Negative: an outpost costs the same as any other claim. */
    val outpostCost: Double = config.getDouble("economy.outpost-cost", -1.0)
    /** Price of one bonus claim bought with /town buy bonus. Negative: buying is off. */
    val bonusBlockCost: Double = config.getDouble("economy.bonus-block-cost", -1.0)
    val bonusBlockCostIncrease: Double = config.getDouble("economy.bonus-block-cost-increase", 1.0).takeIf { it > 0 } ?: 1.0
    val nationUpkeep: Double = config.getDouble("economy.nation-upkeep", 0.0)
    /** Charged daily to a nation that is neutral; it loses neutrality when it cannot pay. */
    val neutralityCost: Double = config.getDouble("economy.neutrality-cost", 0.0)
    /** Highest flat tax a town may set. 0: no limit. */
    val maxTaxAmount: Double = config.getDouble("economy.max-tax-amount", 0.0)
    val maxTaxPercent: Double = config.getDouble("economy.max-tax-percent", 100.0)

    /** "fixed" takes the amounts below; "percentage" takes them as a share of the balance. */
    val deathPercentage: Boolean = config.getString("economy.death.type", "fixed").equals("percentage", ignoreCase = true)
    val deathPercentageCap: Double = config.getDouble("economy.death.percentage-cap", 0.0)
    val deathPvpOnly: Boolean = config.getBoolean("economy.death.pvp-only", false)
    val deathPrice: Double = config.getDouble("economy.death.price", 0.0)
    val deathTownPrice: Double = config.getDouble("economy.death.town-price", 0.0)
    val deathNationPrice: Double = config.getDouble("economy.death.nation-price", 0.0)
    val hasDeathCosts: Boolean get() = deathPrice > 0 || deathTownPrice > 0 || deathNationPrice > 0

    val spawnPriceTown: Double = config.getDouble("economy.spawn-travel.town", 0.0)
    val spawnPriceNation: Double = config.getDouble("economy.spawn-travel.nation", 0.0)
    val spawnPriceAlly: Double = config.getDouble("economy.spawn-travel.ally", 0.0)
    val spawnPricePublic: Double = config.getDouble("economy.spawn-travel.public", 0.0)
    val spawnPaidToTown: Boolean = config.getBoolean("economy.spawn-travel.paid-to-town", true)

    // ---- spawn
    val spawnCooldownSeconds: Int = config.getInt("spawn.cooldown", 0)
    val spawnWarmupSeconds: Int = config.getInt("spawn.warmup", 0)
    val allowSpawnTravel: Boolean = config.getBoolean("spawn.allow-travel", true)
    val allowSpawnTravelNation: Boolean = config.getBoolean("spawn.allow-travel-nation", true)
    val allowSpawnTravelAlly: Boolean = config.getBoolean("spawn.allow-travel-ally", true)
    /** Where /town spawn cannot be used from: "enemy" (a town of an enemy nation), "outlaw", "unclaimed". */
    val preventSpawnIn: Set<String> = config.getStringList("spawn.prevent-in").map { it.trim().lowercase(Locale.ROOT) }.toSet()

    // ---- claiming (distances in chunks, 0 = no rule)
    val minDistanceFromTownPlot: Int = config.getInt("claiming.min-distance-from-town-plot", 0)
    val minDistanceFromTownHomeblock: Int = config.getInt("claiming.min-distance-from-town-homeblock", 0)
    val minDistanceForOutpost: Int = config.getInt("claiming.min-distance-for-outpost", 0)
    val maxClaimRadius: Int = config.getInt("claiming.max-claim-radius", 10).coerceAtLeast(1)
    /** How many plots one resident may own. 0: no limit. */
    val maxPlotsPerResident: Int = config.getInt("claiming.max-plots-per-resident", 0)

    // ---- combat
    /** False: members of the same town, nation or allied nations cannot hurt each other outside arenas. */
    val friendlyFire: Boolean = config.getBoolean("combat.friendly-fire", true)
    val pvpToggleCooldownSeconds: Int = config.getInt("combat.pvp-toggle-cooldown", 0)

    /** Worlds in which nobody can hurt another player, towns included (Towny's per-world pvp=false). */
    val pvpDisabledWorlds: Set<String> = config.getStringList("combat.pvp-disabled-worlds").toSet()

    // ---- protection
    /** Splash and lingering potions with these effects do nothing to players where PvP is off. */
    val protectedPotions: Set<PotionEffectType> = config.getStringList("protection.potion-types").mapNotNull(::potion).toSet()
    val townMobRemoval: Boolean = config.getBoolean("protection.town-mob-removal.enabled", false)
    val townMobRemovalSeconds: Int = config.getInt("protection.town-mob-removal.interval", 5).coerceAtLeast(1)
    val healthRegen: Boolean = config.getBoolean("protection.health-regen.enabled", false)
    val healthRegenSeconds: Int = config.getInt("protection.health-regen.interval", 3).coerceAtLeast(1)

    // ---- wilderness
    /** Blocks anyone may place and break in the wilderness even where building there is off. */
    val wildernessIgnore: Set<Material> = materials(config.getStringList("wilderness.ignore"))

    /** True: an alliance needs the other nation's consent (Towny's disallow_one_way_alliance) and holds both ways. */
    val allyApproval: Boolean = config.getBoolean("nation.require-ally-approval", false)

    /** Whether a newly founded town starts public (spawn travel) and open (join without invite). Towny: true / false. */
    val newTownPublic: Boolean = config.getBoolean("town-defaults.public", false)
    val newTownOpen: Boolean = config.getBoolean("town-defaults.open", false)

    // ---- misc
    val boardOnLogin: Boolean = config.getBoolean("board-on-login", false)
    /** Time of day ("12:00") taxes and upkeep are collected. Null: every 24 hours after the server started. */
    val newDayTime: LocalTime? = config.getString("new-day-time")?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { LocalTime.parse(if (it.length == 4) "0$it" else it) }.getOrNull() }

    /** What [amount] more bonus claims cost a town that already has [owned] of them. */
    fun bonusBlockPrice(owned: Int, amount: Int): Double {
        if (bonusBlockCost <= 0.0) return 0.0
        if (bonusBlockCostIncrease == 1.0) return bonusBlockCost * amount
        var total = 0.0
        for (i in 0 until amount) total += Math.round(Math.pow(bonusBlockCostIncrease, (owned + i).toDouble()) * bonusBlockCost)
        return total
    }

    /** What a town with [ownedPlots] claims pays for its next one. */
    fun claimPrice(baseCost: Double, ownedPlots: Int): Double {
        if (baseCost <= 0.0) return 0.0
        if (claimCostIncrease == 1.0) return baseCost
        return Math.round(Math.pow(claimCostIncrease, ownedPlots.toDouble()) * baseCost).toDouble()
    }

    companion object {
        /** Names that are not blocks or items of this Minecraft version are skipped (old Towny lists carry some). */
        fun materials(names: Collection<String>): Set<Material> {
            val result = EnumSet.noneOf(Material::class.java)
            for (name in names) {
                val material = runCatching { Material.matchMaterial(name.trim()) }.getOrNull() ?: continue
                result.add(material)
            }
            return result
        }

        /** Towny wrote Bukkit's old effect names (CONFUSION, HARM, SLOW, SLOW_DIGGING). */
        fun potion(name: String): PotionEffectType? {
            val key = when (val upper = name.trim().uppercase(Locale.ROOT)) {
                "CONFUSION" -> "nausea"
                "HARM" -> "instant_damage"
                "SLOW" -> "slowness"
                "SLOW_DIGGING" -> "mining_fatigue"
                else -> upper.lowercase(Locale.ROOT)
            }
            return runCatching {
                org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(key))
            }.getOrNull()
        }

        /** Towny durations: "3s", "5m", "12h", "1d", or plain seconds. */
        fun seconds(raw: String?, fallback: Int): Int {
            val text = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return fallback
            val unit = when (text.last()) {
                's' -> 1
                'm' -> 60
                'h' -> 3600
                'd' -> 86400
                else -> return text.toDoubleOrNull()?.toInt() ?: fallback
            }
            return ((text.dropLast(1).toDoubleOrNull() ?: return fallback) * unit).toInt()
        }
    }
}
