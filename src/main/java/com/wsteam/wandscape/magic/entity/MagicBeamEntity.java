package com.wsteam.wandscape.magic.entity;

import java.util.Optional;
import java.util.UUID;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * 服务端显示实体：从源点（法阵中心）射向目标的信标光束。
 * 目标与颜色经同步数据下发客户端，由 {@code MagicBeamEntityRenderer} 用原版
 * {@code BeaconRenderer.renderBeaconBeam} 渲染（原版 beam shader，可染色、光影下正常）。
 *
 * <p>不是子弹：整段光束同时可见、不做位移。若指定了施法 NPC 与目标生物，每 tick 动态跟踪——
 * 源点跟随 NPC 持杖手（沿目标方向前移 {@link #STAFF_CENTER_OFFSET}），目标跟随生物当前坐标；
 * 客户端据此渲染，光束随 NPC 转向。无目标时退化为固定源点→固定终点。
 * 光束粗细随时间动画——先平滑变宽、再平滑变窄（{@link #getWidthFactor}）。
 * 纯视觉实体：无 AI/碰撞/存档，短命后自毁；每 tick 对束内敌对生物造成伤害。
 */
public class MagicBeamEntity extends Entity {

    private static final EntityDataAccessor<Optional<BlockPos>> DATA_TARGET =
            SynchedEntityData.defineId(MagicBeamEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Integer> DATA_COLOR =
            SynchedEntityData.defineId(MagicBeamEntity.class, EntityDataSerializers.INT);
    /** 光束总寿命（tick，由施放方按法阵时长传入并同步）。 */
    private static final EntityDataAccessor<Integer> DATA_LIFETIME =
            SynchedEntityData.defineId(MagicBeamEntity.class, EntityDataSerializers.INT);
    /** 施法 NPC 的 UUID（同步，客户端用它匹配法阵跟随）。 */
    private static final EntityDataAccessor<Optional<UUID>> DATA_CASTER =
            SynchedEntityData.defineId(MagicBeamEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** 默认寿命（tick），同步数据到达前的兜底。 */
    public static final int DEFAULT_LIFETIME_TICKS = 220;
    /** 法阵圆心/光束源点距持杖手沿目标方向的偏移（方块）。 */
    public static final double STAFF_CENTER_OFFSET = 1.0;
    /** 光束最大长度（方块）：沿目标方向射线检测第一个方块为止，未命中取此长度。 */
    public static final double BEAM_RANGE = 200.0;
    /** 宽度峰值所在归一化时间（t 归一化 [0,1]）：≈法阵结束点，之后平滑变细到消失。 */
    public static final float PEAK_T = 0.86f;
    /** 峰值时的光束/光晕半径（方块）。 */
    public static final float MAX_BEAM_RADIUS = 0.5f;
    public static final float MAX_GLOW_RADIUS = 0.7f;
    /** 宽度/伤害乘子下限：开头即有少量伤害，不再近乎零伤。 */
    private static final float MIN_WIDTH = 0.1f;
    /**
     * 光束满宽（wf=1）时每 tick 对束内敌对生物造成的伤害 = 平滑曲线峰值。
     * 总伤 ≈ BEAM_DAMAGE × 寿命 × (0.5 + 0.5×MIN_WIDTH) ≈ 60/目标/次施法。
     */
    private static final float BEAM_DAMAGE = 0.5f;

    /** 施法 NPC 实体引用（服务端跟踪用，null=静态光束）。 */
    private WandscapeNpc casterNpc;
    /** 目标生物实体引用（服务端跟踪用，null=静态光束）。 */
    private LivingEntity targetMob;

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

    public Optional<UUID> getCasterUuid() {
        return entityData.get(DATA_CASTER);
    }

    /** 记录施法者 UUID（同步，客户端法阵据此跟随本光束）。 */
    public void setCaster(UUID uuid) {
        entityData.set(DATA_CASTER, Optional.ofNullable(uuid));
    }

    /** 绑定施法 NPC 实体引用（服务端跟踪）。 */
    public void bindCaster(WandscapeNpc npc) {
        this.casterNpc = npc;
    }

    /** 绑定要跟踪的目标生物（服务端跟踪，null=静态光束）。 */
    public void bindTarget(LivingEntity mob) {
        this.targetMob = mob;
    }

    /** 切换跟踪目标（守卫执行器用于主动切换为最近的怪物）。 */
    public void retarget(LivingEntity mob) {
        this.targetMob = mob;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_TARGET, Optional.empty());
        builder.define(DATA_COLOR, 0xFFA8E0FF);
        builder.define(DATA_LIFETIME, DEFAULT_LIFETIME_TICKS);
        builder.define(DATA_CASTER, Optional.empty());
    }

    private boolean loggedSpawn;

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            trackTarget();
            damageTargets();
        }
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

    /**
     * 每 tick 动态跟踪：施法 NPC 面向目标生物，光束源点跟随 NPC 持杖手（沿目标方向前移
     * {@link #STAFF_CENTER_OFFSET}），DATA_TARGET 跟随生物当前坐标。目标死亡/消失后冻结最后位置。
     */
    private void trackTarget() {
        if (casterNpc == null || targetMob == null) return;
        if (casterNpc.isRemoved() || targetMob.isRemoved() || !targetMob.isAlive()) return;

        // 瞄身体中心（AABB 中心），而非脚底
        Vec3 aim = targetMob.getBoundingBox().getCenter();
        casterNpc.faceTarget(BlockPos.containing(aim));
        Vec3 hand = casterNpc.getStaffPosition();
        Vec3 aimDir = aim.subtract(hand).normalize();
        Vec3 source = hand.add(aimDir.scale(STAFF_CENTER_OFFSET));
        setPos(source.x, source.y, source.z);
        // 光束终点 = 沿目标方向第一个方块（穿透生物，只被方块挡住）
        setTarget(aimFirstBlock(source, aimDir));
    }

    /** 沿 dir 射线检测第一个方块；未命中取 BEAM_RANGE 外一点。 */
    private BlockPos aimFirstBlock(Vec3 from, Vec3 dir) {
        HitResult hit = level().clip(new ClipContext(from, from.add(dir.scale(BEAM_RANGE)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
            return bhr.getBlockPos();
        }
        return BlockPos.containing(from.add(dir.scale(BEAM_RANGE)));
    }

    /**
     * 每 tick 对光束圆柱内的敌对生物（Monster）造成伤害，伤害正比于当前宽度因子。
     * 命中测试：实体中心到光束轴线段的距离 ≤ 束径 + 半体型宽。重置无敌帧使其可逐 tick 结算。
     */
    private void damageTargets() {
        BlockPos tgt = getTarget().orElse(null);
        if (tgt == null) return;
        Vec3 start = position();
        Vec3 dir = tgt.getCenter().subtract(start);
        double length = dir.length();
        if (length < 0.1) return;
        Vec3 ndir = dir.normalize();
        float wf = getWidthFactor(0);
        float radius = Math.max(0.05f, MAX_BEAM_RADIUS * wf);
        // SPELL_POWER 倍率由 NpcSpellPowerHandler 在伤害核算入口统一应用，不在此处单独乘
        float damage = BEAM_DAMAGE * wf;

        AABB box = new AABB(start, tgt.getCenter()).inflate(radius + 1.0);
        for (Entity e : level().getEntities((Entity) null, box, e -> e instanceof Enemy)) {
            if (!(e instanceof LivingEntity mob) || mob.isRemoved()) continue;
            Vec3 center = mob.getBoundingBox().getCenter();
            double proj = center.subtract(start).dot(ndir);
            if (proj < -0.5 || proj > length + 0.5) continue;
            Vec3 closest = start.add(ndir.scale(Mth.clamp(proj, 0, length)));
            double eff = radius + mob.getBbWidth() / 2.0;
            if (center.distanceToSqr(closest) <= eff * eff) {
                mob.invulnerableTime = 0;
                // 光束只造成伤害、不造成击退：magic/indirectMagic 不在 no_knockback 标签，
                // hurt() 会按源点方向击退——先记速度再恢复以抵消。伤害/记仇/反击不受影响。
                Vec3 pre = mob.getDeltaMovement();
                if (casterNpc != null && !casterNpc.isRemoved()) {
                    // NPC 施法：伤害记为 NPC 造成 → 怪物 HurtByTargetGoal 反击 NPC，触发自防御受伤仇恨。
                    // 参数顺序是坑：indirectMagic(A, B) 因 DamageSources.source() 的参数名与
                    // DamageSource 构造器(directEntity, causingEntity)错位，getEntity() 返回 B，
                    // 所以 B 必须是 NPC。否则 LivingEntity.hurt 里 setLastHurtByMob 拿到光束实体
                    // （非 LivingEntity）永不记仇，且 NpcSpellPowerHandler/AchievementService 的
                    // source.getEntity() instanceof WandscapeNpc 判定也失效（SPELL_POWER 倍率不结算）。
                    mob.hurt(level().damageSources().indirectMagic(this, casterNpc), damage);
                } else {
                    // 玩家/静态施法：无施法者，保持原行为（不记仇恨）。
                    mob.hurt(level().damageSources().magic(), damage);
                }
                mob.setDeltaMovement(pre);
            }
        }
    }

    /** 归一化寿命 t ∈ [0,1]（含 partialTick 插值），渲染端动画采样用。 */
    public float getAge(float partialTick) {
        return Math.min(1.0f, (tickCount + partialTick) / (float) getLifetimeTicks());
    }

    /**
     * 宽度乘子 [MIN_WIDTH, 1]：半余弦平滑曲线——t ∈ [0, PEAK_T] 平滑变宽至满值、
     * t ∈ [PEAK_T, 1] 平滑变窄回下限。两端与接点斜率均为 0（ease-in-out），
     * 开头不再近乎零伤、峰值不再突兀。恒 ≥ MIN_WIDTH > 0，避免 renderBeaconBeam 除以 0。
     */
    public float getWidthFactor(float partialTick) {
        float t = getAge(partialTick);
        float k = t <= PEAK_T ? t / PEAK_T : (t - PEAK_T) / (1f - PEAK_T);
        float ease = 0.5f * (1f - (float) Math.cos(Math.PI * k)); // 平滑 0→1
        float shape = t <= PEAK_T ? ease : 1f - ease;
        return MIN_WIDTH + (1f - MIN_WIDTH) * shape;
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
