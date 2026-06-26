package com.wsteam.wandscape.shared.data;

import com.google.gson.annotations.SerializedName;

public enum ElementType {
    @SerializedName("earth") EARTH("earth", 1),
    @SerializedName("wood") WOOD("wood", 1),
    @SerializedName("water") WATER("water", 1),
    @SerializedName("fire") FIRE("fire", 2),
    @SerializedName("iron") IRON("iron", 2),
    @SerializedName("wind") WIND("wind", 2),
    @SerializedName("gold") GOLD("gold", 3),
    @SerializedName("diamond") DIAMOND("diamond", 3),
    @SerializedName("ender") ENDER("ender", 3);

    private final String id;
    private final int tier;

    ElementType(String id, int tier) {
        this.id = id;
        this.tier = tier;
    }

    public String getId() {
        return id;
    }

    public int getTier() {
        return tier;
    }

    public static ElementType fromId(String id) {
        for (ElementType type : values()) {
            if (type.id.equals(id)) return type;
        }
        throw new IllegalArgumentException("Unknown element type: " + id);
    }
}
