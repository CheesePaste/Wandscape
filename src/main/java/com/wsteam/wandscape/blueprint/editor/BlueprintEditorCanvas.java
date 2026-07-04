package com.wsteam.wandscape.blueprint.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

import com.wsteam.wandscape.task.engine.dsl.BlueprintDefinition;
import com.wsteam.wandscape.task.engine.dsl.ExprNode;
import com.wsteam.wandscape.task.engine.dsl.ParamType;
import com.wsteam.wandscape.task.engine.dsl.StepNode;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Mutable directed graph for the blueprint node editor canvas.
 *
 * <p>Three edge types exist conceptually:
 * <ul>
 *   <li><b>Exec edges</b> — connect step node exec-out pins to exec-in pins
 *       (white pulse lines). Control-flow nodes (ForEach/If/Parallel) have
 *       multiple exec-out pins.</li>
 *   <li><b>Data edges</b> — connect expression node output pins to data input
 *       pins on step nodes or other expression nodes (type-colored lines).</li>
 * </ul>
 *
 * <p>Pins are identified by {@code (nodeId, pinId)} tuples — there are no
 * standalone pin objects. Pin definitions come from {@link BlueprintNodeDefinition}.
 *
 * <p>This class also contains the bidirectional converter between
 * {@code CanvasGraph} and {@link BlueprintDefinition} (DSL AST).
 */
public final class BlueprintEditorCanvas {

    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private static final String TAG = "BlueprintCanvas";

    // ═══════════════════════════════════════════════════════════════
    // Core data records
    // ═══════════════════════════════════════════════════════════════

    /** A node on the canvas. */
    public static final class CanvasNode {
        public final long nodeId;
        public String typeId;
        public float posX, posY;
        /** Inline values for literals, var names, action enums, etc. */
        public final Map<String, String> inlineValues;
        /** Dynamic pin extra counts: pinId → extra instances (beyond the base 1). */
        public final Map<String, Integer> dynamicPinCounts;

        public CanvasNode(long nodeId, String typeId, float posX, float posY) {
            this.nodeId = nodeId;
            this.typeId = typeId;
            this.posX = posX;
            this.posY = posY;
            this.inlineValues = new LinkedHashMap<>();
            this.dynamicPinCounts = new LinkedHashMap<>();
        }

        public String getDisplayName() {
            BlueprintNodeDefinition.NodeDef def = BlueprintNodeDefinition.get(typeId);
            return def != null ? def.displayName() : typeId;
        }
    }

    /** An execution-flow edge between two step nodes. */
    public record ExecEdge(long fromNodeId, String fromPinId, long toNodeId, String toPinId) {
        public ExecEdge {
            Objects.requireNonNull(fromPinId);
            Objects.requireNonNull(toPinId);
        }
    }

