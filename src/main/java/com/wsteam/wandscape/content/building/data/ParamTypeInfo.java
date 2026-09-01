package com.wsteam.wandscape.content.building.data;

/**
 * Client-safe enum of blueprint parameter kinds, mirrored in the task-editor GUI.
 * The server-side {@code BlueprintInfo} DTO carries a {@code Map<String, ParamTypeInfo>}
 * of a blueprint's declared params. Blueprints are now Java lambdas (no declarative param
 * list), so producers populate this with an informational type where useful.
 */
public enum ParamTypeInfo {
    STRING,
    INT,
    POS,           // [x, y, z]
    LIST_POS,      // [[x,y,z], ...]
    LIST_STRING,   // ["a", "b"]
    MAP_STRING_STRING
}
