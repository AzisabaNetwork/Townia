package net.azisaba.townia.data

import java.util.*

class TowniaPlayer {
    var uuid: UUID?
    var name: String?
    var townUuid: UUID? = null
        set(value) {
            if (field != value) {
                field = value
                // Whoever keeps a per-town member list rebuilds it; see ResidentManager.
                membershipVersion.incrementAndGet()
            }
        }
    var rank: TownRank
    var lastSeen: Long
    var registeredAt: Long = 0L
    var preferredLang: String?
    var friends: MutableList<String?>? = ArrayList<String?>()
    var about: String? = null

    var isToggleMap: Boolean = false
    var isToggleTownClaim: Boolean = false
    var isTogglePlotBorder: Boolean = false

    var defaultPermsFriend: String? = ""
    var defaultPermsAlly: String? = ""
    var defaultPermsOutsider: String? = ""
    var defaultPermsResident: String? = ""

    var jailedTownUuid: UUID? = null
    var jailReleaseAt: Long = 0L
    var jailBail: Double = 0.0

    /**
     * Ranks from townyperms.yml (helper, sheriff, ...), lower case. They only add permission
     * nodes; [rank] still says who is mayor. Cleared when the resident leaves the town.
     */
    val townRanks: MutableSet<String> = LinkedHashSet()
    val nationRanks: MutableSet<String> = LinkedHashSet()

    /** Every town rank name that applies, including the built-in assistant and sub_mayor. */
    fun effectiveTownRanks(): Set<String> {
        if (rank == TownRank.RESIDENT || rank == TownRank.MAYOR) return townRanks
        val all = LinkedHashSet<String>(townRanks)
        all.add(if (rank == TownRank.SUB_MAYOR) "sub_mayor" else "assistant")
        return all
    }

    constructor(uuid: UUID?, name: String?, townUuid: UUID?, rank: TownRank, lastSeen: Long, preferredLang: String?) {
        this.uuid = uuid
        this.name = name
        this.townUuid = townUuid
        this.rank = rank
        this.lastSeen = lastSeen
        this.registeredAt = lastSeen
        this.preferredLang = preferredLang
    }

    constructor(
        uuid: UUID?,
        name: String?,
        townUuid: UUID?,
        rank: TownRank,
        lastSeen: Long,
        preferredLang: String?,
        friends: MutableList<String?>?
    ) {
        this.uuid = uuid
        this.name = name
        this.townUuid = townUuid
        this.rank = rank
        this.lastSeen = lastSeen
        this.registeredAt = lastSeen
        this.preferredLang = preferredLang
        if (friends != null) this.friends = friends
    }

    companion object {
        /** Goes up whenever any resident's town changes, wherever the change is made. */
        val membershipVersion = java.util.concurrent.atomic.AtomicLong()

        fun parseRanks(raw: String?): List<String> =
            raw?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    val isInTown: Boolean
        get() = townUuid != null
    val isMayor: Boolean
        get() = rank == TownRank.MAYOR
    val isSubMayor: Boolean
        get() = rank == TownRank.SUB_MAYOR
    val isAssistant: Boolean
        get() = rank.isAtLeast(TownRank.ASSISTANT)
    val isAssistantOrHigher: Boolean
        get() = rank.isAtLeast(TownRank.ASSISTANT)
    val isMayorOrHigher: Boolean
        get() = rank.isAtLeast(TownRank.MAYOR)
    val isJailed: Boolean
        get() = jailedTownUuid != null
}
