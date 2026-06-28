package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.bus.api.Event;
public class NpcDiedEvent extends Event {
    private final UUID npcId;
    private final BlockPos deathPos;
    private final CompoundTag graveData;

    public NpcDiedEvent(UUID npcId, BlockPos deathPos, CompoundTag graveData) {
        this.npcId = npcId;
        this.deathPos = deathPos;
        this.graveData = graveData;
    }

    public UUID getNpcId() { return npcId; }
    public BlockPos getDeathPos() { return deathPos; }
    public CompoundTag getGraveData() { return graveData; }
}
