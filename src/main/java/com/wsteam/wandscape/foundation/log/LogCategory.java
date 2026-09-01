package com.wsteam.wandscape.foundation.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Functional domain categories for Wandscape logging.
 * Aligns with the 11 content domains, foundation infrastructure, compat, and bootstrap lifecycles.
 */
public enum LogCategory {
    COLONY("colony", "Colony lifecycle, activation, raids and metrics"),
    BUILDING("building", "Building placement, structure scanning, blueprints and previews"),
    NPC("npc", "NPC AI, attributes, navigation and recruitment"),
    TASK("task", "Task pool, scheduling, assignment and execution"),
    WAREHOUSE("warehouse", "Warehouse storage, item bank and pipeline transport"),
    ROAD("road", "Road network, spline editor and road placement"),
    MAGIC("magic", "Spellcasting, altars and ritual circles"),
    TOURIST("tourist", "Tourist spawning, simulation, taverns and hotel economy"),
    ELEMENT("element", "Element mappings, conversions and element items"),
    PRODUCTION("production", "Workshop crafting, recipe resolution and production queue"),
    ITEMS("items", "Wands, scepters, rings, compass and guidebook"),
    UI("ui", "Screen management, overlays and GUI state"),
    NETWORK("network", "Network packets and payload synchronization"),
    COMPAT("compat", "Third-party mod integrations (Curios, Iron's Spells, JEI)"),
    BOOTSTRAP("bootstrap", "Mod initialization, engine bootstrap and lifecycle"),
    GENERAL("general", "General and uncategorized mod logs");

    private final String id;
    private final String description;
    private final String channelName;
    private final Logger logger;

    LogCategory(String id, String description) {
        this.id = id;
        this.description = description;
        this.channelName = "wandscape." + id;
        this.logger = LoggerFactory.getLogger(this.channelName);
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public String getChannelName() {
        return channelName;
    }

    public Logger getLogger() {
        return logger;
    }

    public static LogCategory fromId(String id) {
        if (id == null) return GENERAL;
        String clean = id.trim().toLowerCase();
        for (LogCategory cat : values()) {
            if (cat.id.equals(clean)) return cat;
        }
        return GENERAL;
    }
}
