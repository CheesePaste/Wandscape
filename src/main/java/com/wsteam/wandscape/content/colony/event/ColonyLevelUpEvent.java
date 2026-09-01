package com.wsteam.wandscape.content.colony.event;

import java.util.UUID;

/** Fired when a colony levels up after gaining enough experience. */
public record ColonyLevelUpEvent(UUID colonyId, int oldLevel, int newLevel, int overflowExp) {
}
