package com.wsteam.wandscape.engine.road;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.XZPoint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Builds road tile data from 3D path coordinates.
 *
 * <p>Each path point carries its own Y (pre-computed by
 * {@code PathGenerator.lShape3D}). The builder is the
 * <strong>executor</strong> — it trusts the path Y unconditionally
 * and reshapes terrain to match:
 * <ul>
 *   <li>Expands each center-line point to 3-wide</li>
 *   <li>Bridges water with oak planks at water surface level</li>
 *   <li>Picks surface blocks from a weighted palette with
 *       position-based deterministic randomness</li>
 *   <li>Excavates 2-block headroom when road is below terrain</li>
 *   <li>Fills support blocks when road is above terrain</li>
 * </ul>
 */
public final class RoadBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RoadBuilder() {}

    /**
     * Build a JsonArray of tiles for the road:build_segment blueprint.
     *
     * @param level          the server level
     * @param path           3D path points with pre-computed Y
     * @param tier           road tier name (e.g. "dirt") — currently unused;
     *                       all surfaces use the TOML palette
     * @param buildingBounds building bounding boxes to avoid
     * @param occupiedTiles  mutable set of already-claimed XZ positions;
     *                       updated in-place
     */
    public static JsonArray buildTiles(Level level, List<PathPoint> path,
                                        String tier,
                                        Collection<BoundingBox> buildingBounds,
                                        Set<XZPoint> occupiedTiles) {
        RoadConfig config = RoadConfig.getInstance();
        List<RoadConfig.WeightedBlock> palette = config.getSurfacePalette();
        int halfWidth = config.getDefaultWidth() / 2;
        int n = path.size();

        JsonArray tiles = new JsonArray();
        int prevPerpDx = 0, prevPerpDz = 1;

        for (int i = 0; i < n; i++) {
            PathPoint p = path.get(i);
            int perpDx = 0, perpDz = 0;

            if (n == 1) {
                perpDz = 1;
            } else {
                boolean moveX = false, moveZ = false;
                if (i > 0) {
                    PathPoint prev = path.get(i - 1);
                    if (prev.x() != p.x()) moveX = true;
                    if (prev.z() != p.z()) moveZ = true;
                }
                if (i < n - 1) {
                    PathPoint next = path.get(i + 1);
                    if (next.x() != p.x()) moveX = true;
                    if (next.z() != p.z()) moveZ = true;
                }
                if (moveX && !moveZ) {
                    perpDz = 1;
                } else if (moveZ && !moveX) {
                    perpDx = 1;
                } else {
                    perpDx = prevPerpDx;
                    perpDz = prevPerpDz;
                }
            }
            prevPerpDx = perpDx;
            prevPerpDz = perpDz;

            for (int w = -halfWidth; w <= halfWidth; w++) {
                int tx = p.x() + perpDx * w;
                int tz = p.z() + perpDz * w;

                XZPoint tileXz = new XZPoint(tx, tz);
                boolean isSameXzStep = i > 0 && path.get(i - 1).xz().equals(p.xz());

                if (!isSameXzStep) {
                    if (occupiedTiles.contains(tileXz)) continue;
                }

                int roadY = p.y();
                int terrainY = level.getHeight(Heightmap.Types.WORLD_SURFACE, tx, tz);

                // ── Building boundary check (full affected Y column) ──
                int colMinY = Math.min(roadY, terrainY);
                int colMaxY = Math.max(roadY + 2, terrainY);
                if (columnIntersectsBuilding(tx, colMinY, colMaxY, tz, buildingBounds)) continue;

                BlockPos roadPos = new BlockPos(tx, roadY, tz);
                BlockState roadState = level.getBlockState(roadPos);
                boolean isWater = !roadState.getFluidState().isEmpty();
                String block;

                if (isWater) {
                    // Water: raise to surface + plank bridge
                    int waterSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, tx, tz);
                    roadPos = new BlockPos(tx, waterSurfaceY, tz);
                    block = "minecraft:oak_planks";
                } else {
                    // Weighted random pick, deterministic per position
                    block = pickFromPalette(palette, tx, tz);
                }
                int actualY = roadPos.getY();

                if (!isSameXzStep) {
                    occupiedTiles.add(tileXz);
                }

                tiles.add(makeTile(roadPos, block));

                // Excavation: clear at least 2-block walkable headroom above road.
                int terrainTop = terrainY - 1;
                if (!isWater && actualY < terrainTop) {
                    int cutDepth = terrainTop - actualY;
                    int maxCut = config.getMaxCutDepth();
                    if (maxCut > 0 && cutDepth > maxCut) {
                        LOGGER.warn("[Road] Cut depth {} exceeds maxCutDepth {} at ({},{},{})",
                                cutDepth, maxCut, tx, actualY, tz);
                    }
                }
                int clearTop = Math.max(actualY + 2, terrainY);
                for (int hy = actualY + 1; hy <= clearTop; hy++) {
                    BlockPos headPos = new BlockPos(tx, hy, tz);
                    BlockState headState = level.getBlockState(headPos);
                    if (!headState.isAir()
                            && headState.getFluidState().isEmpty()
                            && !headState.is(Blocks.BEDROCK)) {
                        tiles.add(makeTile(headPos, "minecraft:air"));
                    }
                }

                // Fill: add support below road when road is above terrain
                if (!isWater && actualY > terrainY) {
                    int fillHeight = actualY - terrainY;
                    int maxFill = config.getMaxFillHeight();
                    if (maxFill > 0 && fillHeight > maxFill) {
                        LOGGER.warn("[Road] Fill height {} exceeds maxFillHeight {} at ({},{},{})",
                                fillHeight, maxFill, tx, actualY, tz);
                    }
                    for (int fy = terrainY; fy < actualY; fy++) {
                        BlockPos fillPos = new BlockPos(tx, fy, tz);
                        BlockState fillState = level.getBlockState(fillPos);
                        if (fillState.isAir() || !fillState.getFluidState().isEmpty()) {
                            tiles.add(makeTile(fillPos, "minecraft:dirt"));
                        }
                    }
                }
            }
        }

        return tiles;
    }

    // ---- Block selection ----

    /**
     * Pick a block from the weighted palette deterministically,
     * keyed by XZ position so the same coordinate always gets
     * the same block (no flicker on chunk reload).
     */
    static String pickFromPalette(List<RoadConfig.WeightedBlock> palette, int x, int z) {
        int totalWeight = 0;
        for (RoadConfig.WeightedBlock wb : palette) {
            totalWeight += wb.weight();
        }
        long h = ((long) x * 31 + z) ^ 0x3A7F;
        int roll = (int) (Math.abs(h) % totalWeight);
        int cumulative = 0;
        for (RoadConfig.WeightedBlock wb : palette) {
            cumulative += wb.weight();
            if (roll < cumulative) return wb.blockId();
        }
        return palette.get(0).blockId(); // unreachable when totalWeight > 0
    }

    // ---- JSON helpers ----

    private static JsonObject makeTile(BlockPos pos, String block) {
        JsonObject tile = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(pos.getX()); arr.add(pos.getY()); arr.add(pos.getZ());
        tile.add("pos", arr);
        tile.addProperty("block", block);
        return tile;
    }

    /** Extract XZ points from tile JSON for occupancy tracking. */
    public static Set<XZPoint> extractXZ(JsonArray tiles) {
        Set<XZPoint> result = new HashSet<>();
        for (int i = 0; i < tiles.size(); i++) {
            var t = tiles.get(i).getAsJsonObject().getAsJsonArray("pos");
            result.add(new XZPoint(t.get(0).getAsInt(), t.get(2).getAsInt()));
        }
        return result;
    }

    // ---- Helpers ----

    /**
     * Check if a vertical column segment intersects any building's 3D volume.
     */
    private static boolean columnIntersectsBuilding(int x, int yMin, int yMax, int z,
                                                    Collection<BoundingBox> boxes) {
        for (BoundingBox box : boxes) {
            if (x >= box.minX() && x <= box.maxX()
                    && z >= box.minZ() && z <= box.maxZ()
                    && yMax >= box.minY() && yMin <= box.maxY()) {
                return true;
            }
        }
        return false;
    }
}
