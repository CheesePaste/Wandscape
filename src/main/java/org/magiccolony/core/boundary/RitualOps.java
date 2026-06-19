package org.magiccolony.core.boundary;

import org.magiccolony.core.ecs.World;
import org.magiccolony.core.op.OpResult;
import org.magiccolony.core.types.GridPos;
import org.magiccolony.core.types.RitualId;

/**
 * Core-layer boundary for ritual execution.
 * Rituals are polled each tick: WAITING while in progress, DONE when complete.
 */
public interface RitualOps {

    /** Begin a ritual. Called once when the RitualOp starts. */
    void beginRitual(RitualId ritual, GridPos target, World world, long casterId);

    /** Poll a ritual that is in progress. Returns WAITING or DONE. */
    OpResult pollRitual(RitualId ritual, GridPos target, World world, long casterId);
}
