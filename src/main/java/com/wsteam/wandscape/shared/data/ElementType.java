package com.wsteam.wandscape.shared.data;

public enum ElementType {
    EARTH("earth", 1),
    WOOD("wood", 1),
    WATER("water", 1),
    FIRE("fire", 2),
    IRON("iron", 2),
    WIND("wind", 2),
    GOLD("gold", 3),
    DIAMOND("diamond", 3),
    ENDER("ender", 3);

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
}
