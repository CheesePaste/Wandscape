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
import net.minecraft.Util;
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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static 3/4-view building thumbnails for the selection bar and the construction
 * confirm screen.
 *
 * <p>每栋建筑只烤**一张**静图，视角固定为 3/4（偏航 {@link #VIEW_YAW_RAD} + 俯角
 * {@link #TILT_RAD}）：正面、一个侧面和屋顶同时可见，比纯正面可辨识得多。历史上这里是
 * 48 帧转盘（{@code preview.fps} 驱动），而转盘并不提供信息，只是用来掩盖「固定角度难看」；
 * 固定成 3/4 之后那 48 帧全是冗余——磁盘、显存、烘焙时间和读取时间都是 48 倍。
 *
 * <p>Each building is off-screen rendered into a small texture once, then displayed as a
 * cheap 2D blit — the per-frame cost is a single texture blit per cell instead of
 * re-tessellating every block.
 *
 * <p>Images are persisted to {@code <gameDir>/config/wandscape/previews/} as
 * {@code v<版本>_<id>_<内容哈希>.png}，所以烤一次即可跨会话复用。
 * 版本前缀是文件名里唯一可读的版本标记：{@link #configure(int)} 会在后台把**低于**当前版本的
 * 文件（以及没有版本前缀的旧管线文件）全部删掉——**bump {@link #CACHE_VERSION} 就是换代 + 自动清理**。
 *
 * <p>Baking runs lazily on the render thread, spread over render frames by a small time
 * budget so the first panel open never hitches. Interior blocks (fully enclosed by opaque
 * cubes) are culled to keep the one-time bake cheap even for multi-thousand-block buildings.
 */
public final class BuildingPreviewCache {

    private static final String TAG = "BuildingPreviewCache";

    /** Bake resolution (clarity) — set from {@code preview.resolution} config via {@link #configure}. */
    public static int RES = 256;

    /**
     * 固定 3/4 视角：yaw 45° 让正面与一个侧面同时朝向相机，30° 俯角露出屋顶——三者齐备才认得出
     * 体量与屋顶形制，纯正面只看得到一面墙。这两个常量进内容哈希，改角度会自动重烤。
     */
    private static final float VIEW_YAW_DEG = 45f;
    private static final float VIEW_YAW_RAD = (float) Math.toRadians(VIEW_YAW_DEG);
    private static final float TILT_DEG = 30f;
    private static final float TILT_RAD = (float) Math.toRadians(TILT_DEG);

    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;
    /** Fraction of the texture the building should fill after its projected footprint. */
    private static final float FILL = 0.78F;

    /**
     * 缓存版本：**bump 它就等于换代**——文件名前缀随之变新，{@link #configure(int)} 随即清掉所有旧的。
     * 改动烘焙管线（投影、光照、LOD、视角）必须 bump，否则旧图会被当成有效缓存继续用。
     */
    private static final int CACHE_VERSION = 6;
    private static final String FILE_PREFIX = "v" + CACHE_VERSION + "_";
    /**
     * 从文件名前缀读版本号。读不到的是旧管线（48 帧时代 {@code <id>_<hash>_<帧号>.png}）留下的，
     * 当前代码永远不会再读，一律当过期清掉。
     */
    private static final Pattern VERSION_PREFIX = Pattern.compile("^v(\\d+)_");

    /** Per-frame budget for the bake queue, in nanoseconds. */
    private static final long BAKE_BUDGET_NS = 8_000_000L;

    /** Apply config values before any baking; also kicks off the stale-cache purge. */
    public static void configure(int resolution) {
        RES = Math.max(128, resolution);
        if (purgeStarted) {
            return;
        }
        purgeStarted = true;
        // 换代时可能要删上万个文件，绝不能占渲染线程。
        Util.ioPool().execute(BuildingPreviewCache::purgeStaleFiles);
    }

    private static boolean purgeStarted;

    private static final String CACHE_SUBDIR = "config/wandscape/previews";
    private static final String TEX_NAME = "wandscape_building_preview";

    /** 一栋建筑烤好的一张静图；{@code ready} 后不再重试（失败也如此，避免死循环重烤）。 */
    private static final class BuildingPreview {
        ResourceLocation texture;
        boolean ready;
    }

    /**
     * 缓存条目：值里记 {@code source} 实例。键走 {@code config.id()}（{@code docs/domain-notes.md} §16
     * 的统一姿势）——以前直接拿 {@link BuildingConfig} 当键，而配置实例会被整批换掉
     * （datapack 重载、入服同步不清缓存），此时 record 的 {@code equals} 要逐组件比整条 pattern，
     * 偏偏 {@link #getFrameLocation} 是**逐帧逐格**查的：进世界重进后就是每帧每格一次 O(pattern)。
     */
    private static final class PreviewEntry {
        final BuildingConfig source;
        final BuildingPreview preview;

        PreviewEntry(BuildingConfig source, BuildingPreview preview) {
            this.source = source;
            this.preview = preview;
        }

        PreviewEntry(BuildingConfig source) {
            this(source, new BuildingPreview());
        }
    }

    private static final Map<String, PreviewEntry> CACHE = new LinkedHashMap<>();

    /** 每栋建筑算一次的缩略图 LOD 格子表，见 {@link #buildLodPreview}。 */
    private static final ConfigKeyedCache<LodPreview> LOD_CACHE = new ConfigKeyedCache<>();

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
     * <p>**预算必须随 RES² 走，不能写死常量**：归并格子要「小于一个像素」这条观感约束是按
     * 像素面积成立的。128² 下一直用的 12,000 格就是把这个比值钉死的那个基准（≈0.73 格/像素），
     * 256² 必须跟着涨到约 48,000 —— 否则超大建筑归并出来的格子会变成屏上 10–20 px 的放大方块。
     * 绘制次数仍按 factor³ 下降：那栋超大建筑一次实测从 378,882 次掉到 2,925 次。
     * 方块数在预算内的建筑 factor == 1，逐方块渲染。
     */
    private static final double LOD_CELLS_PER_PIXEL = 12_000.0 / (128.0 * 128.0);

    /**
     * 单栋一次烘焙的格子硬上限。按面积换算的预算在 512/1024 档会推到 19 万 / 77 万格 ——
     * 那栋 37.9 万方块的建筑就得在**一帧里**烤 1–3 秒（`warmAll` 逐栋烤，中间没有可抢占的点）。
     * 所以 256 档以上把预算钉在 48,000：普通建筑（≤48,000 方块）照旧逐方块渲染，
     * 只有超大建筑会重新出现归并。要让高分辨率一点都不归并，把这个上限调成 1024²×比值即可（代价就是那段卡顿）。
     */
    private static final int LOD_CELL_BUDGET_MAX = 48_000;

    /** 当前分辨率下的格子预算，见 {@link #LOD_CELLS_PER_PIXEL} 与 {@link #LOD_CELL_BUDGET_MAX}。 */
    private static int lodCellBudget() {
        return (int) Math.min(LOD_CELL_BUDGET_MAX, RES * (double) RES * LOD_CELLS_PER_PIXEL);
    }

    private static TextureTarget target;
    private static final ByteBufferBuilder BAKE_BBB = new ByteBufferBuilder(2 * 1024 * 1024);
    private static final MultiBufferSource.BufferSource BAKE_SRC = MultiBufferSource.immediate(BAKE_BBB);

    private BuildingPreviewCache() {}

    /**
     * 取该配置的缓存条目：实例没换直接命中；换了但内容相同就认下新实例（只比这一次，
     * 已烤好的图继续用，此后走身份短路）；同 id 却内容不同则关掉旧纹理并返回 null
     * （调用方会当作没缓存重建）。
     */
    private static PreviewEntry entryFor(BuildingConfig config) {
        PreviewEntry entry = CACHE.get(config.id());
        if (entry == null) {
            return null;
        }
        if (entry.source == config) {
            return entry;
        }
        if (entry.source.equals(config)) {
            PreviewEntry adopted = new PreviewEntry(config, entry.preview);
            CACHE.put(config.id(), adopted);
            return adopted;
        }
        Minecraft mc = Minecraft.getInstance();
        closePreview(mc != null ? mc.getTextureManager() : null, entry.preview);
        CACHE.remove(config.id());
        if (!entry.preview.ready) {
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
            CACHE.put(config.id(), new PreviewEntry(config));
            pendingCount++;
        }
    }

    /** Round-robin start index so a slow config doesn't starve the others. */
    private static int cursor;

    /**
     * Advance the load/bake queue by a small time budget. Must be called on the
     * render thread once per frame (driven by {@link #register()}).
     *
     * <p>每栋建筑只需一步（一张图），所以一帧内能推进多少栋取决于预算；轮转起点保证超大建筑
     * 不会独占预算饿死其它建筑。
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
            PreviewEntry entry = CACHE.get(keys.get(idx));
            if (entry == null) {
                continue;
            }
            BuildingPreview preview = entry.preview;
            if (!preview.ready) {
                preview.texture = materialize(entry.source);
                // 单帧：一次尝试即定论。烤失败也标 ready，否则每帧都会重试同一栋。
                preview.ready = true;
                pendingCount--;
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
     * Texture of this building's 3/4 view, or null if it is not baked yet. Callers treat
     * null as "draw nothing this frame" — the bake pump fills it in a few frames later.
     */
    public static ResourceLocation getFrameLocation(BuildingConfig config) {
        PreviewEntry entry = entryFor(config);
        return entry == null ? null : entry.preview.texture;
    }

    /**
     * Single-building display helper (e.g. the construction confirm screen): requests
     * the config and blits the preview centered in the given rect. Baking is driven by
     * the central per-frame pump, so this only draws. Call on the render thread once
     * per frame.
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

    /** 关掉一条缓存持有的纹理（{@link #closeAll} 与「同 id 内容变了」两条路共用）。 */
    private static void closePreview(TextureManager tm, BuildingPreview preview) {
        if (tm == null || preview.texture == null) {
            return;
        }
        AbstractTexture tex = tm.getTexture(preview.texture);
        if (tex != null) {
            tex.close();
        }
        preview.texture = null;
    }

    public static void closeAll() {
        Minecraft mc = Minecraft.getInstance();
        TextureManager tm = mc != null ? mc.getTextureManager() : null;
        for (PreviewEntry entry : CACHE.values()) {
            closePreview(tm, entry.preview);
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

    private static ResourceLocation materialize(BuildingConfig config) {
        try {
            Path file = frameFile(config);
            NativeImage image = file != null ? readFromDisk(file) : null;
            if (image != null && isFullyTransparent(image)) {
                // Stale cache from a broken bake — discard and re-bake (overwrites below).
                image.close();
                image = null;
            }
            if (image == null) {
                image = bakeFrame(config);
                if (image == null) {
                    return null;
                }
                if (file != null) {
                    writeToDisk(file, image);
                }
            }
            DynamicTexture tex = new DynamicTexture(image);
            tex.setFilter(true, false);
            return Minecraft.getInstance().getTextureManager().register(TEX_NAME, tex);
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to materialize preview {}: {}", config.id(), e.getMessage());
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
            deleteStaleSiblings(file);
        } catch (IOException e) {
            Log.warn(TAG, "Failed to save preview cache {}: {}", file, e.getMessage());
        }
    }

    /**
     * 同一栋建筑在当前版本下只该留一个文件。改 pattern / 改分辨率都会换内容哈希、写出新文件名，
     * 旧的那张如果不删就永远留着（旧管线时代每改一次要漏 48 个文件，正是缓存目录膨胀的主因）。
     */
    private static void deleteStaleSiblings(Path file) {
        String name = file.getFileName().toString();
        int cut = name.lastIndexOf('_');
        if (cut < 0) {
            return;
        }
        // id 只由 [A-Za-z0-9._-] 组成，glob 里没有元字符，前缀直接当 pattern 用是安全的。
        String glob = name.substring(0, cut + 1) + "*.png";
        int removed = 0;
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(file.getParent(), glob)) {
            for (Path p : siblings) {
                if (!p.getFileName().toString().equals(name)) {
                    Files.deleteIfExists(p);
                    removed++;
                }
            }
        } catch (IOException e) {
            Log.warn(TAG, "Failed to sweep stale sibling of {}: {}", file, e.getMessage());
            return;
        }
        if (removed > 0) {
            Log.info(TAG, "Removed {} stale file(s) superseded by {}", removed, name);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Cache maintenance: drop everything below the current version ──
    // ═══════════════════════════════════════════════════════════════

    private static Path previewDir() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : mc.gameDirectory.toPath().resolve(CACHE_SUBDIR);
    }

    /**
     * 删掉所有低于当前 {@link #CACHE_VERSION} 的缓存文件（读不出版本前缀的旧管线文件同样算过期）。
     * 只删更旧的、保留当前与更高版本，所以：
     * <ul>
     *   <li>bump 版本 → 换代并自动清理，不需要人工删目录；</li>
     *   <li>装回旧版本模组 → 旧代码看到的是「更新的」文件，不会被删，只是重烤。</li>
     * </ul>
     */
    private static void purgeStaleFiles() {
        Path dir = previewDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        int removed = 0;
        int kept = 0;
        long bytes = 0L;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path p : files) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                if (!isStaleCacheFile(p.getFileName().toString())) {
                    kept++;
                    continue;
                }
                try {
                    bytes += Files.size(p);
                    if (Files.deleteIfExists(p)) {
                        removed++;
                    }
                } catch (IOException e) {
                    Log.warn(TAG, "Failed to delete stale preview {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            Log.warn(TAG, "Failed to scan preview cache {}: {}", dir, e.getMessage());
            return;
        }
        if (removed > 0) {
            Log.info(TAG, "Purged {} preview file(s) below v{} ({} MB reclaimed, {} kept)",
                    removed, CACHE_VERSION, bytes / 1048576L, kept);
        }
    }

    /** 文件名是否属于「更旧的一代」——没有版本前缀视为旧管线遗产。 */
    private static boolean isStaleCacheFile(String fileName) {
        Matcher m = VERSION_PREFIX.matcher(fileName);
        if (!m.find()) {
            return true;
        }
        try {
            return Integer.parseInt(m.group(1)) < CACHE_VERSION;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static Path frameFile(BuildingConfig config) {
        Path dir = previewDir();
        return dir == null ? null : dir.resolve(stableName(config) + ".png");
    }

    /**
     * Stable content hash so a changed pattern (or resolution / view angle) re-bakes
     * instead of reusing a stale image. 版本不参与哈希——它已经是文件名前缀，
     * 换代由 {@link #purgeStaleFiles} 负责。
     */
    private static String stableName(BuildingConfig config) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < config.pattern().size(); i++) {
            sb.append(config.blockIdAt(i)).append(';');
        }
        for (BlockOffset o : config.pattern()) {
            sb.append(o.x()).append(',').append(o.y()).append(',').append(o.z()).append(';');
        }
        sb.append(RES).append('_').append((int) VIEW_YAW_DEG).append('_').append((int) TILT_DEG);
        String id = config.id().replaceAll("[^A-Za-z0-9._-]", "_");
        return FILE_PREFIX + id + "_" + Integer.toHexString(sb.toString().hashCode());
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Off-screen bake ──
    // ═══════════════════════════════════════════════════════════════

    private static NativeImage bakeFrame(BuildingConfig config) {
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
            float scale = scaleForView(config, meta);
            // ModelView is identity; ortho near=1000 far=3000 → visible camera z ∈ [-3000,-1000].
            pose.translate(RES / 2.0F, RES / 2.0F, -2000.0F);
            pose.scale(scale, -scale, scale);
            pose.mulPose(new Quaternionf().rotateX(TILT_RAD));
            pose.mulPose(new Quaternionf().rotateY(VIEW_YAW_RAD));
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
                Log.warn(TAG, "Preview bake {} came out fully transparent (projection/camera issue)", config.id());
                return null;
            }
            return image;
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to bake preview {}: {}", config.id(), e.getMessage());
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

    private static final ConfigKeyedCache<float[]> BOUNDS_CACHE = new ConfigKeyedCache<>();

    /** Half-extents of the pattern bounding box (incl. the block's own [0,1] size), cached per config. */
    private static float[] boundsOf(BuildingConfig config, BuildingPreviewRenderer.ConfigPreviewMeta meta) {
        return BOUNDS_CACHE.get(config, k -> {
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

    private static final ConfigKeyedCache<Float> SCALE_CACHE = new ConfigKeyedCache<>();

    /**
     * One constant scale per building, based on the projected footprint at the fixed
     * 3/4 view, so the building fills {@link #FILL} of the frame and never clips.
     *
     * <p>以前要对 48 个角度取最坏值（转盘每帧都得一样大），现在只有一个角度，
     * 建筑因此能画得更大——静图比转盘图更容易看清就是这么来的。
     */
    private static float scaleForView(BuildingConfig config, BuildingPreviewRenderer.ConfigPreviewMeta meta) {
        return SCALE_CACHE.get(config, k -> {
            float[] b = boundsOf(k, meta);
            return RES * FILL / Math.max(projectedFootprint(b, VIEW_YAW_RAD), 1e-4f);
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
        return LOD_CACHE.get(config, k -> buildLodPreview(meta));
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
        while (factor < 16 && countCells(full, factor) > lodCellBudget()) {
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
