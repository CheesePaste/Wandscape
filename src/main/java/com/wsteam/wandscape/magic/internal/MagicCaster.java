package com.wsteam.wandscape.magic.internal;

import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.entity.MagicBeamEntity;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.MagicCircleCastPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 施放一次魔法阵攻击：向追踪的玩家发 {@link MagicCircleCastPacket}（客户端渲染法阵，
 * 垂直于施法朝向），并在法阵动画结束后由 {@link MagicCastManager} 生成信标光束射向目标。
 * 调试命令（玩家）、shift+右键 NPC 共用此入口。
 */
public final class MagicCaster {

    private static final String TAG = "MagicCast";

    /** 默认攻击法阵 spec id。 */
    public static final String DEFAULT_CIRCLE = "arcane_hexagram";
    /** 默认光束颜色（浅蓝）。 */
    public static final int DEFAULT_COLOR = 0xFFA8E0FF;

    /** 光束魔法 id（每魔法独立 CD 的 key）。 */
    public static final String BEAM_MAGIC_ID = "beam";
    /** 光束施法最小间隔基础（tick），除以 SPELL_SPEED 得实际 CD；施法时间（魔法阵+光束）不参与。 */
    public static final int BEAM_BASE_CD = 40;
    /** 守卫光束固定魔力消耗。 */
    public static final int BEAM_MANA_COST = 50;

    private static final double CAST_DISTANCE = 1.5;
    /** 光束在法阵出现后多少 tick 开始生成（法阵动画期间从细变宽）。 */
    public static final int BEAM_SPAWN_DELAY = 20;
    /** 法阵结束后光束额外延续的 tick（快速变细到消失）。 */
    public static final int BEAM_TAIL = 20;

    // ── beam 参数：数据驱动（magic_spells/beam.json），缺失回退常量 ──

    /** beam 魔法定义（可能为 null，取用前判空）。 */
    @Nullable
    public static MagicDef beamSpec() {
        return SpellbookLoader.getSpec(BEAM_MAGIC_ID);
    }

    /** 光束基础冷却（tick）。 */
    public static int beamBaseCooldown() {
        MagicDef spec = beamSpec();
        return spec != null ? spec.baseCooldown() : BEAM_BASE_CD;
    }

    /** 光束固定魔力消耗。 */
    public static int beamManaCost() {
        MagicDef spec = beamSpec();
        return spec != null ? spec.manaCost() : BEAM_MANA_COST;
    }

    /** 光束法阵 spec id（效果参数）。 */
    public static String beamCircleId() {
        MagicDef spec = beamSpec();
        return spec != null && spec.effectCircleId() != null ? spec.effectCircleId() : DEFAULT_CIRCLE;
    }

    /** 光束颜色（效果参数，ARGB）。 */
    public static int beamColor() {
        MagicDef spec = beamSpec();
        return spec != null && spec.effectColor() != null ? spec.effectColor() : DEFAULT_COLOR;
    }

    private MagicCaster() {}

    /**
     * 玩家施放（调试命令）。返回 false 表示未找到 spec 或该施法者已有未发射施法。
     */
    public static boolean cast(ServerLevel level, ServerPlayer player, String circleId, @Nullable String colorHex) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        if (spec == null) return false;

        Vec3 look = player.getLookAngle();
        Vec3 source = player.getEyePosition().add(look.scale(CAST_DISTANCE));
        BlockPos target = aimFirstBlock(level, source, look);
        int color = resolveColor(player.getMainHandItem(), colorHex);

        PacketDistributor.sendToPlayersTrackingChunk(level,
                new ChunkPos(BlockPos.containing(source)),
                new MagicCircleCastPacket(UUID.randomUUID(), source, look, circleId));

