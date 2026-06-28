package com.wsteam.wandscape.blueprint.editor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Descriptor registry for all node types in the blueprint editor canvas.
 *
 * <p>Each node type declares its exec pins, data pins, display name, and color.
 * The ImGui renderer walks these descriptors to draw nodes uniformly — no
 * per-type rendering code needed.
 *
 * <p>37 node types: 14 StepNode + 22 ExprNode + 1 Input parameter.
 *
 * <h3>Color convention</h3>
 * <ul>
 *   <li>Step nodes: green=transform, orange=interact, yellow=resource, red=event,
 *       blue=control-flow, purple=call, gray=log</li>
 *   <li>Expr nodes: light-blue=string-literal, light-yellow=int-literal,
 *       light-green=pos-literal, light-blue=var, dark-green=arithmetic,
 *       dark-yellow=comparison, dark-purple=collection</li>
 *   <li>Input nodes: colored by ParamType</li>
 * </ul>
 */
public final class BlueprintNodeDefinition {

    private BlueprintNodeDefinition() {}

    // ── Categories ──

    public static final String CATEGORY_ENTRY = "Entry";
    public static final String CATEGORY_STEP = "Step";
    public static final String CATEGORY_EXPR = "Expression";
    public static final String CATEGORY_INPUT = "Input";

    // ── Pin direction ──

    public enum PinDir { INPUT, OUTPUT }

    // ── Pin kind (exec vs data) ──

    public enum PinKind { EXEC, DATA }

    // ── Pin descriptor ──

    /**
     * A single pin on a node.
     *
     * @param id       unique pin id within the node (e.g. "at", "block", "left")
     * @param label    human-readable label (e.g. "at", "Loop Body")
     * @param typeKey  ParamType key for data pins ("pos"/"string"/"int"/...); "exec" for exec pins
     * @param dir      input or output
     * @param kind     exec or data
     * @param dynamic  whether this pin can be replicated (Format args, Parallel branches)
     */
    public record PinDef(String id, String label, String typeKey, PinDir dir,
                         PinKind kind, boolean dynamic) {

        public PinDef {
            Objects.requireNonNull(id);
            Objects.requireNonNull(label);
            Objects.requireNonNull(typeKey);
            Objects.requireNonNull(dir);
            Objects.requireNonNull(kind);
        }

        /** Convenience: static non-dynamic pin. */
        public static PinDef of(String id, String label, String typeKey, PinDir dir, PinKind kind) {
            return new PinDef(id, label, typeKey, dir, kind, false);
        }

        /** An exec input pin. */
        public static PinDef execIn(String id, String label) {
            return new PinDef(id, label, "exec", PinDir.INPUT, PinKind.EXEC, false);
        }

        /** An exec output pin. */
        public static PinDef execOut(String id, String label) {
            return new PinDef(id, label, "exec", PinDir.OUTPUT, PinKind.EXEC, false);
        }

        /** A data input pin. */
        public static PinDef dataIn(String id, String label, String typeKey) {
            return new PinDef(id, label, typeKey, PinDir.INPUT, PinKind.DATA, false);
        }

        /** A data output pin. */
        public static PinDef dataOut(String id, String label, String typeKey) {
            return new PinDef(id, label, typeKey, PinDir.OUTPUT, PinKind.DATA, false);
        }

        /** A dynamic data input pin (e.g. Format args). */
        public static PinDef dataInDynamic(String id, String label, String typeKey) {
            return new PinDef(id, label, typeKey, PinDir.INPUT, PinKind.DATA, true);
        }
    }

    // ── Node descriptor ──

