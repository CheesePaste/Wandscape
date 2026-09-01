package com.wsteam.wandscape.content.colony.event;

import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.Event;

import java.util.UUID;
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
