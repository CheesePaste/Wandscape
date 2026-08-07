package com.wsteam.wandscape.engine.source.blueprint;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.google.gson.*;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.task.engine.dsl.*;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

/**
 * Loads blueprint JSON files from {@code data/wandscape/blueprints/}
 * and parses them into {@link BlueprintDefinition} ASTs.
 *
 * <p>Registered with {@code WandscapeDataLoader} as the {@code "blueprints"} category.
 * Call {@link #registerIn(BlueprintRegistry, BlueprintInterpreter)} after loading
 * to wire parsed definitions into the registry as executable {@link Blueprint}s.
 */
public final class BlueprintConfigLoader {

    private static final String TAG = "BlueprintCfg";

    private static final Gson GSON = new Gson();

    private final Map<String, BlueprintDefinition> definitions = new ConcurrentHashMap<>();

    public BlueprintConfigLoader() {}

    /**
     * Register the "blueprints" category with the data loader.
     */
    public WandscapeDataRegistry<BlueprintDefinition> registerWith(
            com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader loader) {
        return loader.register("blueprints", (id, json) -> parseDefinition(json));
    }

    /** Get a parsed definition by id. */
    @Nullable
    public BlueprintDefinition get(String id) {
        return definitions.get(id);
    }

    /** All loaded definitions. */
    public Map<String, BlueprintDefinition> getAll() {
        return Map.copyOf(definitions);
    }

