package com.wsteam.wandscape.content.building.preview;

import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 建筑预览的**数据层**：把 {@link BuildingConfig} 的 pattern 解析成 {@link BlockState}，
 * 并算出预览要用的包围盒中心与尺度，按配置缓存。
 *
 * <p>消费方是预览单帧烘焙（{@code BuildingPreviewCache.ensureMeta/lodPreview}）与虚影渲染
 * （{@code BuildingGhostRenderer}）。历史上有两条「自带状态刷新的即时 3D 预览」路径
 * （{@code renderPreview} / {@code renderPreviewBlocks}）已无任何调用方，2026-10-06 删除——
 * 预览统一走 GIF 烘焙，别再往这里加第二套即时渲染。
 */
public final class BuildingPreviewRenderer {

    /**
     * 每配置一份的 pattern→BlockState 解析与预览元数据。键走 {@code config.id()}（见
     * {@link ConfigKeyedCache}）：入服同步换实例后不会退化成每帧一次 O(pattern) 相等比较。
     * 生命期由 {@link #clearMetaCache()}（reload / 退世界）管，不再靠 WeakHashMap 兜底——
     * config 本身由 loader 强引用，弱键从来就没真正回收过什么。
     */
    private static final ConfigKeyedCache<ConfigPreviewMeta> META_CACHE = new ConfigKeyedCache<>();

    public record BlockEntry(BlockOffset offset, BlockState state) {}

    public static final class ConfigPreviewMeta {
        public final Map<BlockOffset, BlockState> resolvedMap;
        public final List<BlockEntry> fullEntries;
        public final float cx, cy, cz;
        public final float maxExtent;

        public ConfigPreviewMeta(BuildingConfig config) {
            this.resolvedMap = buildBlockStates(config);
            List<BlockEntry> entries = new ArrayList<>(resolvedMap.size());
            for (var entry : resolvedMap.entrySet()) {
                entries.add(new BlockEntry(entry.getKey(), entry.getValue()));
            }
            this.fullEntries = java.util.Collections.unmodifiableList(entries);

            if (config.pattern().isEmpty()) {
                this.cx = this.cy = this.cz = 0f;
                this.maxExtent = 1f;
            } else {
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (BlockOffset off : config.pattern()) {
                    if (off.x() < minX) minX = off.x(); if (off.x() > maxX) maxX = off.x();
                    if (off.y() < minY) minY = off.y(); if (off.y() > maxY) maxY = off.y();
                    if (off.z() < minZ) minZ = off.z(); if (off.z() > maxZ) maxZ = off.z();
                }
                this.cx = (minX + maxX) / 2f;
                this.cy = (minY + maxY) / 2f;
                this.cz = (minZ + maxZ) / 2f;
                float extentX = maxX - minX + 1;
                float extentY = maxY - minY + 1;
                float extentZ = maxZ - minZ + 1;
                this.maxExtent = Math.max(extentX, Math.max(extentY, extentZ));
            }
        }
    }

    /** 配置目录变化（datapack 重载 / 入服同步）时清空——理由同 {@code BuildingGhostRenderer#clearAnimatedCache}。 */
    public static void clearMetaCache() {
        META_CACHE.clear();
    }

    public static ConfigPreviewMeta getPreviewMeta(BuildingConfig config) {
        if (config.pattern().isEmpty()) {
            return new ConfigPreviewMeta(config);
        }
        return META_CACHE.get(config, ConfigPreviewMeta::new);
    }

    /**
     * 只看不建：没算过（或同 id 内容已变）就返回 null。
     * 异步预热与分帧烘焙用它取现成结果 —— 这段解析很贵（超大建筑几十万条），不能落在渲染线程上。
     */
    public static ConfigPreviewMeta peekPreviewMeta(BuildingConfig config) {
        return META_CACHE.peek(config);
    }

    private static Map<BlockOffset, BlockState> buildBlockStates(BuildingConfig config) {
        Map<BlockOffset, BlockState> result = new HashMap<>();
        for (int i = 0; i < config.pattern().size(); i++) {
            BlockState state = resolveBlockState(config.blockIdAt(i));
            if (state != null) {
                result.put(config.pattern().get(i), state);
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private BuildingPreviewRenderer() {}

    /**
     * Parse a block ID string that may include state properties.
     * E.g. {@code "minecraft:oak_log[axis=z]"} → oak_log block with AXIS=z.
     * Returns null if the block is not found or the ID is malformed.
     */
    public static BlockState resolveBlockState(String rawId) {
        String baseId;
        String propsStr = null;
        int bracketIdx = rawId.indexOf('[');
        if (bracketIdx >= 0 && rawId.endsWith("]")) {
            baseId = rawId.substring(0, bracketIdx);
            propsStr = rawId.substring(bracketIdx + 1, rawId.length() - 1);
        } else {
            baseId = rawId;
        }

        ResourceLocation rl;
        try {
            rl = ResourceLocation.parse(baseId);
        } catch (Exception e) {
            return null;
        }

        Block block = BuiltInRegistries.BLOCK.get(rl);
        if (block == null) {
            return null;
        }

        BlockState state = block.defaultBlockState();
        if (propsStr != null && !propsStr.isEmpty()) {
            for (String part : propsStr.split(",")) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) continue;
                Property<?> property = block.getStateDefinition().getProperty(kv[0]);
                if (property != null) {
                    state = setPropertyValue(state, property, kv[1]);
                }
            }
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Comparable<T>> BlockState setPropertyValue(
            BlockState state, Property<T> property, String valueStr) {
        return property.getValue(valueStr)
                .map(v -> state.setValue(property, v))
                .orElse(state);
    }
}
