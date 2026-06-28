package com.wsteam.wandscape.core.road;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Lazy cache of contiguous player-built road blocks discovered via BFS.
 *
 * <p>Each "blob" is a connected component of blocks matching the
 * {@code wandscape:custom_roads} tag. Boundaries are computed lazily
 * — a blob block is on the boundary if ≥1 cardinal neighbor (XZ)
 * does not belong to the same blob at any reachable Y.
 *
 * <p>Pure core — zero MC dependencies. The engine layer
 * ({@code RoadBlobExplorer}) populates blobs by scanning the world;
 * {@link RoadRouter} reads them during graph construction.
 *
 * <h3>Thread safety</h3>
 * <p>Not thread-safe — single-threaded server tick access only.
 */
public final class RoadBlobCache {

    /** Max blocks per blob to prevent runaway BFS stalls. */
    public static final int MAX_BLOB_SIZE = 2000;

    private final Map<UUID, Set<PathPoint>> blobBlocks;
    private final Map<PathPoint, UUID> posToBlob;
    private final Map<UUID, Set<PathPoint>> blobBoundaries;

    public RoadBlobCache() {
        this.blobBlocks = new LinkedHashMap<>();
        this.posToBlob = new HashMap<>();
        this.blobBoundaries = new HashMap<>();
    }

    // ── Blob management ──────────────────────────────────────────

    /**
     * Add a newly discovered blob of contiguous road blocks.
     *
     * @param blocks the set of 3D positions in this blob
     * @return the assigned blob UUID
     * @throws IllegalArgumentException if blocks is empty
     */
    public UUID addBlob(Set<PathPoint> blocks) {
        if (blocks.isEmpty()) throw new IllegalArgumentException("Blob must not be empty");
        UUID id = UUID.randomUUID();
        Set<PathPoint> copy = Set.copyOf(blocks);
        blobBlocks.put(id, copy);
        for (PathPoint pp : blocks) {
            posToBlob.putIfAbsent(pp, id);
        }
        // Boundaries computed lazily on first query
        return id;
    }

    /** Remove a blob by ID. */
    public void removeBlob(UUID blobId) {
        Set<PathPoint> blocks = blobBlocks.remove(blobId);
        if (blocks != null) {
            blocks.forEach(posToBlob::remove);
        }
        blobBoundaries.remove(blobId);
    }

    /** Clear all cached blobs (full invalidation). */
    public void clear() {
        blobBlocks.clear();
        posToBlob.clear();
        blobBoundaries.clear();
    }

    // ── Queries ───────────────────────────────────────────────────

    /** True if no blobs are cached. */
    public boolean isEmpty() {
        return blobBlocks.isEmpty();
    }

    /** Number of cached blobs. */
    public int blobCount() {
        return blobBlocks.size();
    }

    /** Total blocks across all cached blobs. */
    public int totalBlockCount() {
        return posToBlob.size();
    }

    /**
     * Get the blob ID that contains the given position, or null.
     * O(1) hash lookup.
     */
    @Nullable
    public UUID getBlobAt(PathPoint pos) {
        return posToBlob.get(pos);
    }

    /** True if the position is in any cached blob. */
    public boolean contains(PathPoint pos) {
        return posToBlob.containsKey(pos);
    }

    /**
     * Get the blocks of a specific blob. Immutable snapshot.
     * Returns empty set if blob ID unknown.
     */
    public Set<PathPoint> getBlobBlocks(UUID blobId) {
        Set<PathPoint> b = blobBlocks.get(blobId);
        return b != null ? b : Set.of();
    }

    /** Immutable view of all blobs. */
    public Map<UUID, Set<PathPoint>> getAllBlobs() {
        return Collections.unmodifiableMap(blobBlocks);
    }

    // ── Boundary computation ──────────────────────────────────────

    /**
     * Get the boundary points of a blob.
     *
     * <p>A block is on the boundary if at least one cardinal neighbor
     * (±X, ±Z) at the same Y is NOT in the same blob. Boundary points
     * are where NPCs can enter/exit the blob.
     *
     * <p>Computed lazily on first call, cached thereafter.
     *
     * @return boundary points (empty set if blob unknown)
     */
    public Set<PathPoint> getBoundaryPoints(UUID blobId) {
        Set<PathPoint> blocks = blobBlocks.get(blobId);
        if (blocks == null) return Set.of();

        Set<PathPoint> cached = blobBoundaries.get(blobId);
        if (cached != null) return cached;

        Set<PathPoint> boundary = computeBoundary(blocks);
        blobBoundaries.put(blobId, boundary);
        return boundary;
    }

    /**
     * Invalidate the boundary cache for a blob.
     * Call if blob contents change (currently not used — blobs are immutable).
     */
    public void invalidateBoundary(UUID blobId) {
        blobBoundaries.remove(blobId);
    }

    private Set<PathPoint> computeBoundary(Set<PathPoint> blocks) {
        Set<PathPoint> boundary = new HashSet<>();
        for (PathPoint pp : blocks) {
            // A block is on the boundary if any of its 4 cardinal XZ neighbors
            // at the same Y is NOT in the blob. Y variation (stairs/slabs) is
            // handled by the centroid虫洞 — each step IS an entry/exit point.
            boolean hasNeighborOutside =
                    !blocks.contains(new PathPoint(pp.x() + 1, pp.y(), pp.z()))
                    || !blocks.contains(new PathPoint(pp.x() - 1, pp.y(), pp.z()))
                    || !blocks.contains(new PathPoint(pp.x(), pp.y(), pp.z() + 1))
                    || !blocks.contains(new PathPoint(pp.x(), pp.y(), pp.z() - 1));

            if (hasNeighborOutside) {
                boundary.add(pp);
            }
        }
        return Collections.unmodifiableSet(boundary);
    }

    // ── Nearest-point query ───────────────────────────────────────

    /**
     * Find the nearest cached blob point to a target position.
     * Searches ALL blobs. Weighted by XZ distance first, then Y.
     *
     * @param target the position to search near
     * @return nearest blob point, or null if cache is empty
     */
    @Nullable
    public PathPoint findNearestBlobPoint(PathPoint target) {
        PathPoint best = null;
        double bestScore = Double.MAX_VALUE;

        for (Set<PathPoint> blocks : blobBlocks.values()) {
            for (PathPoint pp : blocks) {
                int dxz = pp.manhattanXZTo(target);
                int dy = Math.abs(pp.y() - target.y());
                double score = dxz + dy * 0.8;
                if (dy <= dxz) score -= 0.4 * dxz;
                if (score < bestScore) {
                    bestScore = score;
                    best = pp;
                }
            }
        }
        return best;
    }

    // ── Debug ─────────────────────────────────────────────────────

    @Override
    public String toString() {
        int totalBlocks = totalBlockCount();
        int totalBoundaries = blobBoundaries.values().stream().mapToInt(Set::size).sum();
        return "RoadBlobCache[blobs=" + blobBlocks.size()
                + " blocks=" + totalBlocks
                + " boundaries=" + totalBoundaries + "]";
    }
}
