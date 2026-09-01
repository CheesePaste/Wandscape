package com.wsteam.wandscape.wand.internal;

import com.google.gson.JsonParser;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.content.items.wand.internal.WandPresetLoader.WandPreset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WandPresetLoaderTest {

    @Test
    void fromJson_validMinimal_parsesCoreFields() {
        String json = """
            {
              "type": "wand",
              "display_name": "Test Wand",
              "wand_color": "#123456"
            }""";
        WandPreset preset = WandPreset.fromJson("test", JsonParser.parseString(json));
        assertEquals("test", preset.id());
        assertEquals("Test Wand", preset.displayName());
        assertEquals("#123456", preset.defaultColor());
        assertEquals("test", preset.nbt().getString("preset_id"));
        assertEquals("#123456", preset.nbt().getString("wand_color"));
    }

    @Test
    void fromJson_validFull_parsesAttributes() {
        String json = """
            {
              "type": "wand",
              "display_name": "Full Wand",
              "wand_color": "#ABCDEF",
              "attributes": [
                { "type": "spell_power", "operation": "addition", "amount": 1 },
                { "type": "work_speed", "operation": "addition", "amount": 0.5 }
              ]
            }""";
        WandPreset preset = WandPreset.fromJson("full", JsonParser.parseString(json));
        assertEquals(2, preset.attributes().size());
        assertEquals("spell_power", preset.attributes().get(0).type().name().toLowerCase());
        assertEquals(0.5f, preset.attributes().get(1).amount(), 0.001f);
    }

    @Test
    void fromJson_missingDisplayName_fallsBackToId() {
        String json = """
            {
              "type": "wand",
              "wand_color": "#FFFFFF"
            }""";
        WandPreset preset = WandPreset.fromJson("my_id", JsonParser.parseString(json));
        assertEquals("my_id", preset.displayName());
    }

    @Test
    void fromJson_missingColor_defaultsToWhite() {
        String json = """
            {
              "type": "wand",
              "display_name": "No Color"
            }""";
        WandPreset preset = WandPreset.fromJson("nocolor", JsonParser.parseString(json));
        assertEquals("#FFFFFF", preset.defaultColor());
    }

    @Test
    void fromJson_noAttributes_returnsEmptyList() {
        String json = """
            {
              "type": "wand",
              "display_name": "No Attrs",
              "wand_color": "#000000"
            }""";
        WandPreset preset = WandPreset.fromJson("noattrs", JsonParser.parseString(json));
        assertTrue(preset.attributes().isEmpty());
    }

    @Test
    void fromJson_wandColorInNbt() {
        String json = """
            {
              "type": "wand",
              "display_name": "Color Check",
              "wand_color": "#FF00FF"
            }""";
        WandPreset preset = WandPreset.fromJson("color", JsonParser.parseString(json));
        assertEquals("#FF00FF", preset.nbt().getString("wand_color"));
    }

    @Test
    void fromJson_presetIdInNbt() {
        String json = """
            {
              "type": "wand",
              "display_name": "ID Check",
              "wand_color": "#000000"
            }""";
        WandPreset preset = WandPreset.fromJson("my_preset", JsonParser.parseString(json));
        assertEquals("my_preset", preset.nbt().getString("preset_id"));
    }

    @Test
    void fromJson_displayNameInRecord() {
        String json = """
            {
              "type": "wand",
              "display_name": "Builder Wand",
              "wand_color": "#00FF00"
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
              "wand_color": "#000000"
            }""";
        WandPreset preset = WandPreset.fromJson("potion", JsonParser.parseString(json));
        assertNull(preset);
    }

    @Test
    void fromJson_noType_defaultsToWand() {
        String json = """
            {
              "display_name": "Legacy Wand",
              "wand_color": "#AAAAAA"
            }""";
        WandPreset preset = WandPreset.fromJson("legacy", JsonParser.parseString(json));
        assertNotNull(preset);
        assertEquals("Legacy Wand", preset.displayName());
    }

    @Test
    void fromJson_tradeoffWand_parsesNegativeAmounts() {
        String json = """
            {
              "type": "wand",
              "display_name": "Bastion",
              "wand_color": "#4A4A52",
              "attributes": [
                { "type": "move_speed", "operation": "addition", "amount": -0.18 },
                { "type": "max_hp", "operation": "addition", "amount": 55 },
                { "type": "armor_value", "operation": "addition", "amount": 8 }
              ]
            }""";
        WandPreset preset = WandPreset.fromJson("bastion_wand", JsonParser.parseString(json));
        assertEquals(3, preset.attributes().size());
        assertEquals(AttributeType.MOVE_SPEED, preset.attributes().get(0).type());
        assertEquals(-0.18f, preset.attributes().get(0).amount(), 0.001f);
        assertEquals(AttributeType.MAX_HP, preset.attributes().get(1).type());
        assertEquals(55f, preset.attributes().get(1).amount(), 0.001f);
        assertEquals(AttributeType.ARMOR_VALUE, preset.attributes().get(2).type());
        assertEquals(8f, preset.attributes().get(2).amount(), 0.001f);
    }
}
