package net.azisaba.townia.data

object PermissionMatrix {
    const val FULL_PERMS: String = "BDSI"
    const val NO_PERMS: String = ""

    fun hasPerm(permString: String?, type: Char): Boolean {
        if (permString == null) return false
        return permString.indexOf(type) >= 0
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