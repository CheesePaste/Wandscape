package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.Event;

/** Fired after a colony is registered via {@code /wandscape colony create}. */
public class ColonyCreatedEvent extends Event {
    private final UUID colonyId;
    private final BlockPos origin;

    public ColonyCreatedEvent(UUID colonyId, BlockPos origin) {
        this.colonyId = colonyId;
        this.origin = origin;
    }

    public UUID getColonyId() { return colonyId; }
    public BlockPos getOrigin() { return origin; }
}
