package com.wsteam.wandscape.shared.data;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
public sealed interface AtomicStep {
    record OperationA(
        BlockPos target,
        BlockState source,
        BlockState result,
        boolean produceDrops,
        Map<ElementType, Long> elementCost
    ) implements AtomicStep {}

    record OperationB(
        java.util.UUID buildingId,
        String action,
        Map<String, Object> params
    ) implements AtomicStep {}

    record OperationC(
        java.util.UUID targetEntityId,
        String effectId,
        int intensity,
        int durationTicks
    ) implements AtomicStep {}

    record OperationD(
        java.util.UUID buildingId,
        String ritualId,
        int channelTicks,
        boolean needsAltar,
        long manaCost,
        Map<ElementType, Long> elementCost
    ) implements AtomicStep {}
}
