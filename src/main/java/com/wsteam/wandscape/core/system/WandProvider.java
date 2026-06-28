package com.wsteam.wandscape.core.system;

import com.wsteam.wandscape.core.types.BehaviourLevel;
import com.wsteam.wandscape.core.types.BehaviourTag;

import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
/**
 * Boundary interface for querying the colony warehouse for wands
 * that satisfy given capability requirements.
 *
 * <p>Defined in core/ to keep the scheduler decoupled from MC.
 * The engine layer wires the real implementation via
 * {@link SchedulerSystem#SchedulerSystem(WandProvider)}.
 */
@FunctionalInterface
public interface WandProvider {

    /**
     * Find a wand item ID in the colony warehouse that satisfies
     * ALL given requirements. Returns {@code null} if no matching
     * wand is available.
     *
     * @param reqs    required capability levels (e.g. {@code {GATHERING: 1}})
     * @param colonyId the colony whose warehouse to search
     * @return a wand item ID like {@code "wandscape:gatherer_wand"}, or null
     */
    @Nullable
    String findWand(Map<BehaviourTag, BehaviourLevel> reqs, UUID colonyId);
}
