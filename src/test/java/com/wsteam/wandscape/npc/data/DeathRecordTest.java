package com.wsteam.wandscape.npc.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DeathRecordTest {

    private static DeathRecord rec(int x, int y, int z) {
        return new DeathRecord(UUID.randomUUID(), "Wizard", "minecraft:overworld",
                x, y, z, 1000L, UUID.randomUUID(), 0, 0, true,
                40f, 0.3f, 1f, 1f, 1f, 0f, 200f, List.of(), List.of());
    }

    @Test
    void nearestPicksClosest3D() {
        List<DeathRecord> records = List.of(rec(0, 0, 0), rec(10, 0, 0), rec(0, 5, 0));
        DeathRecord r = DeathRecord.nearest(records, 3, 0, 0, 32);
        assertEquals(0, r.x(), "3D 距离最近的是原点记录");
        assertEquals(0, r.y());
        assertEquals(0, r.z());
    }

    @Test
    void nearestRespectsMaxRange() {
        List<DeathRecord> records = List.of(rec(50, 0, 0));
        assertNull(DeathRecord.nearest(records, 0, 0, 0, 32), "超出范围不返回");
        assertNull(DeathRecord.nearest(List.of(), 0, 0, 0, 32), "空列表返回 null");
    }

    @Test
    void nearestIncludesYDistance() {
        List<DeathRecord> above = List.of(rec(0, 20, 0), rec(5, 0, 0));
        DeathRecord r = DeathRecord.nearest(above, 0, 0, 0, 32);
        assertEquals(5, r.x(), "Y 距离参与比较：水平 5 格优先于垂直 20 格");
    }

    @Test
    void inventoryIsCopied() {
        UUID id = UUID.randomUUID();
        DeathRecord r = new DeathRecord(id, "A", "minecraft:overworld",
                1, 2, 3, 1L, UUID.randomUUID(), 0, 0, true,
                40f, 0.3f, 1f, 1f, 1f, 0f, 200f, List.of(), List.of());
        assertEquals(id, r.npcId());
        assertEquals(1, r.x());
        assertEquals(2, r.y());
        assertEquals(3, r.z());
        assertEquals("A", r.name());
    }

    @Test
    void equippedMagicIsCopiedAndNullSafe() {
        DeathRecord r = new DeathRecord(UUID.randomUUID(), "A", "minecraft:overworld",
                1, 2, 3, 1L, UUID.randomUUID(), 0, 0, true,
                40f, 0.3f, 1f, 1f, 1f, 0f, 200f, List.of(),
                List.of("beam", "heal"));
        assertEquals(List.of("beam", "heal"), r.equippedMagic(), "已装备魔法按死亡快照记录");
        // null 入参兜底为空列表（旧有调用方未传该字段时不抛 NPE）
        DeathRecord noMagic = new DeathRecord(UUID.randomUUID(), "A", "minecraft:overworld",
                1, 2, 3, 1L, UUID.randomUUID(), 0, 0, true,
                40f, 0.3f, 1f, 1f, 1f, 0f, 200f, List.of(), null);
        assertEquals(List.of(), noMagic.equippedMagic());
    }

    @Test
    void latestInColonyPicksNewestDeathTimeIgnoringPosition() {
        DeathRecord old = new DeathRecord(UUID.randomUUID(), "Old", "minecraft:overworld",
                100, 0, 0, 100L, UUID.randomUUID(), 0, 0, true,
                40f, 0.3f, 1f, 1f, 1f, 0f, 200f, List.of(), List.of());
        DeathRecord fresh = new DeathRecord(UUID.randomUUID(), "Fresh", "minecraft:overworld",
                20, 5, 20, 900L, UUID.randomUUID(), 0, 0, true,
                40f, 0.3f, 1f, 1f, 1f, 0f, 200f, List.of(), List.of());
        DeathRecord r = DeathRecord.latestInColony(List.of(old, fresh), null);
        assertEquals("Fresh", r.name(), "取 deathTime 最新的记录，不限位置");
        assertNull(DeathRecord.latestInColony(List.of(), null), "空列表返回 null");
    }

    @Test
    void latestInColonyFiltersByColony() {
        UUID colonyA = UUID.randomUUID();
        UUID colonyB = UUID.randomUUID();
        DeathRecord otherColony = new DeathRecord(UUID.randomUUID(), "Other", "minecraft:overworld",
                10, 0, 0, 2000L, colonyB, 0, 0, true,
                40f, 0.3f, 1f, 1f, 1f, 0f, 200f, List.of(), List.of());
        DeathRecord mine = new DeathRecord(UUID.randomUUID(), "Mine", "minecraft:overworld",
                20, 5, 20, 500L, colonyA, 0, 0, true,
                40f, 0.3f, 1f, 1f, 1f, 0f, 200f, List.of(), List.of());
        DeathRecord r = DeathRecord.latestInColony(List.of(otherColony, mine), colonyA);
        assertEquals("Mine", r.name(), "跳过其他殖民地更新的记录，取本殖民地最新");
        assertNull(DeathRecord.latestInColony(List.of(otherColony), colonyA), "无本殖民地记录返回 null");
    }
}
