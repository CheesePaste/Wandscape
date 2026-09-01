package com.wsteam.wandscape.content.task.engine.dsl;

import com.wsteam.wandscape.core.types.GridPos;

import java.util.List;
import java.util.Map;
/**
 * Expression AST node used in blueprint DSL step fields.
 *
 * <p>Expressions are evaluated at runtime against a {@code Map<String, JsonElement>}
 * context (blueprint params + loop variables). The result is a {@link com.google.gson.JsonElement}.
 *
 * <h3>JSON Representation</h3>
 * <ul>
 *   <li>Literal string: {@code "minecraft:stone"}</li>
 *   <li>Literal int: {@code 42}</li>
 *   <li>Literal pos: {@code [0, 64, 0]}</li>
 *   <li>Variable reference (sugar): {@code "$var_name"}</li>
 *   <li>Variable reference (explicit): {@code {"$": "var_name"}}</li>
 *   <li>Field access: {@code {"$.field": ["$pos_var", "x"]}}</li>
 *   <li>Add: {@code {"+": ["$a", "$b"]}}</li>
 *   <li>Sub: {@code {"-": ["$a", "$b"]}}</li>
 *   <li>Mul: {@code {"*": ["$a", "$b"]}}</li>
 *   <li>Compare: {@code {"==": ["$a", "$b"]}}</li>
 *   <li>Size: {@code {"size": "$list"}}</li>
 *   <li>MapGet: {@code {"get": ["$map", {"keyof": "$k"}]}}</li>
 *   <li>Format: {@code {"format": ["template {}", "$arg"]}}</li>
 *   <li>KeyOf: {@code {"keyof": "$pos_var"}}</li>
 * </ul>
 */
public sealed interface ExprNode {

    // ── Literals ──

    /** A string literal. JSON: {@code "some text"}. */
    record LiteralString(String value) implements ExprNode {}

    /** An integer literal. JSON: {@code 42}. */
    record LiteralInt(int value) implements ExprNode {}

    /** A position literal. JSON: {@code [x, y, z]}. */
    record LiteralPos(GridPos value) implements ExprNode {}

    /** A list of positions literal. JSON: {@code [[x,y,z], ...]}. */
    record LiteralListPos(List<GridPos> value) implements ExprNode {}

    /** A list of strings literal. JSON: {@code ["a", "b"]}. */
    record LiteralListString(List<String> value) implements ExprNode {}

    /** A map literal. JSON: {@code {"k1":"v1", "k2":"v2"}}. */
    record LiteralMap(Map<String, String> value) implements ExprNode {}

    // ── Variable references ──

    /**
     * A variable reference. Accesses a blueprint param or loop variable.
     * JSON (explicit): {@code {"$": "param_name"}}
     * JSON (sugar): {@code "$param_name"}
     */
    record Var(String name) implements ExprNode {}

    /**
     * Access a field of a position: {@code x}, {@code y}, or {@code z}.
     * JSON: {@code {"$.field": ["$pos_var", "x"]}}
     */
    record FieldAccess(ExprNode target, String field) implements ExprNode {}

    // ── Arithmetic ──

    /** Addition: {@code pos + pos} or {@code int + int}. */
    record Add(ExprNode left, ExprNode right) implements ExprNode {}

    /** Subtraction: {@code int - int}. */
    record Sub(ExprNode left, ExprNode right) implements ExprNode {}

    /** Multiplication: {@code int * int}. */
    record Mul(ExprNode left, ExprNode right) implements ExprNode {}

    // ── Comparison ──

    /** {@code left == right}. */
    record Eq(ExprNode left, ExprNode right) implements ExprNode {}

    /** {@code left != right}. */
    record Neq(ExprNode left, ExprNode right) implements ExprNode {}

    /** {@code left > right}. */
    record Gt(ExprNode left, ExprNode right) implements ExprNode {}

    /** {@code left < right}. */
    record Lt(ExprNode left, ExprNode right) implements ExprNode {}

    /** {@code left >= right}. */
    record Gte(ExprNode left, ExprNode right) implements ExprNode {}

    /** {@code left <= right}. */
    record Lte(ExprNode left, ExprNode right) implements ExprNode {}

    // ── Collection operations ──

    /**
     * Look up a key in a map. {@code {"get": ["$map_var", "$key_expr"]}}.
     * If the key is a pos, it is implicitly converted to string via {@code toKey()}.
     */
    record MapGet(ExprNode map, ExprNode key) implements ExprNode {}

    /**
     * Get the length of a list. {@code {"size": "$list_var"}}.
     * Works on {@code list<pos>} and {@code list<string>}.
     */
    record Size(ExprNode target) implements ExprNode {}

    // ── String operations ──

    /**
     * String formatting. {@code {"format": ["template {}", "$arg1", "$arg2"]}}.
     * The first element is the template; subsequent elements are args replacing {@code {}}.
     */
    record Format(ExprNode template, List<ExprNode> args) implements ExprNode {}

    /**
     * Convert a position to its string key ({@code "x,y,z"}).
     * {@code {"keyof": "$pos_var"}}
     */
    record KeyOf(ExprNode target) implements ExprNode {}

    /**
     * Map a list to resource+amount items. JSON:
     * {@code {"map_to_items": {"list": "$mat_list", "as": "m", "resource": "$m", "amount": {"get": ["$counts", "$m"]}}}}
     * Evaluates the inner {@code resource} and {@code amount} expressions once
     * per list element, binding the loop variable ({@code as}).
     */
    record MapItems(ExprNode list, String loopVar, ExprNode resource, ExprNode amount) implements ExprNode {}
}
