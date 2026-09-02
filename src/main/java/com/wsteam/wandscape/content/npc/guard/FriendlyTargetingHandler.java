package com.wsteam.wandscape.content.npc.guard;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

/**
 * 阵营互不侵犯过滤器（友军名单双向化）。
 *
 * <p>NPC 侧（{@link WandscapeNpc#isFriendlyForce}）已保证 NPC 不攻击玩家/宠物/随从/游客；但多模组
 * 的 AI 是**反向**的：玩家训养的宠物会记仇/索敌 NPC、玩家的铁魔法随从会索敌 NPC、NPC 的铁魔法随从
 * 会索敌玩家。此 handler 在 {@link Mob#setTarget} 的目标切换事件上拦截：当 {@code entity} 与
 * {@code newTarget} **互为友军**（{@link WandscapeNpc#isMutuallyFriendly}）时取消目标设置，让双方
 * 持有「打不动友军」约束，而无需改动任何 Vanilla/铁魔法 AI。
 *
 * <p>只改「选中目标」，不改伤害——友军的伤害取消仍走 {@code NpcSpellPowerHandler}（NPC 来源）与
 * 双方 AI 各自的目标约束，此处是反向侧的统一兜底。仅当至少一方为真实殖民地成员时生效，敌对生物
 * （EvilMage 等 {@code isColonyNpc()==false}）与普通怪物刻意为敌，不拦截。
 */
public final class FriendlyTargetingHandler {
    private FriendlyTargetingHandler() {}

    @SubscribeEvent
    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        if (event.getEntity().level().isClientSide) return;
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (target == null) return;
        if (!WandscapeNpc.isMutuallyFriendly(event.getEntity(), target)) return;
        // 目标互相友军 → 取消选中，使 Vanilla/铁魔法 AI 持「打不动友军」约束。高频（AI 每轮重选目标会
        // 反复触发），刻意不打印——这是预期的常态抑制，非错误。
        event.setCanceled(true);
    }
}
