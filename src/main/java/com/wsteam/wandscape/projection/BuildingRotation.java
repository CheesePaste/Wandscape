package com.wsteam.wandscape.projection;

import java.util.HashMap;
import java.util.Map;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Static utilities for rotating building structures 90° counter-clockwise
 * around the Y axis.
 *
 * <p>Handles both offset positions and block state properties (axis, facing, etc.)
 * so the rotated building renders and is constructed correctly.
 */
public final class BuildingRotation {

    private BuildingRotation() {}

    /**
     * Rotate a single BlockOffset by {@code steps} increments of 90° CCW around Y.
     * x' = -z, z' = x  (for one step)
     */
    public static BlockOffset rotateOffset(BlockOffset off, int steps) {
        if (steps <= 0) return off;
        steps = steps & 3; // mod 4
        int x = off.x();
        int y = off.y();
        int z = off.z();
        for (int i = 0; i < steps; i++) {
            int nx = -z;
            z = x;
            x = nx;
        }
        return new BlockOffset(x, y, z);
    }

    /**
     * Rotate a block state string (e.g. {@code "minecraft:oak_log[axis=z]"}) by
     * {@code steps} increments of 90° CCW around Y. Uses Minecraft's built-in
     * {@link BlockState#rotate(Rotation)} so all directional properties (axis,
     * facing, rotation, stair shape, etc.) are handled correctly.
     */
    public static String rotateBlockStateString(String blockId, int steps) {
        if (steps <= 0) return blockId;
        steps = steps & 3;

        String baseId;
        String propsStr = null;
        int bracketIdx = blockId.indexOf('[');
        if (bracketIdx >= 0 && blockId.endsWith("]")) {
            baseId = blockId.substring(0, bracketIdx);
            propsStr = blockId.substring(bracketIdx + 1, blockId.length() - 1);
        } else {
            baseId = blockId;
        }

        ResourceLocation rl;
        try {
            rl = ResourceLocation.parse(baseId);
        } catch (Exception e) {
            return blockId;
        }

        Block block = BuiltInRegistries.BLOCK.get(rl);
        if (block == null) return blockId;

        BlockState state = block.defaultBlockState();
        if (propsStr != null && !propsStr.isEmpty()) {
            for (String part : propsStr.split(",")) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) continue;
                Property<?> property = block.getStateDefinition().getProperty(kv[0]);
                if (property != null) {
                    state = setPropertyValue(state, property, kv[1]);
                }
            }
        }

        for (int i = 0; i < steps; i++) {
            state = state.rotate(Rotation.CLOCKWISE_90);
        }

        return blockStateToString(state);
    }

    /**
     * Rotate a boundary box by {@code steps} increments of 90° CCW around Y.
     * Computes the new AABB from all 8 rotated corner points.
     */
    public static BuildingConfig.BoundaryBox rotateBoundary(BuildingConfig.BoundaryBox boundary, int steps) {
        if (steps <= 0) return boundary;
        steps = steps & 3;

        BlockOffset min = boundary.min();
        BlockOffset max = boundary.max();

        int x1 = min.x(), y1 = min.y(), z1 = min.z();
        int x2 = max.x(), y2 = max.y(), z2 = max.z();

        int[][] corners = {
            {x1, y1, z1}, {x2, y1, z1}, {x1, y2, z1}, {x2, y2, z1},
            {x1, y1, z2}, {x2, y1, z2}, {x1, y2, z2}, {x2, y2, z2}
        };

        int newMinX = Integer.MAX_VALUE, newMinY = Integer.MAX_VALUE, newMinZ = Integer.MAX_VALUE;
        int newMaxX = Integer.MIN_VALUE, newMaxY = Integer.MIN_VALUE, newMaxZ = Integer.MIN_VALUE;

        for (int[] c : corners) {
            BlockOffset rotated = rotateOffset(new BlockOffset(c[0], c[1], c[2]), steps);
            newMinX = Math.min(newMinX, rotated.x());
            newMinY = Math.min(newMinY, rotated.y());
            newMinZ = Math.min(newMinZ, rotated.z());
            newMaxX = Math.max(newMaxX, rotated.x());
            newMaxY = Math.max(newMaxY, rotated.y());
            newMaxZ = Math.max(newMaxZ, rotated.z());
        }

        return new BuildingConfig.BoundaryBox(
                new BlockOffset(newMinX, newMinY, newMinZ),
                new BlockOffset(newMaxX, newMaxY, newMaxZ));
    }

    /**
     * Rotate a palette of block state strings by {@code steps} increments of 90°
     * CCW around Y. Rotating a whole building becomes M rotations (one per distinct
     * blockstate) instead of N (one per block); block indices stay unchanged.
     */
    public static java.util.List<String> rotatePalette(java.util.List<String> palette, int steps) {
        if (steps <= 0 || palette.isEmpty()) return palette;
        steps = steps & 3;
        java.util.List<String> result = new java.util.ArrayList<>(palette.size());
        for (String blockState : palette) {
            result.add(rotateBlockStateString(blockState, steps));
        }
        return java.util.Collections.unmodifiableList(result);
    }

    /**
     * Rotate a map of offsets-to-blockIds (block_mapping) by {@code steps}.
     * Keys are "x,y,z" strings, values are block state strings.
     */
    public static Map<String, String> rotateBlockMapping(Map<String, String> blockMapping, int steps) {
        if (steps <= 0) return blockMapping;
        steps = steps & 3;
        Map<String, String> result = new HashMap<>();
        for (var entry : blockMapping.entrySet()) {
            BlockOffset off = parseKey(entry.getKey());
            if (off == null) continue;
            BlockOffset rotatedOff = rotateOffset(off, steps);
            String rotatedBlock = rotateBlockStateString(entry.getValue(), steps);
            result.put(rotatedOff.toKey(), rotatedBlock);
        }
        return result;
    }

    /**
     * Rotate a Direction string (e.g. "north") by {@code steps} increments of 90°
     * clockwise around Y — same handedness as {@link #rotateOffset}. Up/down
     * directions are unchanged; unknown strings pass through unchanged.
     */
    public static String rotateFacing(String facing, int steps) {
        Direction dir = Direction.byName(facing);
        if (dir == null || dir.getAxis() == Direction.Axis.Y) return facing;
        steps = steps & 3;
        for (int i = 0; i < steps; i++) {
            dir = dir.getClockWise();
        }
        return dir.getName();
    }

    /**
     * Rotate a horizontal {@link Direction} by {@code steps} increments of 90°
     * clockwise around Y (same handedness as {@link #rotateOffset}).
     */
    public static Direction rotateDirection(Direction dir, int steps) {
        if (dir == null || dir.getAxis() == Direction.Axis.Y) return dir;
        steps = steps & 3;
        for (int i = 0; i < steps; i++) {
            dir = dir.getClockWise();
        }
        return dir;
    }

    /**
     * Rotate a map of offsets-to-NBT strings (block_nbt) by {@code steps}.
     * Keys are "x,y,z" strings; values are opaque NBT strings carried as-is.
     * Returns {@code null} when the input map is {@code null}.
     */
    public static Map<String, String> rotateBlockNbt(Map<String, String> blockNbt, int steps) {
        if (blockNbt == null || blockNbt.isEmpty()) return blockNbt;
        if (steps <= 0) return blockNbt;
        steps = steps & 3;
        Map<String, String> result = new HashMap<>();
        for (var entry : blockNbt.entrySet()) {
            BlockOffset off = parseKey(entry.getKey());
            if (off == null) continue;
            BlockOffset rotatedOff = rotateOffset(off, steps);
            result.put(rotatedOff.toKey(), entry.getValue());
        }
        return result;
    }

    /**
     * Rotate an array of offset positions (pattern) by {@code steps}.
     * Used for clear_offsets and pattern lists.
     */
    public static BlockOffset[] rotateOffsets(BlockOffset[] offsets, int steps) {
        if (steps <= 0) return offsets;
        steps = steps & 3;
        BlockOffset[] result = new BlockOffset[offsets.length];
        for (int i = 0; i < offsets.length; i++) {
            result[i] = rotateOffset(offsets[i], steps);
        }
        return result;
    }

    /**
     * Rotate a list of offset positions by {@code steps}.
     */
    public static java.util.List<BlockOffset> rotateOffsets(java.util.List<BlockOffset> offsets, int steps) {
        if (steps <= 0 || offsets.isEmpty()) return offsets;
        steps = steps & 3;
        var result = new java.util.ArrayList<BlockOffset>(offsets.size());
        for (BlockOffset off : offsets) {
            result.add(rotateOffset(off, steps));
        }
        return java.util.Collections.unmodifiableList(result);
    }

    // ── Internal helpers ──

    private static BlockOffset parseKey(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockOffset(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Comparable<T>> BlockState setPropertyValue(
            BlockState state, Property<T> property, String valueStr) {
        return property.getValue(valueStr)
                .map(v -> (BlockState) state.setValue(property, v))
                .orElse(state);
    }

    /** Serialize a BlockState back to a string like "minecraft:oak_log[axis=x]". */
    private static String blockStateToString(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        var values = state.getValues();
        if (values.isEmpty()) return id.toString();

        StringBuilder sb = new StringBuilder(id.toString());
        sb.append('[');
        boolean first = true;
        for (var entry : values.entrySet()) {
            if (!first) sb.append(',');
            sb.append(entry.getKey().getName()).append('=');
            sb.append(entry.getValue()); // Direction/Axis/Boolean/Integer all toString correctly
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }
}
