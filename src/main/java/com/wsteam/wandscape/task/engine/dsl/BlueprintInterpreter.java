package com.wsteam.wandscape.task.engine.dsl;

import java.util.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.core.types.*;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.task.runtime.TaskSequence;

/**
 * Runtime interpreter for the Blueprint DSL.
 *
 * <p>Evaluates a {@link BlueprintDefinition} against concrete {@code params}
 * and produces a flat {@link TaskSequence} of {@link AtomicOp}s.
 *
 * <p>Key behaviors:
 * <ul>
 *   <li><b>Expressions</b> are evaluated bottom-up against the current context
 *       (blueprint params + loop variables).</li>
 *   <li><b>{@code for_each}</b> expands its body once per list element.</li>
 *   <li><b>{@code if}</b> evaluates condition, then expands one branch.</li>
 *   <li><b>{@code call}</b> macro-expands another blueprint's steps inline,
 *       with recursion detection.</li>
 *   <li><b>{@code log}</b> emits engine log output directly (no AtomicOp).</li>
 *   <li>All errors throw {@link BlueprintInterpretException}, which maps to
 *       task FAILED state.</li>
 * </ul>
 */
public final class BlueprintInterpreter {

    private static final String TAG = "BlueprintInterp";

    private final BlueprintRegistry registry;

    public BlueprintInterpreter(BlueprintRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Interpret a blueprint definition with the given params.
     *
     * @param definition the DSL AST to interpret
     * @param params     the concrete parameter values (key = param name)
     * @return a fully expanded TaskSequence ready for the task pool
     * @throws BlueprintInterpretException on any DSL error
     */
    public TaskSequence interpret(BlueprintDefinition definition,
                                  Map<String, JsonElement> params) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(params, "params must not be null");

        // Validate required params
        validateParams(definition, params);

        // Create root context (immutable snapshot of supplied params)
        Map<String, JsonElement> context = new HashMap<>(params);

        // Expand root steps
        Set<String> callStack = new HashSet<>();
        callStack.add(definition.id());
        List<AtomicOp> ops = expandSteps(definition.steps(), context, callStack);

        String label = buildLabel(definition, params);
        return new TaskSequence(ops, label);
    }

    // ─────────────────────────────────────────────────────────────────
    // Step expansion
    // ─────────────────────────────────────────────────────────────────

