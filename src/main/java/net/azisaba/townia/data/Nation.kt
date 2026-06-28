package net.azisaba.townia.data

import java.util.*

class Nation @JvmOverloads constructor(
    val id: UUID?,
    var name: String?,
    var capitalTownUuid: UUID?,
    var leaderUuid: UUID?,
    var balance: Double,
    var board: String?,
    var taxes: Double,
    var spawnWorld: String? = null,
    var spawnX: Double = 0.0,
    var spawnY: Double = 0.0,
    var spawnZ: Double = 0.0,
    var spawnYaw: Float = 0f,
    var spawnPitch: Float = 0f
) {
    val allies: MutableSet<UUID?> = HashSet<UUID?>()
    val enemies: MutableSet<UUID?> = HashSet<UUID?>()

    var isNeutral: Boolean = false
    val titles: MutableMap<UUID?, String?> = HashMap<UUID?, String?>()
    val surnames: MutableMap<UUID?, String?> = HashMap<UUID?, String?>()

    fun getFormattedName(): String {
        val resCount = net.azisaba.townia.Townia.instance.residentManager.getResidentsByNation(id!!).size
        val nationLevel = net.azisaba.townia.Townia.instance.towniaConfig.getNationLevel(resCount)
        val prefix = nationLevel?.namePrefix ?: ""
        val postfix = nationLevel?.namePostfix ?: ""
        return "$prefix${name ?: ""}$postfix"
    }

    fun getKingPrefix(): String {
        val resCount = net.azisaba.townia.Townia.instance.residentManager.getResidentsByNation(id!!).size
        val nationLevel = net.azisaba.townia.Townia.instance.towniaConfig.getNationLevel(resCount)
        return nationLevel?.kingPrefix ?: ""
    }

    fun getKingPostfix(): String {
        val resCount = net.azisaba.townia.Townia.instance.residentManager.getResidentsByNation(id!!).size
        val nationLevel = net.azisaba.townia.Townia.instance.towniaConfig.getNationLevel(resCount)
        return nationLevel?.kingPostfix ?: ""
    }

    fun getTownBlockLimitBonus(): Int {
        val resCount = net.azisaba.townia.Townia.instance.residentManager.getResidentsByNation(id!!).size
        val nationLevel = net.azisaba.townia.Townia.instance.towniaConfig.getNationLevel(resCount)
        return nationLevel?.townBlockLimitBonus ?: 0
    }

    fun hasSpawn(): Boolean {
        return spawnWorld != null
    }


    fun setTitle(uuid: UUID?, title: String?) {
        this.titles[uuid] = title
    }

    fun removeTitle(uuid: UUID?) {
        this.titles.remove(uuid)
    }

    fun getTitle(uuid: UUID?): String? {
        return this.titles[uuid]
    }

    fun setSurname(uuid: UUID?, surname: String?) {
        this.surnames[uuid] = surname
    }

    fun removeSurname(uuid: UUID?) {
        this.surnames.remove(uuid)
    }

    fun getSurname(uuid: UUID?): String? {
        return this.surnames[uuid]
    }

    fun setSpawn(world: String?, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        this.spawnWorld = world
        this.spawnX = x
        this.spawnY = y
        this.spawnZ = z
        this.spawnYaw = yaw
        this.spawnPitch = pitch
    }
}