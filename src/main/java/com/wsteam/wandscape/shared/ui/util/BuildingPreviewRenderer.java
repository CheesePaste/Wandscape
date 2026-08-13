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
     * Cached pattern→BlockState resolution per config. BuildingConfig is immutable and
     * held strongly by the loader, so a weak key is safe and never leaks. Rendering runs
     * on the client render thread; synchronizedMap keeps reload-time access safe.
     */
    private static final Map<BuildingConfig, Map<BlockOffset, BlockState>> RESOLVED_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Resolve a config's pattern offsets to BlockStates, cached per config so the
     * per-frame renderers don't re-parse every blockstate string every frame.
     */
    public static Map<BlockOffset, BlockState> resolveBlockStates(BuildingConfig config) {
        if (config.pattern().isEmpty()) return Map.of();
        return RESOLVED_CACHE.computeIfAbsent(config, BuildingPreviewRenderer::buildBlockStates);
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
     * Render a 3D preview of the building into the given screen rectangle.
     *
     * @param g      current GuiGraphics
     * @param config building to preview
     * @param x      screen x (top-left)
     * @param y      screen y (top-left)
     * @param w      preview width in pixels
     * @param h      preview height in pixels
     */
    public static void renderPreview(GuiGraphics g, BuildingConfig config,
                                      int x, int y, int w, int h) {
        List<BlockOffset> pattern = config.pattern();
        Map<BlockOffset, BlockState> resolved = resolveBlockStates(config);
        if (pattern.isEmpty() || resolved.isEmpty()) {
            Log.warn(TAG, "[Preview] Empty pattern or resolved blocks for '{}'", config.id());
            drawDebugRect(g, x, y, w, h, 0xFFFF0000);
            return;
        }

        // ── 1. Compute building bounds and center ──
        int minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        boolean first = true;
        for (BlockOffset off : pattern) {
            if (first) {
                minX = maxX = off.x(); minY = maxY = off.y(); minZ = maxZ = off.z();
                first = false;
            } else {
                if (off.x() < minX) minX = off.x(); if (off.x() > maxX) maxX = off.x();
                if (off.y() < minY) minY = off.y(); if (off.y() > maxY) maxY = off.y();
                if (off.z() < minZ) minZ = off.z(); if (off.z() > maxZ) maxZ = off.z();
            }
        }

        float cx = (minX + maxX) / 2f;
        float cy = (minY + maxY) / 2f;
        float cz = (minZ + maxZ) / 2f;

        float extentX = maxX - minX + 1;
        float extentY = maxY - minY + 1;
        float extentZ = maxZ - minZ + 1;
        float maxExtent = Math.max(extentX, Math.max(extentY, extentZ));

        // Scale so the building fits in the UI rect with padding (0.55f margin)
        float scale = Math.min(w, h) / maxExtent * 0.55f;

        // ── 2. Resolve block states (cached per config — no per-frame string parsing) ──
        record BlockEntry(BlockOffset offset, BlockState state) {}
        List<BlockEntry> entries = new ArrayList<>();
        for (var entry : resolved.entrySet()) {
            entries.add(new BlockEntry(entry.getKey(), entry.getValue()));
        }

        // ── 3. Set up isometric rendering ──
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        MultiBufferSource.BufferSource bufferSource = g.bufferSource();
        PoseStack pose = g.pose();

        // Auto-rotation angle (full rotation every 8 seconds)
        float rotY = (System.currentTimeMillis() % 8000) / 8000f * (float) (Math.PI * 2);

        // Flush any pending GUI batches to avoid state interference
        g.flush();

        pose.pushPose();

        // Move to the center of the UI rectangle; push Z forward so the model
        // renders in front of the GUI background (200 is a safe depth).
        pose.translate(x + w / 2f, y + h / 2f, 200);

        // 【CRITICAL】GUI Y-axis points downward; 3D world Y-axis points upward.
        // Negate Y scale to flip the coordinate system.
        pose.scale(scale, -scale, scale);

        // Isometric rotation: first tilt down, then rotate horizontally
        pose.mulPose(new Quaternionf().rotateX(TILT_RAD));
        pose.mulPose(new Quaternionf().rotateY(rotY));

        // Offset so the building rotates around its own center.
        // The -0.5f shifts from block-corner to block-center anchoring.
        pose.translate(-cx - 0.5f, -cy - 0.5f, -cz - 0.5f);

        // Enable hardware depth test for correct occlusion (replaces CPU sorting)
        RenderSystem.enableDepthTest();

        // Use GUI 3D lighting so blocks appear立体感 rather than flat/dark
        Lighting.setupFor3DItems();

        // ── 4. Render blocks ──
        for (BlockEntry entry : entries) {
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

        // Must flush batches BEFORE disabling depth test!
        bufferSource.endBatch();

        // ── 5. Restore state ──
        pose.popPose();

        // Restore flat GUI lighting and disable depth test
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
