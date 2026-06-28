package net.azisaba.townia.config

data class TownLevel(
    val numResidents: Int,
    val namePrefix: String,
    val namePostfix: String,
    val mayorPrefix: String,
    val mayorPostfix: String,
    val townBlockLimit: Int,
    val townOutpostLimit: Int
)
