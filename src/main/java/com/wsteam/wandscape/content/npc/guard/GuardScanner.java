package com.wsteam.wandscape.content.npc.guard;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.EntityId;

import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 守卫区域扫描的共享静态工具：由 {@link GuardTaskSource}（触发侦测）与
 * {@link GuardAttackExecutor}（持续重选目标）共同使用，避免重复。
 */
public final class GuardScanner {
    private GuardScanner() {}

    /** 所有非停摆（非拆除中）建筑包围盒水平外扩 {@code range} 的守卫区。 */
    public static List<GuardZone> zones(ServerLevel level, int range) {
        List<GuardZone> zones = new ArrayList<>();
        BuildingApi api = buildingApi();
        if (api == null) return zones;
        for (BuildingData b : api.getColonyBuildings(null)) {
            if (b.isDemolishing()) continue;
            BoundingBox bb = api.getBuildingBounds(b.getBuildingId());
            if (bb == null) continue;
            zones.add(GuardZone.of(bb.minX(), bb.minY(), bb.minZ(),
                    bb.maxX(), bb.maxY(), bb.maxZ(), range));
        }
        return zones;
    }

    /** 不可达怪物黑名单（entityId -> 到期游戏 tick），超时未获得视线的怪物在此期间不再被索敌。 */
    private static final Map<Integer, Long> UNREACHABLE_BLACKLIST = new ConcurrentHashMap<>();

    /** 将特定实体设为不可达黑名单（在 durationTicks 内不再被守卫索敌）。 */
    public static void blacklistMob(int entityId, long currentGameTime, long durationTicks) {
        UNREACHABLE_BLACKLIST.put(entityId, currentGameTime + durationTicks);
    }

    /** 实体是否在不可达黑名单中。过期自动清理。 */
    public static boolean isBlacklisted(int entityId, long currentGameTime) {
        Long expire = UNREACHABLE_BLACKLIST.get(entityId);
        if (expire == null) return false;
        if (currentGameTime >= expire) {
            UNREACHABLE_BLACKLIST.remove(entityId);
            return false;
        }
        return true;
    }

    /** 各区域并集内存活敌对目标（{@code isHostileTarget}，中立生物须已发怒），
     *  过滤到任区域内且非黑名单，返回距 {@code from} 最近者；无则 null。 */
    @Nullable
    public static LivingEntity nearestInZones(ServerLevel level, List<GuardZone> zones, Vec3 from) {
        return nearestInZones(level, zones, from, null);
    }

    /** {@link #nearestInZones} 带额外过滤（如守卫过滤己方/同殖民地召唤物）：{@code extraFilter} 返回
     *  false 的目标不入选；null 表示不过滤。 */
    @Nullable
    public static LivingEntity nearestInZones(ServerLevel level, List<GuardZone> zones, Vec3 from,
                                              @Nullable Predicate<LivingEntity> extraFilter) {
        AABB queryBox = unionAabb(zones);
        if (queryBox == null) return null;
        long gameTime = level.getGameTime();
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : level.getEntities((Entity) null, queryBox,
                e -> e instanceof LivingEntity le && WandscapeNpc.isHostileTarget(le, level))) {
            if (!(e instanceof LivingEntity mob) || mob.isRemoved() || !mob.isAlive()) continue;
            if (extraFilter != null && !extraFilter.test(mob)) continue;
            if (isBlacklisted(mob.getId(), gameTime)) continue;
            if (!inAnyZone(mob, zones)) continue;
            double d = mob.distanceToSqr(from);
            if (d < bestSq) {
                bestSq = d;
                best = mob;
            }
        }
        return best;
    }

    /** 任一区域内是否有非黑名单的存活敌对目标（{@code isHostileTarget}，用于脱离判定）。 */
    public static boolean hasMonsterInZones(ServerLevel level, List<GuardZone> zones) {
        return hasMonsterInZones(level, zones, null);
    }

    /** {@link #hasMonsterInZones} 带额外过滤（如守卫过滤己方/同殖民地召唤物）：{@code extraFilter}
     *  返回 false 的目标不计为威胁；null 表示不过滤。 */
    public static boolean hasMonsterInZones(ServerLevel level, List<GuardZone> zones,
                                            @Nullable Predicate<LivingEntity> extraFilter) {
        AABB queryBox = unionAabb(zones);
        if (queryBox == null) return false;
        long gameTime = level.getGameTime();
        for (Entity e : level.getEntities((Entity) null, queryBox,
                e -> e instanceof LivingEntity le && WandscapeNpc.isHostileTarget(le, level))) {
            if (e instanceof LivingEntity mob && !mob.isRemoved() && mob.isAlive()
                    && (extraFilter == null || extraFilter.test(mob))
                    && !isBlacklisted(mob.getId(), gameTime)
                    && inAnyZone(mob, zones)) {
                return true;
            }
        }
        return false;
    }

    /** 任一区域是否包含怪物（按脚底坐标，Y 必须落在建筑包围盒高度内）。 */
    static boolean inAnyZone(LivingEntity mob, List<GuardZone> zones) {
        for (GuardZone z : zones) {
            if (z.contains(mob.getX(), mob.getY(), mob.getZ())) return true;
        }
        return false;
    }

    /** 并集查询盒：块坐标含边界，故 AABB max 取 max+1 以覆盖整块。 */
    @Nullable
    static AABB unionAabb(List<GuardZone> zones) {
        if (zones.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (GuardZone z : zones) {
            minX = Math.min(minX, z.minX());
            minY = Math.min(minY, z.minY());
            minZ = Math.min(minZ, z.minZ());
            maxX = Math.max(maxX, z.maxX());
            maxY = Math.max(maxY, z.maxY());
            maxZ = Math.max(maxZ, z.maxZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    @Nullable
    private static BuildingApi buildingApi() {
        try {
            return WandscapeApis.getBuildingApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
