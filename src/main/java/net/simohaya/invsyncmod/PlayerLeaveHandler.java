package net.simohaya.invsyncmod.events;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.simohaya.invsyncmod.PlayerDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayerLeaveHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("InventorySync/Leave");

    private final PlayerDataManager dataManager;
    private final String serverName;

    public PlayerLeaveHandler(PlayerDataManager dataManager, String serverName) {
        this.dataManager = dataManager;
        this.serverName  = serverName;
    }

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