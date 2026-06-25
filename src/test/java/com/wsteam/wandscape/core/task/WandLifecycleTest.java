package com.wsteam.wandscape.core.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WandLifecycleTest {

    private WandLifecycle lifecycle;
    private UUID colonyId;
    private static final String WAND_ID = "wandscape:gatherer_wand";

    @BeforeEach
    void setUp() {
        lifecycle = new WandLifecycle();
        colonyId = UUID.randomUUID();
    }

    @Test
    void newWand_defaultsToAvailable() {
        assertTrue(lifecycle.isAvailable(colonyId, WAND_ID));
    }

    @Test
    void reserve_marksAsReserved() {
        assertTrue(lifecycle.reserve(colonyId, WAND_ID));
        assertEquals(WandLifecycleState.RESERVED, lifecycle.getState(colonyId, WAND_ID));
        assertFalse(lifecycle.isAvailable(colonyId, WAND_ID));
    }

    @Test
    void reserve_alreadyReserved_returnsFalse() {
        assertTrue(lifecycle.reserve(colonyId, WAND_ID));
        assertFalse(lifecycle.reserve(colonyId, WAND_ID));
    }

    @Test
    void reserve_alreadyEquipped_returnsFalse() {
        lifecycle.reserve(colonyId, WAND_ID);
        lifecycle.confirmEquip(colonyId, WAND_ID);
        assertFalse(lifecycle.reserve(colonyId, WAND_ID));
    }

    @Test
    void fullLifecycle_warehouseToEquippedAndBack() {
        // IN_WAREHOUSE (default) → RESERVED
        assertTrue(lifecycle.reserve(colonyId, WAND_ID));
        assertEquals(WandLifecycleState.RESERVED, lifecycle.getState(colonyId, WAND_ID));

        // → IN_TRANSIT_TO_NPC
        lifecycle.startTransitToNpc(colonyId, WAND_ID);
        assertEquals(WandLifecycleState.IN_TRANSIT_TO_NPC, lifecycle.getState(colonyId, WAND_ID));

        // → EQUIPPED
        lifecycle.confirmEquip(colonyId, WAND_ID);
        assertEquals(WandLifecycleState.EQUIPPED, lifecycle.getState(colonyId, WAND_ID));
        assertTrue(lifecycle.isEquipped(colonyId, WAND_ID));

        // → IN_TRANSIT_TO_WAREHOUSE
        lifecycle.startReturn(colonyId, WAND_ID);
        assertEquals(WandLifecycleState.IN_TRANSIT_TO_WAREHOUSE, lifecycle.getState(colonyId, WAND_ID));

        // → IN_WAREHOUSE
        lifecycle.confirmReturn(colonyId, WAND_ID);
        assertEquals(WandLifecycleState.IN_WAREHOUSE, lifecycle.getState(colonyId, WAND_ID));
        assertTrue(lifecycle.isAvailable(colonyId, WAND_ID));
    }

    @Test
    void release_reservedWand_backToWarehouse() {
        lifecycle.reserve(colonyId, WAND_ID);
        lifecycle.release(colonyId, WAND_ID);
        assertEquals(WandLifecycleState.IN_WAREHOUSE, lifecycle.getState(colonyId, WAND_ID));
        assertTrue(lifecycle.isAvailable(colonyId, WAND_ID));
    }

    @Test
    void release_equippedWand_noOp() {
        lifecycle.reserve(colonyId, WAND_ID);
        lifecycle.confirmEquip(colonyId, WAND_ID);
        lifecycle.release(colonyId, WAND_ID);
        assertEquals(WandLifecycleState.EQUIPPED, lifecycle.getState(colonyId, WAND_ID));
    }

    @Test
    void multipleColonies_independentState() {
        UUID colony2 = UUID.randomUUID();

        lifecycle.reserve(colonyId, WAND_ID);
        assertEquals(WandLifecycleState.RESERVED, lifecycle.getState(colonyId, WAND_ID));
        assertTrue(lifecycle.isAvailable(colony2, WAND_ID)); // different colony
    }

    @Test
    void removeColony_clearsAllWandState() {
        lifecycle.reserve(colonyId, WAND_ID);
        lifecycle.reserve(colonyId, "wandscape:builder_wand");

        lifecycle.removeColony(colonyId);

        assertNull(lifecycle.getState(colonyId, WAND_ID));
        assertNull(lifecycle.getState(colonyId, "wandscape:builder_wand"));
    }

    @Test
    void clear_removesEverything() {
        UUID colony2 = UUID.randomUUID();
        lifecycle.reserve(colonyId, WAND_ID);
        lifecycle.reserve(colony2, "wandscape:builder_wand");

        lifecycle.clear();

        assertNull(lifecycle.getState(colonyId, WAND_ID));
        assertNull(lifecycle.getState(colony2, "wandscape:builder_wand"));
    }
}
