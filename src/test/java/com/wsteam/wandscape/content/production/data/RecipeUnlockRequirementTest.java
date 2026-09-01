package com.wsteam.wandscape.production.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class RecipeUnlockRequirementTest {

    private static final Gson GSON = new GsonBuilder().create();

    @Test
    void fromJson_withLevel() {
        JsonObject obj = new JsonObject();
        obj.addProperty("min_colony_level", 3);

        RecipeUnlockRequirement req = RecipeUnlockRequirement.fromJson(obj);

        assertEquals(3, req.minColonyLevel());
    }

    @Test
    void fromJson_emptyObject_defaultsToOne() {
        JsonObject obj = new JsonObject();

        RecipeUnlockRequirement req = RecipeUnlockRequirement.fromJson(obj);

        assertEquals(1, req.minColonyLevel());
        assertEquals(RecipeUnlockRequirement.NONE, req);
    }

    @Test
    void none_isLevelOne() {
        assertEquals(1, RecipeUnlockRequirement.NONE.minColonyLevel());
    }
}
