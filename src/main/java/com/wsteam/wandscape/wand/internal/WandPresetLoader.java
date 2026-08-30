package com.wsteam.wandscape.wand.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.types.AttributeModifier;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.ModifierOperation;
import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.engine.attribute.WandscapeAttributes;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        public ItemAttributeModifiers itemAttributeModifiers() {
            return buildItemAttributeModifiers(id, attributes);
        }

        public static ItemAttributeModifiers buildItemAttributeModifiers(String id, List<AttributeModifier> attributes) {
            if (attributes == null || attributes.isEmpty()) {
                return ItemAttributeModifiers.EMPTY;
            }
            ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
            for (AttributeModifier mod : attributes) {
                Holder<Attribute> vanillaAttr = WandscapeAttributes.toVanilla(mod.type());
                if (vanillaAttr == null) continue;
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation op =
                        switch (mod.operation()) {
                            case ADDITION -> net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE;
                            case MULTIPLY_BASE -> net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
                        };
                ResourceLocation modId = ResourceLocation.fromNamespaceAndPath(
                        Wandscape.MODID, "wand_" + id + "_" + mod.type().name().toLowerCase(Locale.ROOT));
                builder.add(vanillaAttr,
                        new net.minecraft.world.entity.ai.attributes.AttributeModifier(modId, mod.amount(), op),
                        EquipmentSlotGroup.MAINHAND);
            }
            return builder.build();
        }

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
                            attrObj.get("type").getAsString().toUpperCase(Locale.ROOT));
                    ModifierOperation op = ModifierOperation.valueOf(
                            attrObj.get("operation").getAsString().toUpperCase(Locale.ROOT));
                    float amount = attrObj.get("amount").getAsFloat();
                    attributes.add(new AttributeModifier(type, amount, op));
                }
            }

            return new WandPreset(id, displayName, defaultColor, nbt, attributes);
        }
    }
}
