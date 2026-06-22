package net.azisaba.townia.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.azisaba.townia.Townia;
import net.azisaba.townia.data.*;

import java.io.File;
import java.sql.*;
import java.util.*;

public class DatabaseManager {

    private final Townia plugin;
    private HikariDataSource dataSource;
    private final boolean isMySQL;

    public DatabaseManager(Townia plugin) {
        this.plugin = plugin;
        this.isMySQL = "mysql".equalsIgnoreCase(plugin.getConfig().getString("database.type", "sqlite"));
    }

    public void initialize() throws SQLException {
        HikariConfig config = new HikariConfig();
        if (isMySQL) {
            String host = plugin.getConfig().getString("database.host", "localhost");
            int port = plugin.getConfig().getInt("database.port", 3306);
            String db = plugin.getConfig().getString("database.database", "townia");
            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db);
            config.setUsername(plugin.getConfig().getString("database.username", "root"));
            config.setPassword(plugin.getConfig().getString("database.password", "password"));
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        } else {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) dataFolder.mkdirs();
            File dbFile = new File(dataFolder, "townia.db");
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        }
        config.setMaximumPoolSize(plugin.getConfig().getInt("database.pool-size", 10));
        dataSource = new HikariDataSource(config);

        if (!isMySQL) {
            try (Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }
        }
        createTables();
        plugin.getLogger().info("Database initialised (MySQL: " + isMySQL + ")");
    }

    public synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private void createTables() throws SQLException {
        String autoInc = isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT";
        try (Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS towns (" +
                "id VARCHAR(36) PRIMARY KEY, " +
                "name VARCHAR(64) UNIQUE NOT NULL, " +
                "mayor_uuid VARCHAR(36) NOT NULL, " +
                "nation_uuid VARCHAR(36), " +
                "balance DOUBLE DEFAULT 0, " +
                "claim_limit INT DEFAULT 8, " +
                "bonus_claims INT DEFAULT 0, " +
                "is_public BOOLEAN DEFAULT false, " +
                "created_at BIGINT NOT NULL, " +
                "board VARCHAR(255), " +
                "taxes DOUBLE DEFAULT 0, " +
                "plot_price DOUBLE DEFAULT 0, " +
                "pvp BOOLEAN DEFAULT false, " +
                "mobs BOOLEAN DEFAULT false, " +
                "explosions BOOLEAN DEFAULT false, " +
                "fire BOOLEAN DEFAULT false, " +
                "spawn_world VARCHAR(64), " +
                "spawn_x DOUBLE DEFAULT 0, " +
                "spawn_y DOUBLE DEFAULT 64, " +
                "spawn_z DOUBLE DEFAULT 0, " +
                "spawn_yaw DOUBLE DEFAULT 0, " +
                "spawn_pitch DOUBLE DEFAULT 0, " +
                "homeblock_world VARCHAR(64), " +
                "homeblock_x INT, " +
                "homeblock_z INT, " +
                "perms_resident VARCHAR(16) DEFAULT 'BDSI', " +
                "perms_ally VARCHAR(16) DEFAULT '', " +
                "perms_outsider VARCHAR(16) DEFAULT '', " +
                "perms_nation VARCHAR(16) DEFAULT '', " +
                "daily_upkeep DOUBLE DEFAULT 0)");

            stmt.execute("CREATE TABLE IF NOT EXISTS plots (" +
                "world_name VARCHAR(64) NOT NULL, " +
                "chunk_x INT NOT NULL, " +
                "chunk_z INT NOT NULL, " +
                "town_uuid VARCHAR(36) NOT NULL, " +
                "owner_uuid VARCHAR(36), " +
                "plot_type VARCHAR(16) NOT NULL DEFAULT 'DEFAULT', " +
                "for_sale BOOLEAN NOT NULL DEFAULT false, " +
                "price DOUBLE NOT NULL DEFAULT 0, " +
                "name VARCHAR(255), " +
                "pvp BOOLEAN DEFAULT false, " +
                "mobs BOOLEAN DEFAULT false, " +
                "explosions BOOLEAN DEFAULT false, " +
                "fire BOOLEAN DEFAULT false, " +
                "is_outpost BOOLEAN DEFAULT false, " +
                "perms_resident VARCHAR(16), " +
                "perms_ally VARCHAR(16), " +
                "perms_outsider VARCHAR(16), " +
                "perms_nation VARCHAR(16), " +
                "PRIMARY KEY (world_name, chunk_x, chunk_z), " +
                "FOREIGN KEY (town_uuid) REFERENCES towns(id) ON DELETE CASCADE)");

            stmt.execute("CREATE TABLE IF NOT EXISTS residents (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "name VARCHAR(16) NOT NULL, " +
                "town_uuid VARCHAR(36), " +
                "rank VARCHAR(16) NOT NULL DEFAULT 'RESIDENT', " +
                "last_seen BIGINT NOT NULL DEFAULT 0, " +
                "preferred_lang VARCHAR(8))");

            stmt.execute("CREATE TABLE IF NOT EXISTS nations (" +
                "id VARCHAR(36) PRIMARY KEY, " +
                "name VARCHAR(64) UNIQUE NOT NULL, " +
                "capital_town_uuid VARCHAR(36) NOT NULL, " +
                "leader_uuid VARCHAR(36) NOT NULL, " +
                "balance DOUBLE DEFAULT 0, " +
                "board VARCHAR(255), " +
                "taxes DOUBLE DEFAULT 0, " +
                "spawn_world VARCHAR(64), " +
                "spawn_x DOUBLE DEFAULT 0, " +
                "spawn_y DOUBLE DEFAULT 64, " +
                "spawn_z DOUBLE DEFAULT 0, " +
                "spawn_yaw DOUBLE DEFAULT 0, " +
                "spawn_pitch DOUBLE DEFAULT 0)");

            stmt.execute("CREATE TABLE IF NOT EXISTS nation_relations (" +
                "nation_uuid VARCHAR(36) NOT NULL, " +
                "target_uuid VARCHAR(36) NOT NULL, " +
                "relation VARCHAR(16) NOT NULL, " +
                "PRIMARY KEY (nation_uuid, target_uuid), " +
                "FOREIGN KEY (nation_uuid) REFERENCES nations(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (target_uuid) REFERENCES nations(id) ON DELETE CASCADE)");

            stmt.execute("CREATE TABLE IF NOT EXISTS invites (" +
                "id INTEGER PRIMARY KEY " + autoInc + ", " +
                "target_uuid VARCHAR(36) NOT NULL, " +
                "town_uuid VARCHAR(36) NOT NULL, " +
                "inviter_uuid VARCHAR(36) NOT NULL, " +
                "created_at BIGINT NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS resident_friends (" +
                "resident_uuid VARCHAR(36) NOT NULL, " +
                "friend_uuid VARCHAR(36) NOT NULL, " +
                "PRIMARY KEY (resident_uuid, friend_uuid), " +
                "FOREIGN KEY (resident_uuid) REFERENCES residents(uuid) ON DELETE CASCADE, " +
                "FOREIGN KEY (friend_uuid) REFERENCES residents(uuid) ON DELETE CASCADE)");

            // Schema Migrations (Silent failures if columns already exist)
            try { stmt.execute("ALTER TABLE towns ADD COLUMN board VARCHAR(255)"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN taxes DOUBLE DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN plot_price DOUBLE DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN pvp BOOLEAN DEFAULT false"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN mobs BOOLEAN DEFAULT false"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN explosions BOOLEAN DEFAULT false"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN fire BOOLEAN DEFAULT false"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN homeblock_world VARCHAR(64)"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN homeblock_x INT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN homeblock_z INT"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN perms_resident VARCHAR(16) DEFAULT 'BDSI'"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN perms_ally VARCHAR(16) DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN perms_outsider VARCHAR(16) DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN perms_nation VARCHAR(16) DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE towns ADD COLUMN daily_upkeep DOUBLE DEFAULT 0"); } catch (Exception ignored) {}
            
            try { stmt.execute("ALTER TABLE plots ADD COLUMN name VARCHAR(255)"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE plots ADD COLUMN pvp BOOLEAN DEFAULT false"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE plots ADD COLUMN mobs BOOLEAN DEFAULT false"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE plots ADD COLUMN explosions BOOLEAN DEFAULT false"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE plots ADD COLUMN fire BOOLEAN DEFAULT false"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE plots ADD COLUMN is_outpost BOOLEAN DEFAULT false"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE plots ADD COLUMN perms_resident VARCHAR(16)"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE plots ADD COLUMN perms_ally VARCHAR(16)"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE plots ADD COLUMN perms_outsider VARCHAR(16)"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE plots ADD COLUMN perms_nation VARCHAR(16)"); } catch (Exception ignored) {}
            
            try { stmt.execute("ALTER TABLE nations ADD COLUMN board VARCHAR(255)"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE nations ADD COLUMN taxes DOUBLE DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE nations ADD COLUMN spawn_world VARCHAR(64)"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE nations ADD COLUMN spawn_x DOUBLE DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE nations ADD COLUMN spawn_y DOUBLE DEFAULT 64"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE nations ADD COLUMN spawn_z DOUBLE DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE nations ADD COLUMN spawn_yaw DOUBLE DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.execute("ALTER TABLE nations ADD COLUMN spawn_pitch DOUBLE DEFAULT 0"); } catch (Exception ignored) {}

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_plots_town ON plots(town_uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_residents_town ON residents(town_uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_invites_target ON invites(target_uuid)");
        }
    }

    public synchronized void saveTown(Town town) throws SQLException {
        String sql = isMySQL ? """
            INSERT INTO towns (id, name, mayor_uuid, nation_uuid, balance, claim_limit, bonus_claims,
                               is_public, created_at, board, taxes, plot_price, pvp, mobs, explosions, fire,
                               spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch,
                               homeblock_world, homeblock_x, homeblock_z, perms_resident, perms_ally, perms_outsider, perms_nation, daily_upkeep)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                name=VALUES(name), mayor_uuid=VALUES(mayor_uuid), nation_uuid=VALUES(nation_uuid),
                balance=VALUES(balance), claim_limit=VALUES(claim_limit), bonus_claims=VALUES(bonus_claims),
                is_public=VALUES(is_public), board=VALUES(board), taxes=VALUES(taxes), plot_price=VALUES(plot_price),
                pvp=VALUES(pvp), mobs=VALUES(mobs), explosions=VALUES(explosions), fire=VALUES(fire),
                spawn_world=VALUES(spawn_world),
                spawn_x=VALUES(spawn_x), spawn_y=VALUES(spawn_y), spawn_z=VALUES(spawn_z),
                spawn_yaw=VALUES(spawn_yaw), spawn_pitch=VALUES(spawn_pitch),
                homeblock_world=VALUES(homeblock_world), homeblock_x=VALUES(homeblock_x), homeblock_z=VALUES(homeblock_z),
                perms_resident=VALUES(perms_resident), perms_ally=VALUES(perms_ally), perms_outsider=VALUES(perms_outsider),
                perms_nation=VALUES(perms_nation), daily_upkeep=VALUES(daily_upkeep)
        """ : """
            INSERT INTO towns (id, name, mayor_uuid, nation_uuid, balance, claim_limit, bonus_claims,
                               is_public, created_at, board, taxes, plot_price, pvp, mobs, explosions, fire,
                               spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch,
                               homeblock_world, homeblock_x, homeblock_z, perms_resident, perms_ally, perms_outsider, perms_nation, daily_upkeep)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name=excluded.name, mayor_uuid=excluded.mayor_uuid, nation_uuid=excluded.nation_uuid,
                balance=excluded.balance, claim_limit=excluded.claim_limit, bonus_claims=excluded.bonus_claims,
                is_public=excluded.is_public, board=excluded.board, taxes=excluded.taxes, plot_price=excluded.plot_price,
                pvp=excluded.pvp, mobs=excluded.mobs, explosions=excluded.explosions, fire=excluded.fire,
                spawn_world=excluded.spawn_world,
                spawn_x=excluded.spawn_x, spawn_y=excluded.spawn_y, spawn_z=excluded.spawn_z,
                spawn_yaw=excluded.spawn_yaw, spawn_pitch=excluded.spawn_pitch,
                homeblock_world=excluded.homeblock_world, homeblock_x=excluded.homeblock_x, homeblock_z=excluded.homeblock_z,
                perms_resident=excluded.perms_resident, perms_ally=excluded.perms_ally, perms_outsider=excluded.perms_outsider,
                perms_nation=excluded.perms_nation, daily_upkeep=excluded.daily_upkeep
        """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, town.getId().toString());
            ps.setString(2, town.getName());
            ps.setString(3, town.getMayorUuid().toString());
            ps.setString(4, town.getNationUuid() != null ? town.getNationUuid().toString() : null);
            ps.setDouble(5, town.getBalance());
            ps.setInt(6, town.getClaimLimit());
            ps.setInt(7, town.getBonusClaims());
            ps.setInt(8, town.isPublic() ? 1 : 0);
            ps.setLong(9, town.getCreatedAt());
            ps.setString(10, town.getBoard());
            ps.setDouble(11, town.getTaxes());
            ps.setDouble(12, town.getPlotPrice());
            ps.setInt(13, town.hasPvp() ? 1 : 0);
            ps.setInt(14, town.hasMobs() ? 1 : 0);
            ps.setInt(15, town.hasExplosions() ? 1 : 0);
            ps.setInt(16, town.hasFire() ? 1 : 0);
            ps.setString(17, town.getSpawnWorld());
            ps.setDouble(18, town.getSpawnX());
            ps.setDouble(19, town.getSpawnY());
            ps.setDouble(20, town.getSpawnZ());
            ps.setFloat(21, town.getSpawnYaw());
            ps.setFloat(22, town.getSpawnPitch());
            ps.setString(23, town.getHomeBlockWorld());
            if (town.getHomeBlockWorld() != null) {
                ps.setInt(24, town.getHomeBlockX());
                ps.setInt(25, town.getHomeBlockZ());
            } else {
                ps.setNull(24, java.sql.Types.INTEGER);
                ps.setNull(25, java.sql.Types.INTEGER);
            }
            ps.setString(26, town.getPermsResident());
            ps.setString(27, town.getPermsAlly());
            ps.setString(28, town.getPermsOutsider());
            ps.setString(29, town.getPermsNation());
            ps.setDouble(30, town.getDailyUpkeep());
            ps.executeUpdate();
        }
    }

    public synchronized Optional<Town> getTown(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("SELECT * FROM towns WHERE id = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapTown(rs));
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Town> getTownByName(String name) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("SELECT * FROM towns WHERE LOWER(name) = LOWER(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapTown(rs));
            }
        }
        return Optional.empty();
    }

    public synchronized List<Town> getAllTowns() throws SQLException {
        List<Town> towns = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM towns ORDER BY name")) {
            while (rs.next()) towns.add(mapTown(rs));
        }
        return towns;
    }

    public synchronized void deleteTown(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("DELETE FROM towns WHERE id = ?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE residents SET town_uuid = NULL, rank = 'RESIDENT' WHERE town_uuid = ?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
    }

    private Town mapTown(ResultSet rs) throws SQLException {
        String nationStr = rs.getString("nation_uuid");
        String spawnWorld = rs.getString("spawn_world");
        return new Town(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                UUID.fromString(rs.getString("mayor_uuid")),
                nationStr != null ? UUID.fromString(nationStr) : null,
                rs.getDouble("balance"),
                rs.getInt("claim_limit"),
                rs.getInt("bonus_claims"),
                rs.getInt("is_public") == 1,
                rs.getLong("created_at"),
                rs.getString("board"),
                rs.getDouble("taxes"),
                rs.getDouble("plot_price"),
                rs.getInt("pvp") == 1,
                rs.getInt("mobs") == 1,
                rs.getInt("explosions") == 1,
                rs.getInt("fire") == 1,
                spawnWorld,
                rs.getDouble("spawn_x"),
                rs.getDouble("spawn_y"),
                rs.getDouble("spawn_z"),
                rs.getFloat("spawn_yaw"),
                rs.getFloat("spawn_pitch"),
                rs.getString("homeblock_world"),
                rs.getInt("homeblock_x"),
                rs.getInt("homeblock_z"),
                rs.getString("perms_resident"),
                rs.getString("perms_ally"),
                rs.getString("perms_outsider"),
                rs.getString("perms_nation"),
                rs.getDouble("daily_upkeep")
        );
    }

    public synchronized void saveResident(TowniaPlayer player) throws SQLException {
        String sql = isMySQL ? """
            INSERT INTO residents (uuid, name, town_uuid, rank, last_seen, preferred_lang)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                name=VALUES(name), town_uuid=VALUES(town_uuid), rank=VALUES(rank),
                last_seen=VALUES(last_seen), preferred_lang=VALUES(preferred_lang)
        """ : """
            INSERT INTO residents (uuid, name, town_uuid, rank, last_seen, preferred_lang)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                name=excluded.name, town_uuid=excluded.town_uuid, rank=excluded.rank,
                last_seen=excluded.last_seen, preferred_lang=excluded.preferred_lang
        """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, player.getUuid().toString());
            ps.setString(2, player.getName());
            ps.setString(3, player.getTownUuid() != null ? player.getTownUuid().toString() : null);
            ps.setString(4, player.getRank().name());
            ps.setLong(5, player.getLastSeen());
            ps.setString(6, player.getPreferredLang());
            ps.executeUpdate();
        }
    }

    public synchronized Optional<TowniaPlayer> getResident(UUID uuid) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("SELECT * FROM residents WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapResident(rs));
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<TowniaPlayer> getResidentByName(String name) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("SELECT * FROM residents WHERE LOWER(name) = LOWER(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapResident(rs));
            }
        }
        return Optional.empty();
    }

    public synchronized List<TowniaPlayer> getAllResidents() throws SQLException {
        List<TowniaPlayer> list = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM residents ORDER BY name")) {
            while (rs.next()) list.add(mapResident(rs));
        }
        return list;
    }

    public synchronized List<TowniaPlayer> getResidentsByTown(UUID townUuid) throws SQLException {
        List<TowniaPlayer> list = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("SELECT * FROM residents WHERE town_uuid = ? ORDER BY name")) {
            ps.setString(1, townUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapResident(rs));
            }
        }
        return list;
    }

    private TowniaPlayer mapResident(ResultSet rs) throws SQLException {
        String townStr = rs.getString("town_uuid");
        TowniaPlayer player = new TowniaPlayer(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                townStr != null ? UUID.fromString(townStr) : null,
                TownRank.fromString(rs.getString("rank")),
                rs.getLong("last_seen"),
                rs.getString("preferred_lang")
        );
        player.setFriends(getFriends(player.getUuid()));
        return player;
    }

    public synchronized List<String> getFriends(UUID residentUuid) throws SQLException {
        List<String> friends = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("SELECT friend_uuid FROM resident_friends WHERE resident_uuid = ?")) {
            ps.setString(1, residentUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) friends.add(rs.getString("friend_uuid"));
            }
        }
        return friends;
    }

    public synchronized void addFriend(UUID residentUuid, UUID friendUuid) throws SQLException {
        String sql = isMySQL ? "INSERT IGNORE INTO resident_friends (resident_uuid, friend_uuid) VALUES (?, ?)"
                             : "INSERT INTO resident_friends (resident_uuid, friend_uuid) VALUES (?, ?) ON CONFLICT DO NOTHING";
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, residentUuid.toString());
            ps.setString(2, friendUuid.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void removeFriend(UUID residentUuid, UUID friendUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("DELETE FROM resident_friends WHERE resident_uuid = ? AND friend_uuid = ?")) {
            ps.setString(1, residentUuid.toString());
            ps.setString(2, friendUuid.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void savePlot(Plot plot) throws SQLException {
        String sql = isMySQL ? """
            INSERT INTO plots (world_name, chunk_x, chunk_z, town_uuid, owner_uuid, plot_type, for_sale, price,
                               name, pvp, mobs, explosions, fire, is_outpost, perms_resident, perms_ally, perms_outsider, perms_nation)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                town_uuid=VALUES(town_uuid), owner_uuid=VALUES(owner_uuid),
                plot_type=VALUES(plot_type), for_sale=VALUES(for_sale), price=VALUES(price),
                name=VALUES(name), pvp=VALUES(pvp), mobs=VALUES(mobs), explosions=VALUES(explosions), fire=VALUES(fire),
                is_outpost=VALUES(is_outpost), perms_resident=VALUES(perms_resident), perms_ally=VALUES(perms_ally),
                perms_outsider=VALUES(perms_outsider), perms_nation=VALUES(perms_nation)
        """ : """
            INSERT INTO plots (world_name, chunk_x, chunk_z, town_uuid, owner_uuid, plot_type, for_sale, price,
                               name, pvp, mobs, explosions, fire, is_outpost, perms_resident, perms_ally, perms_outsider, perms_nation)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(world_name, chunk_x, chunk_z) DO UPDATE SET
                town_uuid=excluded.town_uuid, owner_uuid=excluded.owner_uuid,
                plot_type=excluded.plot_type, for_sale=excluded.for_sale, price=excluded.price,
                name=excluded.name, pvp=excluded.pvp, mobs=excluded.mobs, explosions=excluded.explosions, fire=excluded.fire,
                is_outpost=excluded.is_outpost, perms_resident=excluded.perms_resident, perms_ally=excluded.perms_ally,
                perms_outsider=excluded.perms_outsider, perms_nation=excluded.perms_nation
        """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, plot.getWorldName());
            ps.setInt(2, plot.getChunkX());
            ps.setInt(3, plot.getChunkZ());
            ps.setString(4, plot.getTownUuid().toString());
            ps.setString(5, plot.getOwnerUuid() != null ? plot.getOwnerUuid().toString() : null);
            ps.setString(6, plot.getPlotType().name());
            ps.setInt(7, plot.isForSale() ? 1 : 0);
            ps.setDouble(8, plot.getPrice());
            ps.setString(9, plot.getName());
            ps.setInt(10, plot.hasPvp() ? 1 : 0);
            ps.setInt(11, plot.hasMobs() ? 1 : 0);
            ps.setInt(12, plot.hasExplosions() ? 1 : 0);
            ps.setInt(13, plot.hasFire() ? 1 : 0);
            ps.setInt(14, plot.isOutpost() ? 1 : 0);
            ps.setString(15, plot.getPermsResident());
            ps.setString(16, plot.getPermsAlly());
            ps.setString(17, plot.getPermsOutsider());
            ps.setString(18, plot.getPermsNation());
            ps.executeUpdate();
        }
    }

    public synchronized Optional<Plot> getPlot(String worldName, int chunkX, int chunkZ) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM plots WHERE world_name = ? AND chunk_x = ? AND chunk_z = ?")) {
            ps.setString(1, worldName);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapPlot(rs));
            }
        }
        return Optional.empty();
    }

    public synchronized List<Plot> getPlotsByTown(UUID townUuid) throws SQLException {
        List<Plot> list = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("SELECT * FROM plots WHERE town_uuid = ?")) {
            ps.setString(1, townUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapPlot(rs));
            }
        }
        return list;
    }

    public synchronized int countPlotsByTown(UUID townUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM plots WHERE town_uuid = ?")) {
            ps.setString(1, townUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    public synchronized List<Plot> getAllPlots() throws SQLException {
        List<Plot> list = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM plots")) {
            while (rs.next()) list.add(mapPlot(rs));
        }
        return list;
    }

    public synchronized void deletePlot(String worldName, int chunkX, int chunkZ) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM plots WHERE world_name = ? AND chunk_x = ? AND chunk_z = ?")) {
            ps.setString(1, worldName);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            ps.executeUpdate();
        }
    }

    private Plot mapPlot(ResultSet rs) throws SQLException {
        String ownerStr = rs.getString("owner_uuid");
        return new Plot(
                rs.getString("world_name"),
                rs.getInt("chunk_x"),
                rs.getInt("chunk_z"),
                UUID.fromString(rs.getString("town_uuid")),
                ownerStr != null ? UUID.fromString(ownerStr) : null,
                PlotType.fromString(rs.getString("plot_type")),
                rs.getInt("for_sale") == 1,
                rs.getDouble("price"),
                rs.getString("name"),
                rs.getInt("pvp") == 1,
                rs.getInt("mobs") == 1,
                rs.getInt("explosions") == 1,
                rs.getInt("fire") == 1,
                rs.getInt("is_outpost") == 1,
                rs.getString("perms_resident"),
                rs.getString("perms_ally"),
                rs.getString("perms_outsider"),
                rs.getString("perms_nation")
        );
    }

    public synchronized void saveNation(Nation nation) throws SQLException {
        String sql = isMySQL ? """
            INSERT INTO nations (id, name, capital_town_uuid, leader_uuid, balance, board, taxes,
                                 spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                name=VALUES(name), capital_town_uuid=VALUES(capital_town_uuid),
                leader_uuid=VALUES(leader_uuid), balance=VALUES(balance),
                board=VALUES(board), taxes=VALUES(taxes),
                spawn_world=VALUES(spawn_world), spawn_x=VALUES(spawn_x), spawn_y=VALUES(spawn_y),
                spawn_z=VALUES(spawn_z), spawn_yaw=VALUES(spawn_yaw), spawn_pitch=VALUES(spawn_pitch)
        """ : """
            INSERT INTO nations (id, name, capital_town_uuid, leader_uuid, balance, board, taxes,
                                 spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name=excluded.name, capital_town_uuid=excluded.capital_town_uuid,
                leader_uuid=excluded.leader_uuid, balance=excluded.balance,
                board=excluded.board, taxes=excluded.taxes,
                spawn_world=excluded.spawn_world, spawn_x=excluded.spawn_x, spawn_y=excluded.spawn_y,
                spawn_z=excluded.spawn_z, spawn_yaw=excluded.spawn_yaw, spawn_pitch=excluded.spawn_pitch
        """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, nation.getId().toString());
            ps.setString(2, nation.getName());
            ps.setString(3, nation.getCapitalTownUuid().toString());
            ps.setString(4, nation.getLeaderUuid().toString());
            ps.setDouble(5, nation.getBalance());
            ps.setString(6, nation.getBoard());
            ps.setDouble(7, nation.getTaxes());
            ps.setString(8, nation.getSpawnWorld());
            ps.setDouble(9, nation.getSpawnX());
            ps.setDouble(10, nation.getSpawnY());
            ps.setDouble(11, nation.getSpawnZ());
            ps.setFloat(12, nation.getSpawnYaw());
            ps.setFloat(13, nation.getSpawnPitch());
            ps.executeUpdate();
        }
    }

    public synchronized Optional<Nation> getNation(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("SELECT * FROM nations WHERE id = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapNation(rs));
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Nation> getNationByName(String name) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("SELECT * FROM nations WHERE LOWER(name) = LOWER(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapNation(rs));
            }
        }
        return Optional.empty();
    }

    public synchronized List<Nation> getAllNations() throws SQLException {
        List<Nation> list = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM nations ORDER BY name")) {
            while (rs.next()) list.add(mapNation(rs));
        }
        return list;
    }

    public synchronized void deleteNation(UUID id) throws SQLException {
        // Remove nation reference from all member towns
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(
                "UPDATE towns SET nation_uuid = NULL WHERE nation_uuid = ?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("DELETE FROM nations WHERE id = ?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
    }

    private Nation mapNation(ResultSet rs) throws SQLException {
        return new Nation(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                UUID.fromString(rs.getString("capital_town_uuid")),
                UUID.fromString(rs.getString("leader_uuid")),
                rs.getDouble("balance"),
                rs.getString("board"),
                rs.getDouble("taxes"),
                rs.getString("spawn_world"),
                rs.getDouble("spawn_x"),
                rs.getDouble("spawn_y"),
                rs.getDouble("spawn_z"),
                rs.getFloat("spawn_yaw"),
                rs.getFloat("spawn_pitch")
        );
    }

    public synchronized void addNationRelation(UUID nationId, UUID targetId, String relation) throws SQLException {
        String sql = isMySQL ? """
            INSERT INTO nation_relations (nation_uuid, target_uuid, relation) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE relation=VALUES(relation)
        """ : """
            INSERT INTO nation_relations (nation_uuid, target_uuid, relation) VALUES (?, ?, ?)
            ON CONFLICT(nation_uuid, target_uuid) DO UPDATE SET relation=excluded.relation
        """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, nationId.toString());
            ps.setString(2, targetId.toString());
            ps.setString(3, relation);
            ps.executeUpdate();
        }
    }

    public synchronized void removeNationRelation(UUID nationId, UUID targetId) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM nation_relations WHERE nation_uuid = ? AND target_uuid = ?")) {
            ps.setString(1, nationId.toString());
            ps.setString(2, targetId.toString());
            ps.executeUpdate();
        }
    }

    public synchronized Map<UUID, Map<UUID, String>> getAllNationRelations() throws SQLException {
        Map<UUID, Map<UUID, String>> map = new HashMap<>();
        try (Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM nation_relations")) {
            while (rs.next()) {
                UUID nationId = UUID.fromString(rs.getString("nation_uuid"));
                UUID targetId = UUID.fromString(rs.getString("target_uuid"));
                String relation = rs.getString("relation");
                map.computeIfAbsent(nationId, k -> new HashMap<>()).put(targetId, relation);
            }
        }
        return map;
    }

    public synchronized void addInvite(Invite invite) throws SQLException {
        String sql = "INSERT INTO invites (target_uuid, town_uuid, inviter_uuid, created_at) VALUES (?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, invite.targetUuid().toString());
            ps.setString(2, invite.townUuid().toString());
            ps.setString(3, invite.inviterUuid().toString());
            ps.setLong(4, invite.createdAt());
            ps.executeUpdate();
        }
    }

    public synchronized List<Invite> getInvitesByTarget(UUID targetUuid) throws SQLException {
        List<Invite> list = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM invites WHERE target_uuid = ? ORDER BY created_at DESC")) {
            ps.setString(1, targetUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapInvite(rs));
            }
        }
        return list;
    }

    public synchronized Optional<Invite> getInvite(UUID targetUuid, UUID townUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM invites WHERE target_uuid = ? AND town_uuid = ? LIMIT 1")) {
            ps.setString(1, targetUuid.toString());
            ps.setString(2, townUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapInvite(rs));
            }
        }
        return Optional.empty();
    }

    public synchronized void deleteInvite(int id) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("DELETE FROM invites WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public synchronized void deleteInvitesByTarget(UUID targetUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("DELETE FROM invites WHERE target_uuid = ?")) {
            ps.setString(1, targetUuid.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void deleteInvitesByTown(UUID townUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("DELETE FROM invites WHERE town_uuid = ?")) {
            ps.setString(1, townUuid.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void purgeExpiredInvites(int expirySeconds) throws SQLException {
        long cutoff = System.currentTimeMillis() - (long) expirySeconds * 1000L;
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement("DELETE FROM invites WHERE created_at < ?")) {
            ps.setLong(1, cutoff);
            ps.executeUpdate();
        }
    }

    private Invite mapInvite(ResultSet rs) throws SQLException {
        return new Invite(
                rs.getInt("id"),
                UUID.fromString(rs.getString("target_uuid")),
                UUID.fromString(rs.getString("town_uuid")),
                UUID.fromString(rs.getString("inviter_uuid")),
                rs.getLong("created_at")
        );
    }
}
