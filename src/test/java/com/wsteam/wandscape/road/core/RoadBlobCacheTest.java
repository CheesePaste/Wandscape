package com.wsteam.wandscape.road.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;

import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadBlobCache;
import org.junit.jupiter.api.Test;

class RoadBlobCacheTest {

    // ── Blob management ───────────────────────────────────────────

    @Test
    void addBlobThenRetrieve() {
        RoadBlobCache cache = new RoadBlobCache();
        Set<PathPoint> blocks = Set.of(
                new PathPoint(0, 64, 0),
                new PathPoint(1, 64, 0),
                new PathPoint(2, 64, 0));

        UUID id = cache.addBlob(blocks);

        assertEquals(3, cache.totalBlockCount());
        assertEquals(1, cache.blobCount());
        assertNotNull(id);
        assertTrue(cache.contains(new PathPoint(0, 64, 0)));
        assertTrue(cache.contains(new PathPoint(1, 64, 0)));
        assertFalse(cache.contains(new PathPoint(3, 64, 0)));
    }

    @Test
    void addBlobEmptyThrows() {
        RoadBlobCache cache = new RoadBlobCache();
        assertThrows(IllegalArgumentException.class, () -> cache.addBlob(Set.of()));
    }

    @Test
    void removeBlobCleansUp() {
        RoadBlobCache cache = new RoadBlobCache();
        Set<PathPoint> blocks = Set.of(
                new PathPoint(0, 64, 0),
                new PathPoint(1, 64, 0));
        UUID id = cache.addBlob(blocks);

        cache.removeBlob(id);

        assertTrue(cache.isEmpty());
        assertEquals(0, cache.totalBlockCount());
        assertEquals(0, cache.blobCount());
        assertFalse(cache.contains(new PathPoint(0, 64, 0)));
    }

    @Test
    void removeBlobUnknownIdNoops() {
        RoadBlobCache cache = new RoadBlobCache();
        cache.addBlob(Set.of(new PathPoint(0, 64, 0)));
        cache.removeBlob(UUID.randomUUID());
        assertEquals(1, cache.totalBlockCount());
    }

    @Test
    void clearEmptiesAll() {
        RoadBlobCache cache = new RoadBlobCache();
        cache.addBlob(Set.of(new PathPoint(0, 64, 0)));
        cache.addBlob(Set.of(new PathPoint(10, 65, 10)));
        assertEquals(2, cache.blobCount());

        cache.clear();

        assertTrue(cache.isEmpty());
        assertEquals(0, cache.blobCount());
        assertEquals(0, cache.totalBlockCount());
    }

    @Test
    void emptyCacheQueries() {
        RoadBlobCache cache = new RoadBlobCache();
        assertTrue(cache.isEmpty());
        assertEquals(0, cache.blobCount());
        assertEquals(0, cache.totalBlockCount());
        assertNull(cache.getBlobAt(new PathPoint(0, 0, 0)));
        assertNull(cache.findNearestBlobPoint(new PathPoint(0, 0, 0)));
        assertTrue(cache.getBoundaryPoints(UUID.randomUUID()).isEmpty());
        assertTrue(cache.getBlobBlocks(UUID.randomUUID()).isEmpty());
        assertTrue(cache.getAllBlobs().isEmpty());
    }

    // ── Position queries ──────────────────────────────────────────

    @Test
    void getBlobAtReturnsCorrectId() {
        RoadBlobCache cache = new RoadBlobCache();
        Set<PathPoint> blob1 = Set.of(
                new PathPoint(0, 64, 0),
                new PathPoint(1, 64, 0));
        Set<PathPoint> blob2 = Set.of(
                new PathPoint(10, 65, 10),
                new PathPoint(11, 65, 10));

        UUID id1 = cache.addBlob(blob1);
        UUID id2 = cache.addBlob(blob2);

        assertEquals(id1, cache.getBlobAt(new PathPoint(0, 64, 0)));
        assertEquals(id2, cache.getBlobAt(new PathPoint(10, 65, 10)));
        assertNull(cache.getBlobAt(new PathPoint(100, 70, 100)));
    }

    @Test
    void overlappingBlobsFirstWriterWins() {
        RoadBlobCache cache = new RoadBlobCache();
        PathPoint shared = new PathPoint(0, 64, 0);
        UUID id1 = cache.addBlob(Set.of(shared, new PathPoint(1, 64, 0)));
        UUID id2 = cache.addBlob(Set.of(shared, new PathPoint(2, 64, 0)));

        // First blob owns the shared position
        assertEquals(id1, cache.getBlobAt(shared));
        // Total should count deduplicated
        assertEquals(3, cache.totalBlockCount());
    }

    // ── Boundary computation ──────────────────────────────────────

    @Test
    void boundaryOfSingleBlockBlob() {
        RoadBlobCache cache = new RoadBlobCache();
        UUID id = cache.addBlob(Set.of(new PathPoint(0, 64, 0)));

        Set<PathPoint> boundary = cache.getBoundaryPoints(id);
        assertEquals(1, boundary.size());
        assertTrue(boundary.contains(new PathPoint(0, 64, 0)));
    }

    @Test
    void boundaryOfLineBlob() {
        RoadBlobCache cache = new RoadBlobCache();
        Set<PathPoint> blocks = Set.of(
                new PathPoint(0, 64, 0),
                new PathPoint(1, 64, 0),
                new PathPoint(2, 64, 0),
                new PathPoint(3, 64, 0),
                new PathPoint(4, 64, 0));

        UUID id = cache.addBlob(blocks);

        Set<PathPoint> boundary = cache.getBoundaryPoints(id);
        // A 1D line has no neighboring blocks in Z — ALL blocks are boundary.
        // Every point has at least one cardinal direction unoccupied.
        assertEquals(5, boundary.size());
        assertTrue(boundary.contains(new PathPoint(0, 64, 0)));
        assertTrue(boundary.contains(new PathPoint(2, 64, 0)));  // middle IS boundary
        assertTrue(boundary.contains(new PathPoint(4, 64, 0)));
    }

