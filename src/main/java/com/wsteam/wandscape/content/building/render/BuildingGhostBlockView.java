package com.wsteam.wandscape.content.building.render;

import com.wsteam.wandscape.content.building.data.BlockOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;

/**
 * 一栋建筑（某个旋转角）的只读方块视图，作用只有一个：让原版的
 * {@code Block.shouldRenderFace} 能在烘焙虚影时判定「这个面被邻居挡住了吗」，
 * 从而把看不见的面整片丢掉。
 *
 * <p>原版做这件事的入口是 {@code BlockRenderDispatcher.renderBatched(...)} →
 * {@code ModelBlockRenderer.tesselateBlock(level, ..., checkSides, ...)}，其中的
 * {@code level} 就是这么一个「假的方块访问器」。虚影没有真实世界可查，所以这里
 * 用建筑自己的 pattern 造一个。
 *
 * <p><b>三态约定</b>（见 {@link #getBlockState}）——三种情况一律返回空气，也就是
 * 「这格没有方块」：
 * <ul>
 *   <li>pattern 里明写的 {@code minecraft:air} 条目；</li>
 *   <li>pattern 里根本没有的坐标（视图范围内外都是）；</li>
 *   <li>跨越 pattern 边界的邻居查询。</li>
 * </ul>
 * 这不等于「把这格变成空气」。虚影只是渲染，不会在世界里放任何东西；空气格不遮挡，
 * 所以贴着空气的那一面照常画出来，这正是我们要的。
 *
 * <p><b>内存</b>：内部是一张覆盖 pattern 包围盒的定长索引表（{@code short[]}，指向
 * 一张小型状态表），不做哈希。之所以不用 {@code Long2ObjectMap}，是因为一次烘焙里
 * 邻格查询是「每块 × 每面 × 每面顶点」量级（那栋超大建筑约三千万次），哈希建表再快
 * 也要几十纳秒一次，稠密表是 3 ns 量级。代价是包围盒体积决定的固定内存：
 * 154×234×197 ≈ 710 万格 → 约 14 MB；超过 {@link #MAX_CELLS} 时
 * {@link #create} 直接返回 null，调用方退回不做剔除的老路径（宁可不优化，也不赌内存）。
 */
public final class BuildingGhostBlockView implements BlockAndTintGetter {

    /** 定长索引表容纳的最大格数（超过就放弃剔除，退回老路径）。约合 32 MB。 */
    private static final long MAX_CELLS = 16L * 1024 * 1024;

    private final short[] grid;
    private final BlockState[] states;
    private final int originX, originY, originZ;
    private final int sizeX, sizeY, sizeZ;

    /** 真实世界：只为 {@link #getBlockTint} 取群系染色，其余一概不用。 */
    private final Level realLevel;
    /** 建筑锚点：视图内的相对坐标加上它就是世界坐标。 */
    private final BlockPos anchor;

    private BuildingGhostBlockView(short[] grid, BlockState[] states,
                                   int originX, int originY, int originZ,
                                   int sizeX, int sizeY, int sizeZ,
                                   Level realLevel, BlockPos anchor) {
        this.grid = grid;
        this.states = states;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.realLevel = realLevel;
        this.anchor = anchor;
    }

    /**
     * 按已旋转的偏移与状态建视图；包围盒过大或没有可渲染方块时返回 {@code null}。
     *
     * @param rotatedOffsets 已按建筑旋转角旋转、相对 anchor 的偏移（与 {@code states} 平行）
     * @param states         对应的方块状态；{@code null} 与空气一样按「没有方块」处理
     */
    @Nullable
    public static BuildingGhostBlockView create(BlockOffset[] rotatedOffsets, BlockState[] states,
                                                @Nullable Level realLevel, BlockPos anchor) {
        Builder builder = new Builder(rotatedOffsets, states, realLevel, anchor);
        while (!builder.step()) {
            // 一口气跑完：调用方要么在做可续跑的烘焙作业（自己分片），要么要的就是一次到位。
        }
        return builder.build();
    }

    /**
     * 可续跑视图构建，给「烘焙摊到多 tick」的虚影作业用（见 {@code BuildingGhostVboCache}）。
     *
     * <p>两趟下标循环，每趟各自有推进上界，外层按时间预算反复调 {@link #step()} 即可：
     * 先扫包围盒，再按包围盒定长建索引表并逐格填充。中间唯一不可切的是一次
     * {@code new short[cells]}（最大 {@link #MAX_CELLS} ≈ 32 MB，一次零化几十毫秒），
     * 分段方案在这里也换不到更多。
     */
    public static final class Builder {

        /** 单次 {@link #step()} 在一个内层循环里最多推进的格数。 */
        private static final int CHUNK = 1 << 15;

        private final BlockOffset[] rotatedOffsets;
        private final BlockState[] states;
        private final Level realLevel;
        private final BlockPos anchor;
        private final int n;

        /** 0 = 扫包围盒，1 = 填索引表，2 = 已完。 */
        private int pass;
        private int cursor;
        private boolean abandoned;

        private int minX, minY, minZ, maxX, maxY, maxZ;
        private int sizeX, sizeY, sizeZ;
        private short[] grid;
        // 建筑自身用到的状态收进一张小表；空气不入表，落在 0 上。
        private final java.util.HashMap<BlockState, Short> indexOf = new java.util.HashMap<>();
        private final java.util.ArrayList<BlockState> palette = new java.util.ArrayList<>();

