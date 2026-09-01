package com.wsteam.wandscape.content.building.preview;
import com.wsteam.wandscape.content.task.ecs.World;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Rotating building preview thumbnails ("GIF") for the selection bar and the
 * construction confirm screen.
 *
 * <p>Each building is off-screen rendered into a small texture for N rotation
 * frames once, then displayed as a cheap 2D flipbook — the per-frame cost is a
 * single texture blit per cell instead of re-tessellating every block.
 *
 * <p>Frames are persisted to {@code <gameDir>/wandscape/previews/} (PNG strip per
 * building, one file per frame), so the off-screen bake happens exactly once per
 * building ever; later sessions just read the files back. Buildings are
 * data-driven, so data-pack-added buildings auto-generate their cache on first use.
 *
 * <p>Baking runs lazily on the render thread, spread over render frames by a small
 * time budget so the first panel open never hitches. Interior blocks (fully
 * enclosed by opaque cubes) are culled to keep the one-time bake cheap even for
 * multi-thousand-block buildings.
 */
public final class BuildingPreviewGifCache {

    private static final String TAG = "BuildingPreviewGifCache";

    /** Bake resolution (clarity) — set from {@code preview.resolution} config via {@link #configure}. */
    public static int RES = 128;
    /** Rotation frames for a full loop — derived from {@code preview.fps} × loop seconds. */
    public static int FRAME_COUNT = 120;
    /** Full rotation loop duration in ms (fixed slow turntable). */
    public static final int LOOP_MS = 4000;
    private static final float TILT_RAD = (float) Math.toRadians(30);
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;
    /** Fraction of the texture the building should fill after its rotated footprint. */
    private static final float FILL = 0.78F;
    /** Bump when the bake pipeline changes so stale disk frames are not reused. */
    private static final int CACHE_VERSION = 4;
    /** Per-frame budget for the bake queue, in nanoseconds. */
    private static final long BAKE_BUDGET_NS = 8_000_000L;

    /** Apply config values before any baking; changing these re-bakes (hash key includes them). */
    public static void configure(int resolution, int fps) {
        RES = Math.max(48, resolution);
        FRAME_COUNT = Math.max(10, fps * (LOOP_MS / 1000));
    }

    private static final String CACHE_SUBDIR = "wandscape/previews";
    private static final String TEX_NAME = "wandscape_building_preview";

    private static final Map<BuildingConfig, BuildingGif> CACHE = new LinkedHashMap<>();
    private static final Map<BuildingConfig, List<BuildingPreviewRenderer.BlockEntry>> VISIBLE_CACHE = new HashMap<>();

    private static TextureTarget target;
    private static final ByteBufferBuilder BAKE_BBB = new ByteBufferBuilder(2 * 1024 * 1024);
    private static final MultiBufferSource.BufferSource BAKE_SRC = MultiBufferSource.immediate(BAKE_BBB);

    private BuildingPreviewGifCache() {}

    private static final class BuildingGif {
        final ResourceLocation[] frameLocs = new ResourceLocation[FRAME_COUNT];
        int baked;
        boolean ready;
    }

    /** Enqueue a config for (lazy) loading/baking. Idempotent. */
    public static void request(BuildingConfig config) {
        if (config == null || config.pattern().isEmpty()) {
            return;
        }
        CACHE.computeIfAbsent(config, k -> new BuildingGif());
    }

    /** Round-robin start index so a slow config doesn't starve the others. */
    private static int cursor;

    /**
     * Advance the load/bake queue by a small time budget. Must be called on the
     * render thread once per frame (driven by {@link #register()}).
     *
     * <p>Each config advances at most one frame per call, so small buildings fill in
     * almost instantly while huge ones make progress one frame at a time; a rotating
     * start keeps a multi-thousand-block building from monopolising the budget.
     */
    public static void pumpQueue() {
        if (CACHE.isEmpty()) {
            return;
        }
        List<BuildingConfig> keys = List.copyOf(CACHE.keySet());
        int n = keys.size();
        long deadline = System.nanoTime() + BAKE_BUDGET_NS;
        for (int step = 0; step < n; step++) {
            int idx = (cursor + step) % n;
            BuildingConfig config = keys.get(idx);
            BuildingGif gif = CACHE.get(config);
            if (gif != null && !gif.ready) {
                if (gif.baked >= FRAME_COUNT) {
                    gif.ready = true;
                } else {
                    ResourceLocation loc = materializeFrame(config, gif.baked);
                    if (loc != null) {
                        gif.frameLocs[gif.baked] = loc;
                    }
                    gif.baked++;
                    if (gif.baked >= FRAME_COUNT) {
                        gif.ready = true;
                    }
                }
            }
            if (System.nanoTime() >= deadline) {
                cursor = (idx + 1) % n;
                return;
            }
        }
        cursor = (cursor + 1) % n;
    }

