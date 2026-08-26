package com.wsteam.wandscape.engine.nav;

/**
 * Pure-Java terrain queries for choosing safe, standable surface positions.
 *
 * <p>Used by the teleport landing search ({@code WandscapeRitualOps}) and the
 * navigation-target Y snap ({@code WandscapeMovementOps}). The only world
 * interaction is through the minimal {@link TerrainView}, implemented by the
 * engine layer over a real {@code ServerLevel}; this class holds no MC types,
 * so the geometric decisions are unit-testable in a plain JVM.
 *
 * <p>Two strength levels:
 * <ul>
 *   <li>{@link #isStandable} — feet &amp; head clear, non-liquid, ground has a
 *       collision shape. Enough to stand and walk; tolerates slabs and thin roofs.</li>
 *   <li>{@link #isSafeLanding} — additionally requires two full solid blocks
 *       directly below (no thin plates / slabs over a drop). Used for teleport
 *       landings so the NPC never appears floating over a cave.</li>
 * </ul>
 *
 * <p>The Y convention is <b>feet-Y</b>: the Y passed to {@link #isStandable} /
 * {@link #isSafeLanding} is the bottom of an entity standing at that spot, and
 * {@link TerrainView#surfaceY} returns the first air block above the column's
 * top solid block (i.e. the feet-Y of someone standing on that surface).
 */
public final class StandableTerrain {

    private StandableTerrain() {}

    /** Minimal world view needed to decide standability (implemented via {@code ServerLevel}). */
    public interface TerrainView {
        boolean isLoaded(int x, int y, int z);

        /** True when the block has a non-empty collision shape (blocks movement). */
        boolean isBlocking(int x, int y, int z);

        boolean isLiquid(int x, int y, int z);

        /** True when the block is a full solid cube ({@code BlockState#isSolid}). */
        boolean isSolid(int x, int y, int z);

        /** Topmost standable feet-Y of the column (first air above the top solid), or the column floor. */
        int surfaceY(int x, int z);
    }

    /** Basic standability: feet &amp; head clear, non-liquid, ground has a collision shape. */
    public static boolean isStandable(TerrainView v, int x, int y, int z) {
        if (!v.isLoaded(x, y, z) || !v.isLoaded(x, y + 1, z) || !v.isLoaded(x, y - 1, z)) return false;
        if (v.isLiquid(x, y, z) || v.isLiquid(x, y + 1, z)) return false;
        if (v.isBlocking(x, y, z) || v.isBlocking(x, y + 1, z)) return false;
        return v.isBlocking(x, y - 1, z);
    }

    /**
     * Teleport-grade landing check: feet &amp; head clear, non-liquid, and (when
     * {@code requireGround}) two full solid blocks directly below the feet.
     */
    public static boolean isSafeLanding(TerrainView v, int x, int y, int z, boolean requireGround) {
        if (!v.isLoaded(x, y, z) || !v.isLoaded(x, y + 1, z)) return false;
        if (v.isLiquid(x, y, z) || v.isLiquid(x, y + 1, z)) return false;
        if (v.isBlocking(x, y, z) || v.isBlocking(x, y + 1, z)) return false;
        if (requireGround) {
            if (!v.isLoaded(x, y - 1, z) || !v.isLoaded(x, y - 2, z)) return false;
            if (!v.isSolid(x, y - 1, z) || !v.isSolid(x, y - 2, z)) return false;
        }
        return true;
    }

    /** Offset preference when searching for a standing Y near a requested Y (small displacement first). */
    private static final int[] Y_OFFSETS = {0, 1, -1, 2, -2, 3, -3, 4};

    /**
     * Nearest standable feet-Y near {@code nearY}: returns {@code nearY} if it is
     * already standable, else the nearest small Y offset that is, else the column's
     * top surface, else {@code null}. Keeping the original when {@code nearY} and its
     * small offsets already work preserves intentional choices (e.g. a guard engaging
     * on a roof-floor, an underground cave floor); only genuinely bad targets (Y inside
     * terrain) snap up to the real surface. {@code null} means "nothing standable here" —
     * the caller should fall through to the raw target rather than invent a position.
     */
    public static Integer nearestStandableY(TerrainView v, int x, int nearY, int z) {
        for (int dy : Y_OFFSETS) {
            if (isStandable(v, x, nearY + dy, z)) return nearY + dy;
        }
        int top = v.surfaceY(x, z);
        if (isStandable(v, x, top, z)) return top;
        return null;
    }

    /**
     * Nearest safe landing (each column snapped to its <b>top</b> surface, never its
     * cavity) within radii {@code minR..maxR}. Returns {@code {x+0.5, y, z+0.5}} (feet-Y)
     * or {@code null} if no column offers a two-solid-ground surface. Never returns the
     * raw target, so a target inside a wall cannot be chosen as a landing.
     */
    public static double[] findSafeLanding(TerrainView v, int tx, int ty, int tz, int minR, int maxR) {
        double[] best = null;
        int bestSq = Integer.MAX_VALUE;
        for (int r = minR; r <= maxR; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = tx + dx;
                    int z = tz + dz;
                    int y = v.surfaceY(x, z);
                    if (!isSafeLanding(v, x, y, z, true)) continue;
                    int sq = dx * dx + dz * dz;
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = new double[] {x + 0.5, y, z + 0.5};
                    }
                }
            }
            if (best != null) break; // nearest ring found
        }
        return best;
    }

    /**
     * Escape-teleport landing: search near {@code originY} (same floor) within a square
     * ring of radii {@code minR..maxR}, preferring two-solid ground, and only falling
     * back to "feet/head clear" (which still rejects liquid) so a lava/void escape is not
     * blocked by an absent floor. Returns {@code {x+0.5, y, z+0.5}} or {@code null}.
     */
    public static double[] findSafeEscapeLanding(TerrainView v, int ox, int oy, int oz, int minR, int maxR) {
        for (int r = minR; r <= maxR; r++) {
            double[] spot = scanShellNearY(v, ox, oy, oz, r, true);
            if (spot != null) return spot;
        }
        for (int r = minR; r <= maxR; r++) {
            double[] spot = scanShellNearY(v, ox, oy, oz, r, false);
            if (spot != null) return spot;
        }
        return null;
    }

    /** Scan a square ring radius {@code r}, testing feet positions near {@code originY}. */
    private static double[] scanShellNearY(TerrainView v, int ox, int oy, int oz, int r, boolean requireGround) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int x = ox + dx;
                int z = oz + dz;
                for (int dy = 0; dy <= 4; dy++) {
                    int y = oy + dy;
                    if (isSafeLanding(v, x, y, z, requireGround)) {
                        return new double[] {x + 0.5, y, z + 0.5};
                    }
                }
                for (int dy = -1; dy >= -3; dy--) {
                    int y = oy + dy;
                    if (isSafeLanding(v, x, y, z, requireGround)) {
                        return new double[] {x + 0.5, y, z + 0.5};
                    }
                }
            }
        }
        return null;
    }
}
