package com.wsteam.wandscape.shared.data;

/**
 * Client-safe mirror of {@link com.wsteam.wandscape.core.task.ParamType}.
 * Used in GUI to display parameter types and validate user input.
 *
 * <p>Cannot reference core types directly from shared/data, so this enum
 * replicates the six {@code ParamType} sealed variants. The
 * {@link #fromCore(com.wsteam.wandscape.core.task.ParamType)} converter
 * is server-only and called exclusively from {@code TaskApiImpl}.
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
     * Only called server-side from {@code TaskApiImpl}.
     */
    public static ParamTypeInfo fromCore(com.wsteam.wandscape.core.task.ParamType coreType) {
        if (coreType instanceof com.wsteam.wandscape.core.task.ParamType.StringType) return STRING;
        if (coreType instanceof com.wsteam.wandscape.core.task.ParamType.IntType) return INT;
        if (coreType instanceof com.wsteam.wandscape.core.task.ParamType.PosType) return POS;
        if (coreType instanceof com.wsteam.wandscape.core.task.ParamType.ListPosType) return LIST_POS;
        if (coreType instanceof com.wsteam.wandscape.core.task.ParamType.ListStringType) return LIST_STRING;
        if (coreType instanceof com.wsteam.wandscape.core.task.ParamType.MapStringStringType) return MAP_STRING_STRING;
        return STRING;
    }
}
