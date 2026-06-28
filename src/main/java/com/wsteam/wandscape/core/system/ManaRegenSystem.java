package com.wsteam.wandscape.core.system;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;

/**
 * Regenerates mana for all entities that have a ManaPool component.
 * Runs first in the tick order.
 */
public class ManaRegenSystem implements System {

    private static final String TAG = "ManaRegen";

    @Override
    public void update(World world, float delta) {
        int regened = 0;
        for (long entity : world.query(ManaPool.class)) {
            ManaPool pool = world.get(entity, ManaPool.class);
            if (pool != null && !pool.isFull()) {
                pool.regen();
                regened++;
            }
        }
        if (regened > 0) {
            Log.debug(TAG, "regen'd %d entities", regened);
        }
    }
}
