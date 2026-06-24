package net.azisaba.townia.data;

import java.util.UUID;

public class Plot {

    private final String worldName;
    private final int chunkX;
    private final int chunkZ;
    private UUID townUuid;
    private UUID ownerUuid;  // null = town-owned, non-null = individual resident owner
    private PlotType plotType;
    private boolean forSale;
    private double price;
    
    private String name;
    private boolean pvp;
    private boolean mobs;
    private boolean explosions;
    private boolean fire;
    
    private boolean isOutpost;
    private String permsResident;
    private String permsAlly;
    private String permsOutsider;
    private String permsNation;

    public Plot(String worldName, int chunkX, int chunkZ,
                UUID townUuid, UUID ownerUuid,
                PlotType plotType, boolean forSale, double price,
                String name, boolean pvp, boolean mobs, boolean explosions, boolean fire,
                boolean isOutpost, String permsResident, String permsAlly, String permsOutsider, String permsNation) {
        this.worldName = worldName;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.townUuid = townUuid;
        this.ownerUuid = ownerUuid;
        this.plotType = plotType;
        this.forSale = forSale;
        this.price = price;
        this.name = name;
        this.pvp = pvp;
        this.mobs = mobs;
        this.explosions = explosions;
        this.fire = fire;
        this.isOutpost = isOutpost;
        this.permsResident = permsResident;
        this.permsAlly = permsAlly;
        this.permsOutsider = permsOutsider;
        this.permsNation = permsNation;
    }
    
    public Plot(String worldName, int chunkX, int chunkZ,
                UUID townUuid, UUID ownerUuid,
                PlotType plotType, boolean forSale, double price,
                String name, boolean pvp, boolean mobs, boolean explosions, boolean fire) {
        this(worldName, chunkX, chunkZ, townUuid, ownerUuid, plotType, forSale, price, name, pvp, mobs, explosions, fire, false, null, null, null, null);
    }

    public String getWorldName() { return worldName; }
    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public UUID getTownUuid() { return townUuid; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public PlotType getPlotType() { return plotType; }
    public boolean isForSale() { return forSale; }
    public double getPrice() { return price; }
    public String getName() { return name; }
    public boolean hasPvp() { return pvp; }
    public boolean hasMobs() { return mobs; }
    public boolean hasExplosions() { return explosions; }
    public boolean hasFire() { return fire; }
    
    public boolean isOutpost() { return isOutpost; }
    public String getPermsResident() { return permsResident; }
    public String getPermsAlly() { return permsAlly; }
    public String getPermsOutsider() { return permsOutsider; }
    public String getPermsNation() { return permsNation; }

    public boolean isTownOwned() { return ownerUuid == null; }

    public void setTownUuid(UUID townUuid) { this.townUuid = townUuid; }
    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }
    public void setPlotType(PlotType plotType) { this.plotType = plotType; }
    public void setForSale(boolean forSale) { this.forSale = forSale; }
    public void setPrice(double price) { this.price = price; }
    public void setName(String name) { this.name = name; }
    public void setPvp(boolean pvp) { this.pvp = pvp; }
    public void setMobs(boolean mobs) { this.mobs = mobs; }
    public void setExplosions(boolean explosions) { this.explosions = explosions; }
    public void setFire(boolean fire) { this.fire = fire; }
    
    public void setOutpost(boolean isOutpost) { this.isOutpost = isOutpost; }
    public void setPermsResident(String permsResident) { this.permsResident = permsResident; }
    public void setPermsAlly(String permsAlly) { this.permsAlly = permsAlly; }
    public void setPermsOutsider(String permsOutsider) { this.permsOutsider = permsOutsider; }
    public void setPermsNation(String permsNation) { this.permsNation = permsNation; }
}
