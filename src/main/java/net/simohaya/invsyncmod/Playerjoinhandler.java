package net.simohaya.invsyncmod.events;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.simohaya.invsyncmod.PlayerDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * プレイヤーがサーバーに参加したときに MySQL からデータを読み込んで適用する。
 * Velocity 経由でサーバースイッチしてきたプレイヤーも JOIN が発火するため、
 * これだけで「サーバー移動時の復元」をカバーできる。
 */
public class PlayerJoinHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("InventorySync/Join");

    private final PlayerDataManager dataManager;

    public PlayerJoinHandler(PlayerDataManager dataManager) {
        this.dataManager = dataManager;
    }

    /** イベントを登録する。InvsyncmodMod#onInitialize() から呼ぶ。 */
    public void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // JOIN イベントはネットワークスレッドで発火するため
            // サーバーメインスレッドに処理を委譲する
            server.execute(() -> {
                try {
                    dataManager.loadPlayer(handler.player);
                    LOGGER.info("参加時復元: {}", handler.player.getName().getString());
                } catch (Exception e) {
                    LOGGER.error("参加時復元に失敗: {}", handler.player.getName().getString(), e);
                }
            });
        });
    }
}