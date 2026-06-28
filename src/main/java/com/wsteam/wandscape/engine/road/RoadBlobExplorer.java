package com.wsteam.wandscape.engine.road;

import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadBlobCache;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Lazily discovers contiguous player-built road blobs in the world.
 *
 * <p>Scans around two positions (start/end of a planned route), and if
 * uncached road-tagged blocks are found, performs a BFS to discover the
 * entire connected road component.
 *
 * <p>All methods static — no state. The {@link RoadBlobCache} is the
 * sole state holder, stored per-level via {@link RoadSavedData}.
 */
public final class RoadBlobExplorer {

    private static final String TAG = "RoadBlobExplorer";

    /** Max blocks to scan outward from start/end positions. */
    private static final int SCAN_RADIUS = 16;

    /** Max blocks to scan vertically when looking for road blocks near a position. */
    private static final int SCAN_VERTICAL = 4;

    private RoadBlobExplorer() {}

    /**
     * Scan around {@code nearA} and {@code nearB} for road-tagged blocks.
     * If an uncached road block is found, BFS-discover the entire contiguous
     * blob and add it to the cache.
     *
     * @param level   the server level
     * @param nearA   first search position (e.g. route start)
     * @param nearB   second search position (e.g. route end)
     * @param cache   the blob cache to populate
     * @param roadTag the block tag defining road blocks
     * @return the number of new blobs discovered
     */
    public static int scanAndCache(Level level, BlockPos nearA, BlockPos nearB,
                                   RoadBlobCache cache, TagKey<Block> roadTag) {
        int found = 0;
        found += scanAround(level, nearA, cache, roadTag);
        found += scanAround(level, nearB, cache, roadTag);
        if (found > 0) {
            Log.info(TAG, "Discovered {} new road blob(s) — cache now has {} blobs ({} blocks)",
                    found, cache.blobCount(), cache.totalBlockCount());
        }
        return found;
    }

    /**
     * Scan a 3D box around one position for uncached road blocks.
     * For each uncached road block found, discover and cache the full blob.
     */
    private static int scanAround(Level level, BlockPos center, RoadBlobCache cache,
                                  TagKey<Block> roadTag) {
        int found = 0;
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                // Early skip: Manhattan distance > SCAN_RADIUS to reduce checks
                int manhattan = Math.abs(dx) + Math.abs(dz);
                if (manhattan > SCAN_RADIUS) continue;

                for (int dy = -SCAN_VERTICAL; dy <= SCAN_VERTICAL; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    PathPoint pp = new PathPoint(pos.getX(), pos.getY(), pos.getZ());

                    // Skip already-cached positions
                    if (cache.contains(pp)) continue;

                    BlockState state = level.getBlockState(pos);
                    if (state.is(roadTag)) {
                        Set<PathPoint> blob = discoverBlob(level, pos, roadTag, cache);
                        if (!blob.isEmpty()) {
                            UUID blobId = cache.addBlob(blob);
                            Log.debug(TAG, "Blob {}: {} blocks at ({},{},{})",
                                    blobId.toString().substring(0, 8),
                                    blob.size(), pos.getX(), pos.getY(), pos.getZ());
                            found++;
                            // After discovering a blob, skip to next scan position
                            // (the blob's blocks are now cached and will be skipped)
                        }
                    }
                }
            }
        }
        return found;
    }

    /**
     * BFS-discover all blocks in a connected road component.
     *
     * <p>Explores 6 directions (4 cardinal + up/down) to handle
     * stairs, slabs, bridges, and tunnels.
     *
     * @param level     the server level
     * @param start     the starting block position
     * @param roadTag   the block tag for road blocks
     * @param cache     existing cache (to stop BFS at already-known blocks)
     * @return the set of PathPoints in this blob (empty on failure)
     */
    static Set<PathPoint> discoverBlob(Level level, BlockPos start,
                                       TagKey<Block> roadTag, RoadBlobCache cache) {
        Set<PathPoint> blob = new LinkedHashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        queue.add(start);
        visited.add(start);

        // For Y variation: we also check neighbors at Y±1 to follow stairs/slabs
        // Direction order: N, S, E, W, UP, DOWN
        Direction[] dirs = {
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
                Direction.UP, Direction.DOWN
        };

        int checked = 0;
        while (!queue.isEmpty() && blob.size() < RoadBlobCache.MAX_BLOB_SIZE) {
            BlockPos current = queue.poll();
            BlockState currentState = level.getBlockState(current);

            // Verify this block still matches the tag
            if (!currentState.is(roadTag)) continue;

            PathPoint pp = new PathPoint(current.getX(), current.getY(), current.getZ());

            // Skip if already cached (belongs to another blob or this one)
            if (cache.contains(pp)) continue;
            if (!blob.add(pp)) continue; // already in this blob

            checked++;

            for (Direction dir : dirs) {
                BlockPos neighbor = current.relative(dir);
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        if (blob.size() >= RoadBlobCache.MAX_BLOB_SIZE) {
            Log.warn(TAG, "Blob at ({},{},{}) hit MAX_BLOB_SIZE={} — truncated. "
                            + "Players should not pave half the world!",
                    start.getX(), start.getY(), start.getZ(),
                    RoadBlobCache.MAX_BLOB_SIZE);
        }

        if (blob.isEmpty()) {
            Log.debug(TAG, "BFS at ({},{},{}) found 0 blocks after {} checks",
                    start.getX(), start.getY(), start.getZ(), checked);
        }

        return blob;
    }
}
