package com.wsteam.wandscape.content.production;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 默认解锁配方清单，单文件 {@code data/<命名空间>/default_recipes.json}
 * （模组自带 {@code data/wandscape/default_recipes.json}）。
 *
 * <p>列在这里的配方对每个殖民地**始终视为已解锁**：不必存入仓库、消耗物品图纸或用法杖鉴定，
 * 新殖民地开局即可合成这些基础建材。清单是基准而非存档记录——它不写进
 * {@code ColonyRecipeSavedData}，所以 {@code /wandscape recipe lock_all} 锁的是存档里那份
 * "额外解锁"，锁不掉清单内容（想收回就把该 id 从文件里删掉）。
 *
 * <p>多个命名空间的同名文件取并集，整合包/数据包可另写一份塞进自己的命名空间来扩充。
 * 格式：{@code {"recipes": ["minecraft:oak_log", ...]}}，id 带不带 {@code minecraft:} 前缀皆可。
 */
public class DefaultRecipeUnlocks extends SimpleJsonResourceReloadListener {
    private static final String TAG = "DefaultRecipeUnlocks";
    private static final String FILE_PATH = "default_recipes.json";
    private static final String KEY_RECIPES = "recipes";
    private static final Gson GSON = new GsonBuilder().create();

    /** 归一化后的清单；reload 时整体替换（空文件 = 空清单）。 */
    private static volatile Set<String> unlocked = Set.of();

    public DefaultRecipeUnlocks() {
        super(GSON, "");
    }

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonElement> out = new HashMap<>();
        for (String ns : manager.getNamespaces()) {
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(ns, FILE_PATH);
            var resource = manager.getResource(rl);
            if (resource.isEmpty()) continue;
            try (var reader = resource.get().openAsReader()) {
                out.put(rl, JsonParser.parseReader(reader));
            } catch (Exception e) {
                Log.warn(TAG, "Failed to read '{}': {}", rl, e.getMessage());
            }
        }
        return out;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager manager, ProfilerFiller profiler) {
        Set<String> union = new LinkedHashSet<>();
        for (var entry : data.entrySet()) {
            JsonElement root = entry.getValue();
            if (root == null || !root.isJsonObject()) {
                Log.warn(TAG, "'{}' must be a JSON object, skipped", entry.getKey());
                continue;
            }
            JsonObject obj = root.getAsJsonObject();
            JsonArray arr = obj.getAsJsonArray(KEY_RECIPES);
            if (arr == null) {
                Log.warn(TAG, "'{}' has no '{}' array, skipped", entry.getKey(), KEY_RECIPES);
                continue;
            }
            int before = union.size();
            for (JsonElement element : arr) {
                if (element == null || !element.isJsonPrimitive()) continue;
                String raw = element.getAsString();
                if (raw == null || raw.isBlank()) continue;
                union.add(ProductionRecipeManager.normalizeRecipeId(raw.trim()));
            }
            Log.info(TAG, "Loaded {} default-unlocked recipes from '{}'", union.size() - before, entry.getKey());
        }
        unlocked = Collections.unmodifiableSet(union);
        Log.info(TAG, "{} default-unlocked recipes active", unlocked.size());
    }

    /** 该配方（带不带 {@code minecraft:} 前缀均可）是否在默认解锁清单里。 */
    public boolean contains(@Nullable String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) return false;
        Set<String> snapshot = unlocked;
        return snapshot.contains(ProductionRecipeManager.normalizeRecipeId(recipeId));
    }

    /** 默认解锁清单的并集（归一化 id，不可变）；未 reload 过或文件缺失时为空集。 */
    public Set<String> ids() {
        return unlocked;
    }
}
