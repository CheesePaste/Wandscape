package com.wsteam.wandscape.road.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * Lifecycle tests for {@link RoadPlacementState}'s selection cache.
 *
 * <p>Covers the suspend/exit split and the pure phase-flip behaviour of
 * {@code enterBar}/{@code enterPlacing}: temporary mode switches (C 切相位 / ESC / tab
 * switch / panel close) preserve the in-progress start/end positions, tool and ref block,
 * while a full {@code exitProjection} (disconnect) or explicit {@code clearAll} (submit /
 * undo) clears them. {@code Log.info} is a no-op unless verbose, so these run without a MC
 * runtime.
 */
@DisplayName("RoadPlacementState 选取缓存生命周期")
class RoadPlacementStateTest {

    @BeforeEach
    void resetState() {
        // exitProjection fully clears static state so tests do not leak into each other.
        RoadPlacementState.exitProjection();
    }

    @Test
    @DisplayName("enterBar / enterPlacing 为纯相位翻转，保留 startPos/endPos 与工具")
    void phaseFlipPreservesPositionsAndTool() {
        RoadPlacementState.enterPlacing();
        BlockPos start = new BlockPos(10, 64, 10);
        BlockPos end = new BlockPos(20, 64, 20);
        RoadPlacementState.setStartPos(start);
        RoadPlacementState.setEndPos(end);
        RoadPlacementState.setActiveTool(RoadPlacementState.ToolMode.FILL);

        // enterPlacing already set PLACING; flip to BAR and back — selection must survive.
        RoadPlacementState.enterBar();
        assertEquals(RoadPlacementState.RoadPhase.BAR, RoadPlacementState.getRoadPhase());
        assertEquals(start, RoadPlacementState.getStartPos(), "startPos preserved across enterBar");
        assertEquals(end, RoadPlacementState.getEndPos(), "endPos preserved across enterBar");
        assertEquals(RoadPlacementState.ToolMode.FILL, RoadPlacementState.getActiveTool(),
                "tool preserved across enterBar (not reset to REPLACE)");

        RoadPlacementState.enterPlacing();
        assertEquals(RoadPlacementState.RoadPhase.PLACING, RoadPlacementState.getRoadPhase());
        assertEquals(start, RoadPlacementState.getStartPos(), "startPos preserved across enterPlacing");
        assertEquals(end, RoadPlacementState.getEndPos(), "endPos preserved across enterPlacing");
        assertEquals(RoadPlacementState.ToolMode.FILL, RoadPlacementState.getActiveTool(),
                "tool preserved across enterPlacing");
    }

    @Test
    @DisplayName("suspendProjection 保留位置/工具/参考块，仅落下 projecting")
    void suspendPreservesSelection() {
        RoadPlacementState.enterProjection();
        assertTrue(RoadPlacementState.isProjecting());

        BlockPos start = new BlockPos(1, 2, 3);
        RoadPlacementState.setStartPos(start);
        RoadPlacementState.setActiveTool(RoadPlacementState.ToolMode.DESTROY_FILL);
        RoadPlacementState.setRefBlockId("minecraft:stone");

        RoadPlacementState.suspendProjection();

        assertFalse(RoadPlacementState.isProjecting(), "projecting cleared on suspend");
        assertEquals(start, RoadPlacementState.getStartPos(), "startPos preserved on suspend");
        assertEquals(RoadPlacementState.ToolMode.DESTROY_FILL, RoadPlacementState.getActiveTool(),
                "tool preserved on suspend");
        assertEquals("minecraft:stone", RoadPlacementState.getRefBlockId(), "ref block preserved on suspend");
    }

    @Test
    @DisplayName("suspend → enterProjection 循环保留上次会话的选取")
    void suspendResumePreservesAcrossReentry() {
        RoadPlacementState.enterProjection();
        BlockPos start = new BlockPos(-5, 70, 42);
        RoadPlacementState.setStartPos(start);
        RoadPlacementState.setActiveTool(RoadPlacementState.ToolMode.SPLINE);

        RoadPlacementState.suspendProjection();
        RoadPlacementState.enterProjection();

        assertTrue(RoadPlacementState.isProjecting(), "re-entered projecting");
        assertEquals(RoadPlacementState.RoadPhase.BAR, RoadPlacementState.getRoadPhase());
        assertEquals(start, RoadPlacementState.getStartPos(), "startPos survived suspend/resume");
        assertEquals(RoadPlacementState.ToolMode.SPLINE, RoadPlacementState.getActiveTool(),
                "tool survived suspend/resume");
    }

    @Test
    @DisplayName("exitProjection 全清：位置/工具/参考块归零")
    void exitClearsAllSelection() {
        RoadPlacementState.enterProjection();
        RoadPlacementState.setStartPos(new BlockPos(7, 8, 9));
        RoadPlacementState.setEndPos(new BlockPos(10, 11, 12));
        RoadPlacementState.setActiveTool(RoadPlacementState.ToolMode.FILL);
        RoadPlacementState.setRefBlockId("minecraft:dirt");

        RoadPlacementState.exitProjection();

        assertFalse(RoadPlacementState.isProjecting());
        assertEquals(RoadPlacementState.RoadPhase.BAR, RoadPlacementState.getRoadPhase());
        assertEquals(RoadPlacementState.ToolMode.REPLACE, RoadPlacementState.getActiveTool(),
                "tool reset to REPLACE on exit");
        assertNull(RoadPlacementState.getStartPos(), "startPos cleared on exit");
        assertNull(RoadPlacementState.getEndPos(), "endPos cleared on exit");
        assertEquals("", RoadPlacementState.getRefBlockId(), "ref block cleared on exit");
    }

    @Test
    @DisplayName("clearAll 仅清 start/end，不动工具（供提交/撤销使用）")
    void clearAllClearsPositionsOnly() {
        RoadPlacementState.setStartPos(new BlockPos(1, 2, 3));
        RoadPlacementState.setEndPos(new BlockPos(4, 5, 6));
        RoadPlacementState.setActiveTool(RoadPlacementState.ToolMode.FILL);

        RoadPlacementState.clearAll();

        assertNull(RoadPlacementState.getStartPos(), "startPos cleared");
        assertNull(RoadPlacementState.getEndPos(), "endPos cleared");
        assertEquals(RoadPlacementState.ToolMode.FILL, RoadPlacementState.getActiveTool(),
                "clearAll does not reset tool");
    }

    @Test
    @DisplayName("相位在 enterProjection/enterPlacing/enterBar 间正确翻转")
    void phaseTransitions() {
        RoadPlacementState.enterProjection();
        assertEquals(RoadPlacementState.RoadPhase.BAR, RoadPlacementState.getRoadPhase());

        RoadPlacementState.enterPlacing();
        assertEquals(RoadPlacementState.RoadPhase.PLACING, RoadPlacementState.getRoadPhase());

        RoadPlacementState.enterBar();
        assertEquals(RoadPlacementState.RoadPhase.BAR, RoadPlacementState.getRoadPhase());
    }
}
