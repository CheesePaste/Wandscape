package com.wsteam.wandscape.shared.event;

import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.Event;

import java.util.UUID;

/**
 * Fired on the NeoForge bus when a raid starts against a colony.
 *
 * <p>Trigger: a player carrying Bad Omen (RAID_OMEN/BAD_OMEN) approaches within
 * {@code raid.triggerRange} of a building. The raid center is the
 * colony's town hall. Carries enough data for the achievement system to react
 * (colony, wave count, omen level).
 */
public class ColonyRaidStartedEvent extends Event {
    private final UUID colonyId;
    private final int raidId;
    private final BlockPos center;
    private final int omenLevel;
    private final int numGroups;

    public ColonyRaidStartedEvent(UUID colonyId, int raidId, BlockPos center, int omenLevel, int numGroups) {
        this.colonyId = colonyId;
        this.raidId = raidId;
        this.center = center;
        this.omenLevel = omenLevel;
        this.numGroups = numGroups;
    }

    public UUID getColonyId() { return colonyId; }
    public int getRaidId() { return raidId; }
    public BlockPos getCenter() { return center; }
    public int getOmenLevel() { return omenLevel; }
    public int getNumGroups() { return numGroups; }
}
