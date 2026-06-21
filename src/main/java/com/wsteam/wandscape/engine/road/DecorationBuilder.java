package com.wsteam.wandscape.engine.road;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.road.DecorationPoint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Converts {@link DecorationPoint}s into tile JSON for the
 * {@code road:build_decoration} blueprint.
 *
 * <p>Each decoration type expands to one or more blocks:
 * <ul>
 *   <li><b>lamp</b> — fence post at ground + lantern on top</li>
 *   <li><b>bench</b> — stairs block with {@code facing} state</li>
 * </ul>
 *
 * <p>Terrain is validated per-point: must have solid ground,
 * no water, and not intersect any building bounding box.
 */
public final class DecorationBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DecorationBuilder() {}

    /**
     * Build tile JSON for the given decoration points.
     *
     * @param points         decoration placements from {@code DecorationPlanner}
     * @param level          the server level
     * @param buildingBounds building bounding boxes to avoid
     * @param config         road configuration (block ids, etc.)
     * @return JsonArray of {@code {pos, block}} tiles, may be empty
     */
    public static JsonArray buildTiles(List<DecorationPoint> points,
                                        Level level,
                                        Collection<BoundingBox> buildingBounds,
                                        RoadConfig config) {
        JsonArray tiles = new JsonArray();
        RoadConfig.DecorationConfig deco = config.getDecorationConfig();

        for (DecorationPoint pt : points) {
            // Decoration sits at the road surface Y (trust the path elevation).
            // The road builder already leveled the roadY — decorations align to it,
            // not to the original terrain surface.
            int roadY = pt.y();

            // Validate that the road surface block exists here (solid, non-fluid)
            BlockPos roadSurface = new BlockPos(pt.x(), roadY, pt.z());
            BlockState surfaceState = level.getBlockState(roadSurface);
            if (!surfaceState.isSolid() || !surfaceState.getFluidState().isEmpty()) {
                LOGGER.debug("[Deco] road surface missing or water at ({},{},{}) — skip",
                        pt.x(), roadY, pt.z());
                continue;
            }

            // Building bounds: check full height [roadY, roadY+2]
            if (columnIntersectsBuilding(pt.x(), roadY, roadY + 2, pt.z(), buildingBounds)) continue;

            switch (pt.type()) {
                case "lamp" -> {
                    tiles.add(makeTile(pt.x(), roadY + 1, pt.z(), deco.lampPost()));
                    tiles.add(makeTile(pt.x(), roadY + 2, pt.z(), deco.lampLight()));
                }
                case "bench" -> {
                    String faced = deco.benchBlock() + "[facing=" + pt.facing() + "]";
                    tiles.add(makeTile(pt.x(), roadY + 1, pt.z(), faced));
                }
                default -> LOGGER.warn("[Deco] unknown decoration type '{}' at ({},{},{})",
                        pt.type(), pt.x(), roadY, pt.z());
            }
        }

        return tiles;
    }

    private static JsonObject makeTile(int x, int y, int z, String block) {
        JsonObject tile = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(x); arr.add(y); arr.add(z);
        tile.add("pos", arr);
        tile.addProperty("block", block);
        return tile;
    }

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
