package com.wsteam.wandscape.wand.internal;

import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
        CompoundTag nbt
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

            CompoundTag nbt = new CompoundTag();
            nbt.putString("wand_color", defaultColor);

            if (obj.has("behaviors")) {
                CompoundTag behaviors = new CompoundTag();
                JsonObject btObj = obj.getAsJsonObject("behaviors");
                for (var entry : btObj.entrySet()) {
                    behaviors.putInt(entry.getKey(), entry.getValue().getAsInt());
                }
                nbt.put("behaviors", behaviors);
            }

            if (obj.has("range")) {
                nbt.putInt("range", obj.get("range").getAsInt());
            }
            if (obj.has("mana_cost_multiplier")) {
                nbt.putFloat("mana_cost_multiplier", obj.get("mana_cost_multiplier").getAsFloat());
            }

            return new WandPreset(id, displayName, defaultColor, nbt);
        }
    }
}
