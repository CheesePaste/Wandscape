package com.wsteam.wandscape.shared.data;

/**
 * Priority tiers for maintenance fee payment during daily settlement.
 * Buildings in higher tiers are paid before those in lower tiers.
 */
public enum MaintenancePriority {
    CRITICAL,  // node, basic, storage — core infrastructure
    HIGH,      // workstation, crafting_station, potion_station — production
    NORMAL,    // shop, tavern — commerce
    LOW        // service, decoration, wonder — enhancement
}
