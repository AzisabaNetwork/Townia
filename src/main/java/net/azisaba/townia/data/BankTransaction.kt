package net.azisaba.townia.data

import java.util.UUID

data class BankTransaction(
    val id: Int = 0,
    val governmentUuid: UUID,
    val type: TransactionType,
    val amount: Double,
    val reason: String,
    val createdAt: Long = System.currentTimeMillis()
)

enum class TransactionType {
    DEPOSIT,
    WITHDRAW
}
