package net.azisaba.townia

class TowniaException(val messageKey: String?, vararg val replacements: String?) : Exception(
    messageKey
) {
}