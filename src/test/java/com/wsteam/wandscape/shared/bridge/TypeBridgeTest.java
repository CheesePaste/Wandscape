package com.wsteam.wandscape.shared.bridge;

import com.wsteam.wandscape.shared.data.BehaviorType;
import com.wsteam.wandscape.core.types.BehaviourTag;
import com.wsteam.wandscape.core.types.ResourceId;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TypeBridgeTest {

    @Test
    void toBehaviourTag_allEightTypes_roundTrip() {
        for (BehaviorType bt : BehaviorType.values()) {
            BehaviourTag tag = TypeBridge.toBehaviourTag(bt);
            assertEquals(bt.name(), tag.name());
            assertEquals(bt, TypeBridge.toBehaviorType(tag));
        }
    }

    @Test
    void toBehaviorType_allEightTags_roundTrip() {
        for (BehaviourTag tag : BehaviourTag.values()) {
            BehaviorType bt = TypeBridge.toBehaviorType(tag);
            assertEquals(tag.name(), bt.name());
            assertEquals(tag, TypeBridge.toBehaviourTag(bt));
        }
    }

    @Test
    void elementResourceId_withSimpleName() {
        ResourceId id = TypeBridge.elementResourceId("earth");
        assertEquals("element:earth", id.id());
    }

    @Test
    void itemResourceId_withSimpleName() {
        ResourceId id = TypeBridge.itemResourceId("stone");
        assertEquals("item:stone", id.id());
    }

    @Test
    void isElementResource_withElementPrefix_returnsTrue() {
        assertTrue(TypeBridge.isElementResource(TypeBridge.elementResourceId("fire")));
    }

    @Test
    void isElementResource_withItemPrefix_returnsFalse() {
        assertFalse(TypeBridge.isElementResource(TypeBridge.itemResourceId("wood")));
    }

    @Test
    void isItemResource_withItemPrefix_returnsTrue() {
        assertTrue(TypeBridge.isItemResource(TypeBridge.itemResourceId("iron")));
    }

    @Test
    void isItemResource_withElementPrefix_returnsFalse() {
        assertFalse(TypeBridge.isItemResource(TypeBridge.elementResourceId("water")));
    }

    @Test
    void isElementResource_emptyId_returnsFalse() {
        assertFalse(TypeBridge.isElementResource(new ResourceId("")));
    }

    @Test
    void isItemResource_emptyId_returnsFalse() {
        assertFalse(TypeBridge.isItemResource(new ResourceId("")));
    }

    @Test
    void resourceIdHelper_integration() {
        ResourceId elemId = TypeBridge.elementResourceId("x");
        assertTrue(TypeBridge.isElementResource(elemId));
        assertFalse(TypeBridge.isItemResource(elemId));

        ResourceId itemId = TypeBridge.itemResourceId("x");
        assertTrue(TypeBridge.isItemResource(itemId));
        assertFalse(TypeBridge.isElementResource(itemId));
    }
}
