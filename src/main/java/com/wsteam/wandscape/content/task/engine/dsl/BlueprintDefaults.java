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
import java.util.Arrays;
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

    /** 序列标签查的蓝图名键前缀：标签复用蓝图自己的名字（见 lang_src/content/blueprint.json）。 */
    private static final String BLUEPRINT_NAME_KEY = "blueprint.wandscape.";

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
        if (!bool(p, "omit_complete_event", false)) {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("building_name", str(p, "name"));
            data.put("blocks_placed", String.valueOf(offsets.size()));
            data.put("anchor", str(p, "anchor"));
            putBuildingId(data, p);
            ops.add(new AtomicOp.EmitEventOp("build_complete", data));
        }

        return new TaskSequence(ops, label("build:place_structure", p));
    }

    // ─────────────────────────────────────────────────────────────────
    // build:clear_and_build  (legacy name kept for data compat; generic over the
    // offsets/blocks it is given)
    //
    // Whether the boundary box is cleared is decided upstream in EnqueueHelper: when
    // box clearing is on (default) the params carry the rotated boundary box, and this
    // method turns it into ONE ClearBoxOp whose box is enumerated at execution time
    // (先清场、后放置，与 pre-overlap 的 "clear then build" 同序同结果). When off, no
    // boundary params arrive and construction is pure placement (overlapping interiors
    // untouched). 放置循环本身只管把给定 offset 的方块放下去。
    //
    // 参数形态是自描述的，所以不需要版本迁移：动作与旧档的老参数形态（offsets 里自带
    // "minecraft:air" 条目、没有 boundary_*）都仍然可用 —— 老形态不产生 ClearBoxOp，
    // 那些 air 条目会在下面的循环里逐格放掉，与改前完全一致。
    // ─────────────────────────────────────────────────────────────────

    private static TaskSequence clearAndBuild(Map<String, JsonElement> p) {
        GridPos anchor = pos(p, "anchor");
        List<GridPos> offsets = posList(p, "offsets");
        Map<String, String> blocks = strMap(p, "blocks");
        Map<String, String> blockNbt = strMap(p, "block_nbt");
        List<AtomicOp> ops = new ArrayList<>();

        // Inline of the former `call build:place_structure` macro-expansion.
        addMaterialRequest(ops, p);
        List<GridPos> clearExclusions = p.containsKey("pattern_offsets")
                ? posList(p, "pattern_offsets")
                : offsets;
        addBoxClear(ops, p, anchor, clearExclusions);
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
        if (!bool(p, "omit_complete_event", false)) {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("building_name", str(p, "name"));
            data.put("blocks_placed", String.valueOf(offsets.size()));
            data.put("anchor", str(p, "anchor"));
            putBuildingId(data, p);
            ops.add(new AtomicOp.EmitEventOp("build_complete", data));
        }

        return new TaskSequence(ops, label("build:clear_and_build", p));
    }

    /**
     * 整箱清空：把 {@code boundary_min} / {@code boundary_max} 换成**一个**盒内枚举的
     * {@link AtomicOp.ClearBoxOp}（范围由 {@code EnqueueHelper#fillBoundaryParams} 写入）。
     *
     * <p>排除集 = 编译期的 pattern 全集，编成盒内一维索引并排序，执行器用「游标只前进」的
     * 双指针逐格跳过它们 —— 一栋超大建筑 58 万条 pattern 只占约 4.6 MB 的 long[]，而原先
     * 那 666 万条 air 条目要撑出 710 万条 op。
     *
     * <p>落在盒外的 pattern 格不进排除集：清场只在盒内走，走不到那里，也就不会误伤它。
     */
    private static void addBoxClear(List<AtomicOp> ops, Map<String, JsonElement> p,
                                    GridPos anchor, List<GridPos> offsets) {
        if (!p.containsKey("boundary_min") || !p.containsKey("boundary_max")) return;
        GridPos min = pos(p, "boundary_min");
        GridPos max = pos(p, "boundary_max");
        int dx = max.x() - min.x() + 1;
        int dy = max.y() - min.y() + 1;
        int dz = max.z() - min.z() + 1;

        // offsets 相对 anchor，搬到「盒内相对坐标」再编码。
        int ox = anchor.x() - min.x();
        int oy = anchor.y() - min.y();
        int oz = anchor.z() - min.z();
        long[] excluded = new long[offsets.size()];
        int n = 0;
        for (GridPos off : offsets) {
            int rx = off.x() + ox;
            int ry = off.y() + oy;
            int rz = off.z() + oz;
            if (rx < 0 || rx >= dx || ry < 0 || ry >= dy || rz < 0 || rz >= dz) continue;
            excluded[n++] = AtomicOp.ClearBoxOp.index(dx, dz, rx, ry, rz);
        }
        if (n != excluded.length) excluded = Arrays.copyOf(excluded, n);
        Arrays.sort(excluded);

        ops.add(new AtomicOp.ClearBoxOp(min, max, excluded));
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

        return new TaskSequence(ops, label("build:demolish_structure", p));
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

        return new TaskSequence(ops, label("road:build_segment", p));
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

        return new TaskSequence(ops, label("terrain:fill_box", p));
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

        return new TaskSequence(ops, label("terrain:flatten", p));
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
        return new TaskSequence(ops, label("magic:altar_cast", p));
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
        return new TaskSequence(ops, label("node:gather", p));
    }

    private static TaskSequence productionCraft(Map<String, JsonElement> p) {
        List<AtomicOp> ops = List.of(new AtomicOp.BlockInteractOp(
                pos(p, "anchor"), new InteractAction("craft"),
                interactParams(p, "recipe_id", "count"), asInt(p, "channel_ticks")));
        return new TaskSequence(ops, label("production:craft", p));
    }

    private static TaskSequence productionCraftSpell(Map<String, JsonElement> p) {
        List<AtomicOp> ops = List.of(new AtomicOp.BlockInteractOp(
                pos(p, "anchor"), new InteractAction("craft_spell"),
                interactParams(p, "recipe_id", "count"), asInt(p, "channel_ticks")));
        return new TaskSequence(ops, label("production:craft_spell", p));
    }

    private static TaskSequence productionDecompose(Map<String, JsonElement> p) {
        List<AtomicOp> ops = List.of(new AtomicOp.BlockInteractOp(
                pos(p, "anchor"), new InteractAction("decompose"),
                interactParams(p, "item_id", "count"), asInt(p, "channel_ticks")));
        return new TaskSequence(ops, label("production:decompose", p));
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
        return new TaskSequence(ops, label("production:synthesize", p));
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

    /**
     * Format the task label like the old interpreter's {@code buildLabel}: {@code <蓝图名> at (<位置>)}。
     * 名字查蓝图自己的 lang 键 {@code blueprint.wandscape.<蓝图 id>}，客户端按本地语言解析
     * （见 {@code TaskText#sequenceLabel}）；位置段与语言无关，原样拼在后面。
     */
    private static String label(String blueprintId, Map<String, JsonElement> p) {
        String label = BLUEPRINT_NAME_KEY + blueprintId;
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

    private static boolean bool(Map<String, JsonElement> p, String key, boolean defaultValue) {
        JsonElement el = p.get(key);
        if (el == null || !el.isJsonPrimitive()) return defaultValue;
        try {
            return el.getAsBoolean();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static JsonElement require(Map<String, JsonElement> p, String key) {
        JsonElement el = p.get(key);
        if (el == null) throw new IllegalArgumentException("Missing blueprint param: $" + key);
        return el;
    }
}
