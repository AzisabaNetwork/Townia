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
    var tag: String? = null
    /** Public: outsiders may travel to the nation's spawn. */
    var isPublic: Boolean = false
    /** Open: a town may join without an invitation. */
    var isOpen: Boolean = false
    /** What outsiders pay to travel to the nation's spawn. Negative: the server's price applies. */
    var spawnCost: Double = -1.0
    val titles: MutableMap<UUID?, String?> = HashMap<UUID?, String?>()
    val surnames: MutableMap<UUID?, String?> = HashMap<UUID?, String?>()

    fun getFormattedName(sender: org.bukkit.command.CommandSender? = null): String {
        val resCount = net.azisaba.townia.Townia.instance.residentManager.getResidentsByNation(id!!).size
        val nationLevel = net.azisaba.townia.Townia.instance.towniaConfig.getNationLevel(resCount)
        val prefix = nationLevel?.namePrefix ?: ""
        val postfix = nationLevel?.namePostfix ?: ""
        return "${net.azisaba.townia.Townia.instance.messageManager.processTranslationKeys(prefix, sender)}${name ?: ""}${net.azisaba.townia.Townia.instance.messageManager.processTranslationKeys(postfix, sender)}"
    }

    fun getKingPrefix(sender: org.bukkit.command.CommandSender? = null): String {
        val resCount = net.azisaba.townia.Townia.instance.residentManager.getResidentsByNation(id!!).size
        val nationLevel = net.azisaba.townia.Townia.instance.towniaConfig.getNationLevel(resCount)
        val prefix = nationLevel?.kingPrefix ?: ""
        return net.azisaba.townia.Townia.instance.messageManager.processTranslationKeys(prefix, sender)
    }

    fun getKingPostfix(sender: org.bukkit.command.CommandSender? = null): String {
        val resCount = net.azisaba.townia.Townia.instance.residentManager.getResidentsByNation(id!!).size
        val nationLevel = net.azisaba.townia.Townia.instance.towniaConfig.getNationLevel(resCount)
        val postfix = nationLevel?.kingPostfix ?: ""
        return net.azisaba.townia.Townia.instance.messageManager.processTranslationKeys(postfix, sender)
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