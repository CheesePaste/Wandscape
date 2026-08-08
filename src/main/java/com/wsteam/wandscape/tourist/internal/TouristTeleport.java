package com.wsteam.wandscape.tourist.internal;

import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.RoadApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Rescue-teleport destination selection for trapped tourists.
 *
 * <p>The old fallbacks teleported onto {@code findGround(...)} columns, which for a
 * building column returns the roof or the interior floor — trapping the tourist on
 * rooftops / in no-entry zones forever. Every rescue teleport must land OUTSIDE every
 * colony building: nearest built road first, then the building periphery (entry point,
 * bbox faces, a bounded ring scan), then the origin itself if it already stands on safe
 * open ground. Returns null (keep the tourist where it is) rather than teleport it into
 * a building. Tourist spawns share this method so they also never land on a building.
 */
public final class TouristTeleport {

    private static final String TAG = "TouristTeleport";

    private TouristTeleport() {
    }

    /**
     * Pick a rescue destination guaranteed to be outside every colony building.
     *
     * @param origin           current / fallback position to search around
     * @param colonyId         colony owning the buildings to avoid (may be null)
     * @param targetBuildingId building being visited (may be null) — used to prefer
     *                         its entry point / bbox faces when escaping
     * @return the ground block to stand on, or null if no safe spot exists
     */
    @Nullable
    public static BlockPos findSafeSpot(ServerLevel level, BlockPos origin,
            @Nullable UUID colonyId, @Nullable UUID targetBuildingId) {
        // 1. Nearest built road within the search radius.
        BlockPos road = nearestRoadSpot(level, origin, colonyId);
        if (road != null) return road;
        // 2. Building periphery: entry point, bbox faces, then a bounded ring scan.
        BlockPos periphery = peripherySpot(level, origin, colonyId, targetBuildingId);
        if (periphery != null) return periphery;
        // 3. Last resort before giving up: the origin column itself, if it already
        //    stands on safe open ground (e.g. trapped in front of a door).
        BlockPos here = walkableOutsideBuilding(level, origin.getX(), origin.getY(), origin.getZ(), colonyId);
        if (here != null) return here;
        Log.warn(TAG, "No safe rescue spot near {}", origin.toShortString());
        return null;
    }

    /**
     * Rescue destination biased to stay near {@code entry} — used when leaving or
     * abandoning a building so the tourist ends up just outside the entry instead of
     * teleporting to a distant road and walking all the way back.
     */
    @Nullable
    public static BlockPos findSafeSpotNearEntry(ServerLevel level, BlockPos entry,
            @Nullable UUID colonyId) {
        if (entry == null) return null;
        BlockPos near = walkableOutsideBuilding(level, entry.getX(), entry.getY(), entry.getZ(), colonyId);
        if (near != null) return near;
        return findSafeSpot(level, entry, colonyId, null);
    }

    // ── Priority 1: nearest built road ──

    @Nullable
    private static BlockPos nearestRoadSpot(ServerLevel level, BlockPos origin, @Nullable UUID colonyId) {
        RoadNetwork net = roadNetwork(colonyId);
        if (net == null || net.isEmpty()) return null;

        int maxSq = Config.TOURIST_RESCUE_ROAD_RADIUS.get() * Config.TOURIST_RESCUE_ROAD_RADIUS.get();
        PathPoint best = null;
        int bestSq = Integer.MAX_VALUE;
        for (RoadEdge edge : net.getEdges().values()) {
            if (edge.getStatus() != RoadEdge.EdgeStatus.COMPLETE) continue;
            for (PathPoint pp : edge.getPath()) {
                int dx = pp.x() - origin.getX();
                int dz = pp.z() - origin.getZ();
                int sq = dx * dx + dz * dz;
                if (sq < bestSq) {
                    bestSq = sq;
                    best = pp;
                }
            }
        }
        if (best == null || bestSq > maxSq) return null;

        // A road may run under a building built after it — validate the surface.
        BlockPos ground = findGround(level, best.x(), best.y(), best.z());
        if (ground == null) return null;
        if (isFloatingSurface(level, ground)) return null;
        if (isInsideAnyBuilding(level, ground, colonyId)) return null;
        return ground;
    }

    @Nullable
    private static RoadNetwork roadNetwork(@Nullable UUID colonyId) {
        RoadApi api = getRoadApi();
        if (api == null) return null;
        RoadNetwork net = colonyId != null ? api.getNetwork(colonyId) : null;
        if (net == null || net.isEmpty()) net = api.getNetwork(null);
        return (net != null && !net.isEmpty()) ? net : null;
    }

    // ── Priority 2: building periphery ──

