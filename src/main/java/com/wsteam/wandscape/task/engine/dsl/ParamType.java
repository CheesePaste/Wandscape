package com.wsteam.wandscape.task.engine.dsl;

/**
 * Strongly-typed parameter type declaration for {@link BlueprintDefinition}.
 * Six base types with implicit conversions applied at expression evaluation time.
 *
 * <p>Implicit conversions:
 * <ul>
 *   <li>{@code int → string} (42 → "42")</li>
 *   <li>{@code pos → string} ([1,2,3] → "1,2,3" via {@code BlockOffset.toKey()})</li>
 * </ul>
 */
public sealed interface ParamType {

    /** A free-form string value. */
    record StringType() implements ParamType {}

    /** An integer value. Implicitly convertible to string. */
    record IntType() implements ParamType {}

    /** A 3D position {@code [x, y, z]}. Implicitly convertible to string. */
    record PosType() implements ParamType {}

    /** An ordered list of 3D positions. Iterable by {@code for_each}. */
    record ListPosType() implements ParamType {}

    /** An ordered list of strings. Iterable by {@code for_each}. */
    record ListStringType() implements ParamType {}

    /** A key-value map of string → string. Queriable via {@code get}. */
    record MapStringStringType() implements ParamType {}

    /**
     * An ordered list of JSON objects (e.g. decoration entities
     * {@code [{offset, type, facing, nbt}, ...]}). Iterable by {@code for_each}.
     */
    record ListObjectType() implements ParamType {}

    // ---- Convenience instances ----

    ParamType STRING = new StringType();
    ParamType INT = new IntType();
    ParamType POS = new PosType();
    ParamType LIST_POS = new ListPosType();
    ParamType LIST_STRING = new ListStringType();
    ParamType MAP_STRING_STRING = new MapStringStringType();
    ParamType LIST_OBJECT = new ListObjectType();

    /**
     * Parse a type string (e.g. "string", "list<pos>") into a ParamType.
     * Returns null for unrecognized type strings.
     */
    static ParamType parse(String typeStr) {
        return switch (typeStr) {
            case "string" -> STRING;
            case "int" -> INT;
            case "pos" -> POS;
            case "list<pos>" -> LIST_POS;
            case "list<string>" -> LIST_STRING;
            case "map<string,string>" -> MAP_STRING_STRING;
            case "list<object>", "list<map<string,string>>" -> LIST_OBJECT;
            default -> null;
        };
    }
}
