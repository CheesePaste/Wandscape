package com.wsteam.wandscape.engine.road;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.road.XZPoint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds road tile data from path coordinates using MC level access.
 * Computes terrain height per tile, filters impassable positions,
 * and selects the correct block for surface vs intersection tiles.
 */
public final class RoadBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int ROAD_WIDTH = 2;

    private RoadBuilder() {}

    /**
     * Build a JsonArray of tiles ready for the road:build_segment blueprint.
     * Each tile is: {@code {"pos": [x, y, z], "block": "minecraft:..."}}.
     *
     * <p>For width=2, each path point generates a second tile offset
     * perpendicular to the travel direction.
     *
     * @param level         the server level
     * @param path          ordered XZ path points (1-wide center line, already deduped)
     * @param tier          road tier name (e.g. "dirt")
     * @param intersections set of XZ points that are crossroads
     */
    public static JsonArray buildTiles(Level level, List<XZPoint> path,
                                        String tier, Set<XZPoint> intersections) {
        RoadConfig config = RoadConfig.getInstance();
        String surfaceBlock = config.getSurfaceBlock(tier);
        String intersectionBlock = config.getIntersectionBlock(tier);

        JsonArray tiles = new JsonArray();
        for (int i = 0; i < path.size(); i++) {
            XZPoint p = path.get(i);

            // Determine perpendicular offset from travel direction
            int perpDx = 0, perpDz = 0;
            if (path.size() == 1) {
                perpDz = 1;
            } else {
                boolean moveX = false, moveZ = false;
                if (i > 0) {
                    XZPoint prev = path.get(i - 1);
                    if (prev.x() != p.x()) moveX = true;
                    if (prev.z() != p.z()) moveZ = true;
                }
                if (i < path.size() - 1) {
                    XZPoint next = path.get(i + 1);
                    if (next.x() != p.x()) moveX = true;
                    if (next.z() != p.z()) moveZ = true;
                }
                if (moveX && !moveZ) {
                    perpDz = 1; // X segment → offset Z
                } else if (moveZ && !moveX) {
                    perpDx = 1; // Z segment → offset X
                }
            }

            // Main tile + width offset tiles
            List<BlockPos> positions = new ArrayList<>();
            positions.add(new BlockPos(p.x(), 0, p.z())); // Y filled below
            for (int w = 1; w < ROAD_WIDTH; w++) {
                positions.add(new BlockPos(p.x() + perpDx * w, 0, p.z() + perpDz * w));
            }

            for (BlockPos basePos : positions) {
                int groundY = terrainHeightAt(level, basePos.getX(), basePos.getZ());
                BlockPos pos = new BlockPos(basePos.getX(), groundY, basePos.getZ());

                if (!isPassable(pos, level)) continue;

                boolean isIntersection = intersections.contains(p);
                String block = isIntersection ? intersectionBlock : surfaceBlock;

                JsonObject tile = new JsonObject();
                JsonArray posArr = new JsonArray();
                posArr.add(pos.getX());
                posArr.add(pos.getY());
                posArr.add(pos.getZ());
                tile.add("pos", posArr);
                tile.addProperty("block", block);
                tiles.add(tile);
            }
        }

        return tiles;
    }

    /**
     * Find the ground surface Y at the given XZ column.
     * Scans downward from the world max height, stopping at the first
     * non-air, non-liquid block. Returns the Y coordinate one above it.
     */
    public static int terrainHeightAt(Level level, int x, int z) {
        int maxY = level.getMaxBuildHeight();
        int minY = level.getMinBuildHeight();

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos(x, maxY, z);
        while (mpos.getY() > minY) {
            mpos.setY(mpos.getY() - 1);
            BlockState state = level.getBlockState(mpos);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                return mpos.getY() + 1;
            }
        }
        return minY + 1;
    }

    /**
     * Check whether a road tile can be placed at the given position.
     * Rejects water, lava, and solid blocks.
     * Only air (or air-like replaceable blocks) is considered passable.
     */
    public static boolean isPassable(BlockPos pos, Level level) {
        BlockState state = level.getBlockState(pos);
        return (state.isAir() || state.canBeReplaced())
                && state.getFluidState().isEmpty();
    }
}
