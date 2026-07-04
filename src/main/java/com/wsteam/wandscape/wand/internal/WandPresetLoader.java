package com.wsteam.wandscape.wand.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.core.types.AttributeModifier;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.ModifierOperation;
import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

import net.minecraft.nbt.CompoundTag;
public class WandPresetLoader {
    private static final String CATEGORY = "craft_recipes";

    private final WandscapeDataRegistry<WandPreset> registry;

    public WandPresetLoader(WandscapeDataLoader dataLoader) {
        this.registry = dataLoader.register(CATEGORY, WandPreset::fromJson);
    }

    public WandPreset getPreset(String id) {
        return registry.get(id);
    }

    public Map<String, WandPreset> getAllPresets() {
        return registry.getAll();
    }

    public record WandPreset(
        String id,
        String displayName,
        String defaultColor,
        CompoundTag nbt,
        List<AttributeModifier> attributes
    ) {
        static WandPreset fromJson(String id, JsonElement json) {
            JsonObject obj = json.getAsJsonObject();

            // Only process wand-type entries; skip potions and other types
            if (obj.has("type") && !"wand".equals(obj.get("type").getAsString())) {
                return null;
            }

            String displayName = obj.has("display_name")
                    ? obj.get("display_name").getAsString() : id;
            String defaultColor = obj.has("wand_color")
                    ? obj.get("wand_color").getAsString() : "#FFFFFF";

            // New NBT: only preset_id and wand_color
            CompoundTag nbt = new CompoundTag();
            nbt.putString("preset_id", id);
            nbt.putString("wand_color", defaultColor);

            // Parse attributes array
            List<AttributeModifier> attributes = new ArrayList<>();
            if (obj.has("attributes")) {
                JsonArray attrs = obj.getAsJsonArray("attributes");
                for (JsonElement attrEl : attrs) {
                    JsonObject attrObj = attrEl.getAsJsonObject();
                    AttributeType type = AttributeType.valueOf(
                            attrObj.get("type").getAsString().toUpperCase());
                    ModifierOperation op = ModifierOperation.valueOf(
                            attrObj.get("operation").getAsString().toUpperCase());
                    float amount = attrObj.get("amount").getAsFloat();
                    attributes.add(new AttributeModifier(type, amount, op));
                }
            }

            return new WandPreset(id, displayName, defaultColor, nbt, attributes);
        }
    }
}
