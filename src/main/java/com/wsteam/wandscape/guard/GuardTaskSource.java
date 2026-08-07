package com.wsteam.wandscape.guard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.task.source.TaskSource;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 守卫任务源：扫描所有非停摆建筑的攻击区（水平 ±guard.range，Y 不变），
 * 任一区域内有存活敌对生物且无活跃守卫任务时，发布 {@code guard:attack} 任务。
 *
 * <p>同一时间仅一个活跃守卫任务（用 {@link GlobalTaskPool#isActive} 去重）；
 * 任务本体是持续循环（见 {@link com.wsteam.wandscape.guard.executor.GuardAttackExecutor}），
 * 会一直守到脱离区（±guard.releaseRange）无怪才完成，源无需额外限速。
 */
public final class GuardTaskSource implements TaskSource {
    private static final String TAG = "GuardTaskSource";

    /** 当前活跃守卫任务 id；0 = 无。 */
    private long activeTaskId = 0;

    @Override
    public int pollIntervalTicks() {
        return GuardConstants.POLL_INTERVAL_TICKS;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        // 清理：上一守卫任务已结束（脱离区无怪 → 任务完成）
        if (activeTaskId != 0 && !pool.isActive(activeTaskId)) {
            Log.info(TAG, "previous guard task #{} completed/disengaged — clearing", activeTaskId);
            activeTaskId = 0;
        }

        // 触发：任一建筑攻击区内有存活 Enemy
        LivingEntity threat = findThreat(level);
        if (threat == null) return;

        // 已有一个活跃守卫任务 → 不重复发布
        if (activeTaskId != 0) return;

        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("attackRange", new JsonPrimitive(Config.GUARD_RANGE.get()));
        params.put("releaseRange", new JsonPrimitive(Config.GUARD_RELEASE_RANGE.get()));
        TaskRequest request = new TaskRequest("guard:attack", params, GuardConstants.GUARD_PRIORITY);
        activeTaskId = pool.addTask(request);
        Log.info(TAG, ">>> GUARD TASK PUBLISHED #{} target={} attack={} release={} pool={}",
                activeTaskId, threat.getUUID().toString().substring(0, 8),
                Config.GUARD_RANGE.get(), Config.GUARD_RELEASE_RANGE.get(), pool.size());
    }

    /** 攻击区（±guard.range）内距并集盒中心最近的存活 Enemy；无则 null。 */
    private static LivingEntity findThreat(ServerLevel level) {
        List<GuardZone> zones = GuardScanner.zones(level, Config.GUARD_RANGE.get());
        AABB queryBox = GuardScanner.unionAabb(zones);
        if (queryBox == null) return null;
        return GuardScanner.nearestInZones(level, zones, queryBox.getCenter());
    }
}
