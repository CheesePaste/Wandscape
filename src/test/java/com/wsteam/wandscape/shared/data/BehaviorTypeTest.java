package com.wsteam.wandscape.shared.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BehaviorTypeTest {

    @Test
    void fromId_building_returnsBuilding() {
        assertEquals(BehaviorType.BUILDING, BehaviorType.fromId("building"));
    }

    @Test
    void fromId_farming_returnsFarming() {
        assertEquals(BehaviorType.FARMING, BehaviorType.fromId("farming"));
    }

    @Test
    void fromId_mining_returnsMining() {
        assertEquals(BehaviorType.MINING, BehaviorType.fromId("mining"));
    }

    @Test
    void fromId_logging_returnsLogging() {
        assertEquals(BehaviorType.LOGGING, BehaviorType.fromId("logging"));
    }

    @Test
    void fromId_crafting_returnsCrafting() {
        assertEquals(BehaviorType.CRAFTING, BehaviorType.fromId("crafting"));
    }

    @Test
    void fromId_gathering_returnsGathering() {
        assertEquals(BehaviorType.GATHERING, BehaviorType.fromId("gathering"));
    }

    @Test
    void fromId_ritual_returnsRitual() {
        assertEquals(BehaviorType.RITUAL, BehaviorType.fromId("ritual"));
    }

    @Test
    void fromId_entityInteraction_returnsEntityInteraction() {
        assertEquals(BehaviorType.ENTITY_INTERACTION, BehaviorType.fromId("entity_interaction"));
    }

    @Test
    void fromId_null_returnsNull() {
        assertNull(BehaviorType.fromId(null));
    }

    @Test
    void fromId_emptyString_returnsNull() {
        assertNull(BehaviorType.fromId(""));
    }

    @Test
    void fromId_unknown_returnsNull() {
        assertNull(BehaviorType.fromId("exploration"));
    }

    @Test
    void fromId_isCaseSensitive() {
        assertNull(BehaviorType.fromId("Building"));
    }

    @Test
    void getId_and_fromId_roundTrip() {
        for (BehaviorType type : BehaviorType.values()) {
            assertEquals(type, BehaviorType.fromId(type.getId()),
                "Round-trip failed for " + type.name());
        }
    }
}