    @Nullable
    private static BlockPos peripherySpot(ServerLevel level, BlockPos origin,
            @Nullable UUID colonyId, @Nullable UUID targetBuildingId) {
        // 1. The building's documented entry point already sits just outside its bbox.
        if (targetBuildingId != null) {
            BlockPos entry = getEntryPoint(targetBuildingId);
            if (entry != null) {
                BlockPos s = walkableOutsideBuilding(level, entry.getX(), entry.getY(), entry.getZ(), colonyId);
                if (s != null) return s;
            }
        }
        // 2. Step just outside the bbox the tourist is on/in (or the target building's),
        //    so even a huge building is escaped in a handful of tries.
        BoundingBox box = boundsOf(targetBuildingId);
        if (box == null) box = containingBox(level, origin, colonyId);
        if (box != null) {
            int cx = Math.max(box.minX(), Math.min(origin.getX(), box.maxX()));
            int cz = Math.max(box.minZ(), Math.min(origin.getZ(), box.maxZ()));
            BlockPos[] faces = {
                    new BlockPos(box.minX() - 3, origin.getY(), cz),
                    new BlockPos(box.maxX() + 3, origin.getY(), cz),
                    new BlockPos(cx, origin.getY(), box.minZ() - 3),
                    new BlockPos(cx, origin.getY(), box.maxZ() + 3),
            };
            for (BlockPos f : faces) {
                BlockPos s = walkableOutsideBuilding(level, f.getX(), f.getY(), f.getZ(), colonyId);
                if (s != null) return s;
            }
        }
        // 3. Bounded outward ring scan, sampling columns until one clears all bboxes.
        int radius = Config.TOURIST_RESCUE_PERIPHERY_RADIUS.get();
        for (int r = 2; r <= radius; r += 2) {
            int step = Math.max(1, r / 4);
            for (int x = -r; x <= r; x += step) {
                BlockPos s = tryColumn(level, origin, x, r, colonyId);
                if (s != null) return s;
                s = tryColumn(level, origin, x, -r, colonyId);
                if (s != null) return s;
            }
            for (int z = -r; z <= r; z += step) {
                BlockPos s = tryColumn(level, origin, r, z, colonyId);
                if (s != null) return s;
                s = tryColumn(level, origin, -r, z, colonyId);
                if (s != null) return s;
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos tryColumn(ServerLevel level, BlockPos origin, int dx, int dz,
            @Nullable UUID colonyId) {
        return walkableOutsideBuilding(level, origin.getX() + dx, origin.getY(), origin.getZ() + dz, colonyId);
    }

    // ── Shared validation ──

    /** Walkable ground at (x, z) that is not inside any building and not a floating roof/shelf. */
    @Nullable
    private static BlockPos walkableOutsideBuilding(ServerLevel level, int x, int baseY, int z,
            @Nullable UUID colonyId) {
        BlockPos ground = findGround(level, x, baseY, z);
        if (ground == null) return null;
        if (isInsideAnyBuilding(level, ground, colonyId)) return null;
        if (isFloatingSurface(level, ground)) return null;
        return ground;
    }

    private static boolean isInsideAnyBuilding(ServerLevel level, BlockPos pos, @Nullable UUID colonyId) {
        if (colonyId == null) return false;
        BuildingApi api = getBuildingApi();
        if (api == null) return false;
        for (BuildingData b : api.getColonyBuildings(colonyId)) {
            if (!(b instanceof BuildingState state)) continue;
            BoundingBox box = state.getBounds();
            if (box != null && box.isInside(pos)) return true;
        }
        return false;
    }

    @Nullable
    private static BoundingBox boundsOf(@Nullable UUID buildingId) {
        if (buildingId == null) return null;
        BuildingApi api = getBuildingApi();
        if (api == null) return null;
        var data = api.getBuilding(buildingId);
        return (data instanceof BuildingState state) ? state.getBounds() : null;
    }

    @Nullable
    private static BoundingBox containingBox(ServerLevel level, BlockPos pos, @Nullable UUID colonyId) {
        if (colonyId == null) return null;
        BuildingApi api = getBuildingApi();
        if (api == null) return null;
        for (BuildingData b : api.getColonyBuildings(colonyId)) {
            if (!(b instanceof BuildingState state)) continue;
            BoundingBox box = state.getBounds();
            if (box != null && box.isInside(pos)) return box;
        }
        return null;
    }

    @Nullable
    private static BlockPos getEntryPoint(UUID buildingId) {
        BuildingApi api = getBuildingApi();
        if (api == null) return null;
        return api.getEntryPoint(buildingId);
    }

    /** Scan from the world surface down; returns the first air-above-solid with two solid below. */
    @Nullable
    private static BlockPos findGround(ServerLevel level, int x, int baseY, int z) {
        int topY = Math.max(level.getMinBuildHeight(),
                Math.min(level.getMaxBuildHeight() - 1,
                        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)));
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(x, topY, z);
        while (mp.getY() > level.getMinBuildHeight()) {
            if (level.getBlockState(mp).isAir()
                    && level.getBlockState(mp.below()).isSolid()
                    && level.getBlockState(mp.below(2)).isSolid()) {
                return mp.immutable();
            }
            mp.move(0, -1, 0);
        }
        return null;
    }

    /** True when {@code feet} stands on a solid block with open air directly beneath (roof / shelf). */
    private static boolean isFloatingSurface(ServerLevel level, BlockPos feet) {
        if (!level.getBlockState(feet).isAir()) return false;
        if (!level.getBlockState(feet.below()).isSolid()) return false;
        return level.getBlockState(feet.below(2)).isAir();
    }

    /**
     * True when {@code feet} sits on a building roof (floating surface, or at the top
     * of a building's bbox) rather than a legit ground floor — used to decide whether
     * a shadow→entity hydration snap should relocate instead of landing on a roof.
     */
    static boolean isRoofInsideBuilding(ServerLevel level, BlockPos feet, @Nullable UUID colonyId) {
        BoundingBox box = containingBox(level, feet, colonyId);
        if (box == null) return false;
        if (isFloatingSurface(level, feet)) return true;
        return feet.getY() >= box.maxY() - 1;
    }

    @Nullable
    private static BuildingApi getBuildingApi() {
        try { return WandscapeApis.getBuildingApi(); }
        catch (IllegalStateException e) { return null; }
    }

    @Nullable
    private static RoadApi getRoadApi() {
        try { return WandscapeApis.getRoadApi(); }
        catch (IllegalStateException e) { return null; }
    }
}
