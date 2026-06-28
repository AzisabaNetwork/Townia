package net.azisaba.townia.config

data class NationLevel(
    val numResidents: Int,
    val namePrefix: String,
    val namePostfix: String,
    val kingPrefix: String,
    val kingPostfix: String,
    val capitalPrefix: String,
    val capitalPostfix: String,
    val townBlockLimitBonus: Int,
    val nationBonusOutpostLimit: Int
)