    /**
     * Register all loaded DSL blueprints into the runtime registry.
     * Each definition is wrapped as a {@link BlueprintSteps} lambda that
     * delegates to the {@link BlueprintInterpreter}.
     */
    public void registerIn(BlueprintRegistry registry, BlueprintInterpreter interpreter) {
        for (var entry : definitions.entrySet()) {
            String id = entry.getKey();
            BlueprintDefinition def = entry.getValue();
            // Register with definition so call steps can macro-expand this blueprint
            registry.register(id, new Blueprint(id,
                    (BlueprintSteps) params -> interpreter.interpret(def, params),
                    def));
            Log.info(TAG, "registered blueprint: %s (params=%d steps=%d)",
                    id, def.params().size(), def.steps().size());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // JSON → BlueprintDefinition
    // ─────────────────────────────────────────────────────────────────

    private synchronized BlueprintDefinition parseDefinition(JsonElement json) {
        JsonObject obj = json.getAsJsonObject();

        String id = getString(obj, "id", null);
        if (id == null || id.isEmpty()) {
            Log.warn(TAG, "Blueprint missing id, skipping");
            return null;
        }

        String displayName = getString(obj, "display_name", "");
        String description = getString(obj, "description", "");

        // Parse params
        Map<String, ParamType> params = Collections.emptyMap();
        if (obj.has("params")) {
            params = parseParams(obj.getAsJsonObject("params"));
        }

        // Parse steps
        List<StepNode> steps = Collections.emptyList();
        if (obj.has("steps")) {
            steps = parseSteps(obj.getAsJsonArray("steps"));
        }

        BlueprintDefinition def = new BlueprintDefinition(id, params, steps, displayName, description);
        definitions.put(id, def);
        Log.info(TAG, "loaded blueprint: %s (params=%d steps=%d)", id, params.size(), steps.size());
        return def;
    }

    // ─────────────────────────────────────────────────────────────────
    // Params parsing
    // ─────────────────────────────────────────────────────────────────

    private Map<String, ParamType> parseParams(JsonObject obj) {
        Map<String, ParamType> params = new LinkedHashMap<>();
        for (var entry : obj.entrySet()) {
            String typeStr = entry.getValue().getAsString();
            ParamType type = ParamType.parse(typeStr);
            if (type == null) {
                Log.warn(TAG, "Unknown param type '%s' for param '%s', skipping",
                        typeStr, entry.getKey());
                continue;
            }
            params.put(entry.getKey(), type);
        }
        return Collections.unmodifiableMap(params);
    }

    // ─────────────────────────────────────────────────────────────────
    // Steps parsing
    // ─────────────────────────────────────────────────────────────────

    private List<StepNode> parseSteps(JsonArray array) {
        List<StepNode> steps = new ArrayList<>();
        for (JsonElement el : array) {
            JsonObject obj = el.getAsJsonObject();
            String type = getString(obj, "type", "");
            StepNode step = parseStep(type, obj);
            if (step != null) {
                steps.add(step);
            }
        }
        return Collections.unmodifiableList(steps);
    }

    private StepNode parseStep(String type, JsonObject obj) {
        return switch (type) {
            case "place" -> parsePlace(obj);
            case "remove" -> parseRemove(obj);
            case "convert" -> parseConvert(obj);
            case "block_interact" -> parseBlockInteract(obj);
            case "entity_interact" -> parseEntityInteract(obj);
            case "ritual" -> parseRitual(obj);
            case "altar_cast" -> parseAltarCast(obj);
            case "request_resource" -> parseRequestResource(obj);
            case "emit_event" -> parseEmitEvent(obj);
            case "for_each" -> parseForEach(obj);
            case "if" -> parseIf(obj);
            case "call" -> parseCall(obj);
            case "parallel" -> parseParallel(obj);
            case "log" -> parseLog(obj);
            default -> {
                Log.warn(TAG, "Unknown step type '%s', skipping step", type);
                yield null;
            }
        };
    }

    // ── Individual step parsers ──

    private StepNode parsePlace(JsonObject obj) {
        ExprNode at = parseExpr(obj.get("at"));
        ExprNode block = parseExpr(obj.get("block"));
        ExprNode consumable = obj.has("consumable") ? parseExpr(obj.get("consumable")) : null;
        ExprNode nbt = obj.has("nbt") ? parseExpr(obj.get("nbt")) : null;
        return new StepNode.PlaceStep(at, block, consumable, nbt);
    }

    private StepNode parseRemove(JsonObject obj) {
        ExprNode at = parseExpr(obj.get("at"));
        ExprNode from = parseExpr(obj.get("from"));
        return new StepNode.RemoveStep(at, from);
    }

    private StepNode parseConvert(JsonObject obj) {
        ExprNode at = parseExpr(obj.get("at"));
        ExprNode from = parseExpr(obj.get("from"));
        ExprNode to = parseExpr(obj.get("to"));
        return new StepNode.ConvertStep(at, from, to);
    }

    private StepNode parseBlockInteract(JsonObject obj) {
        ExprNode at = parseExpr(obj.get("at"));
        String action = obj.get("action").getAsString();
        ExprNode channelTicks = obj.has("channel_ticks") ? parseExpr(obj.get("channel_ticks"))
                : new ExprNode.LiteralInt(0);
        Map<String, ExprNode> params = new LinkedHashMap<>();
        if (obj.has("params")) {
            JsonObject paramsObj = obj.getAsJsonObject("params");
            for (var entry : paramsObj.entrySet()) {
                params.put(entry.getKey(), parseExpr(entry.getValue()));
            }
        }
        return new StepNode.BlockInteractStep(at, action, params, channelTicks);
    }

    private StepNode parseEntityInteract(JsonObject obj) {
        ExprNode target = parseExpr(obj.get("target"));
        ExprNode effect = parseExpr(obj.get("effect"));
        ExprNode strength = parseExpr(obj.get("strength"));
        ExprNode duration = parseExpr(obj.get("duration"));
        return new StepNode.EntityInteractStep(target, effect, strength, duration);
    }

    private StepNode parseRitual(JsonObject obj) {
        ExprNode ritual = parseExpr(obj.get("ritual"));
        ExprNode at = parseExpr(obj.get("at"));
        Map<String, ExprNode> params = new LinkedHashMap<>();
        if (obj.has("params")) {
            JsonObject paramsObj = obj.getAsJsonObject("params");
            for (var entry : paramsObj.entrySet()) {
                params.put(entry.getKey(), parseExpr(entry.getValue()));
            }
        }
        return new StepNode.RitualStep(ritual, at, params);
    }

    private StepNode parseAltarCast(JsonObject obj) {
        ExprNode at = parseExpr(obj.get("at"));
        ExprNode magicId = parseExpr(obj.get("magic_id"));
        Map<String, ExprNode> params = new LinkedHashMap<>();
        if (obj.has("params")) {
            JsonObject paramsObj = obj.getAsJsonObject("params");
            for (var entry : paramsObj.entrySet()) {
                params.put(entry.getKey(), parseExpr(entry.getValue()));
            }
        }
        return new StepNode.AltarCastStep(at, magicId, params);
    }

    private StepNode parseRequestResource(JsonObject obj) {
        if (obj.has("items")) {
            JsonElement itemsEl = obj.get("items");
            if (itemsEl.isJsonObject()) {
                // Dynamic: {"map_to_items": {...}} expression
                ExprNode dynamicItems = parseExpr(itemsEl);
                return new StepNode.RequestResourceStep(List.of(), dynamicItems);
            }
            // Static format: list of {resource, amount} pairs — all-or-nothing atomic request
            JsonArray itemsArr = itemsEl.getAsJsonArray();
            List<StepNode.RequestResourceStep.ResourceEntry> entries = new ArrayList<>();
            for (JsonElement el : itemsArr) {
                JsonObject itemObj = el.getAsJsonObject();
                ExprNode resource = parseExpr(itemObj.get("resource"));
                ExprNode amount = parseExpr(itemObj.get("amount"));
                entries.add(new StepNode.RequestResourceStep.ResourceEntry(resource, amount));
            }
            return new StepNode.RequestResourceStep(entries);
        }
        // Items key is required for request_resource steps
        throw new JsonParseException("request_resource step must have an 'items' array");
    }

    private StepNode parseEmitEvent(JsonObject obj) {
        ExprNode event = parseExpr(obj.get("event"));
        Map<String, ExprNode> data = new LinkedHashMap<>();
        if (obj.has("data")) {
            JsonObject dataObj = obj.getAsJsonObject("data");
            for (var entry : dataObj.entrySet()) {
                data.put(entry.getKey(), parseExpr(entry.getValue()));
            }
        }
        return new StepNode.EmitEventStep(event, data);
    }

    private StepNode parseForEach(JsonObject obj) {
        ExprNode list = parseExpr(obj.get("list"));
        String var = obj.get("var").getAsString();
        List<StepNode> bodySteps = parseSteps(obj.getAsJsonArray("steps"));
        return new StepNode.ForEachStep(list, var, bodySteps);
    }

    private StepNode parseIf(JsonObject obj) {
        ExprNode condition = parseExpr(obj.get("condition"));
        Map<String, ExprNode> params = new LinkedHashMap<>();
        if (obj.has("params")) {
            JsonObject paramsObj = obj.getAsJsonObject("params");
            for (var entry : paramsObj.entrySet()) {
                params.put(entry.getKey(), parseExpr(entry.getValue()));
            }
        }
        boolean elseInvert = obj.has("else_invert") && obj.get("else_invert").getAsBoolean();
        List<StepNode> thenSteps = parseSteps(obj.getAsJsonArray("then"));
        List<StepNode> elseSteps = obj.has("else")
                ? parseSteps(obj.getAsJsonArray("else"))
                : Collections.emptyList();
        return new StepNode.IfStep(condition, params, elseInvert, thenSteps, elseSteps);
    }

    private StepNode parseCall(JsonObject obj) {
        ExprNode blueprintId = parseExpr(obj.get("blueprint"));
        Map<String, ExprNode> with = new LinkedHashMap<>();
        if (obj.has("with")) {
            JsonObject withObj = obj.getAsJsonObject("with");
            for (var entry : withObj.entrySet()) {
                with.put(entry.getKey(), parseExpr(entry.getValue()));
            }
        }
        return new StepNode.CallStep(blueprintId, with);
    }

    private StepNode parseParallel(JsonObject obj) {
        List<StepNode> bodySteps = parseSteps(obj.getAsJsonArray("steps"));
        return new StepNode.ParallelStep(bodySteps);
    }

    private StepNode parseLog(JsonObject obj) {
        String level = obj.has("level") ? obj.get("level").getAsString() : "info";
        ExprNode text = parseExpr(obj.get("text"));
        return new StepNode.LogStep(level, text);
    }

    // ─────────────────────────────────────────────────────────────────
    // Expression parser
    // ─────────────────────────────────────────────────────────────────

    /**
     * Parse a JSON element into an {@link ExprNode}.
     *
     * <p>Inference rules:
     * <ul>
     *   <li>JsonPrimitive string starting with {@code $} (no braces) → {@link ExprNode.Var} (sugar)</li>
     *   <li>JsonPrimitive string → {@link ExprNode.LiteralString}</li>
     *   <li>JsonPrimitive number → {@link ExprNode.LiteralInt}</li>
     *   <li>JsonArray of 3 numbers → {@link ExprNode.LiteralPos}</li>
     *   <li>JsonArray of arrays → {@link ExprNode.LiteralListPos}</li>
     *   <li>JsonArray of strings → {@link ExprNode.LiteralListString}</li>
     *   <li>JsonObject → operator expression</li>
     * </ul>
     */
    ExprNode parseExpr(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return new ExprNode.LiteralString("");
        }

        if (el.isJsonPrimitive()) {
            JsonPrimitive prim = el.getAsJsonPrimitive();
            if (prim.isString()) {
                String str = prim.getAsString();
                // Sugar: "$var_name" → Var
                if (str.startsWith("$") && !str.contains("{")) {
                    return new ExprNode.Var(str.substring(1));
                }
                return new ExprNode.LiteralString(str);
            }
            if (prim.isNumber()) {
                return new ExprNode.LiteralInt(prim.getAsInt());
            }
            if (prim.isBoolean()) {
                return new ExprNode.LiteralString(Boolean.toString(prim.getAsBoolean()));
            }
        }

        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.isEmpty()) {
                return new ExprNode.LiteralListPos(Collections.emptyList());
            }
            // Heuristic: if first element is an array, it's list<pos>
            if (arr.get(0).isJsonArray()) {
                List<GridPos> positions = new ArrayList<>();
                for (JsonElement elem : arr) {
                    positions.add(parseGridPos(elem.getAsJsonArray()));
                }
                return new ExprNode.LiteralListPos(positions);
            }
            // Heuristic: if first element is a number and we have 3 elements, it's a pos
            if (arr.size() == 3 && arr.get(0).isJsonPrimitive()
                    && arr.get(0).getAsJsonPrimitive().isNumber()) {
                return new ExprNode.LiteralPos(parseGridPos(arr));
            }
            // Otherwise treat as list<string>
            List<String> strings = new ArrayList<>();
            for (JsonElement elem : arr) {
                strings.add(jsonElementToSimpleString(elem));
            }
            return new ExprNode.LiteralListString(strings);
        }

