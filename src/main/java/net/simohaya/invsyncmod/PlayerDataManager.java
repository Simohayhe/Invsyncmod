package net.simohaya.invsyncmod;

import com.google.gson.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.effect.StatusEffectInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public class PlayerDataManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("InventorySync/Data");
    private static final Gson GSON = new GsonBuilder().create();

    // インベントリのスロット定数
    // PlayerInventory: 0-8=ホットバー, 9-35=メイン, 36-39=防具, 40=オフハンド
    private static final int OFFHAND_SLOT = 40;
    private static final int ARMOR_START  = 36;
    private static final int ARMOR_END    = 40;

    private final DatabaseManager db;

    public PlayerDataManager(DatabaseManager db) {
        this.db = db;
    }

    // -------------------------------------------------------------------
    // 保存
    // -------------------------------------------------------------------

    public void savePlayer(ServerPlayerEntity player, String serverName) {
        RegistryWrapper.WrapperLookup lookup = player.getServerWorld().getRegistryManager();

        PlayerData data = new PlayerData(
                player.getUuid(),
                player.getHealth(),
                player.getHungerManager().getFoodLevel(),
                player.getHungerManager().getSaturationLevel(),
                player.totalExperience,
                player.experienceLevel,
                player.experienceProgress,
                serializeSlots(player, 0, 36, lookup),   // メインインベントリ
                serializeSlot(player, OFFHAND_SLOT, lookup),  // オフハンド
                serializeSlots(player, ARMOR_START, ARMOR_END, lookup), // 防具
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
        RegistryWrapper.WrapperLookup lookup = player.getServerWorld().getRegistryManager();

        player.setHealth(Math.min(data.health(), player.getMaxHealth()));
        player.getHungerManager().setFoodLevel(data.foodLevel());
        player.getHungerManager().setSaturationLevel(data.saturation());
        player.totalExperience    = data.experience();
        player.experienceLevel    = data.expLevel();
        player.experienceProgress = data.expProgress();

        if (data.inventoryJson() != null) deserializeSlots(player, data.inventoryJson(), 0, lookup);
        if (data.offhandJson()   != null) deserializeSlot(player, data.offhandJson(), OFFHAND_SLOT, lookup);
        if (data.armorJson()     != null) deserializeSlots(player, data.armorJson(), ARMOR_START, lookup);

        player.clearStatusEffects();
        if (data.effectsJson() != null) deserializeEffects(player, data.effectsJson());

        LOGGER.info("復元完了: {}", player.getName().getString());
    }

    // -------------------------------------------------------------------
    // シリアライズ
    // -------------------------------------------------------------------

    /** 指定範囲のスロットを JSON 配列に変換する */
    private String serializeSlots(ServerPlayerEntity player, int from, int to, RegistryWrapper.WrapperLookup lookup) {
        JsonArray arr = new JsonArray();
        for (int i = from; i < to; i++) {
            arr.add(itemToJson(player.getInventory().getStack(i), i, lookup));
        }
        return GSON.toJson(arr);
    }

    /** 単一スロットを JSON オブジェクトに変換する */
    private String serializeSlot(ServerPlayerEntity player, int slot, RegistryWrapper.WrapperLookup lookup) {
        return GSON.toJson(itemToJson(player.getInventory().getStack(slot), slot, lookup));
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

    /** JSON 配列からスロット群を復元する（slotOffset: armor は 36 から始まるためオフセット調整用）*/
    private void deserializeSlots(ServerPlayerEntity player, String json, int slotOffset, RegistryWrapper.WrapperLookup lookup) {
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            int slot = obj.get("slot").getAsInt();
            player.getInventory().setStack(slot, jsonToItem(obj, lookup));
        }
    }

    /** JSON オブジェクトから単一スロットを復元する */
    private void deserializeSlot(ServerPlayerEntity player, String json, int slot, RegistryWrapper.WrapperLookup lookup) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        player.getInventory().setStack(slot, jsonToItem(obj, lookup));
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
    // ItemStack ↔ JsonObject
    // -------------------------------------------------------------------

    private JsonObject itemToJson(ItemStack stack, int slot, RegistryWrapper.WrapperLookup lookup) {
        JsonObject obj = new JsonObject();
        obj.addProperty("slot", slot);
        if (stack.isEmpty()) {
            obj.addProperty("empty", true);
        } else {
            // toNbt(RegistryWrapper.WrapperLookup) — 1.21.x Yarn API
            obj.add("nbt", nbtToJson(stack.toNbt(lookup)));
        }
        return obj;
    }

    private ItemStack jsonToItem(JsonObject obj, RegistryWrapper.WrapperLookup lookup) {
        if (obj.has("empty") && obj.get("empty").getAsBoolean()) return ItemStack.EMPTY;
        if (!obj.has("nbt")) return ItemStack.EMPTY;
        // fromNbt(RegistryWrapper.WrapperLookup, NbtCompound) — 1.21.x Yarn API
        return ItemStack.fromNbt(lookup, jsonToNbt(obj.get("nbt").getAsJsonObject()))
                .orElse(ItemStack.EMPTY);
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