package net.simohaya.invsyncmod;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

public class DatabaseManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("InventorySync/DB");

    private HikariDataSource dataSource;

    public void init(Path configDir) {
        Properties props = loadProperties(configDir);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8",
                props.getProperty("db.host", "localhost"),
                props.getProperty("db.port", "3306"),
                props.getProperty("db.name", "minecraft_sync")
        ));
        config.setUsername(props.getProperty("db.user", "minecraft"));
        config.setPassword(props.getProperty("db.password", ""));
        config.setMaximumPoolSize(Integer.parseInt(props.getProperty("db.pool.max", "10")));
        config.setConnectionTimeout(Long.parseLong(props.getProperty("db.pool.timeout", "30000")));
        config.setPoolName("InventorySync-Pool");
        config.setConnectionTestQuery("SELECT 1");
        config.setMinimumIdle(2);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);

        dataSource = new HikariDataSource(config);
        LOGGER.info("DB接続プール起動完了");

        createTable();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            LOGGER.info("DB接続を閉じました");
        }
    }

    private void createTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS player_data (
                    uuid          CHAR(36)    NOT NULL PRIMARY KEY,
                    health        FLOAT       NOT NULL DEFAULT 20.0,
                    food_level    INT         NOT NULL DEFAULT 20,
                    saturation    FLOAT       NOT NULL DEFAULT 5.0,
                    experience    INT         NOT NULL DEFAULT 0,
                    exp_level     INT         NOT NULL DEFAULT 0,
                    exp_progress  FLOAT       NOT NULL DEFAULT 0.0,
                    inventory     MEDIUMTEXT,
                    effects       TEXT,
                    last_server   VARCHAR(64),
                    updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
                                              ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            LOGGER.info("player_dataテーブル確認/作成完了");
        } catch (SQLException e) {
            LOGGER.error("テーブル作成失敗", e);
            throw new RuntimeException("player_dataテーブルの作成に失敗", e);
        }
    }

    public void savePlayerData(PlayerData data) {
        String sql = """
                INSERT INTO player_data
                    (uuid, health, food_level, saturation, experience, exp_level, exp_progress,
                     inventory, effects, last_server)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    health       = VALUES(health),
                    food_level   = VALUES(food_level),
                    saturation   = VALUES(saturation),
                    experience   = VALUES(experience),
                    exp_level    = VALUES(exp_level),
                    exp_progress = VALUES(exp_progress),
                    inventory    = VALUES(inventory),
                    effects      = VALUES(effects),
                    last_server  = VALUES(last_server);
                """;

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1,  data.uuid().toString());
            ps.setFloat (2,  data.health());
            ps.setInt   (3,  data.foodLevel());
            ps.setFloat (4,  data.saturation());
            ps.setInt   (5,  data.experience());
            ps.setInt   (6,  data.expLevel());
            ps.setFloat (7,  data.expProgress());
            ps.setString(8,  data.inventoryJson());
            ps.setString(9,  data.effectsJson());
            ps.setString(10, data.lastServer());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("保存失敗: uuid={}", data.uuid(), e);
        }
    }

    public Optional<PlayerData> loadPlayerData(UUID uuid) {
        String sql = """
                SELECT health, food_level, saturation, experience, exp_level, exp_progress,
                       inventory, effects, last_server
                FROM player_data WHERE uuid = ?;
                """;

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PlayerData(
                            uuid,
                            rs.getFloat ("health"),
                            rs.getInt   ("food_level"),
                            rs.getFloat ("saturation"),
                            rs.getInt   ("experience"),
                            rs.getInt   ("exp_level"),
                            rs.getFloat ("exp_progress"),
                            rs.getString("inventory"),
                            rs.getString("effects"),
                            rs.getString("last_server")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("読込失敗: uuid={}", uuid, e);
        }
        return Optional.empty();
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("接続プールが初期化されていません");
        }
        return dataSource.getConnection();
    }

    private Properties loadProperties(Path configDir) {
        Properties props = new Properties();
        Path configFile = configDir.resolve("invsyncmod.properties");
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                props.load(in);
            } catch (IOException e) {
                LOGGER.warn("設定ファイル読込失敗、デフォルト値を使用", e);
            }
        } else {
            LOGGER.warn("設定ファイルが見つかりません: {}", configFile);
        }
        return props;
    }
}