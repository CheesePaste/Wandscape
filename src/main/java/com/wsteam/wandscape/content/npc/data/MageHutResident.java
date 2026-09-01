package com.wsteam.wandscape.content.npc.data;

import com.wsteam.wandscape.content.npc.types.AttributeType;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.UUID;

/**
 * Persistent record of the single mage assigned to a Mage Hut.
 *
 * <p>Stored in {@code BuildingSavedData} (per building), it survives the mage's
 * death — the hut keeps its {@code level}/{@code base} so it can display the
 * occupant (dead or alive), continue training/level-up math, and re-apply the
 * progression when the mage is revived.
 *
 * <p>{@code base} is the per-attribute current base (after training), indexed by
 * {@link AttributeType#ordinal()}. Effective = base + perLevel×(level−1) +
 * equipBonus (see {@link MageHutAttributes}).
 */
public record MageHutResident(
        @Nullable UUID npcId,
        UUID colonyId,
        String mageName,
        int level,
        float[] base
) {
    public MageHutResident {
        base = Arrays.copyOf(base, AttributeType.values().length);
        if (level < 1) level = 1;
    }

    /** Current base for one attribute. */
    public float base(AttributeType type) {
        return base[type.ordinal()];
    }

    /** A copy with the given attribute's base replaced. */
    public MageHutResident withBase(AttributeType type, float value) {
        float[] next = Arrays.copyOf(base, base.length);
        next[type.ordinal()] = value;
        return new MageHutResident(npcId, colonyId, mageName, level, next);
    }

    /** A copy with a new level. */
    public MageHutResident withLevel(int newLevel) {
        return new MageHutResident(npcId, colonyId, mageName, newLevel, base);
    }

    /** A copy with a new mage npc id (e.g. after a revive re-binds a fresh entity). */
    public MageHutResident withNpcId(@Nullable UUID newNpcId) {
        return new MageHutResident(newNpcId, colonyId, mageName, level, base);
    }
}
