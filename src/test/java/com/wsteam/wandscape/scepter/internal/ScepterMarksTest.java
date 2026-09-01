package com.wsteam.wandscape.scepter.internal;

import java.util.UUID;

import com.wsteam.wandscape.content.items.scepter.internal.ScepterMarks;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ScepterMarks} — 庇护 toggle/跨殖民地隔离/敌对单槽+转移+解除/敌对与庇护互斥 + SavedData NBT 往返。
 * 字数用自选字面量，不钉死任何平衡数值。
 */
class ScepterMarksTest {

    private static final UUID COLONY_A = UUID.randomUUID();
    private static final UUID COLONY_B = UUID.randomUUID();
    private static final UUID ENTITY_X = UUID.randomUUID();
    private static final UUID ENTITY_Y = UUID.randomUUID();

    // ── 庇护 ──

    @Test
    void toggleShelterAddsThenRemoves() {
        ScepterMarks m = new ScepterMarks();
        assertFalse(m.isSheltered(COLONY_A, ENTITY_X));
        assertTrue(m.toggleShelter(COLONY_A, ENTITY_X));
        assertTrue(m.isSheltered(COLONY_A, ENTITY_X));
        assertFalse(m.toggleShelter(COLONY_A, ENTITY_X));
        assertFalse(m.isSheltered(COLONY_A, ENTITY_X));
    }

    @Test
    void shelterIsIsolatedPerColony() {
        ScepterMarks m = new ScepterMarks();
        m.toggleShelter(COLONY_A, ENTITY_X);
        assertTrue(m.isSheltered(COLONY_A, ENTITY_X));
        assertFalse(m.isSheltered(COLONY_B, ENTITY_X));   // 其它殖民地不受影响
        assertFalse(m.isShelteredForAny(ENTITY_Y));
        assertTrue(m.isShelteredForAny(ENTITY_X));          // 任意殖民地庇护即成立
    }

    @Test
    void emptyColonyEntryPruned() {
        ScepterMarks m = new ScepterMarks();
        m.toggleShelter(COLONY_A, ENTITY_X);
        assertFalse(m.all().isEmpty());
        m.toggleShelter(COLONY_A, ENTITY_X);  // 解除
        assertTrue(m.all().isEmpty());        // 空条目被清
    }

    // ── 敌对 ──

    @Test
    void hostileIsSingleSlotAndTransfersOnRemark() {
        ScepterMarks m = new ScepterMarks();
        assertNull(m.forcedHostile(COLONY_A));
        assertTrue(m.toggleForcedHostile(COLONY_A, ENTITY_X));
        assertEquals(ENTITY_X, m.forcedHostile(COLONY_A));
        // 转移：右键另一生物替换旧标记
        assertTrue(m.toggleForcedHostile(COLONY_A, ENTITY_Y));
        assertEquals(ENTITY_Y, m.forcedHostile(COLONY_A));
        // 再右键当前目标 → 解除
        assertFalse(m.toggleForcedHostile(COLONY_A, ENTITY_Y));
        assertNull(m.forcedHostile(COLONY_A));
    }

    @Test
    void hostileIsIsolatedPerColony() {
        ScepterMarks m = new ScepterMarks();
        m.toggleForcedHostile(COLONY_A, ENTITY_X);
        assertNull(m.forcedHostile(COLONY_B));
        m.toggleForcedHostile(COLONY_B, ENTITY_Y);
        assertEquals(ENTITY_X, m.forcedHostile(COLONY_A));
        assertEquals(ENTITY_Y, m.forcedHostile(COLONY_B));
    }

    @Test
    void hostileMarkRemovesShelterMutuallyExclusive() {
        ScepterMarks m = new ScepterMarks();
        m.toggleShelter(COLONY_A, ENTITY_X);
        assertTrue(m.isSheltered(COLONY_A, ENTITY_X));
        m.toggleForcedHostile(COLONY_A, ENTITY_X);   // 敌对标记同目标
        assertFalse(m.isSheltered(COLONY_A, ENTITY_X)); // 自动撤庇护
        assertEquals(ENTITY_X, m.forcedHostile(COLONY_A));
    }

    @Test
    void clearForcedHostileByEntityClearsMatching() {
        ScepterMarks m = new ScepterMarks();
        m.toggleForcedHostile(COLONY_A, ENTITY_X);
        m.toggleForcedHostile(COLONY_B, ENTITY_Y);
        assertTrue(m.clearForcedHostileByEntity(ENTITY_X));
        assertNull(m.forcedHostile(COLONY_A));
        assertEquals(ENTITY_Y, m.forcedHostile(COLONY_B)); // 其它殖民地的标记不受影响
        assertFalse(m.clearForcedHostileByEntity(ENTITY_X)); // 已清，无再变
    }

    // ── NBT 往返（SavedData 落盘）──

    @Test
    void savedDataRoundTripPersistsShelterAndHostile() {
        ScepterMarks marks = new ScepterMarks();
        marks.toggleShelter(COLONY_A, ENTITY_X);
        marks.toggleShelter(COLONY_A, ENTITY_Y);
        marks.toggleForcedHostile(COLONY_B, ENTITY_Y);

        CompoundTag nbt = marks.toNbt();
        ScepterMarks loaded = new ScepterMarks();
        loaded.loadFromNbt(nbt);

        assertTrue(loaded.isSheltered(COLONY_A, ENTITY_X));
        assertTrue(loaded.isSheltered(COLONY_A, ENTITY_Y));
        assertTrue(loaded.isShelteredForAny(ENTITY_X));
        assertEquals(ENTITY_Y, loaded.forcedHostile(COLONY_B));
        assertNull(loaded.forcedHostile(COLONY_A));
    }
}