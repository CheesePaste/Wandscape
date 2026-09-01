package com.wsteam.wandscape.shared.data;

import com.wsteam.wandscape.content.task.engine.dsl.ParamType;

/**
 * Client-safe mirror of {@link ParamType}.
 * Used in GUI to display parameter types and validate user input.
 *
 * <p>Cannot reference core types directly from shared/data, so this enum
 * replicates the six {@code ParamType} sealed variants. The
 * {@link #fromCore(ParamType)} converter
 * is server-only and called exclusively from {@code TaskNetworkHandler}.
 */
public enum ParamTypeInfo {
    STRING,
    INT,
    POS,           // [x, y, z]
    LIST_POS,      // [[x,y,z], ...]
    LIST_STRING,   // ["a", "b"]
    MAP_STRING_STRING;

    /**
     * Convert a core {@code ParamType} to its shared mirror.
     * Only called server-side from {@code TaskNetworkHandler}.
     */
    public static ParamTypeInfo fromCore(ParamType coreType) {
        if (coreType instanceof ParamType.StringType) return STRING;
        if (coreType instanceof ParamType.IntType) return INT;
        if (coreType instanceof ParamType.PosType) return POS;
        if (coreType instanceof ParamType.ListPosType) return LIST_POS;
        if (coreType instanceof ParamType.ListStringType) return LIST_STRING;
        if (coreType instanceof ParamType.MapStringStringType) return MAP_STRING_STRING;
        return STRING;
    }
}
