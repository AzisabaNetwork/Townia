package net.azisaba.townia.data

import java.util.*

class Plot(
    val worldName: String?, val chunkX: Int, val chunkZ: Int,
    var townUuid: UUID?,
    var ownerUuid: UUID?,
    var plotType: PlotType?, forSale: Boolean, var price: Double,
    var name: String?, pvp: Boolean, mobs: Boolean, explosions: Boolean, fire: Boolean,
    isOutpost: Boolean, permsResident: String?, permsAlly: String?, permsOutsider: String?, permsNation: String?,
    permsFriend: String? = null
) {
    /** Permissions for the owner's friends (only meaningful for resident-owned plots). */
    var permsFriend: String? = permsFriend
    var isForSale: Boolean = forSale

    private var pvp: Boolean
    private var mobs: Boolean
    private var explosions: Boolean
    private var fire: Boolean

    var isOutpost: Boolean
    var permsResident: String?
    var permsAlly: String?
    var permsOutsider: String?
    var permsNation: String?

    init {
        this.pvp = pvp
        this.mobs = mobs
        this.explosions = explosions
        this.fire = fire
        this.isOutpost = isOutpost
        this.permsResident = permsResident
        this.permsAlly = permsAlly
        this.permsOutsider = permsOutsider
        this.permsNation = permsNation
    }

    constructor(
        worldName: String?, chunkX: Int, chunkZ: Int,
        townUuid: UUID?, ownerUuid: UUID?,
        plotType: PlotType?, forSale: Boolean, price: Double,
        name: String?, pvp: Boolean, mobs: Boolean, explosions: Boolean, fire: Boolean
    ) : this(
        worldName,
        chunkX,
        chunkZ,
        townUuid,
        ownerUuid,
        plotType,
        forSale,
        price,
        name,
        pvp,
        mobs,
        explosions,
        fire,
        false,
        null,
        null,
        null,
        null
    )

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

    val isTownOwned: Boolean
        get() = ownerUuid == null

    /**
     * Unsets every plot-level perm so the plot inherits again. Call this whenever the owner
     * changes: perms set for (or by) the previous owner must not carry over to the next one.
     */
    fun clearPerms() {
        permsFriend = null
        permsResident = null
        permsAlly = null
        permsOutsider = null
        permsNation = null
    }

    /** Changes the owner and drops the previous owner's plot perms. */
    fun changeOwner(newOwnerUuid: UUID?) {
        if (ownerUuid != newOwnerUuid) clearPerms()
        ownerUuid = newOwnerUuid
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
}