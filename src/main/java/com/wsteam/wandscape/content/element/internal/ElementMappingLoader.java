package com.wsteam.wandscape.content.element.internal;
import com.wsteam.wandscape.content.task.ecs.World;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wsteam.wandscape.foundation.registry.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.registry.WandscapeDataRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
public class ElementMappingLoader {
    private static final String TAG = "ElementMappingLoader";
    private static final String CATEGORY = "element_mappings";

    private final WandscapeDataRegistry<ElementMappingConfig> registry;

    /** Seed values loaded from element_seeds.json — base-material values, kept for reporting/count. */
    private final Map<String, Map<ElementType, Long>> seedValues = new LinkedHashMap<>();

    /** 程序化注册覆盖层（addon 经 ElementApi.registerMapping 写入；查询先查它再回落 JSON registry）。 */
    private final Map<String, ElementMappingConfig> runtimeOverrides = new ConcurrentHashMap<>();

    /**
     * {@code id(item 或 block) → config} 的 O(1) 索引，覆盖**全部**条目（含 disabled —— 判定
     * 「禁用」本身就要先查到它，绝不能拿 {@link #getAllConfigs()} 那种已过滤的表来建）。
     *
     * <p>{@link #indexedFrom} 记录建索引时用的那份 registry 快照实例；快照换实例（= 数据重载过）
     * 就重建。这依赖 {@code WandscapeDataRegistry#getAll()} 返回稳定快照，见其 javadoc。
     *
     * <p>为什么必须有：{@link #findConfigByItemId} 原先每次调用都「整表复制 + 全表线性扫描」，
     * 而它被放在逐方块的循环里 —— spark 实测这条链在一栋超大建筑（58 万条 pattern）上占服务端
     * 线程 27.4% ≈ 27.6 秒，另加 {@link #isDisabled} 那条 13.4% ≈ 13.5 秒。
     */
    private volatile Map<String, ElementMappingConfig> index;

    /** 建 {@link #index} 时所用的 registry 快照；与当前快照同实例即索引仍有效。 */
    private volatile Map<String, ElementMappingConfig> indexedFrom;

    public ElementMappingLoader(WandscapeDataLoader dataLoader) {
        this.registry = dataLoader.register(CATEGORY, ElementMappingConfig::fromJson);
    }

    /** 程序化注册一个块/物品的元素映射（覆盖 JSON）；buildCost 为空 → 无成本。 */
    public void register(String id, Map<ElementType, Long> buildCost) {
        runtimeOverrides.put(id, new ElementMappingConfig(null, null,
                buildCost == null ? Map.of() : Map.copyOf(buildCost), false));
    }

    /** 撤销程序化注册，恢复回落 JSON registry。 */
    public void unregister(String id) {
        runtimeOverrides.remove(id);
    }

    @javax.annotation.Nullable
    private ElementMappingConfig runtimeConfig(String id) {
        return runtimeOverrides.get(id);
    }

    /**
     * 按裸方块 / 物品 id 查映射：程序化覆盖先行，然后走 {@link #index()}。
     * 两份表都以「先登记先胜」处理重复键（JSON 侧的胜者取决于快照迭代序，与原线性扫描同为
     * 「任取其一」，只是现在**固定**下来，同一存档不会这次查 A、下次查 B）。
     */
    @javax.annotation.Nullable
    private ElementMappingConfig lookup(String blockOrItemId) {
        ElementMappingConfig override = runtimeConfig(blockOrItemId);
        if (override != null) return override;
        return index().get(blockOrItemId);
    }

    /** 建/复用 id 索引；registry 快照换了实例才重建（几百微秒级，重载时才发生）。 */
    private Map<String, ElementMappingConfig> index() {
        Map<String, ElementMappingConfig> snapshot = registry.getAll();
        Map<String, ElementMappingConfig> cached = index;
        if (cached != null && indexedFrom == snapshot) return cached;

        Map<String, ElementMappingConfig> built = new HashMap<>(snapshot.size() * 2);
        for (ElementMappingConfig config : snapshot.values()) {
            String blockId = config.blockId();
            if (blockId != null) built.putIfAbsent(blockId, config);
            String itemId = config.itemId();
            if (itemId != null) built.putIfAbsent(itemId, config);
        }
        // 先写 index 再写 indexedFrom：读方看到新 indexedFrom 时必定也能看到新 index。
        index = built;
        indexedFrom = snapshot;
        return built;
    }

