package com.wsteam.wandscape.content.production.event;

import net.neoforged.bus.api.Event;

import java.util.UUID;

/**
 * Fired on the NeoForge event bus when a production recipe is permanently
 * unlocked for a colony.
 */
public class RecipeUnlockedEvent extends Event {
    private final UUID colonyId;
    private final String recipeId;
    private final String source;

    public RecipeUnlockedEvent(UUID colonyId, String recipeId, String source) {
        this.colonyId = colonyId;
        this.recipeId = recipeId;
        this.source = source;
    }

    public UUID getColonyId() {
        return colonyId;
    }

    public String getRecipeId() {
        return recipeId;
    }

    public String getSource() {
        return source;
    }
}
