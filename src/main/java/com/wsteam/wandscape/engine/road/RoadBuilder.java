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
 *   <li>Expands each center-line point via circular brush
 *       (overlapping filled circles eliminate corner gaps)</li>
 *   <li>Bridges water with oak planks at water surface level</li>
 *   <li>Picks surface blocks from a weighted palette with
 *       position-based deterministic randomness</li>
 *   <li>Excavates 2-block headroom when road is below terrain</li>
 *   <li>Sparse viaduct pillars below elevated road, stopping
 *       when any lower road's headroom is touched</li>
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
     * @param occupiedTiles  mutable set of already-claimed 3D positions;
     *                       updated in-place with all tiles placed by this call
     * @param roadWidth      road width in blocks (e.g. 3 = 3-wide, halfWidth = 1)
     */
    public static JsonArray buildTiles(Level level, List<PathPoint> path,
                                       String tier,
                                       Collection<BoundingBox> buildingBounds,
                                       Set<PathPoint> occupiedTiles,
                                       int roadWidth) {
        RoadConfig config = RoadConfig.getInstance();
        List<RoadConfig.WeightedBlock> palette = config.getSurfacePalette();
        int r = roadWidth / 2;
        int rSq = r * r;
        int n = path.size();

        JsonArray tiles = new JsonArray();
        int pillarsPlaced = 0;

        for (int i = 0; i < n; i++) {
            PathPoint p = path.get(i);
            int px = p.x();
            int pz = p.z();
            boolean isSameXzStep = i > 0 && path.get(i - 1).xz().equals(p.xz());

            // 用于记录当前中心点的环境数据，供给循环外的柱子逻辑使用
            int centerTerrainY = 0;
            int centerActualY = p.y();
            boolean centerIsWater = false;
            boolean centerProcessed = false;

            // 1. 表面铺设与净空挖掘 (Circular brush)
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > rSq + 1) continue;

                    int tx = px + dx;
                    int tz = pz + dz;
                    int roadY = p.y();
                    int terrainY = level.getHeight(Heightmap.Types.WORLD_SURFACE, tx, tz);

                    // ── 建筑边界检查 ──
                    int colMinY = Math.min(roadY, terrainY);
                    int colMaxY = Math.max(roadY + 2, terrainY);
                    if (columnIntersectsBuilding(tx, colMinY, colMaxY, tz, buildingBounds)) continue;

                    BlockPos roadPos = new BlockPos(tx, roadY, tz);
                    BlockState roadState = level.getBlockState(roadPos);
                    boolean isWater = !roadState.getFluidState().isEmpty();
                    String block;

                    if (isWater) {
                        int waterSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, tx, tz);
                        roadPos = new BlockPos(tx, waterSurfaceY, tz);
                        block = "minecraft:oak_planks";
                    } else {
                        block = pickFromPalette(palette, tx, tz);
                    }
                    int actualY = roadPos.getY();

                    // 【记录中心点信息】：避开 continue 的拦截
                    if (dx == 0 && dz == 0) {
                        centerTerrainY = terrainY;
                        centerActualY = actualY;
                        centerIsWater = isWater;
                        centerProcessed = true; // 标记中心点未被建筑物阻挡
                    }

                    // ── 3D occupancy check (去重拦截) ──
                    if (!isSameXzStep) {
                        PathPoint surfacePt = new PathPoint(tx, actualY, tz);
                        // 就是这里的 continue 拦截了原来的柱子逻辑
                        if (occupiedTiles.contains(surfacePt)) continue;
                        occupiedTiles.add(surfacePt);
                    }

                    tiles.add(makeTile(roadPos, block));

                    // ── 挖掘 (Excavation) ──
                    int terrainTop = terrainY - 1;
                    if (!isWater && actualY < terrainTop) {
                        int cutDepth = terrainTop - actualY;
                        int maxCut = config.getMaxCutDepth();
                        if (maxCut > 0 && cutDepth > maxCut) {
                            LOGGER.warn("[Road] Cut depth {} exceeds maxCutDepth {} at ({},{},{})",
                                    cutDepth, maxCut, tx, actualY, tz);
                        }
                    }
                    int clearTop = Math.max(actualY + 2, Math.min(terrainY, actualY + 3));
                    for (int hy = actualY + 1; hy <= clearTop; hy++) {
                        PathPoint headPt = new PathPoint(tx, hy, tz);
                        if (occupiedTiles.contains(headPt)) continue;
                        BlockPos headPos = new BlockPos(tx, hy, tz);
                        BlockState headState = level.getBlockState(headPos);
                        if (!headState.isAir()
                                && headState.getFluidState().isEmpty()
                                && !headState.is(Blocks.BEDROCK)) {
                            tiles.add(makeTile(headPos, "minecraft:air"));
                            occupiedTiles.add(headPt);
                        }
                    }
                }
            }

            // ========================================================
            // 2. 桥墩柱子生成 (独立于笔刷外，彻底解决 continue 拦截问题)
            // ========================================================
            if (centerProcessed && config.isPillarEnabled() && !centerIsWater && centerActualY > centerTerrainY) {
                int spacing = config.getPillarSpacing();
                // 确保 spacing > 0，防止除以零崩溃
                if (spacing > 0 && (i % spacing == 0) && (i > 1 && i < n - 2)) {
                    String pillarBlock = config.getPillarBlock();
                    int placed = 0;

                    // 从道路下方一格开始向下延伸射线
                    for (int fy = centerActualY - 1; fy >= centerTerrainY; fy--) {
                        PathPoint pillarPt = new PathPoint(px, fy, pz);

                        // 防冲突核心：如果碰到其他道路（或自己底下的道路），立刻停止！
                        if (occupiedTiles.contains(pillarPt)) break;

                        BlockPos pillarPos = new BlockPos(px, fy, pz);
                        tiles.add(makeTile(pillarPos, pillarBlock));
                        occupiedTiles.add(pillarPt);
                        placed++;
                    }
                    pillarsPlaced += placed;
                }
            }
        }

        if (pillarsPlaced > 0) {
            LOGGER.info("[Road] buildTiles: {} pillars placed ({} path points, width={})",
                    pillarsPlaced, n, roadWidth);
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
        // Splitmix64-style hash: adjacent tiles decorrelate fully.
        // Old ((long)x*31+z)^0x3A7F caused visible 3-tile repeating stripes.
        long h = ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC6A4A7935BD1E995L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = h ^ (h >>> 27);
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

    /** Extract 3D path points from tile JSON for occupancy tracking. */
    public static Set<PathPoint> extractPathPoints(JsonArray tiles) {
        Set<PathPoint> result = new HashSet<>();
        for (int i = 0; i < tiles.size(); i++) {
            var t = tiles.get(i).getAsJsonObject().getAsJsonArray("pos");
            result.add(new PathPoint(
                    t.get(0).getAsInt(), t.get(1).getAsInt(), t.get(2).getAsInt()));
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
