package com.wsteam.wandscape.content.building.preview;

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
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
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
 * <p>Frames are persisted to {@code <gameDir>/config/wandscape/previews/} (PNG strip per
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
    private static final int CACHE_VERSION = 5;
    /** Per-frame budget for the bake queue, in nanoseconds. */
    private static final long BAKE_BUDGET_NS = 8_000_000L;

    /** Apply config values before any baking; changing these re-bakes (hash key includes them). */
    public static void configure(int resolution, int fps) {
        RES = Math.max(48, resolution);
        FRAME_COUNT = Math.max(10, fps * (LOOP_MS / 1000));
    }

    private static final String CACHE_SUBDIR = "config/wandscape/previews";
    private static final String TEX_NAME = "wandscape_building_preview";

    private static final class BuildingGif {
        final ResourceLocation[] frameLocs = new ResourceLocation[FRAME_COUNT];
        int baked;
        boolean ready;
    }

    /**
     * 缓存条目：值里记 {@code source} 实例。键走 {@code config.id()}（{@code docs/domain-notes.md} §16
     * 的统一姿势）——以前直接拿 {@link BuildingConfig} 当键，而配置实例会被整批换掉
     * （datapack 重载、入服同步不清缓存），此时 record 的 {@code equals} 要逐组件比整条 pattern，
     * 偏偏 {@link #getFrameLocation} 是**逐帧逐格**查的：进世界重进后就是每帧每格一次 O(pattern)。
     */
    private static final class GifEntry {
        final BuildingConfig source;
        final BuildingGif gif;

        GifEntry(BuildingConfig source, BuildingGif gif) {
            this.source = source;
            this.gif = gif;
        }

        GifEntry(BuildingConfig source) {
            this(source, new BuildingGif());
        }
    }

    private static final Map<String, GifEntry> CACHE = new LinkedHashMap<>();

    /** 每个建筑算一次的缩略图 LOD 格子表，见 {@link #buildLodPreview}。 */
    private static final Map<BuildingConfig, LodPreview> LOD_CACHE = new HashMap<>();

    /**
     * 还没烤完的建筑数。归零后 {@link #pumpQueue} 直接返回 —— 稳态（全部就绪）下每帧零成本，
     * 连 key 列表都不必复制。以前是每帧扫一遍全部 key 并逐个 {@code CACHE.get}，配合当时
     * 昂贵的 {@code BuildingConfig.hashCode()}（遍历整条 pattern）占了渲染线程 68%。
     */
    private static int pendingCount;

    /**
     * 一张缩略图最多画多少格。超了就把建筑按 factor³ 归并，每格只画**一个代表方块**
     * 并把它放大 factor 倍 —— 所以建筑看着仍是连续实心的，不是抽稀出来的点阵。
     *
     * <p>128 px 的缩略图上，4×4×4 归并后的一格还不到一个像素，观感上是同一张图；
     * 而绘制次数按 factor³ 下降：那栋超大建筑每帧从 378,882 次掉到 2,925 次。
     * 方块数在预算内的建筑 factor == 1，逐方块渲染，与改造前完全一致。
     */
    private static final int LOD_CELL_BUDGET = 12_000;

    private static TextureTarget target;
    private static final ByteBufferBuilder BAKE_BBB = new ByteBufferBuilder(2 * 1024 * 1024);
    private static final MultiBufferSource.BufferSource BAKE_SRC = MultiBufferSource.immediate(BAKE_BBB);

    private BuildingPreviewGifCache() {}

    /**
     * 取该配置的缓存条目：实例没换直接命中；换了但内容相同就认下新实例（只比这一次，
     * 已烘好的帧继续用，此后走身份短路）；同 id 却内容不同则关掉旧帧纹理并返回 null
     * （调用方会当作没缓存重建）。
     */
    private static GifEntry entryFor(BuildingConfig config) {
        GifEntry entry = CACHE.get(config.id());
        if (entry == null) {
            return null;
        }
        if (entry.source == config) {
            return entry;
        }
        if (entry.source.equals(config)) {
            GifEntry adopted = new GifEntry(config, entry.gif);
            CACHE.put(config.id(), adopted);
            return adopted;
        }
        Minecraft mc = Minecraft.getInstance();
        closeGif(mc != null ? mc.getTextureManager() : null, entry.gif);
        CACHE.remove(config.id());
        if (!entry.gif.ready) {
            pendingCount = Math.max(0, pendingCount - 1);
        }
        return null;
    }

    /** Enqueue a config for (lazy) loading/baking. Idempotent. */
    public static void request(BuildingConfig config) {
        if (config == null || config.pattern().isEmpty()) {
            return;
        }
        // get + put 而非 computeIfAbsent：要能分辨「这次是否新建」，才好维护 pendingCount。
        if (entryFor(config) == null) {
            CACHE.put(config.id(), new GifEntry(config));
            pendingCount++;
        }
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
        if (pendingCount <= 0) {   // 快路径：全部就绪
            return;
        }
        List<String> keys = List.copyOf(CACHE.keySet());
        int n = keys.size();
        long deadline = System.nanoTime() + BAKE_BUDGET_NS;
        for (int step = 0; step < n; step++) {
            int idx = (cursor + step) % n;
            GifEntry entry = CACHE.get(keys.get(idx));
            if (entry == null) {
                continue;
            }
            BuildingConfig config = entry.source;
            BuildingGif gif = entry.gif;
            if (!gif.ready) {
                if (gif.baked >= FRAME_COUNT) {
                    gif.ready = true;
                    pendingCount--;
                } else {
                    ResourceLocation loc = materializeFrame(config, gif.baked);
                    if (loc != null) {
                        gif.frameLocs[gif.baked] = loc;
                    }
                    gif.baked++;
                    if (gif.baked >= FRAME_COUNT) {
                        gif.ready = true;
                        pendingCount--;
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
        GifEntry entry = entryFor(config);
        if (entry == null) {
            return null;
        }
        BuildingGif gif = entry.gif;
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

    /** 关掉一条缓存持有的全部帧纹理（{@link #closeAll} 与「同 id 内容变了」两条路共用）。 */
    private static void closeGif(TextureManager tm, BuildingGif gif) {
        if (tm == null) {
            return;
        }
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

    public static void closeAll() {
        Minecraft mc = Minecraft.getInstance();
        TextureManager tm = mc != null ? mc.getTextureManager() : null;
        for (GifEntry entry : CACHE.values()) {
            closeGif(tm, entry.gif);
        }
        CACHE.clear();
        LOD_CACHE.clear();
        BOUNDS_CACHE.clear();
        SCALE_CACHE.clear();
        pendingCount = 0;
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
        LodPreview preview = lodPreview(config, meta);
        List<PreviewCell> entries = preview.cells();
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

            int factor = preview.factor();
            for (PreviewCell cell : entries) {
                pose.pushPose();
                pose.translate(cell.x(), cell.y(), cell.z());
                // LOD 格子：把代表方块放大 factor 倍填满整格，建筑看上去仍是实心的。
                if (factor > 1) {
                    pose.scale(factor, factor, factor);
                }
                blockRenderer.renderSingleBlock(
                        cell.state(), pose, BAKE_SRC, FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
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

    /** 缩略图的一格：{@code factor³} 个方块归并成一个，坐标是格子在 pattern 空间里的最小角。 */
    private record PreviewCell(int x, int y, int z, BlockState state) {}

    /** 一张缩略图用的格子表；{@code factor == 1} 表示逐方块、没有归并。 */
    private record LodPreview(List<PreviewCell> cells, int factor) {}

    private static LodPreview lodPreview(BuildingConfig config,
                                         BuildingPreviewRenderer.ConfigPreviewMeta meta) {
        return LOD_CACHE.computeIfAbsent(config, k -> buildLodPreview(meta));
    }

    /**
     * 方块数在预算内就逐方块（factor = 1），超预算就一级级翻倍归并到预算为止。
     *
     * <p>每格的代表方块**优先取能遮挡的整方块**：一格里第一个碰到的可能是火把、告示牌
     * 或树叶，放大 k 倍会画成一片漂浮的碎片，建筑看着就不完整了 —— 宁可取它的石墙。
     */
    private static LodPreview buildLodPreview(BuildingPreviewRenderer.ConfigPreviewMeta meta) {
        List<BuildingPreviewRenderer.BlockEntry> full = meta.fullEntries;

        int factor = 1;
        while (factor < 16 && countCells(full, factor) > LOD_CELL_BUDGET) {
            factor <<= 1;
        }

        Map<Long, PreviewCell> grid = new HashMap<>();
        for (BuildingPreviewRenderer.BlockEntry entry : full) {
            BlockState state = entry.state();
            if (state == null || state.isAir()) continue;
            BlockOffset o = entry.offset();
            long key = cellKey(o.x(), o.y(), o.z(), factor);
            PreviewCell prev = grid.get(key);
            if (prev == null || (!prev.state().canOcclude() && state.canOcclude())) {
                grid.put(key, new PreviewCell(
                        cellMin(o.x(), factor), cellMin(o.y(), factor), cellMin(o.z(), factor), state));
            }
        }

        // 只留外壳：六面邻居格子都在、且都遮挡的格子从外面看不见。
        List<PreviewCell> out = new ArrayList<>(grid.size());
        for (PreviewCell cell : grid.values()) {
            if (!isEnclosedCell(grid, cell, factor)) {
                out.add(cell);
            }
        }
        return new LodPreview(List.copyOf(out), factor);
    }

    private static int countCells(List<BuildingPreviewRenderer.BlockEntry> full, int factor) {
        LongSet seen = new LongOpenHashSet(full.size());
        for (BuildingPreviewRenderer.BlockEntry entry : full) {
            BlockState state = entry.state();
            if (state == null || state.isAir()) continue;
            BlockOffset o = entry.offset();
            seen.add(cellKey(o.x(), o.y(), o.z(), factor));
        }
        return seen.size();
    }

    /** 格子最小角；{@code factor} 是 2 的幂，用 floorDiv 才对负偏移也成立。 */
    private static int cellMin(int v, int factor) {
        return Math.floorDiv(v, factor) * factor;
    }

    /** 把格号打包成 long 作哈希键（floorDiv 之后再取低位，负坐标也稳定）。 */
    private static long cellKey(int x, int y, int z, int factor) {
        return ((long) (Math.floorDiv(x, factor) & 0x1FFFFF) << 42)
                | ((long) (Math.floorDiv(y, factor) & 0xFFFFF) << 22)
                | (Math.floorDiv(z, factor) & 0x3FFFFF);
    }

    private static boolean isEnclosedCell(Map<Long, PreviewCell> grid, PreviewCell cell, int factor) {
        for (int axis = 0; axis < 3; axis++) {
            for (int dir = -1; dir <= 1; dir += 2) {
                PreviewCell n = grid.get(cellKey(
                        cell.x() + (axis == 0 ? dir * factor : 0),
                        cell.y() + (axis == 1 ? dir * factor : 0),
                        cell.z() + (axis == 2 ? dir * factor : 0),
                        factor));
                if (n == null || !n.state().canOcclude()) return false;
            }
        }
        return true;
    }

}
