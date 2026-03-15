package net.simohaya.invsyncmod;

import com.google.gson.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
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

    public void savePlayerSilent(ServerPlayerEntity player, String serverName, boolean doLog) {
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
        if (doLog) {
            LOGGER.info("定期保存完了: {}", player.getName().getString());
        }
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
    // シリアライズ
    // -------------------------------------------------------------------

    private String serializeInventory(ServerPlayerEntity player, RegistryWrapper.WrapperLookup lookup) {
        JsonArray arr = new JsonArray();
        int total = player.getInventory().size();
        for (int i = 0; i < total; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("slot", i);
                ItemStack.CODEC.encodeStart(lookup.getOps(NbtOps.INSTANCE), stack)
                        .result()
                        .ifPresent(tag -> obj.addProperty("nbt", tag.toString()));
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
                    .ifPresent(tag -> arr.add(tag.toString()));
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
                String nbtString = obj.get("nbt").getAsString();
                try {
                    NbtCompound nbt = net.minecraft.nbt.StringNbtReader.readCompound(nbtString);
                    {
                        ItemStack.CODEC.parse(lookup.getOps(NbtOps.INSTANCE), nbt)
                                .result()
                                .ifPresent(stack -> player.getInventory().setStack(slot, stack));
                    }
                } catch (Exception e) {
                    LOGGER.warn("スロット{}の復元に失敗: {}", slot, e.getMessage());
                }
            }
        }
    }

    private void deserializeEffects(ServerPlayerEntity player, String json) {
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement el : arr) {
            try {
                NbtCompound nbt = net.minecraft.nbt.StringNbtReader.readCompound(el.getAsString());
                {
                    StatusEffectInstance.CODEC
                            .parse(NbtOps.INSTANCE, nbt)
                            .result()
                            .ifPresent(player::addStatusEffect);
                }
            } catch (Exception e) {
                LOGGER.warn("エフェクト復元に失敗: {}", e.getMessage());
            }
        }
    }
}