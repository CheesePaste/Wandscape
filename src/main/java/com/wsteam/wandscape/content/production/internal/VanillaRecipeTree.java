package com.wsteam.wandscape.content.production.internal;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.production.ProductionRecipeManager;
import com.wsteam.wandscape.content.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 原版合成树：把 {@link RecipeManager} 里的原版配方压成一张「材料 → 能做的下游」的图，
 * 供 {@link ProductionRecipeManager} 沿树推导解锁。索引是 {@link Node}（只留 id 字符串），
 * 遍历 {@link #derive} 因此不碰 MC 类型，是纯逻辑。
 *
 * <p>树覆盖的配方类型与 {@code ElementValueGenerator} 一致（合成 / 熔炼 / 烟熏 / 营火 /
 * 切石 / 锻造），所以「能推出的」与「element_mappings 里有价的」是同一个域：图鉴里不会出现
 * 有价却推不出来的条目。产出没有元素映射的配方直接丢掉——那种物品既合不出来、也不可能被解锁，
 * 留着只会白占遍历。特殊配方（{@code isSpecial()}，如染色 / 烟花 / 地图复制）没有稳定的材料表，
 * 一律跳过。
 *
 * <p>材料槽按标签展开：{@code #minecraft:planks} 之类，只要槽里有**任意一个**选项已解锁就算满足。
 *
 * <p><b>缓存与失效</b>：整张图挂在 {@link #snapshot} 上，键是配方管理器实例本身——
 * {@code /reload} 会新建一份 {@code ReloadableServerResources}，实例换了就重建，
 * 因此不需要订阅重载事件。另存一个 {@link #fingerprint()} 内容指纹，供每个殖民地比对
 * 「我上次按哪一版配方表推导过」，配方表没变就整段跳过重扫。
 */
public final class VanillaRecipeTree {
    private static final String TAG = "VanillaRecipeTree";

    /** 与 ElementValueGenerator 同一套配方类型，保证推导域与元素价域重合。 */
    @SuppressWarnings("rawtypes")
    private static final List<RecipeType> RECIPE_TYPES = List.of(
            RecipeType.CRAFTING,
            RecipeType.SMELTING,
            RecipeType.BLASTING,
            RecipeType.SMOKING,
            RecipeType.CAMPFIRE_COOKING,
            RecipeType.STONECUTTING,
            RecipeType.SMITHING);

    /**
     * 一条原版配方：产出 id + 若干材料槽，每个槽是一组可替换的选项 id。
     * 只留字符串，遍历逻辑因此不碰 MC 类型（与 {@code ElementValueGenerator.RecipeNode} 同一取舍）。
     */
    record Node(String outputId, List<List<String>> slots) {}

    /**
     * @param source      产出这张图的配方管理器实例，用于判断缓存是否还有效
     * @param consumers   材料 id → 用到它的配方（一张图里同一条配方只出现一次）
     * @param nodeCount   配方条数（仅用于日志）
     * @param baseline    默认清单沿树推导的闭包（含默认清单本身），对所有殖民地恒定已解锁
     * @param fingerprint 图内容指纹，跨重启稳定
     */
    private record Snapshot(RecipeManager source, Map<String, List<Node>> consumers,
                            int nodeCount, Set<String> baseline, int fingerprint) {}

    private static volatile Snapshot snapshot;

    private VanillaRecipeTree() {}

    // ── 构建 ──

    /**
     * 配方表换了（开局 / {@code /reload} / 换了存档）就重建，否则什么都不做。
     * 生产配方加载器还没就绪时放弃本次构建，留给下一次调用。
     */
    public static void buildIfNeeded(@Nullable MinecraftServer server) {
        if (server == null) return;
        RecipeManager recipes = server.getRecipeManager();
        if (recipes == null) return;
        Snapshot current = snapshot;
        if (current != null && current.source() == recipes) return;

        var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (loader == null) return;
        Set<String> synthesizable = new HashSet<>();
        for (SynthesizeRecipe recipe : loader.getAllSynthesizeRecipes()) {
            synthesizable.add(ProductionRecipeManager.normalizeRecipeId(recipe.id()));
        }
        if (synthesizable.isEmpty()) return;

        long started = System.nanoTime();
        Collected collected = collect(recipes, server.registryAccess(), synthesizable);

        Set<String> defaults = Wandscape.DEFAULT_RECIPE_UNLOCKS.ids();
        Set<String> baseline = new LinkedHashSet<>(defaults);
        baseline.addAll(derive(List.copyOf(defaults), id -> false, collected.consumers()));
        int fingerprint = fingerprintOf(collected.consumers(), baseline);
        snapshot = new Snapshot(recipes, collected.consumers(), collected.nodeCount(),
                Set.copyOf(baseline), fingerprint);

        Log.info(TAG, "Recipe tree built: {} ingredient(s) → {} recipe(s), {} default-derived, {} ms",
                collected.consumers().size(), collected.nodeCount(), baseline.size() - defaults.size(),
                (System.nanoTime() - started) / 1_000_000L);
    }

    /** @param consumers 材料 id → 用到它的配方；@param nodeCount 配方条数 */
    private record Collected(Map<String, List<Node>> consumers, int nodeCount) {}

    /** 是否已就绪：未构建时推导与基线查询都退化为「什么都没有」。 */
    public static boolean isBuilt() {
        return snapshot != null;
    }

    /** 图内容指纹，跨重启稳定；未构建时返回 0。 */
    public static int fingerprint() {
        Snapshot current = snapshot;
        return current == null ? 0 : current.fingerprint();
    }

    /**
     * 默认清单沿树推导出的闭包（含默认清单本身）；未构建时为空集，
     * 调用方应同时认 {@code DefaultRecipeUnlocks} 本身。
     */
    public static Set<String> baseline() {
        Snapshot current = snapshot;
        return current == null ? Set.of() : current.baseline();
    }

    /** 该条目是否属于基线闭包（即不必按殖民地记录就恒定已解锁）。 */
    public static boolean isBaseline(@Nullable String normalizedId) {
        if (normalizedId == null) return false;
        Snapshot current = snapshot;
        return current != null && current.baseline().contains(normalizedId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Collected collect(RecipeManager recipes, HolderLookup.Provider registries,
                                     Set<String> synthesizable) {
        Map<String, List<Node>> consumers = new HashMap<>();
        int nodeCount = 0;
        int skipped = 0;
        for (RecipeType type : RECIPE_TYPES) {
            for (Object holderObj : recipes.getAllRecipesFor(type)) {
                Recipe<?> recipe = ((RecipeHolder<?>) holderObj).value();
                if (recipe.isSpecial()) continue;

                ItemStack result = recipe.getResultItem(registries);
                if (result.isEmpty()) continue;
                String outputId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
                if (!synthesizable.contains(outputId)) {
                    skipped++;
                    continue;
                }

                NonNullList<Ingredient> ingredients = recipe.getIngredients();
                List<List<String>> slots = new ArrayList<>();
                boolean complete = true;
                for (Ingredient ingredient : ingredients) {
                    if (ingredient == Ingredient.EMPTY) continue;
                    ItemStack[] options = ingredient.getItems();
                    if (options.length == 0) {
                        complete = false;
                        break;
                    }
                    List<String> ids = new ArrayList<>(options.length);
                    for (ItemStack option : options) {
                        ids.add(BuiltInRegistries.ITEM.getKey(option.getItem()).toString());
                    }
                    slots.add(List.copyOf(ids));
                }
                if (!complete || slots.isEmpty()) continue;

                Node node = new Node(outputId, List.copyOf(slots));
                nodeCount++;
                for (String ingredientId : ingredientIds(slots)) {
                    consumers.computeIfAbsent(ingredientId, k -> new ArrayList<>()).add(node);
                }
            }
        }
        Log.info(TAG, "Recipe tree collected: {} recipe(s) producing an item with no synthesize recipe skipped",
                skipped);
        return new Collected(consumers, nodeCount);
    }

    /** 一条配方用到的全部材料 id（去重），用于建立反向索引。 */
    private static Set<String> ingredientIds(List<List<String>> slots) {
        Set<String> ids = new HashSet<>();
        for (List<String> slot : slots) {
            ids.addAll(slot);
        }
        return ids;
    }

    // ── 推导（不 import MC 的纯遍历） ──

    /**
     * 从 {@code seeds}（本次新解锁的条目）出发，反复把「每个材料槽都有已解锁选项」的配方加进已知集合，
     * 直到不再增长。seeds 本身视为已知，因此基线材料（默认清单及其上下游）也参与判定。
     *
     * @param seeds   新解锁的条目 id（本身不算推导结果）
     * @param isKnown 该条目此刻是否已解锁（读存档的那一侧）
     * @return 本次新推导出的条目 id，按推导顺序；seeds 不在内
     */
    public static List<String> deriveFrom(Collection<String> seeds, Predicate<String> isKnown) {
        Snapshot current = snapshot;
        if (current == null) return List.of();
        return derive(seeds, isKnown, current.consumers());
    }

    static List<String> derive(Collection<String> seeds, Predicate<String> isKnown,
                               Map<String, List<Node>> consumers) {
        Set<String> known = new HashSet<>();
        for (String seed : seeds) {
            if (seed != null) known.add(seed);
        }
        ArrayDeque<String> queue = new ArrayDeque<>(known);

        List<String> found = new ArrayList<>();
        while (!queue.isEmpty()) {
            String itemId = queue.poll();
            for (Node node : consumers.getOrDefault(itemId, List.of())) {
                if (known.contains(node.outputId()) || isKnown.test(node.outputId())) continue;
                if (!satisfied(node, known, isKnown)) continue;
                known.add(node.outputId());
                found.add(node.outputId());
                queue.add(node.outputId());
            }
        }
        return found;
    }

    /** 每个材料槽都要有一个选项已经解锁（seeds、本轮刚推出来的、基线或存档解锁都算）。 */
    private static boolean satisfied(Node node, Set<String> derived, Predicate<String> isKnown) {
        for (List<String> slot : node.slots()) {
            boolean any = false;
            for (String option : slot) {
                if (derived.contains(option) || isKnown.test(option)) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
        }
        return true;
    }

    // ── 指纹 ──

    /**
     * 配方表的内容指纹：逐条配方拼一行（材料排序，免掉同一条配方因遍历顺序不同而抖动），
     * 排序后取 {@code List.hashCode}——{@code String} 与 {@code List} 的哈希都有规范约定，跨重启稳定。
     * 用途只有一个：让每个殖民地判断「我上次按哪一版配方表推导过」，没变就整段跳过重扫。
     */
    private static int fingerprintOf(Map<String, List<Node>> consumers, Set<String> baseline) {
        Set<String> lines = new HashSet<>();
        for (List<Node> nodes : consumers.values()) {
            for (Node node : nodes) {
                StringBuilder sb = new StringBuilder(node.outputId());
                for (List<String> slot : node.slots()) {
                    sb.append('|');
                    List<String> sorted = new ArrayList<>(slot);
                    java.util.Collections.sort(sorted);
                    for (String option : sorted) sb.append(option).append(',');
                }
                lines.add(sb.toString());
            }
        }
        List<String> ordered = new ArrayList<>(lines);
        java.util.Collections.sort(ordered);
        ordered.add("baseline:" + new java.util.TreeSet<>(baseline));
        return ordered.hashCode();
    }
}