    /**
     * Pre-warm the whole building catalog so previews are ready before the player
     * ever opens the panel. Smallest buildings first so they appear fastest.
     */
    public static void warmAll() {
        for (BuildingConfig config : BuildingConfigLoader.getInstance().getAll().values().stream()
                .sorted(java.util.Comparator.comparingInt(c -> c.pattern().size()))
                .toList()) {
            request(config);
        }
    }

    private static boolean registered = false;

    /** Subscribe the per-frame bake pump on the render thread. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class,
                e -> pumpQueue());
    }

    /**
     * Current frame texture for a config, or null if not ready. Returns the first
     * baked frame as a static placeholder while the rest of the animation is still
     * baking, so the building appears immediately.
     */
    public static ResourceLocation getFrameLocation(BuildingConfig config) {
        BuildingGif gif = CACHE.get(config);
        if (gif == null) {
            return null;
        }
        if (!gif.ready) {
            return gif.frameLocs[0];
        }
        int frame = (int) (((System.currentTimeMillis() % LOOP_MS) / (float) LOOP_MS) * FRAME_COUNT);
        return gif.frameLocs[Math.floorMod(frame, FRAME_COUNT)];
    }

    /**
     * Single-building display helper (e.g. the construction confirm screen): requests
     * the config and blits the current frame centered in the given rect. Baking is
     * driven by the central per-frame pump, so this only draws. Call on the render
     * thread once per frame.
     */
    public static void drawFrame(GuiGraphics g, BuildingConfig config, int x, int y, int w, int h) {
        request(config);
        ResourceLocation frameLoc = getFrameLocation(config);
        if (frameLoc == null) {
            return;
        }
        int size = Math.min(w, h);
        int bx = x + (w - size) / 2;
        int by = y + (h - size) / 2;
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.blit(frameLoc, bx, by, size, size, 0.0F, 0.0F, RES, RES, RES, RES);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    public static void closeAll() {
        Minecraft mc = Minecraft.getInstance();
        TextureManager tm = mc != null ? mc.getTextureManager() : null;
        if (tm != null) {
            for (BuildingGif gif : CACHE.values()) {
                for (ResourceLocation loc : gif.frameLocs) {
                    if (loc == null) {
                        continue;
                    }
                    AbstractTexture tex = tm.getTexture(loc);
                    if (tex != null) {
                        tex.close();
                    }
                }
            }
        }
        CACHE.clear();
        VISIBLE_CACHE.clear();
        BOUNDS_CACHE.clear();
        SCALE_CACHE.clear();
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Frame materialization: load from disk cache, else off-screen bake ──
    // ═══════════════════════════════════════════════════════════════

    private static ResourceLocation materializeFrame(BuildingConfig config, int f) {
        try {
            Path file = frameFile(config, f);
            NativeImage image = readFromDisk(file);
            if (image != null && isFullyTransparent(image)) {
                // Stale cache from a broken bake — discard and re-bake (overwrites below).
                image.close();
                image = null;
            }
            if (image == null) {
                image = bakeFrame(config, f);
                if (image == null) {
                    return null;
                }
                writeToDisk(file, image);
            }
            DynamicTexture tex = new DynamicTexture(image);
            tex.setFilter(true, false);
            return Minecraft.getInstance().getTextureManager().register(TEX_NAME, tex);
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to materialize preview frame {}#{}: {}", config.id(), f, e.getMessage());
            return null;
        }
    }

    private static NativeImage readFromDisk(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return NativeImage.read(in);
        } catch (IOException e) {
            Log.warn(TAG, "Failed to read preview cache {} (will re-bake): {}", file, e.getMessage());
            return null;
        }
    }

    private static void writeToDisk(Path file, NativeImage image) {
        try {
            Files.createDirectories(file.getParent());
            image.writeToFile(file.toFile());
        } catch (IOException e) {
            Log.warn(TAG, "Failed to save preview cache {}: {}", file, e.getMessage());
        }
    }

    private static Path frameFile(BuildingConfig config, int f) {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve(CACHE_SUBDIR);
        return dir.resolve(stableName(config) + "_" + f + ".png");
    }

    /** Stable content hash so a changed pattern re-bakes instead of reusing stale frames. */
    private static String stableName(BuildingConfig config) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < config.pattern().size(); i++) {
            sb.append(config.blockIdAt(i)).append(';');
        }
        for (BlockOffset o : config.pattern()) {
            sb.append(o.x()).append(',').append(o.y()).append(',').append(o.z()).append(';');
        }
        sb.append(RES).append('x').append(RES).append('_').append(FRAME_COUNT).append("_v").append(CACHE_VERSION);
        String id = config.id().replaceAll("[^A-Za-z0-9._-]", "_");
        return id + "_" + Integer.toHexString(sb.toString().hashCode());
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Off-screen bake ──
    // ═══════════════════════════════════════════════════════════════

