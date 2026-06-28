package net.azisaba.townia.data

import java.util.*

class TowniaPlayer {
    var uuid: UUID?
    var name: String?
    var townUuid: UUID?
    var rank: TownRank
    var lastSeen: Long
    var preferredLang: String?
    var friends: MutableList<String?>? = ArrayList<String?>()

    var isToggleMap: Boolean = false
    var isToggleTownClaim: Boolean = false
    var isTogglePlotBorder: Boolean = false

    var defaultPermsFriend: String? = ""
    var defaultPermsAlly: String? = ""
    var defaultPermsOutsider: String? = ""
    var defaultPermsResident: String? = "BDSI"

    constructor(uuid: UUID?, name: String?, townUuid: UUID?, rank: TownRank, lastSeen: Long, preferredLang: String?) {
        this.uuid = uuid
        this.name = name
        this.townUuid = townUuid
        this.rank = rank
        this.lastSeen = lastSeen
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
        this.preferredLang = preferredLang
        if (friends != null) this.friends = friends
    }

    val isInTown: Boolean
        get() = townUuid != null
    val isMayor: Boolean
        get() = rank == TownRank.MAYOR
    val isCoMayor: Boolean
        get() = rank == TownRank.CO_MAYOR
    val isAssistant: Boolean
        get() = rank.isAtLeast(TownRank.ASSISTANT)
    val isAssistantOrHigher: Boolean
        get() = rank.isAtLeast(TownRank.ASSISTANT)
    val isMayorOrHigher: Boolean
        get() = rank.isAtLeast(TownRank.MAYOR)
}