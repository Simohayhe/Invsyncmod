package net.simohaya.invsyncmod;

import com.google.gson.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        JsonArray arr = new JsonArray();
        // メイン36 + 防具4 + オフハンド1 = 合計41スロット
        int total = player.getInventory().size();
        for (int i = 0; i < total; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("slot", i);
                // ItemStack.CODEC でシリアライズ
                ItemStack.CODEC.encodeStart(lookup.getOps(NbtOps.INSTANCE), stack)
                        .result()
                        .ifPresent(tag -> {
                            if (tag instanceof NbtCompound c) obj.add("nbt", nbtToJson(c));
                        });
                arr.add(obj);
            }
        }
        return GSON.toJson(arr);
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
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        player.getInventory().clear();
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            int slot = obj.get("slot").getAsInt();
            if (obj.has("nbt")) {
                NbtCompound nbt = jsonToNbt(obj.get("nbt").getAsJsonObject());
                ItemStack.CODEC.parse(lookup.getOps(NbtOps.INSTANCE), nbt)
                        .result()
                        .ifPresent(stack -> player.getInventory().setStack(slot, stack));
            }
        }
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