package com.wsteam.wandscape.citizen;

/**
 * Citizen professions with display names.
 * Maps to vanilla {@code VillagerProfession.NONE} — we manage profession
 * ourselves rather than using vanilla's job site binding system.
 */
public enum Profession {
    FARMER("农民"),
    MERCHANT("商人"),
    SCHOLAR("学者"),
    ARTISAN("工匠"),
    GUARD("守卫"),
    IDLER("无业");

    private final String displayName;

    Profession(String displayName) {
        this.displayName = displayName;
    }

    /** The Chinese display name shown in right-click feedback. */
    public String getDisplayName() {
        return displayName;
    }
}
