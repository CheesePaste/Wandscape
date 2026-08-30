package com.wsteam.wandscape.shared.data;

import com.google.gson.annotations.SerializedName;

import java.util.Map;
public enum ElementType {
    @SerializedName("earth") EARTH("earth"),
    @SerializedName("wood") WOOD("wood"),
    @SerializedName("water") WATER("water"),
    @SerializedName("fire") FIRE("fire"),
    @SerializedName("metal") METAL("metal"),
    @SerializedName("wind") WIND("wind"),
    @SerializedName("dark") DARK("dark");

    private final String id;

    ElementType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static ElementType fromId(String id) {
        for (ElementType type : values()) {
            if (type.id.equals(id)) return type;
        }
        throw new IllegalArgumentException("Unknown element type: " + id);
    }

    /** 是否每种元素存量都 ≥ cost（缺元素按 0 记）。纯逻辑，可单测。 */
    public static boolean allEnough(Map<ElementType, Long> balances, long cost) {
        for (ElementType t : values()) {
            if (balances.getOrDefault(t, 0L) < cost) return false;
        }
        return true;
    }
}