    private List<AtomicOp> expandSteps(List<StepNode> steps,
                                        Map<String, JsonElement> context,
                                        Set<String> callStack) {
        List<AtomicOp> result = new ArrayList<>();
        for (StepNode step : steps) {
            result.addAll(expandStep(step, context, callStack));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<AtomicOp> expandStep(StepNode step,
                                       Map<String, JsonElement> context,
                                       Set<String> callStack) {
        return switch (step) {
            case StepNode.PlaceStep s -> {
                GridPos at = evalPos(s.at(), context, "place.at");
                BlockType block = new BlockType(evalString(s.block(), context, "place.block"));
                ResourceStack consumable = null;
                if (s.consumable() != null) {
                    String resourceId = evalString(s.consumable(), context, "place.consumable");
                    consumable = new ResourceStack(new ResourceId(resourceId), 1);
                }
                String nbt = s.nbt() != null
                        ? evalString(s.nbt(), context, "place.nbt")
                        : null;
                yield List.of(AtomicOp.TransformOp.place(at, block, consumable, nbt));
            }
            case StepNode.RemoveStep s -> {
                GridPos at = evalPos(s.at(), context, "remove.at");
                BlockType from = new BlockType(evalString(s.from(), context, "remove.from"));
                yield List.of(AtomicOp.TransformOp.remove(at, from, Collections.emptyList()));
            }
            case StepNode.ConvertStep s -> {
                GridPos at = evalPos(s.at(), context, "convert.at");
                BlockType from = new BlockType(evalString(s.from(), context, "convert.from"));
                BlockType to = new BlockType(evalString(s.to(), context, "convert.to"));
                yield List.of(AtomicOp.TransformOp.convert(at, from, to));
            }
            case StepNode.BlockInteractStep s -> {
                GridPos at = evalPos(s.at(), context, "block_interact.at");
                InteractAction action = new InteractAction(s.action());
                int channelTicks = evalInt(s.channelTicks(), context, "block_interact.channel_ticks");
                float manaCost = (float) evalInt(s.manaCost(), context, "block_interact.mana_cost");
                Map<String, String> params = new LinkedHashMap<>();
                for (var entry : s.params().entrySet()) {
                    params.put(entry.getKey(),
                            evalString(entry.getValue(), context, "block_interact.params." + entry.getKey()));
                }
                yield List.of(new AtomicOp.BlockInteractOp(at, action, params, channelTicks, manaCost));
            }
            case StepNode.EntityInteractStep s -> {
                String targetStr = evalString(s.target(), context, "entity_interact.target");
                EntityId target = new EntityId(parseEntityId(targetStr));
                EffectId effect = new EffectId(evalString(s.effect(), context, "entity_interact.effect"));
                int strength = evalInt(s.strength(), context, "entity_interact.strength");
                int duration = evalInt(s.duration(), context, "entity_interact.duration");
                yield List.of(new AtomicOp.EntityInteractOp(target, effect, strength, duration));
            }
            case StepNode.RitualStep s -> {
                RitualId ritual = new RitualId(evalString(s.ritual(), context, "ritual.ritual"));
                GridPos at = evalPos(s.at(), context, "ritual.at");
                Map<String, String> params = new LinkedHashMap<>();
                for (var entry : s.params().entrySet()) {
                    params.put(entry.getKey(),
                            evalString(entry.getValue(), context, "ritual.params." + entry.getKey()));
                }
                yield List.of(new AtomicOp.RitualOp(ritual, at, params));
            }
            case StepNode.RequestResourceStep s -> {
                List<ResourceStack> stacks;
                if (s.dynamicItems() != null) {
                    // Dynamic: evaluate expression → [{resource, amount}, ...]
                    JsonElement result = evaluate(s.dynamicItems(), context);
                    if (!result.isJsonArray()) {
                        throw new BlueprintInterpretException(
                                "request_resource dynamic items must evaluate to an array, got: " + result);
                    }
                    stacks = new ArrayList<>();
                    for (JsonElement el : result.getAsJsonArray()) {
                        JsonObject itemObj = el.getAsJsonObject();
                        String res = itemObj.get("resource").getAsString();
                        int amt = itemObj.get("amount").getAsInt();
                        stacks.add(new ResourceStack(new ResourceId(res), amt));
                    }
                } else {
                    // Static items path
                    stacks = new ArrayList<>();
                    for (var entry : s.items()) {
                        String resource = evalString(entry.resource(), context, "request_resource.resource");
                        int amount = evalInt(entry.amount(), context, "request_resource.amount");
                        stacks.add(new ResourceStack(new ResourceId(resource), amount));
                    }
                }
                if (stacks.isEmpty()) {
                    yield List.of(); // nothing to request — skip the op
                } else {
                    yield List.of(new AtomicOp.ResourceRequestOp(stacks));
                }
            }
            case StepNode.EmitEventStep s -> {
                String event = evalString(s.event(), context, "emit_event.event");
                Map<String, String> data = new LinkedHashMap<>();
                for (var entry : s.data().entrySet()) {
                    data.put(entry.getKey(),
                            evalString(entry.getValue(), context, "emit_event.data." + entry.getKey()));
                }
                yield List.of(new AtomicOp.EmitEventOp(event, data));
            }
            case StepNode.ForEachStep s -> expandForEach(s, context, callStack);
            case StepNode.IfStep s -> expandIf(s, context, callStack);
            case StepNode.CallStep s -> expandCall(s, context, callStack);
            case StepNode.ParallelStep s -> {
                List<AtomicOp> subOps = expandSteps(s.steps(), context, callStack);
                yield List.of(new AtomicOp.ParallelOp(subOps));
            }
            case StepNode.LogStep s -> {
                String text = evalString(s.text(), context, "log.text");
                String level = s.level();
                switch (level) {
                    case "warn" -> Log.warn(TAG, "%s", text);
                    case "debug" -> { /* FINE logs removed */ }
                    default -> Log.info(TAG, "%s", text);
                }
                yield List.of(); // No AtomicOp
            }
        };
    }

    // ── for_each ──

    private List<AtomicOp> expandForEach(StepNode.ForEachStep step,
                                          Map<String, JsonElement> context,
                                          Set<String> callStack) {
        JsonElement listEl = evaluate(step.list(), context);

        if (!listEl.isJsonArray()) {
            throw new BlueprintInterpretException(
                    "for_each list must evaluate to an array, got: " + listEl);
        }

        // Shadowing detection: loop var must not hide outer var
        if (context.containsKey(step.var())) {
            throw new BlueprintInterpretException(
                    "for_each variable '" + step.var() + "' shadows an existing variable");
        }

        JsonArray array = listEl.getAsJsonArray();
        List<AtomicOp> result = new ArrayList<>();

        for (JsonElement element : array) {
            // Create inner context with loop variable bound
            Map<String, JsonElement> innerContext = new HashMap<>(context);
            innerContext.put(step.var(), element);
            result.addAll(expandSteps(step.steps(), innerContext, callStack));
        }

        return result;
    }

    // ── if ──

    private List<AtomicOp> expandIf(StepNode.IfStep step,
                                     Map<String, JsonElement> context,
                                     Set<String> callStack) {
        String conditionName = evalString(step.condition(), context, "if.condition");

        // Build condition params map
        Map<String, String> condParams = new LinkedHashMap<>();
        for (var entry : step.params().entrySet()) {
            condParams.put(entry.getKey(),
                    evalString(entry.getValue(), context, "if.params." + entry.getKey()));
        }

        // Determine which branch to take.
        // The actual condition evaluation happens at op-execution time
        // (by the IfConditionExecutor looking up the condition evaluator).
        // We emit both branches separated by an IfConditionOp.
        List<AtomicOp> thenOps = expandSteps(step.thenSteps(), context, callStack);
        List<AtomicOp> elseOps = expandSteps(step.elseSteps(), context, callStack);

        List<AtomicOp> result = new ArrayList<>();

        if (step.elseInvert()) {
            // elseInvert: swap then/else semantics relative to condition
            // → condition=false → skip then → jump to else
            result.addAll(thenOps);
            // IfConditionOp with elseSkip=true: skip when condition=false
            result.add(new AtomicOp.IfConditionOp(conditionName, condParams,
                    elseOps.size(), true));
            result.addAll(elseOps);
        } else {
            // Normal: condition=true → skip else → jump to then
            result.addAll(thenOps);
            // IfConditionOp with elseSkip=false: skip when condition=true
            result.add(new AtomicOp.IfConditionOp(conditionName, condParams,
                    elseOps.size(), false));
            result.addAll(elseOps);
        }

        return result;
    }

    // ── call ──

    private List<AtomicOp> expandCall(StepNode.CallStep step,
                                       Map<String, JsonElement> context,
                                       Set<String> callStack) {
        String calleeId = evalString(step.blueprintId(), context, "call.blueprint");

        // Recursion detection
        if (callStack.contains(calleeId)) {
            throw new BlueprintInterpretException(
                    "Recursive call detected: " + calleeId
                    + " is already in the call stack " + callStack);
        }

        // Look up the callee blueprint
        Blueprint calleeBp = registry.get(calleeId);
        if (calleeBp == null) {
            throw new BlueprintInterpretException(
                    "Unknown blueprint in call: " + calleeId);
        }

        // Must be a DSL blueprint with definition
        BlueprintDefinition calleeDef = calleeBp.definition();
        if (calleeDef == null) {
            throw new BlueprintInterpretException(
                    "Cannot macro-expand lambda blueprint without definition: " + calleeId
                    + ". Only DSL blueprints (with BlueprintDefinition) support call.");
        }

        // Build callee context: evaluate "with" expressions against caller context
        Map<String, JsonElement> calleeContext = new HashMap<>();
        for (var withEntry : step.with().entrySet()) {
            String paramName = withEntry.getKey();
            ExprNode valueExpr = withEntry.getValue();
            JsonElement value = evaluate(valueExpr, context);
            calleeContext.put(paramName, value);
        }

        // Validate: all declared params must be provided
        for (var paramEntry : calleeDef.params().entrySet()) {
            if (!calleeContext.containsKey(paramEntry.getKey())) {
                throw new BlueprintInterpretException(
                        "call to '" + calleeId + "' missing required param '"
                        + paramEntry.getKey() + "' (type: " + paramEntry.getValue() + ")");
            }
        }

        // Expand callee steps with new call stack (includes callee for recursion detection)
        Set<String> newStack = new HashSet<>(callStack);
        newStack.add(calleeId);
        return expandSteps(calleeDef.steps(), calleeContext, newStack);
    }

    // ─────────────────────────────────────────────────────────────────
    // Expression evaluation
    // ─────────────────────────────────────────────────────────────────

    /**
     * Evaluate an expression node against the given context.
     * Returns the resulting JsonElement.
     */
    JsonElement evaluate(ExprNode expr, Map<String, JsonElement> context) {
        return switch (expr) {
            case ExprNode.LiteralString s -> new JsonPrimitive(s.value());
            case ExprNode.LiteralInt i -> new JsonPrimitive(i.value());
            case ExprNode.LiteralPos p -> posToJson(p.value());
            case ExprNode.LiteralListPos l -> {
                JsonArray arr = new JsonArray();
                for (GridPos p : l.value()) arr.add(posToJson(p));
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
            case ExprNode.Var v -> {
                JsonElement val = context.get(v.name());
                if (val == null) {
                    throw new BlueprintInterpretException(
                            "Undefined variable: $" + v.name());
                }
                yield val;
            }
            case ExprNode.FieldAccess f -> {
                GridPos pos = evalPos(f.target(), context, "fieldAccess");
                yield switch (f.field()) {
                    case "x" -> new JsonPrimitive(pos.x());
                    case "y" -> new JsonPrimitive(pos.y());
                    case "z" -> new JsonPrimitive(pos.z());
                    default -> throw new BlueprintInterpretException(
                            "Unknown field: " + f.field() + " (expected x, y, or z)");
                };
            }
            case ExprNode.Add a -> evalArithmetic(a.left(), a.right(), context, true);
            case ExprNode.Sub s -> {
                int left = evalInt(s.left(), context, "sub.left");
                int right = evalInt(s.right(), context, "sub.right");
                yield new JsonPrimitive(left - right);
            }
            case ExprNode.Mul m -> {
                int left = evalInt(m.left(), context, "mul.left");
                int right = evalInt(m.right(), context, "mul.right");
                yield new JsonPrimitive(left * right);
            }
            case ExprNode.Eq e -> evalCompare(e.left(), e.right(), context, "==");
            case ExprNode.Neq e -> evalCompare(e.left(), e.right(), context, "!=");
            case ExprNode.Gt e -> evalCompare(e.left(), e.right(), context, ">");
            case ExprNode.Lt e -> evalCompare(e.left(), e.right(), context, "<");
            case ExprNode.Gte e -> evalCompare(e.left(), e.right(), context, ">=");
            case ExprNode.Lte e -> evalCompare(e.left(), e.right(), context, "<=");
            case ExprNode.MapGet g -> evalMapGet(g, context);
            case ExprNode.Size s -> evalSize(s, context);
            case ExprNode.Format f -> evalFormat(f, context);
            case ExprNode.KeyOf k -> {
                GridPos pos = evalPos(k.target(), context, "keyof");
                yield new JsonPrimitive(pos.x() + "," + pos.y() + "," + pos.z());
            }
            case ExprNode.MapItems mi -> {
                JsonElement listEl = evaluate(mi.list(), context);
                if (!listEl.isJsonArray()) {
                    throw new BlueprintInterpretException(
                            "map_to_items: list must evaluate to an array, got: " + listEl);
                }
                JsonArray input = listEl.getAsJsonArray();
                JsonArray output = new JsonArray();
                for (JsonElement element : input) {
                    Map<String, JsonElement> inner = new HashMap<>(context);
                    inner.put(mi.loopVar(), element);
                    JsonElement resVal = evaluate(mi.resource(), inner);
                    JsonElement amtVal = evaluate(mi.amount(), inner);
                    JsonObject item = new JsonObject();
                    item.add("resource", resVal);
                    item.add("amount", amtVal);
                    output.add(item);
                }
                yield output;
            }
        };
    }

    // ── Arithmetic helpers ──

    private JsonElement evalArithmetic(ExprNode left, ExprNode right,
                                        Map<String, JsonElement> context,
                                        boolean isAdd) {
        JsonElement l = evaluate(left, context);
        JsonElement r = evaluate(right, context);

        // pos + pos → pos
        if (isAdd && l.isJsonArray() && r.isJsonArray()) {
            GridPos lp = jsonToPos(l, "add.left");
            GridPos rp = jsonToPos(r, "add.right");
            return posToJson(new GridPos(lp.x() + rp.x(), lp.y() + rp.y(), lp.z() + rp.z()));
        }

        // int + int or int - int
        int li = jsonToInt(l, isAdd ? "add.left" : "sub.left");
        int ri = jsonToInt(r, isAdd ? "add.right" : "sub.right");
        return new JsonPrimitive(isAdd ? li + ri : li - ri);
    }

    // ── Comparison ──

    private JsonElement evalCompare(ExprNode left, ExprNode right,
                                     Map<String, JsonElement> context,
                                     String op) {
        JsonElement l = evaluate(left, context);
        JsonElement r = evaluate(right, context);

        // If both are int-typed, compare as ints
        if (isIntLike(l) && isIntLike(r)) {
            int li = jsonToInt(l, "compare.left");
            int ri = jsonToInt(r, "compare.right");
            return new JsonPrimitive(compareInts(li, ri, op));
        }

        // Otherwise compare as strings
        String ls = jsonToString(l);
        String rs = jsonToString(r);
        int cmp = ls.compareTo(rs);
        return new JsonPrimitive(compareInts(cmp, 0, op));
    }

    private static boolean compareInts(int a, int b, String op) {
        return switch (op) {
            case "==" -> a == b;
            case "!=" -> a != b;
            case ">"  -> a > b;
            case "<"  -> a < b;
            case ">=" -> a >= b;
            case "<=" -> a <= b;
            default   -> throw new BlueprintInterpretException("Unknown comparison: " + op);
        };
    }

    // ── Map get ──

    private JsonElement evalMapGet(ExprNode.MapGet g, Map<String, JsonElement> context) {
        JsonElement mapEl = evaluate(g.map(), context);
        if (!mapEl.isJsonObject()) {
            throw new BlueprintInterpretException(
                    "MapGet: map must evaluate to an object, got: " + mapEl);
        }
        JsonElement keyEl = evaluate(g.key(), context);

        // Implicit conversion: pos → string via toKey()
        String keyStr;
        if (keyEl.isJsonArray()) {
            // It's a pos — convert to "x,y,z" format
            GridPos pos = jsonToPos(keyEl, "mapGet.key");
            keyStr = pos.x() + "," + pos.y() + "," + pos.z();
        } else {
            keyStr = jsonToString(keyEl);
        }

        JsonElement result = mapEl.getAsJsonObject().get(keyStr);
        if (result == null) {
            Log.warn(TAG, "MapGet: key '%s' not found in map, using empty string", keyStr);
            return new JsonPrimitive("");
        }
        return result;
    }

    // ── Size ──

    private JsonElement evalSize(ExprNode.Size s, Map<String, JsonElement> context) {
        JsonElement target = evaluate(s.target(), context);
        if (!target.isJsonArray()) {
            throw new BlueprintInterpretException(
                    "size: target must be an array, got: " + target);
        }
        return new JsonPrimitive(target.getAsJsonArray().size());
    }

    // ── Format ──

    private JsonElement evalFormat(ExprNode.Format f, Map<String, JsonElement> context) {
        String template = evalString(f.template(), context, "format.template");
        List<String> argStrings = new ArrayList<>();
        for (int i = 0; i < f.args().size(); i++) {
            argStrings.add(evalString(f.args().get(i), context, "format.arg[" + i + "]"));
        }

        // Replace {} placeholders sequentially
        StringBuilder result = new StringBuilder();
        int argIndex = 0;
        int placeholder = template.indexOf("{}");
        int lastEnd = 0;
        while (placeholder >= 0 && argIndex < argStrings.size()) {
            result.append(template, lastEnd, placeholder);
            result.append(argStrings.get(argIndex));
            argIndex++;
            lastEnd = placeholder + 2;
            placeholder = template.indexOf("{}", lastEnd);
        }
        result.append(template.substring(lastEnd));

        return new JsonPrimitive(result.toString());
    }

    // ─────────────────────────────────────────────────────────────────
    // Typed evaluation conveniences
    // ─────────────────────────────────────────────────────────────────

    private String evalString(ExprNode expr, Map<String, JsonElement> context,
                              String fieldPath) {
        JsonElement val = evaluate(expr, context);
        return jsonToString(val);
    }

    private int evalInt(ExprNode expr, Map<String, JsonElement> context,
                        String fieldPath) {
        JsonElement val = evaluate(expr, context);
        return jsonToInt(val, fieldPath);
    }

    private GridPos evalPos(ExprNode expr, Map<String, JsonElement> context,
                            String fieldPath) {
        JsonElement val = evaluate(expr, context);
        return jsonToPos(val, fieldPath);
    }

    // ─────────────────────────────────────────────────────────────────
    // JsonElement conversion helpers
    // ─────────────────────────────────────────────────────────────────

    /** Convert a JsonElement to a string (with implicit int→string and pos→string). */
    static String jsonToString(JsonElement el) {
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        if (el.isJsonArray()) {
            // pos → "x,y,z" implicit conversion
            GridPos pos = jsonToPos(el, "implicit");
            return pos.x() + "," + pos.y() + "," + pos.z();
        }
        // Object → JSON representation
        return el.toString();
    }

    /** Convert a JsonElement to int (with implicit string→int parsing). */
    static int jsonToInt(JsonElement el, String fieldPath) {
        if (el.isJsonPrimitive()) {
            JsonPrimitive prim = el.getAsJsonPrimitive();
            try {
                if (prim.isNumber()) return prim.getAsInt();
                if (prim.isString()) return Integer.parseInt(prim.getAsString());
            } catch (NumberFormatException e) {
                throw new BlueprintInterpretException(
                        "Cannot parse as int: " + el + " at " + fieldPath);
            }
        }
        throw new BlueprintInterpretException(
                "Expected int value at " + fieldPath + ", got: " + el);
    }

    /** Convert a JsonElement to GridPos. */
    static GridPos jsonToPos(JsonElement el, String fieldPath) {
        if (!el.isJsonArray()) {
            throw new BlueprintInterpretException(
                    "Expected pos [x,y,z] at " + fieldPath + ", got: " + el);
        }
        JsonArray arr = el.getAsJsonArray();
        if (arr.size() != 3) {
            throw new BlueprintInterpretException(
                    "Expected pos [x,y,z] with 3 elements at " + fieldPath
                    + ", got " + arr.size() + " elements: " + arr);
        }
        return new GridPos(arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
    }

    /** Convert a GridPos to a JSON array [x,y,z]. */
    static JsonArray posToJson(GridPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.x());
        arr.add(pos.y());
        arr.add(pos.z());
        return arr;
    }

    /** Check if a JsonElement looks like an integer (for comparison type dispatch). */
    private static boolean isIntLike(JsonElement el) {
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber();
    }

    /**
     * Parse a string entity identifier to a long for {@link EntityId}.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Try as UUID (most significant bits)</li>
     *   <li>Try as numeric long string (direct ECS entity ID)</li>
     *   <li>Fallback: deterministic 64-bit hash (FNV-1a) of the name string</li>
     * </ol>
     *
     * <p>TODO: replace hash fallback with proper entity registry lookup
     * (ECS name index or player name→UUID resolution) when entity system
     * supports name-to-ID resolution at blueprint interpretation time.
     * The interpreter currently has no access to the ECS World.
     */
    private static long parseEntityId(String str) {
        // 1. Try UUID string
        try {
            return java.util.UUID.fromString(str).getMostSignificantBits();
        } catch (IllegalArgumentException ignored) {}

        // 2. Try numeric entity ID
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException ignored) {}

        // 3. Fallback: deterministic 64-bit non-negative hash (FNV-1a)
        long hash = 0xCBF29CE484222325L;
        for (int i = 0; i < str.length(); i++) {
            hash ^= str.charAt(i);
            hash *= 0x100000001B3L;
        }
        return hash;
    }

    // ─────────────────────────────────────────────────────────────────
    // Validation & label
    // ─────────────────────────────────────────────────────────────────

    /** Validate that all declared params are present. */
    private void validateParams(BlueprintDefinition definition,
                                 Map<String, JsonElement> params) {
        for (var entry : definition.params().entrySet()) {
            String name = entry.getKey();
            ParamType type = entry.getValue();
            if (!params.containsKey(name)) {
                throw new BlueprintInterpretException(
                        "Missing required param '" + name + "' (type: " + type
                        + ") for blueprint '" + definition.id() + "'");
            }
            // Type checking is deferred to expression evaluation:
            // when a step tries to use a param as a specific type (e.g. pos),
            // the jsonToPos/jsonToInt methods will throw if the value is wrong.
        }
    }

    /** Build a human-readable task label. */
    private String buildLabel(BlueprintDefinition definition,
                              Map<String, JsonElement> params) {
        String label = definition.displayName();
        if (label == null || label.isEmpty()) {
            label = definition.id();
        }

        // Append anchor if present
        JsonElement anchor = params.get("anchor");
        if (anchor != null && anchor.isJsonArray()) {
            try {
                GridPos pos = jsonToPos(anchor, "label.anchor");
                label += " at " + pos;
            } catch (BlueprintInterpretException ignored) {
                // Not a valid pos — skip appending
            }
        } else {
            // Fallback to x/y/z
            JsonElement x = params.get("x");
            JsonElement y = params.get("y");
            JsonElement z = params.get("z");
            if (x != null && y != null && z != null) {
                try {
                    label += " at (" + jsonToInt(x, "x") + ", "
                            + jsonToInt(y, "y") + ", " + jsonToInt(z, "z") + ")";
                } catch (BlueprintInterpretException ignored) {
                    // Skip
                }
            }
        }
        return label;
    }

    // ─────────────────────────────────────────────────────────────────
    // Exception type
    // ─────────────────────────────────────────────────────────────────

    /**
     * Thrown when blueprint interpretation fails.
     * Maps to task FAILED state in the engine.
     */
    public static final class BlueprintInterpretException extends RuntimeException {
        public BlueprintInterpretException(String message) {
            super(message);
        }

        public BlueprintInterpretException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
