package net.simohaya.invsyncmod;

import com.google.gson.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.effect.StatusEffectInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * プレイヤーデータのシリアライズ（Minecraft → JSON）と
 * デシリアライズ（JSON → Minecraft）を担当するクラス。
 */
public class PlayerDataManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("InventorySync/Data");
    private static final Gson GSON = new GsonBuilder().create();

    private final DatabaseManager db;

    public PlayerDataManager(DatabaseManager db) {
        this.db = db;
    }

    // -------------------------------------------------------------------
    // 保存（サーバー離脱時）
    // -------------------------------------------------------------------

    /**
     * プレイヤーの現在状態をすべて取得して MySQL に保存する。
     *
     * @param player     保存対象のプレイヤー
     * @param serverName このサーバーの識別名（config で設定）
     */
    public void savePlayer(ServerPlayerEntity player, String serverName) {
        RegistryWrapper.WrapperLookup lookup = player.getWorld().getRegistryManager();

        PlayerData data = new PlayerData(
                player.getUuid(),
                player.getHealth(),
                player.getHungerManager().getFoodLevel(),
                player.getHungerManager().getSaturationLevel(),
                player.totalExperience,
                player.experienceLevel,
                player.experienceProgress,
                serializeInventory(player, lookup),
                serializeOffhand(player, lookup),
                serializeArmor(player, lookup),
                serializeEffects(player),
                serverName
        );

        db.savePlayerData(data);
        LOGGER.info("プレイヤーデータ保存完了: {} ({})", player.getName().getString(), player.getUuid());
    }

    // -------------------------------------------------------------------
    // 復元（サーバー参加時）
    // -------------------------------------------------------------------

    /**
     * MySQL からプレイヤーデータを読み込んでプレイヤーに適用する。
     *
     * @param player 復元対象のプレイヤー
     */
    public void loadPlayer(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        Optional<PlayerData> opt = db.loadPlayerData(uuid);

        if (opt.isEmpty()) {
            LOGGER.info("保存データなし、スキップ: {}", uuid);
            return;
        }

        PlayerData data = opt.get();
        RegistryWrapper.WrapperLookup lookup = player.getWorld().getRegistryManager();

        // HP（最大値を超えないようにクランプ）
        float maxHealth = player.getMaxHealth();
        player.setHealth(Math.min(data.health(), maxHealth));

        // 食料・満腹度
        player.getHungerManager().setFoodLevel(data.foodLevel());
        player.getHungerManager().setSaturationLevel(data.saturation());

        // 経験値
        player.totalExperience  = data.experience();
        player.experienceLevel  = data.expLevel();
        player.experienceProgress = data.expProgress();

        // インベントリ
        if (data.inventoryJson() != null) {
            deserializeInventory(player, data.inventoryJson(), lookup);
        }

        // オフハンド
        if (data.offhandJson() != null) {
            deserializeOffhand(player, data.offhandJson(), lookup);
        }

        // 防具
        if (data.armorJson() != null) {
            deserializeArmor(player, data.armorJson(), lookup);
        }

        // エフェクト（既存エフェクトをクリアしてから適用）
        player.clearStatusEffects();
        if (data.effectsJson() != null) {
            deserializeEffects(player, data.effectsJson());
        }

        LOGGER.info("プレイヤーデータ復元完了: {} ({})", player.getName().getString(), uuid);
    }

    // -------------------------------------------------------------------
    // シリアライズ（Minecraft → JSON 文字列）
    // -------------------------------------------------------------------

    /** メインインベントリ（36スロット）を JSON 文字列に変換する。 */
    private String serializeInventory(ServerPlayerEntity player, RegistryWrapper.WrapperLookup lookup) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            arr.add(itemToJson(stack, i, lookup));
        }
        return GSON.toJson(arr);
    }

    /** オフハンドスロットを JSON 文字列に変換する。 */
    private String serializeOffhand(ServerPlayerEntity player, RegistryWrapper.WrapperLookup lookup) {
        ItemStack stack = player.getOffHandStack();
        JsonObject obj = itemToJson(stack, 0, lookup);
        return GSON.toJson(obj);
    }

    /** 防具スロット（ヘルメット〜ブーツ）を JSON 文字列に変換する。 */
    private String serializeArmor(ServerPlayerEntity player, RegistryWrapper.WrapperLookup lookup) {
        JsonArray arr = new JsonArray();
        int slot = 0;
        for (ItemStack stack : player.getArmorItems()) {
            arr.add(itemToJson(stack, slot++, lookup));
        }
        return GSON.toJson(arr);
    }

    /** アクティブなポーション効果を JSON 文字列に変換する。 */
    private String serializeEffects(ServerPlayerEntity player) {
        JsonArray arr = new JsonArray();
        Collection<StatusEffectInstance> effects = player.getStatusEffects();
        for (StatusEffectInstance effect : effects) {
            NbtCompound nbt = new NbtCompound();
            StatusEffectInstance.CODEC
                    .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, effect)
                    .result()
                    .ifPresent(tag -> {
                        if (tag instanceof NbtCompound c) {
                            JsonObject obj = nbtToJson(c);
                            arr.add(obj);
                        }
                    });
        }
        return GSON.toJson(arr);
    }

    // -------------------------------------------------------------------
    // デシリアライズ（JSON 文字列 → Minecraft）
    // -------------------------------------------------------------------

    /** JSON 文字列からメインインベントリを復元する。 */
    private void deserializeInventory(ServerPlayerEntity player, String json, RegistryWrapper.WrapperLookup lookup) {
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        player.getInventory().clear();
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            int slot = obj.get("slot").getAsInt();
            ItemStack stack = jsonToItem(obj, lookup);
            player.getInventory().setStack(slot, stack);
        }
    }

    /** JSON 文字列からオフハンドを復元する。 */
    private void deserializeOffhand(ServerPlayerEntity player, String json, RegistryWrapper.WrapperLookup lookup) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        ItemStack stack = jsonToItem(obj, lookup);
        player.getInventory().offHand.set(0, stack);
    }

    /** JSON 文字列から防具スロットを復元する。 */
    private void deserializeArmor(ServerPlayerEntity player, String json, RegistryWrapper.WrapperLookup lookup) {
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        int slot = 0;
        for (JsonElement el : arr) {
            if (slot >= 4) break;
            ItemStack stack = jsonToItem(el.getAsJsonObject(), lookup);
            player.getInventory().armor.set(slot++, stack);
        }
    }

    /** JSON 文字列からポーション効果を復元してプレイヤーに付与する。 */
    private void deserializeEffects(ServerPlayerEntity player, String json) {
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement el : arr) {
            NbtCompound nbt = jsonToNbt(el.getAsJsonObject());
            StatusEffectInstance.CODEC
                    .parse(net.minecraft.nbt.NbtOps.INSTANCE, nbt)
                    .result()
                    .ifPresent(player::addStatusEffect);
        }
    }

    // -------------------------------------------------------------------
    // ヘルパー（ItemStack ↔ JSON）
    // -------------------------------------------------------------------

    /** ItemStack を slot 番号付きの JsonObject に変換する。 */
    private JsonObject itemToJson(ItemStack stack, int slot, RegistryWrapper.WrapperLookup lookup) {
        JsonObject obj = new JsonObject();
        obj.addProperty("slot", slot);
        if (stack.isEmpty()) {
            obj.addProperty("empty", true);
        } else {
            NbtCompound nbt = new NbtCompound();
            stack.encode(lookup, nbt);
            obj.add("nbt", nbtToJson(nbt));
        }
        return obj;
    }

    /** JsonObject から ItemStack を復元する。 */
    private ItemStack jsonToItem(JsonObject obj, RegistryWrapper.WrapperLookup lookup) {
        if (obj.has("empty") && obj.get("empty").getAsBoolean()) {
            return ItemStack.EMPTY;
        }
        if (!obj.has("nbt")) return ItemStack.EMPTY;

        NbtCompound nbt = jsonToNbt(obj.get("nbt").getAsJsonObject());
        return ItemStack.fromNbt(lookup, nbt).orElse(ItemStack.EMPTY);
    }

    // -------------------------------------------------------------------
    // ヘルパー（NbtCompound ↔ JsonObject）
    // -------------------------------------------------------------------

    /** NbtCompound を再帰的に JsonObject へ変換する。 */
    private JsonObject nbtToJson(NbtCompound nbt) {
        JsonObject obj = new JsonObject();
        for (String key : nbt.getKeys()) {
            NbtElement el = nbt.get(key);
            obj.add(key, nbtElementToJson(el));
        }
        return obj;
    }

    private JsonElement nbtElementToJson(NbtElement el) {
        if (el instanceof NbtCompound c) {
            return nbtToJson(c);
        } else if (el instanceof NbtList list) {
            JsonArray arr = new JsonArray();
            for (NbtElement item : list) arr.add(nbtElementToJson(item));
            return arr;
        } else {
            return new JsonPrimitive(el.asString());
        }
    }

    /** JsonObject を再帰的に NbtCompound へ変換する。 */
    private NbtCompound jsonToNbt(JsonObject obj) {
        NbtCompound nbt = new NbtCompound();
        for (var entry : obj.entrySet()) {
            putNbtValue(nbt, entry.getKey(), entry.getValue());
        }
        return nbt;
    }

    private void putNbtValue(NbtCompound nbt, String key, JsonElement el) {
        if (el.isJsonObject()) {
            nbt.put(key, jsonToNbt(el.getAsJsonObject()));
        } else if (el.isJsonArray()) {
            NbtList list = new NbtList();
            for (JsonElement item : el.getAsJsonArray()) {
                if (item.isJsonObject()) list.add(jsonToNbt(item.getAsJsonObject()));
                else list.add(net.minecraft.nbt.NbtString.of(item.getAsString()));
            }
            nbt.put(key, list);
        } else {
            nbt.putString(key, el.getAsString());
        }
    }
}