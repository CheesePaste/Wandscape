package com.wsteam.wandscape.content.task.engine.dsl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.types.BlockType;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.content.task.types.InteractAction;
import com.wsteam.wandscape.content.task.types.ResourceId;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.content.task.runtime.TaskSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default blueprints as Java lambdas — the replacement for the removed JSON blueprint DSL.
 *
 * <p>Each method mirrors one {@code data/wandscape/blueprints/*.json} file, producing the same
 * {@link TaskSequence} of {@link AtomicOp}s the old interpreter emitted. Params arrive fully
 * resolved from {@code EnqueueHelper.buildWorkItem} (building bind + material auto-compute +
 * rotation) or the relevant task source, so each builder only reads typed values and emits ops.
 *
 * <p>Registered in {@link EngineBootstrap} via {@link #register}.
 */
public final class BlueprintDefaults {

    private BlueprintDefaults() {}

    public static void register(BlueprintRegistry registry) {
        registry.register("build:place_structure", BlueprintDefaults::placeStructure);
        registry.register("build:clear_and_build", BlueprintDefaults::clearAndBuild);
        registry.register("build:demolish_structure", BlueprintDefaults::demolishStructure);
        registry.register("road:build_segment", BlueprintDefaults::roadBuildSegment);
        registry.register("terrain:fill_box", BlueprintDefaults::terrainFillBox);
        registry.register("terrain:flatten", BlueprintDefaults::terrainFlatten);
        registry.register("magic:altar_cast", BlueprintDefaults::magicAltarCast);
        registry.register("node:gather", BlueprintDefaults::nodeGather);
        registry.register("production:craft", BlueprintDefaults::productionCraft);
        registry.register("production:craft_spell", BlueprintDefaults::productionCraftSpell);
        registry.register("production:decompose", BlueprintDefaults::productionDecompose);
        registry.register("production:synthesize", BlueprintDefaults::productionSynthesize);
    }

    // ─────────────────────────────────────────────────────────────────
    // build:place_structure
    // ─────────────────────────────────────────────────────────────────

    private static TaskSequence placeStructure(Map<String, JsonElement> p) {
        GridPos anchor = pos(p, "anchor");
        List<GridPos> offsets = posList(p, "offsets");
        Map<String, String> blocks = strMap(p, "blocks");
        Map<String, String> blockNbt = strMap(p, "block_nbt");
        List<JsonElement> entities = array(p, "entities");
        List<AtomicOp> ops = new ArrayList<>();

        addMaterialRequest(ops, p);
        for (GridPos off : offsets) {
            String key = key(off);
            String block = blocks.get(key);
            if (block == null) continue;
            String nbt = blockNbt.get(key);
            ops.add(AtomicOp.TransformOp.place(anchor.add(off), new BlockType(block), nbt));
        }
        for (JsonElement ent : entities) {
            JsonObject o = ent.getAsJsonObject();
            GridPos offset = pos(o.get("offset"));
            String type = o.get("type").getAsString();
            String facing = o.has("facing") ? o.get("facing").getAsString() : "";
            String nbt = o.has("nbt") && !o.get("nbt").isJsonNull()
                    ? o.get("nbt").getAsString() : null;
            ops.add(new AtomicOp.SpawnDecorationOp(anchor.add(offset), type, facing, nbt));
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("building_name", str(p, "name"));
        data.put("blocks_placed", String.valueOf(offsets.size()));
        data.put("anchor", str(p, "anchor"));
        putBuildingId(data, p);
        ops.add(new AtomicOp.EmitEventOp("build_complete", data));

        return new TaskSequence(ops, label("Build Structure", p));
    }

    // ─────────────────────────────────────────────────────────────────
    // build:clear_and_build  (legacy name kept for data compat; generic over the
    // offsets/blocks it is given)
    //
    // Whether the boundary box is cleared is decided upstream in EnqueueHelper: when
    // box clearing is on (default), it expands offsets/blocks to the whole rotated
    // boundary with non-pattern voxels mapped to "minecraft:air", so this loop single-
    // passes the box exactly like the pre-overlap "clear then build" did. When off,
    // only the pattern voxels arrive and construction is pure placement (overlapping
    // interiors untouched). This method itself always just places each given offset's
    // mapped block — an air mapping is a normal (free) place.
    // ─────────────────────────────────────────────────────────────────

    private static TaskSequence clearAndBuild(Map<String, JsonElement> p) {
        GridPos anchor = pos(p, "anchor");
        List<GridPos> offsets = posList(p, "offsets");
        Map<String, String> blocks = strMap(p, "blocks");
        Map<String, String> blockNbt = strMap(p, "block_nbt");
        List<AtomicOp> ops = new ArrayList<>();

        // Inline of the former `call build:place_structure` macro-expansion.
        addMaterialRequest(ops, p);
        for (GridPos off : offsets) {
            String key = key(off);
            String block = blocks.get(key);
            if (block == null) continue;
            String nbt = blockNbt.get(key);
            ops.add(AtomicOp.TransformOp.place(anchor.add(off), new BlockType(block), nbt));
        }
        for (JsonElement ent : array(p, "entities")) {
            JsonObject o = ent.getAsJsonObject();
            GridPos offset = pos(o.get("offset"));
            String type = o.get("type").getAsString();
            String facing = o.has("facing") ? o.get("facing").getAsString() : "";
            String nbt = o.has("nbt") && !o.get("nbt").isJsonNull()
                    ? o.get("nbt").getAsString() : null;
            ops.add(new AtomicOp.SpawnDecorationOp(anchor.add(offset), type, facing, nbt));
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("building_name", str(p, "name"));
        data.put("blocks_placed", String.valueOf(offsets.size()));
        data.put("anchor", str(p, "anchor"));
        putBuildingId(data, p);
        ops.add(new AtomicOp.EmitEventOp("build_complete", data));

        return new TaskSequence(ops, label("Clear and Build", p));
    }

    // ─────────────────────────────────────────────────────────────────
    // build:demolish_structure
    // ─────────────────────────────────────────────────────────────────

    private static TaskSequence demolishStructure(Map<String, JsonElement> p) {
        GridPos anchor = pos(p, "anchor");
        List<AtomicOp> ops = new ArrayList<>();
        for (GridPos off : posList(p, "offsets")) {
            ops.add(AtomicOp.TransformOp.place(anchor.add(off), BlockType.AIR));
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("anchor", str(p, "anchor"));
        data.put("building_id", str(p, "building_id"));
        ops.add(new AtomicOp.EmitEventOp("demolish_complete", data));

        return new TaskSequence(ops, label("Demolish Structure", p));
    }

    // ─────────────────────────────────────────────────────────────────
    // road:build_segment  (tiles: [{pos, block}] injected at runtime)
    // ─────────────────────────────────────────────────────────────────

    private static TaskSequence roadBuildSegment(Map<String, JsonElement> p) {
        List<AtomicOp> ops = new ArrayList<>();
        addMaterialRequest(ops, p);
        List<JsonElement> tiles = array(p, "tiles");
        for (JsonElement tile : tiles) {
            JsonObject o = tile.getAsJsonObject();
            ops.add(AtomicOp.TransformOp.place(pos(o.get("pos")), new BlockType(o.get("block").getAsString())));
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("segment_id", str(p, "segment_id"));
        data.put("edge_id", str(p, "edge_id"));
        data.put("tiles_placed", String.valueOf(tiles.size()));
        ops.add(new AtomicOp.EmitEventOp("road_segment_complete", data));

        return new TaskSequence(ops, label("Road Segment", p));
    }

    // ─────────────────────────────────────────────────────────────────
    // terrain:fill_box  (tiles: [{pos, block}] injected at runtime)
    // ─────────────────────────────────────────────────────────────────

    private static TaskSequence terrainFillBox(Map<String, JsonElement> p) {
        List<AtomicOp> ops = new ArrayList<>();
        addMaterialRequest(ops, p);
        List<JsonElement> tiles = array(p, "tiles");
        for (JsonElement tile : tiles) {
            JsonObject o = tile.getAsJsonObject();
            ops.add(AtomicOp.TransformOp.place(pos(o.get("pos")), new BlockType(o.get("block").getAsString())));
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("blocks_placed", String.valueOf(tiles.size()));
        ops.add(new AtomicOp.EmitEventOp("terrain_fill_complete", data));

        return new TaskSequence(ops, label("Fill Box", p));
    }

    // ─────────────────────────────────────────────────────────────────
    // terrain:flatten  (tiles_break: [pos...], tiles_fill: [{pos, block}])
    // ─────────────────────────────────────────────────────────────────

    private static TaskSequence terrainFlatten(Map<String, JsonElement> p) {
        List<AtomicOp> ops = new ArrayList<>();
        String fillBlock = str(p, "fill_block");
        int fillCount = asInt(p, "fill_count");
        if (fillCount > 0) {
            ops.add(new AtomicOp.ResourceRequestOp(
                    List.of(new ResourceStack(new ResourceId(fillBlock), fillCount))));
        }
        for (GridPos pos : posList(p, "tiles_break")) {
            ops.add(AtomicOp.TransformOp.remove(pos, BlockType.AIR, List.of()));
        }
        List<JsonElement> tilesFill = array(p, "tiles_fill");
        for (JsonElement tile : tilesFill) {
            JsonObject o = tile.getAsJsonObject();
            ops.add(AtomicOp.TransformOp.place(pos(o.get("pos")), new BlockType(o.get("block").getAsString())));
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("blocks_removed", String.valueOf(posList(p, "tiles_break").size()));
        data.put("blocks_placed", String.valueOf(tilesFill.size()));
        data.put("fill_block", fillBlock);
        ops.add(new AtomicOp.EmitEventOp("terrain_flatten_complete", data));

        return new TaskSequence(ops, label("Terrain Flatten", p));
    }

    // ─────────────────────────────────────────────────────────────────
    // magic:altar_cast
    // ─────────────────────────────────────────────────────────────────

    private static TaskSequence magicAltarCast(Map<String, JsonElement> p) {
        GridPos anchor = pos(p, "anchor");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("altar", str(p, "altar"));
        params.put("duration", str(p, "duration"));
        List<AtomicOp> ops = List.of(new AtomicOp.AltarCastOp(anchor, str(p, "magic_id"), params));
        return new TaskSequence(ops, label("Altar Cast", p));
    }

    // ─────────────────────────────────────────────────────────────────
    // node:gather / production:*  (single block_interact)
    // ─────────────────────────────────────────────────────────────────

    private static TaskSequence nodeGather(Map<String, JsonElement> p) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("element", str(p, "element"));
        params.put("amount", str(p, "amount"));
        List<AtomicOp> ops = List.of(new AtomicOp.BlockInteractOp(
                pos(p, "anchor"), new InteractAction("gather"), params, asInt(p, "channel_ticks")));
        return new TaskSequence(ops, label("节点采集", p));
    }

    private static TaskSequence productionCraft(Map<String, JsonElement> p) {
        List<AtomicOp> ops = List.of(new AtomicOp.BlockInteractOp(
                pos(p, "anchor"), new InteractAction("craft"),
                interactParams(p, "recipe_id", "count"), asInt(p, "channel_ticks")));
        return new TaskSequence(ops, label("制作物品", p));
    }

    private static TaskSequence productionCraftSpell(Map<String, JsonElement> p) {
        List<AtomicOp> ops = List.of(new AtomicOp.BlockInteractOp(
                pos(p, "anchor"), new InteractAction("craft_spell"),
                interactParams(p, "recipe_id", "count"), asInt(p, "channel_ticks")));
        return new TaskSequence(ops, label("制作魔法卷轴", p));
    }

    private static TaskSequence productionDecompose(Map<String, JsonElement> p) {
        List<AtomicOp> ops = List.of(new AtomicOp.BlockInteractOp(
                pos(p, "anchor"), new InteractAction("decompose"),
                interactParams(p, "item_id", "count"), asInt(p, "channel_ticks")));
        return new TaskSequence(ops, label("分解物品", p));
    }

    private static TaskSequence productionSynthesize(Map<String, JsonElement> p) {
        Map<String, String> params = interactParams(p, "recipe_id", "count");
        // 补货驱动的合成带 supply=restock：容量豁免标（见 WarehouseCapacity 判定）。
        JsonElement supply = p.get("supply");
        if (supply != null && supply.isJsonPrimitive() && !supply.getAsString().isEmpty()) {
            params.put("supply", supply.getAsString());
        }
        List<AtomicOp> ops = List.of(new AtomicOp.BlockInteractOp(
                pos(p, "anchor"), new InteractAction("synthesize"),
                params, asInt(p, "channel_ticks")));
        return new TaskSequence(ops, label("合成物品", p));
    }

    // ─────────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────────

    /** Request resources from {@code material_list} × {@code material_counts}; skip if empty. */
    private static void addMaterialRequest(List<AtomicOp> ops, Map<String, JsonElement> p) {
        JsonElement listEl = p.get("material_list");
        JsonElement countsEl = p.get("material_counts");
        if (listEl == null || !listEl.isJsonArray() || countsEl == null) return;
        JsonObject counts = countsEl.getAsJsonObject();
        List<ResourceStack> stacks = new ArrayList<>();
        for (JsonElement matEl : listEl.getAsJsonArray()) {
            String mat = matEl.getAsString();
            if (!counts.has(mat)) continue;
            int amt = counts.get(mat).getAsInt();
            if (amt > 0) stacks.add(new ResourceStack(new ResourceId(mat), amt));
        }
        if (!stacks.isEmpty()) ops.add(new AtomicOp.ResourceRequestOp(stacks));
    }

    private static Map<String, String> interactParams(Map<String, JsonElement> p, String k1, String k2) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(k1, str(p, k1));
        params.put(k2, str(p, k2));
        return params;
    }

    /** Format the task label like the old interpreter's {@code buildLabel}: displayName at anchor. */
    private static String label(String displayName, Map<String, JsonElement> p) {
        String label = displayName;
        JsonElement anchor = p.get("anchor");
        if (anchor != null && anchor.isJsonArray()) {
            try { label += " at " + pos(anchor); } catch (RuntimeException ignored) {}
        } else {
            JsonElement x = p.get("x"), y = p.get("y"), z = p.get("z");
            if (x != null && y != null && z != null) {
                try { label += " at (" + asInt(x) + ", " + asInt(y) + ", " + asInt(z) + ")"; }
                catch (RuntimeException ignored) {}
            }
        }
        return label;
    }

    private static String key(GridPos pos) {
        return pos.x() + "," + pos.y() + "," + pos.z();
    }

    /**
     * Attach {@code building_id} to an emitted event when the work item carries it,
     * so completion listeners can resolve the building by id (anchors are no longer
     * unique once bounding boxes may overlap). No-op for legacy work items.
     */
    private static void putBuildingId(Map<String, String> data, Map<String, JsonElement> p) {
        JsonElement bid = p.get("building_id");
        if (bid != null && bid.isJsonPrimitive() && !bid.getAsString().isEmpty()) {
            data.put("building_id", bid.getAsString());
        }
    }

    private static GridPos pos(Map<String, JsonElement> p, String key) {
        return pos(require(p, key));
    }

    private static GridPos pos(JsonElement el) {
        JsonArray arr = el.getAsJsonArray();
        return new GridPos(arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
    }

    private static List<GridPos> posList(Map<String, JsonElement> p, String key) {
        JsonElement el = require(p, key);
        List<GridPos> list = new ArrayList<>();
        for (JsonElement e : el.getAsJsonArray()) list.add(pos(e));
        return list;
    }

    private static List<JsonElement> array(Map<String, JsonElement> p, String key) {
        JsonElement el = p.get(key);
        if (el == null || !el.isJsonArray()) return List.of();
        return el.getAsJsonArray().asList();
    }

    private static Map<String, String> strMap(Map<String, JsonElement> p, String key) {
        JsonElement el = p.get(key);
        Map<String, String> map = new LinkedHashMap<>();
        if (el == null || !el.isJsonObject()) return map;
        for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
            map.put(e.getKey(), str(e.getValue()));
        }
        return map;
    }

    private static String str(Map<String, JsonElement> p, String key) {
        return str(require(p, key));
    }

    private static String str(JsonElement el) {
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonArray()) {
            GridPos pos = pos(el);
            return pos.x() + "," + pos.y() + "," + pos.z();
        }
        return el.toString();
    }

    private static int asInt(Map<String, JsonElement> p, String key) {
        return asInt(require(p, key));
    }

    private static int asInt(JsonElement el) {
        return el.getAsJsonPrimitive().isNumber()
                ? el.getAsJsonPrimitive().getAsInt()
                : Integer.parseInt(el.getAsJsonPrimitive().getAsString());
    }

    private static JsonElement require(Map<String, JsonElement> p, String key) {
        JsonElement el = p.get(key);
        if (el == null) throw new IllegalArgumentException("Missing blueprint param: $" + key);
        return el;
    }
}
