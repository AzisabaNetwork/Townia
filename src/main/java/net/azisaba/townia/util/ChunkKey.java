package net.azisaba.townia.util;

import org.bukkit.Chunk;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

public record ChunkKey(String world, int x, int z) {

    public static ChunkKey of(Chunk chunk) {
        return new ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    public static ChunkKey of(String world, int x, int z) {
        return new ChunkKey(world, x, z);
    }

}
