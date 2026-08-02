package com.wsteam.wandscape.engine;

import java.lang.reflect.Field;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.entity.VillagerLike;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
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
            // 该生物原本就猎杀村民 → 同优先级、同视野规则地索敌 NPC/游客
            mob.targetSelector.addGoal(wrapped.getPriority(), villagerLikeGoal(mob));
            return;
        }
    }

    /**
     * 目标类型用 {@link VillagerLike}（接口）。运行时 {@code Level#getEntitiesOfClass} 走
     * {@code clazz.isInstance()}，接口可用；仅编译期泛型约束 {@code T extends LivingEntity}
     * 不满足接口，故 raw cast + 抑制警告。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Goal villagerLikeGoal(Mob mob) {
        return new NearestAttackableTargetGoal(mob, (Class) VillagerLike.class, false);
    }

    private static boolean targetsVillagers(NearestAttackableTargetGoal<?> goal) {
        try {
            Class<?> type = (Class<?>) TARGET_TYPE.get(goal);
            return type != null && AbstractVillager.class.isAssignableFrom(type);
        } catch (IllegalAccessException e) {
            Log.warn(TAG, "反射读取索敌目标类失败: {}", e.getMessage());
            return false;
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