        if (el.isJsonObject()) {
            return parseObjectExpr(el.getAsJsonObject());
        }

        return new ExprNode.LiteralString(el.toString());
    }

    /**
     * Parse a JSON object as an operator expression.
     * Keys are operator names, values are operands.
     */
    private ExprNode parseObjectExpr(JsonObject obj) {
        // Single-key objects are operator expressions
        Set<String> keys = obj.keySet();
        if (keys.size() != 1) {
            // Multi-key → it's a literal map
            Map<String, String> map = new LinkedHashMap<>();
            for (var entry : obj.entrySet()) {
                map.put(entry.getKey(), jsonElementToSimpleString(entry.getValue()));
            }
            return new ExprNode.LiteralMap(map);
        }

        String op = keys.iterator().next();
        JsonElement operand = obj.get(op);

        return switch (op) {
            // Variable reference: {"$": "param_name"}
            case "$" -> new ExprNode.Var(operand.getAsString());

            // Field access: {"$.field": ["$pos_var", "x"]}
            case "$.field" -> {
                JsonArray arr = operand.getAsJsonArray();
                yield new ExprNode.FieldAccess(parseExpr(arr.get(0)), arr.get(1).getAsString());
            }

            // Arithmetic
            case "+", "-", "*" -> {
                JsonArray arr = operand.getAsJsonArray();
                ExprNode left = parseExpr(arr.get(0));
                ExprNode right = parseExpr(arr.get(1));
                yield switch (op) {
                    case "+" -> new ExprNode.Add(left, right);
                    case "-" -> new ExprNode.Sub(left, right);
                    case "*" -> new ExprNode.Mul(left, right);
                    default -> throw new IllegalStateException("unreachable");
                };
            }

            // Comparison
            case "==", "!=", ">", "<", ">=", "<=" -> {
                JsonArray arr = operand.getAsJsonArray();
                ExprNode left = parseExpr(arr.get(0));
                ExprNode right = parseExpr(arr.get(1));
                yield switch (op) {
                    case "==" -> new ExprNode.Eq(left, right);
                    case "!=" -> new ExprNode.Neq(left, right);
                    case ">" -> new ExprNode.Gt(left, right);
                    case "<" -> new ExprNode.Lt(left, right);
                    case ">=" -> new ExprNode.Gte(left, right);
                    case "<=" -> new ExprNode.Lte(left, right);
                    default -> throw new IllegalStateException("unreachable");
                };
            }

            // Map get: {"get": ["$map_var", "$key_expr"]}
            case "get" -> {
                JsonArray arr = operand.getAsJsonArray();
                yield new ExprNode.MapGet(parseExpr(arr.get(0)), parseExpr(arr.get(1)));
            }

            // Size: {"size": "$list_var"}
            case "size" -> new ExprNode.Size(parseExpr(operand));

            // KeyOf: {"keyof": "$pos_var"}
            case "keyof" -> new ExprNode.KeyOf(parseExpr(operand));

            // MapItems: {"map_to_items": {"list": ..., "as": "v", "resource": ..., "amount": ...}}
            case "map_to_items" -> {
                JsonObject mapObj = operand.getAsJsonObject();
                ExprNode list = parseExpr(mapObj.get("list"));
                String as = mapObj.get("as").getAsString();
                ExprNode resource = parseExpr(mapObj.get("resource"));
                ExprNode amount = parseExpr(mapObj.get("amount"));
                yield new ExprNode.MapItems(list, as, resource, amount);
            }

            // Format: {"format": ["template {}", "$arg1", "$arg2"]}
            case "format" -> {
                JsonArray arr = operand.getAsJsonArray();
                ExprNode template = parseExpr(arr.get(0));
                List<ExprNode> args = new ArrayList<>();
                for (int i = 1; i < arr.size(); i++) {
                    args.add(parseExpr(arr.get(i)));
                }
                yield new ExprNode.Format(template, args);
            }

            default -> {
                // Unknown operator → treat as literal map
                Log.warn(TAG, "Unknown expression operator '%s', treating as literal map", op);
                Map<String, String> map = new LinkedHashMap<>();
                for (var entry : obj.entrySet()) {
                    map.put(entry.getKey(), jsonElementToSimpleString(entry.getValue()));
                }
                yield new ExprNode.LiteralMap(map);
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private static String getString(JsonObject obj, String key, String def) {
        return obj.has(key) ? obj.get(key).getAsString() : def;
    }

    private static GridPos parseGridPos(JsonArray arr) {
        return new GridPos(arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
    }

    /** Convert a JsonElement to a simple string representation (no JSON quotes). */
    private static String jsonElementToSimpleString(JsonElement el) {
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }
}
