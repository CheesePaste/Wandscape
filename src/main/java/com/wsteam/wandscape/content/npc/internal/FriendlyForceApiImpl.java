package com.wsteam.wandscape.content.npc.internal;
import com.wsteam.wandscape.api.FriendlyForceApi;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * {@link FriendlyForceApi} 实现：持有其它模组注册的友军判定器列表，供
 * {@code WandscapeNpc#classify} 在内置类型不命中时兜底查询。
 *
 * <p>{@code registerAlly} 通常在其它模组 FML 构造/初始化阶段调用；{@code CopyOnWriteArrayList}
 * 保证跨线程注册与运行时读取安全（读多写极少）。判定器须轻量（instanceof 优先），它在每次
 * 友军判定、目标过滤时都会被查询。
 */
public final class FriendlyForceApiImpl implements FriendlyForceApi {
    private static final String TAG = "FriendlyForceApi";
    private final List<Predicate<LivingEntity>> allies = new CopyOnWriteArrayList<>();

    @Override
    public void registerAlly(Predicate<LivingEntity> isAlly) {
        if (isAlly == null) return;
        allies.add(isAlly);
        Log.info(TAG, "registered external ally predicate (now {})", allies.size());
    }

    @Override
    public boolean isExternalAlly(LivingEntity entity) {
        if (allies.isEmpty()) return false;
        for (Predicate<LivingEntity> p : allies) {
            try {
                if (p.test(entity)) return true;
            } catch (RuntimeException e) {
                Log.warn(TAG, "external ally predicate failed for {}: {}",
                        entity.getType().getDescriptionId(), e.toString());
            }
        }
        return false;
    }
}
