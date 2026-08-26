package com.wsteam.wandscape.shared.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket.BuildingEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 回归测试：空地/未建成建筑（一个方块都没有）也能被准心射线选中——
 * 核心是 {@link BuildingAreaSyncPacket#raycastUnbuilt} 的边界框射线判定。
 */
@DisplayName("BuildingAreaSyncPacket: 空工地射线选中")
class BuildingAreaSyncPacketTest {

    @AfterEach
    void resetCache() {
        BuildingAreaSyncPacket.handleClient(new BuildingAreaSyncPacket(List.of()));
    }

    private static BuildingEntry entry(BlockPos anchor, boolean completed,
                                       int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new BuildingEntry(anchor, "test", "basic", 0, completed, true,
                minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Test
    @DisplayName("worldBox 按 anchor + 偏移换算世界 AABB，无边界时返回 null")
    void worldBox() {
        BlockPos anchor = new BlockPos(10, 20, 30);
        var e = entry(anchor, false, 0, 0, 0, 4, 4, 4);
        AABB box = BuildingAreaSyncPacket.worldBox(e);
        assertEquals(new AABB(10, 20, 30, 15, 25, 35), box);

        var noBoundary = new BuildingEntry(anchor, "t", "basic", 0, false, false, 0, 0, 0, 0, 0, 0);
        assertNull(BuildingAreaSyncPacket.worldBox(noBoundary));
    }

    @Test
    @DisplayName("interiorBlockPos 返回盒内代表点（中心格）")
    void interiorBlockPos() {
        var e = entry(new BlockPos(0, 0, 0), false, 0, 0, 0, 4, 4, 4);
        assertEquals(new BlockPos(2, 2, 2), BuildingAreaSyncPacket.interiorBlockPos(e));

        var noBoundary = new BuildingEntry(new BlockPos(7, 8, 9), "t", "basic", 0, false, false, 0, 0, 0, 0, 0, 0);
        assertEquals(new BlockPos(7, 8, 9), BuildingAreaSyncPacket.interiorBlockPos(noBoundary));
    }

    @Test
    @DisplayName("buildingId 是稳定可复现的（type@anchor），且 findBuildingIdAt 复用同一派发")
    void buildingIdStable() {
        var e = entry(new BlockPos(1, 2, 3), false, 0, 0, 0, 4, 4, 4);
        UUID id1 = BuildingAreaSyncPacket.buildingId(e);
        UUID id2 = BuildingAreaSyncPacket.buildingId(entry(new BlockPos(1, 2, 3), true, 0, 0, 0, 4, 4, 4));
        assertEquals(id1, id2);
        assertNotEquals(id1, BuildingAreaSyncPacket.buildingId(entry(new BlockPos(9, 9, 9), false, 0, 0, 0, 4, 4, 4)));
    }

    @Test
    @DisplayName("射线穿过未建成建筑边界框 → 选中该建筑且给出盒内坐标")
    void raycastHitsUnbuiltBox() {
        var e = entry(new BlockPos(0, 0, 0), false, 0, 0, 0, 4, 4, 4);
        BuildingAreaSyncPacket.handleClient(new BuildingAreaSyncPacket(List.of(e)));

        Vec3 origin = new Vec3(1, 2, -5);
        Vec3 end = new Vec3(1, 2, 10);
        var hit = BuildingAreaSyncPacket.raycastUnbuilt(origin, end);

        assertNotNull(hit);
        assertEquals(BuildingAreaSyncPacket.buildingId(e), hit.buildingId());
        assertEquals(new BlockPos(2, 2, 2), hit.pos());
        // 判定盒外扩 1 格：z 从 -1 进入（原盒从 z=0），射线从 z=-5 起 → 距离² = 16
        assertEquals(16.0, hit.distSq());
    }

    @Test
    @DisplayName("射线不穿任何框 → null")
    void raycastMisses() {
        var e = entry(new BlockPos(0, 0, 0), false, 0, 0, 0, 4, 4, 4);
        BuildingAreaSyncPacket.handleClient(new BuildingAreaSyncPacket(List.of(e)));

        Vec3 origin = new Vec3(20, 10, -5);
        Vec3 end = new Vec3(20, 10, 10);
        assertNull(BuildingAreaSyncPacket.raycastUnbuilt(origin, end));
    }

    @Test
    @DisplayName("已建成建筑不参与空工地选中")
    void completedBuildingSkipped() {
        var done = entry(new BlockPos(0, 0, 0), true, 0, 0, 0, 4, 4, 4);
        BuildingAreaSyncPacket.handleClient(new BuildingAreaSyncPacket(List.of(done)));

        Vec3 origin = new Vec3(1, 2, -5);
        Vec3 end = new Vec3(1, 2, 10);
        assertNull(BuildingAreaSyncPacket.raycastUnbuilt(origin, end));
    }

    @Test
    @DisplayName("准心起点站在工地框内 → 仍算选中该工地")
    void originInsideBoxCounts() {
        var e = entry(new BlockPos(0, 0, 0), false, 0, 0, 0, 4, 4, 4);
        BuildingAreaSyncPacket.handleClient(new BuildingAreaSyncPacket(List.of(e)));

        // 起点 (2,2,2) 在框内：AABB.clip 从内部出发为空，靠 contains 兜底。
        Vec3 origin = new Vec3(2, 2, 2);
        Vec3 end = new Vec3(2, 2, 10);
        var hit = BuildingAreaSyncPacket.raycastUnbuilt(origin, end);

        assertNotNull(hit);
        assertEquals(BuildingAreaSyncPacket.buildingId(e), hit.buildingId());
        // 起点在框内：按 0 距离兜底命中
        assertEquals(0.0, hit.distSq());
    }

    @Test
    @DisplayName("多建筑在同一条射线上时选中最近的一个")
    void picksNearest() {
        var near = entry(new BlockPos(0, 0, 0), false, 0, 0, 0, 4, 4, 4);
        var far = entry(new BlockPos(0, 0, 10), false, 0, 0, 0, 4, 4, 4);
        BuildingAreaSyncPacket.handleClient(new BuildingAreaSyncPacket(List.of(near, far)));

        Vec3 origin = new Vec3(1, 1, -5);
        Vec3 end = new Vec3(1, 1, 20);
        var hit = BuildingAreaSyncPacket.raycastUnbuilt(origin, end);

        assertNotNull(hit);
        assertEquals(BuildingAreaSyncPacket.buildingId(near), hit.buildingId());
        assertEquals(16.0, hit.distSq());   // 近框入口距离² 16 < 远框 196
    }

    @Test
    @DisplayName("站在某工地框内但看向另一工地时，优先选被目视穿过的那个")
    void insideOneLookingAtAnotherPrefersCrossed() {
        var underfoot = entry(new BlockPos(0, 0, 0), false, 0, 0, 0, 4, 4, 4);
        var ahead = entry(new BlockPos(0, 0, 10), false, 0, 0, 0, 4, 4, 4);
        BuildingAreaSyncPacket.handleClient(new BuildingAreaSyncPacket(List.of(underfoot, ahead)));

        // 起点 (2,2,2) 在 underfoot 内，但射线向前穿过 ahead。
        Vec3 origin = new Vec3(2, 2, 2);
        Vec3 end = new Vec3(2, 2, 20);
        var hit = BuildingAreaSyncPacket.raycastUnbuilt(origin, end);

        assertNotNull(hit);
        assertEquals(BuildingAreaSyncPacket.buildingId(ahead), hit.buildingId());
    }

    @Test
    @DisplayName("俯视擦边射线（上方 45° 斜射近底角）仍能选中——判定盒外扩")
    void descendingGazeRegisters() {
        var e = entry(new BlockPos(0, 0, 0), false, 0, 0, 0, 4, 4, 4);
        BuildingAreaSyncPacket.handleClient(new BuildingAreaSyncPacket(List.of(e)));

        // 默认俯瞰相机：从上方 45° 斜射建筑近底角，射线擦着入框——不外扩会在
        // 近底角与地形命中几乎同时、无法胜出。
        Vec3 origin = new Vec3(0, 6, -6);
        Vec3 end = new Vec3(0, 0, 0);
        var hit = BuildingAreaSyncPacket.raycastUnbuilt(origin, end);

        assertNotNull(hit);
        assertEquals(BuildingAreaSyncPacket.buildingId(e), hit.buildingId());
        // 外扩后入口在 z=-1、y=1：距离² = 50，明确早于地形。
        assertEquals(50.0, hit.distSq());
    }

    @Test
    @DisplayName("空缓存不抛异常")
    void emptyCache() {
        BuildingAreaSyncPacket.handleClient(new BuildingAreaSyncPacket(List.of()));
        assertNull(BuildingAreaSyncPacket.raycastUnbuilt(new Vec3(0, 0, 0), new Vec3(0, 0, 10)));
    }

    @Test
    @DisplayName("clear 清空缓存：离开世界后不再用旧存档建筑误判重叠")
    void clearEmptiesCache() {
        var e = entry(new BlockPos(0, 0, 0), false, 0, 0, 0, 4, 4, 4);
        BuildingAreaSyncPacket.handleClient(new BuildingAreaSyncPacket(List.of(e)));
        assertNotNull(BuildingAreaSyncPacket.findBuildingIdAt(new BlockPos(2, 2, 2)));

        BuildingAreaSyncPacket.clear();

        assertTrue(BuildingAreaSyncPacket.getCached().isEmpty());
        assertNull(BuildingAreaSyncPacket.findBuildingIdAt(new BlockPos(2, 2, 2)));
    }
}