package net.azisaba.townia.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.azisaba.townia.Townia
import net.azisaba.townia.data.*
import java.io.File
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.*

class DatabaseManager(private val plugin: Townia) {
    private var dataSource: HikariDataSource? = null
    private val isMySQL: Boolean = "mysql".equals(plugin.getConfig().getString("database.type", "sqlite"), ignoreCase = true)

    @Throws(SQLException::class)
    fun initialize() {
        val config = HikariConfig()
        if (isMySQL) {
            val host: String = plugin.getConfig().getString("database.host", "localhost")!!
            val port = plugin.getConfig().getInt("database.port", 3306)
            val db: String = plugin.getConfig().getString("database.database", "townia")!!
            config.setJdbcUrl("jdbc:mysql://$host:$port/$db")
            config.username = plugin.getConfig().getString("database.username", "root")
            config.password = plugin.getConfig().getString("database.password", "password")
            config.addDataSourceProperty("cachePrepStmts", "true")
            config.addDataSourceProperty("prepStmtCacheSize", "250")
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        } else {
            val dataFolder = plugin.dataFolder
            if (!dataFolder.exists()) dataFolder.mkdirs()
            val dbFile = File(dataFolder, "townia.db")
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.absolutePath)
        }
        config.setMaximumPoolSize(plugin.getConfig().getInt("database.pool-size", 10))
        dataSource = HikariDataSource(config)

