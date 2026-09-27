package com.wsteam.wandscape.content.production;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.content.production.event.RecipeUnlockedEvent;
import com.wsteam.wandscape.content.production.internal.ColonyRecipeSavedData;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.util.ItemKey;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manager for production recipe unlocking and gating.
 *
 * <p>Synthesize recipes start fully locked for each colony.
 * They are permanently unlocked through multiple triggers:
 * <ul>
 *     <li>Warehouse deposit: when an item is added to the colony warehouse</li>
 *     <li>Existing warehouse inventory synchronization</li>
 *     <li>Explicit API / command / quest unlocks</li>
 * </ul>
 */
public final class ProductionRecipeManager {
    private static final String TAG = "ProductionRecipeManager";

    public static final String SOURCE_WAREHOUSE_DEPOSIT = "warehouse_deposit";
    public static final String SOURCE_WAREHOUSE_SYNC = "warehouse_sync";
    public static final String SOURCE_BLUEPRINT = "blueprint";
    /** 法杖鉴定模式：对着方块右键就地解锁它对应的合成配方。 */
    public static final String SOURCE_WAND_IDENTIFY = "wand_identify";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_COMMAND = "command";

    private ProductionRecipeManager() {}

    /**
     * Normalizes an item or recipe ID to standard format with namespace (defaulting to "minecraft:").
     */
    public static String normalizeRecipeId(@Nullable String id) {
        if (id == null || id.isEmpty()) return "";
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /**
     * Check if a synthesize recipe is unlocked for the specified colony.
     *
     * @param colonyId the colony UUID
     * @param recipeOrItemId the recipe or output item ID
     * @return {@code true} if unlocked; {@code false} if locked, colony null, or recipe null
     */
    public static boolean isSynthesizeUnlocked(@Nullable UUID colonyId, @Nullable String recipeOrItemId) {
        if (!Config.isRecipeLockEnabled()) {
            return recipeOrItemId != null && !recipeOrItemId.isEmpty();
        }
        if (colonyId == null || recipeOrItemId == null) return false;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;

        String normalized = normalizeRecipeId(recipeOrItemId);
        ColonyRecipeSavedData data = ColonyRecipeSavedData.get(server);
        return data.isRecipeUnlocked(colonyId, normalized);
    }

    /**
     * Permanently unlock a synthesize recipe for a colony.
     *
     * @param colonyId the colony UUID
     * @param recipeOrItemId the recipe or output item ID
     * @param source the unlock source description (e.g. "warehouse_deposit")
     * @return {@code true} if the recipe was newly unlocked; {@code false} if already unlocked or invalid
     */
    public static boolean unlockSynthesize(@Nullable UUID colonyId, @Nullable String recipeOrItemId, String source) {
        if (colonyId == null || recipeOrItemId == null) return false;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;

        String normalized = normalizeRecipeId(recipeOrItemId);
        ColonyRecipeSavedData data = ColonyRecipeSavedData.get(server);
        boolean newlyUnlocked = data.unlockRecipe(colonyId, normalized);
        if (newlyUnlocked) {
            Log.info(TAG, "[Recipe] Colony {} unlocked recipe '{}' via {}",
                    colonyId.toString().substring(0, 8), normalized, source);
            NeoForge.EVENT_BUS.post(new RecipeUnlockedEvent(colonyId, normalized, source));
        }
        return newlyUnlocked;
    }

    /**
     * Lock a synthesize recipe for a colony.
     *
     * @return {@code true} if it was unlocked and is now locked; {@code false} otherwise
     */
    public static boolean lockSynthesize(@Nullable UUID colonyId, @Nullable String recipeOrItemId) {
        if (colonyId == null || recipeOrItemId == null) return false;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;

        String normalized = normalizeRecipeId(recipeOrItemId);
        ColonyRecipeSavedData data = ColonyRecipeSavedData.get(server);
        boolean locked = data.lockRecipe(colonyId, normalized);
        if (locked) {
            Log.info(TAG, "[Recipe] Colony {} locked recipe '{}'",
                    colonyId.toString().substring(0, 8), normalized);
        }
        return locked;
    }

    /**
     * Permanently unlock all synthesize recipes for a colony.
     *
     * @param colonyId the colony UUID
     * @param source the unlock source description (e.g. "command")
     * @return the number of recipes that were newly unlocked
     */
    public static int unlockAllSynthesize(@Nullable UUID colonyId, String source) {
        if (colonyId == null) return 0;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 0;
        var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (loader == null) return 0;

        ColonyRecipeSavedData data = ColonyRecipeSavedData.get(server);
        int newlyUnlocked = 0;
        for (SynthesizeRecipe recipe : loader.getAllSynthesizeRecipes()) {
            String normalized = normalizeRecipeId(recipe.id());
            if (data.unlockRecipe(colonyId, normalized)) {
                newlyUnlocked++;
                NeoForge.EVENT_BUS.post(new RecipeUnlockedEvent(colonyId, normalized, source));
            }
        }
        if (newlyUnlocked > 0) {
            Log.info(TAG, "[Recipe] Colony {} unlocked all synthesize recipes ({} newly unlocked) via {}",
                    colonyId.toString().substring(0, 8), newlyUnlocked, source);
        }
        return newlyUnlocked;
    }

    /**
     * Lock all synthesize recipes for a colony.
     *
     * @return the number of recipes that were locked
     */
    public static int lockAllSynthesize(@Nullable UUID colonyId) {
        if (colonyId == null) return 0;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 0;

        ColonyRecipeSavedData data = ColonyRecipeSavedData.get(server);
        int locked = data.lockAllRecipes(colonyId);
        if (locked > 0) {
            Log.info(TAG, "[Recipe] Colony {} locked all recipes ({} locked)",
                    colonyId.toString().substring(0, 8), locked);
        }
        return locked;
    }

    /**
     * Returns the total number of synthesize recipes registered in the loader.
     */
    public static int getTotalSynthesizeRecipeCount() {
        var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
        return loader != null ? loader.getAllSynthesizeRecipes().size() : 0;
    }

    /**
     * Returns an unmodifiable snapshot of all unlocked synthesize recipe IDs for the colony.
     */
    public static Set<String> getUnlockedRecipes(@Nullable UUID colonyId) {
        if (!Config.isRecipeLockEnabled()) {
            var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
            if (loader != null) {
                Set<String> all = new java.util.LinkedHashSet<>();
                for (SynthesizeRecipe recipe : loader.getAllSynthesizeRecipes()) {
                    all.add(normalizeRecipeId(recipe.id()));
                }
                return Collections.unmodifiableSet(all);
            }
        }
        if (colonyId == null) return Set.of();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return Set.of();

        return ColonyRecipeSavedData.get(server).getUnlockedRecipes(colonyId);
    }

    /**
     * Called whenever an item is added to the colony warehouse. If the item has a valid
     * synthesize recipe and is not yet unlocked, permanently unlocks it.
     */
    public static void checkAndUnlockOnWarehouseAdd(@Nullable UUID colonyId, @Nullable String itemId) {
        if (colonyId == null || itemId == null) return;
        var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (loader == null) return;

        // Check if there is a synthesize recipe that outputs this item
        if (loader.getSynthesizeRecipe(itemId) != null) {
            unlockSynthesize(colonyId, itemId, SOURCE_WAREHOUSE_DEPOSIT);
        }
    }

    /**
     * Synchronize and unlock recipes for all items already stored in the colony's warehouse.
     */
    public static void syncWarehouseItems(@Nullable UUID colonyId, ColonyItemBank bank) {
        if (colonyId == null || bank == null) return;
        var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (loader == null) return;

        Map<ItemKey, Long> snapshot = bank.getSnapshot(colonyId);
        for (var entry : snapshot.entrySet()) {
            if (entry.getValue() > 0) {
                String itemId = entry.getKey().itemId();
                if (loader.getSynthesizeRecipe(itemId) != null) {
                    unlockSynthesize(colonyId, itemId, SOURCE_WAREHOUSE_SYNC);
                }
            }
        }
    }
}
