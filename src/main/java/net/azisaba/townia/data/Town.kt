package net.azisaba.townia.data

import net.azisaba.townia.Townia
import java.util.*

class Town(
    val id: UUID?, var name: String?, var mayorUuid: UUID?, var nationUuid: UUID?,
    var balance: Double, var claimLimit: Int, var bonusClaims: Int, var isPublic: Boolean,
    val createdAt: Long,
    var board: String?, var taxes: Double, var plotPrice: Double,
    private var pvp: Boolean, private var mobs: Boolean, private var explosions: Boolean, private var fire: Boolean,
    // Spawn point
    var spawnWorld: String?, var spawnX: Double, var spawnY: Double, var spawnZ: Double,
    var spawnYaw: Float, var spawnPitch: Float
) {
    var isOpen: Boolean = false

    // Home block
    var homeBlockWorld: String? = null
        private set
    var homeBlockX: Int = 0
        private set
    var homeBlockZ: Int = 0
        private set

    var permsResident: String? = "BDSI"
    var permsAlly: String? = ""
    var permsOutsider: String? = ""
    var permsNation: String? = ""

    var dailyUpkeep: Double = 0.0

    var tag: String? = null
    var isTaxPercent: Boolean = false
    var plotTax: Double = 0.0
    var shopPrice: Double = 0.0
    var shopTax: Double = 0.0
    var embassyPrice: Double = 0.0
    var embassyTax: Double = 0.0

    var isAllowInvisibility: Boolean = true
    var isAllowSit: Boolean = true
    var isAllowPetPickup: Boolean = true
    var isAllowPassenger: Boolean = true


    val outposts: MutableList<TowniaOutpost?> = ArrayList<TowniaOutpost?>()

    val totalClaimLimit: Int
        get() {
            val resCount = Townia.instance.residentManager.getResidentsByTown(id!!).size
            val townLevel = Townia.instance.towniaConfig.getTownLevel(resCount)
            val baseLimit = townLevel?.townBlockLimit ?: claimLimit
            
            var nationBonus = 0
            if (isInNation) {
                val nationOpt = Townia.instance.nationManager.getNation(nationUuid!!)
                if (nationOpt.isPresent) {
                    nationBonus = nationOpt.get().getTownBlockLimitBonus()
                }
            }
            
            return baseLimit + bonusClaims + nationBonus
        }

    fun getFormattedName(sender: org.bukkit.command.CommandSender? = null): String {
        val resCount = Townia.instance.residentManager.getResidentsByTown(id!!).size
        val townLevel = Townia.instance.towniaConfig.getTownLevel(resCount)
        val prefix = townLevel?.namePrefix ?: ""
        val postfix = townLevel?.namePostfix ?: ""
        return "${Townia.instance.messageManager.processTranslationKeys(prefix, sender)}${name ?: ""}${Townia.instance.messageManager.processTranslationKeys(postfix, sender)}"
    }

    fun getMayorPrefix(sender: org.bukkit.command.CommandSender? = null): String {
        val resCount = Townia.instance.residentManager.getResidentsByTown(id!!).size
        val townLevel = Townia.instance.towniaConfig.getTownLevel(resCount)
        val prefix = townLevel?.mayorPrefix ?: ""
        return Townia.instance.messageManager.processTranslationKeys(prefix, sender)
    }

    fun getMayorPostfix(sender: org.bukkit.command.CommandSender? = null): String {
        val resCount = Townia.instance.residentManager.getResidentsByTown(id!!).size
        val townLevel = Townia.instance.towniaConfig.getTownLevel(resCount)
        val postfix = townLevel?.mayorPostfix ?: ""
        return Townia.instance.messageManager.processTranslationKeys(postfix, sender)
    }

    fun hasPvp(): Boolean {
        return pvp
    }

    fun hasMobs(): Boolean {
        return mobs
    }

    fun hasExplosions(): Boolean {
        return explosions
    }

    fun hasFire(): Boolean {
        return fire
    }

    val isInNation: Boolean
        get() = nationUuid != null

    fun hasSpawn(): Boolean {
        return spawnWorld != null
    }

    fun hasHomeBlock(): Boolean {
        return homeBlockWorld != null
    }

    val townSizeRank: String
        get() {
            val count: Int = Townia.instance.residentManager.getResidentsByTown(id!!).size
            if (count >= 28) return "MetroPolis"
            if (count >= 24) return "Large City"
            if (count >= 20) return "City"
            if (count >= 14) return "Large Town"
            if (count >= 10) return "Town"
            if (count >= 6) return "Village"
            if (count >= 2) return "Hamlet"
            return "Settlement"
        }

    fun setPvp(pvp: Boolean) {
        this.pvp = pvp
    }

    fun setMobs(mobs: Boolean) {
        this.mobs = mobs
    }

    fun setExplosions(explosions: Boolean) {
        this.explosions = explosions
    }

    fun setFire(fire: Boolean) {
        this.fire = fire
    }

    fun setSpawn(world: String?, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        this.spawnWorld = world
        this.spawnX = x
        this.spawnY = y
        this.spawnZ = z
        this.spawnYaw = yaw
        this.spawnPitch = pitch
    }

    fun setHomeBlock(world: String?, x: Int, z: Int) {
        this.homeBlockWorld = world
        this.homeBlockX = x
        this.homeBlockZ = z
    }

    constructor(
        id: UUID?, name: String?, mayorUuid: UUID?, nationUuid: UUID?,
        balance: Double, claimLimit: Int, bonusClaims: Int, isPublic: Boolean,
        createdAt: Long, board: String?, taxes: Double, plotPrice: Double,
        pvp: Boolean, mobs: Boolean, explosions: Boolean, fire: Boolean,
        spawnWorld: String?, spawnX: Double, spawnY: Double, spawnZ: Double,
        spawnYaw: Float, spawnPitch: Float,
        homeBlockWorld: String?, homeBlockX: Int, homeBlockZ: Int,
        permsResident: String?, permsAlly: String?, permsOutsider: String?, permsNation: String?,
        dailyUpkeep: Double
    ) : this(
        id, name, mayorUuid, nationUuid, balance, claimLimit, bonusClaims, isPublic, createdAt,
        board, taxes, plotPrice, pvp, mobs, explosions, fire, spawnWorld, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch
    ) {
        this.homeBlockWorld = homeBlockWorld
        this.homeBlockX = homeBlockX
        this.homeBlockZ = homeBlockZ
        this.permsResident = permsResident
        this.permsAlly = permsAlly
        this.permsOutsider = permsOutsider
        this.permsNation = permsNation
        this.dailyUpkeep = dailyUpkeep
    }
}