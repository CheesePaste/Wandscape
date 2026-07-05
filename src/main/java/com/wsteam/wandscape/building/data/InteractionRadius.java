package com.wsteam.wandscape.building.data;

import java.lang.reflect.Type;

import javax.annotation.Nullable;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.wsteam.wandscape.building.data.BuildingConfig.BoundaryBox;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Defines the tourist interaction zone for a building.
 *
 * <p>Three formats supported in JSON:
 * <ul>
 *   <li><b>Uniform</b> (backwards compatible): {@code "interaction_radius": 3}
 *       — expands the building AABB by N blocks in all directions</li>
 *   <li><b>Per-axis</b>: {@code "interaction_radius": {"x": 3, "y": 1, "z": 3}}
 *       — expands per axis independently</li>
 *   <li><b>Explicit box</b> (highest priority): {@code "interaction_radius": {"min": [-5,0,-5], "max": [10,5,10]}}
 *       — absolute box relative to building anchor, {@code min/max} detected wins over other formats</li>
 * </ul>
 */
public record InteractionRadius(
        int uniform,
        @Nullable BlockOffset expand,
        @Nullable BoundaryBox area
) {
    public static final InteractionRadius NONE = new InteractionRadius(0, null, null);

    /**
     * Compute the world-space interaction bounding box.
     *
     * @param buildingBounds the building's own bounding box (world coords)
     * @param anchor         the building's anchor position
     */
    public BoundingBox computeInteractionBounds(BoundingBox buildingBounds, BlockPos anchor) {
        if (area != null) {
            // Explicit box mode — relative to anchor, independent of building bounds
            return new BoundingBox(
                    anchor.getX() + area.min().x(),
                    anchor.getY() + area.min().y(),
                    anchor.getZ() + area.min().z(),
                    anchor.getX() + area.max().x(),
                    anchor.getY() + area.max().y(),
                    anchor.getZ() + area.max().z()
            );
        }
        if (expand != null) {
            // Per-axis expansion mode
            return new BoundingBox(
                    buildingBounds.minX() - expand.x(),
                    buildingBounds.minY() - expand.y(),
                    buildingBounds.minZ() - expand.z(),
                    buildingBounds.maxX() + expand.x(),
                    buildingBounds.maxY() + expand.y(),
                    buildingBounds.maxZ() + expand.z()
            );
        }
        // Uniform expansion mode
        return new BoundingBox(
                buildingBounds.minX() - uniform,
                buildingBounds.minY() - uniform,
                buildingBounds.minZ() - uniform,
                buildingBounds.maxX() + uniform,
                buildingBounds.maxY() + uniform,
                buildingBounds.maxZ() + uniform
        );
    }

    /**
     * Effective interaction range for tourist AI arrival detection.
     * Returns the maximum one-sided expansion across all axes.
     */
    public int getEffectiveRange() {
        if (area != null) {
            int dx = Math.max(Math.abs(area.min().x()), Math.abs(area.max().x()));
            int dy = Math.max(Math.abs(area.min().y()), Math.abs(area.max().y()));
            int dz = Math.max(Math.abs(area.min().z()), Math.abs(area.max().z()));
            return Math.max(dx, Math.max(dy, dz));
        }
        if (expand != null) {
            return Math.max(expand.x(), Math.max(expand.y(), expand.z()));
        }
        return uniform;
    }

    // ── Gson deserializer ──

    public static class Deserializer implements JsonDeserializer<InteractionRadius> {
        @Override
        public InteractionRadius deserialize(JsonElement json, Type typeOfT,
                                              JsonDeserializationContext context) throws JsonParseException {
            // Format 1: uniform int — "interaction_radius": 4
            if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
                return new InteractionRadius(json.getAsInt(), null, null);
            }

            JsonObject obj = json.getAsJsonObject();

            // Format 2: explicit box — {min: [x,y,z], max: [x,y,z]} (highest priority)
            if (obj.has("min") && obj.has("max")) {
                var offsetDs = new BlockOffset.Deserializer();
                BlockOffset min = offsetDs.deserialize(obj.get("min"), BlockOffset.class, context);
                BlockOffset max = offsetDs.deserialize(obj.get("max"), BlockOffset.class, context);
                return new InteractionRadius(0, null, new BuildingConfig.BoundaryBox(min, max));
            }

            // Format 3: per-axis expansion — {x: 3, y: 1, z: 3}
            int x = getInt(obj, "x", 0);
            int y = getInt(obj, "y", 0);
            int z = getInt(obj, "z", 0);
            return new InteractionRadius(0, BlockOffset.of(x, y, z), null);
        }

        private static int getInt(JsonObject obj, String key, int def) {
            return obj.has(key) ? obj.get(key).getAsInt() : def;
        }
    }
}
