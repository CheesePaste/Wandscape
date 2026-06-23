package com.wsteam.wandscape.shared.event;

import java.util.UUID;

import net.neoforged.bus.api.Event;

/**
 * Fired when any of the three colony evaluation values (comfort / magic / wonder)
 * changes for a given colony.
 *
 * <p>Subscribers can use the delta fields to determine whether the change is
 * an increase or a decrease, and react accordingly (e.g. unlock buildings,
 * adjust tavern task modifiers, reveal new wand upgrades).
 */
public class ColonyEvaluationChangedEvent extends Event {
    private final UUID colonyId;
    private final int oldComfort;
    private final int newComfort;
    private final int oldMagic;
    private final int newMagic;
    private final int oldWonder;
    private final int newWonder;

    public ColonyEvaluationChangedEvent(
            UUID colonyId,
            int oldComfort, int newComfort,
            int oldMagic, int newMagic,
            int oldWonder, int newWonder
    ) {
        this.colonyId = colonyId;
        this.oldComfort = oldComfort;
        this.newComfort = newComfort;
        this.oldMagic = oldMagic;
        this.newMagic = newMagic;
        this.oldWonder = oldWonder;
        this.newWonder = newWonder;
    }

    public UUID getColonyId() { return colonyId; }

    public int getOldComfort() { return oldComfort; }
    public int getNewComfort() { return newComfort; }
    public int getOldMagic()   { return oldMagic;   }
    public int getNewMagic()   { return newMagic;   }
    public int getOldWonder()  { return oldWonder;  }
    public int getNewWonder()  { return newWonder;  }

    /** True when at least one of the three values actually changed. */
    public boolean hasChanged() {
        return oldComfort != newComfort
            || oldMagic   != newMagic
            || oldWonder  != newWonder;
    }
}