        if (!isMySQL) {
            dataSource!!.getConnection().use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.execute("PRAGMA journal_mode=WAL")
                    stmt.execute("PRAGMA foreign_keys=ON")
                }
            }
        }
        createTables()
        plugin.logger.info("Database initialised (MySQL: $isMySQL)")
    }

    @Synchronized
    fun close() {
        if (dataSource != null && !dataSource!!.isClosed) {
            dataSource!!.close()
        }
    }

    @Throws(SQLException::class)
    private fun createTables() {
        val autoInc = if (isMySQL) "AUTO_INCREMENT" else "AUTOINCREMENT"
        dataSource!!.getConnection().use { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS towns (" +
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
                            "daily_upkeep DOUBLE DEFAULT 0, " +
                            "allow_invisibility BOOLEAN DEFAULT true, " +
                            "allow_sit BOOLEAN DEFAULT true, " +
                            "allow_pet_pickup BOOLEAN DEFAULT true, " +
                            "allow_passenger BOOLEAN DEFAULT true)"
                )
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS plots (" +
                            "world_name VARCHAR(64) NOT NULL, " +
                            "chunk_x INT NOT NULL, " +
                            "chunk_z INT NOT NULL, " +
                            "town_uuid VARCHAR(36) NOT NULL, " +
                            "owner_uuid VARCHAR(36), " +
                            "plot_type VARCHAR(16) NOT NULL DEFAULT 'DEFAULT', " +
                            "for_sale BOOLEAN NOT NULL DEFAULT false, " +
                            "price DOUBLE NOT NULL DEFAULT 0, " +
                            "name VARCHAR(64), " +
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
                            "FOREIGN KEY (town_uuid) REFERENCES towns(id) ON DELETE CASCADE)"
                )

                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS residents (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "name VARCHAR(16) NOT NULL, " +
                            "town_uuid VARCHAR(36), " +
                            "rank VARCHAR(16) NOT NULL DEFAULT 'RESIDENT', " +
                            "last_seen BIGINT NOT NULL DEFAULT 0, " +
                            "preferred_lang VARCHAR(8), " +
                            "toggle_map INTEGER DEFAULT 0, " +
                            "toggle_town_claim INTEGER DEFAULT 0, " +
                            "toggle_plot_border INTEGER DEFAULT 0, " +
                            "default_perms_friend VARCHAR(16) DEFAULT '', " +
                            "default_perms_ally VARCHAR(16) DEFAULT '', " +
                            "default_perms_outsider VARCHAR(16) DEFAULT '', " +
                            "default_perms_resident VARCHAR(16) DEFAULT 'BDSI')"
                )

                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS nations (" +
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
                            "spawn_pitch DOUBLE DEFAULT 0, " +
                            "neutral BOOLEAN DEFAULT false)"
                )

                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS nation_relations (" +
                            "nation_uuid VARCHAR(36) NOT NULL, " +
                            "target_uuid VARCHAR(36) NOT NULL, " +
                            "relation VARCHAR(16) NOT NULL, " +
                            "PRIMARY KEY (nation_uuid, target_uuid), " +
                            "FOREIGN KEY (nation_uuid) REFERENCES nations(id) ON DELETE CASCADE, " +
                            "FOREIGN KEY (target_uuid) REFERENCES nations(id) ON DELETE CASCADE)"
                )

                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS nation_titles (" +
                            "nation_uuid VARCHAR(36) NOT NULL, " +
                            "uuid VARCHAR(36) NOT NULL, " +
                            "title_type VARCHAR(8) NOT NULL, " +
                            "value VARCHAR(64) NOT NULL, " +
                            "PRIMARY KEY (nation_uuid, uuid, title_type), " +
                            "FOREIGN KEY (nation_uuid) REFERENCES nations(id) ON DELETE CASCADE)"
                )

                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS invites (" +
                            "id INTEGER PRIMARY KEY " + autoInc + ", " +
                            "target_uuid VARCHAR(36) NOT NULL, " +
                            "town_uuid VARCHAR(36) NOT NULL, " +
                            "inviter_uuid VARCHAR(36) NOT NULL, " +
                            "created_at BIGINT NOT NULL)"
                )

                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS resident_friends (" +
                            "resident_uuid VARCHAR(36) NOT NULL, " +
                            "friend_uuid VARCHAR(36) NOT NULL, " +
                            "PRIMARY KEY (resident_uuid, friend_uuid), " +
                            "FOREIGN KEY (resident_uuid) REFERENCES residents(uuid) ON DELETE CASCADE, " +
                            "FOREIGN KEY (friend_uuid) REFERENCES residents(uuid) ON DELETE CASCADE)"
                )

                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS town_outposts (" +
                            "id INTEGER PRIMARY KEY " + autoInc + ", " +
                            "town_uuid VARCHAR(36) NOT NULL, " +
                            "world VARCHAR(64) NOT NULL, " +
                            "x DOUBLE NOT NULL, " +
                            "y DOUBLE NOT NULL, " +
                            "z DOUBLE NOT NULL, " +
                            "yaw FLOAT NOT NULL, " +
                            "pitch FLOAT NOT NULL, " +
                            "is_public BOOLEAN DEFAULT false, " +
                            "FOREIGN KEY (town_uuid) REFERENCES towns(id) ON DELETE CASCADE)"
                )

                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN board VARCHAR(255)")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN taxes DOUBLE DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN plot_price DOUBLE DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN pvp BOOLEAN DEFAULT false")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN mobs BOOLEAN DEFAULT false")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN explosions BOOLEAN DEFAULT false")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN fire BOOLEAN DEFAULT false")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN homeblock_world VARCHAR(64)")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN homeblock_x INT")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN homeblock_z INT")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN perms_resident VARCHAR(16) DEFAULT 'BDSI'")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN perms_ally VARCHAR(16) DEFAULT ''")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN perms_outsider VARCHAR(16) DEFAULT ''")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN perms_nation VARCHAR(16) DEFAULT ''")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN daily_upkeep DOUBLE DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN allow_invisibility BOOLEAN DEFAULT true")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN allow_sit BOOLEAN DEFAULT true")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN allow_pet_pickup BOOLEAN DEFAULT true")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE towns ADD COLUMN allow_passenger BOOLEAN DEFAULT true")
                } catch (_: Exception) {
                }

                try {
                    stmt.execute("ALTER TABLE plots ADD COLUMN name VARCHAR(64)")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE plots ADD COLUMN pvp BOOLEAN DEFAULT false")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE plots ADD COLUMN mobs BOOLEAN DEFAULT false")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE plots ADD COLUMN explosions BOOLEAN DEFAULT false")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE plots ADD COLUMN fire BOOLEAN DEFAULT false")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE plots ADD COLUMN is_outpost BOOLEAN DEFAULT false")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE plots ADD COLUMN perms_resident VARCHAR(16)")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE plots ADD COLUMN perms_ally VARCHAR(16)")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE plots ADD COLUMN perms_outsider VARCHAR(16)")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE plots ADD COLUMN perms_nation VARCHAR(16)")
                } catch (_: Exception) {
                }

                try {
                    stmt.execute("ALTER TABLE nations ADD COLUMN board VARCHAR(255)")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE nations ADD COLUMN taxes DOUBLE DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE nations ADD COLUMN spawn_world VARCHAR(64)")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE nations ADD COLUMN spawn_x DOUBLE DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE nations ADD COLUMN spawn_y DOUBLE DEFAULT 64")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE nations ADD COLUMN spawn_z DOUBLE DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE nations ADD COLUMN spawn_yaw DOUBLE DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE nations ADD COLUMN spawn_pitch DOUBLE DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE nations ADD COLUMN neutral BOOLEAN DEFAULT false")
                } catch (_: Exception) {
                }

                try {
                    stmt.execute("ALTER TABLE residents ADD COLUMN toggle_map INTEGER DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE residents ADD COLUMN toggle_town_claim INTEGER DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE residents ADD COLUMN toggle_plot_border INTEGER DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE residents ADD COLUMN default_perms_friend VARCHAR(16) DEFAULT ''")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE residents ADD COLUMN default_perms_ally VARCHAR(16) DEFAULT ''")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE residents ADD COLUMN default_perms_outsider VARCHAR(16) DEFAULT ''")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE residents ADD COLUMN default_perms_resident VARCHAR(16) DEFAULT 'BDSI'")
                } catch (_: Exception) {
                }

                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS bank_history (" +
                            "id INTEGER PRIMARY KEY " + autoInc + ", " +
                            "government_uuid VARCHAR(36) NOT NULL, " +
                            "type VARCHAR(16) NOT NULL, " +
                            "amount DOUBLE NOT NULL, " +
                            "reason VARCHAR(255) NOT NULL, " +
                            "created_at BIGINT NOT NULL)"
                )

                stmt.execute("CREATE INDEX IF NOT EXISTS idx_bank_history_gov ON bank_history(government_uuid)")

                stmt.execute("CREATE INDEX IF NOT EXISTS idx_plots_town ON plots(town_uuid)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_residents_town ON residents(town_uuid)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_invites_target ON invites(target_uuid)")
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun saveTown(town: Town) {
        val sql = if (isMySQL) """
            INSERT INTO towns (id, name, mayor_uuid, nation_uuid, balance, claim_limit, bonus_claims,
                               is_public, created_at, board, taxes, plot_price, pvp, mobs, explosions, fire,
                               spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch,
                               homeblock_world, homeblock_x, homeblock_z, perms_resident, perms_ally, perms_outsider, perms_nation, daily_upkeep,
                               allow_invisibility, allow_sit, allow_pet_pickup, allow_passenger)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                perms_nation=VALUES(perms_nation), daily_upkeep=VALUES(daily_upkeep),
                allow_invisibility=VALUES(allow_invisibility), allow_sit=VALUES(allow_sit),
                allow_pet_pickup=VALUES(allow_pet_pickup), allow_passenger=VALUES(allow_passenger)
        
        """.trimIndent() else """
            INSERT INTO towns (id, name, mayor_uuid, nation_uuid, balance, claim_limit, bonus_claims,
                               is_public, created_at, board, taxes, plot_price, pvp, mobs, explosions, fire,
                               spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch,
                               homeblock_world, homeblock_x, homeblock_z, perms_resident, perms_ally, perms_outsider, perms_nation, daily_upkeep,
                               allow_invisibility, allow_sit, allow_pet_pickup, allow_passenger)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                perms_nation=excluded.perms_nation, daily_upkeep=excluded.daily_upkeep,
                allow_invisibility=excluded.allow_invisibility, allow_sit=excluded.allow_sit,
                allow_pet_pickup=excluded.allow_pet_pickup, allow_passenger=excluded.allow_passenger
        
        """.trimIndent()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, town.id.toString())
                ps.setString(2, town.name)
                ps.setString(3, town.mayorUuid.toString())
                ps.setString(4, if (town.nationUuid != null) town.nationUuid.toString() else null)
                ps.setDouble(5, town.balance)
                ps.setInt(6, town.claimLimit)
                ps.setInt(7, town.bonusClaims)
                ps.setInt(8, if (town.isPublic) 1 else 0)
                ps.setLong(9, town.createdAt)
                ps.setString(10, town.board)
                ps.setDouble(11, town.taxes)
                ps.setDouble(12, town.plotPrice)
                ps.setInt(13, if (town.hasPvp()) 1 else 0)
                ps.setInt(14, if (town.hasMobs()) 1 else 0)
                ps.setInt(15, if (town.hasExplosions()) 1 else 0)
                ps.setInt(16, if (town.hasFire()) 1 else 0)
                ps.setString(17, town.spawnWorld)
                ps.setDouble(18, town.spawnX)
                ps.setDouble(19, town.spawnY)
                ps.setDouble(20, town.spawnZ)
                ps.setFloat(21, town.spawnYaw)
                ps.setFloat(22, town.spawnPitch)
                ps.setString(23, town.homeBlockWorld)
                if (town.homeBlockWorld != null) {
                    ps.setInt(24, town.homeBlockX)
                    ps.setInt(25, town.homeBlockZ)
                } else {
                    ps.setNull(24, Types.INTEGER)
                    ps.setNull(25, Types.INTEGER)
                }
                ps.setString(26, town.permsResident)
                ps.setString(27, town.permsAlly)
                ps.setString(28, town.permsOutsider)
                ps.setString(29, town.permsNation)
                ps.setDouble(30, town.dailyUpkeep)
                ps.setInt(31, if (town.isAllowInvisibility) 1 else 0)
                ps.setInt(32, if (town.isAllowSit) 1 else 0)
                ps.setInt(33, if (town.isAllowPetPickup) 1 else 0)
                ps.setInt(34, if (town.isAllowPassenger) 1 else 0)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun getTown(id: UUID): Optional<Town> {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT * FROM towns WHERE id = ?").use { ps ->
                ps.setString(1, id.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) return Optional.of<Town?>(mapTown(rs))
                }
            }
        }
        return Optional.empty<Town?>()
    }

    @Synchronized
    @Throws(SQLException::class)
    fun getTownByName(name: String?): Optional<Town> {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT * FROM towns WHERE LOWER(name) = LOWER(?)").use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return Optional.of<Town?>(mapTown(rs))
                }
            }
        }
        return Optional.empty<Town?>()
    }

    @get:Throws(SQLException::class)
    @get:Synchronized
    val allTowns: MutableList<Town?>
        get() {
            val towns: MutableList<Town?> = ArrayList<Town?>()
            dataSource!!.getConnection().use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM towns ORDER BY name").use { rs ->
                        while (rs.next()) towns.add(mapTown(rs))
                    }
                }
            }
            return towns
        }

    @Synchronized
    @Throws(SQLException::class)
    fun deleteTown(id: UUID) {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("DELETE FROM towns WHERE id = ?").use { ps ->
                ps.setString(1, id.toString())
                ps.executeUpdate()
            }
        }
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(
                "UPDATE residents SET town_uuid = NULL, rank = 'RESIDENT' WHERE town_uuid = ?"
            ).use { ps ->
                ps.setString(1, id.toString())
                ps.executeUpdate()
            }
        }
    }

    @Throws(SQLException::class)
    private fun mapTown(rs: ResultSet): Town {
        val nationStr = rs.getString("nation_uuid")
        val spawnWorld = rs.getString("spawn_world")
        val town = Town(
            UUID.fromString(rs.getString("id")),
            rs.getString("name"),
            UUID.fromString(rs.getString("mayor_uuid")),
            if (nationStr != null) UUID.fromString(nationStr) else null,
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
        )
        try {
            if (rs.getObject("allow_invisibility") != null) town.isAllowInvisibility =
                rs.getInt("allow_invisibility") != 0
        } catch (ignored: Exception) {
        }
        try {
            if (rs.getObject("allow_sit") != null) town.isAllowSit = rs.getInt("allow_sit") != 0
        } catch (ignored: Exception) {
        }
        try {
            if (rs.getObject("allow_pet_pickup") != null) town.isAllowPetPickup = rs.getInt("allow_pet_pickup") != 0
        } catch (ignored: Exception) {
        }
        try {
            if (rs.getObject("allow_passenger") != null) town.isAllowPassenger = rs.getInt("allow_passenger") != 0
        } catch (ignored: Exception) {
        }
        return town
    }

    @Synchronized
    @Throws(SQLException::class)
    fun loadTownOutposts(town: Town) {
        town.outposts.clear()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT * FROM town_outposts WHERE town_uuid = ?").use { ps ->
                ps.setString(1, town.id.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        town.outposts.add(
                            TowniaOutpost(
                                rs.getInt("id"),
                                rs.getString("world"),
                                rs.getDouble("x"),
                                rs.getDouble("y"),
                                rs.getDouble("z"),
                                rs.getFloat("yaw"),
                                rs.getFloat("pitch"),
                                rs.getInt("is_public") == 1
                            )
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun saveTownOutpost(townId: UUID, outpost: TowniaOutpost) {
        val sql = if (isMySQL) """
            INSERT INTO town_outposts (id, town_uuid, world, x, y, z, yaw, pitch, is_public) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z), yaw=VALUES(yaw), pitch=VALUES(pitch), is_public=VALUES(is_public)
        
        """.trimIndent() else """
            INSERT INTO town_outposts (id, town_uuid, world, x, y, z, yaw, pitch, is_public) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z, yaw=excluded.yaw, pitch=excluded.pitch, is_public=excluded.is_public
        
        """.trimIndent()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(sql).use { ps ->
                if (outpost.id == 0) {
                    ps.setNull(1, Types.INTEGER)
                } else {
                    ps.setInt(1, outpost.id)
                }
                ps.setString(2, townId.toString())
                ps.setString(3, outpost.world)
                ps.setDouble(4, outpost.x)
                ps.setDouble(5, outpost.y)
                ps.setDouble(6, outpost.z)
                ps.setFloat(7, outpost.yaw)
                ps.setFloat(8, outpost.pitch)
                ps.setInt(9, if (outpost.isPublic) 1 else 0)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun deleteTownOutpost(id: Int) {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("DELETE FROM town_outposts WHERE id = ?").use { ps ->
                ps.setInt(1, id)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun saveResident(player: TowniaPlayer) {
        val sql = if (isMySQL) """
            INSERT INTO residents (uuid, name, town_uuid, rank, last_seen, preferred_lang,
                                   toggle_map, toggle_town_claim, toggle_plot_border,
                                   default_perms_friend, default_perms_ally, default_perms_outsider, default_perms_resident)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                name=VALUES(name), town_uuid=VALUES(town_uuid), rank=VALUES(rank),
                last_seen=VALUES(last_seen), preferred_lang=VALUES(preferred_lang),
                toggle_map=VALUES(toggle_map), toggle_town_claim=VALUES(toggle_town_claim), toggle_plot_border=VALUES(toggle_plot_border),
                default_perms_friend=VALUES(default_perms_friend), default_perms_ally=VALUES(default_perms_ally),
                default_perms_outsider=VALUES(default_perms_outsider), default_perms_resident=VALUES(default_perms_resident)
        
        """.trimIndent() else """
            INSERT INTO residents (uuid, name, town_uuid, rank, last_seen, preferred_lang,
                                   toggle_map, toggle_town_claim, toggle_plot_border,
                                   default_perms_friend, default_perms_ally, default_perms_outsider, default_perms_resident)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                name=excluded.name, town_uuid=excluded.town_uuid, rank=excluded.rank,
                last_seen=excluded.last_seen, preferred_lang=excluded.preferred_lang,
                toggle_map=excluded.toggle_map, toggle_town_claim=excluded.toggle_town_claim, toggle_plot_border=excluded.toggle_plot_border,
                default_perms_friend=excluded.default_perms_friend, default_perms_ally=excluded.default_perms_ally,
                default_perms_outsider=excluded.default_perms_outsider, default_perms_resident=excluded.default_perms_resident
        
        """.trimIndent()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, player.uuid.toString())
                ps.setString(2, player.name)
                ps.setString(3, if (player.townUuid != null) player.townUuid.toString() else null)
                ps.setString(4, player.rank.name)
                ps.setLong(5, player.lastSeen)
                ps.setString(6, player.preferredLang)
                ps.setInt(7, if (player.isToggleMap) 1 else 0)
                ps.setInt(8, if (player.isToggleTownClaim) 1 else 0)
                ps.setInt(9, if (player.isTogglePlotBorder) 1 else 0)
                ps.setString(10, player.defaultPermsFriend)
                ps.setString(11, player.defaultPermsAlly)
                ps.setString(12, player.defaultPermsOutsider)
                ps.setString(13, player.defaultPermsResident)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun getResident(uuid: UUID): Optional<TowniaPlayer> {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT * FROM residents WHERE uuid = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) return Optional.of<TowniaPlayer?>(mapResident(rs))
                }
            }
        }
        return Optional.empty<TowniaPlayer?>()
    }

    @Synchronized
    @Throws(SQLException::class)
    fun getResidentByName(name: String?): Optional<TowniaPlayer> {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT * FROM residents WHERE LOWER(name) = LOWER(?)").use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return Optional.of<TowniaPlayer?>(mapResident(rs))
                }
            }
        }
        return Optional.empty<TowniaPlayer?>()
    }

    @get:Throws(SQLException::class)
    @get:Synchronized
    val allResidents: MutableList<TowniaPlayer?>
        get() {
            val list: MutableList<TowniaPlayer?> = ArrayList<TowniaPlayer?>()
            dataSource!!.getConnection().use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM residents ORDER BY name").use { rs ->
                        while (rs.next()) list.add(mapResident(rs))
                    }
                }
            }
            return list
        }

    @Synchronized
    @Throws(SQLException::class)
    fun getResidentsByTown(townUuid: UUID): MutableList<TowniaPlayer?> {
        val list: MutableList<TowniaPlayer?> = ArrayList<TowniaPlayer?>()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT * FROM residents WHERE town_uuid = ? ORDER BY name").use { ps ->
                ps.setString(1, townUuid.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) list.add(mapResident(rs))
                }
            }
        }
        return list
    }

    @Throws(SQLException::class)
    private fun mapResident(rs: ResultSet): TowniaPlayer {
        val townStr = rs.getString("town_uuid")
        val player = TowniaPlayer(
            UUID.fromString(rs.getString("uuid")),
            rs.getString("name"),
            if (townStr != null) UUID.fromString(townStr) else null,
            TownRank.fromString(rs.getString("rank")),
            rs.getLong("last_seen"),
            rs.getString("preferred_lang")
        )
        player.friends = getFriends(player.uuid!!)
        player.isToggleMap = rs.getInt("toggle_map") == 1
        player.isToggleTownClaim = rs.getInt("toggle_town_claim") == 1
        player.isTogglePlotBorder = rs.getInt("toggle_plot_border") == 1
        val dpf = rs.getString("default_perms_friend")
        if (dpf != null) player.defaultPermsFriend = dpf
        val dpa = rs.getString("default_perms_ally")
        if (dpa != null) player.defaultPermsAlly = dpa
        val dpo = rs.getString("default_perms_outsider")
        if (dpo != null) player.defaultPermsOutsider = dpo
        val dpr = rs.getString("default_perms_resident")
        if (dpr != null) player.defaultPermsResident = dpr
        return player
    }

    @Synchronized
    @Throws(SQLException::class)
    fun getFriends(residentUuid: UUID): MutableList<String?> {
        val friends: MutableList<String?> = ArrayList<String?>()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT friend_uuid FROM resident_friends WHERE resident_uuid = ?").use { ps ->
                ps.setString(1, residentUuid.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) friends.add(rs.getString("friend_uuid"))
                }
            }
        }
        return friends
    }

    @Synchronized
    @Throws(SQLException::class)
    fun addFriend(residentUuid: UUID, friendUuid: UUID) {
        val sql = if (isMySQL)
            "INSERT IGNORE INTO resident_friends (resident_uuid, friend_uuid) VALUES (?, ?)"
        else
            "INSERT INTO resident_friends (resident_uuid, friend_uuid) VALUES (?, ?) ON CONFLICT DO NOTHING"
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, residentUuid.toString())
                ps.setString(2, friendUuid.toString())
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun removeFriend(residentUuid: UUID, friendUuid: UUID) {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("DELETE FROM resident_friends WHERE resident_uuid = ? AND friend_uuid = ?")
                .use { ps ->
                    ps.setString(1, residentUuid.toString())
                    ps.setString(2, friendUuid.toString())
                    ps.executeUpdate()
                }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun savePlot(plot: Plot) {
        val sql = if (isMySQL) """
            INSERT INTO plots (world_name, chunk_x, chunk_z, town_uuid, owner_uuid, plot_type, for_sale, price,
                               name, pvp, mobs, explosions, fire, is_outpost, perms_resident, perms_ally, perms_outsider, perms_nation)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                town_uuid=VALUES(town_uuid), owner_uuid=VALUES(owner_uuid),
                plot_type=VALUES(plot_type), for_sale=VALUES(for_sale), price=VALUES(price),
                name=VALUES(name), pvp=VALUES(pvp), mobs=VALUES(mobs), explosions=VALUES(explosions), fire=VALUES(fire),
                is_outpost=VALUES(is_outpost), perms_resident=VALUES(perms_resident), perms_ally=VALUES(perms_ally),
                perms_outsider=VALUES(perms_outsider), perms_nation=VALUES(perms_nation)
        
        """.trimIndent() else """
            INSERT INTO plots (world_name, chunk_x, chunk_z, town_uuid, owner_uuid, plot_type, for_sale, price,
                               name, pvp, mobs, explosions, fire, is_outpost, perms_resident, perms_ally, perms_outsider, perms_nation)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(world_name, chunk_x, chunk_z) DO UPDATE SET
                town_uuid=excluded.town_uuid, owner_uuid=excluded.owner_uuid,
                plot_type=excluded.plot_type, for_sale=excluded.for_sale, price=excluded.price,
                name=excluded.name, pvp=excluded.pvp, mobs=excluded.mobs, explosions=excluded.explosions, fire=excluded.fire,
                is_outpost=excluded.is_outpost, perms_resident=excluded.perms_resident, perms_ally=excluded.perms_ally,
                perms_outsider=excluded.perms_outsider, perms_nation=excluded.perms_nation
        
        """.trimIndent()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, plot.worldName)
                ps.setInt(2, plot.chunkX)
                ps.setInt(3, plot.chunkZ)
                ps.setString(4, plot.townUuid.toString())
                ps.setString(5, if (plot.ownerUuid != null) plot.ownerUuid.toString() else null)
                ps.setString(6, plot.plotType?.name)
                ps.setInt(7, if (plot.isForSale) 1 else 0)
                ps.setDouble(8, plot.price)
                ps.setString(9, plot.name)
                ps.setInt(10, if (plot.hasPvp()) 1 else 0)
                ps.setInt(11, if (plot.hasMobs()) 1 else 0)
                ps.setInt(12, if (plot.hasExplosions()) 1 else 0)
                ps.setInt(13, if (plot.hasFire()) 1 else 0)
                ps.setInt(14, if (plot.isOutpost) 1 else 0)
                ps.setString(15, plot.permsResident)
                ps.setString(16, plot.permsAlly)
                ps.setString(17, plot.permsOutsider)
                ps.setString(18, plot.permsNation)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun getPlot(worldName: String?, chunkX: Int, chunkZ: Int): Optional<Plot> {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(
                "SELECT * FROM plots WHERE world_name = ? AND chunk_x = ? AND chunk_z = ?"
            ).use { ps ->
                ps.setString(1, worldName)
                ps.setInt(2, chunkX)
                ps.setInt(3, chunkZ)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return Optional.of<Plot?>(mapPlot(rs))
                }
            }
        }
        return Optional.empty<Plot?>()
    }

    @Synchronized
    @Throws(SQLException::class)
    fun getPlotsByTown(townUuid: UUID): MutableList<Plot?> {
        val list: MutableList<Plot?> = ArrayList<Plot?>()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT * FROM plots WHERE town_uuid = ?").use { ps ->
                ps.setString(1, townUuid.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) list.add(mapPlot(rs))
                }
            }
        }
        return list
    }

    @Synchronized
    @Throws(SQLException::class)
    fun countPlotsByTown(townUuid: UUID): Int {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM plots WHERE town_uuid = ?").use { ps ->
                ps.setString(1, townUuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) return rs.getInt(1)
                }
            }
        }
        return 0
    }

    @get:Throws(SQLException::class)
    @get:Synchronized
    val allPlots: MutableList<Plot?>
        get() {
            val list: MutableList<Plot?> = ArrayList<Plot?>()
            dataSource!!.getConnection().use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM plots").use { rs ->
                        while (rs.next()) list.add(mapPlot(rs))
                    }
                }
            }
            return list
        }

    @Synchronized
    @Throws(SQLException::class)
    fun deletePlot(worldName: String?, chunkX: Int, chunkZ: Int) {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(
                "DELETE FROM plots WHERE world_name = ? AND chunk_x = ? AND chunk_z = ?"
            ).use { ps ->
                ps.setString(1, worldName)
                ps.setInt(2, chunkX)
                ps.setInt(3, chunkZ)
                ps.executeUpdate()
            }
        }
    }

    @Throws(SQLException::class)
    private fun mapPlot(rs: ResultSet): Plot {
        val ownerStr = rs.getString("owner_uuid")
        return Plot(
            rs.getString("world_name"),
            rs.getInt("chunk_x"),
            rs.getInt("chunk_z"),
            UUID.fromString(rs.getString("town_uuid")),
            if (ownerStr != null) UUID.fromString(ownerStr) else null,
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
        )
    }

    @Synchronized
    @Throws(SQLException::class)
    fun saveNation(nation: Nation) {
        val sql = if (isMySQL) """
            INSERT INTO nations (id, name, capital_town_uuid, leader_uuid, balance, board, taxes,
                                 spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch, neutral)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                name=VALUES(name), capital_town_uuid=VALUES(capital_town_uuid),
                leader_uuid=VALUES(leader_uuid), balance=VALUES(balance),
                board=VALUES(board), taxes=VALUES(taxes),
                spawn_world=VALUES(spawn_world), spawn_x=VALUES(spawn_x), spawn_y=VALUES(spawn_y),
                spawn_z=VALUES(spawn_z), spawn_yaw=VALUES(spawn_yaw), spawn_pitch=VALUES(spawn_pitch),
                neutral=VALUES(neutral)
        
        """.trimIndent() else """
            INSERT INTO nations (id, name, capital_town_uuid, leader_uuid, balance, board, taxes,
                                 spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch, neutral)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name=excluded.name, capital_town_uuid=excluded.capital_town_uuid,
                leader_uuid=excluded.leader_uuid, balance=excluded.balance,
                board=excluded.board, taxes=excluded.taxes,
                spawn_world=excluded.spawn_world, spawn_x=excluded.spawn_x, spawn_y=excluded.spawn_y,
                spawn_z=excluded.spawn_z, spawn_yaw=excluded.spawn_yaw, spawn_pitch=excluded.spawn_pitch,
                neutral=excluded.neutral
        
        """.trimIndent()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, nation.id.toString())
                ps.setString(2, nation.name)
                ps.setString(3, nation.capitalTownUuid.toString())
                ps.setString(4, nation.leaderUuid.toString())
                ps.setDouble(5, nation.balance)
                ps.setString(6, nation.board)
                ps.setDouble(7, nation.taxes)
                ps.setString(8, nation.spawnWorld)
                ps.setDouble(9, nation.spawnX)
                ps.setDouble(10, nation.spawnY)
                ps.setDouble(11, nation.spawnZ)
                ps.setFloat(12, nation.spawnYaw)
                ps.setFloat(13, nation.spawnPitch)
                ps.setInt(14, if (nation.isNeutral) 1 else 0)
                ps.executeUpdate()
            }
        }
        saveNationTitles(nation)
    }

    @Synchronized
    @Throws(SQLException::class)
    fun getNation(id: UUID): Optional<Nation> {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT * FROM nations WHERE id = ?").use { ps ->
                ps.setString(1, id.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) return Optional.of<Nation?>(mapNation(rs))
                }
            }
        }
        return Optional.empty<Nation?>()
    }

    @Synchronized
    @Throws(SQLException::class)
    fun getNationByName(name: String?): Optional<Nation> {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT * FROM nations WHERE LOWER(name) = LOWER(?)").use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs ->
                    if (rs.next()) return Optional.of<Nation?>(mapNation(rs))
                }
            }
        }
        return Optional.empty<Nation?>()
    }

    @get:Throws(SQLException::class)
    @get:Synchronized
    val allNations: MutableList<Nation?>
        get() {
            val list: MutableList<Nation?> = ArrayList<Nation?>()
            dataSource!!.getConnection().use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM nations ORDER BY name").use { rs ->
                        while (rs.next()) list.add(mapNation(rs))
                    }
                }
            }
            return list
        }

    @Synchronized
    @Throws(SQLException::class)
    fun deleteNation(id: UUID) {
        // Remove nation reference from all member towns
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(
                "UPDATE towns SET nation_uuid = NULL WHERE nation_uuid = ?"
            ).use { ps ->
                ps.setString(1, id.toString())
                ps.executeUpdate()
            }
        }
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("DELETE FROM nations WHERE id = ?").use { ps ->
                ps.setString(1, id.toString())
                ps.executeUpdate()
            }
        }
    }

    @Throws(SQLException::class)
    private fun mapNation(rs: ResultSet): Nation {
        val nation = Nation(
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
        )
        nation.isNeutral = rs.getInt("neutral") == 1
        return nation
    }

    @Synchronized
    @Throws(SQLException::class)
    fun saveNationTitles(nation: Nation) {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("DELETE FROM nation_titles WHERE nation_uuid = ?").use { ps ->
                ps.setString(1, nation.id.toString())
                ps.executeUpdate()
            }
        }
        if (!nation.titles.isEmpty() || !nation.surnames.isEmpty()) {
            val sql = "INSERT INTO nation_titles (nation_uuid, uuid, title_type, value) VALUES (?, ?, ?, ?)"
            dataSource!!.getConnection().use { connection ->
                connection.prepareStatement(sql).use { ps ->
                    for (entry in nation.titles.entries) {
                        ps.setString(1, nation.id.toString())
                        ps.setString(2, entry.key.toString())
                        ps.setString(3, "TITLE")
                        ps.setString(4, entry.value)
                        ps.addBatch()
                    }
                    for (entry in nation.surnames.entries) {
                        ps.setString(1, nation.id.toString())
                        ps.setString(2, entry.key.toString())
                        ps.setString(3, "SURNAME")
                        ps.setString(4, entry.value)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun loadNationTitles(nation: Nation) {
        nation.titles.clear()
        nation.surnames.clear()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT * FROM nation_titles WHERE nation_uuid = ?").use { ps ->
                ps.setString(1, nation.id.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val uuid = UUID.fromString(rs.getString("uuid"))
                        val type = rs.getString("title_type")
                        val value = rs.getString("value")
                        if ("TITLE" == type) {
                            nation.setTitle(uuid, value)
                        } else if ("SURNAME" == type) {
                            nation.setSurname(uuid, value)
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun addNationRelation(nationId: UUID, targetId: UUID, relation: String?) {
        val sql = if (isMySQL) """
            INSERT INTO nation_relations (nation_uuid, target_uuid, relation) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE relation=VALUES(relation)
        
        """.trimIndent() else """
            INSERT INTO nation_relations (nation_uuid, target_uuid, relation) VALUES (?, ?, ?)
            ON CONFLICT(nation_uuid, target_uuid) DO UPDATE SET relation=excluded.relation
        
        """.trimIndent()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, nationId.toString())
                ps.setString(2, targetId.toString())
                ps.setString(3, relation)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun removeNationRelation(nationId: UUID, targetId: UUID) {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(
                "DELETE FROM nation_relations WHERE nation_uuid = ? AND target_uuid = ?"
            ).use { ps ->
                ps.setString(1, nationId.toString())
                ps.setString(2, targetId.toString())
                ps.executeUpdate()
            }
        }
    }

    @get:Throws(SQLException::class)
    @get:Synchronized
    val allNationRelations: MutableMap<UUID?, MutableMap<UUID?, String?>?>
        get() {
            val map: MutableMap<UUID?, MutableMap<UUID?, String?>?> = HashMap<UUID?, MutableMap<UUID?, String?>?>()
            dataSource!!.getConnection().use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM nation_relations").use { rs ->
                        while (rs.next()) {
                            val nationId = UUID.fromString(rs.getString("nation_uuid"))
                            val targetId = UUID.fromString(rs.getString("target_uuid"))
                            val relation = rs.getString("relation")
                            map.computeIfAbsent(nationId) { k: UUID? -> HashMap<UUID?, String?>() }!!
                                .put(targetId, relation)
                        }
                    }
                }
            }
            return map
        }

    @Synchronized
    @Throws(SQLException::class)
    fun addInvite(invite: Invite) {
        val sql = "INSERT INTO invites (target_uuid, town_uuid, inviter_uuid, created_at) VALUES (?, ?, ?, ?)"
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, invite.targetUuid.toString())
                ps.setString(2, invite.townUuid.toString())
                ps.setString(3, invite.inviterUuid.toString())
                ps.setLong(4, invite.createdAt)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun getInvitesByTarget(targetUuid: UUID): MutableList<Invite?> {
        val list: MutableList<Invite?> = ArrayList<Invite?>()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(
                "SELECT * FROM invites WHERE target_uuid = ? ORDER BY created_at DESC"
            ).use { ps ->
                ps.setString(1, targetUuid.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) list.add(mapInvite(rs))
                }
            }
        }
        return list
    }

    @Synchronized
    @Throws(SQLException::class)
    fun getInvite(targetUuid: UUID, townUuid: UUID): Optional<Invite> {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(
                "SELECT * FROM invites WHERE target_uuid = ? AND town_uuid = ? LIMIT 1"
            ).use { ps ->
                ps.setString(1, targetUuid.toString())
                ps.setString(2, townUuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) return Optional.of<Invite?>(mapInvite(rs))
                }
            }
        }
        return Optional.empty<Invite?>()
    }

    @Synchronized
    @Throws(SQLException::class)
    fun deleteInvite(id: Int) {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("DELETE FROM invites WHERE id = ?").use { ps ->
                ps.setInt(1, id)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun deleteInvitesByTarget(targetUuid: UUID) {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("DELETE FROM invites WHERE target_uuid = ?").use { ps ->
                ps.setString(1, targetUuid.toString())
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun deleteInvitesByTown(townUuid: UUID) {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("DELETE FROM invites WHERE town_uuid = ?").use { ps ->
                ps.setString(1, townUuid.toString())
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    @Throws(SQLException::class)
    fun purgeExpiredInvites(expirySeconds: Int) {
        val cutoff = System.currentTimeMillis() - expirySeconds.toLong() * 1000L
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("DELETE FROM invites WHERE created_at < ?").use { ps ->
                ps.setLong(1, cutoff)
                ps.executeUpdate()
            }
        }
    }

    @Throws(SQLException::class)
    private fun mapInvite(rs: ResultSet): Invite {
        return Invite(
            rs.getInt("id"),
            UUID.fromString(rs.getString("target_uuid")),
            UUID.fromString(rs.getString("town_uuid")),
            UUID.fromString(rs.getString("inviter_uuid")),
            rs.getLong("created_at")
        )
    }

    @Synchronized
    @Throws(SQLException::class)
    fun addBankHistory(transaction: BankTransaction) {
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO bank_history (government_uuid, type, amount, reason, created_at) VALUES (?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, transaction.governmentUuid.toString())
                ps.setString(2, transaction.type.name)
                ps.setDouble(3, transaction.amount)
                ps.setString(4, transaction.reason)
                ps.setLong(5, transaction.createdAt)
                ps.executeUpdate()
            }
        }
    }

    @Throws(SQLException::class)
    fun getBankHistory(govUuid: UUID, limit: Int, offset: Int): MutableList<BankTransaction> {
        val history = ArrayList<BankTransaction>()
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement(
                "SELECT * FROM bank_history WHERE government_uuid = ? ORDER BY created_at DESC LIMIT ? OFFSET ?"
            ).use { ps ->
                ps.setString(1, govUuid.toString())
                ps.setInt(2, limit)
                ps.setInt(3, offset)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    history.add(mapBankHistory(rs))
                }
            }
        }
        return history
    }

    @Throws(SQLException::class)
    fun countBankHistory(govUuid: UUID): Int {
        var count = 0
        dataSource!!.getConnection().use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM bank_history WHERE government_uuid = ?").use { ps ->
                ps.setString(1, govUuid.toString())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    count = rs.getInt(1)
                }
            }
        }
        return count
    }

    @Throws(SQLException::class)
    private fun mapBankHistory(rs: ResultSet): BankTransaction {
        return BankTransaction(
            rs.getInt("id"),
            UUID.fromString(rs.getString("government_uuid")),
            TransactionType.valueOf(rs.getString("type")),
            rs.getDouble("amount"),
            rs.getString("reason"),
            rs.getLong("created_at")
        )
    }
}