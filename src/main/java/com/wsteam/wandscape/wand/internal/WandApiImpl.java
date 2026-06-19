package com.wsteam.wandscape.wand.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.shared.api.WandApi;
import com.wsteam.wandscape.shared.data.AbilitySet;
import com.wsteam.wandscape.shared.data.BehaviorType;
import com.wsteam.wandscape.shared.data.WandBehaviorData;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public class WandApiImpl implements WandApi {

    private static final String TAG_COLOR = "wand_color";
    private static final String TAG_BEHAVIORS = "behaviors";
    private static final String TAG_RANGE = "range";
    private static final String TAG_MANA_COST = "mana_cost_multiplier";

    @Override
    public AbilitySet computeAbilities(List<ItemStack> wands) {
        Map<BehaviorType, Integer> result = new HashMap<>();
        for (ItemStack wand : wands) {
            CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
            if (customData == null) continue;
            CompoundTag tag = customData.copyTag();
            CompoundTag behaviors = tag.getCompound(TAG_BEHAVIORS);
            for (String key : behaviors.getAllKeys()) {
                BehaviorType type = BehaviorType.fromId(key);
                if (type == null) continue;
                int level = behaviors.getInt(key);
                result.merge(type, level, Math::max);
            }
        }
        return new AbilitySet(result);
    }

    @Override
    public WandBehaviorData getBehaviorData(ItemStack wand) {
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return new WandBehaviorDataImpl(
                "#FFFFFF", Map.of(),
                WandscapeConstants.DEFAULT_WAND_RANGE,
                WandscapeConstants.DEFAULT_MANA_COST_MULTIPLIER
            );
        }
        CompoundTag tag = customData.copyTag();
        String color = tag.getString(TAG_COLOR);
        if (color.isEmpty()) color = "#FFFFFF";

        Map<BehaviorType, Integer> behaviors = new HashMap<>();
        CompoundTag btTag = tag.getCompound(TAG_BEHAVIORS);
        for (String key : btTag.getAllKeys()) {
            BehaviorType type = BehaviorType.fromId(key);
            if (type != null) {
                behaviors.put(type, btTag.getInt(key));
            }
        }

        int range = tag.contains(TAG_RANGE) ? tag.getInt(TAG_RANGE) : WandscapeConstants.DEFAULT_WAND_RANGE;
        float manaMult = tag.contains(TAG_MANA_COST)
            ? tag.getFloat(TAG_MANA_COST) : WandscapeConstants.DEFAULT_MANA_COST_MULTIPLIER;

        return new WandBehaviorDataImpl(color, behaviors, range, manaMult);
    }

    @Override
    public int getBehaviorLevel(ItemStack wand, BehaviorType type) {
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return 0;
        CompoundTag behaviors = customData.copyTag().getCompound(TAG_BEHAVIORS);
        return behaviors.getInt(type.getId());
    }

    @Override
    public String getWandColor(ItemStack wand) {
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return "#FFFFFF";
        String color = customData.copyTag().getString(TAG_COLOR);
        return color.isEmpty() ? "#FFFFFF" : color;
    }

    @Override
    public float getManaCostMultiplier(ItemStack wand) {
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.contains(TAG_MANA_COST)) {
            return WandscapeConstants.DEFAULT_MANA_COST_MULTIPLIER;
        }
        return customData.copyTag().getFloat(TAG_MANA_COST);
    }

    @Override
    public int getRange(ItemStack wand) {
        CustomData customData = wand.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.contains(TAG_RANGE)) {
            return WandscapeConstants.DEFAULT_WAND_RANGE;
        }
        return customData.copyTag().getInt(TAG_RANGE);
    }
}
