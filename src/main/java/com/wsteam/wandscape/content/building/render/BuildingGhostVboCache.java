package com.wsteam.wandscape.content.building.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.*;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Pre-baked GPU vertex buffers for building ghosts with zero-copy VBO rendering.
 *
 * <p>几何按 16³ 段切分，每段一个独立的 {@link VertexBuffer} 与索引。这么切有两个好处：
 *
 * <ul>
 *   <li><b>视锥剔除</b>：绘制时逐段判可见性，看不见的段连索引都不用重建、draw 也不发。
 *       那栋超大建筑 803 个非空段，站在外面看时能剔掉相当一部分。</li>
 *   <li><b>构建期峰值只受最大一段约束</b>：逐段构建、建完立刻关掉该段的 native 顶点缓冲，
 *       不再需要按整栋楼预留几百 MB（实测中位 468 格/段）。</li>
 * </ul>
 *
 * <p><b>烘焙按 tick 分摊</b>：首次画某栋建筑的虚影要从头烘一栋楼的 VBO。那栋超大建筑
 * pattern 58 万条，解析 + 逐段 tessellation 一次做完实测约 2 秒（spark 里
 * {@code getOrBake} 一战占掉渲染线程 6.87%），整笔卡在一个 tick 上，弱机可能直接卡死。
 * 现在烘焙是一个可续跑作业（{@link GhostBakeJob}），每 tick 只推进
 * {@link #BAKE_BUDGET_NS} 那么多；期间已建好的段照常画（虚影逐段长出来），几何不降级。
 */
public final class BuildingGhostVboCache {

    private static final float GHOST_ALPHA = 0.55f;
    private static final int FULL_BRIGHT = 0xF000F0;

    /** 段尺寸（边长）与位移。 */
    private static final int SECTION_SHIFT = 4;
    private static final int SECTION_SIZE = 1 << SECTION_SHIFT;

    /**
     * 遮罩索引重建的最小间隔（tick）；anchor 变化时不等，立即重建。
     * 1 = 每 tick 至多一次，即把原来的「每渲染帧一次」降到「每 tick 一次」。
     */
    private static final long MASK_REBUILD_INTERVAL_TICKS = 1L;

    /**
     * 每帧、每栋建筑重建遮罩索引的时间预算。原来每 tick 会把**所有可见段**重建一遍
     * （那栋超大建筑约 44 万格逐格 {@code getBlockState} + 803 次 {@code glBufferData}），
     * 整笔卡在那一个 tick 上，表现为帧率忽高忽低。改成按预算轮转：每帧最多花这么久，
     * 转完一圈把所有可见段刷新一次，遮罩最多落后几 tick（刚建好的格子上会残留一小会儿虚影）。
     */
    private static final long MASK_REBUILD_BUDGET_NS = 1_000_000L;

    /**
     * 烘焙按**帧**分摊的时间预算 —— 每帧由渲染入口取一次 {@link #bakeDeadline()}，往下传给
     * 本帧要画的所有虚影共用，与 {@code BuildingPreviewGifCache.pumpQueue} 同一口径
     * （那边也是「整个队列共用一个 deadline」，不是每个建筑一份）。
     *
     * <p>为什么不按建筑算：在建工地是**逐个**画的（{@code ConstructionGhostRenderer} 每帧
     * 遍历所有未完工建筑），每个建筑各给一份预算的话，场上 20 个工地就是 20 份，单帧开销
     * 直接翻 20 倍 —— 那正是要治的病。
     *
     * <p>为什么不按 tick：预算是被「每帧都花掉」的，按帧算代价才被摊平在每一帧上，不会在
     * tick 的第一帧挤出一个尖峰。1ms 在 240 帧下约占帧预算的四分之一，此时每秒能推进约
     * 240ms 的烘焙量。嫌虚影长得慢就调大这个值（单帧上限随之变大）。
     */
    private static final long BAKE_BUDGET_NS = 1_000_000L;

    /**
     * 一栋建筑的虚影（含**烘完的网格**与在跑的作业）多久没被画到就整个释放（纳秒）。
     *
     * <p>烘完的网格是常驻的：顶点与索引在 GPU 上，每段还额外留一份 {@code fullIndex} 直接缓冲，
     * 那栋超大建筑一栋就是几十 MB。没有上限、也没有过期的话，「看过的建筑 × 转过的旋转角」
     * 会一直堆到关游戏 —— 所以要有这一条。
     *
     * <p>口径是**每栋建筑**而不是整个虚影系统：系统级闲置判定会造成「只要还在看着任意一栋
     * 虚影，其它几百栋永远不回收」。代价是玩家一边移动、大楼时不时出视锥时，超过这个时限
     * 会被收掉重烘（渐进的，不会卡）。
     *
     * <p>另外两条释放路径：玩家退出世界（{@code WandscapeClient#onPlayerLoggingOut}，立刻全清）
     * 与 datapack 重载（{@link #closeAll()}）。
     */
    private static final long IDLE_CLEAR_NS = 300L * 1_000_000_000L;

    /** {@link #sweepIdle()} 的最小间隔。它每次渲染回调都会被调到，用不着每帧扫。 */
    private static final long SWEEP_INTERVAL_NS = 50_000_000L;

    /**
     * 本帧所有虚影共用的烘焙时间上限（绝对时刻）。渲染入口每帧取一次，往下传。
     *
     * @see #BAKE_BUDGET_NS
     */
    public static long bakeDeadline() {
        return System.nanoTime() + BAKE_BUDGET_NS;
    }

    private static final Map<String, GhostBucket> CACHE = new HashMap<>();
    /** {@link #sweepIdle()} 上次执行时刻，限流用。 */
    private static long lastSweepNs = System.nanoTime();
    private static final ByteBufferBuilder INDEX_BBB = new ByteBufferBuilder(4 * 1024 * 1024);

    private BuildingGhostVboCache() {}

    /** Draw the full ghost building using event camera ModelView matrix (120 FPS). */
    public static void drawGhost(Minecraft mc, Matrix4f cameraModelView, Matrix4f projection,
                                 Vec3 camPos, BlockPos anchor, BuildingConfig config, int rotationSteps,
                                 Frustum frustum, long bakeDeadlineNs) {
        BakedGhostMesh mesh = getOrBake(mc, config, rotationSteps, anchor, bakeDeadlineNs);
        if (mesh == null) return;

        RenderType rt = RenderType.translucent();
        rt.setupRenderState();
        drawVisibleSections(mesh, mc, cameraModelView, projection, camPos, anchor, frustum, false);
        rt.clearRenderState();
    }

    /** Draw ghost skipping placed blocks (under-construction footprint). */
    public static void drawGhostSkipped(Minecraft mc, Matrix4f cameraModelView, Matrix4f projection,
                                        Vec3 camPos, BlockPos anchor, BuildingConfig config, int rotationSteps,
                                        Frustum frustum, long bakeDeadlineNs) {
        BakedGhostMesh mesh = getOrBake(mc, config, rotationSteps, anchor, bakeDeadlineNs);
        if (mesh == null) return;

        RenderType rt = RenderType.translucent();
        rt.setupRenderState();
        drawVisibleSections(mesh, mc, cameraModelView, projection, camPos, anchor, frustum, true);
        rt.clearRenderState();
    }

    /**
     * 收集可见段、按**由近到远**排序后逐个绘制。
     *
     * <p><b>为什么必须排序</b>：{@link RenderType#translucent()} 的写掩码是 builder 默认的
     * {@code COLOR_DEPTH_WRITE}（颜色与深度都写；MC 里想关掉深度写的类型都显式
     * {@code setWriteMaskState(COLOR_WRITE)}），深度测试是 {@code LEQUAL}。也就是说先画的
     * 面会把深度写死、后面的面只要更远就被拒掉 —— 只要**近的先画**，"墙后面的那部分"根本
     * 不会混色。而段的自然顺序是分组时的哈希桶序（烘一次就固定、与相机无关），先画远再画近时
     * 远的面已经混过色、遮不住了，看起来就是斑驳的"能透视进楼里"。
     *
     * <p>几何一字不动（段、顶点、索引都照旧），只改绘制顺序，所以不触碰"虚影必须完整渲染"
     * 的口径。填充侧：每像素的混色层数从 H(穿过的面数) 降到约 1；顶点侧无收益。
     *
     * @param masked true = 工地虚影（要重建跳过已放置格的遮罩索引）
     */
    private static void drawVisibleSections(BakedGhostMesh mesh, Minecraft mc,
                                            Matrix4f cameraModelView, Matrix4f projection,
                                            Vec3 camPos, BlockPos anchor, Frustum frustum,
                                            boolean masked) {
        // 排序键 = (距离的高 32 位 | 段数组下标)，升序即由近到远；
        // 关掉排序时高 32 位全 0，升序即"段数组下标升序" = 原始顺序，两条路共用一个缓冲。
        long[] order = mesh.drawOrder;
        int n = 0;
        SectionMesh[] sections = mesh.sections;
        for (int i = 0; i < mesh.scanned; i++) {
            SectionMesh section = sections[i];
            if (section == null) continue;   // 该段产不出四边形，或还没建到
            if (!isSectionVisible(section, anchor, frustum)) continue;
            order[n++] = sortKey(section, i, anchor, camPos);
        }
        Arrays.sort(order, 0, n);   // 原始类型排序：零分配、无装箱
        if (n == 0) return;

        // 遮罩重建：按时间预算轮转，别在一次 tick 里把整栋楼的可见段全重建一遍。
        // 没轮到的段继续用上一轮的遮罩索引，至多落后几 tick。
        if (masked) {
            long deadline = System.nanoTime() + MASK_REBUILD_BUDGET_NS;
            int start = mesh.maskCursor % n;
            int advanced = 0;
            for (int k = 0; k < n; k++) {
                SectionMesh section = sections[(int) (order[(start + k) % n] & 0xFFFF_FFFFL)];
                if (needsMaskRebuild(mc, section, anchor)) {
                    rebuildMaskedIndex(mc, section, anchor);
                    advanced = k + 1;
                    if (System.nanoTime() >= deadline) break;
                }
            }
            mesh.maskCursor = (start + advanced) % n;
        }

        // 所有段共用同一个 modelView —— 顶点是按绝对的旋转后偏移烘进去的（见 buildSection
        // 里的 pose.translate），段原点只在视锥剔除那一步用，这里**不加段原点**。所以 shader
        // 与矩阵只需设一次，之后每段只 bind + draw —— 对齐原版 LevelRenderer#renderSectionLayer。
        // （原来是每段 drawWithShader：每帧切 ~800 次 shader 程序、上传 ~800 次 MVP。）
        //
        // 「锚点 − 相机」这次平移走 **ChunkOffset**，不乘进 modelView：rendertype_translucent
        // 的顶点着色器算的是 pos = Position + ChunkOffset、vertexDistance = fog_distance(pos,
        // FogShape)，原版地形正是**逐段**把 ChunkOffset 设成「段原点 − 相机」，好把区块相对顶点
        // 凑成相机相对坐标。两种写法几何逐位等价（M·T·P == M·(P+C)，M 是相机旋转），但 ChunkOffset
        // 留 0 就等于拿**建筑局部坐标**当相机相对坐标 —— 雾按「到锚点的距离」算：那栋
        // 154×233×195 的魔法学院局部距离最大 249（远端角）/232（顶），越过
        // FogEnd = 渲染距离×16 后被 linear_fog 涂成纯 FogColor（夜里只剩 6%~9% 亮度 ≈ 黑），
        // 表现就是「顶和远端那个角是黑的、开光影又全亮」（Iris 换成 gbuffers_*，原版这套雾不参与）。
        RenderSystem.setShader(GameRenderer::getRendertypeTranslucentShader);
        ShaderInstance shader = RenderSystem.getShader();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, cameraModelView, projection, mc.getWindow());
        Uniform chunkOffset = shader.CHUNK_OFFSET;
        if (chunkOffset != null) {
            chunkOffset.set(
                    (float) (anchor.getX() - camPos.x),
                    (float) (anchor.getY() - camPos.y),
                    (float) (anchor.getZ() - camPos.z));
        }
        shader.apply();   // apply() 会把 ChunkOffset 连同其余 uniform 一起上传

        for (int k = 0; k < n; k++) {
            SectionMesh section = sections[(int) (order[k] & 0xFFFF_FFFFL)];
            if (!masked && section.indexClobbered) {
                restoreFullIndex(section);
                section.indexClobbered = false;
            }
            section.vbo.bind();
            section.vbo.draw();
        }
        VertexBuffer.unbind();

        // 还原成原版「段循环结束」的值 0 —— 但必须显式 upload()：set() 只标脏，而
        // ShaderInstance#clear() 并不清 uniform 值，光 set 不传会让这一帧之后任何走地形着色器的
        // draw（手里的半透明方块、别的虚影路径）被这个偏移顶歪。
        if (chunkOffset != null) {
            chunkOffset.set(0.0F, 0.0F, 0.0F);
            chunkOffset.upload();
        }
        shader.clear();
    }

    /**
     * 段到相机的排序键。距离取「相机到段 AABB 的最近点」，相机在段内时为 0。
     * 非负 double 的 IEEE 位模式按 long 解释是单调的，所以取高 32 位就够排序用。
     */
    private static long sortKey(SectionMesh section, int index, BlockPos anchor, Vec3 camPos) {
        double x0 = anchor.getX() + section.originX;
        double y0 = anchor.getY() + section.originY;
        double z0 = anchor.getZ() + section.originZ;
        double x1 = x0 + SECTION_SIZE;
        double y1 = y0 + SECTION_SIZE;
        double z1 = z0 + SECTION_SIZE;
        double dx = camPos.x < x0 ? x0 - camPos.x : (camPos.x > x1 ? camPos.x - x1 : 0.0);
        double dy = camPos.y < y0 ? y0 - camPos.y : (camPos.y > y1 ? camPos.y - y1 : 0.0);
        double dz = camPos.z < z0 ? z0 - camPos.z : (camPos.z > z1 ? camPos.z - z1 : 0.0);
        double distSq = dx * dx + dy * dy + dz * dz;
        long bits = Double.doubleToRawLongBits(distSq) >>> 32;
        return (bits << 32) | (index & 0xFFFF_FFFFL);
    }

    private static boolean isSectionVisible(SectionMesh section, BlockPos anchor, Frustum frustum) {
        if (frustum == null) return true;
        double x = anchor.getX() + section.originX;
        double y = anchor.getY() + section.originY;
        double z = anchor.getZ() + section.originZ;
        return frustum.isVisible(new AABB(x, y, z, x + SECTION_SIZE, y + SECTION_SIZE, z + SECTION_SIZE));
    }

    /**
     * Whether this section's masked index must be rewritten this frame.
     *
     * <p>重写一次要把该段所有格子的索引写一遍再 {@code glBufferData} 上 GPU。分段之后
     * 一次只有一段（中位 468 格，约几十 KB），而原来是整栋楼几十 MB —— 这是分段最大的收益。
     * 遮罩只在「有格子刚建好」时才变，所以这里做节流：anchor 一动就立刻重建（不重建会拿错
     * 位置的遮罩），否则每 {@link #MASK_REBUILD_INTERVAL_TICKS} tick 至多一次。
     */
    private static boolean needsMaskRebuild(Minecraft mc, SectionMesh section, BlockPos anchor) {
        // 当前上传的是完整索引（drawGhost 恢复过）——必须是遮罩版才算数。
        if (!section.indexClobbered) return true;
        if (!anchor.equals(section.maskAnchor)) return true;
        if (section.maskTick == Long.MIN_VALUE) return true;
        return currentTick(mc) - section.maskTick >= MASK_REBUILD_INTERVAL_TICKS;
    }

    private static long currentTick(Minecraft mc) {
        return mc.level != null ? mc.level.getGameTime() : 0L;
    }

    public static void closeAll() {
        synchronized (CACHE) {
            for (GhostBucket bucket : CACHE.values()) {
                bucket.releaseAll();
            }
            CACHE.clear();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── 缓存 ──
    // ═══════════════════════════════════════════════════════════════

    /**
     * 一栋建筑的四个旋转角各自的烘焙结果。
     *
     * <p>{@link #source} 是烘这份 VBO 时的配置实例。进世界时服务端会同步建筑数据，
     * {@code BuildingConfigLoader} 会重建全部实例；键必须是 {@code id} 而不是 record
     * （见 {@link #bucketLocked}）。
     */
    private static final class GhostBucket {
        BuildingConfig source;
        final BakedGhostMesh[] byRotation = new BakedGhostMesh[4];
        final GhostBakeJob[] jobs = new GhostBakeJob[4];
        /** 上次真的画到这栋建筑虚影的时刻，{@link #IDLE_CLEAR_NS} 据此回收。 */
        long lastDrawnNs = System.nanoTime();

        GhostBucket(BuildingConfig source) {
            this.source = source;
        }

        /** 丢掉在跑的作业并归还全部 GPU 缓冲（漏一个 VertexBuffer 就是永久显存泄漏）。 */
        void releaseAll() {
            for (int i = 0; i < 4; i++) {
                jobs[i] = null;
                closeMesh(byRotation[i]);
                byRotation[i] = null;
            }
        }
    }

    private static void closeMesh(BakedGhostMesh mesh) {
        if (mesh == null || mesh.sections == null) return;
        for (SectionMesh section : mesh.sections) {
            if (section != null) section.vbo.close();
        }
    }

    /**
     * 取这栋建筑的缓存桶，**键是 {@code config.id()} 而不是 record**。
     *
     * <p>record 的 {@code equals} 会逐组件比较，含整条 {@code pattern}；而
     * {@code hashCode} 只哈希 id/packageId。进世界同步会把每栋建筑换成新实例，新实例与旧实例
     * 同 hash、不同身份 —— {@code HashMap} 的 identity 短路失效，查找退化成每帧、每栋楼
     * 比一遍 58 万条 pattern（spark 实测占渲染线程 27.16%）。{@code id} 是权威唯一键
     * （见 {@code BuildingConfigLoader#parseAndRegister}），String 的哈希与比较都是 O(1)。
     *
     * <p>实例换了之后只做**一次**内容比较：内容一样就认下新实例（此后走身份短路，VBO 继续
     * 复用 —— 这才是"重进世界缓存生效"）；真变了才释放重烘，不留同 hash 的僵尸键。
     *
     * <p>调用方必须已持有 {@link #CACHE} 的锁。
     */
    private static GhostBucket bucketLocked(BuildingConfig config) {
        String key = config.id();
        GhostBucket bucket = CACHE.get(key);
        if (bucket != null && bucket.source != config) {
            if (bucket.source.equals(config)) {
                bucket.source = config;
            } else {
                bucket.releaseAll();
                bucket = null;
            }
        }
        if (bucket == null) {
            bucket = new GhostBucket(config);
            CACHE.put(key, bucket);
        }
        return bucket;
    }

    /**
     * 取这栋建筑该旋转角的网格，顺手把烘焙作业推到 {@code bakeDeadlineNs} 为止。
     *
     * <p>整段都持 {@link #CACHE} 的锁：调用方全在渲染线程，唯一的另一个线程是 datapack
     * 重载时跑 {@link #closeAll()} 的那条。锁只为了不和它交错（作业推进有单帧预算上界，
     * 真撞上也只是让重载等一小会儿）。
     */
    private static BakedGhostMesh getOrBake(Minecraft mc, BuildingConfig config, int rotationSteps,
                                            BlockPos anchor, long bakeDeadlineNs) {
        if (config.pattern().isEmpty()) return null;
        int steps = rotationSteps & 3;
        synchronized (CACHE) {
            GhostBucket bucket = bucketLocked(config);
            BakedGhostMesh mesh = bucket.byRotation[steps];
            GhostBakeJob job = bucket.jobs[steps];

            // 换维度了（level 实例变了）：半成品作业连着旧 Level 与半拉的段，作废重开。
            // 必须往前挪到建作业之前 —— 建作业要花一份 n 长的数组，建完立刻扔掉是白烧。
            // 已经烘完的网格不受影响（那时 jobs[steps] 早已是 null），它的顶点是相对偏移；
            // 唯一带世界信息的是烘进去的群系染色，换维度后会沿用旧的，属于可接受的瑕疵。
            if (job != null && job.level != mc.level) {
                bucket.jobs[steps] = null;
                closeMesh(mesh);
                bucket.byRotation[steps] = null;
                mesh = null;
                job = null;
            }

            if (mesh == null) {
                mesh = new BakedGhostMesh();
                bucket.byRotation[steps] = mesh;
                job = new GhostBakeJob(mc, config, steps, anchor);
                bucket.jobs[steps] = job;
            }

            bucket.lastDrawnNs = System.nanoTime();
            if (job != null && job.advance(mesh, bakeDeadlineNs)) {
                bucket.jobs[steps] = null;   // 完工
                Log.debug(LogCategory.BUILDING, "ghost", "baked {} rot={} sections={} in {} ms",
                        config.id(), steps, mesh.builtCount, job.elapsedMs());
            }
            // 还没建出任何段（或这栋楼本来就产不出四边形）：当帧不画。
            // mesh 一直留在桶里，所以不会退化成"每帧重烘"。
            return mesh.builtCount == 0 ? null : mesh;
        }
    }

    /**
     * 回收闲置的虚影：一栋建筑 {@link #IDLE_CLEAR_NS} 没被画到，就把它的网格与作业整个释放，
     * 并从缓存里移出（下次要看再重烘）。
     *
     * <p>得由「面板没开也会跑」的地方调（{@code ConstructionGhostRenderer} 的渲染回调开头），
     * 否则关掉面板之后就没人触发回收了。内部按 {@link #SWEEP_INTERVAL_NS} 限流。
     */
    public static void sweepIdle() {
        long now = System.nanoTime();
        synchronized (CACHE) {
            if (now - lastSweepNs < SWEEP_INTERVAL_NS) return;
            lastSweepNs = now;
            Iterator<GhostBucket> it = CACHE.values().iterator();
            while (it.hasNext()) {
                GhostBucket bucket = it.next();
                if (now - bucket.lastDrawnNs <= IDLE_CLEAR_NS) continue;
                bucket.releaseAll();
                it.remove();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── 烘焙作业（可跨 tick 续跑）──
    // ═══════════════════════════════════════════════════════════════

    /**
     * 一栋建筑某个旋转角的烘焙作业。
     *
     * <p>四个阶段，每个都是「带 cursor 的下标循环」，每次 {@link #advance} 只把当前阶段推进
     * 一片（每片至多 {@link #CHUNK} 次迭代），外层按时间预算反复调进：
     *
     * <ul>
     *   <li>A 解析：逐格算出旋转后偏移与方块状态，用 palette 级状态表（见
     *       {@code BuildingGhostRenderer#paletteStates}）—— 这一步原来对每格重新解析一次
     *       方块状态字符串，58 万格约 0.9 秒，是按 V 卡顿的主因。</li>
     *   <li>B 建视图：逐面剔除要用的假方块视图（{@link BuildingGhostBlockView.Builder}）。</li>
     *   <li>C 分组：按 16³ 段把 pattern 下标分组，三趟可切的下标循环。</li>
     *   <li>D 建段：逐段 tessellation + 上传，一段一个切片单位，随时可中断。</li>
     * </ul>
     */
    private static final class GhostBakeJob {

        /** 单次切片在一个内层循环里最多推进的格数；每片都短到不必再查表。 */
        private static final int CHUNK = 1 << 15;

        private static final int PHASE_RESOLVE = 0;
        private static final int PHASE_VIEW = 1;
        private static final int PHASE_GROUP = 2;
        private static final int PHASE_SECTIONS = 3;
        private static final int PHASE_DONE = 4;

        private final Minecraft mc;
        private final int steps;
        private final BlockPos anchor;
        private final int n;

        /** 作业创建时的世界实例；变了就说明换了世界，作业作废（见 {@code getOrBake}）。 */
        final Level level;
        private final long startedNs = System.nanoTime();

        private final List<BlockOffset> pattern;
        private final List<Integer> blockIndices;
        /** palette 级方块状态表（已按 steps 旋转），下标即 palette 下标。 */
        private final BlockState[] byPalette;

        private final BlockOffset[] rotatedOffsets;
        private final BlockState[] cellStates;
        private final Block[] cellBlocks;

        private int phase = PHASE_RESOLVE;
        private int cursor;

        private BuildingGhostBlockView.Builder viewBuilder;
        private BuildingGhostBlockView view;

        private long[] sectionKeys;
        private Long2IntMap sectionIndexOf;
        private int[] sectionCounts;
        private int[] sectionFillCursor;
        private int[][] sectionCells;
        private int groupPass;

        private int sectionIndex;
        private final RandomSource random = RandomSource.create(42L);

        GhostBakeJob(Minecraft mc, BuildingConfig config, int steps, BlockPos anchor) {
            this.mc = mc;
            this.level = mc.level;
            this.steps = steps;
            this.anchor = anchor;
            this.pattern = config.pattern();
            this.blockIndices = config.blockIndices();
            this.byPalette = BuildingGhostRenderer.paletteStates(config, steps);
            this.n = pattern.size();
            this.rotatedOffsets = new BlockOffset[n];
            this.cellStates = new BlockState[n];
            this.cellBlocks = new Block[n];
        }

        /** 作业从建下到烘完的墙钟耗时（毫秒）；分摊烘焙后这个数包含中间空闲的帧，仅供诊断。 */
        long elapsedMs() {
            return (System.nanoTime() - startedNs) / 1_000_000L;
        }

        /**
         * 推进到 {@code deadlineNs} 用完或这栋楼烘完；返回 {@code true} 表示烘完了。
         *
         * <p>预算是**全帧共用**的绝对时刻，所以预算耗尽时这里一步都不做 —— 那就等于这一帧
         * 把它排在后面。不会饿死：轮到前面的作业总有烘完的一刻，之后自然轮到它。
         */
        boolean advance(BakedGhostMesh mesh, long deadlineNs) {
            while (phase < PHASE_DONE && System.nanoTime() < deadlineNs) {
                // 每个 step 内部各自有 CHUNK 上界（建段那一步是「一整段」），所以最坏也只是
                // 超出预算一次切片单位。
                if (phase == PHASE_RESOLVE) stepResolve();
                else if (phase == PHASE_VIEW) stepView();
                else if (phase == PHASE_GROUP) stepGroup(mesh);
                else stepSections(mesh);
            }
            return phase >= PHASE_DONE;
        }

        private void stepResolve() {
            int end = Math.min(n, cursor + CHUNK);
            for (; cursor < end; cursor++) {
                rotatedOffsets[cursor] = BuildingRotation.rotateOffset(pattern.get(cursor), steps);
                BlockState state = byPalette[blockIndices.get(cursor)];
                cellStates[cursor] = state;
                cellBlocks[cursor] = state != null ? state.getBlock() : null;
            }
            if (cursor < n) return;
            // 逐面剔除用的方块视图（B6）。**整栋一份**——跨段边界的邻居必须查得到，
            // 所以它不能按段切。建不出来（包围盒过大等）就是 null，退回不做剔除的老路径。
            viewBuilder = new BuildingGhostBlockView.Builder(rotatedOffsets, cellStates, level, anchor);
            phase = PHASE_VIEW;
            cursor = 0;
        }

        private void stepView() {
            if (!viewBuilder.step()) return;
            view = viewBuilder.build();
            viewBuilder = null;
            phase = PHASE_GROUP;
            cursor = 0;
            groupPass = 0;
            sectionKeys = new long[n];
            sectionIndexOf = new Long2IntOpenHashMap();
            sectionIndexOf.defaultReturnValue(-1);
        }

        /** 把 pattern 下标按 16³ 段分组，填出 {@code sectionCells}（段 → 该段的 pattern 下标）。 */
        private void stepGroup(BakedGhostMesh mesh) {
            if (groupPass == 0) {
                int end = Math.min(n, cursor + CHUNK);
                for (; cursor < end; cursor++) {
                    long key = sectionKey(rotatedOffsets[cursor]);
                    sectionKeys[cursor] = key;
                    if (!sectionIndexOf.containsKey(key)) {
                        sectionIndexOf.put(key, sectionIndexOf.size());
                    }
                }
                if (cursor < n) return;
                int sectionCount = sectionIndexOf.size();
                sectionCounts = new int[sectionCount];
                sectionFillCursor = new int[sectionCount];
                groupPass = 1;
                cursor = 0;
                return;
            }

            if (groupPass == 1) {
                int end = Math.min(n, cursor + CHUNK);
                for (; cursor < end; cursor++) {
                    sectionCounts[sectionIndexOf.get(sectionKeys[cursor])]++;
                }
                if (cursor < n) return;
                sectionCells = new int[sectionCounts.length][];
                for (int s = 0; s < sectionCounts.length; s++) {
                    sectionCells[s] = new int[sectionCounts[s]];
                }
                groupPass = 2;
                cursor = 0;
                return;
            }

            int end = Math.min(n, cursor + CHUNK);
            for (; cursor < end; cursor++) {
                int s = sectionIndexOf.get(sectionKeys[cursor]);
                sectionCells[s][sectionFillCursor[s]++] = cursor;
            }
            if (cursor < n) return;

            // 分组完成：段数组按总槽位定长分配，逐段往槽里填。产不出四边形的段留 null，
            // 段序不变（与老实现里 sections 只收非空段的顺序一致）。
            mesh.sections = new SectionMesh[sectionCells.length];
            mesh.drawOrder = new long[sectionCells.length];
            // 分组用的中间表到这儿就没用了：那栋超大建筑光 sectionKeys 就是 4.6 MB，
            // 半成品作业可能挂很久（见 #IDLE_CLEAR_NS），别白占着。
            sectionKeys = null;
            sectionIndexOf = null;
            sectionCounts = null;
            sectionFillCursor = null;
            phase = PHASE_SECTIONS;
            sectionIndex = 0;
        }

        /** 建一段。一段是一个切片单位：段内 tessellation 与 VBO 上传必须是原子的。 */
        private void stepSections(BakedGhostMesh mesh) {
            if (sectionIndex >= sectionCells.length) {
                phase = PHASE_DONE;
                return;
            }
            SectionMesh section = buildSection(mc, sectionCells[sectionIndex],
                    rotatedOffsets, cellStates, cellBlocks, view, random);
            mesh.sections[sectionIndex] = section;
            if (section != null) mesh.builtCount++;
            sectionIndex++;
            mesh.scanned = sectionIndex;
            if (sectionIndex >= sectionCells.length) phase = PHASE_DONE;
        }
    }

    private static long sectionKey(BlockOffset o) {
        return (((long) (o.x() >> SECTION_SHIFT) & 0x1FFFFF) << 32)
                | (((long) (o.y() >> SECTION_SHIFT) & 0x7FF) << 21)
                | ((o.z() >> SECTION_SHIFT) & 0x1FFFFF);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── 构建 ──
    // ═══════════════════════════════════════════════════════════════

    /**
     * 构建一个段。顶点缓冲在这里按**本段**的非空气格数预留，建完立刻关闭 ——
     * {@link VertexBuffer#upload} 是同步的 {@code glBufferData}，拷进 GPU 后它就没用了；
     * 而 {@link ByteBufferBuilder} 没有 Cleaner 也没有 finalizer，只有 {@code close()}
     * 会 {@code ALLOCATOR.free}，漏掉就是每次烘焙永久泄漏整个 capacity。
     */
    private static SectionMesh buildSection(Minecraft mc, int[] cells,
                                            BlockOffset[] rotatedOffsets, BlockState[] cellStates,
                                            Block[] cellBlocks, BuildingGhostBlockView view,
                                            RandomSource random) {
        int m = cells.length;
        BlockOffset first = rotatedOffsets[cells[0]];
        int originX = first.x() >> SECTION_SHIFT << SECTION_SHIFT;
        int originY = first.y() >> SECTION_SHIFT << SECTION_SHIFT;
        int originZ = first.z() >> SECTION_SHIFT << SECTION_SHIFT;

        ByteBufferBuilder vertBbb = new ByteBufferBuilder(sectionVertexCapacity(cells, cellStates));
        try {
            BufferBuilder bb = new BufferBuilder(vertBbb, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            int[] vertexCount = new int[1];
            MultiBufferSource ghostSource = rt -> new AlphaCountingConsumer(bb, vertexCount);
            // 剔除路径直接要 VertexConsumer（不是 MultiBufferSource）——AlphaCountingConsumer
            // 本来就是直接包 BufferBuilder 的，复用一个实例，别每格新建。
            VertexConsumer cellConsumer = view != null ? new AlphaCountingConsumer(bb, vertexCount) : null;

            int[] quadStart = new int[m];
            int[] quadCount = new int[m];
            BlockOffset[] offsets = new BlockOffset[m];
            Block[] blocks = new Block[m];
            int totalQuads = 0;

            PoseStack pose = new PoseStack();

            // 不再对几何体施加全局旋转：旋转几何体会把每格体积相对构造偏移最多 1 格
            // （90°/270° 偏 1 格、180° 两方向各偏 1 格）。改为每格平移到旋转后的偏移
            // （rotatedOffsets）并用旋转后的 BlockState 渲染，与服务端构造逐格一致。
            for (int c = 0; c < m; c++) {
                int i = cells[c];
                BlockState state = cellStates[i];
                BlockOffset rotated = rotatedOffsets[i];
                offsets[c] = rotated;
                blocks[c] = cellBlocks[i];

                // Skip animated blocks (chests, shulker boxes, signs, banners, ...). They
                // have no static block model — renderSingleBlock would tessellate them
                // through the item/BESR path into this BLOCK-format buffer, corrupting
                // vertex layout and sampling the wrong atlas (chest atlas vs block atlas).
                // They are drawn by a separate per-frame pass (BuildingGhostRenderer.renderGhostAnimated).
                if (state == null || state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED) {
                    quadStart[c] = totalQuads;
                    quadCount[c] = 0;
                    continue;
                }

                int before = vertexCount[0];
                pose.pushPose();
                pose.translate(rotated.x(), rotated.y(), rotated.z());

                if (view != null) {
                    // checkSides = true：原版会拿 view 逐个方向查邻居，被不透明邻居挡住的面直接
                    // 不产出——这也顺带让「六面都被包住」的方块产出 0 个四边形。用不带 AO 的
                    // 那版是因为原路径 renderSingleBlock 本就是平光；挂上 AO 会凭空多出转角明暗。
                    BlockPos cellPos = new BlockPos(rotated.x(), rotated.y(), rotated.z());
                    mc.getBlockRenderer().getModelRenderer().tesselateWithoutAO(
                            view, mc.getBlockRenderer().getBlockModel(state), state, cellPos,
                            pose, cellConsumer, true, random, state.getSeed(cellPos),
                            OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
                } else {
                    mc.getBlockRenderer().renderSingleBlock(
                            state, pose, ghostSource, FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                            ModelData.EMPTY, RenderType.translucent());
                }

                pose.popPose();

                quadStart[c] = totalQuads;
                quadCount[c] = (vertexCount[0] - before) / 4;
                totalQuads += quadCount[c];
            }

            if (totalQuads == 0) return null;

            MeshData mesh = bb.buildOrThrow();
            VertexFormat.IndexType indexType = mesh.drawState().indexType();
            VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            try {
                vbo.bind();
                vbo.upload(mesh);
                VertexBuffer.unbind();

                ByteBuffer fullIndex = ByteBuffer.allocateDirect(totalQuads * 6 * indexType.bytes);
                long fp = MemoryUtil.memAddress(fullIndex);
                for (int q = 0; q < totalQuads; q++) {
                    int base = q * 4;
                    writeQuadIndex(fp, indexType, base, base + 1, base + 2, base + 2, base + 3, base);
                    fp += 6L * indexType.bytes;
                }

                return new SectionMesh(originX, originY, originZ, vbo, indexType,
                        quadStart, quadCount, offsets, blocks, fullIndex);
            } catch (RuntimeException | Error e) {
                // 这一段还没挂进任何 SectionMesh，抛出去就没人关得到它了（那栋超大建筑索引缓冲
                // 十几 MB，OOM 真会发生）—— 自己关掉再抛，否则每次重试都漏一个 GL buffer。
                vbo.close();
                throw e;
            }
        } finally {
            vertBbb.close();
        }
    }

    /**
     * 本段顶点缓冲容量上界：段内非空气格数 × 6 面 × 4 顶点 × 32 字节（{@code BLOCK} 格式）。
     *
     * <p>不把空气算进去——空气占 pattern 相当比例，而空气质量为 0
     * （{@code AirBlock#getRenderShape} 返回 {@code INVISIBLE}），算进去是平白多占。
     * 真超过这个上界时 {@code ByteBufferBuilder} 自会增长，不会截断。
     */
    private static int sectionVertexCapacity(int[] cells, BlockState[] cellStates) {
        long nonAir = 0L;
        for (int i : cells) {
            BlockState state = cellStates[i];
            if (state != null && !state.isAir()) {
                nonAir++;
            }
        }
        long bytes = nonAir * 6L * 4L * 32L;
        if (bytes < 256L * 1024L) {
            return 256 * 1024;
        }
        return (int) Math.min(bytes, Integer.MAX_VALUE - 8);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── 索引 ──
    // ═══════════════════════════════════════════════════════════════

    private static void restoreFullIndex(SectionMesh section) {
        long dest = INDEX_BBB.reserve(section.fullIndex.capacity());
        MemoryUtil.memCopy(MemoryUtil.memAddress(section.fullIndex), dest, section.fullIndex.capacity());
        uploadIndex(section);
    }

    private static void rebuildMaskedIndex(Minecraft mc, SectionMesh section, BlockPos anchor) {
        long dest = INDEX_BBB.reserve(section.fullIndex.capacity());
        int m = section.offsets.length;
        long p = dest;
        for (int c = 0; c < m; c++) {
            BlockOffset off = section.offsets[c];
            boolean skip = section.cellBlocks[c] != null
                    && mc.level.getBlockState(anchor.offset(off.x(), off.y(), off.z()))
                            .getBlock() == section.cellBlocks[c];
            int start = section.quadStart[c];
            int count = section.quadCount[c];
            for (int q = 0; q < count; q++) {
                int base = (start + q) * 4;
                if (skip) {
                    writeQuadIndex(p, section.indexType, base, base, base, base, base, base);
                } else {
                    writeQuadIndex(p, section.indexType, base, base + 1, base + 2, base + 2, base + 3, base);
                }
                p += 6L * section.indexType.bytes;
            }
        }
        uploadIndex(section);
        section.indexClobbered = true;
        section.maskAnchor = anchor.immutable();
        section.maskTick = currentTick(mc);
    }

    private static void uploadIndex(SectionMesh section) {
        ByteBufferBuilder.Result result = INDEX_BBB.build();
        if (result == null) return;
        section.vbo.bind();
        section.vbo.uploadIndexBuffer(result);
        VertexBuffer.unbind();
    }

    private static void writeQuadIndex(long ptr, VertexFormat.IndexType type,
                                       int i0, int i1, int i2, int i3, int i4, int i5) {
        if (type == VertexFormat.IndexType.SHORT) {
            MemoryUtil.memPutShort(ptr, (short) i0);
            MemoryUtil.memPutShort(ptr + 2, (short) i1);
            MemoryUtil.memPutShort(ptr + 4, (short) i2);
            MemoryUtil.memPutShort(ptr + 6, (short) i3);
            MemoryUtil.memPutShort(ptr + 8, (short) i4);
            MemoryUtil.memPutShort(ptr + 10, (short) i5);
        } else {
            MemoryUtil.memPutInt(ptr, i0);
            MemoryUtil.memPutInt(ptr + 4, i1);
            MemoryUtil.memPutInt(ptr + 8, i2);
            MemoryUtil.memPutInt(ptr + 12, i3);
            MemoryUtil.memPutInt(ptr + 16, i4);
            MemoryUtil.memPutInt(ptr + 20, i5);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── 绘制 ──
    // ═══════════════════════════════════════════════════════════════

    private static final class BakedGhostMesh {
        /** 全部段槽位；还没建到的槽位、以及产不出四边形的段都是 null。 */
        SectionMesh[] sections;
        /** 每帧复用的排序缓冲（排序键 = 距离高位 | 段下标），长度 = sections.length。 */
        long[] drawOrder;
        /** 已扫过的槽位数（{@code [0, scanned)} 里非 null 的才是已建好的段）。 */
        int scanned;
        /** 已建出的段数。 */
        int builtCount;
        /** 遮罩重建的轮转起点（可见列表内的下标），让预算公平地铺到所有可见段上。 */
        int maskCursor;
    }

    private static final class SectionMesh {
        /** 段原点（相对 anchor 的 pattern 空间坐标，已对齐到 16 的倍数）。 */
        final int originX, originY, originZ;
        final VertexBuffer vbo;
        final VertexFormat.IndexType indexType;
        final int[] quadStart;
        final int[] quadCount;
        final BlockOffset[] offsets;
        final Block[] cellBlocks;
        final ByteBuffer fullIndex;
        boolean indexClobbered;
        /** 当前遮罩索引对应的 anchor；null = 还没建过遮罩。 */
        BlockPos maskAnchor;
        /** 上次重建遮罩的 tick；{@link Long#MIN_VALUE} = 还没建过。 */
        long maskTick = Long.MIN_VALUE;

        SectionMesh(int originX, int originY, int originZ,
                    VertexBuffer vbo, VertexFormat.IndexType indexType,
                    int[] quadStart, int[] quadCount,
                    BlockOffset[] offsets, Block[] cellBlocks,
                    ByteBuffer fullIndex) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.vbo = vbo;
            this.indexType = indexType;
            this.quadStart = quadStart;
            this.quadCount = quadCount;
            this.offsets = offsets;
            this.cellBlocks = cellBlocks;
            this.fullIndex = fullIndex;
        }
    }

    private static final class AlphaCountingConsumer implements VertexConsumer {
        private final VertexConsumer real;
        private final int[] count;

        AlphaCountingConsumer(VertexConsumer real, int[] count) {
            this.real = real;
            this.count = count;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            real.addVertex(x, y, z);
            count[0]++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            real.setColor(r, g, b, (int) (a * GHOST_ALPHA));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            real.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            real.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            real.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            real.setNormal(x, y, z);
            return this;
        }
    }
}
