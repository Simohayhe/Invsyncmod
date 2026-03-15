package net.simohaya.invsyncmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.simohaya.invsyncmod.events.PlayerJoinHandler;
import net.simohaya.invsyncmod.events.PlayerLeaveHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Invsyncmod implements ModInitializer {

    public static final String MOD_ID = "invsyncmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static DatabaseManager databaseManager;
    private static PlayerDataManager playerDataManager;

    @Override
    public void onInitialize() {
        LOGGER.info("InvSyncMod 起動中...");

        Path configDir = FabricLoader.getInstance().getConfigDir();

        // 設定ファイルが存在しなければデフォルトをコピー
        copyDefaultConfig(configDir);

        // このサーバーの識別名を読み込む
        String serverName = loadServerName(configDir);
        LOGGER.info("サーバー名: {}", serverName);

        // DB 初期化
        databaseManager = new DatabaseManager();
        databaseManager.init(configDir);

        // データマネージャー初期化
        playerDataManager = new PlayerDataManager(databaseManager);

        // イベント登録
        new PlayerJoinHandler(playerDataManager).register();
        new PlayerLeaveHandler(playerDataManager, serverName).register();

        // 1秒ごとの定期同期を登録
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 == 0) {
                boolean doLog = server.getTicks() % 12000 == 0;
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    playerDataManager.savePlayerSilent(player, serverName, doLog);
                }
            }
        });

        // サーバー停止時に DB 接続を閉じる
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("DB 接続を閉じています...");
            databaseManager.close();
        }));

        LOGGER.info("InvSyncMod 起動完了！");
    }

    private void copyDefaultConfig(Path configDir) {
        Path target = configDir.resolve("invsyncmod.properties");
        if (Files.exists(target)) return;

        try (InputStream in = getClass().getResourceAsStream("/invsyncmod.properties")) {
            if (in != null) {
                Files.copy(in, target);
                LOGGER.info("デフォルト設定ファイルを作成しました: {}", target);
            }
        } catch (IOException e) {
            LOGGER.warn("デフォルト設定のコピーに失敗しました", e);
        }
    }

    private String loadServerName(Path configDir) {
        Path configFile = configDir.resolve("invsyncmod.properties");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("設定ファイルの読み込みに失敗しました", e);
        }
        return props.getProperty("server.name", "default");
    }

    public static DatabaseManager getDatabaseManager()       { return databaseManager; }
    public static PlayerDataManager getPlayerDataManager()   { return playerDataManager; }
}