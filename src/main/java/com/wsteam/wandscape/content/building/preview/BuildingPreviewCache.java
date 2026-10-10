package com.wsteam.wandscape.content.building.preview;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
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
import net.minecraft.network.chat.Component;
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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * 分帧烘焙的**每帧总预算**。切片之后这个值才真正等于「每帧最多花多少」：以前一步就是一整栋
     * （最贵那栋 312 ms），而预算是在一步**之后**才检查的，等于拦不住。
     */
    private static final long BAKE_BUDGET_NS = 3_000_000L;
    /** 单次镶嵌切片的上限。最贵的镶嵌（约 4.7 万格）必须被切成很多片，否则它自己就是一整块卡顿。 */
    private static final long BAKE_SLICE_NS = 1_000_000L;

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

    /** 同时只允许一栋在飞：所有任务共用同一个离屏 target，并行会互相覆盖。 */
    private static BakeJob activeJob;

    /** 已在 worker 上排队的 META 预热，避免重复提交。 */
    private static final Set<String> META_IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** 本轮烘焙波次的每帧开销统计，跑空后打一行——用来验收「没有一帧被整栋占满」。 */
    private static long waveMaxFrameNs;
    private static int waveFrames;

    // ── 波次统计：还要说清这一轮是「读盘」还是「重新烘焙」 ──

    private static int waveLoads;
    private static int waveBakes;
    private static long waveLoadNs;
    private static long waveBakeNs;
    /** 被重新烘焙的 id（限量，只够在日志里点名，不为它涨内存）。 */
    private static final List<String> waveBakedIds = new ArrayList<>();
    private static final int WAVE_ID_LOG_LIMIT = 12;

    /**
     * 记一笔：这次物化是命中磁盘还是重新烘焙。读盘记整段墙钟，烘焙记**任务累计工作耗时**
     * （分帧之后墙钟被摊进多帧，只有工作耗时能和读盘直接比）。基准测试自有一套报告，不重复计。
     */
    private static void recordWave(boolean fromDisk, String id, long ns) {
        if (benchMode != null) {
            return;
        }
        if (fromDisk) {
            waveLoads++;
            waveLoadNs += ns;
        } else {
            waveBakes++;
            waveBakeNs += ns;
            if (waveBakedIds.size() < WAVE_ID_LOG_LIMIT) {
                waveBakedIds.add(id);
            }
        }
    }

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
        // 面板每帧每格都会 request，所以这里必须廉价；META 提前在 worker 上备好，
        // 等真开始烤的时候就不能再落到渲染线程上（那是最贵且不可切的一段）。
        requestMetaAsync(config);
    }

    /** Round-robin start index so a slow config doesn't starve the others. */
    private static int cursor;

    /**
     * 每帧的烘焙泵。必须在渲染线程上每帧调用一次（见 {@link #register()}）。
     *
     * <p>**消抖的关键**：一帧的总开销由 {@link #BAKE_BUDGET_NS} 封顶，而单栋最贵的镶嵌按
     * {@link #BAKE_SLICE_NS} 切片跨帧推进 —— 所以任何一帧都不会被一整栋（实测最贵那栋 312 ms）占满。
     * 代价是总时长变长：以前 56 栋 1 秒烤完但每帧都在卡，现在铺开成几秒的轻微开销。
     *
     * <p>磁盘命中仍是同步快路径（一栋一次读 + 解码 + 上传），只有真需要烤的才进 {@link BakeJob}。
     */
    public static void pumpQueue() {
        if (pendingCount <= 0 && activeJob == null) {   // 快路径：全部就绪
            logWaveIfDrained();
            return;
        }
        long frameStart = System.nanoTime();
        long frameDeadline = frameStart + BAKE_BUDGET_NS;
        List<String> keys = List.copyOf(CACHE.keySet());
        int n = keys.size();
        for (int step = 0; step < n; step++) {
            if (activeJob != null) {
                long slice = Math.min(frameDeadline, System.nanoTime() + BAKE_SLICE_NS);
                JobStep jobStep = advanceJob(activeJob, slice);
                if (jobStep == JobStep.DONE) {
                    completeJob(activeJob);
                }
                // WAIT = 在等 worker 备 META：必须当帧收工，否则这里会空转到预算耗尽。
                if (jobStep == JobStep.WAIT || System.nanoTime() >= frameDeadline) {
                    break;
                }
                continue;
            }
            int idx = (cursor + step) % n;
            PreviewEntry entry = CACHE.get(keys.get(idx));
            if (entry == null || entry.preview.ready) {
                continue;
            }
            ResourceLocation fromDisk = readMaterialize(entry.source);
            if (fromDisk != null) {
                entry.preview.texture = fromDisk;
                // 一次尝试即定论：失败也标 ready，否则每帧都会重试同一栋。
                entry.preview.ready = true;
                pendingCount--;
            } else {
                activeJob = startJob(entry.source, false);
            }
            if (System.nanoTime() >= frameDeadline) {
                cursor = (idx + 1) % n;
                break;
            }
        }
        waveFrames++;
        waveMaxFrameNs = Math.max(waveMaxFrameNs, System.nanoTime() - frameStart);
    }

    /**
     * 队列跑空时打总结，一次给两条互相独立的验收信息：
     *
     * <ol>
     *   <li>{@code [Preview]} —— **「这次确实是读盘、没有重新烘焙」的唯一正向依据**：普通启动应该是
     *       「读盘命中 N / 重新烘焙 0」；只有换代、改了 pattern、或缓存被删之后才会出现烘焙。</li>
     *   <li>{@code [Bake]} —— 分帧推进的验收：这一轮铺了多少帧、**单帧最大开销**有没有守住预算。</li>
     * </ol>
     */
    private static void logWaveIfDrained() {
        if (waveLoads > 0 || waveBakes > 0) {
            Log.info(TAG, "[Preview] 本轮完成 {} 栋：读盘命中 {}（{} ms，均 {} ms）/ 重新烘焙 {}（{} ms，均 {} ms）· {}px",
                    waveLoads + waveBakes, waveLoads, waveLoadNs / 1_000_000L,
                    waveLoads == 0 ? 0 : waveLoadNs / 1_000_000L / waveLoads,
                    waveBakes, waveBakeNs / 1_000_000L,
                    waveBakes == 0 ? 0 : waveBakeNs / 1_000_000L / waveBakes, RES);
            if (waveBakes > 0) {
                Log.info(TAG, "[Preview] 重新烘焙的 {} 栋：{}{}", waveBakes, String.join(", ", waveBakedIds),
                        waveBakes > waveBakedIds.size() ? " …" : "");
            }
        }
        if (waveFrames > 1) {
            Log.info(TAG, "[Bake] 波次结束：{} 帧，单帧最大 {} ms（每帧预算 {} ms / 单片 {} ms）",
                    waveFrames, waveMaxFrameNs / 1e6, BAKE_BUDGET_NS / 1e6, BAKE_SLICE_NS / 1e6);
        }
        waveLoads = 0;
        waveBakes = 0;
        waveLoadNs = 0L;
        waveBakeNs = 0L;
        waveBakedIds.clear();
        waveFrames = 0;
        waveMaxFrameNs = 0L;
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
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, e -> tick());
    }

    /** 每帧入口：基准测试在跑就推进它，否则推进正常烘焙队列。 */
    private static void tick() {
        if (benchMode != null) {
            pumpBenchmark();
        } else {
            pumpQueue();
        }
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
        cursor = 0;
        waveFrames = 0;
        waveMaxFrameNs = 0L;
        waveLoads = 0;
        waveBakes = 0;
        waveLoadNs = 0L;
        waveBakeNs = 0L;
        waveBakedIds.clear();
        if (activeJob != null) {
            // 顶点缓冲必须整个丢掉：BufferBuilder 没有 discard()，留着残留顶点就会画进下一栋的图里。
            activeJob.cancel();
            activeJob = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Frame materialization: load from disk cache, else off-screen bake ──
    // ═══════════════════════════════════════════════════════════════

    /**
     * **同步**物化：一次调用跑完整栋。基准测试（{@code /wsbench}）与只读趟走这条 —— 它们要的是
     * 「一栋到底花多少」，与分帧策略无关；正常游玩走 {@link #pumpQueue} 的切片路径。
     */
    private static ResourceLocation materialize(BuildingConfig config, Source source) {
        try {
            if (source == Source.DISK_ONLY) {
                return readMaterialize(config);
            }
            BakeJob job = startJob(config, true);
            while (advanceJob(job, Long.MAX_VALUE) != JobStep.DONE) {
                // 同步路径：切片上限 = 无穷，一次跑完
            }
            ResourceLocation loc = finishJob(job);
            job.buffer.close();
            return loc;
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to materialize preview {}: {}", config.id(), e.getMessage());
            return null;
        }
    }

    /** 正常泵的磁盘快路径：命中就返回纹理，未命中返回 null（不回退去烤）。 */
    private static ResourceLocation readMaterialize(BuildingConfig config) {
        Path file = frameFile(config);
        if (file == null) {
            return null;
        }
        long startNs = System.nanoTime();
        NativeImage image = readFromDisk(file);
        if (image == null || !verifyNotBlank(image)) {
            return null;
        }
        ResourceLocation loc = upload(image);
        recordWave(true, config.id(), System.nanoTime() - startNs);
        return loc;
    }

    /** 全透明 = 上一次烤坏的残留（或空白建筑），丢掉并当未命中。 */
    private static boolean verifyNotBlank(NativeImage image) {
        try (Timing ignored = time(Phase.POST)) {
            if (isFullyTransparent(image)) {
                image.close();
                return false;
            }
            return true;
        }
    }

    private static ResourceLocation upload(NativeImage image) {
        try (Timing ignored = time(Phase.UPLOAD)) {
            DynamicTexture tex = new DynamicTexture(image);
            tex.setFilter(true, false);
            return Minecraft.getInstance().getTextureManager().register(TEX_NAME, tex);
        }
    }

    /**
     * 读盘 + 解码。文件不存在走 {@link NoSuchFileException} 直接返回 null —— 不再先 {@code isRegularFile}
     * 探一次：那次 stat 每栋每次都要付，而「没有缓存」本来就是正常路径，不该当成异常去 warn。
     */
    private static NativeImage readFromDisk(Path file) {
        try (Timing ignored = time(Phase.READ);
             InputStream in = Files.newInputStream(file)) {
            return NativeImage.read(in);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            Log.warn(TAG, "Failed to read preview cache {} (will re-bake): {}", file, e.getMessage());
            return null;
        }
    }

    private static void writeToDisk(Path file, NativeImage image, boolean sweepSiblings) {
        try (Timing ignored = time(Phase.ENCODE)) {
            Files.createDirectories(file.getParent());
            image.writeToFile(file.toFile());
            if (sweepSiblings) {
                deleteStaleSiblings(file);
            }
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
     * 文件名缓存：{@link #stableName} 要遍历整条 pattern（超大建筑 58 万条）拼一遍字符串，而它
     * **每次物化**（每栋每次读盘或烘焙）都会被调一次。文件名只由 pattern / RES / 视角决定，
     * 会话内不变，所以按 {@code config.id()} 缓存下来。
     *
     * <p>刻意不进 {@link #closeAll()}：它不持有任何资源，键又走内容比对，配置换代了自己会认新实例。
     */
    private static final ConfigKeyedCache<String> NAME_CACHE = new ConfigKeyedCache<>();

    private static String stableName(BuildingConfig config) {
        return NAME_CACHE.get(config, BuildingPreviewCache::buildStableName);
    }

    /**
     * Stable content hash so a changed pattern (or resolution / view angle) re-bakes
     * instead of reusing a stale image. 版本不参与哈希——它已经是文件名前缀，
     * 换代由 {@link #purgeStaleFiles} 负责。
     */
    private static String buildStableName(BuildingConfig config) {
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
    // ── Benchmark: per-phase timing for bake vs disk read ──
    // ═══════════════════════════════════════════════════════════════

    /**
     * 计时阶段。{@code META/DRAW/READBACK/POST/ENCODE} 只出现在烘焙趟，{@code READ} 只出现在读取趟，
     * {@code POST}（透明校验）与 {@code UPLOAD}（建纹理 + GL 上传）两趟都有。
     */
    private enum Phase { META, DRAW, READBACK, POST, ENCODE, READ, UPLOAD }

    private static final int PHASE_COUNT = Phase.values().length;

    /** 取图来源。{@link #DISK_ONLY} 下没有缓存文件就算 miss，绝不回退去烤——否则两趟的数字会混在一起。 */
    private enum Source { FORCE_BAKE, DISK_ONLY }

    /** {@code /wsbench} 的三种模式。 */
    public enum BenchMode { BAKE, LOAD, BOTH }

    private static BenchMode benchMode;
    private static List<BuildingConfig> benchConfigs = List.of();
    private static boolean benchReadPass;
    private static int benchCursor;
    private static BenchPass benchBakePass;
    private static BenchPass benchLoadPass;
    private static BenchStats benchInFlight;

    private static final class BenchStats {
        final long startNs = System.nanoTime();
        final long[] phases = new long[PHASE_COUNT];
    }

    private static final class BenchPass {
        int measured;
        int misses;
        long totalNs;
        long maxNs;
        BuildingConfig worstConfig;
        long[] worstPhases;
        final long[] phases = new long[PHASE_COUNT];
        final List<Long> walls = new ArrayList<>();
    }

    /**
     * 开始一次烘焙 / 读取基准测试，入口是客户端命令 {@code /wsbench}。必须在渲染线程上调用。
     *
     * <p>先把纹理与逐配置缓存全清掉（冷启动），再**每帧只推进一座**，记录整座墙钟与分阶段耗时；
     * 跑完把汇总同时打进日志和聊天栏。返回 false 表示已经有一次在跑。
     */
    public static boolean startBenchmark(BenchMode mode) {
        if (benchMode != null) {
            return false;
        }
        List<BuildingConfig> configs =
                new ArrayList<>(BuildingConfigLoader.getInstance().getAll().values());
        if (configs.isEmpty()) {
            return false;
        }
        configs.sort(java.util.Comparator.comparingInt(c -> c.pattern().size()));
        closeAll();                                  // 清纹理 + CACHE + LOD/包围盒/缩放
        BuildingPreviewRenderer.clearMetaCache();     // 连元数据也重算，否则第一座白捡便宜
        benchConfigs = List.copyOf(configs);
        benchBakePass = mode == BenchMode.LOAD ? null : new BenchPass();
        benchLoadPass = mode == BenchMode.BAKE ? null : new BenchPass();
        benchReadPass = benchBakePass == null;
        benchCursor = 0;
        benchMode = mode;
        Log.info(TAG, "[Bench] start mode={} res={} buildings={}", mode, RES, benchConfigs.size());
        notifyPlayer("预览基准开始：" + mode.name().toLowerCase() + " · " + benchConfigs.size()
                + " 座 · " + RES + "px（跑完自动出结果）");
        return true;
    }

    /** 一帧一座地推进基准测试，跑完自动汇报。 */
    private static void pumpBenchmark() {
        if (benchCursor >= benchConfigs.size()) {
            if (!benchReadPass && benchLoadPass != null) {
                benchReadPass = true;
                benchCursor = 0;
                Log.info(TAG, "[Bench] bake 趟完成，接着测读取趟");
                return;
            }
            finishBenchmark();
            return;
        }
        BuildingConfig config = benchConfigs.get(benchCursor++);
        request(config);
        PreviewEntry entry = CACHE.get(config.id());
        if (entry == null) {
            return;
        }
        if (benchReadPass) {
            // 读取趟先把上一趟留下的纹理扔掉，否则测到的是「已经上传好了」的假象。
            closePreview(Minecraft.getInstance().getTextureManager(), entry.preview);
        }
        benchInFlight = new BenchStats();
        ResourceLocation loc = materialize(config, benchReadPass ? Source.DISK_ONLY : Source.FORCE_BAKE);
        BenchStats stats = benchInFlight;
        benchInFlight = null;
        BenchPass pass = benchReadPass ? benchLoadPass : benchBakePass;
        if (loc == null) {
            pass.misses++;
            return;
        }
        entry.preview.texture = loc;
        entry.preview.ready = true;
        long wall = System.nanoTime() - stats.startNs;
        pass.measured++;
        pass.totalNs += wall;
        pass.maxNs = Math.max(pass.maxNs, wall);
        pass.walls.add(wall);
        for (int i = 0; i < PHASE_COUNT; i++) {
            pass.phases[i] += stats.phases[i];
        }
        if (wall >= pass.maxNs) {   // 新晋最坏：留住它的 id 与分阶段，才看得出卡在哪一栋、哪一段
            pass.worstConfig = config;
            pass.worstPhases = stats.phases.clone();
        }
    }

    private static void finishBenchmark() {
        List<String> lines = new ArrayList<>();
        lines.add("[Bench] res=" + RES + " buildings=" + benchConfigs.size()
                + " mode=" + benchMode + "（每趟一帧一座）");
        reportPass(lines, benchBakePass, "bake");
        reportPass(lines, benchLoadPass, "load");
        for (String line : lines) {
            Log.info(TAG, line);
        }
        // 命令是玩家主动跑的，结论直接上屏；每帧的常态输出仍然只走日志。
        for (String line : lines) {
            notifyPlayer(line.replace("[Bench] ", ""));
        }
        recountPending();
        benchMode = null;
        benchConfigs = List.of();
        benchBakePass = null;
        benchLoadPass = null;
        benchCursor = 0;
    }

    private static void reportPass(List<String> out, BenchPass pass, String label) {
        if (pass == null) {
            return;
        }
        if (pass.measured == 0) {
            out.add("[Bench] " + label + "：无可测样本（miss=" + pass.misses + "）");
            return;
        }
        List<Long> walls = new ArrayList<>(pass.walls);
        walls.sort(null);
        out.add(String.format("[Bench] %-4s n=%d miss=%d 总计 %.1f ms 均值 %.2f ms p50 %.2f ms 最大 %.2f ms",
                label, pass.measured, pass.misses, pass.totalNs / 1e6, pass.totalNs / 1e6 / pass.measured,
                walls.get(walls.size() / 2) / 1e6, pass.maxNs / 1e6));
        StringBuilder sb = new StringBuilder("[Bench]   分阶段：");
        for (Phase phase : Phase.values()) {
            long ns = pass.phases[phase.ordinal()];
            if (ns <= 0) {
                continue;
            }
            sb.append(phase).append(' ')
                    .append(String.format("%.1f", ns / 1e6)).append(" ms(均 ")
                    .append(String.format("%.2f", ns / 1e6 / pass.measured)).append(")  ");
        }
        out.add(sb.toString().trim());
        if (pass.worstConfig != null) {
            StringBuilder worst = new StringBuilder("[Bench]   最坏 ")
                    .append(pass.worstConfig.id()).append(' ')
                    .append(String.format("%.1f", pass.maxNs / 1e6)).append(" ms：");
            for (Phase phase : Phase.values()) {
                long ns = pass.worstPhases[phase.ordinal()];
                if (ns > 0) {
                    worst.append(phase).append(' ').append(String.format("%.1f", ns / 1e6)).append("  ");
                }
            }
            out.add(worst.toString().trim());
        }
    }

    /** 基准测试直接往 CACHE 里写结果，跑完按实际状态重算待烤数，免得 pendingCount 被算歪。 */
    private static void recountPending() {
        int pending = 0;
        for (PreviewEntry entry : CACHE.values()) {
            if (!entry.preview.ready) {
                pending++;
            }
        }
        pendingCount = pending;
    }

    private static void notifyPlayer(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[wandscape] " + message), false);
        }
    }

    /** 计时开关：不在基准里时返回 0，{@link #lap} 随之什么都不做——正常游玩零开销。 */
    private static long mark() {
        return benchInFlight != null ? System.nanoTime() : 0L;
    }

    private static void lap(Phase phase, long startNs) {
        if (benchInFlight != null && startNs != 0L) {
            benchInFlight.phases[phase.ordinal()] += System.nanoTime() - startNs;
        }
    }

    /** {@code try (Timing ignored = time(Phase.READ)) { ... }} —— 作用域结束即记一笔。 */
    private static Timing time(Phase phase) {
        return new Timing(phase);
    }

    private static final class Timing implements AutoCloseable {
        private final Phase phase;
        private final long startNs;

        Timing(Phase phase) {
            this.phase = phase;
            this.startNs = mark();
        }

        @Override public void close() {
            lap(phase, startNs);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Off-screen bake ──
    // ═══════════════════════════════════════════════════════════════

    /**
     * 一栋跨帧烘焙的阶段。**只有 {@link #TESS} 会按时间片切开**（它的成本随建筑大小线性涨），
     * 其余阶段各自是一次原子操作。
     */
    private enum Stage { META, TESS, ASSEMBLE, DONE }

    /** {@link #ensureMeta} 的三态结果。 */
    private enum MetaState { WAIT, EMPTY, READY }

    /**
     * 一栋正在烘焙的建筑。**自带一套字节缓冲**，而不是共用静态缓冲：镶嵌结果要跨帧累积，而
     * {@code BufferBuilder} 没有 {@code discard()} —— 共用缓冲一旦中途被取消（reload / 退世界 /
     * 内容变了），残留顶点就会画进下一栋的图里。自带缓冲取消时直接 close() 收场。
     */
    private static final class BakeJob {
        final BuildingConfig config;
        final Path file;
        final boolean sweepSiblings;
        /** 同步路径（基准测试）允许自己算 META；正常泵一律等 worker，渲染线程不碰这段。 */
        final boolean inlineMeta;
        final ByteBufferBuilder buffer = new ByteBufferBuilder(2 * 1024 * 1024);
        final BufferBuilder builder;
        final MultiBufferSource source;
        Stage stage = Stage.META;
        BuildingPreviewRenderer.ConfigPreviewMeta meta;
        LodPreview preview;
        PoseStack pose;
        NativeImage image;
        int cursor;
        /** 实际工作耗时（不含等 worker 的 WAIT 帧）——分帧之后只有它是能和读盘直接比的数。 */
        long workNs;

        BakeJob(BuildingConfig config, Path file, boolean sweepSiblings, boolean inlineMeta) {
            this.config = config;
            this.file = file;
            this.sweepSiblings = sweepSiblings;
            this.inlineMeta = inlineMeta;
            RenderType solid = RenderType.solid();
            this.builder = new BufferBuilder(buffer, solid.mode(), solid.format());
            // 所有格子都用 solid 渲染，所以这一个 builder 就是全部顶点。
            this.source = renderType -> builder;
        }

        /** 放弃这一栋：释放已镶嵌的顶点缓冲。 */
        void cancel() {
            buffer.close();
        }
    }

    /** 起手一栋（不推进）。 */
    private static BakeJob startJob(BuildingConfig config, boolean inlineMeta) {
        Path file = frameFile(config);
        // 覆盖同名文件不会产生新的孤儿；只有名字变了（pattern / 分辨率 / 视角）才需要扫同族，
        // 所以拿一次 stat 换掉每次写盘都做一遍的目录列举。
        return new BakeJob(config, file, file != null && !Files.exists(file), inlineMeta);
    }

    /** 推进一栋的结果。{@link #WAIT} 必须让泵**当帧直接收工**，否则会空转到预算耗尽。 */
    private enum JobStep { WAIT, PROGRESS, DONE }

    /** 推进一栋，并累计它的**实际工作耗时**（等 worker 的 WAIT 帧不算，分帧之后墙钟不可比）。 */
    private static JobStep advanceJob(BakeJob job, long sliceDeadlineNs) {
        long startNs = System.nanoTime();
        JobStep step = advanceJobInner(job, sliceDeadlineNs);
        if (step != JobStep.WAIT) {
            job.workNs += System.nanoTime() - startNs;
        }
        return step;
    }

    /**
     * 状态机本体，见 {@link #advanceJob}。{@code sliceDeadlineNs} 是本次允许花到的时间点。
     * 返回 {@link JobStep#DONE} = 已收尾（{@link BakeJob#image} 为 null 表示这栋烤不出来）。
     *
     * <p>整段包在 catch 里：镶嵌是逐格调 {@code renderSingleBlock} 的，任何一个坏方块模型抛出来
     * 都不许冒泡进渲染事件（那会变成每帧崩溃），一律降级成「这栋没有图」+ warn。
     */
    private static JobStep advanceJobInner(BakeJob job, long sliceDeadlineNs) {
        try {
            while (true) {
                switch (job.stage) {
                    case META -> {
                        MetaState state = ensureMeta(job);
                        if (state == MetaState.WAIT) {
                            return JobStep.WAIT;
                        }
                        if (state == MetaState.EMPTY) {
                            job.stage = Stage.DONE;
                            return JobStep.DONE;
                        }
                        job.stage = Stage.TESS;
                    }
                    case TESS -> {
                        if (!tessellate(job, sliceDeadlineNs)) {
                            return JobStep.PROGRESS;      // 时间片用完，下一帧接着镶
                        }
                        job.stage = Stage.ASSEMBLE;       // 镶完了：同一帧把它画出来收尾
                    }
                    case ASSEMBLE -> {
                        job.image = assemble(job);
                        job.stage = Stage.DONE;
                        return JobStep.DONE;
                    }
                    default -> {
                        return JobStep.DONE;
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to bake preview {}: {}", job.config.id(), e.getMessage());
            job.image = null;
            job.stage = Stage.DONE;
            return JobStep.DONE;
        }
    }

    /**
     * 备好 META + LOD 格表。正常泵只从缓存里取，取不到就交给 worker、这一帧什么都不做 ——
     * **这是消抖的另一半**：META 56 栋要 327 ms、最贵一栋上百毫秒，而且它没法切片。
     */
    private static MetaState ensureMeta(BakeJob job) {
        if (job.meta != null) {
            return MetaState.READY;
        }
        if (job.config.pattern().isEmpty()) {
            return MetaState.EMPTY;
        }
        long tMeta = mark();
        BuildingPreviewRenderer.ConfigPreviewMeta meta = BuildingPreviewRenderer.peekPreviewMeta(job.config);
        LodPreview preview = peekLod(job.config);
        if (meta == null || preview == null) {
            if (!job.inlineMeta) {
                requestMetaAsync(job.config);
                return MetaState.WAIT;
            }
            if (meta == null) {
                meta = BuildingPreviewRenderer.getPreviewMeta(job.config);
            }
            if (preview == null) {
                preview = lodPreview(job.config, meta);
            }
        }
        lap(Phase.META, tMeta);
        if (meta.resolvedMap.isEmpty() || preview.cells().isEmpty()) {
            return MetaState.EMPTY;
        }
        job.meta = meta;
        job.preview = preview;
        job.pose = buildPose(job.config, meta);
        return MetaState.READY;
    }

    /**
     * 按时间片继续镶嵌顶点，剩下的留到下一帧。**这一段是唯一被切的地方**：成本随格子数线性涨
     * （最贵那栋约 4.7 万格），不切它自己就是一整块几百毫秒的卡顿。
     */
    private static boolean tessellate(BakeJob job, long sliceDeadlineNs) {
        List<PreviewCell> cells = job.preview.cells();
        int factor = job.preview.factor();
        BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
        long tDraw = mark();
        while (job.cursor < cells.size()) {
            PreviewCell cell = cells.get(job.cursor++);
            job.pose.pushPose();
            job.pose.translate(cell.x(), cell.y(), cell.z());
            // LOD 格子：把代表方块放大 factor 倍填满整格，建筑看上去仍是实心的。
            if (factor > 1) {
                job.pose.scale(factor, factor, factor);
            }
            blockRenderer.renderSingleBlock(
                    cell.state(), job.pose, job.source, FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, RenderType.solid());
            job.pose.popPose();
            // 每 64 格才看一次表：别让 nanoTime 自己变成开销
            if ((job.cursor & 0x3F) == 0 && System.nanoTime() >= sliceDeadlineNs) {
                lap(Phase.DRAW, tDraw);
                return false;
            }
        }
        lap(Phase.DRAW, tDraw);
        return true;
    }

    /**
     * 把已镶嵌好的顶点一次画进离屏 target，回读成图。顶点是上一阶段跨帧攒的、不依赖 target 内容，
     * 所以 FBO 不需要跨帧保留。返回 null = 这栋烤不出来（已 warn）。
     */
    private static NativeImage assemble(BakeJob job) {
        ensureTarget();
        Minecraft mc = Minecraft.getInstance();

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
            long tDraw = mark();
            try (MeshData mesh = job.builder.build()) {
                if (mesh != null) {
                    RenderType.solid().draw(mesh);
                }
            }
            lap(Phase.DRAW, tDraw);

            NativeImage image = new NativeImage(RES, RES, false);
            long tReadback = mark();
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, false);
            lap(Phase.READBACK, tReadback);

            long tPost = mark();
            image.flipY();
            if (isFullyTransparent(image)) {
                Log.warn(TAG, "Preview bake {} came out fully transparent (projection/camera issue)", job.config.id());
                image.close();
                return null;
            }
            lap(Phase.POST, tPost);
            return image;
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to bake preview {}: {}", job.config.id(), e.getMessage());
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

    /** 收尾：写盘 + 上传纹理（成功才有图）。 */
    private static ResourceLocation finishJob(BakeJob job) {
        if (job.image == null) {
            return null;
        }
        if (job.file != null) {
            writeToDisk(job.file, job.image, job.sweepSiblings);
        }
        return upload(job.image);
    }

    /** 一栋烤完（或失败）：结果落到条目上，并释放顶点缓冲。 */
    private static void completeJob(BakeJob job) {
        ResourceLocation loc = finishJob(job);
        job.buffer.close();
        activeJob = null;
        PreviewEntry entry = CACHE.get(job.config.id());
        // 条目可能已经在烘焙期间被「同 id 内容变了」那条路删掉并自己减过数了，所以只在还没就绪时落值与减数。
        if (entry != null && !entry.preview.ready) {
            entry.preview.texture = loc;
            entry.preview.ready = true;
            pendingCount = Math.max(0, pendingCount - 1);
        }
        if (loc != null) {
            recordWave(false, job.config.id(), job.workNs);
        }
    }

    /** 镶嵌用的 pose：模型空间 → 屏幕（居中 + 缩放 + 固定 3/4 视角）；每格平移在循环里 push/pop。 */
    private static PoseStack buildPose(BuildingConfig config, BuildingPreviewRenderer.ConfigPreviewMeta meta) {
        PoseStack pose = new PoseStack();
        float scale = scaleForView(config, meta);
        // ModelView is identity; ortho near=1000 far=3000 → visible camera z ∈ [-3000,-1000].
        pose.translate(RES / 2.0F, RES / 2.0F, -2000.0F);
        pose.scale(scale, -scale, scale);
        pose.mulPose(new Quaternionf().rotateX(TILT_RAD));
        pose.mulPose(new Quaternionf().rotateY(VIEW_YAW_RAD));
        pose.translate(-meta.cx - 0.5F, -meta.cy - 0.5F, -meta.cz - 0.5F);
        return pose;
    }

    private static LodPreview peekLod(BuildingConfig config) {
        return LOD_CACHE.peek(config);
    }

    /**
     * 把某配置的 META + LOD 格表丢给 worker 预热。渲染线程**永不自己算**它：56 栋要 327 ms、
     * 最贵一栋上百毫秒，且不可切片。重复调用很廉价（两次查表 + 一次集合命中）。
     */
    private static void requestMetaAsync(BuildingConfig config) {
        if (config.pattern().isEmpty() || benchMode != null) {
            // 基准测试期间不预热：它量的就是真实工作量，别让 worker 抢先把 META 算好。
            return;
        }
        if (BuildingPreviewRenderer.peekPreviewMeta(config) != null && peekLod(config) != null) {
            return;
        }
        if (!META_IN_FLIGHT.add(config.id())) {
            return;
        }
        Util.backgroundExecutor().execute(() -> {
            try {
                BuildingPreviewRenderer.ConfigPreviewMeta meta = BuildingPreviewRenderer.getPreviewMeta(config);
                if (!meta.resolvedMap.isEmpty()) {
                    lodPreview(config, meta);
                }
            } catch (Throwable t) {
                Log.warn(TAG, "Async preview meta failed for {}: {}", config.id(), t.getMessage());
            } finally {
                META_IN_FLIGHT.remove(config.id());
            }
        });
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