    /** A data-flow edge between expression/step nodes. */
    public record DataEdge(long fromNodeId, String fromPinId, long toNodeId, String toPinId) {
        public DataEdge {
            Objects.requireNonNull(fromPinId);
            Objects.requireNonNull(toPinId);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Graph state
    // ═══════════════════════════════════════════════════════════════

    public final Map<Long, CanvasNode> nodes;
    public final List<ExecEdge> execEdges;
    public final List<DataEdge> dataEdges;
    public String blueprintId;
    public String displayName;
    public String description;
    /** Blueprint entry parameters (rendered as Input nodes). */
    public final Map<String, ParamType> params;

    public BlueprintEditorCanvas() {
        this.nodes = new LinkedHashMap<>();
        this.execEdges = new ArrayList<>();
        this.dataEdges = new ArrayList<>();
        this.params = new LinkedHashMap<>();
        this.blueprintId = "";
        this.displayName = "";
        this.description = "";
        // Every canvas starts with a Begin entry node
        createNode("begin", -150, 0);
    }

    // ═══════════════════════════════════════════════════════════════
    // Node CRUD
    // ═══════════════════════════════════════════════════════════════

    /** Create a new node of the given type. */
    public CanvasNode createNode(String typeId, float posX, float posY) {
        long id = NEXT_ID.getAndIncrement();
        var node = new CanvasNode(id, typeId, posX, posY);
        nodes.put(id, node);
        return node;
    }

    /** Remove a node and all edges connected to it. */
    public void removeNode(long nodeId) {
        nodes.remove(nodeId);
        execEdges.removeIf(e -> e.fromNodeId == nodeId || e.toNodeId == nodeId);
        dataEdges.removeIf(e -> e.fromNodeId == nodeId || e.toNodeId == nodeId);
    }

    // ═══════════════════════════════════════════════════════════════
    // Edge CRUD
    // ═══════════════════════════════════════════════════════════════

    /** Add an exec edge. Removes any existing exec edge to the same target pin. */
    public void addExecEdge(long fromNodeId, String fromPinId, long toNodeId, String toPinId) {
        execEdges.removeIf(e -> e.toNodeId == toNodeId && e.toPinId.equals(toPinId));
        execEdges.add(new ExecEdge(fromNodeId, fromPinId, toNodeId, toPinId));
    }

    /** Add a data edge. Removes any existing data edge to the same target pin. */
    public void addDataEdge(long fromNodeId, String fromPinId, long toNodeId, String toPinId) {
        dataEdges.removeIf(e -> e.toNodeId == toNodeId && e.toPinId.equals(toPinId));
        dataEdges.add(new DataEdge(fromNodeId, fromPinId, toNodeId, toPinId));
    }

    /** Remove the data edge targeting a specific pin. */
    public void removeDataEdgeTo(long toNodeId, String toPinId) {
        dataEdges.removeIf(e -> e.toNodeId == toNodeId && e.toPinId.equals(toPinId));
    }

    /** Remove the exec edge targeting a specific pin. */
    public void removeExecEdgeTo(long toNodeId, String toPinId) {
        execEdges.removeIf(e -> e.toNodeId == toNodeId && e.toPinId.equals(toPinId));
    }

    // ═══════════════════════════════════════════════════════════════
    // Queries
    // ═══════════════════════════════════════════════════════════════

    /** Find the node connected to (fromNodeId, fromPinId) via an exec edge. */
    public CanvasNode findExecTarget(long fromNodeId, String fromPinId) {
        for (ExecEdge e : execEdges) {
            if (e.fromNodeId == fromNodeId && e.fromPinId.equals(fromPinId)) {
                return nodes.get(e.toNodeId);
            }
        }
        return null;
    }

    /** Find the source node of a data edge connected to (toNodeId, toPinId). */
    public CanvasNode findDataSource(long toNodeId, String toPinId) {
        for (DataEdge e : dataEdges) {
            if (e.toNodeId == toNodeId && e.toPinId.equals(toPinId)) {
                return nodes.get(e.fromNodeId);
            }
        }
        return null;
    }

    /** Get the DataEdge connected to the given pin. */
    public DataEdge findDataEdgeTo(long toNodeId, String toPinId) {
        for (DataEdge e : dataEdges) {
            if (e.toNodeId == toNodeId && e.toPinId.equals(toPinId)) {
                return e;
            }
        }
        return null;
    }

    /** Check if a node has any incoming exec edge on its exec_in pin. */
    public boolean hasExecIncoming(long nodeId) {
        for (ExecEdge e : execEdges) {
            if (e.toNodeId == nodeId && "exec_in".equals(e.toPinId)) {
                return true;
            }
        }
        return false;
    }

    /** Find the Begin entry node (the single execution start point). */
    public CanvasNode findBeginNode() {
        for (CanvasNode node : nodes.values()) {
            if ("begin".equals(node.typeId)) return node;
        }
        return null;
    }

    /** Find the root step node: the node connected to Begin's exec_out, or null. */
    public CanvasNode findExecRoot() {
        CanvasNode begin = findBeginNode();
        if (begin != null) {
            return findExecTarget(begin.nodeId, "exec_out");
        }
        // Fallback: find any step with no incoming exec edge
        for (CanvasNode node : nodes.values()) {
            String cat = BlueprintNodeDefinition.get(node.typeId).category();
            if (BlueprintNodeDefinition.CATEGORY_STEP.equals(cat)
                    && !hasExecIncoming(node.nodeId)) {
                return node;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // Serialization: CanvasGraph → BlueprintDefinition
    // ═══════════════════════════════════════════════════════════════

    /**
     * Convert the canvas graph to a {@link BlueprintDefinition} AST.
     * Uses DFS along exec edges to reconstruct step lists,
     * and walks data edges backwards to reconstruct expression trees.
     */
    public BlueprintDefinition toDefinition() {
        CanvasNode root = findExecRoot();
        List<StepNode> steps;
        if (root != null) {
            Set<Long> visited = new HashSet<>();
            steps = collectSteps(root, visited);
        } else {
            steps = List.of();
        }

        Map<String, ParamType> defParams = new LinkedHashMap<>(params);
        if (defParams.isEmpty()) {
            // Auto-detect params from Var expression nodes
            for (CanvasNode node : nodes.values()) {
                if ("var".equals(node.typeId)) {
                    String varName = node.inlineValues.get("name");
                    if (varName != null && !varName.isEmpty() && !varName.startsWith("$")) {
                        defParams.putIfAbsent(varName, ParamType.STRING);
                    }
                }
            }
        }

        return new BlueprintDefinition(
                blueprintId.isEmpty() ? "custom:untitled" : blueprintId,
                defParams,
                steps,
                displayName,
                description
        );
    }

    /**
     * DFS along exec edges to build the step list.
     */
    private List<StepNode> collectSteps(CanvasNode startNode, Set<Long> visited) {
        List<StepNode> steps = new ArrayList<>();
        CanvasNode current = startNode;

        while (current != null && visited.add(current.nodeId)) {
            StepNode baseStep = createStepNode(current);
            if (baseStep == null) break;

            String typeId = current.typeId;

            if ("for_each".equals(typeId)) {
                CanvasNode loopStart = findExecTarget(current.nodeId, "loop_body");
                List<StepNode> loopBody = List.of();
                if (loopStart != null) {
                    Set<Long> innerVisited = new HashSet<>();
                    loopBody = collectSteps(loopStart, innerVisited);
                }
                // Rebuild ForEachStep with loop body
                var forEach = (StepNode.ForEachStep) baseStep;
                steps.add(new StepNode.ForEachStep(forEach.list(), forEach.var(), loopBody));
                current = findExecTarget(current.nodeId, "completed");

            } else if ("if".equals(typeId)) {
                CanvasNode thenStart = findExecTarget(current.nodeId, "then");
                CanvasNode elseStart = findExecTarget(current.nodeId, "else");
                List<StepNode> thenSteps = List.of();
                List<StepNode> elseSteps = List.of();
                if (thenStart != null) {
                    Set<Long> innerVisited = new HashSet<>();
                    thenSteps = collectSteps(thenStart, innerVisited);
                }
                if (elseStart != null) {
                    Set<Long> innerVisited = new HashSet<>();
                    elseSteps = collectSteps(elseStart, innerVisited);
                }
                var ifStep = (StepNode.IfStep) baseStep;
                steps.add(new StepNode.IfStep(ifStep.condition(), ifStep.params(),
                        ifStep.elseInvert(), thenSteps, elseSteps));
                current = findExecTarget(current.nodeId, "completed");

            } else if ("parallel".equals(typeId)) {
                // Collect all branch exec chains
                List<StepNode> allBranches = new ArrayList<>();
                BlueprintNodeDefinition.NodeDef def = BlueprintNodeDefinition.get("parallel");
                if (def != null) {
                    for (var pin : def.execPins()) {
                        if (pin.dir() == BlueprintNodeDefinition.PinDir.OUTPUT
                                && !"completed".equals(pin.id()) && !"exec_in".equals(pin.id())) {
                            CanvasNode branchStart = findExecTarget(current.nodeId, pin.id());
                            if (branchStart != null) {
                                Set<Long> innerVisited = new HashSet<>();
                                allBranches.addAll(collectSteps(branchStart, innerVisited));
                            }
                        }
                    }
                }
                steps.add(new StepNode.ParallelStep(allBranches));
                current = findExecTarget(current.nodeId, "completed");

            } else {
                // Linear step: follow exec_out → next
                steps.add(baseStep);
                current = findExecTarget(current.nodeId, "exec_out");
            }
        }
        return steps;
    }

    /**
     * Create a StepNode from a step CanvasNode by resolving all data inputs.
     */
    private StepNode createStepNode(CanvasNode node) {
        return switch (node.typeId) {
            case "place" -> {
                ExprNode at = resolveDataInput(node.nodeId, "at");
                ExprNode block = resolveDataInput(node.nodeId, "block");
                ExprNode consumable = resolveDataInputOpt(node.nodeId, "consumable");
                yield consumable != null
                        ? new StepNode.PlaceStep(at, block, consumable)
                        : new StepNode.PlaceStep(at, block);
            }
            case "remove" -> {
                ExprNode at = resolveDataInput(node.nodeId, "at");
                ExprNode from = resolveDataInput(node.nodeId, "from");
                yield new StepNode.RemoveStep(at, from);
            }
            case "convert" -> {
                ExprNode at = resolveDataInput(node.nodeId, "at");
                ExprNode from = resolveDataInput(node.nodeId, "from");
                ExprNode to = resolveDataInput(node.nodeId, "to");
                yield new StepNode.ConvertStep(at, from, to);
            }
            case "block_interact" -> {
                ExprNode at = resolveDataInput(node.nodeId, "at");
                String action = node.inlineValues.getOrDefault("action", "toggle");
                Map<String, ExprNode> params = resolveMapParams(node, "block_interact_params");
                ExprNode channelTicks = resolveDataInputOpt(node.nodeId, "channel_ticks");
                ExprNode manaCost = resolveDataInputOpt(node.nodeId, "mana_cost");
                if (channelTicks == null) channelTicks = new ExprNode.LiteralInt(0);
                if (manaCost == null) manaCost = new ExprNode.LiteralInt(1);
                yield new StepNode.BlockInteractStep(at, action, params, channelTicks, manaCost);
            }
            case "entity_interact" -> {
                ExprNode target = resolveDataInput(node.nodeId, "target");
                ExprNode effect = resolveDataInput(node.nodeId, "effect");
                ExprNode strength = resolveDataInput(node.nodeId, "strength");
                ExprNode duration = resolveDataInput(node.nodeId, "duration");
                yield new StepNode.EntityInteractStep(target, effect, strength, duration);
            }
            case "ritual" -> {
                ExprNode ritual = resolveDataInput(node.nodeId, "ritual");
                ExprNode at = resolveDataInput(node.nodeId, "at");
                Map<String, ExprNode> params = resolveMapParams(node, "ritual_params");
                yield new StepNode.RitualStep(ritual, at, params);
            }
            case "request_resource" -> {
                ExprNode dynamicItems = resolveDataInputOpt(node.nodeId, "dynamic_items");
                if (dynamicItems != null) {
                    yield new StepNode.RequestResourceStep(List.of(), dynamicItems);
                }
                // Static items from inline values
                yield new StepNode.RequestResourceStep(List.of(),
                        new ExprNode.LiteralListString(List.of()));
            }
            case "emit_event" -> {
                ExprNode event = resolveDataInput(node.nodeId, "event");
                Map<String, ExprNode> data = resolveMapParams(node, "emit_event_data");
                yield new StepNode.EmitEventStep(event, data);
            }
            case "for_each" -> {
                ExprNode list = resolveDataInput(node.nodeId, "list");
                String varName = node.inlineValues.getOrDefault("var_name", "it");
                yield new StepNode.ForEachStep(list, varName, List.of());
            }
            case "if" -> {
                ExprNode condition = resolveDataInput(node.nodeId, "condition");
                Map<String, ExprNode> condParams = resolveMapParams(node, "if_params");
                boolean elseInvert = "true".equals(node.inlineValues.get("else_invert"));
                yield new StepNode.IfStep(condition, condParams, elseInvert, List.of(), List.of());
            }
            case "call" -> {
                ExprNode blueprintId = resolveDataInput(node.nodeId, "blueprint_id");
                Map<String, ExprNode> with = resolveMapParams(node, "call_with");
                yield new StepNode.CallStep(blueprintId, with);
            }
            case "parallel" -> new StepNode.ParallelStep(List.of());
            case "log" -> {
                String level = node.inlineValues.getOrDefault("level", "info");
                ExprNode text = resolveDataInput(node.nodeId, "text");
                yield new StepNode.LogStep(level, text);
            }
            default -> {
                Log.warn(TAG, "Unknown step type: {}", node.typeId);
                yield null;
            }
        };
    }

    /**
     * Resolve the expression feeding into a data input pin.
     * Walks data edges backwards to the expression node, then recursively builds ExprNode.
     */
    private ExprNode resolveDataInput(long nodeId, String pinId) {
        ExprNode result = resolveDataInputOpt(nodeId, pinId);
        if (result == null) {
            Log.warn(TAG, "Unconnected required data pin: {}/{}", nodeId, pinId);
            return new ExprNode.LiteralString("");
        }
        return result;
    }

    /** Optional variant: returns null if unconnected. */
    private ExprNode resolveDataInputOpt(long nodeId, String pinId) {
        DataEdge edge = findDataEdgeTo(nodeId, pinId);
        if (edge == null) return null;
        CanvasNode source = nodes.get(edge.fromNodeId);
        if (source == null) return null;
        return resolveExpression(source);
    }

    /**
     * Recursively build an ExprNode tree from an expression canvas node.
     */
    private ExprNode resolveExpression(CanvasNode node) {
        return switch (node.typeId) {
            case "literal_string" -> new ExprNode.LiteralString(
                    node.inlineValues.getOrDefault("value", ""));
            case "literal_int" -> {
                try {
                    yield new ExprNode.LiteralInt(
                            Integer.parseInt(node.inlineValues.getOrDefault("value", "0")));
                } catch (NumberFormatException e) {
                    yield new ExprNode.LiteralInt(0);
                }
            }
            case "literal_pos" -> new ExprNode.LiteralPos(parsePos(
                    node.inlineValues.getOrDefault("value", "0,0,0")));
            case "literal_list_pos" -> new ExprNode.LiteralListPos(
                    parsePosList(node.inlineValues.getOrDefault("value", "")));
            case "literal_list_string" -> new ExprNode.LiteralListString(
                    parseStringList(node.inlineValues.getOrDefault("value", "")));
            case "literal_map" -> new ExprNode.LiteralMap(
                    parseStringMap(node.inlineValues.getOrDefault("value", "")));
            case "var" -> new ExprNode.Var(
                    node.inlineValues.getOrDefault("name", "unknown"));
            case "field_access" -> {
                ExprNode target = resolveDataInput(node.nodeId, "target");
                String field = node.inlineValues.getOrDefault("field", "x");
                yield new ExprNode.FieldAccess(target, field);
            }
            case "add" -> new ExprNode.Add(
                    resolveDataInput(node.nodeId, "left"),
                    resolveDataInput(node.nodeId, "right"));
            case "sub" -> new ExprNode.Sub(
                    resolveDataInput(node.nodeId, "left"),
                    resolveDataInput(node.nodeId, "right"));
            case "mul" -> new ExprNode.Mul(
                    resolveDataInput(node.nodeId, "left"),
                    resolveDataInput(node.nodeId, "right"));
            case "eq" -> new ExprNode.Eq(
                    resolveDataInputOpt(node.nodeId, "left"),
                    resolveDataInputOpt(node.nodeId, "right"));
            case "neq" -> new ExprNode.Neq(
                    resolveDataInputOpt(node.nodeId, "left"),
                    resolveDataInputOpt(node.nodeId, "right"));
            case "gt" -> new ExprNode.Gt(
                    resolveDataInputOpt(node.nodeId, "left"),
                    resolveDataInputOpt(node.nodeId, "right"));
            case "lt" -> new ExprNode.Lt(
                    resolveDataInputOpt(node.nodeId, "left"),
                    resolveDataInputOpt(node.nodeId, "right"));
            case "gte" -> new ExprNode.Gte(
                    resolveDataInputOpt(node.nodeId, "left"),
                    resolveDataInputOpt(node.nodeId, "right"));
            case "lte" -> new ExprNode.Lte(
                    resolveDataInputOpt(node.nodeId, "left"),
                    resolveDataInputOpt(node.nodeId, "right"));
            case "map_get" -> new ExprNode.MapGet(
                    resolveDataInput(node.nodeId, "map"),
                    resolveDataInput(node.nodeId, "key"));
            case "size" -> new ExprNode.Size(
                    resolveDataInput(node.nodeId, "target"));
            case "format" -> {
                ExprNode template = resolveDataInput(node.nodeId, "template");
                // Collect dynamic args
                List<ExprNode> args = new ArrayList<>();
                int extraArgs = node.dynamicPinCounts.getOrDefault("arg", 0);
                for (int i = 0; i <= extraArgs; i++) {
                    String pinId = i == 0 ? "arg" : "arg_" + i;
                    ExprNode arg = resolveDataInputOpt(node.nodeId, pinId);
                    if (arg != null) args.add(arg);
                }
                yield new ExprNode.Format(template, args);
            }
            case "key_of" -> new ExprNode.KeyOf(
                    resolveDataInput(node.nodeId, "target"));
            case "map_items" -> new ExprNode.MapItems(
                    resolveDataInput(node.nodeId, "list"),
                    node.inlineValues.getOrDefault("loop_var", "it"),
                    resolveDataInput(node.nodeId, "resource"),
                    resolveDataInput(node.nodeId, "amount"));
            default -> {
                Log.warn(TAG, "Unknown expression type: {}", node.typeId);
                yield new ExprNode.LiteralString("");
            }
        };
    }

    /** Parse a "x,y,z" string to a GridPos. */
    private static GridPos parsePos(String s) {
        if (s == null || s.isEmpty()) return new GridPos(0, 0, 0);
        String[] parts = s.split(",");
        try {
            return new GridPos(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (Exception e) {
            return new GridPos(0, 0, 0);
        }
    }

    private static List<GridPos> parsePosList(String s) {
        if (s == null || s.isEmpty()) return List.of();
        List<GridPos> result = new ArrayList<>();
        for (String part : s.split(";")) {
            if (!part.isBlank()) result.add(parsePos(part.trim()));
        }
        return result;
    }

    private static List<String> parseStringList(String s) {
        if (s == null || s.isEmpty()) return List.of();
        return List.of(s.split(","));
    }

    private static Map<String, String> parseStringMap(String s) {
        if (s == null || s.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : s.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) result.put(kv[0].trim(), kv[1].trim());
        }
        return result;
    }

    /** Resolve map-style params stored in inline values (key1=exprStr,key2=exprStr,...). */
    private Map<String, ExprNode> resolveMapParams(CanvasNode node, String inlineKey) {
        Map<String, ExprNode> result = new LinkedHashMap<>();
        String raw = node.inlineValues.get(inlineKey);
        if (raw == null || raw.isEmpty()) return result;
        for (String pair : raw.split(";;")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                result.put(kv[0].trim(), new ExprNode.LiteralString(kv[1].trim()));
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    // Deserialization: BlueprintDefinition → CanvasGraph
    // ═══════════════════════════════════════════════════════════════

    /** Create a populated CanvasGraph from a BlueprintDefinition. */
    public static BlueprintEditorCanvas fromDefinition(BlueprintDefinition def) {
        BlueprintEditorCanvas graph = new BlueprintEditorCanvas();
        graph.blueprintId = def.id();
        graph.displayName = def.displayName();
        graph.description = def.description();
        graph.params.putAll(def.params());

        // Begin node is already created by constructor — use it
        CanvasNode beginNode = graph.findBeginNode();
        if (beginNode == null) {
            beginNode = graph.createNode("begin", -150, 0);
        }

        // Create Input nodes for params
        float inputX = -350;
        float inputY = 100;
        for (var entry : def.params().entrySet()) {
            CanvasNode inputNode = graph.createNode("input", inputX, inputY);
            inputNode.inlineValues.put("name", entry.getKey());
            inputNode.inlineValues.put("type", paramTypeToString(entry.getValue()));
            inputY += 80;
        }

        // Create step + expression nodes from steps
        if (!def.steps().isEmpty()) {
            float stepX = 50;
            float stepY = 0;
            List<Long> chain = new ArrayList<>();
            createStepChain(def.steps(), graph, stepX, stepY, chain);

            // Wire BEGIN → first step
            if (!chain.isEmpty()) {
                graph.addExecEdge(beginNode.nodeId, "exec_out", chain.get(0), "exec_in");
            }

            // Wire exec chain sequentially — use correct exit pin per node type
            for (int i = 0; i < chain.size() - 1; i++) {
                String exitPin = execExitPin(chain.get(i), graph);
                graph.addExecEdge(chain.get(i), exitPin, chain.get(i + 1), "exec_in");
            }
        }

        return graph;
    }

    /**
     * Recursively create step + expression nodes from a StepNode list.
     * Appends created step node IDs to the chain list.
     */
    private static float createStepChain(List<StepNode> steps, BlueprintEditorCanvas graph,
                                          float startX, float startY, List<Long> chain) {
        float x = startX;
        float y = startY;
        for (StepNode step : steps) {
            long nodeId = createStepWithExpressions(step, graph, x, y);
            if (nodeId >= 0) {
                chain.add(nodeId);
                x += 220; // horizontal spacing
            }
        }
        return y + 140; // return next row Y
    }

    /**
     * Create a step node on the canvas plus all expression sub-nodes for its fields.
     * Returns the step node ID, or -1 on failure.
     */
    private static long createStepWithExpressions(StepNode step, BlueprintEditorCanvas graph,
                                                   float posX, float posY) {
        String typeId = stepTypeId(step);
        if (typeId == null) return -1;

        CanvasNode stepNode = graph.createNode(typeId, posX, posY);

        float exprX = posX - 200;
        float exprY = posY;

        return switch (step) {
            case StepNode.PlaceStep s -> {
                long atId = createExprNodes(s.at(), graph, exprX, exprY);
                if (atId >= 0) graph.addDataEdge(atId, "value", stepNode.nodeId, "at");
                exprY += 60;
                long blockId = createExprNodes(s.block(), graph, exprX, exprY);
                if (blockId >= 0) graph.addDataEdge(blockId, "value", stepNode.nodeId, "block");
                if (s.consumable() != null) {
                    exprY += 60;
                    long consId = createExprNodes(s.consumable(), graph, exprX, exprY);
                    if (consId >= 0) graph.addDataEdge(consId, "value", stepNode.nodeId, "consumable");
                }
                yield stepNode.nodeId;
            }
            case StepNode.RemoveStep s -> {
                long atId = createExprNodes(s.at(), graph, exprX, exprY);
                if (atId >= 0) graph.addDataEdge(atId, "value", stepNode.nodeId, "at");
                exprY += 60;
                long fromId = createExprNodes(s.from(), graph, exprX, exprY);
                if (fromId >= 0) graph.addDataEdge(fromId, "value", stepNode.nodeId, "from");
                yield stepNode.nodeId;
            }
            case StepNode.ConvertStep s -> {
                long atId = createExprNodes(s.at(), graph, exprX, exprY);
                if (atId >= 0) graph.addDataEdge(atId, "value", stepNode.nodeId, "at");
                exprY += 60;
                long fromId = createExprNodes(s.from(), graph, exprX, exprY);
                if (fromId >= 0) graph.addDataEdge(fromId, "value", stepNode.nodeId, "from");
                exprY += 60;
                long toId = createExprNodes(s.to(), graph, exprX, exprY);
                if (toId >= 0) graph.addDataEdge(toId, "value", stepNode.nodeId, "to");
                yield stepNode.nodeId;
            }
            case StepNode.BlockInteractStep s -> {
                stepNode.inlineValues.put("action", s.action());
                long atId = createExprNodes(s.at(), graph, exprX, exprY);
                if (atId >= 0) graph.addDataEdge(atId, "value", stepNode.nodeId, "at");
                exprY += 60;
                long chId = createExprNodes(s.channelTicks(), graph, exprX, exprY);
                if (chId >= 0) graph.addDataEdge(chId, "value", stepNode.nodeId, "channel_ticks");
                exprY += 60;
                long mcId = createExprNodes(s.manaCost(), graph, exprX, exprY);
                if (mcId >= 0) graph.addDataEdge(mcId, "value", stepNode.nodeId, "mana_cost");
                yield stepNode.nodeId;
            }
            case StepNode.EntityInteractStep s -> {
                long tId = createExprNodes(s.target(), graph, exprX, exprY);
                if (tId >= 0) graph.addDataEdge(tId, "value", stepNode.nodeId, "target");
                exprY += 60;
                long eId = createExprNodes(s.effect(), graph, exprX, exprY);
                if (eId >= 0) graph.addDataEdge(eId, "value", stepNode.nodeId, "effect");
                exprY += 60;
                long sId = createExprNodes(s.strength(), graph, exprX, exprY);
                if (sId >= 0) graph.addDataEdge(sId, "value", stepNode.nodeId, "strength");
                exprY += 60;
                long dId = createExprNodes(s.duration(), graph, exprX, exprY);
                if (dId >= 0) graph.addDataEdge(dId, "value", stepNode.nodeId, "duration");
                yield stepNode.nodeId;
            }
            case StepNode.RitualStep s -> {
                long rId = createExprNodes(s.ritual(), graph, exprX, exprY);
                if (rId >= 0) graph.addDataEdge(rId, "value", stepNode.nodeId, "ritual");
                exprY += 60;
                long atId = createExprNodes(s.at(), graph, exprX, exprY);
                if (atId >= 0) graph.addDataEdge(atId, "value", stepNode.nodeId, "at");
                yield stepNode.nodeId;
            }
            case StepNode.RequestResourceStep s -> {
                if (s.dynamicItems() != null) {
                    long diId = createExprNodes(s.dynamicItems(), graph, exprX, exprY);
                    if (diId >= 0) graph.addDataEdge(diId, "value", stepNode.nodeId, "dynamic_items");
                }
                yield stepNode.nodeId;
            }
            case StepNode.EmitEventStep s -> {
                long evId = createExprNodes(s.event(), graph, exprX, exprY);
                if (evId >= 0) graph.addDataEdge(evId, "value", stepNode.nodeId, "event");
                yield stepNode.nodeId;
            }
            case StepNode.ForEachStep s -> {
                stepNode.inlineValues.put("var_name", s.var());
                long listId = createExprNodes(s.list(), graph, exprX, exprY);
                if (listId >= 0) graph.addDataEdge(listId, "value", stepNode.nodeId, "list");
                if (!s.steps().isEmpty()) {
                    float bodyX = posX + 50;
                    float bodyY = posY + 150;
                    List<Long> bodyChain = new ArrayList<>();
                    createStepChain(s.steps(), graph, bodyX, bodyY, bodyChain);
                    if (!bodyChain.isEmpty()) {
                        graph.addExecEdge(stepNode.nodeId, "loop_body", bodyChain.get(0), "exec_in");
                        for (int i = 0; i < bodyChain.size() - 1; i++) {
                            graph.addExecEdge(bodyChain.get(i), "exec_out", bodyChain.get(i + 1), "exec_in");
                        }
                    }
                }
                yield stepNode.nodeId;
            }
            case StepNode.IfStep s -> {
                stepNode.inlineValues.put("else_invert", String.valueOf(s.elseInvert()));
                long condId = createExprNodes(s.condition(), graph, exprX, exprY);
                if (condId >= 0) graph.addDataEdge(condId, "value", stepNode.nodeId, "condition");
                float thenY = posY + 150;
                if (!s.thenSteps().isEmpty()) {
                    float thenX = posX - 100;
                    List<Long> thenChain = new ArrayList<>();
                    createStepChain(s.thenSteps(), graph, thenX, thenY, thenChain);
                    if (!thenChain.isEmpty()) {
                        graph.addExecEdge(stepNode.nodeId, "then", thenChain.get(0), "exec_in");
                        for (int i = 0; i < thenChain.size() - 1; i++) {
                            graph.addExecEdge(thenChain.get(i), "exec_out", thenChain.get(i + 1), "exec_in");
                        }
                    }
                }
                if (!s.elseSteps().isEmpty()) {
                    float elseX = posX + 200;
                    List<Long> elseChain = new ArrayList<>();
                    createStepChain(s.elseSteps(), graph, elseX, thenY, elseChain);
                    if (!elseChain.isEmpty()) {
                        graph.addExecEdge(stepNode.nodeId, "else", elseChain.get(0), "exec_in");
                        for (int i = 0; i < elseChain.size() - 1; i++) {
                            graph.addExecEdge(elseChain.get(i), "exec_out", elseChain.get(i + 1), "exec_in");
                        }
                    }
                }
                yield stepNode.nodeId;
            }
            case StepNode.CallStep s -> {
                long bpId = createExprNodes(s.blueprintId(), graph, exprX, exprY);
                if (bpId >= 0) graph.addDataEdge(bpId, "value", stepNode.nodeId, "blueprint_id");
                yield stepNode.nodeId;
            }
            case StepNode.ParallelStep s -> {
                float branchY = posY + 150;
                float branchX = posX - 100;
                for (int i = 0; i < s.steps().size(); i++) {
                    String branchPinId = i == 0 ? "branch_0" : "branch_" + i;
                    List<Long> branchChain = new ArrayList<>();
                    createStepChain(List.of(s.steps().get(i)), graph, branchX, branchY, branchChain);
                    if (!branchChain.isEmpty()) {
                        graph.addExecEdge(stepNode.nodeId, branchPinId, branchChain.get(0), "exec_in");
                    }
                    branchX += 250;
                }
                yield stepNode.nodeId;
            }
            case StepNode.LogStep s -> {
                stepNode.inlineValues.put("level", s.level());
                long textId = createExprNodes(s.text(), graph, exprX, exprY);
                if (textId >= 0) graph.addDataEdge(textId, "value", stepNode.nodeId, "text");
                yield stepNode.nodeId;
            }
        };
    }

    /**
     * Recursively create expression nodes for an ExprNode tree.
     * Returns the CanvasNode ID of the root expression node.
     */
    private static long createExprNodes(ExprNode expr, BlueprintEditorCanvas graph,
                                         float posX, float posY) {
        if (expr == null) return -1;
        return switch (expr) {
            case ExprNode.LiteralString s -> {
                var n = graph.createNode("literal_string", posX, posY);
                n.inlineValues.put("value", s.value());
                yield n.nodeId;
            }
            case ExprNode.LiteralInt i -> {
                var n = graph.createNode("literal_int", posX, posY);
                n.inlineValues.put("value", String.valueOf(i.value()));
                yield n.nodeId;
            }
            case ExprNode.LiteralPos p -> {
                var n = graph.createNode("literal_pos", posX, posY);
                n.inlineValues.put("value", p.value().x() + "," + p.value().y() + "," + p.value().z());
                yield n.nodeId;
            }
            case ExprNode.LiteralListPos l -> {
                var n = graph.createNode("literal_list_pos", posX, posY);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < l.value().size(); i++) {
                    if (i > 0) sb.append(";");
                    var p = l.value().get(i);
                    sb.append(p.x()).append(",").append(p.y()).append(",").append(p.z());
                }
                n.inlineValues.put("value", sb.toString());
                yield n.nodeId;
            }
            case ExprNode.LiteralListString l -> {
                var n = graph.createNode("literal_list_string", posX, posY);
                n.inlineValues.put("value", String.join(",", l.value()));
                yield n.nodeId;
            }
            case ExprNode.LiteralMap m -> {
                var n = graph.createNode("literal_map", posX, posY);
                StringBuilder sb = new StringBuilder();
                for (var entry : m.value().entrySet()) {
                    if (!sb.isEmpty()) sb.append(",");
                    sb.append(entry.getKey()).append("=").append(entry.getValue());
                }
                n.inlineValues.put("value", sb.toString());
                yield n.nodeId;
            }
            case ExprNode.Var v -> {
                var n = graph.createNode("var", posX, posY);
                n.inlineValues.put("name", v.name());
                yield n.nodeId;
            }
            case ExprNode.FieldAccess f -> {
                var n = graph.createNode("field_access", posX, posY);
                n.inlineValues.put("field", f.field());
                long targetId = createExprNodes(f.target(), graph, posX - 180, posY);
                if (targetId >= 0) {
                    graph.addDataEdge(targetId, "value", n.nodeId, "target");
                }
                yield n.nodeId;
            }
            case ExprNode.Add a -> {
                var n = graph.createNode("add", posX, posY);
                long leftId = createExprNodes(a.left(), graph, posX - 180, posY - 30);
                long rightId = createExprNodes(a.right(), graph, posX - 180, posY + 30);
                if (leftId >= 0) graph.addDataEdge(leftId, "value", n.nodeId, "left");
                if (rightId >= 0) graph.addDataEdge(rightId, "value", n.nodeId, "right");
                yield n.nodeId;
            }
            case ExprNode.Sub s -> {
                var n = graph.createNode("sub", posX, posY);
                long leftId = createExprNodes(s.left(), graph, posX - 180, posY - 30);
                long rightId = createExprNodes(s.right(), graph, posX - 180, posY + 30);
                if (leftId >= 0) graph.addDataEdge(leftId, "value", n.nodeId, "left");
                if (rightId >= 0) graph.addDataEdge(rightId, "value", n.nodeId, "right");
                yield n.nodeId;
            }
            case ExprNode.Mul m -> {
                var n = graph.createNode("mul", posX, posY);
                long leftId = createExprNodes(m.left(), graph, posX - 180, posY - 30);
                long rightId = createExprNodes(m.right(), graph, posX - 180, posY + 30);
                if (leftId >= 0) graph.addDataEdge(leftId, "value", n.nodeId, "left");
                if (rightId >= 0) graph.addDataEdge(rightId, "value", n.nodeId, "right");
                yield n.nodeId;
            }
            // Comparison nodes — all have left/right
            case ExprNode.Eq e -> createBinaryExpr("eq", e.left(), e.right(), graph, posX, posY);
            case ExprNode.Neq e -> createBinaryExpr("neq", e.left(), e.right(), graph, posX, posY);
            case ExprNode.Gt e -> createBinaryExpr("gt", e.left(), e.right(), graph, posX, posY);
            case ExprNode.Lt e -> createBinaryExpr("lt", e.left(), e.right(), graph, posX, posY);
            case ExprNode.Gte e -> createBinaryExpr("gte", e.left(), e.right(), graph, posX, posY);
            case ExprNode.Lte e -> createBinaryExpr("lte", e.left(), e.right(), graph, posX, posY);
            case ExprNode.MapGet mg -> {
                var n = graph.createNode("map_get", posX, posY);
                long mapId = createExprNodes(mg.map(), graph, posX - 180, posY - 30);
                long keyId = createExprNodes(mg.key(), graph, posX - 180, posY + 30);
                if (mapId >= 0) graph.addDataEdge(mapId, "value", n.nodeId, "map");
                if (keyId >= 0) graph.addDataEdge(keyId, "value", n.nodeId, "key");
                yield n.nodeId;
            }
            case ExprNode.Size sz -> {
                var n = graph.createNode("size", posX, posY);
                long targetId = createExprNodes(sz.target(), graph, posX - 180, posY);
                if (targetId >= 0) graph.addDataEdge(targetId, "value", n.nodeId, "target");
                yield n.nodeId;
            }
            case ExprNode.Format f -> {
                var n = graph.createNode("format", posX, posY);
                long templateId = createExprNodes(f.template(), graph, posX - 180, posY);
                if (templateId >= 0) graph.addDataEdge(templateId, "value", n.nodeId, "template");
                // Create arg nodes
                for (int i = 0; i < f.args().size(); i++) {
                    long argId = createExprNodes(f.args().get(i), graph, posX - 180, posY + 40 + i * 40);
                    String pinId = i == 0 ? "arg" : "arg_" + i;
                    if (argId >= 0) graph.addDataEdge(argId, "value", n.nodeId, pinId);
                }
                if (f.args().size() > 0) {
                    n.dynamicPinCounts.put("arg", f.args().size() - 1);
                }
                yield n.nodeId;
            }
            case ExprNode.KeyOf k -> {
                var n = graph.createNode("key_of", posX, posY);
                long targetId = createExprNodes(k.target(), graph, posX - 180, posY);
                if (targetId >= 0) graph.addDataEdge(targetId, "value", n.nodeId, "target");
                yield n.nodeId;
            }
            case ExprNode.MapItems mi -> {
                var n = graph.createNode("map_items", posX, posY);
                n.inlineValues.put("loop_var", mi.loopVar());
                long listId = createExprNodes(mi.list(), graph, posX - 180, posY - 60);
                long resId = createExprNodes(mi.resource(), graph, posX - 180, posY);
                long amtId = createExprNodes(mi.amount(), graph, posX - 180, posY + 60);
                if (listId >= 0) graph.addDataEdge(listId, "value", n.nodeId, "list");
                if (resId >= 0) graph.addDataEdge(resId, "value", n.nodeId, "resource");
                if (amtId >= 0) graph.addDataEdge(amtId, "value", n.nodeId, "amount");
                yield n.nodeId;
            }
        };
    }

    /** Helper: create a binary expression node (comparison operators). */
    private static long createBinaryExpr(String typeId, ExprNode left, ExprNode right,
                                          BlueprintEditorCanvas graph, float posX, float posY) {
        var n = graph.createNode(typeId, posX, posY);
        long leftId = createExprNodes(left, graph, posX - 180, posY - 30);
        long rightId = createExprNodes(right, graph, posX - 180, posY + 30);
        if (leftId >= 0) graph.addDataEdge(leftId, "value", n.nodeId, "left");
        if (rightId >= 0) graph.addDataEdge(rightId, "value", n.nodeId, "right");
        return n.nodeId;
    }

    /** Map StepNode type to its typeId. */
    private static String stepTypeId(StepNode step) {
        return switch (step) {
            case StepNode.PlaceStep s -> "place";
            case StepNode.RemoveStep s -> "remove";
            case StepNode.ConvertStep s -> "convert";
            case StepNode.BlockInteractStep s -> "block_interact";
            case StepNode.EntityInteractStep s -> "entity_interact";
            case StepNode.RitualStep s -> "ritual";
            case StepNode.RequestResourceStep s -> "request_resource";
            case StepNode.EmitEventStep s -> "emit_event";
            case StepNode.ForEachStep s -> "for_each";
            case StepNode.IfStep s -> "if";
            case StepNode.CallStep s -> "call";
            case StepNode.ParallelStep s -> "parallel";
            case StepNode.LogStep s -> "log";
        };
    }

    /** The exec output pin name used to reach the next step in sequential flow.
     *  Linear steps use "exec_out"; control-flow nodes use "completed". */
    private static String execExitPin(long nodeId, BlueprintEditorCanvas graph) {
        CanvasNode node = graph.nodes.get(nodeId);
        if (node == null) return "exec_out";
        return switch (node.typeId) {
            case "for_each", "if", "parallel" -> "completed";
            default -> "exec_out";
        };
    }

    /** Convert ParamType to a display string. */
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
