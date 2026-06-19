package com.wsteam.wandscape.shared.data;

public enum BehaviorType {
    BUILDING("building"),
    FARMING("farming"),
    MINING("mining"),
    LOGGING("logging"),
    CRAFTING("crafting"),
    GATHERING("gathering"),
    RITUAL("ritual"),
    ENTITY_INTERACTION("entity_interaction");

    private final String id;

    BehaviorType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static BehaviorType fromId(String id) {
        for (BehaviorType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }
}
