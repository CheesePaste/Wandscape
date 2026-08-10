package com.wsteam.wandscape.engine;

import java.lang.reflect.Field;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.entity.VillagerLike;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 让原版敌对生物把 {@link VillagerLike} 实体（NPC / 游客）当作村民一样索敌。
 *
 * <p>不枚举「哪些生物追村民」——凡是加入世界时目标选择器里已存在对
 * {@link AbstractVillager} 的 {@link NearestAttackableTargetGoal} 的生物（含其它
 * mod 的生物），就追加一个同优先级、目标为 {@link VillagerLike} 的等价 goal。
 * 这样自动覆盖僵尸族 / 灾厄村民 / 劫掠兽，同时天然排除不追村民的中立生物
 * （如僵尸猪灵），也无需随原版生物增减而维护清单。
 *
 * <p>自身是敌对生物（{@link Enemy}）的 {@link VillagerLike}（如敌对测试法师）
 * 不追加——僵尸/灾厄不会去追杀同为敌对阵营的它。
 *
 * <p>同一实体可能多次加入世界（维度传送 / chunk 重载），若目标选择器已存在本
 * handler 追加的等价 goal 则跳过，避免每次 join 叠加一个重复 goal。
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

        for (WrappedGoal wrapped : mob.targetSelector.getAvailableGoals()) {
            Goal goal = wrapped.getGoal();
            if (!(goal instanceof NearestAttackableTargetGoal<?> targetGoal)) continue;
            if (!targetsVillagers(targetGoal)) continue;
            // 同一实体可能多次 join（维度传送/chunk 重载）——重复 add 会叠加相同 goal
            if (hasVillagerLikeGoal(mob)) return;
            // 该生物原本就猎杀村民 → 同优先级、同视野规则地索敌 NPC/游客
            mob.targetSelector.addGoal(wrapped.getPriority(), villagerLikeGoal(mob));
            return;
        }
    }

    /**
     * 目标类型必须是具体类而非接口：实体区块存储 {@code ClassInstanceMultiMap.find()} 只支持
     * {@code Entity} 子类查找，接口会抛 IllegalArgumentException。故用 NPC/游客的公共父类
     * {@link PathfinderMob}，再用谓词收窄到 {@link VillagerLike}，避免 engine 跨包引用实体类。
     */
    private static Goal villagerLikeGoal(Mob mob) {
        return new NearestAttackableTargetGoal<>(mob, PathfinderMob.class, false,
                e -> e instanceof VillagerLike && !(e instanceof Enemy));
    }

    /** 已存在本 handler 追加的等价 goal（唯一标记：目标类恰为 PathfinderMob.class，原版生物不会直接索敌该宽类）。 */
    private static boolean hasVillagerLikeGoal(Mob mob) {
        for (WrappedGoal wrapped : mob.targetSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof NearestAttackableTargetGoal<?> goal
                    && targetTypeOf(goal) == PathfinderMob.class) {
                return true;
            }
        }
        return false;
    }

    private static boolean targetsVillagers(NearestAttackableTargetGoal<?> goal) {
        Class<?> type = targetTypeOf(goal);
        return type != null && AbstractVillager.class.isAssignableFrom(type);
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
            Log.warn(TAG, "找不到字段 {}#{} — 跳过原版生物村民级索敌增强", owner.getSimpleName(), name);
            return null;
        }
    }
}
