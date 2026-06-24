package net.azisaba.townia.data;

import java.util.UUID;

public record Invite(int id, UUID targetUuid, UUID townUuid, UUID inviterUuid, long createdAt) {

    public boolean isExpired(int expirySeconds) {
        return System.currentTimeMillis() - createdAt > (long) expirySeconds * 1000L;
    }
}
