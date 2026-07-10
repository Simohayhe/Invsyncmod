package net.simohaya.invsyncmod;

import com.google.gson.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
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

    public void savePlayer(ServerPlayer player, String serverName) {
        HolderLookup.Provider lookup = player.registryAccess();

        PlayerData data = new PlayerData(
                player.getUUID(),
                player.getHealth(),
                player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel(),
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

    public void savePlayerSilent(ServerPlayer player, String serverName, boolean doLog) {
        HolderLookup.Provider lookup = player.registryAccess();

        PlayerData data = new PlayerData(
                player.getUUID(),
                player.getHealth(),
                player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel(),
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

    public void loadPlayer(ServerPlayer player) {
        Optional<PlayerData> opt = db.loadPlayerData(player.getUUID());
        if (opt.isEmpty()) {
            LOGGER.info("データなし、スキップ: {}", player.getUUID());
            return;
        }

        PlayerData data = opt.get();
        HolderLookup.Provider lookup = player.registryAccess();

        player.setHealth(Math.min(data.health(), player.getMaxHealth()));
        player.getFoodData().setFoodLevel(data.foodLevel());
        player.getFoodData().setSaturation(data.saturation());
        player.totalExperience    = data.experience();
        player.experienceLevel    = data.expLevel();
        player.experienceProgress = data.expProgress();

        if (data.inventoryJson() != null) deserializeInventory(player, data.inventoryJson(), lookup);

        player.removeAllEffects();
        if (data.effectsJson() != null) deserializeEffects(player, data.effectsJson());

        LOGGER.info("復元完了: {}", player.getName().getString());
    }

    // -------------------------------------------------------------------
    // シリアライズ
    // -------------------------------------------------------------------

    private String serializeInventory(ServerPlayer player, HolderLookup.Provider lookup) {
        JsonArray arr = new JsonArray();
        int total = player.getInventory().getContainerSize();
        for (int i = 0; i < total; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("slot", i);
                ItemStack.CODEC.encodeStart(lookup.createSerializationContext(NbtOps.INSTANCE), stack)
                        .result()
                        .ifPresent(tag -> obj.addProperty("nbt", tag.toString()));
                arr.add(obj);
            }
        }
        return GSON.toJson(arr);
    }

    private String serializeEffects(ServerPlayer player) {
        JsonArray arr = new JsonArray();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            MobEffectInstance.CODEC
                    .encodeStart(NbtOps.INSTANCE, effect)
                    .result()
                    .ifPresent(tag -> arr.add(tag.toString()));
        }
        return GSON.toJson(arr);
    }

    // -------------------------------------------------------------------
    // デシリアライズ
    // -------------------------------------------------------------------

    private void deserializeInventory(ServerPlayer player, String json, HolderLookup.Provider lookup) {
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        player.getInventory().clearContent();
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            int slot = obj.get("slot").getAsInt();
            if (obj.has("nbt")) {
                String nbtString = obj.get("nbt").getAsString();
                try {
                    CompoundTag nbt = net.minecraft.nbt.TagParser.parseCompoundFully(nbtString);
                    {
                        ItemStack.CODEC.parse(lookup.createSerializationContext(NbtOps.INSTANCE), nbt)
                                .result()
                                .ifPresent(stack -> player.getInventory().setItem(slot, stack));
                    }
                } catch (Exception e) {
                    LOGGER.warn("スロット{}の復元に失敗: {}", slot, e.getMessage());
                }
            }
        }
    }

    private void deserializeEffects(ServerPlayer player, String json) {
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement el : arr) {
            try {
                CompoundTag nbt = net.minecraft.nbt.TagParser.parseCompoundFully(el.getAsString());
                {
                    MobEffectInstance.CODEC
                            .parse(NbtOps.INSTANCE, nbt)
                            .result()
                            .ifPresent(player::addEffect);
                }
            } catch (Exception e) {
                LOGGER.warn("エフェクト復元に失敗: {}", e.getMessage());
            }
        }
    }
}
