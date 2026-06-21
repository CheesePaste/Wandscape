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
 * {@code PathGenerator.lShape3D}). The builder:
 * <ul>
 *   <li>Expands each center-line point to 3-wide</li>
 *   <li>Bridges water with oak planks at water surface level</li>
 *   <li>Applies vanilla-style block variation on land</li>
 *   <li>Excavates 2-block headroom when the road is below terrain</li>
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
     * @param tier           road tier name (e.g. "dirt")
     * @param buildingBounds building bounding boxes to avoid
     * @param occupiedTiles  mutable set of already-claimed XZ positions;
     *                       updated in-place
     */
    public static JsonArray buildTiles(Level level, List<PathPoint> path,
                                        String tier,
                                        Collection<BoundingBox> buildingBounds,
                                        Set<XZPoint> occupiedTiles) {
        RoadConfig config = RoadConfig.getInstance();
        String defaultBlock = config.getDefaultBlock(tier);
        int halfWidth = config.getDefaultWidth() / 2;
        int n = path.size();

        JsonArray tiles = new JsonArray();
        int prevPerpDx = 0, prevPerpDz = 1; // default if first point is stair

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
                    // Stair step or corner: carry forward previous perpendicular
                    perpDx = prevPerpDx;
                    perpDz = prevPerpDz;
                }
            }
            prevPerpDx = perpDx;
            prevPerpDz = perpDz;

            for (int w = -halfWidth; w <= halfWidth; w++) {
                int tx = p.x() + perpDx * w;
                int tz = p.z() + perpDz * w;

                if (insideAnyBuilding(tx, tz, buildingBounds)) continue;

                XZPoint tileXz = new XZPoint(tx, tz);

                // Stair detection: same XZ as previous path point = vertical step.
                // These bypass occupancy check (XZ already claimed by first point in column).
                boolean isStairStep = i > 0 && path.get(i - 1).xz().equals(p.xz());

                if (!isStairStep) {
                    if (occupiedTiles.contains(tileXz)) continue;
                }

                int roadY = p.y();
                BlockPos roadPos = new BlockPos(tx, roadY, tz);
                BlockState roadState = level.getBlockState(roadPos);
                String block;
                int actualY = roadY;

                if (!roadState.getFluidState().isEmpty()) {
                    actualY = level.getHeight(Heightmap.Types.WORLD_SURFACE, tx, tz);
                    roadPos = new BlockPos(tx, actualY, tz);
                    block = "minecraft:oak_planks";
                } else {
                    block = applyVariation(level, roadPos, defaultBlock);
                }

                if (!isStairStep) {
                    occupiedTiles.add(tileXz);
                }

                // Road surface tile
                tiles.add(makeTile(roadPos, block));

                // Excavation: clear 2-block headroom above road
                int terrainSurface = level.getHeight(Heightmap.Types.WORLD_SURFACE, tx, tz);
                for (int hy = actualY + 1; hy <= actualY + 2; hy++) {
                    BlockPos headPos = new BlockPos(tx, hy, tz);
                    if (hy <= terrainSurface) {
                        BlockState headState = level.getBlockState(headPos);
                        if (!headState.isAir()
                                && headState.getFluidState().isEmpty()
                                && !headState.is(Blocks.BEDROCK)) {
                            tiles.add(makeTile(headPos, "minecraft:air"));
                        }
                    }
                }
            }
        }

        return tiles;
    }

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

    // ---- Vanilla-style block variation ----

    static String applyVariation(Level level, BlockPos roadPos, String blockId) {
        if (!"minecraft:dirt_path".equals(blockId)) return blockId;

        BlockPos below = roadPos.below();
        BlockState ground = level.getBlockState(below);

        if (!ground.getFluidState().isEmpty()) {
            return "minecraft:oak_planks";
        }

        String groundName = ground.getBlock().builtInRegistryHolder()
                .key().location().toString();

        if ("minecraft:grass_block".equals(groundName)) {
            long h = ((long) roadPos.getX() * 31 + roadPos.getZ()) ^ 0x3A7F;
            if (Math.abs(h % 100) < 15) {
                return "minecraft:grass_block";
            }
        }

        if ("minecraft:stone".equals(groundName)
                || "minecraft:cobblestone".equals(groundName)) {
            return "minecraft:cobblestone";
        }

        return blockId;
    }

    // ---- Helpers ----

    private static boolean insideAnyBuilding(int x, int z,
                                              Collection<BoundingBox> boxes) {
        for (BoundingBox box : boxes) {
            if (x >= box.minX() && x <= box.maxX()
                    && z >= box.minZ() && z <= box.maxZ()) {
                return true;
            }
        }
        return false;
    }
}
