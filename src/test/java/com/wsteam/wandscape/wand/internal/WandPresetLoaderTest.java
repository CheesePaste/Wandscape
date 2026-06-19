package com.wsteam.wandscape.wand.internal;

import com.google.gson.JsonParser;
import com.wsteam.wandscape.wand.internal.WandPresetLoader.WandPreset;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WandPresetLoaderTest {

    @Test
    void fromJson_validMinimal_parsesCoreFields() {
        String json = """
            {
              "display_name": "Test Wand",
              "default_color": "#123456",
              "behaviors": { "mining": 2 }
            }""";
        WandPreset preset = WandPreset.fromJson("test", JsonParser.parseString(json));
        assertEquals("test", preset.id());
        assertEquals("Test Wand", preset.displayName());
        assertEquals("#123456", preset.defaultColor());
        assertEquals("#123456", preset.nbt().getString("wand_color"));
        CompoundTag bt = preset.nbt().getCompound("behaviors");
        assertEquals(2, bt.getInt("mining"));
    }

    @Test
    void fromJson_validFull_allOptionalFields() {
        String json = """
            {
              "display_name": "Full Wand",
              "default_color": "#ABCDEF",
              "behaviors": { "crafting": 4 },
              "default_range": 3,
              "default_mana_cost_multiplier": 0.5
            }""";
        WandPreset preset = WandPreset.fromJson("full", JsonParser.parseString(json));
        assertEquals(3, preset.nbt().getInt("range"));
        assertEquals(0.5f, preset.nbt().getFloat("mana_cost_multiplier"), 0.001f);
    }

    @Test
    void fromJson_missingBehaviors_throws() {
        String json = """
            {
              "display_name": "Bad Wand",
              "default_color": "#FFFFFF"
            }""";
        assertThrows(Exception.class,
            () -> WandPreset.fromJson("bad", JsonParser.parseString(json)));
    }

    @Test
    void fromJson_missingDisplayName_throws() {
        String json = """
            {
              "default_color": "#FFFFFF",
              "behaviors": { "ritual": 1 }
            }""";
        assertThrows(Exception.class,
            () -> WandPreset.fromJson("bad", JsonParser.parseString(json)));
    }

    @Test
    void fromJson_missingDefaultColor_throws() {
        String json = """
            {
              "display_name": "Bad Wand",
              "behaviors": { "ritual": 1 }
            }""";
        assertThrows(Exception.class,
            () -> WandPreset.fromJson("bad", JsonParser.parseString(json)));
    }

    @Test
    void fromJson_optionalRangeAbsent_notInNbt() {
        String json = """
            {
              "display_name": "No Range",
              "default_color": "#000000",
              "behaviors": { "gathering": 1 }
            }""";
        WandPreset preset = WandPreset.fromJson("norange", JsonParser.parseString(json));
        assertFalse(preset.nbt().contains("range"));
    }

    @Test
    void fromJson_optionalManaCostAbsent_notInNbt() {
        String json = """
            {
              "display_name": "No Mana",
              "default_color": "#000000",
              "behaviors": { "gathering": 1 }
            }""";
        WandPreset preset = WandPreset.fromJson("nomana", JsonParser.parseString(json));
        assertFalse(preset.nbt().contains("mana_cost_multiplier"));
    }

    @Test
    void fromJson_behaviorsMultipleEntries_allInNbt() {
        String json = """
            {
              "display_name": "Multi",
              "default_color": "#000000",
              "behaviors": { "building": 5, "farming": 2, "mining": 1 }
            }""";
        WandPreset preset = WandPreset.fromJson("multi", JsonParser.parseString(json));
        CompoundTag bt = preset.nbt().getCompound("behaviors");
        assertEquals(5, bt.getInt("building"));
        assertEquals(2, bt.getInt("farming"));
        assertEquals(1, bt.getInt("mining"));
    }

    @Test
    void fromJson_wandColorInNbt() {
        String json = """
            {
              "display_name": "Color Check",
              "default_color": "#FF00FF",
              "behaviors": { "ritual": 2 }
            }""";
        WandPreset preset = WandPreset.fromJson("color", JsonParser.parseString(json));
        assertEquals("#FF00FF", preset.nbt().getString("wand_color"));
    }

    @Test
    void fromJson_displayNameInRecord() {
        String json = """
            {
              "display_name": "Builder Wand",
              "default_color": "#00FF00",
              "behaviors": { "building": 3 }
            }""";
        WandPreset preset = WandPreset.fromJson("builder", JsonParser.parseString(json));
        assertEquals("Builder Wand", preset.displayName());
    }
}