    private static NativeImage bakeFrame(BuildingConfig config, int f) {
        BuildingPreviewRenderer.ConfigPreviewMeta meta = BuildingPreviewRenderer.getPreviewMeta(config);
        if (meta.resolvedMap.isEmpty()) {
            return null;
        }
        List<BuildingPreviewRenderer.BlockEntry> entries = visibleEntries(config, meta);
        if (entries.isEmpty()) {
            return null;
        }
        ensureTarget();

        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        float angle = (f / (float) FRAME_COUNT) * (float) (Math.PI * 2);

        var modelViewStack = RenderSystem.getModelViewStack();
        RenderSystem.backupProjectionMatrix();
        modelViewStack.pushMatrix();
        modelViewStack.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(
                new Matrix4f().ortho(0.0F, RES, RES, 0.0F, 1000.0F, 3000.0F),
                VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.enableDepthTest();
        Lighting.setupFor3DItems();
        target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        target.clear(true);
        target.bindWrite(true);

        try {
            PoseStack pose = new PoseStack();
            float scale = scaleForBuilding(config, meta);
            // ModelView is identity; ortho near=1000 far=3000 → visible camera z ∈ [-3000,-1000].
            pose.translate(RES / 2.0F, RES / 2.0F, -2000.0F);
            pose.scale(scale, -scale, scale);
            pose.mulPose(new Quaternionf().rotateX(TILT_RAD));
            pose.mulPose(new Quaternionf().rotateY(angle));
            pose.translate(-meta.cx - 0.5F, -meta.cy - 0.5F, -meta.cz - 0.5F);

            for (BuildingPreviewRenderer.BlockEntry entry : entries) {
                pose.pushPose();
                pose.translate(entry.offset().x(), entry.offset().y(), entry.offset().z());
                blockRenderer.renderSingleBlock(
                        entry.state(), pose, BAKE_SRC, FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                        ModelData.EMPTY, RenderType.solid());
                pose.popPose();
            }
            BAKE_SRC.endBatch();

            NativeImage image = new NativeImage(RES, RES, false);
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, false);
            image.flipY();
            if (isFullyTransparent(image)) {
                Log.warn(TAG, "Preview bake {}#{} came out fully transparent (projection/camera issue)", config.id(), f);
                return null;
            }
            return image;
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to bake preview frame {}#{}: {}", config.id(), f, e.getMessage());
            return null;
        } finally {
            target.unbindWrite();
            mc.getMainRenderTarget().bindWrite(true);
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.disableDepthTest();
            Lighting.setupForFlatItems();
        }
    }

    private static void ensureTarget() {
        if (target == null) {
            target = new TextureTarget(RES, RES, true, Minecraft.ON_OSX);
        }
    }

    private static final Map<BuildingConfig, float[]> BOUNDS_CACHE = new HashMap<>();

