package com.wsteam.wandscape.projection.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProjectionClientState}'s pure selection-preservation logic.
 *
 * <p>The full enter/suspend/exit lifecycle drives the MC client runtime (Minecraft,
 * SoundService) and is left to integration tests. Here we only cover the extractable
 * pure helper {@link ProjectionClientState#clampSlotIndex(int, int)}, which embodies the
 * "keep the slot selection if still valid, otherwise reset" rule.
 */
@DisplayName("ProjectionClientState.clampSlotIndex")
class ProjectionClientStateTest {

    @Test
    @DisplayName("范围内的索引保持不变")
    void inRangeIndexPreserved() {
        assertEquals(0, ProjectionClientState.clampSlotIndex(0, 5));
        assertEquals(2, ProjectionClientState.clampSlotIndex(2, 5));
        assertEquals(4, ProjectionClientState.clampSlotIndex(4, 5)); // last valid slot
    }

    @Test
    @DisplayName("负索引回到 0")
    void negativeIndexFoldsToZero() {
        assertEquals(0, ProjectionClientState.clampSlotIndex(-1, 5));
        assertEquals(0, ProjectionClientState.clampSlotIndex(-100, 5));
    }

    @Test
    @DisplayName("越界（>= size）回到 0")
    void outOfHighIndexFoldsToZero() {
        assertEquals(0, ProjectionClientState.clampSlotIndex(5, 5)); // == size, out of range
        assertEquals(0, ProjectionClientState.clampSlotIndex(10, 3));
    }

    @Test
    @DisplayName("空列表（size==0）任何索引都回到 0")
    void emptyListFoldsToZero() {
        assertEquals(0, ProjectionClientState.clampSlotIndex(0, 0));
        assertEquals(0, ProjectionClientState.clampSlotIndex(3, 0));
        assertEquals(0, ProjectionClientState.clampSlotIndex(-1, 0));
    }
}
