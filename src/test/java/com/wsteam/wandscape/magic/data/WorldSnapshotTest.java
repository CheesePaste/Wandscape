package com.wsteam.wandscape.magic.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import com.wsteam.wandscape.content.magic.data.WorldSnapshot;
import org.junit.jupiter.api.Test;

class WorldSnapshotTest {

    @Test
    void clampsAndCopies() {
        WorldSnapshot s = new WorldSnapshot(-3, 2.5f, -1f, null);
        assertEquals(0, s.enemyCount());
        assertEquals(1f, s.selfHpRatio(), 1e-6f);
        assertEquals(0f, s.allyLowestHpRatio(), 1e-6f);
        assertEquals(Set.of(), s.activeEffects());
    }

    @Test
    void activeEffectsIsDefensiveCopy() {
        Set<String> mutable = new java.util.HashSet<>(List.of("minecraft:speed"));
        WorldSnapshot s = new WorldSnapshot(1, 1f, 1f, mutable);
        mutable.add("minecraft:strength");
        assertEquals(Set.of("minecraft:speed"), s.activeEffects(), "快照不持有调用方的可变引用");
    }

    @Test
    void hasHostileTarget() {
        assertTrue(new WorldSnapshot(1, 1f, 1f, Set.of()).hasHostileTarget());
        assertFalse(WorldSnapshot.EMPTY.hasHostileTarget());
    }

    @Test
    void hasInjuredAlly() {
        assertTrue(new WorldSnapshot(0, 1f, 0.5f, Set.of()).hasInjuredAlly());
        assertFalse(new WorldSnapshot(0, 1f, 1f, Set.of()).hasInjuredAlly());
        assertFalse(WorldSnapshot.EMPTY.hasInjuredAlly(), "无友方 = 满血 = 无受伤友方");
    }

    @Test
    void emptyIsStable() {
        assertSame(WorldSnapshot.EMPTY, WorldSnapshot.EMPTY);
        assertEquals(0, WorldSnapshot.EMPTY.enemyCount());
        assertEquals(1f, WorldSnapshot.EMPTY.selfHpRatio());
    }
}
