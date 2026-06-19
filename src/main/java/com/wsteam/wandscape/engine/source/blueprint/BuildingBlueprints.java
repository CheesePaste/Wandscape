package com.wsteam.wandscape.engine.source.blueprint;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.core.CoreBootstrap;

import com.wsteam.wandscape.core.Log;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.task.BlueprintRegistry;
import com.wsteam.wandscape.core.task.BlueprintSteps;
import com.wsteam.wandscape.core.task.TaskSequence;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;

/**
 * Registers "build:*" blueprints that translate to {@link AtomicOp.TransformOp} sequences.
 * Called once before {@link CoreBootstrap#bootstrap}.
 *
 * <p>Each blueprint takes x/y/z params from the WorkItem and generates
 * a single-step TransformOp.place task. When elements and resources are
 * added (stage 3), a ResourceRequestOp step will be prepended.
 */
public final class BuildingBlueprints {
    private static final String TAG = "BuildingBp";

    private BuildingBlueprints() {}

    /**
     * Register all build blueprints into the given registry.
     */
    public static void registerAll(BlueprintRegistry registry) {
        registry.register("build:stone_bricks",
                buildPlaceSteps(BlockType.STONE_BRICKS, "Build stone_bricks"));
        registry.register("build:oak_planks",
                buildPlaceSteps(BlockType.OAK_PLANKS, "Build oak_planks"));
        registry.register("build:stone",
                buildPlaceSteps(BlockType.STONE, "Build stone"));
        registry.register("build:dirt",
                buildPlaceSteps(BlockType.DIRT, "Build dirt"));
        registry.register("build:glass",
                buildPlaceSteps(BlockType.GLASS, "Build glass"));

        Log.info(TAG, "registered %d build:* blueprints", 5);
    }

    /**
     * Create a single-step placement blueprint.
     */
    private static BlueprintSteps buildPlaceSteps(BlockType blockType, String label) {
        return params -> {
            GridPos pos = parsePos(params);
            return new TaskSequence(
                    List.of(AtomicOp.TransformOp.place(pos, blockType)),
                    label + " at " + pos);
        };
    }

    private static GridPos parsePos(Map<String, String> params) {
        try {
            int x = Integer.parseInt(params.getOrDefault("x", "0"));
            int y = Integer.parseInt(params.getOrDefault("y", "0"));
            int z = Integer.parseInt(params.getOrDefault("z", "0"));
            return new GridPos(x, y, z);
        } catch (NumberFormatException e) {
            return GridPos.ORIGIN;
        }
    }
}
