package com.wsteam.wandscape.shared.api;

import java.util.List;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.types.AttributeModifier;

import net.minecraft.world.item.ItemStack;
public interface WandApi {
    String getWandColor(ItemStack wand);

    /** 读取物品 CUSTOM_DATA 里绑定的法杖 preset id；默认杖/未绑定返回 null。 */
    @Nullable
    String getWandPresetId(ItemStack stack);

    /** 法杖 preset id → 属性修饰符列表；未知 id 返回 null（调用方回退默认杖）。 */
    @Nullable
    List<AttributeModifier> getWandModifiers(String presetId);
}
