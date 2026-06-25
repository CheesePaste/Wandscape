package com.wsteam.wandscape.core.task;

/**
 * States a wand can be in within a colony's lifecycle.
 *
 * <pre>
 * IN_WAREHOUSE → RESERVED → IN_TRANSIT_TO_NPC → EQUIPPED
 *                                                  │
 *                                                  ▼
 * IN_WAREHOUSE ← IN_TRANSIT_TO_WAREHOUSE ←─────────┘
 * </pre>
 */
public enum WandLifecycleState {
    /** Wand is in the warehouse, available for reservation. */
    IN_WAREHOUSE,
    /** Wand is reserved for an NPC but not yet picked up (prevents double-assignment). */
    RESERVED,
    /** Wand is being transported from warehouse to NPC. */
    IN_TRANSIT_TO_NPC,
    /** Wand is equipped on an NPC and in active use. */
    EQUIPPED,
    /** Wand is being transported from NPC back to warehouse. */
    IN_TRANSIT_TO_WAREHOUSE,
}
