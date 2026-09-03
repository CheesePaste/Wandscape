package com.wsteam.wandscape.content.building.internal;

import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for a building's occupied voxels and the "does this
 * conflict?" test. Both the server {@link BuildingSavedData#register} overlap
 * gate and the client ghost preview ({@code BuildingAreaSyncPacket}) derive from
 * the same rule:
 *
 * <ul>
 *   <li>Bounding boxes (AABB) may overlap freely — indoor / tightly-packed /
 *       nested buildings are allowed.</li>
 *   <li>A world voxel belongs to at most one building. Construction/demolition/
 *       repair only ever touch a building's own pattern voxels; a shared voxel is
 *       rejected with the two-phase test below.</li>
 * </ul>
 *
 * <p>Two-phase conflict test (cheap with many buildings): first compare the
 * occupied-voxel AABBs (broad phase, constant work per pair), and only when two
 * boxes intersect do we compare the actual voxel sets (narrow phase). Matching
 * the two-phase ask: never iterate every building's full pattern blindly.
 */
public final class BuildingVoxels {

    private BuildingVoxels() {}

    /**
     * Rotated pattern offsets per (config instance, rotation). Keyed by the config
     * object's identity so a `/reload` that replaces the config (same id, new
     * object) never serves stale rotated offsets. Rotation is anchor-independent.
     */
    private static final Map<String, List<BlockOffset>> ROTATED_PATTERN_CACHE = new ConcurrentHashMap<>();

    /**
     * The world-space voxels a building configuration would occupy at an anchor
     * with a given rotation (0-3, 90° CCW).
     *
     * @param positions unmodifiable set of occupied world positions
     * @param extent    AABB over {@link #positions()} (broad phase); null when empty
     */
    public record Occupancy(Set<BlockPos> positions, @Nullable BoundingBox extent) {
        public boolean isEmpty() {
            return positions.isEmpty();
        }
    }

    /** Occupied voxels of a building config placed at {@code anchor} with {@code rotationSteps}. */
    public static Occupancy compute(BuildingConfig config, BlockPos anchor, int rotationSteps) {
        List<BlockOffset> pattern = rotatedOffsets(config, rotationSteps);
        return computeFromOffsets(pattern, anchor);
    }

    /** Build an occupancy from pre-rotated offsets + world anchor. */
    public static Occupancy computeFromOffsets(List<BlockOffset> offsets, BlockPos anchor) {
        if (offsets.isEmpty()) {
            return new Occupancy(Collections.emptySet(), null);
        }
        Set<BlockPos> world = new HashSet<>(offsets.size());
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockOffset off : offsets) {
            int x = anchor.getX() + off.x();
            int y = anchor.getY() + off.y();
            int z = anchor.getZ() + off.z();
            world.add(new BlockPos(x, y, z));
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        return new Occupancy(Collections.unmodifiableSet(world),
                new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /**
     * Two-phase voxel conflict between two occupancies: bounding-AABB broad phase,
     * then exact voxel compare iterating the smaller set. Empty / null-extent
     * occupancies never conflict.
     */
    public static boolean overlaps(Occupancy a, Occupancy b) {
        return overlaps(a.positions(), a.extent(), b.positions(), b.extent());
    }

    /** Two-phase voxel conflict between two position sets with their broad-phase AABBs. */
    public static boolean overlaps(Set<BlockPos> aPositions, @Nullable BoundingBox aExtent,
                                   Set<BlockPos> bPositions, @Nullable BoundingBox bExtent) {
        if (aExtent == null || bExtent == null) return false;
        if (aPositions.isEmpty() || bPositions.isEmpty()) return false;
        if (!extentsIntersect(aExtent, bExtent)) return false;
        // Narrow phase — iterate the smaller set.
        if (aPositions.size() <= bPositions.size()) {
            for (BlockPos pos : aPositions) {
                if (bPositions.contains(pos)) return true;
            }
        } else {
            for (BlockPos pos : bPositions) {
                if (aPositions.contains(pos)) return true;
            }
        }
        return false;
    }

    /** Inclusive AABB intersection test (no dependency on a BoundingBox.intersects overload). */
    public static boolean extentsIntersect(BoundingBox a, BoundingBox b) {
        return a.maxX() >= b.minX() && a.minX() <= b.maxX()
                && a.maxY() >= b.minY() && a.minY() <= b.maxY()
                && a.maxZ() >= b.minZ() && a.minZ() <= b.maxZ();
    }

    /** AABB spanning a set of world positions; null when the collection is empty. */
    @Nullable
    public static BoundingBox boundingBoxOf(Collection<BlockPos> positions) {
        if (positions.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : positions) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Rotated pattern offsets with a per-(config instance, rotation) cache so previews stay cheap. */
    public static List<BlockOffset> rotatedOffsets(BuildingConfig config, int rotationSteps) {
        int rot = rotationSteps & 3;
        String key = System.identityHashCode(config) + "|" + rot;
        List<BlockOffset> cached = ROTATED_PATTERN_CACHE.get(key);
        if (cached != null) return cached;
        List<BlockOffset> pattern = config.pattern();
        List<BlockOffset> rotated = BuildingRotation.rotateOffsets(pattern, rot);
        if (rotated == pattern) {
            rotated = new ArrayList<>(pattern); // keep cache per-call detached from the config list
        }
        List<BlockOffset> unmod = Collections.unmodifiableList(rotated);
        ROTATED_PATTERN_CACHE.put(key, unmod);
        return unmod;
    }
}
