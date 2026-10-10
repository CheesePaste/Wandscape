package com.wsteam.wandscape.content.production;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.content.production.event.RecipeUnlockedEvent;
import com.wsteam.wandscape.content.production.internal.ColonyRecipeSavedData;
import com.wsteam.wandscape.content.production.internal.VanillaRecipeTree;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import com.wsteam.wandscape.foundation.util.ItemKey;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manager for production recipe unlocking and gating.
 *
 * <p>Synthesize recipes start fully locked for each colony, except those listed in
 * {@code data/<namespace>/default_recipes.json} ({@link DefaultRecipeUnlocks}) and everything
 * those entries can be crafted into along the vanilla recipe tree ({@link VanillaRecipeTree}),
 * which every colony can synthesize from the start. Everything else is permanently unlocked
 * through multiple triggers:
 * <ul>
 *     <li>Warehouse deposit: when an item is added to the colony warehouse</li>
 *     <li>Existing warehouse inventory synchronization</li>
 *     <li>Wand identify, item blueprint, explicit API / command / quest unlocks</li>
 * </ul>
 *
 * <p>Every unlock is then propagated along the vanilla recipe tree: knowing the materials of a
 * recipe means knowing the recipe, so unlocking white wool in a colony that already knows oak
 * planks also unlocks white beds. Derived entries are recorded like any other unlock, with
 * {@link #SOURCE_RECIPE_TREE} as their source.
 */
public final class ProductionRecipeManager {
    private static final String TAG = "ProductionRecipeManager";

    public static final String SOURCE_WAREHOUSE_DEPOSIT = "warehouse_deposit";
    public static final String SOURCE_WAREHOUSE_SYNC = "warehouse_sync";
    public static final String SOURCE_BLUEPRINT = "blueprint";
    /** 法杖鉴定模式：对着方块右键就地解锁它对应的合成配方。 */
    public static final String SOURCE_WAND_IDENTIFY = "wand_identify";
    /** 沿原版合成树推导出的下游条目（材料齐全即解锁），不是玩家直接获得的那一件。 */
    public static final String SOURCE_RECIPE_TREE = "recipe_tree";
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
     * <p>Recipes listed in {@code data/<namespace>/default_recipes.json} count as unlocked for
     * every colony from the start (see {@link DefaultRecipeUnlocks}); the per-colony saved data
     * holds only the additional unlocks earned in game.
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

        String normalized = normalizeRecipeId(recipeOrItemId);
        if (Wandscape.DEFAULT_RECIPE_UNLOCKS.contains(normalized)) return true;
        // 默认清单沿合成树能推出来的下游同样恒定已解锁（图未构建时退化为只看默认清单）
        if (VanillaRecipeTree.isBaseline(normalized)) return true;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;

        ColonyRecipeSavedData data = ColonyRecipeSavedData.get(server);
        return data.isRecipeUnlocked(colonyId, normalized);
    }

    /**
     * UI-oriented gate query: {@code true} when the item has a synthesize recipe that is not
     * yet unlocked for the colony, i.e. the colony cannot produce the item at all right now.
     *
     * <p>Items without any synthesize recipe report {@code false}: there is no recipe to unlock
     * for them, so "recipe not unlocked" would be a wrong explanation for an empty stock.
     *
     * @param colonyId the colony UUID
     * @param itemId the output item ID
     * @return {@code true} if a recipe exists and is still locked
     */
    public static boolean isSynthesizeRecipeLocked(@Nullable UUID colonyId, @Nullable String itemId) {
        if (itemId == null || itemId.isEmpty()) return false;
        var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (loader == null || loader.getSynthesizeRecipe(itemId) == null) return false;
        return !isSynthesizeUnlocked(colonyId, itemId);
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
        boolean newlyUnlocked = recordUnlock(server, colonyId, normalized, source);
        if (newlyUnlocked) propagateFrom(colonyId, normalized);
        return newlyUnlocked;
    }

    /**
     * 记一条解锁：写进存档、发事件、记日志。推导不在这里——见 {@link #propagateFrom}，
     * 免得推导出的下游又反过来触发一次推导。
     */
    private static boolean recordUnlock(MinecraftServer server, UUID colonyId, String normalized, String source) {
        boolean newlyUnlocked = ColonyRecipeSavedData.get(server).unlockRecipe(colonyId, normalized);
        if (newlyUnlocked) {
            // 逐条解锁是热路径噪声：换模组 / 改数据包后的 resync 会按「配方数 × 殖民地数」放量。
            // 降为 PRODUCTION 分类的 debug（默认 INFO 时静默，不产生任何行）；汇总见 propagateFrom / resyncTree。
            Log.debug(LogCategory.PRODUCTION, TAG, "[Recipe] Colony {} unlocked recipe '{}' via {}",
                    shortId(colonyId), normalized, source);
            NeoForge.EVENT_BUS.post(new RecipeUnlockedEvent(colonyId, normalized, source));
        }
        return newlyUnlocked;
    }

    /**
     * 沿原版合成树（{@link VanillaRecipeTree}）推导下游：新解锁的条目当已知材料，凡是每个材料槽
     * 都有已解锁选项的配方一并解锁，递归下去（知道白羊毛和橡木木板，就知道白床）。推导结果同样
     * 进存档并永久生效，来源记为 {@link #SOURCE_RECIPE_TREE}；推导是增量的，只走新解锁条目的下游。
     */
    private static void propagateFrom(UUID colonyId, String seed) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        VanillaRecipeTree.buildIfNeeded(server);
        if (!VanillaRecipeTree.isBuilt()) return;

        List<String> derived = VanillaRecipeTree.deriveFrom(
                List.of(seed), id -> isSynthesizeUnlocked(colonyId, id));
        if (derived.isEmpty()) return;

        int recorded = 0;
        for (String id : derived) {
            if (recordUnlock(server, colonyId, id, SOURCE_RECIPE_TREE)) recorded++;
        }
        if (recorded > 0) {
            Log.info(TAG, "[Recipe] Colony {} derived {} downstream recipe(s) from '{}'",
                    shortId(colonyId), recorded, seed);
        }
    }

    /**
     * 开局 / 数据包重载后重扫全部殖民地：配方表变了（换模组、改数据包）就按各镇现有解锁集重推一遍，
     * 补上按旧表推不出来的下游。合成树指纹没变的镇整段跳过，所以稳态下每次启动只花几次哈希查找。
     */
    public static void resyncTree(@Nullable MinecraftServer server) {
        if (server == null) return;
        VanillaRecipeTree.buildIfNeeded(server);
        if (!VanillaRecipeTree.isBuilt()) return;

        ColonyRecipeSavedData data = ColonyRecipeSavedData.get(server);
        // 关着配方锁时全表已解锁、推导必然空转，所以这一轮盖另一枚指纹收尾：玩家把锁打开后
        // 的下一次启动会按真指纹补跑一遍，那段时间里的解锁才不会漏推导。
        int stamp = Config.isRecipeLockEnabled()
                ? VanillaRecipeTree.fingerprint()
                : ~VanillaRecipeTree.fingerprint();
        int rescanned = 0;
        int derived = 0;
        for (UUID colonyId : data.colonyIds()) {
            Integer stored = data.getTreeFingerprint(colonyId);
            if (stored != null && stored == stamp) continue;

            List<String> found = VanillaRecipeTree.deriveFrom(
                    List.copyOf(data.getUnlockedRecipes(colonyId)),
                    id -> isSynthesizeUnlocked(colonyId, id));
            for (String id : found) {
                if (recordUnlock(server, colonyId, id, SOURCE_RECIPE_TREE)) derived++;
            }
            data.setTreeFingerprint(colonyId, stamp);
            rescanned++;
        }
        if (rescanned > 0) {
            Log.info(TAG, "[Recipe] Recipe tree resync: {} colon(ies) rescanned, {} recipe(s) derived",
                    rescanned, derived);
        }
    }

    private static String shortId(UUID colonyId) {
        return colonyId.toString().substring(0, 8);
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
     * Returns an unmodifiable snapshot of all unlocked synthesize recipe IDs for the colony:
     * the default-unlocked baseline plus this colony's own unlocks.
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

        Set<String> unlocked = new java.util.LinkedHashSet<>(Wandscape.DEFAULT_RECIPE_UNLOCKS.ids());
        unlocked.addAll(VanillaRecipeTree.baseline());
        unlocked.addAll(ColonyRecipeSavedData.get(server).getUnlockedRecipes(colonyId));
        return Collections.unmodifiableSet(unlocked);
    }

    /**
     * Called whenever an item is added to the colony warehouse. If the item has a valid
     * synthesize recipe and is not yet unlocked, permanently unlocks it.
     *
     * <p>Baseline recipes are skipped: they (and everything the default list can be crafted
     * into) are already available and would only add redundant per-colony records to the save.
     */
    public static void checkAndUnlockOnWarehouseAdd(@Nullable UUID colonyId, @Nullable String itemId) {
        if (colonyId == null || itemId == null) return;
        var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (loader == null) return;

        // Check if there is a synthesize recipe that outputs this item
        if (loader.getSynthesizeRecipe(itemId) != null && !isBaselineUnlocked(itemId)) {
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
                if (loader.getSynthesizeRecipe(itemId) != null && !isBaselineUnlocked(itemId)) {
                    unlockSynthesize(colonyId, itemId, SOURCE_WAREHOUSE_SYNC);
                }
            }
        }
    }

    /** 默认清单及其合成树上下游：恒定已解锁，不必按殖民地记账。 */
    private static boolean isBaselineUnlocked(String itemId) {
        return Wandscape.DEFAULT_RECIPE_UNLOCKS.contains(itemId)
                || VanillaRecipeTree.isBaseline(normalizeRecipeId(itemId));
    }
}
