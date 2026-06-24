package net.azisaba.townia.data

import java.util.*

@JvmRecord
data class Invite(
    val id: Int,
    val targetUuid: UUID?,
    val townUuid: UUID?,
    val inviterUuid: UUID?,
    val createdAt: Long
) {
    fun isExpired(expirySeconds: Int): Boolean {
        return System.currentTimeMillis() - createdAt > expirySeconds.toLong() * 1000L
    }
}