package com.wsteam.wandscape.content.npc.guard;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.npc.guard.executor.GuardAttackExecutor;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.content.task.source.TaskSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 守卫任务源：扫描所有非停摆建筑的攻击区（水平 ±guard.range，Y 不变），
 * 任一区域内有存活敌对生物且无活跃守卫任务时，发布 {@code guard:attack} 任务。
 *
 * <p>同一时间仅一个活跃守卫任务（用 {@link GlobalTaskPool#isActive} 去重）；
 * 任务本体是持续循环（见 {@link GuardAttackExecutor}），
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

        // 和平模式：小镇没有会战斗的 NPC → 不发布守卫任务
        // （否则和平 NPC 反复接任务立即完成，造成每轮轮询的空转）
        if (!hasAggressiveNpc()) {
            Log.info(TAG, "all colony NPCs peaceful — guard task suppressed");
            return;
        }

        // 已有一个活跃守卫任务 → 不重复发布
        if (activeTaskId != 0) return;

        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("attackRange", new JsonPrimitive(com.wsteam.wandscape.foundation.util.BalanceValues.guardRange()));
        params.put("releaseRange", new JsonPrimitive(com.wsteam.wandscape.foundation.util.BalanceValues.guardReleaseRange()));
        // 守卫任务刻意不绑定殖民地：守卫区由全殖民地建筑包围盒并集生成，可能横跨多个小镇，
        // 执行器（GuardAttackExecutor）防守所有区域。colonyId=null → 无主任务，
        // 由距威胁最近的真实殖民地 NPC 接取（调度器按邻近评分）；占位殖民地 NPC 永不接取。
        TaskRequest request = new TaskRequest("guard:attack", params, GuardConstants.GUARD_PRIORITY, null);
        activeTaskId = pool.addTask(request);
        Log.info(TAG, ">>> GUARD TASK PUBLISHED #{} target={} attack={} release={} pool={}",
                activeTaskId, threat.getUUID().toString().substring(0, 8),
                com.wsteam.wandscape.foundation.util.BalanceValues.guardRange(), com.wsteam.wandscape.foundation.util.BalanceValues.guardReleaseRange(), pool.size());
    }

    /** 攻击区（±guard.range）内距并集盒中心最近的存活 Enemy；无则 null。
     *  殖民地 NPC 的召唤随从不构成对建筑的威胁，不触发守卫任务——否则发布后立即被守卫执行器
     *  过滤为空目标而 stand-down，反复发布空转。 */
    private static LivingEntity findThreat(ServerLevel level) {
        List<GuardZone> zones = GuardScanner.zones(level, com.wsteam.wandscape.foundation.util.BalanceValues.guardRange());
        AABB queryBox = GuardScanner.unionAabb(zones);
        if (queryBox == null) return null;
        var scepterApi = WandscapeApis.getScepterApiSilently();
        return GuardScanner.nearestInZones(level, zones, queryBox.getCenter(),
                m -> !WandscapeNpc.isColonyNpcSummon(m)
                        && (scepterApi == null || !scepterApi.isShelteredForAny(m.getUUID(), level)));
    }

    /**
     * 是否存在未开启和平模式、且不在跟随模式的小镇 NPC。
     * 跟随中的 NPC 不会从任务池接取守卫任务（调度器跳过），若全小镇都跟随，
     * 守卫任务发布后无人可接会空挂——与和平模式一样抑制发布。
     */
    private static boolean hasAggressiveNpc() {
        for (WandscapeNpc npc : EntityComponentBridge.INSTANCE.allNpcs().values()) {
            if (npc != null && !npc.isPeaceMode() && !npc.isFollowMode() && !npc.isRemoved()) return true;
        }
        return false;
    }
}