    @Test
    void boundaryOfSquareBlob() {
        RoadBlobCache cache = new RoadBlobCache();
        // 3x3 square
        Set<PathPoint> blocks = new java.util.HashSet<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                blocks.add(new PathPoint(x, 64, z));
            }
        }
        UUID id = cache.addBlob(blocks);

        Set<PathPoint> boundary = cache.getBoundaryPoints(id);
        // Center (1,64,1) is NOT boundary — surrounded on all 4 sides
        assertFalse(boundary.contains(new PathPoint(1, 64, 1)));
        // Corner (0,64,0) IS boundary
        assertTrue(boundary.contains(new PathPoint(0, 64, 0)));
        // Edge (1,64,0) IS boundary
        assertTrue(boundary.contains(new PathPoint(1, 64, 0)));
    }

    @Test
    void boundaryCachedOnSecondCall() {
        RoadBlobCache cache = new RoadBlobCache();
        UUID id = cache.addBlob(Set.of(
                new PathPoint(0, 64, 0),
                new PathPoint(1, 64, 0)));

        Set<PathPoint> b1 = cache.getBoundaryPoints(id);
        Set<PathPoint> b2 = cache.getBoundaryPoints(id);
        assertSame(b1, b2); // same object — cached
    }

    @Test
    void boundaryUnknownIdReturnsEmpty() {
        RoadBlobCache cache = new RoadBlobCache();
        assertTrue(cache.getBoundaryPoints(UUID.randomUUID()).isEmpty());
    }

    // ── Nearest point query ───────────────────────────────────────

    @Test
    void findNearestBlobPointBasic() {
        RoadBlobCache cache = new RoadBlobCache();
        cache.addBlob(Set.of(
                new PathPoint(0, 64, 0),
                new PathPoint(10, 64, 0)));

        PathPoint nearest = cache.findNearestBlobPoint(new PathPoint(1, 64, 0));
        assertNotNull(nearest);
        assertEquals(new PathPoint(0, 64, 0), nearest);
    }

    @Test
    void findNearestBlobPointEmptyCacheReturnsNull() {
        RoadBlobCache cache = new RoadBlobCache();
        assertNull(cache.findNearestBlobPoint(new PathPoint(0, 0, 0)));
    }

    @Test
    void findNearestBlobPointPrefersCloseXz() {
        RoadBlobCache cache = new RoadBlobCache();
        cache.addBlob(Set.of(
                new PathPoint(0, 64, 0),
                new PathPoint(100, 64, 0)));

        PathPoint nearest = cache.findNearestBlobPoint(new PathPoint(5, 64, 0));
        assertEquals(new PathPoint(0, 64, 0), nearest);
    }

    @Test
    void findNearestBlobPointSameXzPrefersCloseY() {
        RoadBlobCache cache = new RoadBlobCache();
        cache.addBlob(Set.of(
                new PathPoint(0, 64, 0),
                new PathPoint(0, 70, 0)));

        // Query at (0, 63, 0) — both at XZ=0, but (0,64,0) is closer in Y
        PathPoint nearest = cache.findNearestBlobPoint(new PathPoint(0, 63, 0));
        assertEquals(new PathPoint(0, 64, 0), nearest);
    }

    // ── GetAllBlobs ───────────────────────────────────────────────

    @Test
    void getAllBlobsReturnsUnmodifiableView() {
        RoadBlobCache cache = new RoadBlobCache();
        cache.addBlob(Set.of(new PathPoint(0, 64, 0)));
        var blobs = cache.getAllBlobs();
        assertEquals(1, blobs.size());
        assertThrows(UnsupportedOperationException.class,
                () -> blobs.put(UUID.randomUUID(), Set.of()));
    }

    // ── Edge cases ───────────────────────────────────────────────

    @Test
    void blobWithVerticalBlocks() {
        RoadBlobCache cache = new RoadBlobCache();
        // A staircase blob — 3 blocks going up
        Set<PathPoint> blocks = Set.of(
                new PathPoint(0, 64, 0),
                new PathPoint(1, 65, 0),
                new PathPoint(2, 66, 0));
        UUID id = cache.addBlob(blocks);

        assertEquals(3, cache.totalBlockCount());
        Set<PathPoint> boundary = cache.getBoundaryPoints(id);
        // All 3 should be boundary (disconnected in XZ)
        assertEquals(3, boundary.size());
    }

    @Test
    void largeBlobDoesNotThrow() {
        RoadBlobCache cache = new RoadBlobCache();
        Set<PathPoint> blocks = new java.util.HashSet<>();
        // 40x40 = 1600 blocks (under MAX_BLOB_SIZE)
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 40; z++) {
                blocks.add(new PathPoint(x, 64, z));
            }
        }
        UUID id = cache.addBlob(blocks);
        assertEquals(1600, cache.totalBlockCount());
        // Boundary of 40x40: 4*40 - 4 = 156 (perimeter minus corners double-count)
        Set<PathPoint> boundary = cache.getBoundaryPoints(id);
        assertTrue(boundary.size() > 0);
        assertTrue(boundary.size() < 1600);
    }

    @Test
    void toStringNonEmpty() {
        RoadBlobCache cache = new RoadBlobCache();
        cache.addBlob(Set.of(new PathPoint(0, 64, 0)));
        String s = cache.toString();
        assertTrue(s.contains("RoadBlobCache"));
        assertTrue(s.contains("blobs=1"));
    }
}
