package com.wsteam.wandscape.magic.entity;

import java.util.Optional;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 服务端显示实体：从源点（法阵中心）射向目标的信标光束。
 * 目标与颜色经同步数据下发客户端，由 {@code MagicBeamEntityRenderer} 用原版
 * {@code BeaconRenderer.renderBeaconBeam} 渲染（原版 beam shader，可染色、光影下正常）。
 *
 * <p>不是子弹：起点/终点在生成时一次性定死，整段光束同时可见，不做位移。
 * 光束粗细随时间动画——先慢慢变宽、再快速变窄（{@link #getWidthFactor}）。
 * 纯视觉实体：无 AI/碰撞/存档，短命后自毁。伤害由后续战斗 op（守卫阶段 1）负责。
 */
public class MagicBeamEntity extends Entity {

    private static final EntityDataAccessor<Optional<BlockPos>> DATA_TARGET =
            SynchedEntityData.defineId(MagicBeamEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Integer> DATA_COLOR =
            SynchedEntityData.defineId(MagicBeamEntity.class, EntityDataSerializers.INT);
    /** 光束总寿命（tick，由施放方按法阵时长传入并同步）。 */
    private static final EntityDataAccessor<Integer> DATA_LIFETIME =
            SynchedEntityData.defineId(MagicBeamEntity.class, EntityDataSerializers.INT);

    /** 默认寿命（tick），同步数据到达前的兜底。 */
    public static final int DEFAULT_LIFETIME_TICKS = 220;
    /** 宽度峰值所在归一化时间（t 归一化 [0,1]）：≈法阵结束点，之后快速变细到消失。 */
    public static final float PEAK_T = 0.86f;
    /** 峰值时的光束/光晕半径（方块）。 */
    public static final float MAX_BEAM_RADIUS = 0.5f;
    public static final float MAX_GLOW_RADIUS = 0.7f;
    /** 宽度乘子下限：光束从「特别细」开始，随法阵时长逐渐变宽。 */
    private static final float MIN_WIDTH = 0.02f;
    /** 宽窄动画缓动指数：>1 使「变宽」更慢、「变窄」更快。 */
    private static final float WIDTH_POWER = 1.4f;

    public MagicBeamEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    public MagicBeamEntity(Level level, Vec3 source, BlockPos target, int color, int lifeTicks) {
        this(Wandscape.MAGIC_BEAM.get(), level);
        setPos(source.x, source.y, source.z);
        setTarget(target);
        setBeamColor(color);
        setLifetime(lifeTicks);
    }

    public Optional<BlockPos> getTarget() {
        return entityData.get(DATA_TARGET);
    }

    public void setTarget(BlockPos pos) {
        entityData.set(DATA_TARGET, Optional.ofNullable(pos));
    }

    public int getBeamColor() {
        return entityData.get(DATA_COLOR);
    }

    public void setBeamColor(int color) {
        entityData.set(DATA_COLOR, color);
    }

    public int getLifetimeTicks() {
        return entityData.get(DATA_LIFETIME);
    }

    public void setLifetime(int ticks) {
        entityData.set(DATA_LIFETIME, Math.max(1, ticks));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_TARGET, Optional.empty());
        builder.define(DATA_COLOR, 0xFFA8E0FF);
        builder.define(DATA_LIFETIME, DEFAULT_LIFETIME_TICKS);
    }

    private boolean loggedSpawn;

    @Override
    public void tick() {
        super.tick();
        if (!loggedSpawn && tickCount >= 5) {
            loggedSpawn = true;
            Log.info("MagicBeam", "beam tick id={} client={} pos={} targetPresent={} target={} life={}",
                    getId(), level().isClientSide, position(), getTarget().isPresent(), getTarget().orElse(null),
                    getLifetimeTicks());
        }
        if (tickCount == getLifetimeTicks() - 1) {
            Log.info("MagicBeam", "beam expire id={} client={} tickCount={}",
                    getId(), level().isClientSide, tickCount);
        }
        // 两端都用 tickCount（客户端实体也自增），避免依赖未同步的字段导致客户端立即自毁
        if (tickCount >= getLifetimeTicks()) discard();
    }

    /** 归一化寿命 t ∈ [0,1]（含 partialTick 插值），渲染端动画采样用。 */
    public float getAge(float partialTick) {
        return Math.min(1.0f, (tickCount + partialTick) / (float) getLifetimeTicks());
    }

    /**
     * 宽度乘子 [MIN_WIDTH, 1]：t ∈ [0, PEAK_T] 慢慢变宽（k^WIDTH_POWER），
     * t ∈ [PEAK_T, 1] 快速变窄（(1-k)^WIDTH_POWER）。
     * 恒大于 0，避免 renderBeaconBeam 内部除以 beamRadius 时为 0。
     */
    public float getWidthFactor(float partialTick) {
        float t = getAge(partialTick);
        float factor;
        if (t <= PEAK_T) {
            float k = Math.max(0f, t / PEAK_T);
            factor = (float) Math.pow(k, WIDTH_POWER);
        } else {
            float k = Math.min(1f, (t - PEAK_T) / (1f - PEAK_T));
            factor = (float) Math.pow(1f - k, WIDTH_POWER);
        }
        return Math.max(MIN_WIDTH, factor);
    }

    /** 纯显示实体不入存档，避免世界重载后残留光束。 */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    /** 渲染剔除包围盒覆盖源点到目标整段，避免相机看向光束末端时被视锥剔除。 */
    @Override
    public AABB getBoundingBoxForCulling() {
        BlockPos tgt = getTarget().orElse(null);
        if (tgt != null) {
            return new AABB(position(), tgt.getCenter()).inflate(1.0);
        }
        return super.getBoundingBoxForCulling();
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double dist) {
        return dist < 256.0 * 256.0;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
