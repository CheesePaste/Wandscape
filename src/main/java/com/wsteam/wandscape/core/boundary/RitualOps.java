package com.wsteam.wandscape.core.boundary;

import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.OpResult;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.RitualId;

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
