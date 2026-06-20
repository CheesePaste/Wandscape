package com.wsteam.wandscape.engine.source.blueprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.core.Log;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.task.BlueprintSteps;
import com.wsteam.wandscape.core.task.TaskSequence;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;

/**
 * Translates a {@link BuildingConfig} into a {@link BlueprintSteps}
 * by expanding {@code pattern + block_mapping} into
 * {@link AtomicOp.TransformOp#place} steps.
 *
 * <p>This covers the 90% case: static building structures.
 * Complex blueprints (loops, conditionals, mixed op types, external
 * state queries) still use raw {@code BlueprintSteps} lambdas.
 */
public final class DataDrivenSteps {
    private static final String TAG = "DataDrivenSteps";

    private DataDrivenSteps() {}

    /**
     * Create a {@link BlueprintSteps} from a loaded {@link BuildingConfig}.
     * The returned function takes runtime coordinates (x/y/z in params)
     * and returns a {@link TaskSequence} with one {@code TransformOp.place}
     * per pattern offset.
     */
    public static BlueprintSteps fromConfig(BuildingConfig config) {
        return params -> {
            GridPos anchor = parsePos(params);
            List<AtomicOp> ops = new ArrayList<>();

            for (BlockOffset offset : config.pattern()) {
                String blockId = config.blockMapping().get(offset.toKey());
                if (blockId == null) {
                    Log.warn(TAG,
                            "missing block_mapping for offset %s in building %s — skipping",
                            offset.toKey(), config.id());
                    continue;
                }
                GridPos pos = anchor.add(offset.x(), offset.y(), offset.z());
                ops.add(AtomicOp.TransformOp.place(pos, new BlockType(blockId)));
            }

            return new TaskSequence(ops,
                    "Build " + config.displayName() + " at " + anchor);
        };
    }

    private static GridPos parsePos(Map<String, JsonElement> params) {
        try {
            int x = params.containsKey("x") ? params.get("x").getAsInt() : 0;
            int y = params.containsKey("y") ? params.get("y").getAsInt() : 0;
            int z = params.containsKey("z") ? params.get("z").getAsInt() : 0;
            return new GridPos(x, y, z);
        } catch (NumberFormatException | IllegalStateException e) {
            return GridPos.ORIGIN;
        }
    }
}
