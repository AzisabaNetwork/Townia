package net.azisaba.townia.util

import org.bukkit.Chunk

@JvmRecord
data class ChunkKey(val world: String?, val x: Int, val z: Int) {
    companion object {
        fun of(chunk: Chunk): ChunkKey {
            return ChunkKey(chunk.getWorld().name, chunk.getX(), chunk.getZ())
        }

        fun of(world: String?, x: Int, z: Int): ChunkKey {
            return ChunkKey(world, x, z)
        }
    }
}