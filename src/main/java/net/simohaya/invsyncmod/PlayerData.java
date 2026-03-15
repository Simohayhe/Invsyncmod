package net.simohaya.invsyncmod;

import java.util.UUID;

public record PlayerData(
        UUID   uuid,
        float  health,
        int    foodLevel,
        float  saturation,
        int    experience,
        int    expLevel,
        float  expProgress,
        String inventoryJson,  // メイン+オフハンド+防具をまとめて保存
        String effectsJson,
        String lastServer
) {}