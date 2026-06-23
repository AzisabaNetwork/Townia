package net.azisaba.townia.data;

import net.azisaba.townia.Townia;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

public class Town {

    private final UUID id;
    private String name;
    private UUID mayorUuid;
    private UUID nationUuid;
    private double balance;
    private int claimLimit;
    private int bonusClaims;
    private boolean isPublic;
    private boolean open;
    private long createdAt;

    // Extended features
    private String board;
    private double taxes;
    private double plotPrice;
    private boolean pvp;
    private boolean mobs;
    private boolean explosions;
    private boolean fire;
    
    // Home block
    private String homeBlockWorld;
    private int homeBlockX;
    private int homeBlockZ;
    
    // Permissions
    private String permsResident = "BDSI";
    private String permsAlly = "";
    private String permsOutsider = "";
    private String permsNation = "";
    
    private double dailyUpkeep;

    // Extended economy fields
    private String tag;
    private boolean taxPercent;
    private double plotTax;
    private double shopPrice;
    private double shopTax;
    private double embassyPrice;
    private double embassyTax;

    // TownConfig fields
    private boolean allowInvisibility = true;
    private boolean allowSit          = true;
    private boolean allowPetPickup    = true;
    private boolean allowPassenger    = true;



    // Spawn point
    private String spawnWorld;
    private double spawnX;
    private double spawnY;
    private double spawnZ;
    private float spawnYaw;
    private float spawnPitch;

    private final List<TowniaOutpost> outposts = new ArrayList<>();

