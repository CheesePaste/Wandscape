package com.wsteam.wandscape.shared.bridge;

import com.wsteam.wandscape.shared.data.BehaviorType;
import com.wsteam.wandscape.shared.data.TaskStatus;

import org.junit.jupiter.api.Test;
import org.magiccolony.core.task.TaskState;
import org.magiccolony.core.types.BehaviourTag;
import org.magiccolony.core.types.ResourceId;

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
    void toTaskState_pendingApproval() {
        assertEquals(TaskState.PENDING_APPROVAL,
            TypeBridge.toTaskState(TaskStatus.PENDING_APPROVAL));
    }

    @Test
    void toTaskState_pendingAssign() {
        assertEquals(TaskState.PENDING_ASSIGN,
            TypeBridge.toTaskState(TaskStatus.PENDING_ASSIGN));
    }

    @Test
    void toTaskState_inProgress() {
        assertEquals(TaskState.IN_PROGRESS,
            TypeBridge.toTaskState(TaskStatus.IN_PROGRESS));
    }

    @Test
    void toTaskState_awaitingMaterials_mapsToAwaitingResources() {
        assertEquals(TaskState.AWAITING_RESOURCES,
            TypeBridge.toTaskState(TaskStatus.AWAITING_MATERIALS));
    }

    @Test
    void toTaskState_interrupted() {
        assertEquals(TaskState.INTERRUPTED,
            TypeBridge.toTaskState(TaskStatus.INTERRUPTED));
    }

    @Test
    void toTaskState_completed() {
        assertEquals(TaskState.COMPLETED,
            TypeBridge.toTaskState(TaskStatus.COMPLETED));
    }

    @Test
    void toTaskStatus_allSixStates_roundTrip() {
        for (TaskStatus status : TaskStatus.values()) {
            TaskState state = TypeBridge.toTaskState(status);
            TaskStatus back = TypeBridge.toTaskStatus(state);
            assertEquals(status, back, "Round-trip failed for " + status);
        }
    }

    @Test
    void toTaskStatus_awaitingResources_mapsToAwaitingMaterials() {
        assertEquals(TaskStatus.AWAITING_MATERIALS,
            TypeBridge.toTaskStatus(TaskState.AWAITING_RESOURCES));
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
