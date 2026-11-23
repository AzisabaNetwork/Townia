package net.azisaba.townia.data

object PermissionMatrix {
    const val FULL_PERMS: String = "BDSI"
    const val NO_PERMS: String = ""

    fun hasPerm(permString: String?, type: Char): Boolean {
        if (permString == null) return false
        return permString.indexOf(type) >= 0
    }

    /**
     * What an unset plot perm group inherits. Resident-owned plots inherit the owner's personal
     * defaults, like Towny, so the town's perms (meant for town-owned land) never open someone
     * else's plot; nation has no personal default and still falls back to the town.
     * Town-owned plots inherit the town's perms, and have no friends.
     *
     * @param group one of friend, resident, ally, nation, outsider
     */
    fun inheritedPlotPerms(plot: Plot, town: Town?, owner: TowniaPlayer?, group: String): String? {
        if (plot.ownerUuid != null) {
            return when (group) {
                "friend" -> owner?.defaultPermsFriend
                "resident" -> owner?.defaultPermsResident
                "ally" -> owner?.defaultPermsAlly
                "outsider" -> owner?.defaultPermsOutsider
                else -> town?.permsNation
            }
        }
        return when (group) {
            "friend" -> null
            "resident" -> town?.permsResident
            "ally" -> town?.permsAlly
            "outsider" -> town?.permsOutsider
            else -> town?.permsNation
        }
    }

    fun setPerm(permString: String?, type: Char, value: Boolean): String {
        var permString = permString
        if (permString == null) permString = ""
        val has = hasPerm(permString, type)
        if (value && !has) {
            return permString + type
        } else if (!value && has) {
            return permString.replace(type.toString(), "")
        }
        return permString
    }
}