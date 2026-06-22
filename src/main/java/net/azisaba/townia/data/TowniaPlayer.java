package net.azisaba.townia.data;

import java.util.UUID;

public class TowniaPlayer {

    private final UUID uuid;
    private String name;
    private UUID townUuid;
    private TownRank rank;
    private long lastSeen;
    private String preferredLang;
    private java.util.List<String> friends = new java.util.ArrayList<>();

    public TowniaPlayer(UUID uuid, String name, UUID townUuid, TownRank rank, long lastSeen, String preferredLang) {
        this.uuid = uuid;
        this.name = name;
        this.townUuid = townUuid;
        this.rank = rank;
        this.lastSeen = lastSeen;
        this.preferredLang = preferredLang;
    }
    
    public TowniaPlayer(UUID uuid, String name, UUID townUuid, TownRank rank, long lastSeen, String preferredLang, java.util.List<String> friends) {
        this.uuid = uuid;
        this.name = name;
        this.townUuid = townUuid;
        this.rank = rank;
        this.lastSeen = lastSeen;
        this.preferredLang = preferredLang;
        if (friends != null) this.friends = friends;
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public UUID getTownUuid() { return townUuid; }
    public TownRank getRank() { return rank; }
    public long getLastSeen() { return lastSeen; }
    public String getPreferredLang() { return preferredLang; }
    public java.util.List<String> getFriends() { return friends; }

    public boolean isInTown() { return townUuid != null; }
    public boolean isMayor() { return rank == TownRank.MAYOR; }
    public boolean isCoMayor() { return rank == TownRank.CO_MAYOR; }
    public boolean isAssistant() { return rank.isAtLeast(TownRank.ASSISTANT); }
    public boolean isAssistantOrHigher() { return rank.isAtLeast(TownRank.ASSISTANT); }
    public boolean isMayorOrHigher() { return rank.isAtLeast(TownRank.MAYOR); }

    public void setName(String name) { this.name = name; }
    public void setTownUuid(UUID townUuid) { this.townUuid = townUuid; }
    public void setRank(TownRank rank) { this.rank = rank; }
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
    public void setPreferredLang(String preferredLang) { this.preferredLang = preferredLang; }
    public void setFriends(java.util.List<String> friends) { this.friends = friends; }
}
