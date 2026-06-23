package com.wsteam.wandscape.production.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class RecipeUnlockRequirementTest {

    private static final Gson GSON = new GsonBuilder().create();

    @Test
    void fromJson_allThreeFields() {
        JsonObject obj = new JsonObject();
        obj.addProperty("min_comfort", 5);
        obj.addProperty("min_magic", 10);
        obj.addProperty("min_wonder", 3);

        RecipeUnlockRequirement req = RecipeUnlockRequirement.fromJson(obj);

        assertEquals(5, req.minComfort());
        assertEquals(10, req.minMagic());
        assertEquals(3, req.minWonder());
    }

    @Test
    void fromJson_onlyMagicSet() {
        JsonObject obj = new JsonObject();
        obj.addProperty("min_magic", 7);

        RecipeUnlockRequirement req = RecipeUnlockRequirement.fromJson(obj);

        assertEquals(0, req.minComfort());
        assertEquals(7, req.minMagic());
        assertEquals(0, req.minWonder());
    }

    @Test
    void fromJson_emptyObject_defaultsToZero() {
        JsonObject obj = new JsonObject();

        RecipeUnlockRequirement req = RecipeUnlockRequirement.fromJson(obj);

        assertEquals(0, req.minComfort());
        assertEquals(0, req.minMagic());
        assertEquals(0, req.minWonder());
        assertEquals(RecipeUnlockRequirement.NONE, req);
    }

    @Test
    void none_isAllZeros() {
        assertEquals(0, RecipeUnlockRequirement.NONE.minComfort());
        assertEquals(0, RecipeUnlockRequirement.NONE.minMagic());
        assertEquals(0, RecipeUnlockRequirement.NONE.minWonder());
    }
}