        public Builder(BlockOffset[] rotatedOffsets, BlockState[] states,
                       @Nullable Level realLevel, BlockPos anchor) {
            this.rotatedOffsets = rotatedOffsets;
            this.states = states;
            this.realLevel = realLevel;
            this.anchor = anchor;
            this.n = rotatedOffsets.length;
            if (n == 0 || states.length != n || realLevel == null) {
                this.abandoned = true;
                this.pass = 2;
            } else {
                this.minX = this.minY = this.minZ = Integer.MAX_VALUE;
                this.maxX = this.maxY = this.maxZ = Integer.MIN_VALUE;
            }
        }

        /** 推进一小片；返回 {@code true} 表示已建完（用 {@link #build()} 取结果）。 */
        public boolean step() {
            if (pass == 2) return true;
            if (pass == 0) {
                int end = Math.min(n, cursor + CHUNK);
                for (; cursor < end; cursor++) {
                    BlockOffset off = rotatedOffsets[cursor];
                    if (off.x() < minX) minX = off.x();
                    if (off.x() > maxX) maxX = off.x();
                    if (off.y() < minY) minY = off.y();
                    if (off.y() > maxY) maxY = off.y();
                    if (off.z() < minZ) minZ = off.z();
                    if (off.z() > maxZ) maxZ = off.z();
                }
                if (cursor < n) return false;

                long sx = (long) maxX - minX + 1;
                long sy = (long) maxY - minY + 1;
                long sz = (long) maxZ - minZ + 1;
                long cells = sx * sy * sz;
                if (cells > MAX_CELLS || cells > Integer.MAX_VALUE) {
                    abandoned = true;
                    pass = 2;
                    return true;
                }
                sizeX = (int) sx;
                sizeY = (int) sy;
                sizeZ = (int) sz;
                // 0 = 空气（缺省值），其余是 states 里的下标 + 1
                grid = new short[sizeX * sizeY * sizeZ];
                pass = 1;
                cursor = 0;
                return false;
            }

            int end = Math.min(n, cursor + CHUNK);
            for (; cursor < end; cursor++) {
                BlockState state = states[cursor];
                if (state == null || state.isAir()) continue;
                BlockOffset off = rotatedOffsets[cursor];
                int lx = off.x() - minX, ly = off.y() - minY, lz = off.z() - minZ;
                short slot = indexOf.computeIfAbsent(state, s -> {
                    palette.add(s);
                    return (short) palette.size();   // 刻意 +1，好让 0 留给空气
                });
                grid[(ly * sizeZ + lz) * sizeX + lx] = slot;
            }
            // 槽位是 short，且 0 留给空气。状态种类多到这个地步只可能来自畸形数据包，
            // 直接放弃剔除退回老路径，别让 0 号槽把某个方块静默变成空气。
            if (palette.size() > Short.MAX_VALUE) {
                abandoned = true;
                pass = 2;
                return true;
            }
            if (cursor < n) return false;
            pass = 2;
            return true;
        }

        /** 建完后的视图；被放弃（包围盒过大 / 无数据）时返回 {@code null}。可重复调用。 */
        @Nullable
        public BuildingGhostBlockView build() {
            if (abandoned || grid == null) return null;
            return new BuildingGhostBlockView(grid, palette.toArray(new BlockState[0]),
                    minX, minY, minZ, sizeX, sizeY, sizeZ, realLevel, anchor);
        }
    }

    // ── BlockGetter ──

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int lx = pos.getX() - originX;
        if (lx < 0 || lx >= sizeX) return Blocks.AIR.defaultBlockState();
        int ly = pos.getY() - originY;
        if (ly < 0 || ly >= sizeY) return Blocks.AIR.defaultBlockState();
        int lz = pos.getZ() - originZ;
        if (lz < 0 || lz >= sizeZ) return Blocks.AIR.defaultBlockState();
        short slot = grid[(ly * sizeZ + lz) * sizeX + lx];
        return slot == 0 ? Blocks.AIR.defaultBlockState() : states[slot - 1];
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return Fluids.EMPTY.defaultFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getMinBuildHeight() {
        return 0;
    }

    @Override
    public int getHeight() {
        return sizeY;
    }

    // ── BlockAndTintGetter ──

    /**
     * 群系染色转发给真实世界：{@code pos} 是相对 anchor 的建筑内坐标，加上 anchor 就是
     * 世界坐标。这一步不能省——草地、树叶、藤蔓的绿色全从这里来，恒返白会把它们画成灰的。
     */
    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        return realLevel.getBlockTint(anchor.offset(pos), colorResolver);
    }

    /**
     * 恒返 1.0：虚影一直是不带方向明暗的平光（原路径 {@code renderSingleBlock} 也是这样），
     * 这里若返回原版的方向系数，六面就会各暗各的，观感会变。
     */
    @Override
    public float getShade(Direction direction, boolean shade) {
        return 1.0F;
    }

    /** 恒返满亮：与原路径传给 {@code renderSingleBlock} 的 {@code FULL_BRIGHT} 一致。 */
    @Override
    public int getBrightness(LightLayer lightType, BlockPos pos) {
        return 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return 15;
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return true;
    }

    /**
     * 永不会被查——{@link #getBrightness} 等取光入口都已覆写。返回真实世界的光照引擎只是
     * 为了不给空值：万一将来有哪个模型绕过上面几个入口直接摸光照引擎，拿到一份合法对象
     * 也不会崩。
     */
    @Override
    public LevelLightEngine getLightEngine() {
        return realLevel.getLightEngine();
    }
}
