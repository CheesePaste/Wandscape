package com.wsteam.wandscape.compat.goety;

import com.Polarice3.Goety.api.items.magic.IFocus;
import com.Polarice3.Goety.api.magic.ISpell;
import com.Polarice3.Goety.common.enchantments.ModEnchantments;
import com.Polarice3.Goety.common.items.ModItems;
import com.Polarice3.Goety.common.magic.SpellStat;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.data.SpellConditions;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * 诡厄巫法辅助工具类：聚晶识别、法术解析、组件附魔序列化与动态 MagicDef 构造。
 */
public final class GoetyHelper {

    private static final String TAG = "GoetyHelper";

    private GoetyHelper() {}

    /** 检查物品堆是否为诡厄巫法的聚晶（IFocus 或 isFocus）。 */
    public static boolean isFocus(ItemStack stack) {
        if (!GoetyCompat.isLoaded() || stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        return item instanceof IFocus || ModItems.isFocus(item);
    }

    /** 从聚晶物品堆中提取注册 ID（如 goety:fang_focus）。 */
    @Nullable
    public static String getFocusId(ItemStack stack) {
        if (!isFocus(stack)) return null;
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null ? key.toString() : null;
    }

    /** 从注册 ID 或物品堆中获取对应的 ISpell 实例。 */
    @Nullable
    public static ISpell getSpell(String focusId) {
        if (!GoetyCompat.isLoaded() || focusId == null || focusId.isBlank()) return null;
        ResourceLocation loc = ResourceLocation.tryParse(focusId);
        if (loc == null) return null;
        Item item = BuiltInRegistries.ITEM.get(loc);
        if (item instanceof IFocus focus) {
            return focus.getSpell();
        }
        return null;
    }

    /** 是否为有效的诡厄巫法聚晶法术 ID。 */
    public static boolean isValidSpell(String focusId) {
        return getSpell(focusId) != null;
    }

    /**
     * 将聚晶 ItemStack 的完整数据（含附魔、自定义组件等）序列化为 SNBT 字符串存入 customData。
     */
    public static String serializeFocus(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                Tag tag = stack.saveOptional(server.registryAccess());
                return tag.toString();
            }
        } catch (Exception e) {
            Log.warn(TAG, "Failed to serialize focus stack: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 根据聚晶 ID 与 customData 还原完整聚晶 ItemStack（保留附魔与所有物品组件）。
     */
    public static ItemStack deserializeFocus(String focusId, @Nullable String customData) {
        if (focusId == null || focusId.isBlank()) return ItemStack.EMPTY;
        if (customData != null && !customData.isBlank()) {
            try {
                CompoundTag tag = TagParser.parseTag(customData);
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    ItemStack parsed = ItemStack.parseOptional(server.registryAccess(), tag);
                    if (!parsed.isEmpty()) {
                        return parsed;
                    }
                }
            } catch (Exception e) {
                Log.warn(TAG, "Failed to deserialize focus data for {}: {}", focusId, e.getMessage());
            }
        }
        ResourceLocation loc = ResourceLocation.tryParse(focusId);
        if (loc != null) {
            Item item = BuiltInRegistries.ITEM.get(loc);
            if (item != null) {
                return new ItemStack(item);
            }
        }
        return ItemStack.EMPTY;
    }

    /** 获取聚晶法术的显示名称。 */
    public static Component getSpellDisplayName(String focusId, @Nullable String customData) {
        ItemStack stack = deserializeFocus(focusId, customData);
        if (!stack.isEmpty()) {
            return stack.getHoverName();
        }
        return Component.literal(focusId);
    }

    /**
     * 根据聚晶法术与装备所在的策略组，构造动态合成的 {@link MagicDef}。
     * 对齐铁魔法设计：由策略槽分类（aoe/defense/support/single_target）覆盖目标与施法门控。
     */
    @Nullable
    public static MagicDef getSyntheticDef(String focusId, String categoryName, @Nullable String customData) {
        if (!isValidSpell(focusId)) return null;
        ISpell spell = getSpell(focusId);
        if (spell == null) return null;

        // 灵魂消耗换算为魔力消耗（支持 Config 比例系数配置）
        int rawSoul = spell.defaultSoulCost();
        double soulRatio = Config.GOETY_SOUL_TO_MANA_MULTIPLIER.get();
        int manaCost = Math.max(1, (int) Math.round(rawSoul * soulRatio));

        // 冷却换算（tick，支持 Config 比例系数配置；SPELL_SPEED 在 MagicState 进一步缩短）
        int rawCooldown = spell.defaultSpellCooldown();
        double cdRatio = Config.GOETY_COOLDOWN_MULTIPLIER.get();
        int baseCooldown = Math.max(10, (int) Math.round(rawCooldown * cdRatio));

        int castTime = Math.max(0, spell.defaultCastDuration());
        double range = 32.0;

        MagicDef.TargetMode targetMode;
        SpellConditions conditions;

        switch (categoryName == null ? "" : categoryName) {
            case "aoe" -> {
                targetMode = MagicDef.TargetMode.HOSTILE_NEAREST;
                conditions = SpellConditions.NONE;
            }
            case "defense" -> {
                targetMode = MagicDef.TargetMode.SELF;
                conditions = new SpellConditions(0.8f, null, null);
            }
            case "support" -> {
                targetMode = MagicDef.TargetMode.ALLY_LOWEST_HP;
                conditions = new SpellConditions(null, 0.8f, null);
            }
            default -> {
                targetMode = MagicDef.TargetMode.HOSTILE_NEAREST;
                conditions = SpellConditions.NONE;
            }
        }

        return new MagicDef(
                focusId,
                MagicDef.Category.NORMAL,
                manaCost,
                baseCooldown,
                castTime,
                range,
                targetMode,
                null,
                null,
                null,
                false,
                0,
                0,
                conditions,
                null,
                null
        );
    }

    /**
     * 根据聚晶物品堆上所附魔的 Goety 附魔等级，强化并返回 SpellStat。
     */
    public static SpellStat buildSpellStat(ServerLevel level, ISpell spell, ItemStack focusStack) {
        SpellStat stat = spell.defaultStats();
        if (stat == null) {
            stat = new SpellStat(0, 0, 0, 0.0, 0, 0.0f);
        }
        if (focusStack == null || focusStack.isEmpty()) {
            return stat;
        }

        int potency = getEnchantmentLevel(level, focusStack, ModEnchantments.POTENCY);
        int range = getEnchantmentLevel(level, focusStack, ModEnchantments.RANGE);
        int duration = getEnchantmentLevel(level, focusStack, ModEnchantments.DURATION);
        int radius = getEnchantmentLevel(level, focusStack, ModEnchantments.RADIUS);
        int burning = getEnchantmentLevel(level, focusStack, ModEnchantments.BURNING);
        int velocity = getEnchantmentLevel(level, focusStack, ModEnchantments.VELOCITY);

        if (potency > 0) stat.increasePotency(potency * 2);
        if (range > 0) stat.increaseRange(range * 2);
        if (duration > 0) stat.increaseDuration(duration * 20);
        if (radius > 0) stat.increaseRadius(radius * 0.5);
        if (burning > 0) stat.increaseBurning(burning);
        if (velocity > 0) stat.increaseVelocity(velocity * 0.2f);

        return stat;
    }

    private static int getEnchantmentLevel(ServerLevel level, ItemStack stack, ResourceKey<Enchantment> key) {
        Optional<Holder.Reference<Enchantment>> holder =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key);
        if (holder.isEmpty()) return 0;
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        return enchantments.getLevel(holder.get());
    }
}
