package net.azisaba.townia.data

@JvmRecord
data class TowniaOutpost(
    val id: Int,
    val world: String?,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val isPublic: Boolean
)