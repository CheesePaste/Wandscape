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
              "type": "wand",
              "display_name": "Test Wand",
              "wand_color": "#123456",
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
              "type": "wand",
              "display_name": "Full Wand",
              "wand_color": "#ABCDEF",
              "behaviors": { "crafting": 4 },
              "range": 3,
              "mana_cost_multiplier": 0.5
            }""";
        WandPreset preset = WandPreset.fromJson("full", JsonParser.parseString(json));
        assertEquals(3, preset.nbt().getInt("range"));
        assertEquals(0.5f, preset.nbt().getFloat("mana_cost_multiplier"), 0.001f);
    }

    @Test
    void fromJson_missingDisplayName_fallsBackToId() {
        String json = """
            {
              "type": "wand",
              "wand_color": "#FFFFFF",
              "behaviors": { "ritual": 1 }
            }""";
        WandPreset preset = WandPreset.fromJson("my_id", JsonParser.parseString(json));
        assertEquals("my_id", preset.displayName());
    }

    @Test
    void fromJson_missingColor_defaultsToWhite() {
        String json = """
            {
              "type": "wand",
              "display_name": "No Color",
              "behaviors": { "ritual": 1 }
            }""";
        WandPreset preset = WandPreset.fromJson("nocolor", JsonParser.parseString(json));
        assertEquals("#FFFFFF", preset.defaultColor());
    }

    @Test
    void fromJson_optionalRangeAbsent_notInNbt() {
        String json = """
            {
              "type": "wand",
              "display_name": "No Range",
              "wand_color": "#000000",
              "behaviors": { "gathering": 1 }
            }""";
        WandPreset preset = WandPreset.fromJson("norange", JsonParser.parseString(json));
        assertFalse(preset.nbt().contains("range"));
    }

    @Test
    void fromJson_optionalManaCostAbsent_notInNbt() {
        String json = """
            {
              "type": "wand",
              "display_name": "No Mana",
              "wand_color": "#000000",
              "behaviors": { "gathering": 1 }
            }""";
        WandPreset preset = WandPreset.fromJson("nomana", JsonParser.parseString(json));
        assertFalse(preset.nbt().contains("mana_cost_multiplier"));
    }

    @Test
    void fromJson_behaviorsMultipleEntries_allInNbt() {
        String json = """
            {
              "type": "wand",
              "display_name": "Multi",
              "wand_color": "#000000",
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
              "type": "wand",
              "display_name": "Color Check",
              "wand_color": "#FF00FF",
              "behaviors": { "ritual": 2 }
            }""";
        WandPreset preset = WandPreset.fromJson("color", JsonParser.parseString(json));
        assertEquals("#FF00FF", preset.nbt().getString("wand_color"));
    }

    @Test
    void fromJson_displayNameInRecord() {
        String json = """
            {
              "type": "wand",
              "display_name": "Builder Wand",
              "wand_color": "#00FF00",
              "behaviors": { "building": 3 }
            }""";
        WandPreset preset = WandPreset.fromJson("builder", JsonParser.parseString(json));
        assertEquals("Builder Wand", preset.displayName());
    }

    @Test
    void fromJson_potionType_returnsNull() {
        String json = """
            {
              "type": "potion",
              "display_name": "Not A Wand",
              "wand_color": "#000000",
              "behaviors": { "ritual": 1 }
            }""";
        WandPreset preset = WandPreset.fromJson("potion", JsonParser.parseString(json));
        assertNull(preset);
    }

    @Test
    void fromJson_noType_defaultsToWand() {
        String json = """
            {
              "display_name": "Legacy Wand",
              "wand_color": "#AAAAAA",
              "behaviors": { "mining": 1 }
            }""";
        WandPreset preset = WandPreset.fromJson("legacy", JsonParser.parseString(json));
        assertNotNull(preset);
        assertEquals("Legacy Wand", preset.displayName());
    }
}
