package com.wsteam.wandscape.shared.event;

import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.Event;

import java.util.UUID;

/**
 * Fired on the NeoForge bus when a raid against a colony is won (all raiders
 * killed, all waves cleared).
 *
 * <p>{@code groupsSpawned} is the number of waves actually fought (vanilla
 * scales it by difficulty 3/5/7 plus a bonus wave for omen level > 1).
 */
public class ColonyRaidVictoryEvent extends Event {
    private final UUID colonyId;
    private final int raidId;
    private final BlockPos center;
    private final int omenLevel;
    private final int groupsSpawned;

    public ColonyRaidVictoryEvent(UUID colonyId, int raidId, BlockPos center, int omenLevel, int groupsSpawned) {
        this.colonyId = colonyId;
        this.raidId = raidId;
        this.center = center;
        this.omenLevel = omenLevel;
        this.groupsSpawned = groupsSpawned;
    }

    public UUID getColonyId() { return colonyId; }
    public int getRaidId() { return raidId; }
    public BlockPos getCenter() { return center; }
    public int getOmenLevel() { return omenLevel; }
    public int getGroupsSpawned() { return groupsSpawned; }
}
