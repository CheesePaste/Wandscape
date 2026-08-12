package com.wsteam.wandscape.guard;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 受伤仇恨：NPC 被非玩家攻击者打伤时记录仇恨目标（记在 {@link WandscapeNpc} 上），
 * 供 {@code SelfDefenseExecutor} 的下一轮目标解析优先反击。
 *
 * <p>只对 {@code Enemy} 记仇：光束伤害（MagicBeamEntity）只伤敌对生物，对非 Enemy
 * 记仇会导致反击打不死的空转。玩家 / 其它 NPC 不记仇（友伤排除）。
 *
 * <p>注意：NeoForge 1.21.1 中该事件由 {@code LivingHurtEvent} 改名而来
 * （构造改传 {@code DamageContainer}），订阅 {@code LivingIncomingDamageEvent}。
 */
public final class SelfDefenseHandler {
    private static final String TAG = "SelfDefense";

    /** 传送引导期间伤害乘子（0.25 = 减伤 75%）：定身硬吃时靠减伤存活，而非原免疫。 */
    private static final float TELEPORT_DAMAGE_MULTIPLIER = 0.25f;

    private SelfDefenseHandler() {}

    @SubscribeEvent
    public static void onLivingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof WandscapeNpc npc)) return;
        if (npc.level().isClientSide) return;

        // 任何伤害都重置脱战回血计时（回血仅在脱战后生效）
        npc.markRecentlyDamaged();

        // 传送引导期间：减伤 75%（替代原免疫；所有伤害类型适用，含环境伤害）
        if (npc.isTeleportChanneling(npc.level().getGameTime())) {
            event.setAmount(event.getAmount() * TELEPORT_DAMAGE_MULTIPLIER);
        }

        LivingEntity attacker = attackerFrom(event.getSource());
        // 环境伤害（无活体攻击者：窒息/岩浆/火烧/溺水等）→ 传送逃生
        if (attacker == null) {
            handleEnvironmentalDamage(event, npc);
            return;
        }
        if (attacker instanceof Player || attacker instanceof WandscapeNpc) return;
        if (!(attacker instanceof Enemy)) return;

        long expiry = npc.level().getGameTime() + Config.GUARD_HATE_DURATION_TICKS.get();
        npc.setHatedAttacker(attacker.getUUID(), expiry);
        Log.info(TAG, "NPC {} hurt by {} — hate set until +{}t",
                npc.getUUID().toString().substring(0, 8),
                attacker.getUUID().toString().substring(0, 8),
                Config.GUARD_HATE_DURATION_TICKS.get());
    }

    /** 伤害源的真实攻击者：近战=攻击者本体、弹射物=发射者（source.getEntity()）；无则 null。 */
    @Nullable
    private static LivingEntity attackerFrom(DamageSource source) {
        return source.getEntity() instanceof LivingEntity le ? le : null;
    }

    /**
     * 环境伤害（窒息/岩浆/火烧/溺水等非生物伤害）处理：
     * 非引导中且非殖民地 NPC 才发起逃生传送；引导期间减伤已生效，不重复逃生、不再免疫取消。
     * 触发本次伤害仍结算一次（保证脱战回血计时正确）。
     */
    private static void handleEnvironmentalDamage(LivingIncomingDamageEvent event, WandscapeNpc npc) {
        if (!npc.isColonyNpc()) return;
        if (!(npc.level() instanceof ServerLevel level)) return;

        // 传送引导期间：减伤已生效，不发起新的逃生传送
        if (npc.isTeleportChanneling(level.getGameTime())) return;
        NpcEscapeTeleport.attempt(level, npc);
    }
}
