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
 */
public class ColonyRecipeSavedData extends SavedData {
    private static final String TAG = "ColonyRecipeSavedData";
    public static final String DATA_NAME = "wandscape_colony_recipes";
    public static final int CURRENT_VERSION = 1;

    private static final String KEY_VERSION = "version";
    private static final String KEY_COLONIES = "colonies";
    private static final String KEY_COLONY_ID = "colony_id";
    private static final String KEY_RECIPES = "recipes";

    /** colonyId -> set of unlocked recipe IDs (normalized) */
    private final Map<UUID, Set<String>> unlockedRecipes = new ConcurrentHashMap<>();

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

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(KEY_VERSION, CURRENT_VERSION);
        ListTag colonyList = new ListTag();
        for (var entry : unlockedRecipes.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            CompoundTag colonyTag = new CompoundTag();
            colonyTag.putUUID(KEY_COLONY_ID, entry.getKey());
            ListTag recipeList = new ListTag();
            for (String r : entry.getValue()) {
                recipeList.add(StringTag.valueOf(r));
            }
            colonyTag.put(KEY_RECIPES, recipeList);
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
        }
        return data;
    }
}
