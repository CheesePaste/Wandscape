package com.wsteam.wandscape.content.npc.guard;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 跟随战斗目标：跟随者玩家攻击生物时，把该生物记为跟随 NPC 的战斗目标（原版狼
 * {@code OwnerHurtTargetGoal} 行为）。由 {@code SelfDefenseExecutor} 目标解析优先消费，
 * 复用整套战斗引擎（光束/LOS/施法/走位）追击，目标死亡后回落跟随。
 *
 * <p>边界：只对「跟随模式且跟随该玩家」的殖民地 NPC 生效（遍历 {@code EntityComponentBridge}
 * 的 ECS 殖民地 NPC）；友军名单（玩家 + 同殖民地 NPC/铁魔法随从/游客，见
 * {@link WandscapeNpc#isFriendlyForce}）内的目标绝不标记——玩家打自己人，跟随 NPC 不参战；
 * 和平模式 / 休息中的 NPC 不标记。每次玩家攻击刷新过期时间（{@code guard.followAttackDurationTicks}）。
 */
public final class FollowAttackHandler {
    private static final String TAG = "FollowAttack";

    private FollowAttackHandler() {}

    @SubscribeEvent
    public static void onLivingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getSource().getEntity() instanceof Player player)) return;
        if (player.isSpectator()) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (victim.isRemoved() || !victim.isAlive()) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        // 遍历殖民地 NPC，找「跟随该玩家」的；有匹配则标记战斗目标（友军/和平/休息已过滤）。
        // 每次玩家攻击刷新过期，目标死亡/过期/出范围后由 resolveTarget 自然回落。
        for (WandscapeNpc npc : EntityComponentBridge.INSTANCE.allNpcs().values()) {
            if (npc == null || npc.isRemoved() || npc.level().isClientSide) continue;
            if (!npc.isFollowMode()) continue;
            if (!player.getUUID().equals(npc.getFollowerUuid())) continue;
            if (npc.isPeaceMode() || npc.isResting()) continue;
            if (!npc.isValidFollowAttackTarget(victim)) continue;
            npc.markFollowAttackTarget(victim);
            Log.info(TAG, "NPC {} follow-attacks target {} (by {})",
                    npc.getUUID().toString().substring(0, 8),
                    victim.getUUID().toString().substring(0, 8),
                    player.getName().getString());
        }
    }
}
