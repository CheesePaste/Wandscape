package com.wsteam.wandscape.content.production.internal;

import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-colony persistent storage of permanently unlocked production recipes.
 *
 * <p>除玩家挣来的解锁外，还按殖民地记一份**合成树指纹**（{@link #getTreeFingerprint}）：配方表变了
 * 才需要沿树重推下游，指纹没变就整段跳过，所以反复开服不产生重扫开销（见
 * {@code ProductionRecipeManager.resyncTree}）。缺指纹（旧档 / 新殖民地）时按「需要重扫」处理。
 */
public class ColonyRecipeSavedData extends SavedData {
    private static final String TAG = "ColonyRecipeSavedData";
    public static final String DATA_NAME = "wandscape_colony_recipes";
    /** v2：加了按殖民地的合成树指纹（v1 存档没有该字段，读作「需要重扫」）。 */
    public static final int CURRENT_VERSION = 2;

    private static final String KEY_VERSION = "version";
    private static final String KEY_COLONIES = "colonies";
    private static final String KEY_COLONY_ID = "colony_id";
    private static final String KEY_RECIPES = "recipes";
    private static final String KEY_TREE_FINGERPRINT = "tree_fingerprint";

    /** colonyId -> set of unlocked recipe IDs (normalized) */
    private final Map<UUID, Set<String>> unlockedRecipes = new ConcurrentHashMap<>();
    /** colonyId -> 该镇上次沿合成树推导时用的配方表指纹；缺省表示还没推过 */
    private final Map<UUID, Integer> treeFingerprints = new ConcurrentHashMap<>();

    private static final Factory<ColonyRecipeSavedData> FACTORY = new Factory<>(
            ColonyRecipeSavedData::new,
            ColonyRecipeSavedData::load,
            null
    );

    public static ColonyRecipeSavedData get(Level level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static ColonyRecipeSavedData get(MinecraftServer server) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * Unlock a recipe for a colony.
     *
     * @return {@code true} if this recipe was newly unlocked; {@code false} if already unlocked
     */
    public boolean unlockRecipe(UUID colonyId, String recipeId) {
        if (colonyId == null || recipeId == null || recipeId.isEmpty()) return false;
        boolean added = unlockedRecipes
                .computeIfAbsent(colonyId, k -> ConcurrentHashMap.newKeySet())
                .add(recipeId);
        if (added) {
            setDirty();
        }
        return added;
    }

    /**
     * Lock a recipe for a colony.
     *
     * @return {@code true} if the recipe was previously unlocked and has now been locked
     */
    public boolean lockRecipe(UUID colonyId, String recipeId) {
        if (colonyId == null || recipeId == null || recipeId.isEmpty()) return false;
        Set<String> set = unlockedRecipes.get(colonyId);
        if (set != null && set.remove(recipeId)) {
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Lock all recipes for a colony.
     *
     * @return the number of recipes that were removed
     */
    public int lockAllRecipes(UUID colonyId) {
        if (colonyId == null) return 0;
        Set<String> set = unlockedRecipes.remove(colonyId);
        if (set != null && !set.isEmpty()) {
            setDirty();
            return set.size();
        }
        return 0;
    }

    /**
     * Check if a recipe is unlocked for a colony.
     */
    public boolean isRecipeUnlocked(UUID colonyId, String recipeId) {
        if (colonyId == null || recipeId == null || recipeId.isEmpty()) return false;
        Set<String> set = unlockedRecipes.get(colonyId);
        return set != null && set.contains(recipeId);
    }

    /**
     * Get an unmodifiable view of all unlocked recipe IDs for a colony.
     */
    public Set<String> getUnlockedRecipes(UUID colonyId) {
        if (colonyId == null) return Set.of();
        Set<String> set = unlockedRecipes.get(colonyId);
        return set != null ? Collections.unmodifiableSet(set) : Set.of();
    }

    /** 所有在档的殖民地（有解锁记录或有合成树指纹的都算）。 */
    public Set<UUID> colonyIds() {
        Set<UUID> ids = new LinkedHashSet<>(unlockedRecipes.keySet());
        ids.addAll(treeFingerprints.keySet());
        return ids;
    }

    /** 该殖民地上次推导时用的配方表指纹；{@code null} 表示没推过（新镇 / v1 旧档）。 */
    @Nullable
    public Integer getTreeFingerprint(UUID colonyId) {
        return colonyId == null ? null : treeFingerprints.get(colonyId);
    }

    /** 记下该殖民地已按这版配方表推导过。 */
    public void setTreeFingerprint(UUID colonyId, int fingerprint) {
        if (colonyId == null) return;
        Integer previous = treeFingerprints.put(colonyId, fingerprint);
        if (previous == null || previous != fingerprint) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(KEY_VERSION, CURRENT_VERSION);
        ListTag colonyList = new ListTag();
        for (UUID colonyId : colonyIds()) {
            Set<String> recipes = unlockedRecipes.get(colonyId);
            Integer fingerprint = treeFingerprints.get(colonyId);
            if ((recipes == null || recipes.isEmpty()) && fingerprint == null) continue;
            CompoundTag colonyTag = new CompoundTag();
            colonyTag.putUUID(KEY_COLONY_ID, colonyId);
            if (recipes != null && !recipes.isEmpty()) {
                ListTag recipeList = new ListTag();
                for (String r : recipes) {
                    recipeList.add(StringTag.valueOf(r));
                }
                colonyTag.put(KEY_RECIPES, recipeList);
            }
            if (fingerprint != null) {
                colonyTag.putInt(KEY_TREE_FINGERPRINT, fingerprint);
            }
            colonyList.add(colonyTag);
        }
        tag.put(KEY_COLONIES, colonyList);
        return tag;
    }

    private static ColonyRecipeSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ColonyRecipeSavedData data = new ColonyRecipeSavedData();
        int version = tag.getInt(KEY_VERSION);
        if (version < 1) {
            Log.info(TAG, "Initializing new or unversioned ColonyRecipeSavedData (version {})", CURRENT_VERSION);
        } else if (version < 2) {
            Log.info(TAG, "Migrating ColonyRecipeSavedData v{} → v{}: 无合成树指纹的镇会在下次启动重扫一次",
                    version, CURRENT_VERSION);
        }

        ListTag colonyList = tag.getList(KEY_COLONIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < colonyList.size(); i++) {
            CompoundTag colonyTag = colonyList.getCompound(i);
            if (!colonyTag.hasUUID(KEY_COLONY_ID)) continue;
            UUID colonyId = colonyTag.getUUID(KEY_COLONY_ID);
            Set<String> recipes = ConcurrentHashMap.newKeySet();
            ListTag recipeList = colonyTag.getList(KEY_RECIPES, Tag.TAG_STRING);
            for (int j = 0; j < recipeList.size(); j++) {
                recipes.add(recipeList.getString(j));
            }
            data.unlockedRecipes.put(colonyId, recipes);
            if (colonyTag.contains(KEY_TREE_FINGERPRINT)) {
                data.treeFingerprints.put(colonyId, colonyTag.getInt(KEY_TREE_FINGERPRINT));
            }
        }
        return data;
    }
}
