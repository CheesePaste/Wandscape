package com.wsteam.wandscape.blueprint.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.wsteam.wandscape.core.task.BlueprintDefinition;
import com.wsteam.wandscape.core.task.ExprNode;
import com.wsteam.wandscape.core.task.ParamType;
import com.wsteam.wandscape.core.task.StepNode;
import com.wsteam.wandscape.core.types.GridPos;

import com.wsteam.wandscape.shared.log.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client→Server network layer for blueprint editor persistence.
 *
 * <p>Serializes a {@link BlueprintDefinition} to/from JSON using Gson,
 * matching the format described in {@code architecture/data/blueprints.md}.
 * The JSON is sent to the server for writing to {@code data/wandscape/blueprints/}.
 *
 * <p>TODO: Implement actual NeoForge network packet when the server-side
 * handler is ready. Currently logs the JSON to console.
 */
public final class BlueprintEditorNetwork {

    private static final String TAG = "BlueprintEditorNetwork";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BlueprintEditorNetwork() {}

    // ═══════════════════════════════════════════════════════════════
    // JSON serialization (Definition → JSON string)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Serialize a BlueprintDefinition to JSON matching blueprints.md format.
     */
    public static String definitionToJson(BlueprintDefinition def) {
        JsonObject root = new JsonObject();
        root.addProperty("id", def.id());
        if (def.displayName() != null && !def.displayName().isEmpty()) {
            root.addProperty("display_name", def.displayName());
        }
        if (def.description() != null && !def.description().isEmpty()) {
            root.addProperty("description", def.description());
        }

        // Params
        if (!def.params().isEmpty()) {
            JsonObject paramsObj = new JsonObject();
            for (var entry : def.params().entrySet()) {
                paramsObj.addProperty(entry.getKey(), paramTypeToString(entry.getValue()));
            }
            root.add("params", paramsObj);
        }

        // Steps
        JsonArray stepsArr = new JsonArray();
        for (StepNode step : def.steps()) {
            stepsArr.add(stepToJson(step));
        }
        root.add("steps", stepsArr);

        return GSON.toJson(root);
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON deserialization (JSON string → Definition)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse a JSON string into a BlueprintDefinition.
     * Returns null on parse failure.
     */
    public static BlueprintDefinition jsonToDefinition(String json) {
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            String id = root.get("id").getAsString();
            String displayName = root.has("display_name")
                    ? root.get("display_name").getAsString() : "";
            String description = root.has("description")
                    ? root.get("description").getAsString() : "";

            // Params
            Map<String, ParamType> params = new LinkedHashMap<>();
            if (root.has("params")) {
                JsonObject paramsObj = root.getAsJsonObject("params");
                for (var entry : paramsObj.entrySet()) {
                    ParamType pt = ParamType.parse(entry.getValue().getAsString());
                    if (pt != null) {
                        params.put(entry.getKey(), pt);
                    }
                }
            }

            // Steps
            List<StepNode> steps = new ArrayList<>();
            if (root.has("steps")) {
                JsonArray stepsArr = root.getAsJsonArray("steps");
                for (JsonElement el : stepsArr) {
                    StepNode step = stepFromJson(el.getAsJsonObject());
                    if (step != null) {
                        steps.add(step);
                    }
                }
            }

            return new BlueprintDefinition(id, params, steps, displayName, description);

        } catch (Exception e) {
            Log.warn(TAG, "Failed to parse blueprint JSON: {}", e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Network send (stub — TODO: NeoForge packet)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send a save request to the server.
     * TODO: Replace with actual NeoForge network packet once server handler exists.
     */
    public static void sendSaveToServer(BlueprintDefinition def, String json) {
        Log.info(TAG, "=== Blueprint JSON ===\n{}\n=== End Blueprint ===", json);
        // TODO: Create BlueprintSavePacket and send via PacketDistributor
        // PacketDistributor.sendToServer(new BlueprintSavePacket(def, json));
    }

    // ═══════════════════════════════════════════════════════════════
    // StepNode → JSON
    // ═══════════════════════════════════════════════════════════════

    static JsonObject stepToJson(StepNode step) {
        return switch (step) {
            case StepNode.PlaceStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "place");
                obj.add("at", exprToJson(s.at()));
                obj.add("block", exprToJson(s.block()));
                if (s.consumable() != null) {
                    obj.add("consumable", exprToJson(s.consumable()));
                }
                yield obj;
            }
            case StepNode.RemoveStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "remove");
                obj.add("at", exprToJson(s.at()));
                obj.add("from", exprToJson(s.from()));
                yield obj;
            }
            case StepNode.ConvertStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "convert");
                obj.add("at", exprToJson(s.at()));
                obj.add("from", exprToJson(s.from()));
                obj.add("to", exprToJson(s.to()));
                yield obj;
            }
            case StepNode.BlockInteractStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "block_interact");
                obj.add("at", exprToJson(s.at()));
                obj.addProperty("action", s.action());
                if (s.channelTicks() != null) {
                    obj.add("channel_ticks", exprToJson(s.channelTicks()));
                }
                if (s.manaCost() != null) {
                    obj.add("mana_cost", exprToJson(s.manaCost()));
                }
                if (!s.params().isEmpty()) {
                    JsonObject paramsObj = new JsonObject();
                    for (var entry : s.params().entrySet()) {
                        paramsObj.add(entry.getKey(), exprToJson(entry.getValue()));
                    }
                    obj.add("params", paramsObj);
                }
                yield obj;
            }
            case StepNode.EntityInteractStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "entity_interact");
                obj.add("target", exprToJson(s.target()));
                obj.add("effect", exprToJson(s.effect()));
                obj.add("strength", exprToJson(s.strength()));
                obj.add("duration", exprToJson(s.duration()));
                yield obj;
            }
            case StepNode.RitualStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "ritual");
                obj.add("ritual", exprToJson(s.ritual()));
                obj.add("at", exprToJson(s.at()));
                if (!s.params().isEmpty()) {
                    JsonObject paramsObj = new JsonObject();
                    for (var entry : s.params().entrySet()) {
                        paramsObj.add(entry.getKey(), exprToJson(entry.getValue()));
                    }
                    obj.add("params", paramsObj);
                }
                yield obj;
            }
            case StepNode.RequestResourceStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "request_resource");
                if (s.dynamicItems() != null) {
                    // Expression object — write directly as "items" with the expression value
                    obj.add("items", exprToJson(s.dynamicItems()));
                } else if (!s.items().isEmpty()) {
                    JsonArray itemsArr = new JsonArray();
                    for (var item : s.items()) {
                        JsonObject itemObj = new JsonObject();
                        itemObj.add("resource", exprToJson(item.resource()));
                        itemObj.add("amount", exprToJson(item.amount()));
                        itemsArr.add(itemObj);
                    }
                    obj.add("items", itemsArr);
                }
                yield obj;
            }
            case StepNode.EmitEventStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "emit_event");
                obj.add("event", exprToJson(s.event()));
                if (!s.data().isEmpty()) {
                    JsonObject dataObj = new JsonObject();
                    for (var entry : s.data().entrySet()) {
                        dataObj.add(entry.getKey(), exprToJson(entry.getValue()));
                    }
                    obj.add("data", dataObj);
                }
                yield obj;
            }
            case StepNode.ForEachStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "for_each");
                obj.add("list", exprToJson(s.list()));
                obj.addProperty("var", s.var());
                JsonArray bodyArr = new JsonArray();
                for (StepNode bodyStep : s.steps()) {
                    bodyArr.add(stepToJson(bodyStep));
                }
                obj.add("steps", bodyArr);
                yield obj;
            }
            case StepNode.IfStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "if");
                obj.add("condition", exprToJson(s.condition()));
                if (s.elseInvert()) {
                    obj.addProperty("else_invert", true);
                }
                if (!s.params().isEmpty()) {
                    JsonObject paramsObj = new JsonObject();
                    for (var entry : s.params().entrySet()) {
                        paramsObj.add(entry.getKey(), exprToJson(entry.getValue()));
                    }
                    obj.add("params", paramsObj);
                }
                JsonArray thenArr = new JsonArray();
                for (StepNode thenStep : s.thenSteps()) {
                    thenArr.add(stepToJson(thenStep));
                }
                obj.add("then", thenArr);
                JsonArray elseArr = new JsonArray();
                for (StepNode elseStep : s.elseSteps()) {
                    elseArr.add(stepToJson(elseStep));
                }
                obj.add("else", elseArr);
                yield obj;
            }
            case StepNode.CallStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "call");
                obj.add("blueprint", exprToJson(s.blueprintId()));
                if (!s.with().isEmpty()) {
                    JsonObject withObj = new JsonObject();
                    for (var entry : s.with().entrySet()) {
                        withObj.add(entry.getKey(), exprToJson(entry.getValue()));
                    }
                    obj.add("with", withObj);
                }
                yield obj;
            }
            case StepNode.ParallelStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "parallel");
                JsonArray stepsArr = new JsonArray();
                for (StepNode child : s.steps()) {
                    stepsArr.add(stepToJson(child));
                }
                obj.add("steps", stepsArr);
                yield obj;
            }
            case StepNode.LogStep s -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "log");
                obj.addProperty("level", s.level());
                obj.add("text", exprToJson(s.text()));
                yield obj;
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // ExprNode → JsonElement (with sugar syntax for simple cases)
    // ═══════════════════════════════════════════════════════════════

    static JsonElement exprToJson(ExprNode expr) {
        return switch (expr) {
            case ExprNode.LiteralString s -> new JsonPrimitive(s.value());
            case ExprNode.LiteralInt i -> new JsonPrimitive(i.value());
            case ExprNode.LiteralPos p -> posArray(p.value());
            case ExprNode.LiteralListPos l -> {
                JsonArray arr = new JsonArray();
                for (GridPos p : l.value()) arr.add(posArray(p));
                yield arr;
            }
            case ExprNode.LiteralListString l -> {
                JsonArray arr = new JsonArray();
                for (String s : l.value()) arr.add(new JsonPrimitive(s));
                yield arr;
            }
            case ExprNode.LiteralMap m -> {
                JsonObject obj = new JsonObject();
                for (var entry : m.value().entrySet()) {
                    obj.addProperty(entry.getKey(), entry.getValue());
                }
                yield obj;
            }
            case ExprNode.Var v -> new JsonPrimitive("$" + v.name()); // Sugar syntax
            case ExprNode.FieldAccess f -> {
                JsonObject obj = new JsonObject();
                obj.add("$.field", fieldAccessArray(f));
                yield obj;
            }
            case ExprNode.Add a -> binaryExpr("+", a.left(), a.right());
            case ExprNode.Sub s -> binaryExpr("-", s.left(), s.right());
            case ExprNode.Mul m -> binaryExpr("*", m.left(), m.right());
            case ExprNode.Eq e -> binaryExpr("==", e.left(), e.right());
            case ExprNode.Neq e -> binaryExpr("!=", e.left(), e.right());
            case ExprNode.Gt e -> binaryExpr(">", e.left(), e.right());
            case ExprNode.Lt e -> binaryExpr("<", e.left(), e.right());
            case ExprNode.Gte e -> binaryExpr(">=", e.left(), e.right());
            case ExprNode.Lte e -> binaryExpr("<=", e.left(), e.right());
            case ExprNode.MapGet mg -> {
                JsonObject obj = new JsonObject();
                JsonArray args = new JsonArray();
                args.add(exprToJson(mg.map()));
                args.add(exprToJson(mg.key()));
                obj.add("get", args);
                yield obj;
            }
            case ExprNode.Size sz -> {
                JsonObject obj = new JsonObject();
                obj.add("size", exprToJson(sz.target()));
                yield obj;
            }
            case ExprNode.Format f -> {
                JsonObject obj = new JsonObject();
                JsonArray arr = new JsonArray();
                arr.add(exprToJson(f.template()));
                for (var arg : f.args()) arr.add(exprToJson(arg));
                obj.add("format", arr);
                yield obj;
            }
            case ExprNode.KeyOf k -> {
                JsonObject obj = new JsonObject();
                obj.add("keyof", exprToJson(k.target()));
                yield obj;
            }
            case ExprNode.MapItems mi -> {
                JsonObject obj = new JsonObject();
                JsonObject inner = new JsonObject();
                inner.add("list", exprToJson(mi.list()));
                inner.addProperty("as", mi.loopVar());
                inner.add("resource", exprToJson(mi.resource()));
                inner.add("amount", exprToJson(mi.amount()));
                obj.add("map_to_items", inner);
                yield obj;
            }
        };
    }

    // ── Expression JSON helpers ──

    private static JsonArray posArray(GridPos p) {
        JsonArray arr = new JsonArray();
        arr.add(p.x());
        arr.add(p.y());
        arr.add(p.z());
        return arr;
    }

    private static JsonArray fieldAccessArray(ExprNode.FieldAccess f) {
        JsonArray arr = new JsonArray();
        arr.add(exprToJson(f.target()));
        arr.add(new JsonPrimitive(f.field()));
        return arr;
    }

    private static JsonElement binaryExpr(String op, ExprNode left, ExprNode right) {
        JsonObject obj = new JsonObject();
        JsonArray args = new JsonArray();
        args.add(exprToJson(left));
        args.add(exprToJson(right));
        obj.add(op, args);
        return obj;
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON → StepNode (for deserialization)
    // ═══════════════════════════════════════════════════════════════

    static StepNode stepFromJson(JsonObject obj) {
        String type = obj.get("type").getAsString();
        return switch (type) {
            case "place" -> {
                ExprNode at = exprFromJson(obj.get("at"));
                ExprNode block = exprFromJson(obj.get("block"));
                ExprNode consumable = obj.has("consumable")
                        ? exprFromJson(obj.get("consumable")) : null;
                yield consumable != null
                        ? new StepNode.PlaceStep(at, block, consumable)
                        : new StepNode.PlaceStep(at, block);
            }
            case "remove" -> new StepNode.RemoveStep(
                    exprFromJson(obj.get("at")),
                    exprFromJson(obj.get("from")));
            case "convert" -> new StepNode.ConvertStep(
                    exprFromJson(obj.get("at")),
                    exprFromJson(obj.get("from")),
                    exprFromJson(obj.get("to")));
            case "block_interact" -> {
                ExprNode at = exprFromJson(obj.get("at"));
                String action = obj.get("action").getAsString();
                Map<String, ExprNode> params = new LinkedHashMap<>();
                if (obj.has("params")) {
                    JsonObject paramsObj = obj.getAsJsonObject("params");
                    for (var entry : paramsObj.entrySet()) {
                        params.put(entry.getKey(), exprFromJson(entry.getValue()));
                    }
                }
                ExprNode channelTicks = obj.has("channel_ticks")
                        ? exprFromJson(obj.get("channel_ticks"))
                        : new ExprNode.LiteralInt(0);
                ExprNode manaCost = obj.has("mana_cost")
                        ? exprFromJson(obj.get("mana_cost"))
                        : new ExprNode.LiteralInt(1);
                yield new StepNode.BlockInteractStep(at, action, params, channelTicks, manaCost);
            }
            case "entity_interact" -> new StepNode.EntityInteractStep(
                    exprFromJson(obj.get("target")),
                    exprFromJson(obj.get("effect")),
                    exprFromJson(obj.get("strength")),
                    exprFromJson(obj.get("duration")));
            case "ritual" -> {
                ExprNode ritual = exprFromJson(obj.get("ritual"));
                ExprNode at = exprFromJson(obj.get("at"));
                Map<String, ExprNode> params = new LinkedHashMap<>();
                if (obj.has("params")) {
                    JsonObject paramsObj = obj.getAsJsonObject("params");
                    for (var entry : paramsObj.entrySet()) {
                        params.put(entry.getKey(), exprFromJson(entry.getValue()));
                    }
                }
                yield new StepNode.RitualStep(ritual, at, params);
            }
            case "request_resource" -> {
                if (obj.has("items")) {
                    JsonElement itemsEl = obj.get("items");
                    if (itemsEl.isJsonArray()) {
                        // Static items: [{resource, amount}, ...]
                        JsonArray itemsArr = itemsEl.getAsJsonArray();
                        List<StepNode.RequestResourceStep.ResourceEntry> items = new ArrayList<>();
                        for (JsonElement el : itemsArr) {
                            JsonObject itemObj = el.getAsJsonObject();
                            items.add(new StepNode.RequestResourceStep.ResourceEntry(
                                    exprFromJson(itemObj.get("resource")),
                                    exprFromJson(itemObj.get("amount"))));
                        }
                        yield new StepNode.RequestResourceStep(items, null);
                    } else {
                        // Dynamic expression: {"map_to_items": {...}} etc.
                        yield new StepNode.RequestResourceStep(List.of(),
                                exprFromJson(itemsEl));
                    }
                }
                yield new StepNode.RequestResourceStep(List.of());
            }
            case "emit_event" -> {
                ExprNode event = exprFromJson(obj.get("event"));
                Map<String, ExprNode> data = new LinkedHashMap<>();
                if (obj.has("data")) {
                    JsonObject dataObj = obj.getAsJsonObject("data");
                    for (var entry : dataObj.entrySet()) {
                        data.put(entry.getKey(), exprFromJson(entry.getValue()));
                    }
                }
                yield new StepNode.EmitEventStep(event, data);
            }
            case "for_each" -> {
                ExprNode list = exprFromJson(obj.get("list"));
                String var = obj.get("var").getAsString();
                List<StepNode> body = new ArrayList<>();
                if (obj.has("steps")) {
                    JsonArray stepsArr = obj.getAsJsonArray("steps");
                    for (JsonElement el : stepsArr) {
                        StepNode s = stepFromJson(el.getAsJsonObject());
                        if (s != null) body.add(s);
                    }
                }
                yield new StepNode.ForEachStep(list, var, body);
            }
            case "if" -> {
                ExprNode condition = exprFromJson(obj.get("condition"));
                boolean elseInvert = obj.has("else_invert") && obj.get("else_invert").getAsBoolean();
                Map<String, ExprNode> params = new LinkedHashMap<>();
                if (obj.has("params")) {
                    JsonObject paramsObj = obj.getAsJsonObject("params");
                    for (var entry : paramsObj.entrySet()) {
                        params.put(entry.getKey(), exprFromJson(entry.getValue()));
                    }
                }
                List<StepNode> thenSteps = new ArrayList<>();
                if (obj.has("then")) {
                    for (JsonElement el : obj.getAsJsonArray("then")) {
                        StepNode s = stepFromJson(el.getAsJsonObject());
                        if (s != null) thenSteps.add(s);
                    }
                }
                List<StepNode> elseSteps = new ArrayList<>();
                if (obj.has("else")) {
                    for (JsonElement el : obj.getAsJsonArray("else")) {
                        StepNode s = stepFromJson(el.getAsJsonObject());
                        if (s != null) elseSteps.add(s);
                    }
                }
                yield new StepNode.IfStep(condition, params, elseInvert, thenSteps, elseSteps);
            }
            case "call" -> {
                ExprNode blueprintId = exprFromJson(obj.get("blueprint"));
                Map<String, ExprNode> with = new LinkedHashMap<>();
                if (obj.has("with")) {
                    JsonObject withObj = obj.getAsJsonObject("with");
                    for (var entry : withObj.entrySet()) {
                        with.put(entry.getKey(), exprFromJson(entry.getValue()));
                    }
                }
                yield new StepNode.CallStep(blueprintId, with);
            }
            case "parallel" -> {
                List<StepNode> steps = new ArrayList<>();
                if (obj.has("steps")) {
                    for (JsonElement el : obj.getAsJsonArray("steps")) {
                        StepNode s = stepFromJson(el.getAsJsonObject());
                        if (s != null) steps.add(s);
                    }
                }
                yield new StepNode.ParallelStep(steps);
            }
            case "log" -> new StepNode.LogStep(
                    obj.has("level") ? obj.get("level").getAsString() : "info",
                    exprFromJson(obj.get("text")));
            default -> {
                Log.warn(TAG, "Unknown step type: {}", type);
                yield null;
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON → ExprNode
    // ═══════════════════════════════════════════════════════════════

    static ExprNode exprFromJson(JsonElement el) {
        if (el.isJsonPrimitive()) {
            JsonPrimitive prim = el.getAsJsonPrimitive();
            if (prim.isString()) {
                String s = prim.getAsString();
                // Sugar syntax: "$var" → Var
                if (s.startsWith("$")) {
                    return new ExprNode.Var(s.substring(1));
                }
                return new ExprNode.LiteralString(s);
            }
            if (prim.isNumber()) {
                return new ExprNode.LiteralInt(prim.getAsInt());
            }
            return new ExprNode.LiteralString(prim.getAsString());
        }

        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            // Check if it's a pos array [x, y, z]
            if (arr.size() == 3 && arr.get(0).isJsonPrimitive()
                    && arr.get(0).getAsJsonPrimitive().isNumber()) {
                return new ExprNode.LiteralPos(new GridPos(
                        arr.get(0).getAsInt(),
                        arr.get(1).getAsInt(),
                        arr.get(2).getAsInt()));
            }
            // Generic array → treat as list of positions or strings
            List<GridPos> posList = new ArrayList<>();
            List<String> strList = new ArrayList<>();
            boolean allPos = true;
            for (JsonElement elem : arr) {
                if (elem.isJsonArray()) {
                    JsonArray posArr = elem.getAsJsonArray();
                    if (posArr.size() == 3) {
                        posList.add(new GridPos(posArr.get(0).getAsInt(),
                                posArr.get(1).getAsInt(), posArr.get(2).getAsInt()));
                    } else {
                        allPos = false;
                    }
                } else {
                    allPos = false;
                    strList.add(elem.getAsString());
                }
            }
            if (allPos && !posList.isEmpty()) return new ExprNode.LiteralListPos(posList);
            if (!strList.isEmpty()) return new ExprNode.LiteralListString(strList);
            return new ExprNode.LiteralListString(List.of());
        }

        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            // Single-key operator objects: {"+": [...]}, {"get": [...]}, etc.
            for (var entry : obj.entrySet()) {
                String key = entry.getKey();
                JsonElement val = entry.getValue();

                return switch (key) {
                    case "$" -> new ExprNode.Var(val.getAsString());
                    case "$.field" -> {
                        JsonArray fieldArr = val.getAsJsonArray();
                        yield new ExprNode.FieldAccess(
                                exprFromJson(fieldArr.get(0)),
                                fieldArr.get(1).getAsString());
                    }
                    case "+" -> {
                        JsonArray args = val.getAsJsonArray();
                        yield new ExprNode.Add(exprFromJson(args.get(0)),
                                exprFromJson(args.get(1)));
                    }
                    case "-" -> {
                        JsonArray args = val.getAsJsonArray();
                        yield new ExprNode.Sub(exprFromJson(args.get(0)),
                                exprFromJson(args.get(1)));
                    }
                    case "*" -> {
                        JsonArray args = val.getAsJsonArray();
                        yield new ExprNode.Mul(exprFromJson(args.get(0)),
                                exprFromJson(args.get(1)));
                    }
                    case "==" -> {
                        JsonArray args = val.getAsJsonArray();
                        yield new ExprNode.Eq(exprFromJson(args.get(0)),
                                exprFromJson(args.get(1)));
                    }
                    case "!=" -> {
                        JsonArray args = val.getAsJsonArray();
                        yield new ExprNode.Neq(exprFromJson(args.get(0)),
                                exprFromJson(args.get(1)));
                    }
                    case ">" -> {
                        JsonArray args = val.getAsJsonArray();
                        yield new ExprNode.Gt(exprFromJson(args.get(0)),
                                exprFromJson(args.get(1)));
                    }
                    case "<" -> {
                        JsonArray args = val.getAsJsonArray();
                        yield new ExprNode.Lt(exprFromJson(args.get(0)),
                                exprFromJson(args.get(1)));
                    }
                    case ">=" -> {
                        JsonArray args = val.getAsJsonArray();
                        yield new ExprNode.Gte(exprFromJson(args.get(0)),
                                exprFromJson(args.get(1)));
                    }
                    case "<=" -> {
                        JsonArray args = val.getAsJsonArray();
                        yield new ExprNode.Lte(exprFromJson(args.get(0)),
                                exprFromJson(args.get(1)));
                    }
                    case "get" -> {
                        JsonArray args = val.getAsJsonArray();
                        yield new ExprNode.MapGet(exprFromJson(args.get(0)),
                                exprFromJson(args.get(1)));
                    }
                    case "size" -> new ExprNode.Size(exprFromJson(val));
                    case "format" -> {
                        JsonArray args = val.getAsJsonArray();
                        ExprNode template = exprFromJson(args.get(0));
                        List<ExprNode> fmtArgs = new ArrayList<>();
                        for (int i = 1; i < args.size(); i++) {
                            fmtArgs.add(exprFromJson(args.get(i)));
                        }
                        yield new ExprNode.Format(template, fmtArgs);
                    }
                    case "keyof" -> new ExprNode.KeyOf(exprFromJson(val));
                    case "map_to_items" -> {
                        JsonObject inner = val.getAsJsonObject();
                        yield new ExprNode.MapItems(
                                exprFromJson(inner.get("list")),
                                inner.get("as").getAsString(),
                                exprFromJson(inner.get("resource")),
                                exprFromJson(inner.get("amount")));
                    }
                    default -> {
                        // Assume it's a string map literal
                        Map<String, String> map = new LinkedHashMap<>();
                        for (var mapEntry : obj.entrySet()) {
                            map.put(mapEntry.getKey(),
                                    mapEntry.getValue().getAsString());
                        }
                        yield new ExprNode.LiteralMap(map);
                    }
                };
            }
            // Empty object → empty map
            return new ExprNode.LiteralMap(Map.of());
        }

        return new ExprNode.LiteralString("");
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private static String paramTypeToString(ParamType type) {
        if (type instanceof ParamType.StringType) return "string";
        if (type instanceof ParamType.IntType) return "int";
        if (type instanceof ParamType.PosType) return "pos";
        if (type instanceof ParamType.ListPosType) return "list<pos>";
        if (type instanceof ParamType.ListStringType) return "list<string>";
        if (type instanceof ParamType.MapStringStringType) return "map<string,string>";
        return "string";
    }
}
