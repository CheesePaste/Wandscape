package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;
public class NpcRecruitedEvent extends Event {
    private final UUID npcId;
    private final UUID tavernId;

    public NpcRecruitedEvent(UUID npcId, UUID tavernId) {
        this.npcId = npcId;
        this.tavernId = tavernId;
    }

    public UUID getNpcId() { return npcId; }
    public UUID getTavernId() { return tavernId; }
}