        boolean ok = MagicCastManager.schedule(level, player.getUUID(), source, target, color,
                BEAM_SPAWN_DELAY, spec.durationTicks + BEAM_TAIL, null, null);
        if (ok) {
            SoundService.playAt(level, player.getX(), player.getY(), player.getZ(),
                    WandscapeSounds.MAGIC_CAST, SoundSource.PLAYERS, 0.5f, 1.0f);
        }
        return ok;
    }

    /**
     * NPC 施放指向**指定目标**（守卫执行器用）：面向目标、法阵圆心落在法杖中段、光束射向目标身体中心。
     * 法阵/光束由 MagicBeamEntity 动态跟踪目标。目标必须存活；门控（锁/CD/蓝）不满足则拒绝。
     */
    public static boolean castNpcAt(ServerLevel level, WandscapeNpc npc, LivingEntity target,
                                    String circleId, @Nullable Integer color) {
        return castNpcBeam(level, npc, target, circleId, color);
    }

    /**
     * 光束施放公共路径（守卫用）：先过施法门控（互斥锁 + 光束独立 CD + 固定魔力），
     * 成功后面向目标、法阵圆心落在法杖中段、光束射向目标身体中心。
     * 魔力消耗 = beam MagicDef 数据（magic_spells/beam.json）。
     */
    private static boolean castNpcBeam(ServerLevel level, WandscapeNpc npc, LivingEntity target,
                                       String circleId, @Nullable Integer color) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        if (spec == null || target == null || target.isRemoved() || !target.isAlive()) return false;

        int lockDuration = BEAM_SPAWN_DELAY + spec.durationTicks + BEAM_TAIL;
        if (!npc.tryCastSpell(BEAM_MAGIC_ID, beamBaseCooldown(), beamManaCost(), lockDuration)) return false;

        UUID effectId = npc.getUUID();
        Vec3 hand = npc.getStaffPosition();
        // 瞄身体中心（AABB 中心），而非脚底
        Vec3 aim = target.getBoundingBox().getCenter();
        Vec3 axis = aim.subtract(hand).normalize();
        npc.faceTarget(BlockPos.containing(aim));
        Vec3 source = hand.add(axis.scale(MagicBeamEntity.STAFF_CENTER_OFFSET));
        BlockPos beamTarget = aimFirstBlock(level, source, axis);
        int c = color != null ? color : resolveColor(npc.getMainHandItem(), null);

        PacketDistributor.sendToPlayersTrackingEntity(npc,
                new MagicCircleCastPacket(effectId, source, axis, circleId));

        boolean ok = MagicCastManager.schedule(level, npc.getUUID(), source, beamTarget, c,
                BEAM_SPAWN_DELAY, spec.durationTicks + BEAM_TAIL, npc, target);
        Log.info(TAG, "castNpcBeam id={} circle={} target={} hand={} axis={} source={} scheduled={}",
                npc.getUUID().toString().substring(0, 8), circleId,
                target.getUUID().toString().substring(0, 8),
                fmt(hand), fmt(axis), fmt(source), ok);
        return ok;
    }

    /** 沿 dir 射线检测第一个方块（光束终点，穿透生物只被方块挡住）；未命中取 BEAM_RANGE 外一点。 */
    private static BlockPos aimFirstBlock(ServerLevel level, Vec3 from, Vec3 dir) {
        HitResult hit = level.clip(new ClipContext(from, from.add(dir.scale(MagicBeamEntity.BEAM_RANGE)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
            return bhr.getBlockPos();
        }
        return BlockPos.containing(from.add(dir.scale(MagicBeamEntity.BEAM_RANGE)));
    }

    /** 调试日志：Vec3 四舍五入两位。 */
    private static String fmt(Vec3 v) {
        return String.format("(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
    }

    /** 光束颜色：参数 > 手持法杖 wand_color > 默认青蓝。 */
    private static int resolveColor(ItemStack held, @Nullable String colorHex) {
        if (colorHex != null) {
            String h = colorHex.startsWith("#") ? colorHex : "#" + colorHex;
            if (h.length() == 7 && h.charAt(0) == '#') {
                try {
                    return 0xFF000000 | Integer.parseInt(h.substring(1), 16);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        CustomData data = held.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.contains("wand_color")) {
            String hex = data.copyTag().getString("wand_color");
            if (hex.length() == 7 && hex.charAt(0) == '#') {
                try {
                    return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return DEFAULT_COLOR;
    }
}
