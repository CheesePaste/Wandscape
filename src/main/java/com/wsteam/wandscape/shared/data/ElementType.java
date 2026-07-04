package com.wsteam.wandscape.shared.data;

import com.google.gson.annotations.SerializedName;
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
}
