package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;

public class NpcResurrectedEvent extends Event {
    private final UUID npcId;
    private final UUID altarId;

    public NpcResurrectedEvent(UUID npcId, UUID altarId) {
        this.npcId = npcId;
        this.altarId = altarId;
    }

    public UUID getNpcId() { return npcId; }
    public UUID getAltarId() { return altarId; }
}
