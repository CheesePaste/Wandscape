package com.wsteam.wandscape.guard;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
            if (b.isShutdown() || b.isDemolishing()) continue;
            BoundingBox bb = api.getBuildingBounds(b.getBuildingId());
            if (bb == null) continue;
            zones.add(GuardZone.of(bb.minX(), bb.minY(), bb.minZ(),
                    bb.maxX(), bb.maxY(), bb.maxZ(), range));
        }
        return zones;
    }

    /** 各区域并集内存活 Enemy，过滤到任区域内，返回距 {@code from} 最近者；无则 null。 */
    @Nullable
    public static LivingEntity nearestInZones(ServerLevel level, List<GuardZone> zones, Vec3 from) {
        AABB queryBox = unionAabb(zones);
        if (queryBox == null) return null;
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : level.getEntities((Entity) null, queryBox, e -> e instanceof Enemy)) {
            if (!(e instanceof LivingEntity mob) || mob.isRemoved() || !mob.isAlive()) continue;
            if (!inAnyZone(mob, zones)) continue;
            double d = mob.distanceToSqr(from);
            if (d < bestSq) {
                bestSq = d;
                best = mob;
            }
        }
        return best;
    }

    /** 任一区域内是否有存活 Enemy（用于脱离判定）。 */
    public static boolean hasMonsterInZones(ServerLevel level, List<GuardZone> zones) {
        AABB queryBox = unionAabb(zones);
        if (queryBox == null) return false;
        for (Entity e : level.getEntities((Entity) null, queryBox, e -> e instanceof Enemy)) {
            if (e instanceof LivingEntity mob && !mob.isRemoved() && mob.isAlive()
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
