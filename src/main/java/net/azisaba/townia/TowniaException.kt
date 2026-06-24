package net.azisaba.townia

class TowniaException(val messageKey: String?, vararg replacements: String?) : Exception(
    messageKey
) {
    val replacements: Array<out String?>

    init {
        this.replacements = replacements
    }
}