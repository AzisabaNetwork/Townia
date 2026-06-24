package net.azisaba.townia.util

import org.bukkit.Chunk

@JvmRecord
data class ChunkKey(val world: String?, val x: Int, val z: Int) {
    companion object {
        fun of(chunk: Chunk): ChunkKey {
            return ChunkKey(chunk.world.name, chunk.x, chunk.z)
        }

        fun of(world: String?, x: Int, z: Int): ChunkKey {
            return ChunkKey(world, x, z)
        }
    }
}