    public Map<ElementType, Long> getBuildCost(BlockState state) {
        ElementMappingConfig config = findConfig(state);
        return config != null && !config.disabled() ? config.buildCost() : Map.of();
    }

    /** Find a representative block ID for an element type (for visual transport). */
    @javax.annotation.Nullable
    public String getRepresentativeBlock(ElementType element) {
        for (ElementMappingConfig config : getAllConfigs()) {
            if (config.buildCost().containsKey(element)) {
                return config.blockId();
            }
        }
        return null;
    }

    public Map<ElementType, Long> getItemBuildCost(Item item) {
        ElementMappingConfig config = findConfigByItem(item);
        return config != null && !config.disabled() ? config.buildCost() : Map.of();
    }

    /**
     * Canonical element value of an item — its build_cost.
     * Shared by shop sale profit and workstation decomposition.
     */
    public Map<ElementType, Long> getItemElementValue(String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return Map.of();
        Item item = BuiltInRegistries.ITEM.get(rl);
        return getItemBuildCost(item);
    }

    private ElementMappingConfig findConfig(BlockState state) {
        // 方块与它的物品形态共用同一个 id，索引把 itemId / blockId 两个键都登记了，
        // 所以一次查表即覆盖原先「先按 blockId 扫、再回落按 itemId 查」的两段。
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return lookup(blockId);
    }

    private ElementMappingConfig findConfigByItem(Item item) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        return findConfigByItemId(id);
    }

    private ElementMappingConfig findConfigByItemId(String itemId) {
        return lookup(itemId);
    }

    public boolean hasMapping(String blockOrItemId) {
        ElementMappingConfig config = findConfigByItemId(blockOrItemId);
        return config != null && !config.disabled();
    }

    /** True when an element mapping exists and is explicitly disabled via {@code "disabled": true}. */
    public boolean isDisabled(String blockOrItemId) {
        ElementMappingConfig config = findConfigByItemId(blockOrItemId);
        return config != null && config.disabled();
    }

    // ── Seed values (from element_seeds.json) ──

    /** Parse and load seed values from element_seeds.json content. */
    public void loadSeedValues(String jsonContent) {
        seedValues.clear();
        JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
        for (var elem : root.getAsJsonArray("seeds")) {
            JsonObject obj = elem.getAsJsonObject();
            String itemId = obj.get("item").getAsString();
            Map<ElementType, Long> values = new LinkedHashMap<>();
            if (obj.has("values")) {
                JsonObject valObj = obj.getAsJsonObject("values");
                for (var entry : valObj.entrySet()) {
                    ElementType type = ElementType.valueOf(entry.getKey().toUpperCase());
                    values.put(type, entry.getValue().getAsLong());
                }
            }
            if (!values.isEmpty()) {
                seedValues.put(itemId, values);
            }
        }
    }

    public int getSeedCount() {
        return seedValues.size();
    }

    public Map<ElementType, Long> getBuildCostByItemId(String itemId) {
        // 「再按 blockId 找一遍」的回落已并入索引（两个键都登记），且禁用即无成本。
        ElementMappingConfig config = findConfigByItemId(itemId);
        return config != null && !config.disabled() ? config.buildCost() : Map.of();
    }

    /**
     * Active (non-disabled) configs only. Disabled mappings are excluded from the element
     * economy: no synthesize recipes, no decompose, no representative transport, no audit
     * coverage. Lookups that need to distinguish "disabled" from "absent" use the raw registry.
     */
    public Collection<ElementMappingConfig> getAllConfigs() {
        return registry.getAll().values().stream()
                .filter(c -> !c.disabled())
                .toList();
    }
}
