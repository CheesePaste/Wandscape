package com.wsteam.wandscape.magic.entity;

import java.util.Optional;

import com.wsteam.wandscape.Wandscape;

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
 * 纯视觉实体：无 AI/碰撞/存档，短命后自毁。伤害由后续战斗 op（守卫阶段 1）负责。
 */
public class MagicBeamEntity extends Entity {

    private static final EntityDataAccessor<Optional<BlockPos>> DATA_TARGET =
            SynchedEntityData.defineId(MagicBeamEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Integer> DATA_COLOR =
            SynchedEntityData.defineId(MagicBeamEntity.class, EntityDataSerializers.INT);

    private static final int DEFAULT_LIFETIME = 60;

    private int life;

    public MagicBeamEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    public MagicBeamEntity(Level level, Vec3 source, BlockPos target, int color) {
        this(Wandscape.MAGIC_BEAM.get(), level);
        setPos(source.x, source.y, source.z);
        setTarget(target);
        setBeamColor(color);
        this.life = DEFAULT_LIFETIME;
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

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_TARGET, Optional.empty());
        builder.define(DATA_COLOR, 0xFF3F8FFF);
    }

    @Override
    public void tick() {
        super.tick();
        if (--life <= 0) discard();
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
