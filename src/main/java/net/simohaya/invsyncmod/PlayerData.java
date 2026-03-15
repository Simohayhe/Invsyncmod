package net.simohaya.invsyncmod;

import java.util.UUID;

/**
 * サーバー間で同期するプレイヤーデータをまとめたレコード。
 * 各フィールドは DatabaseManager で MySQL に保存・読込される。
 */
public record PlayerData(
        UUID   uuid,
        // --- HP / 食料 ---
        float  health,
        int    foodLevel,
        float  saturation,
        // --- 経験値 ---
        int    experience,
        int    expLevel,
        float  expProgress,
        // --- アイテム（JSON シリアライズ済み文字列）---
        String inventoryJson,
        String offhandJson,
        String armorJson,
        // --- エフェクト（JSON シリアライズ済み文字列）---
        String effectsJson,
        // --- メタ ---
        String lastServer
) {}