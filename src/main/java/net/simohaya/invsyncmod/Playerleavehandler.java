package net.simohaya.invsyncmod.events;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.simohaya.invsyncmod.PlayerDataManager;
import net.simohaya.invsyncmod.InvsyncmodMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * プレイヤーがサーバーを離脱したときにデータを MySQL へ保存する。
 * Velocity がサーバースイッチを行う際も DISCONNECT が発火するため、
 * これだけで「サーバー移動時の保存」をカバーできる。
 */
public class PlayerLeaveHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("InventorySync/Leave");

    private final PlayerDataManager dataManager;
    private final String serverName;

    public PlayerLeaveHandler(PlayerDataManager dataManager, String serverName) {
        this.dataManager = dataManager;
        this.serverName  = serverName;
    }

    /** イベントを登録する。InvsyncmodMod#onInitialize() から呼ぶ。 */
    public void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            server.execute(() -> {
                try {
                    dataManager.savePlayer(handler.player, serverName);
                    LOGGER.info("離脱時保存: {}", handler.player.getName().getString());
                } catch (Exception e) {
                    LOGGER.error("離脱時保存に失敗: {}", handler.player.getName().getString(), e);
                }
            });
        });
    }
}