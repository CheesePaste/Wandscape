package com.wsteam.wandscape.engine;

import com.wsteam.wandscape.shared.entity.PlayerLike;
import com.wsteam.wandscape.shared.entity.VillagerLike;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 让原版**敌对生物**（{@link Enemy}）把 {@link PlayerLike} 实体（NPC）当**玩家**索敌、把
 * {@link VillagerLike} 实体（游客）当**村民**索敌。
 *
 * <p>只增强 {@link Enemy}：北极熊/铁傀儡/狼等中立·防御生物同样带 Player 索敌 goal，但那是
 * 条件性的（愤怒/声望），追加无条件的 PlayerLike 索敌会让它们无端攻击 NPC（仇恨吸引）。
 *
 * <p>不枚举「哪些生物追玩家/追村民」——凡是加入世界时目标选择器里已存在对
 * {@link Player} 的 {@link NearestAttackableTargetGoal} 的生物（骷髅 / 史莱姆 /
 * 苦力怕 / 僵尸 / 灾厄等，含其它 mod 的生物），就追加一个目标为 {@link PlayerLike}
 * 的等价 goal；对 {@link AbstractVillager} 的追加目标为 {@link VillagerLike} 的
 * 等价 goal。这样天然覆盖玩家级与村民级索敌，且无需随原版生物增减而维护清单。
 *
 * <p>追加 goal 的优先级**严格低于**该生物所有原版索敌 goal：目标选择器每个 Flag
 * 同时只运行一个 goal，同优先级会互相锁死（先运行的抢到 TARGET 后对方永远抢不回，
 * 导致怪物死盯一个目标、无视其它）。低优先级保证原版玩家索敌始终优先——玩家在场就
 * 打玩家（不回归原版），没有玩家竞争时才把 NPC/游客当目标。
 *
 * <p>自身是 {@link PlayerLike} 的敌对生物（如敌对测试法师）不追加玩家级索敌——
 * 它伤不了小镇 NPC（光束伤害钩子排除），避免它死盯打不死的目标。
 *
 * <p>同一实体可能多次加入世界（维度传送 / chunk 重载），若目标选择器已存在宽类
 * （{@link PathfinderMob}）索敌 goal 则跳过，避免每次 join 叠加重复 goal。
 */
public final class HostileTargetingHandler {

    private static final String TAG = "HostileTargetingHandler";

    /**
     * 是否可直读 {@link NearestAttackableTargetGoal#targetType}。该字段经 NeoForge
     * AccessTransformer（{@code META-INF/accesstransformer.cfg}）从 protected 提为 public，
     * 不使用反射。若 AT 因版本变动未生效（字段改名等），置 false 禁用增强而非崩溃。
     */
    private static boolean atActive = true;

    private HostileTargetingHandler() {}

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!atActive) return; // AT 未生效：跳过增强（锦上添花，缺了不伤功能）
        // 只增强敌对生物（Enemy）：北极熊/铁傀儡/狼等中立·防御生物虽带 Player 索敌 goal，
        // 但那是条件性的（愤怒/声望），追加无条件 PlayerLike 索敌会让它们无端攻击 NPC（仇恨吸引）。
        if (!(mob instanceof Enemy)) return;

        // 记录该生物是否追玩家/追村民，以及所有原版索敌 goal 的最低优先级（最大优先级数字）。
        boolean huntsVillagers = false;
        boolean huntsPlayers = false;
        int lowestNativePriority = 0;
        for (WrappedGoal wrapped : mob.targetSelector.getAvailableGoals()) {
            Goal goal = wrapped.getGoal();
            if (!(goal instanceof NearestAttackableTargetGoal<?> targetGoal)) continue;
            Class<?> type;
            try {
                type = targetGoal.targetType; // AT 提权后直读；异常仅在 AT 未生效时出现
            } catch (Throwable t) {
                Log.error(TAG, "Reading targetType failed (AT not applied?) — disabling monster "
                        + "targeting enhancement: {}", t.getMessage());
                atActive = false;
                return;
            }
            if (AbstractVillager.class.isAssignableFrom(type)) {
                huntsVillagers = true;
                lowestNativePriority = Math.max(lowestNativePriority, wrapped.getPriority());
            }
            if (Player.class.isAssignableFrom(type)) {
                huntsPlayers = true;
                lowestNativePriority = Math.max(lowestNativePriority, wrapped.getPriority());
            }
        }
        if (!huntsVillagers && !huntsPlayers) return;
        // 同一实体可能多次 join（维度传送/chunk 重载）——宽类索敌 goal 已加过则跳过，避免叠加
        if (hasBroadTargetGoal(mob)) return;

        // 追加 goal 必须严格低于所有原版索敌优先级：目标选择器每个 Flag 同时只跑一个 goal，
        // 同优先级会互相锁死（先运行的抢到 TARGET 后对方永远抢不回）。低优先级保证原版玩家索敌
        // 始终优先——玩家在场就打玩家（不回归原版），没有玩家竞争时才把 NPC/游客当目标。
        if (huntsPlayers && !(mob instanceof PlayerLike)) {
            mob.targetSelector.addGoal(lowestNativePriority + 1, playerLikeGoal(mob));
        }
        if (huntsVillagers) {
            mob.targetSelector.addGoal(lowestNativePriority + 2, villagerLikeGoal(mob));
        }
    }

    /**
     * 目标类型必须是具体类而非接口：实体区块存储 {@code ClassInstanceMultiMap.find()} 只支持
     * {@code Entity} 子类查找，接口会抛 IllegalArgumentException。故用 NPC/游客的公共父类
     * {@link PathfinderMob}，再用谓词收窄到 {@link PlayerLike} / {@link VillagerLike}，
     * 避免 engine 跨包引用实体类。
     */
    private static Goal playerLikeGoal(Mob mob) {
        return new NearestAttackableTargetGoal<>(mob, PathfinderMob.class, false,
                e -> e instanceof PlayerLike && !(e instanceof Enemy));
    }

    private static Goal villagerLikeGoal(Mob mob) {
        return new NearestAttackableTargetGoal<>(mob, PathfinderMob.class, false,
                e -> e instanceof VillagerLike && !(e instanceof Enemy));
    }

    /** 已存在本 handler 追加的宽类索敌 goal（唯一标记：目标类恰为 PathfinderMob.class，原版生物不会直接索敌该宽类）。 */
    private static boolean hasBroadTargetGoal(Mob mob) {
        for (WrappedGoal wrapped : mob.targetSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof NearestAttackableTargetGoal<?> goal
                    && goal.targetType == PathfinderMob.class) {
                return true;
            }
        }
        return false;
    }
}