    /** Half-extents of the pattern bounding box (incl. the block's own [0,1] size), cached per config. */
    private static float[] boundsOf(BuildingConfig config, BuildingPreviewRenderer.ConfigPreviewMeta meta) {
        return BOUNDS_CACHE.computeIfAbsent(config, k -> {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BuildingPreviewRenderer.BlockEntry e : meta.fullEntries) {
                BlockOffset o = e.offset();
                minX = Math.min(minX, o.x()); maxX = Math.max(maxX, o.x());
                minY = Math.min(minY, o.y()); maxY = Math.max(maxY, o.y());
                minZ = Math.min(minZ, o.z()); maxZ = Math.max(maxZ, o.z());
            }
            return new float[]{(maxX - minX + 1) / 2f, (maxY - minY + 1) / 2f, (maxZ - minZ + 1) / 2f};
        });
    }

    private static final Map<BuildingConfig, Float> SCALE_CACHE = new HashMap<>();

    /**
     * One constant scale per building, based on the worst-case rotated footprint
     * across all frames, so the building renders the same size at every angle —
     * rotating only, never zooming. Nothing clips because the worst case fits.
     */
    private static float scaleForBuilding(BuildingConfig config, BuildingPreviewRenderer.ConfigPreviewMeta meta) {
        return SCALE_CACHE.computeIfAbsent(config, k -> {
            float[] b = boundsOf(k, meta);
            float maxProj = 0f;
            for (int f = 0; f < FRAME_COUNT; f++) {
                float angle = (f / (float) FRAME_COUNT) * (float) (Math.PI * 2);
                maxProj = Math.max(maxProj, projectedFootprint(b, angle));
            }
            return RES * FILL / Math.max(maxProj, 1e-4f);
        });
    }

    /** Max |x| and |y| of the 8 rotated corners (pose order: rotateY then rotateX). */
    private static float projectedFootprint(float[] b, float angle) {
        float hx = b[0], hy = b[1], hz = b[2];
        float cosA = (float) Math.cos(angle), sinA = (float) Math.sin(angle);
        float cosT = (float) Math.cos(TILT_RAD), sinT = (float) Math.sin(TILT_RAD);
        float maxProj = 0f;
        for (int sx : new int[]{-1, 1}) {
            for (int sy : new int[]{-1, 1}) {
                for (int sz : new int[]{-1, 1}) {
                    float x = sx * hx, y = sy * hy, z = sz * hz;
                    float x1 = x * cosA + z * sinA;
                    float z1 = -x * sinA + z * cosA;
                    float y2 = y * cosT - z1 * sinT;
                    maxProj = Math.max(maxProj, Math.max(Math.abs(x1), Math.abs(y2)));
                }
            }
        }
        return maxProj;
    }

    /** True if every pixel has zero alpha — indicates a failed/empty off-screen render. */
    private static boolean isFullyTransparent(NativeImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getPixelRGBA(x, y) >>> 24) & 0xFF) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Blocks visible in the thumbnail: the exterior shell plus anything adjacent to a non-opaque neighbor. */
    private static List<BuildingPreviewRenderer.BlockEntry> visibleEntries(
            BuildingConfig config, BuildingPreviewRenderer.ConfigPreviewMeta meta) {
        return VISIBLE_CACHE.computeIfAbsent(config, k -> {
            List<BuildingPreviewRenderer.BlockEntry> out = new ArrayList<>();
            for (BuildingPreviewRenderer.BlockEntry entry : meta.fullEntries) {
                if (!isEnclosed(meta, entry.offset())) {
                    out.add(entry);
                }
            }
            return List.copyOf(out);
        });
    }

    private static boolean isEnclosed(BuildingPreviewRenderer.ConfigPreviewMeta meta, BlockOffset o) {
        return isOccluding(meta, o.x() + 1, o.y(), o.z())
                && isOccluding(meta, o.x() - 1, o.y(), o.z())
                && isOccluding(meta, o.x(), o.y() + 1, o.z())
                && isOccluding(meta, o.x(), o.y() - 1, o.z())
                && isOccluding(meta, o.x(), o.y(), o.z() + 1)
                && isOccluding(meta, o.x(), o.y(), o.z() - 1);
    }

    private static boolean isOccluding(BuildingPreviewRenderer.ConfigPreviewMeta meta, int x, int y, int z) {
        BlockState state = meta.resolvedMap.get(BlockOffset.of(x, y, z));
        return state != null && state.canOcclude();
    }
}
