package net.azisaba.townia.data;

import java.util.UUID;

public class Nation {

    private final UUID id;
    private String name;
    private UUID capitalTownUuid;
    private UUID leaderUuid;
    private double balance;
    
    private String board;
    private double taxes;
    private final java.util.Set<UUID> allies = new java.util.HashSet<>();
    private final java.util.Set<UUID> enemies = new java.util.HashSet<>();

    private boolean neutral = false;
    private final java.util.Map<UUID, String> titles = new java.util.HashMap<>();
    private final java.util.Map<UUID, String> surnames = new java.util.HashMap<>();

    private String spawnWorld;
    private double spawnX;
    private double spawnY;
    private double spawnZ;
    private float spawnYaw;
    private float spawnPitch;

    public Nation(UUID id, String name, UUID capitalTownUuid, UUID leaderUuid, double balance, String board, double taxes,
                  String spawnWorld, double spawnX, double spawnY, double spawnZ, float spawnYaw, float spawnPitch) {
        this.id = id;
        this.name = name;
        this.capitalTownUuid = capitalTownUuid;
        this.leaderUuid = leaderUuid;
        this.balance = balance;
        this.board = board;
        this.taxes = taxes;
        this.spawnWorld = spawnWorld;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.spawnYaw = spawnYaw;
        this.spawnPitch = spawnPitch;
    }
    
    public Nation(UUID id, String name, UUID capitalTownUuid, UUID leaderUuid, double balance, String board, double taxes) {
        this(id, name, capitalTownUuid, leaderUuid, balance, board, taxes, null, 0, 0, 0, 0, 0);
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getCapitalTownUuid() { return capitalTownUuid; }
    public UUID getLeaderUuid() { return leaderUuid; }
    public double getBalance() { return balance; }
    public String getBoard() { return board; }
    public double getTaxes() { return taxes; }
    public java.util.Set<UUID> getAllies() { return allies; }
    public java.util.Set<UUID> getEnemies() { return enemies; }
    public boolean isNeutral() { return neutral; }
    public java.util.Map<UUID, String> getTitles() { return titles; }
    public java.util.Map<UUID, String> getSurnames() { return surnames; }
    
    public String getSpawnWorld() { return spawnWorld; }
    public double getSpawnX() { return spawnX; }
    public double getSpawnY() { return spawnY; }
    public double getSpawnZ() { return spawnZ; }
    public float getSpawnYaw() { return spawnYaw; }
    public float getSpawnPitch() { return spawnPitch; }
    public boolean hasSpawn() { return spawnWorld != null; }


    public void setName(String name) { this.name = name; }
    public void setCapitalTownUuid(UUID capitalTownUuid) { this.capitalTownUuid = capitalTownUuid; }
    public void setLeaderUuid(UUID leaderUuid) { this.leaderUuid = leaderUuid; }
    public void setBalance(double balance) { this.balance = balance; }
    public void setBoard(String board) { this.board = board; }
    public void setTaxes(double taxes) { this.taxes = taxes; }
    public void setNeutral(boolean neutral) { this.neutral = neutral; }

    public void setTitle(UUID uuid, String title) { this.titles.put(uuid, title); }
    public void removeTitle(UUID uuid) { this.titles.remove(uuid); }
    public String getTitle(UUID uuid) { return this.titles.get(uuid); }

    public void setSurname(UUID uuid, String surname) { this.surnames.put(uuid, surname); }
    public void removeSurname(UUID uuid) { this.surnames.remove(uuid); }
    public String getSurname(UUID uuid) { return this.surnames.get(uuid); }
    
    public void setSpawn(String world, double x, double y, double z, float yaw, float pitch) {
        this.spawnWorld = world;
        this.spawnX = x;
        this.spawnY = y;
        this.spawnZ = z;
        this.spawnYaw = yaw;
        this.spawnPitch = pitch;
    }
}
