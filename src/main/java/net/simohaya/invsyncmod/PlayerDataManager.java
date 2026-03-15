package net.simohaya.invsyncmod;

import com.google.gson.*;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.entity.effect.StatusEffectInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public class PlayerDataManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("InventorySync/Data");
    private static final Gson GSON = new GsonBuilder().create();

    private final DatabaseManager db;

    public PlayerDataManager(DatabaseManager db) {
        this.db = db;
    }

    // -------------------------------------------------------------------
    // 保存
    // -------------------------------------------------------------------

    public void savePlayer(ServerPlayerEntity player, String serverName) {
        RegistryWrapper.WrapperLookup lookup = player.getRegistryManager();

        PlayerData data = new PlayerData(
                player.getUuid(),
                player.getHealth(),
                player.getHungerManager().getFoodLevel(),
                player.getHungerManager().getSaturationLevel(),
                player.totalExperience,
                player.experienceLevel,
                player.experienceProgress,
                serializeInventory(player, lookup),
                serializeEffects(player),
                serverName
        );

        db.savePlayerData(data);
        LOGGER.info("保存完了: {}", player.getName().getString());
    }

    // -------------------------------------------------------------------
    // 復元
    // -------------------------------------------------------------------

    public void loadPlayer(ServerPlayerEntity player) {
        Optional<PlayerData> opt = db.loadPlayerData(player.getUuid());
        if (opt.isEmpty()) {
            LOGGER.info("データなし、スキップ: {}", player.getUuid());
            return;
        }

        PlayerData data = opt.get();
        RegistryWrapper.WrapperLookup lookup = player.getRegistryManager();

        player.setHealth(Math.min(data.health(), player.getMaxHealth()));
        player.getHungerManager().setFoodLevel(data.foodLevel());
        player.getHungerManager().setSaturationLevel(data.saturation());
        player.totalExperience    = data.experience();
        player.experienceLevel    = data.expLevel();
        player.experienceProgress = data.expProgress();

        if (data.inventoryJson() != null) deserializeInventory(player, data.inventoryJson(), lookup);

        player.clearStatusEffects();
        if (data.effectsJson() != null) deserializeEffects(player, data.effectsJson());

        LOGGER.info("復元完了: {}", player.getName().getString());
    }

    // -------------------------------------------------------------------
    // シリアライズ（Inventories ユーティリティを使用）
    // -------------------------------------------------------------------

    private String serializeInventory(ServerPlayerEntity player, RegistryWrapper.WrapperLookup lookup) {
        // メイン(36) + オフハンド(1) + 防具(4) = 41スロット全部まとめて保存
        DefaultedList<ItemStack> combined = DefaultedList.ofSize(41, ItemStack.EMPTY);
        for (int i = 0; i < 36; i++) combined.set(i, player.getInventory().getStack(i));
        combined.set(36, player.getInventory().getStack(40)); // オフハンド
        for (int i = 0; i < 4; i++) combined.set(37 + i, player.getInventory().getStack(36 + i)); // 防具

        NbtCompound nbt = new NbtCompound();
        Inventories.writeNbt(nbt, combined, true, lookup);
        return GSON.toJson(nbtToJson(nbt));
    }

    private String serializeEffects(ServerPlayerEntity player) {
        JsonArray arr = new JsonArray();
        for (StatusEffectInstance effect : player.getStatusEffects()) {
            StatusEffectInstance.CODEC
                    .encodeStart(NbtOps.INSTANCE, effect)
                    .result()
                    .ifPresent(tag -> {
                        if (tag instanceof NbtCompound c) arr.add(nbtToJson(c));
                    });
        }
        return GSON.toJson(arr);
    }

    // -------------------------------------------------------------------
    // デシリアライズ
    // -------------------------------------------------------------------

    private void deserializeInventory(ServerPlayerEntity player, String json, RegistryWrapper.WrapperLookup lookup) {
        NbtCompound nbt = jsonToNbt(JsonParser.parseString(json).getAsJsonObject());
        DefaultedList<ItemStack> combined = DefaultedList.ofSize(41, ItemStack.EMPTY);
        Inventories.readNbt(nbt, combined, lookup);

        for (int i = 0; i < 36; i++) player.getInventory().setStack(i, combined.get(i));
        player.getInventory().setStack(40, combined.get(36)); // オフハンド
        for (int i = 0; i < 4; i++) player.getInventory().setStack(36 + i, combined.get(37 + i)); // 防具
    }

    private void deserializeEffects(ServerPlayerEntity player, String json) {
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement el : arr) {
            NbtCompound nbt = jsonToNbt(el.getAsJsonObject());
            StatusEffectInstance.CODEC
                    .parse(NbtOps.INSTANCE, nbt)
                    .result()
                    .ifPresent(player::addStatusEffect);
        }
    }

    // -------------------------------------------------------------------
    // NbtCompound ↔ JsonObject
    // -------------------------------------------------------------------

    private JsonObject nbtToJson(NbtCompound nbt) {
        JsonObject obj = new JsonObject();
        for (String key : nbt.getKeys()) {
            obj.add(key, nbtElementToJson(nbt.get(key)));
        }
        return obj;
    }

    private JsonElement nbtElementToJson(NbtElement el) {
        if (el instanceof NbtCompound c) return nbtToJson(c);
        if (el instanceof NbtList list) {
            JsonArray arr = new JsonArray();
            for (NbtElement item : list) arr.add(nbtElementToJson(item));
            return arr;
        }
        return new JsonPrimitive(el.toString());
    }

    private NbtCompound jsonToNbt(JsonObject obj) {
        NbtCompound nbt = new NbtCompound();
        for (var entry : obj.entrySet()) putNbtValue(nbt, entry.getKey(), entry.getValue());
        return nbt;
    }

    private void putNbtValue(NbtCompound nbt, String key, JsonElement el) {
        if (el.isJsonObject()) {
            nbt.put(key, jsonToNbt(el.getAsJsonObject()));
        } else if (el.isJsonArray()) {
            NbtList list = new NbtList();
            for (JsonElement item : el.getAsJsonArray()) {
                if (item.isJsonObject()) list.add(jsonToNbt(item.getAsJsonObject()));
                else list.add(NbtString.of(item.getAsString()));
            }
            nbt.put(key, list);
        } else {
            nbt.putString(key, el.getAsString());
        }
    }
}