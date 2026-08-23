package com.wsteam.wandscape.core.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;

import org.junit.jupiter.api.Test;

class InventoryTest {

    @Test
    void addEmptyStack_isNoOpReturnsTrue() {
        Inventory inv = new Inventory(4);
        assertTrue(inv.add(new ResourceStack(ResourceId.STONE, 0)));
        assertEquals(0, inv.usedSlots());
        assertEquals(0, inv.count(ResourceId.STONE));
    }

    @Test
    void addSameResource_mergesIntoOneStack() {
        Inventory inv = new Inventory(4);
        assertTrue(inv.add(new ResourceStack(ResourceId.STONE, 5)));
        assertTrue(inv.add(new ResourceStack(ResourceId.STONE, 7)));
        assertEquals(1, inv.usedSlots());
        assertEquals(12, inv.count(ResourceId.STONE));
    }

    @Test
    void addNormalizedState_mergesWithCleanResource() {
        // "stone[axe]" strips to "stone" so it should merge with an existing stone stack.
        Inventory inv = new Inventory(4);
        assertTrue(inv.add(new ResourceStack(ResourceId.STONE, 3)));
        assertTrue(inv.add(new ResourceStack(new ResourceId("stone[axe]"), 4)));
        assertEquals(1, inv.usedSlots());
        assertEquals(7, inv.count(ResourceId.STONE));
    }

    @Test
    void addDifferentResource_usesNewSlot() {
        Inventory inv = new Inventory(4);
        assertTrue(inv.add(new ResourceStack(ResourceId.STONE, 3)));
        assertTrue(inv.add(new ResourceStack(ResourceId.WOOD, 2)));
        assertEquals(2, inv.usedSlots());
        assertEquals(3, inv.count(ResourceId.STONE));
        assertEquals(2, inv.count(ResourceId.WOOD));
    }

    @Test
    void addToFullInventory_returnsFalse() {
        Inventory inv = new Inventory(1);
        assertTrue(inv.add(new ResourceStack(ResourceId.STONE, 3)));
        assertFalse(inv.add(new ResourceStack(ResourceId.WOOD, 2)), "no slot left → add fails");
        assertEquals(1, inv.usedSlots());
        assertEquals(0, inv.count(ResourceId.WOOD));
    }

    @Test
    void isFull_boundaryAtCapacity() {
        Inventory inv = new Inventory(2);
        assertFalse(inv.isFull());
        inv.add(new ResourceStack(ResourceId.STONE, 1));
        assertFalse(inv.isFull(), "size 1 < capacity 2");
        inv.add(new ResourceStack(ResourceId.WOOD, 1));
        assertTrue(inv.isFull(), "size 2 == capacity 2");
    }

    @Test
    void count_sumAndRespectsNormalizedId() {
        Inventory inv = new Inventory(8);
        inv.add(new ResourceStack(ResourceId.STONE, 2));
        inv.add(new ResourceStack(new ResourceId("stone[axe]"), 3));
        inv.add(new ResourceStack(ResourceId.WOOD, 10));
        assertEquals(5, inv.count(ResourceId.STONE));
        assertEquals(10, inv.count(ResourceId.WOOD));
        assertEquals(0, inv.count(ResourceId.GLASS));
    }

    @Test
    void remove_partialAndFull() {
        Inventory inv = new Inventory(8);
        inv.add(new ResourceStack(ResourceId.STONE, 10));
        assertEquals(4, inv.remove(ResourceId.STONE, 4), "partial remove takes 4");
        assertEquals(6, inv.count(ResourceId.STONE));
        assertEquals(6, inv.remove(ResourceId.STONE, 6), "full remove takes remainder");
        assertEquals(0, inv.count(ResourceId.STONE));
        assertEquals(0, inv.usedSlots());
    }

    @Test
    void removeExceedingAmount_dropsWholeStack() {
        Inventory inv = new Inventory(8);
        inv.add(new ResourceStack(ResourceId.STONE, 3));
        assertEquals(3, inv.remove(ResourceId.STONE, 99), "removes only what exists");
        assertEquals(0, inv.count(ResourceId.STONE));
    }

    @Test
    void removeZeroAmount_isNoOp() {
        Inventory inv = new Inventory(8);
        inv.add(new ResourceStack(ResourceId.STONE, 5));
        assertEquals(0, inv.remove(ResourceId.STONE, 0));
        assertEquals(5, inv.count(ResourceId.STONE));
        assertEquals(1, inv.usedSlots());
    }

    @Test
    void remove_matchesNormalizedResourceId() {
        Inventory inv = new Inventory(8);
        inv.add(new ResourceStack(ResourceId.STONE, 5));
        // Removing with a state-qualified id normalizes to "stone" and matches the clean stack.
        assertEquals(3, inv.remove(new ResourceId("stone[axe]"), 3));
        assertEquals(2, inv.count(ResourceId.STONE));
    }

    @Test
    void hasEnough_comparesAgainstCount() {
        Inventory inv = new Inventory(8);
        inv.add(new ResourceStack(ResourceId.STONE, 5));
        assertTrue(inv.hasEnough(ResourceId.STONE, 5));
        assertFalse(inv.hasEnough(ResourceId.STONE, 6));
        assertFalse(inv.hasEnough(ResourceId.WOOD, 1));
    }
}