    public Town(UUID id, String name, UUID mayorUuid, UUID nationUuid,
                double balance, int claimLimit, int bonusClaims, boolean isPublic,
                long createdAt, String board, double taxes, double plotPrice,
                boolean pvp, boolean mobs, boolean explosions, boolean fire,
                String spawnWorld, double spawnX, double spawnY, double spawnZ,
                float spawnYaw, float spawnPitch) {
        this.id = id;
        this.name = name;
        this.mayorUuid = mayorUuid;
        this.nationUuid = nationUuid;
        this.balance = balance;
        this.claimLimit = claimLimit;
        this.bonusClaims = bonusClaims;
        this.isPublic = isPublic;
        this.createdAt = createdAt;
        this.board = board;
        this.taxes = taxes;
        this.plotPrice = plotPrice;
        this.pvp = pvp;
        this.mobs = mobs;
        this.explosions = explosions;
        this.fire = fire;
        this.spawnWorld = spawnWorld;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.spawnYaw = spawnYaw;
        this.spawnPitch = spawnPitch;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getMayorUuid() { return mayorUuid; }
    public UUID getNationUuid() { return nationUuid; }
    public double getBalance() { return balance; }
    public int getClaimLimit() { return claimLimit; }
    public int getBonusClaims() { return bonusClaims; }
    public int getTotalClaimLimit() { return claimLimit + bonusClaims; }
    public boolean isPublic() { return isPublic; }
    public boolean isOpen() { return open; }
    public void setOpen(boolean open) { this.open = open; }
    public long getCreatedAt() { return createdAt; }
    
    public String getBoard() { return board; }
    public double getTaxes() { return taxes; }
    public double getPlotPrice() { return plotPrice; }
    public boolean hasPvp() { return pvp; }
    public boolean hasMobs() { return mobs; }
    public boolean hasExplosions() { return explosions; }
    public boolean hasFire() { return fire; }

    public String getSpawnWorld() { return spawnWorld; }
    public double getSpawnX() { return spawnX; }
    public double getSpawnY() { return spawnY; }
    public double getSpawnZ() { return spawnZ; }
    public float getSpawnYaw() { return spawnYaw; }
    public float getSpawnPitch() { return spawnPitch; }
    public boolean isInNation() { return nationUuid != null; }
    public boolean hasSpawn() { return spawnWorld != null; }
    
    public String getHomeBlockWorld() { return homeBlockWorld; }
    public int getHomeBlockX() { return homeBlockX; }
    public int getHomeBlockZ() { return homeBlockZ; }
    public boolean hasHomeBlock() { return homeBlockWorld != null; }
    
    public String getPermsResident() { return permsResident; }
    public String getPermsAlly() { return permsAlly; }
    public String getPermsOutsider() { return permsOutsider; }
    public String getPermsNation() { return permsNation; }
    
    public double getDailyUpkeep() { return dailyUpkeep; }

    public List<TowniaOutpost> getOutposts() { return outposts; }

    public String getTownSizeRank() {
        int count = Townia.getInstance().getResidentManager().getResidentsByTown(id).size();
        if (count >= 28) return "MetroPolis";
        if (count >= 24) return "Large City";
        if (count >= 20) return "City";
        if (count >= 14) return "Large Town";
        if (count >= 10) return "Town";
        if (count >= 6) return "Village";
        if (count >= 2) return "Hamlet";
        return "Settlement";
    }

    public void setName(String name) { this.name = name; }
    public void setMayorUuid(UUID mayorUuid) { this.mayorUuid = mayorUuid; }
    public void setNationUuid(UUID nationUuid) { this.nationUuid = nationUuid; }
    public void setBalance(double balance) { this.balance = balance; }
    public void setClaimLimit(int claimLimit) { this.claimLimit = claimLimit; }
    public void setBonusClaims(int bonusClaims) { this.bonusClaims = bonusClaims; }
    public void setPublic(boolean aPublic) { isPublic = aPublic; }

    public void setBoard(String board) { this.board = board; }
    public void setTaxes(double taxes) { this.taxes = taxes; }
    public void setPlotPrice(double plotPrice) { this.plotPrice = plotPrice; }
    public void setPvp(boolean pvp) { this.pvp = pvp; }
    public void setMobs(boolean mobs) { this.mobs = mobs; }
    public void setExplosions(boolean explosions) { this.explosions = explosions; }
    public void setFire(boolean fire) { this.fire = fire; }

    public void setSpawn(String world, double x, double y, double z, float yaw, float pitch) {
        this.spawnWorld = world;
        this.spawnX = x;
        this.spawnY = y;
        this.spawnZ = z;
        this.spawnYaw = yaw;
        this.spawnPitch = pitch;
    }
    
    public void setHomeBlock(String world, int x, int z) {
        this.homeBlockWorld = world;
        this.homeBlockX = x;
        this.homeBlockZ = z;
    }
    
    public void setPermsResident(String perms) { this.permsResident = perms; }
    public void setPermsAlly(String perms) { this.permsAlly = perms; }
    public void setPermsOutsider(String perms) { this.permsOutsider = perms; }
    public void setPermsNation(String perms) { this.permsNation = perms; }
    
    public void setDailyUpkeep(double dailyUpkeep) { this.dailyUpkeep = dailyUpkeep; }

    public String getTag() { return tag; }
    public boolean isTaxPercent() { return taxPercent; }
    public double getPlotTax() { return plotTax; }
    public double getShopPrice() { return shopPrice; }
    public double getShopTax() { return shopTax; }
    public double getEmbassyPrice() { return embassyPrice; }
    public double getEmbassyTax() { return embassyTax; }

    public void setTag(String tag) { this.tag = tag; }
    public void setTaxPercent(boolean taxPercent) { this.taxPercent = taxPercent; }
    public void setPlotTax(double plotTax) { this.plotTax = plotTax; }
    public void setShopPrice(double shopPrice) { this.shopPrice = shopPrice; }
    public void setShopTax(double shopTax) { this.shopTax = shopTax; }
    public void setEmbassyPrice(double embassyPrice) { this.embassyPrice = embassyPrice; }
    public void setEmbassyTax(double embassyTax) { this.embassyTax = embassyTax; }

    // TownConfig getters / setters
    public boolean isAllowInvisibility() { return allowInvisibility; }
    public boolean isAllowSit()          { return allowSit; }
    public boolean isAllowPetPickup()    { return allowPetPickup; }
    public boolean isAllowPassenger()    { return allowPassenger; }

    public void setAllowInvisibility(boolean v) { this.allowInvisibility = v; }
    public void setAllowSit(boolean v)          { this.allowSit = v; }
    public void setAllowPetPickup(boolean v)    { this.allowPetPickup = v; }
    public void setAllowPassenger(boolean v)    { this.allowPassenger = v; }


    // Additional constructor for loading
    public Town(UUID id, String name, UUID mayorUuid, UUID nationUuid,
                double balance, int claimLimit, int bonusClaims, boolean isPublic,
                long createdAt, String board, double taxes, double plotPrice,
                boolean pvp, boolean mobs, boolean explosions, boolean fire,
                String spawnWorld, double spawnX, double spawnY, double spawnZ,
                float spawnYaw, float spawnPitch,
                String homeBlockWorld, int homeBlockX, int homeBlockZ,
                String permsResident, String permsAlly, String permsOutsider, String permsNation,
                double dailyUpkeep) {
        this(id, name, mayorUuid, nationUuid, balance, claimLimit, bonusClaims, isPublic, createdAt,
             board, taxes, plotPrice, pvp, mobs, explosions, fire, spawnWorld, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);
        this.homeBlockWorld = homeBlockWorld;
        this.homeBlockX = homeBlockX;
        this.homeBlockZ = homeBlockZ;
        this.permsResident = permsResident;
        this.permsAlly = permsAlly;
        this.permsOutsider = permsOutsider;
        this.permsNation = permsNation;
        this.dailyUpkeep = dailyUpkeep;
    }
}