    /**
     * Complete descriptor for one node type.
     *
     * @param typeId      unique type key ("place", "for_each", "add", "var", ...)
     * @param displayName human-readable name for search palette and Inspector
     * @param category    CATEGORY_STEP / CATEGORY_EXPR / CATEGORY_INPUT
     * @param color       ABGR packed color for the node header bar
     * @param execPins    execution-flow pins (in/out)
     * @param dataPins    data pins (in/out)
     */
    public record NodeDef(String typeId, String displayName, String category,
                          int color, List<PinDef> execPins, List<PinDef> dataPins) {

        public NodeDef {
            Objects.requireNonNull(typeId);
            Objects.requireNonNull(displayName);
            Objects.requireNonNull(category);
            Objects.requireNonNull(execPins);
            Objects.requireNonNull(dataPins);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Colors (ABGR packed ints, ImGui convention)
    // ═══════════════════════════════════════════════════════════════

    // Step node colors
    static final int C_TRANSFORM = 0xFF50AF4C;   // green: place/remove/convert
    static final int C_INTERACT  = 0xFF0098FF;   // orange: block_interact/entity_interact/ritual
    static final int C_RESOURCE  = 0xFF3BEBFF;   // yellow: request_resource
    static final int C_EVENT     = 0xFF3643F4;   // red: emit_event
    static final int C_CONTROL   = 0xFFF39621;   // blue: for_each/if/parallel
    static final int C_CALL      = 0xFFB0279C;   // purple: call
    static final int C_LOG       = 0xFF9E9E9E;   // gray: log

    // Expression node colors
    static final int C_LITERAL_STRING = 0xFFFF814B;  // light blue
    static final int C_LITERAL_INT    = 0xFF4CDFFF;  // light yellow
    static final int C_LITERAL_POS    = 0xFF55E164;  // light green
    static final int C_LITERAL_LIST   = 0xFF36A2FF;  // light orange
    static final int C_LITERAL_MAP    = 0xFFAF69EE;  // light purple
    static final int C_VAR            = 0xFFFF814B;  // light blue
    static final int C_ARITHMETIC     = 0xFF3C8E38;  // dark green
    static final int C_COMPARISON     = 0xFF2DC0FB;  // dark yellow
    static final int C_COLLECTION     = 0xFFA21F7B;  // dark purple
    static final int C_FORMAT         = 0xFFAF69EE;  // light purple

    static final int C_BEGIN = 0xFFFFFFFF;         // white for begin node
    static final int C_INPUT_NODE = 0xFF888888;    // gray for input nodes

    // ═══════════════════════════════════════════════════════════════
    // Entry node (Begin — execution starts here, like UE Event node)
    // ═══════════════════════════════════════════════════════════════

    public static final NodeDef BEGIN = new NodeDef("begin", "Begin",
            CATEGORY_ENTRY, C_BEGIN,
            List.of(PinDef.execOut("exec_out", "")),
            List.of());

    // ═══════════════════════════════════════════════════════════════
    // Step node definitions (14)
    // ═══════════════════════════════════════════════════════════════

    public static final NodeDef PLACE = new NodeDef("place", "Place",
            CATEGORY_STEP, C_TRANSFORM,
            List.of(PinDef.execIn("exec_in", ""), PinDef.execOut("exec_out", "")),
            List.of(
                    PinDef.dataIn("at", "at", "pos"),
                    PinDef.dataIn("block", "block", "string"),
                    PinDef.dataIn("consumable", "consumable", "string")
            ));

    public static final NodeDef REMOVE = new NodeDef("remove", "Remove",
            CATEGORY_STEP, C_TRANSFORM,
            List.of(PinDef.execIn("exec_in", ""), PinDef.execOut("exec_out", "")),
            List.of(
                    PinDef.dataIn("at", "at", "pos"),
                    PinDef.dataIn("from", "from", "string")
            ));

    public static final NodeDef CONVERT = new NodeDef("convert", "Convert",
            CATEGORY_STEP, C_TRANSFORM,
            List.of(PinDef.execIn("exec_in", ""), PinDef.execOut("exec_out", "")),
            List.of(
                    PinDef.dataIn("at", "at", "pos"),
                    PinDef.dataIn("from", "from", "string"),
                    PinDef.dataIn("to", "to", "string")
            ));

    public static final NodeDef BLOCK_INTERACT = new NodeDef("block_interact", "Block Interact",
            CATEGORY_STEP, C_INTERACT,
            List.of(PinDef.execIn("exec_in", ""), PinDef.execOut("exec_out", "")),
            List.of(
                    PinDef.dataIn("at", "at", "pos"),
                    PinDef.dataIn("channel_ticks", "channelTicks", "int"),
                    PinDef.dataIn("mana_cost", "manaCost", "int")
                    // action + params are inline-edited in Inspector
            ));

    public static final NodeDef ENTITY_INTERACT = new NodeDef("entity_interact", "Entity Interact",
            CATEGORY_STEP, C_INTERACT,
            List.of(PinDef.execIn("exec_in", ""), PinDef.execOut("exec_out", "")),
            List.of(
                    PinDef.dataIn("target", "target", "string"),
                    PinDef.dataIn("effect", "effect", "string"),
                    PinDef.dataIn("strength", "strength", "int"),
                    PinDef.dataIn("duration", "duration", "int")
            ));

    public static final NodeDef RITUAL = new NodeDef("ritual", "Ritual",
            CATEGORY_STEP, C_INTERACT,
            List.of(PinDef.execIn("exec_in", ""), PinDef.execOut("exec_out", "")),
            List.of(
                    PinDef.dataIn("ritual", "ritual", "string"),
                    PinDef.dataIn("at", "at", "pos")
                    // params are inline-edited in Inspector
            ));

    public static final NodeDef REQUEST_RESOURCE = new NodeDef("request_resource", "Request Resource",
            CATEGORY_STEP, C_RESOURCE,
            List.of(PinDef.execIn("exec_in", ""), PinDef.execOut("exec_out", "")),
            List.of(
                    PinDef.dataIn("dynamic_items", "items", "list<item>")
                    // static items are inline-edited in Inspector
            ));

    public static final NodeDef EMIT_EVENT = new NodeDef("emit_event", "Emit Event",
            CATEGORY_STEP, C_EVENT,
            List.of(PinDef.execIn("exec_in", ""), PinDef.execOut("exec_out", "")),
            List.of(
                    PinDef.dataIn("event", "event", "string")
                    // data map is inline-edited in Inspector
            ));

    public static final NodeDef FOR_EACH = new NodeDef("for_each", "For Each",
            CATEGORY_STEP, C_CONTROL,
            List.of(
                    PinDef.execIn("exec_in", ""),
                    PinDef.execOut("loop_body", "Loop Body"),
                    PinDef.execOut("completed", "Completed")
            ),
            List.of(
                    PinDef.dataIn("list", "list", "list<any>"),
                    PinDef.dataIn("var_name", "var", "string")
            ));

    public static final NodeDef IF = new NodeDef("if", "If",
            CATEGORY_STEP, C_CONTROL,
            List.of(
                    PinDef.execIn("exec_in", ""),
                    PinDef.execOut("then", "Then"),
                    PinDef.execOut("else", "Else"),
                    PinDef.execOut("completed", "Completed")
            ),
            List.of(
                    PinDef.dataIn("condition", "condition", "string"),
                    PinDef.dataIn("else_invert", "elseInvert", "bool")
                    // params map is inline-edited in Inspector
            ));

    public static final NodeDef CALL = new NodeDef("call", "Call",
            CATEGORY_STEP, C_CALL,
            List.of(PinDef.execIn("exec_in", ""), PinDef.execOut("exec_out", "")),
            List.of(
                    PinDef.dataIn("blueprint_id", "blueprintId", "string")
                    // with map is inline-edited in Inspector
            ));

    public static final NodeDef PARALLEL = new NodeDef("parallel", "Parallel",
            CATEGORY_STEP, C_CONTROL,
            List.of(
                    PinDef.execIn("exec_in", ""),
                    PinDef.execOut("branch_0", "Branch 1"),
                    PinDef.execOut("completed", "Completed")
            ),
            List.of());

    public static final NodeDef LOG = new NodeDef("log", "Log",
            CATEGORY_STEP, C_LOG,
            List.of(PinDef.execIn("exec_in", ""), PinDef.execOut("exec_out", "")),
            List.of(
                    PinDef.dataIn("text", "text", "string")
                    // level is inline-edited in Inspector
            ));

    // ═══════════════════════════════════════════════════════════════
    // Expression node definitions (22)
    // ═══════════════════════════════════════════════════════════════

    public static final NodeDef LITERAL_STRING = new NodeDef("literal_string", "String",
            CATEGORY_EXPR, C_LITERAL_STRING,
            List.of(),
            List.of(PinDef.dataOut("value", "", "string")));

    public static final NodeDef LITERAL_INT = new NodeDef("literal_int", "Int",
            CATEGORY_EXPR, C_LITERAL_INT,
            List.of(),
            List.of(PinDef.dataOut("value", "", "int")));

    public static final NodeDef LITERAL_POS = new NodeDef("literal_pos", "Pos",
            CATEGORY_EXPR, C_LITERAL_POS,
            List.of(),
            List.of(PinDef.dataOut("value", "", "pos")));

    public static final NodeDef LITERAL_LIST_POS = new NodeDef("literal_list_pos", "Pos List",
            CATEGORY_EXPR, C_LITERAL_LIST,
            List.of(),
            List.of(PinDef.dataOut("value", "", "list<pos>")));

    public static final NodeDef LITERAL_LIST_STRING = new NodeDef("literal_list_string", "String List",
            CATEGORY_EXPR, C_LITERAL_LIST,
            List.of(),
            List.of(PinDef.dataOut("value", "", "list<string>")));

    public static final NodeDef LITERAL_MAP = new NodeDef("literal_map", "Map",
            CATEGORY_EXPR, C_LITERAL_MAP,
            List.of(),
            List.of(PinDef.dataOut("value", "", "map<string,string>")));

    public static final NodeDef VAR = new NodeDef("var", "Var",
            CATEGORY_EXPR, C_VAR,
            List.of(),
            List.of(PinDef.dataOut("value", "", "any")));

    public static final NodeDef FIELD_ACCESS = new NodeDef("field_access", "Field Access",
            CATEGORY_EXPR, C_LITERAL_POS,
            List.of(),
            List.of(
                    PinDef.dataIn("target", "", "pos"),
                    PinDef.dataOut("value", "", "int")
            ));

    public static final NodeDef ADD = new NodeDef("add", "Add",
            CATEGORY_EXPR, C_ARITHMETIC,
            List.of(),
            List.of(
                    PinDef.dataIn("left", "", "any"),
                    PinDef.dataIn("right", "", "any"),
                    PinDef.dataOut("value", "", "any")
            ));

    public static final NodeDef SUB = new NodeDef("sub", "Subtract",
            CATEGORY_EXPR, C_ARITHMETIC,
            List.of(),
            List.of(
                    PinDef.dataIn("left", "", "int"),
                    PinDef.dataIn("right", "", "int"),
                    PinDef.dataOut("value", "", "int")
            ));

    public static final NodeDef MUL = new NodeDef("mul", "Multiply",
            CATEGORY_EXPR, C_ARITHMETIC,
            List.of(),
            List.of(
                    PinDef.dataIn("left", "", "int"),
                    PinDef.dataIn("right", "", "int"),
                    PinDef.dataOut("value", "", "int")
            ));

    public static final NodeDef EQ = new NodeDef("eq", "Equal ==",
            CATEGORY_EXPR, C_COMPARISON,
            List.of(),
            List.of(
                    PinDef.dataIn("left", "", "any"),
                    PinDef.dataIn("right", "", "any"),
                    PinDef.dataOut("value", "", "bool")
            ));

    public static final NodeDef NEQ = new NodeDef("neq", "Not Equal !=",
            CATEGORY_EXPR, C_COMPARISON,
            List.of(),
            List.of(
                    PinDef.dataIn("left", "", "any"),
                    PinDef.dataIn("right", "", "any"),
                    PinDef.dataOut("value", "", "bool")
            ));

    public static final NodeDef GT = new NodeDef("gt", "Greater >",
            CATEGORY_EXPR, C_COMPARISON,
            List.of(),
            List.of(
                    PinDef.dataIn("left", "", "any"),
                    PinDef.dataIn("right", "", "any"),
                    PinDef.dataOut("value", "", "bool")
            ));

    public static final NodeDef LT = new NodeDef("lt", "Less <",
            CATEGORY_EXPR, C_COMPARISON,
            List.of(),
            List.of(
                    PinDef.dataIn("left", "", "any"),
                    PinDef.dataIn("right", "", "any"),
                    PinDef.dataOut("value", "", "bool")
            ));

    public static final NodeDef GTE = new NodeDef("gte", "Greater >= ",
            CATEGORY_EXPR, C_COMPARISON,
            List.of(),
            List.of(
                    PinDef.dataIn("left", "", "any"),
                    PinDef.dataIn("right", "", "any"),
                    PinDef.dataOut("value", "", "bool")
            ));

    public static final NodeDef LTE = new NodeDef("lte", "Less <= ",
            CATEGORY_EXPR, C_COMPARISON,
            List.of(),
            List.of(
                    PinDef.dataIn("left", "", "any"),
                    PinDef.dataIn("right", "", "any"),
                    PinDef.dataOut("value", "", "bool")
            ));

    public static final NodeDef MAP_GET = new NodeDef("map_get", "Map Get",
            CATEGORY_EXPR, C_COLLECTION,
            List.of(),
            List.of(
                    PinDef.dataIn("map", "", "map<string,string>"),
                    PinDef.dataIn("key", "", "any"),
                    PinDef.dataOut("value", "", "string")
            ));

    public static final NodeDef SIZE = new NodeDef("size", "Size",
            CATEGORY_EXPR, C_COLLECTION,
            List.of(),
            List.of(
                    PinDef.dataIn("target", "", "list<any>"),
                    PinDef.dataOut("value", "", "int")
            ));

    public static final NodeDef FORMAT = new NodeDef("format", "Format",
            CATEGORY_EXPR, C_FORMAT,
            List.of(),
            List.of(
                    PinDef.dataIn("template", "", "string"),
                    PinDef.dataInDynamic("arg", "arg", "string"),
                    PinDef.dataOut("value", "", "string")
            ));

    public static final NodeDef KEY_OF = new NodeDef("key_of", "Key Of",
            CATEGORY_EXPR, C_LITERAL_POS,
            List.of(),
            List.of(
                    PinDef.dataIn("target", "", "pos"),
                    PinDef.dataOut("value", "", "string")
            ));

    public static final NodeDef MAP_ITEMS = new NodeDef("map_items", "Map Items",
            CATEGORY_EXPR, C_COLLECTION,
            List.of(),
            List.of(
                    PinDef.dataIn("list", "", "list<any>"),
                    PinDef.dataIn("resource", "", "string"),
                    PinDef.dataIn("amount", "", "int"),
                    PinDef.dataOut("value", "", "list<item>")
            ));

    // ═══════════════════════════════════════════════════════════════
    // Input parameter node
    // ═══════════════════════════════════════════════════════════════

    public static final NodeDef INPUT = new NodeDef("input", "Input",
            CATEGORY_INPUT, C_INPUT_NODE,
            List.of(),
            List.of(PinDef.dataOut("value", "", "any")));

    // ═══════════════════════════════════════════════════════════════
    // Registry (ordered for search palette)
    // ═══════════════════════════════════════════════════════════════

    private static final List<NodeDef> ALL_DEFS = List.of(
            // Entry
            BEGIN,
            // Steps
            PLACE, REMOVE, CONVERT, BLOCK_INTERACT, ENTITY_INTERACT, RITUAL,
            REQUEST_RESOURCE, EMIT_EVENT, FOR_EACH, IF, CALL, PARALLEL, LOG,
            // Expressions
            LITERAL_STRING, LITERAL_INT, LITERAL_POS, LITERAL_LIST_POS,
            LITERAL_LIST_STRING, LITERAL_MAP, VAR, FIELD_ACCESS,
            ADD, SUB, MUL, EQ, NEQ, GT, LT, GTE, LTE,
            MAP_GET, SIZE, FORMAT, KEY_OF, MAP_ITEMS,
            // Input
            INPUT
    );

    /** All registered node definitions (read-only, ordered). */
    public static List<NodeDef> all() {
        return ALL_DEFS;
    }

    /** Look up a node definition by typeId. Returns null if not found. */
    public static NodeDef get(String typeId) {
        for (NodeDef def : ALL_DEFS) {
            if (def.typeId.equals(typeId)) return def;
        }
        return null;
    }

    /** Filter definitions by a search query (case-insensitive substring match on typeId + displayName). */
    public static List<NodeDef> search(String query) {
        if (query == null || query.isBlank()) return ALL_DEFS;
        String lower = query.toLowerCase();
        return ALL_DEFS.stream()
                .filter(d -> d.typeId.toLowerCase().contains(lower)
                        || d.displayName.toLowerCase().contains(lower))
                .toList();
    }
}
