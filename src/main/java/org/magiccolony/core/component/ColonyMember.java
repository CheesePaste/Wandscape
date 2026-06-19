package org.magiccolony.core.component;

import java.util.UUID;

/** Marks an NPC entity as belonging to a colony. */
public record ColonyMember(UUID colonyId) {}
