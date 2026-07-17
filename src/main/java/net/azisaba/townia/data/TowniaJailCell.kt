package net.azisaba.townia.data

data class TowniaJailCell(
    val id: Int,
    val name: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float
)
