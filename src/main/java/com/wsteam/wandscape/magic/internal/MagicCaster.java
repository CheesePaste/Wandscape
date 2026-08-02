package com.wsteam.wandscape.magic.internal;

import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.network.MagicCircleCastPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
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

    /** 默认攻击法阵 spec id。 */
    public static final String DEFAULT_CIRCLE = "arcane_hexagram";
    /** 默认光束颜色（青蓝）。 */
    public static final int DEFAULT_COLOR = 0xFF3F8FFF;

    private static final double CAST_DISTANCE = 1.5;
    /** 法杖中心距手部沿法杖方向的偏移（方块）：圆心/光束起点落在法杖中间而非手部。 */
    private static final double STAFF_CENTER_OFFSET = 1.0;
    private static final double AIM_RANGE = 64.0;

    private MagicCaster() {}

    /**
     * 玩家施放（调试命令）。返回 false 表示未找到 spec 或该施法者已有未发射施法。
     */
    public static boolean cast(ServerLevel level, ServerPlayer player, String circleId, @Nullable String colorHex) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        if (spec == null) return false;

        Vec3 look = player.getLookAngle();
        Vec3 source = player.getEyePosition().add(look.scale(CAST_DISTANCE));
        BlockPos target = aimTarget(player);
        int color = resolveColor(player.getMainHandItem(), colorHex);

        PacketDistributor.sendToPlayersTrackingChunk(level,
                new ChunkPos(BlockPos.containing(source)),
                new MagicCircleCastPacket(UUID.randomUUID(), source, look, circleId));

        return MagicCastManager.schedule(level, player.getUUID(), source, target, color, spec.durationTicks);
    }

    /**
     * NPC 施放（shift+右键触发）：法阵圆心落在法杖中心（持杖手沿法杖方向前移一段），
     * 法阵平面垂直法杖，光束从法杖中心沿法杖方向射向远处。不改变 NPC 朝向。
     */
    public static boolean castNpc(ServerLevel level, WandscapeNpc npc, String circleId, @Nullable Integer color) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        if (spec == null) return false;

        Vec3 staffDir = npc.getStaffDirection();
        Vec3 source = npc.getStaffPosition().add(staffDir.scale(STAFF_CENTER_OFFSET));
        BlockPos target = aimTarget(level, source, staffDir);
        int c = color != null ? color : resolveColor(npc.getMainHandItem(), null);

        PacketDistributor.sendToPlayersTrackingEntity(npc,
                new MagicCircleCastPacket(UUID.randomUUID(), source, staffDir, circleId));

        return MagicCastManager.schedule(level, npc.getUUID(), source, target, c, spec.durationTicks);
    }

    /** 玩家准星目标：命中方块取其坐标，未命中取视线 64 格外一点。 */
    private static BlockPos aimTarget(Player player) {
        HitResult hit = player.pick(AIM_RANGE, 1.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
            return bhr.getBlockPos();
        }
        return BlockPos.containing(player.getEyePosition().add(player.getLookAngle().scale(AIM_RANGE)));
    }

    /** NPC 目标：从源点沿水平朝向射线检测，命中方块取其坐标，未命中取 64 格外一点。 */
    private static BlockPos aimTarget(ServerLevel level, Vec3 from, Vec3 dir) {
        HitResult hit = level.clip(new ClipContext(from, from.add(dir.scale(AIM_RANGE)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
            return bhr.getBlockPos();
        }
        return BlockPos.containing(from.add(dir.scale(AIM_RANGE)));
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
