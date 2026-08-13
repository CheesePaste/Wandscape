package com.wsteam.wandscape.shared.ui.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Standalone 3D building preview renderer.
 * Renders a miniature 3D isometric view of any {@link BuildingConfig} into a GUI rectangle.
 *
 * <p>Not coupled to any specific UI — call {@link #renderPreview} from any
 * overlay or screen with a {@link GuiGraphics} context.
 *
 * <p>Uses {@link BlockRenderDispatcher#renderSingleBlock} with the GUI's native
 * orthographic projection (PoseStack transforms only — no viewport or projection
 * matrix hacks). Hardware depth test handles occlusion, replacing manual sorting.
 */
public final class BuildingPreviewRenderer {

    private static final String TAG = "BuildingPreviewRenderer";
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    // Fixed isometric tilt angle (standard 30 degrees)
    private static final float TILT_RAD = (float) Math.toRadians(30);

    /**
     * Cached pattern→BlockState resolution and preview metadata per config.
     * BuildingConfig is immutable and held strongly by the loader, so a weak key is safe and never leaks.
     */
    private static final Map<BuildingConfig, ConfigPreviewMeta> META_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public record BlockEntry(BlockOffset offset, BlockState state) {}

    public static final class ConfigPreviewMeta {
        public final Map<BlockOffset, BlockState> resolvedMap;
        public final List<BlockEntry> fullEntries;
        public final List<BlockEntry> iconEntries;
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
                this.iconEntries = fullEntries;
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

                // Render 100% complete block entries for crisp micro-icons without missing or floating blocks
                this.iconEntries = fullEntries;
            }
        }
    }

    public static ConfigPreviewMeta getPreviewMeta(BuildingConfig config) {
        if (config.pattern().isEmpty()) {
            return new ConfigPreviewMeta(config);
        }
        return META_CACHE.computeIfAbsent(config, ConfigPreviewMeta::new);
    }

    /**
     * Resolve a config's pattern offsets to BlockStates, cached per config so the
     * per-frame renderers don't re-parse every blockstate string every frame.
     */
    public static Map<BlockOffset, BlockState> resolveBlockStates(BuildingConfig config) {
        return getPreviewMeta(config).resolvedMap;
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
     * Batch-friendly 3D block preview renderer for GUI icons.
     */
    public static void renderPreviewBlocks(GuiGraphics g, BuildingConfig config,
                                           int x, int y, int w, int h) {
        ConfigPreviewMeta meta = getPreviewMeta(config);
        if (config.pattern().isEmpty() || meta.resolvedMap.isEmpty()) {
            return;
        }

        float scale = Math.min(w, h) / meta.maxExtent * 0.55f;
        List<BlockEntry> entries = meta.fullEntries;

        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        MultiBufferSource.BufferSource bufferSource = g.bufferSource();
        PoseStack pose = g.pose();

        float rotY = (System.currentTimeMillis() % 8000) / 8000f * (float) (Math.PI * 2);

        pose.pushPose();
        pose.translate(x + w / 2f, y + h / 2f, 100);
        pose.scale(scale, -scale, scale);
        pose.mulPose(new Quaternionf().rotateX(TILT_RAD));
        pose.mulPose(new Quaternionf().rotateY(rotY));
        pose.translate(-meta.cx - 0.5f, -meta.cy - 0.5f, -meta.cz - 0.5f);

        for (int i = 0; i < entries.size(); i++) {
            BlockEntry entry = entries.get(i);
            BlockOffset off = entry.offset();
            pose.pushPose();
            pose.translate(off.x(), off.y(), off.z());
            blockRenderer.renderSingleBlock(
                entry.state(),
                pose,
                bufferSource,
                FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY
            );
            pose.popPose();
        }

        pose.popPose();
    }

    /**
     * Standalone 3D preview renderer (manages its own state flush/setup).
     */
    public static void renderPreview(GuiGraphics g, BuildingConfig config,
                                      int x, int y, int w, int h) {
        g.flush();
        RenderSystem.enableDepthTest();
        Lighting.setupFor3DItems();

        renderPreviewBlocks(g, config, x, y, w, h);

        g.bufferSource().endBatch();
        Lighting.setupForFlatItems();
        RenderSystem.disableDepthTest();
    }

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
                .map(v -> (BlockState) state.setValue(property, v))
                .orElse(state);
    }

    private static void drawDebugRect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);
    }
}
