package com.wsteam.wandscape.ring.internal;

import com.wsteam.wandscape.content.items.ring.internal.OathRingStorage;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OathRingStorage} — 固定槽语义 + NBT 往返。
 * 容量用自选字面量（1/2/4 档位容量属 RingTier 数据，不在此钉死）。
 */
class OathRingStorageTest {

    private static CompoundTag mage(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "wandscape:wandscape_npc");
        tag.putString("marker", id);
        return tag;
    }

    @Test
    void storeUsesLowestFreeSlotWithinCapacity() {
        OathRingStorage s = new OathRingStorage();
        assertEquals(0, s.findStoreSlot(2));
        s.put(0, mage("a"));
        assertEquals(1, s.findStoreSlot(2));
        s.put(1, mage("b"));
        assertEquals(-1, s.findStoreSlot(2)); // 容量内已满
        assertEquals(2, s.findStoreSlot(4));  // 高容量下可继续存槽 2
    }

    @Test
    void releaseTakesLowestOccupiedSlot() {
        OathRingStorage s = new OathRingStorage();
        s.put(0, mage("a"));
        s.put(2, mage("c"));
        assertEquals(0, s.findReleaseSlot(4));
        s.remove(0);
        assertEquals(2, s.findReleaseSlot(4)); // 槽 1 从未被占，跳过
    }

    @Test
    void fixedSlotsDoNotCollapseOnRelease() {
        OathRingStorage s = new OathRingStorage();
        s.put(0, mage("a"));
        s.put(1, mage("b"));
        s.put(2, mage("c"));
        s.remove(0);                          // 释放槽 0
        assertNull(s.get(0));
        assertNotNull(s.get(1));              // 槽 1 法师不前移 (固定槽语义)
        assertNotNull(s.get(2));
        assertEquals(1, s.findReleaseSlot(4));
    }

    @Test
    void capacityBoundsBothDirections() {
        OathRingStorage s = new OathRingStorage();
        s.put(0, mage("a"));
        for (int capacity : new int[]{1, 2, 4}) {
            assertEquals(0, s.findReleaseSlot(capacity)); // 首个已占槽 0 各容量都可见
        }
        s.remove(0);
        s.put(3, mage("d"));
        assertEquals(-1, s.findReleaseSlot(2));           // 槽 3 超出容量 2
        assertEquals(3, s.findReleaseSlot(4));            // 容量 4 可见
        assertEquals(-1, s.findStoreSlot(0));             // 容量 0 不可存
    }

    @Test
    void nbtRoundTripPreservesSlotPositionsAndBlobs() {
        OathRingStorage s = new OathRingStorage();
        s.put(0, mage("a"));
        s.put(3, mage("d"));
        CompoundTag packed = s.toNbt();

        OathRingStorage restored = OathRingStorage.fromNbt(packed);
        assertEquals(0, restored.findReleaseSlot(4));
        assertEquals("a", restored.get(0).getString("marker"));
        assertEquals("d", restored.get(3).getString("marker"));
        assertNull(restored.get(1));
        assertNull(restored.get(2));
        assertTrue(restored.hasAnyStored());
    }

    @Test
    void emptyStorageRoundTripsToEmpty() {
        OathRingStorage restored = OathRingStorage.fromNbt(new OathRingStorage().toNbt());
        assertFalse(restored.hasAnyStored());
        assertEquals(-1, restored.findReleaseSlot(4));
        assertEquals(0, restored.findStoreSlot(4));
    }

    @Test
    void toMaskReflectsOccupiedSlots() {
        OathRingStorage s = new OathRingStorage();
        assertEquals(0, s.toMask());
        s.put(0, mage("a"));
        assertEquals((byte) 0b0001, s.toMask());
        s.put(2, mage("c"));
        assertEquals((byte) 0b0101, s.toMask());
        s.put(3, mage("d"));
        assertEquals((byte) 0b1101, s.toMask());
        s.remove(0);
        assertEquals((byte) 0b1100, s.toMask());
    }
}