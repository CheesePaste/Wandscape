package com.wsteam.wandscape.wand.internal;

import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

import net.minecraft.nbt.CompoundTag;
public class WandPresetLoader {
    private static final String CATEGORY = "wands";

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
            String displayName = obj.get("display_name").getAsString();
            String defaultColor = obj.get("default_color").getAsString();

            CompoundTag nbt = new CompoundTag();
            nbt.putString("wand_color", defaultColor);

            CompoundTag behaviors = new CompoundTag();
            JsonObject btObj = obj.getAsJsonObject("behaviors");
            for (var entry : btObj.entrySet()) {
                behaviors.putInt(entry.getKey(), entry.getValue().getAsInt());
            }
            nbt.put("behaviors", behaviors);

            if (obj.has("default_range")) {
                nbt.putInt("range", obj.get("default_range").getAsInt());
            }
            if (obj.has("default_mana_cost_multiplier")) {
                nbt.putFloat("mana_cost_multiplier", obj.get("default_mana_cost_multiplier").getAsFloat());
            }

            return new WandPreset(id, displayName, defaultColor, nbt);
        }
    }
}
