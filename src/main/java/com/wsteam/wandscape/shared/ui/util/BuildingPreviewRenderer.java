package com.wsteam.wandscape.shared.ui.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
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

/**
 * Standalone 3D building preview renderer.
 * Renders a miniature 3D view of any {@link BuildingConfig} into a GUI rectangle.
 *
 * <p>Not coupled to any specific UI — call {@link #renderPreview} from any
 * overlay or screen with a {@link GuiGraphics} context.
 *
 * <p>Uses {@link BlockRenderDispatcher#renderSingleBlock} with a temporary
 * perspective projection. Blocks are sorted back-to-front for correct occlusion.
 */
public final class BuildingPreviewRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;
    private static final float VIEW_FOV = 25f;
    private static final float TILT_RAD = 0.55f;
    private static final float NEAR = 0.05f;
    private static final float FAR = 500f;
    private static long lastDebugLogMs = 0;
    private static long lastEntryLogMs = 0;

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
        long ts = System.currentTimeMillis();
        if (ts - lastEntryLogMs > 1000) {
            lastEntryLogMs = ts;
            LOGGER.info("[Preview] renderPreview ENTRY id={} patternSize={} mappingSize={} rect=({},{},{},{})",
                    config.id(), config.pattern().size(), config.blockMapping().size(), x, y, w, h);
        }
        List<BlockOffset> pattern = config.pattern();
        Map<String, String> blockMapping = config.blockMapping();
        if (pattern.isEmpty() || blockMapping.isEmpty()) {
            LOGGER.warn("[Preview] Empty pattern or mapping for '{}'", config.id());
            drawDebugRect(g, x, y, w, h, 0xFFFF0000);
            return;
        }

        // ── Compute building bounds ──
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

        float cx = (minX + maxX) / 2f + 0.5f;
        float cy = (minY + maxY) / 2f + 0.5f;
        float cz = (minZ + maxZ) / 2f + 0.5f;
        float extent = Math.max(maxX - minX + 1, Math.max(maxY - minY + 1, maxZ - minZ + 1));
        float camDist = extent * 4f + 2f;
        float visibleVertical = 2f * camDist * (float) Math.tan(Math.toRadians(VIEW_FOV / 2f));
        float scale = (visibleVertical * 0.55f) / Math.max(extent, 1f);

        // ── Resolve block states (sorted back-to-front by camera distance) ──
        record BlockEntry(BlockOffset offset, BlockState state) {}
        List<BlockEntry> entries = new ArrayList<>();
        for (BlockOffset off : pattern) {
            String key = off.toKey();
            String rawId = blockMapping.get(key);
            if (rawId == null) continue;
            BlockState state = resolveBlockState(rawId);
            if (state == null) continue;
            entries.add(new BlockEntry(off, state));
        }
        if (entries.isEmpty()) {
            LOGGER.warn("[Preview] No entries resolved for '{}' (pattern={}, mappingKeys={})",
                    config.id(), pattern.size(), blockMapping.size());
            drawDebugRect(g, x, y, w, h, 0xFFFF0000);
            return;
        }

        // Auto-rotation angle
        float rotY = (System.currentTimeMillis() % 8000) / 8000f * (float) (Math.PI * 2);

        // Sort back-to-front relative to rotated camera
        float sinY = (float) Math.sin(rotY);
        float cosY = (float) Math.cos(rotY);
        float sinX = (float) Math.sin(TILT_RAD);
        float cosX = (float) Math.cos(TILT_RAD);
        entries.sort((a, b) -> {
            float az = (a.offset().x() - cx) * sinY + (a.offset().z() - cz) * cosY;
            float bz = (b.offset().x() - cx) * sinY + (b.offset().z() - cz) * cosY;
            float ay = (a.offset().y() - cy) * cosX - az * sinX;
            float by = (b.offset().y() - cy) * cosX - bz * sinX;
            return Float.compare(ay, by);
        });

        // ── Set up 3D rendering ──
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        MultiBufferSource.BufferSource bufferSource = g.bufferSource();
        PoseStack pose = g.pose();

        long renderStart = System.currentTimeMillis();
        if (renderStart - lastDebugLogMs > 5000) {
            lastDebugLogMs = renderStart;
            LOGGER.info("[Preview] id={} resolved={} rect=({},{},{},{}) cx={:.1f} cy={:.1f} cz={:.1f} "
                    + "extent={:.1f} scale={:.3f} camDist={:.1f} rotY={:.2f}",
                    config.id(), entries.size(), x, y, w, h, cx, cy, cz,
                    extent, scale, camDist, rotY);
        }

        int guiScale = (int) mc.getWindow().getGuiScale();
        int winH = mc.getWindow().getHeight();

        LOGGER.debug("[Preview] Projection set: fov={} aspect={} viewport=({},{},{},{}) guiScale={} winH={}",
                VIEW_FOV, String.format("%.3f", (float)w/Math.max(h,1)),
                (int)(x*guiScale), winH-(int)((y+h)*guiScale),
                (int)(w*guiScale), (int)(h*guiScale), guiScale, winH);

        RenderSystem.viewport(
                (int) (x * guiScale),
                winH - (int) ((y + h) * guiScale),
                (int) (w * guiScale),
                (int) (h * guiScale));

        pose.pushPose();
        pose.setIdentity();
        pose.translate(0, 0, -camDist);
        pose.mulPose(new Quaternionf().rotateY(rotY));
        pose.mulPose(new Quaternionf().rotateX(TILT_RAD));
        pose.scale(scale, scale, scale);
        pose.translate(-cx, -cy, -cz);

        for (BlockEntry entry : entries) {
            BlockOffset off = entry.offset();
            pose.pushPose();
            pose.translate(off.x(), off.y(), off.z());
            blockRenderer.renderSingleBlock(entry.state(), pose, bufferSource,
                    FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }

        LOGGER.debug("[Preview] Rendered {} blocks, flushing all batches", entries.size());
        bufferSource.endBatch();

        pose.popPose();

        RenderSystem.restoreProjectionMatrix();
        RenderSystem.disableDepthTest();
        RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), winH);

        LOGGER.debug("[Preview] Cleanup complete for '{}'", config.id());

        long elapsed = System.currentTimeMillis() - renderStart;
        if (elapsed > 16) {
            LOGGER.warn("[Preview] SLOW render for '{}': {}ms for {} blocks", config.id(), elapsed, entries.size());
        }
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
            LOGGER.trace("[Preview] Bad block id '{}': {}", rawId, e.getMessage());
            return null;
        }

        Block block = BuiltInRegistries.BLOCK.get(rl);
        if (block == null) {
            LOGGER.trace("[Preview] Unknown block '{}'", baseId);
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
