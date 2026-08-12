package com.wsteam.wandscape.engine;

import java.lang.reflect.Field;

import javax.annotation.Nullable;

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
 * 让原版敌对生物把 {@link PlayerLike} 实体（NPC）当**玩家**索敌、把
 * {@link VillagerLike} 实体（游客）当**村民**索敌。
 *
 * <p>不枚举「哪些生物追玩家/追村民」——凡是加入世界时目标选择器里已存在对
 * {@link Player} 的 {@link NearestAttackableTargetGoal} 的生物（骷髅 / 史莱姆 /
 * 苦力怕 / 僵尸 / 灾厄等，含其它 mod 的生物），就追加一个同优先级、目标为
 * {@link PlayerLike} 的等价 goal；对 {@link AbstractVillager} 的追加目标为
 * {@link VillagerLike} 的等价 goal。这样天然覆盖玩家级与村民级索敌，且无需随
 * 原版生物增减而维护清单。
 *
 * <p>自身是 {@link PlayerLike} 的敌对生物（如敌对测试法师）不追加玩家级索敌——
 * 它伤不了殖民地 NPC（光束伤害钩子排除），避免它死盯打不死的目标。
 *
 * <p>同一实体可能多次加入世界（维度传送 / chunk 重载），若目标选择器已存在宽类
 * （{@link PathfinderMob}）索敌 goal 则跳过，避免每次 join 叠加重复 goal。
 */
public final class HostileTargetingHandler {

    private static final String TAG = "HostileTargetingHandler";

    /** NearestAttackableTargetGoal#targetType（protected 字段，历版稳定）——反射读取索敌目标类。 */
    @Nullable
    private static final Field TARGET_TYPE = findField(NearestAttackableTargetGoal.class, "targetType");

    private HostileTargetingHandler() {}

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (TARGET_TYPE == null) return;

        int villagerPriority = Integer.MAX_VALUE;
        int playerPriority = Integer.MAX_VALUE;
        for (WrappedGoal wrapped : mob.targetSelector.getAvailableGoals()) {
            Goal goal = wrapped.getGoal();
            if (!(goal instanceof NearestAttackableTargetGoal<?> targetGoal)) continue;
            Class<?> type = targetTypeOf(targetGoal);
            if (type == null) continue;
            if (AbstractVillager.class.isAssignableFrom(type)) {
                villagerPriority = Math.min(villagerPriority, wrapped.getPriority());
            }
            if (Player.class.isAssignableFrom(type)) {
                playerPriority = Math.min(playerPriority, wrapped.getPriority());
            }
        }
        if (villagerPriority == Integer.MAX_VALUE && playerPriority == Integer.MAX_VALUE) return;
        // 同一实体可能多次 join（维度传送/chunk 重载）——宽类索敌 goal 已加过则跳过，避免叠加
        if (hasBroadTargetGoal(mob)) return;

        if (villagerPriority != Integer.MAX_VALUE) {
            mob.targetSelector.addGoal(villagerPriority, villagerLikeGoal(mob));
        }
        // 自身是玩家级索敌对象（敌对测试法师等）不追加，避免死盯打不死的目标
        if (playerPriority != Integer.MAX_VALUE && !(mob instanceof PlayerLike)) {
            mob.targetSelector.addGoal(playerPriority, playerLikeGoal(mob));
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
                    && targetTypeOf(goal) == PathfinderMob.class) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Class<?> targetTypeOf(NearestAttackableTargetGoal<?> goal) {
        if (TARGET_TYPE == null) return null;
        try {
            return (Class<?>) TARGET_TYPE.get(goal);
        } catch (IllegalAccessException e) {
            Log.warn(TAG, "反射读取索敌目标类失败: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    private static Field findField(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            Log.warn(TAG, "找不到字段 {}#{} — 跳过原版生物玩家/村民级索敌增强", owner.getSimpleName(), name);
            return null;
        }
    }
}
