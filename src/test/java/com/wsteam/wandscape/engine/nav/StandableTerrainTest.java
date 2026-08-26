package com.wsteam.wandscape.engine.nav;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-JVM regression tests for {@link StandableTerrain}.
 *
 * <p>Guards the "never land on a cave roof / floating plate / wall interior" contract that
 * fixed the NPC teleport loop &amp; fall-death bug (see {@code docs/decisions.md}). Uses a tiny
 * fake {@link StandableTerrain.TerrainView} over an in-memory block grid — no MC runtime.
 */
public class StandableTerrainTest {

    /** Block kinds distinguishable by the terrain predicates. */
    enum B {
        AIR(false, false, false),
        SOLID(true, true, false),   // full cube: blocking + isSolid
        THIN(true, false, false);   // slab / 1-block roof: blocking, NOT isSolid
        final boolean blocking, solid, liquid;
        B(boolean blocking, boolean solid, boolean liquid) {
            this.blocking = blocking;
            this.solid = solid;
            this.liquid = liquid;
        }
    }

    /** Minimal in-memory {@link TerrainView}: columns are block grids; surfaceY = first air above top solid. */
    static final class FakeTerrain implements StandableTerrain.TerrainView {
        record Pos(int x, int y, int z) {}
        final Map<Pos, B> blocks = new HashMap<>();
        int topY = 127, botY = 0;

        void set(int x, int y, int z, B b) { blocks.put(new Pos(x, y, z), b); }
        void fillSolid(int x0, int x1, int z0, int z1, int y0, int y1) {
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++)
                    for (int y = y0; y <= y1; y++) set(x, y, z, B.SOLID);
        }
        private B at(int x, int y, int z) { return blocks.getOrDefault(new Pos(x, y, z), B.AIR); }

        @Override public boolean isLoaded(int x, int y, int z) { return true; }
        @Override public boolean isBlocking(int x, int y, int z) { return at(x, y, z).blocking; }
        @Override public boolean isLiquid(int x, int y, int z) { return at(x, y, z).liquid; }
        @Override public boolean isSolid(int x, int y, int z) { return at(x, y, z).solid; }
        @Override public int surfaceY(int x, int z) {
            for (int y = topY; y >= botY; y--) {
                if (at(x, y, z).blocking || at(x, y, z).liquid) return y + 1;
            }
            return botY;
        }
    }

    /** A target deep inside solid rock snaps up to the real surface (the reported bug). */
    @Test
    void wallTarget_snapsToSurface() {
        FakeTerrain t = new FakeTerrain();
        t.fillSolid(0, 0, 0, 0, 0, 90);            // solid mountain, surface feet at y=91
        Integer standY = StandableTerrain.nearestStandableY(t, 0, 40, 0);
        assertEquals(91, standY, "deep-in-rock target snaps up to the real surface");
        double[] landing = StandableTerrain.findSafeLanding(t, 0, 40, 0, 0, 4);
        assertNotNull(landing, "a landing is found on the surface");
        assertEquals(91, landing[1], "landing on the surface, not inside the rock");
        assertEquals(0.5, landing[0], 1e-6);
        assertEquals(0.5, landing[2], 1e-6);
    }

    /** A good standing Y is preserved (no yank) so guard/underground targets stay put. */
    @Test
    void alreadyStandable_isUnchanged() {
        FakeTerrain t = new FakeTerrain();
        t.fillSolid(5, 5, 7, 7, 0, 63);            // ground top at y=63 → feet at 64
        Integer standY = StandableTerrain.nearestStandableY(t, 5, 64, 7);
        assertEquals(64, standY, "a good standing Y is preserved (no snap)");
    }

    /** A standable cave floor keeps its Y instead of being yanked to the surface above. */
    @Test
    void caveFloor_keptInsteadOfSnappingToSurface() {
        FakeTerrain t = new FakeTerrain();
        t.fillSolid(1, 1, 1, 1, 0, 63);            // solid up to 63
        for (int y = 56; y < 63; y++) t.set(1, y, 1, B.AIR); // carve cave, leaving floor at 55
        Integer standY = StandableTerrain.nearestStandableY(t, 1, 56, 1);
        assertEquals(56, standY, "standable cave floor kept (offset 0 wins over surface snap)");
    }

    /** A 1-block roof over air is NOT a safe teleport landing (would fall into the cave). */
    @Test
    void thinRoof_rejected() {
        FakeTerrain t = new FakeTerrain();
        t.set(2, 64, 2, B.SOLID);                   // roof block
        // y=65 air, y=66 air (feet at 65); below roof y=63 is air (cave)
        assertFalse(StandableTerrain.isSafeLanding(t, 2, 65, 2, true),
                "1-block roof over air is not a two-solid-ground landing");
        // Every column in range shares the same thin-roof/air profile → no landing at all.
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++)
                t.set(2 + dx, 64, 2 + dz, B.SOLID);
        double[] landing = StandableTerrain.findSafeLanding(t, 2, 64, 2, 0, 4);
        assertNull(landing, "no two-solid-ground surface anywhere → no landing");
    }

    /** An all-air column (hollow / open shaft) offers no two-solid surface. */
    @Test
    void noGround_returnsNull() {
        FakeTerrain t = new FakeTerrain();           // everything is air
        double[] landing = StandableTerrain.findSafeLanding(t, 0, 64, 0, 0, 6);
        assertNull(landing, "a hollow column provides no two-solid surface");
    }

    /** Escape landing finds a nearby ground ring rather than the origin. */
    @Test
    void escapeLanding_findsNearbyGround() {
        FakeTerrain t = new FakeTerrain();
        // Solid ground (2 deep) on the ring r=4..8; origin column stays air/lava.
        for (int r = 4; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    t.set(10 + dx, 63, 10 + dz, B.SOLID);
                    t.set(10 + dx, 62, 10 + dz, B.SOLID);
                }
            }
        }
        double[] landing = StandableTerrain.findSafeEscapeLanding(t, 10, 64, 10, 4, 8);
        assertNotNull(landing, "escape finds a nearby ground ring");
        assertEquals(64, landing[1], 1e-6, "feet on the r=4 ring surface");
        int dx = (int) Math.round(landing[0] - 0.5 - 10);
        int dz = (int) Math.round(landing[2] - 0.5 - 10);
        assertEquals(4, Math.max(Math.abs(dx), Math.abs(dz)),
                "lands on the r=4 ring (nearest ground), not the origin");
    }
}
