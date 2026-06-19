package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.Event;

public class ColonyCreatedEvent extends Event {
    private final UUID colonyId;
    private final BlockPos townHallPos;

    public ColonyCreatedEvent(UUID colonyId, BlockPos townHallPos) {
        this.colonyId = colonyId;
        this.townHallPos = townHallPos;
    }

    public UUID getColonyId() { return colonyId; }
    public BlockPos getTownHallPos() { return townHallPos; }
}
