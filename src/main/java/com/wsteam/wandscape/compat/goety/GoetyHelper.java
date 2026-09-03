package com.wsteam.wandscape.compat.goety;

import com.Polarice3.Goety.api.items.magic.IFocus;
import com.Polarice3.Goety.api.magic.IChargingSpell;
import com.Polarice3.Goety.api.magic.ISpell;
import com.Polarice3.Goety.api.magic.SpellType;
import com.Polarice3.Goety.common.enchantments.ModEnchantments;
import com.Polarice3.Goety.common.items.ModItems;
import com.Polarice3.Goety.common.magic.SpellStat;
import com.Polarice3.Goety.config.SpellConfig;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.data.SpellConditions;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
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
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;
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

        int castTime = (spell instanceof IChargingSpell charging)
                ? Math.min(60, Math.max(20, charging.defaultCastUp()))
                : 0;
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
     * 根据法术类型构造对应的 Goety 法杖/权杖，并将聚晶注入法杖容器中，
     * 以满足 rightStaff 校验并提供完整的法术派生强化效果。
     */
    @NotNull
    public static ItemStack getStaffForSpell(@Nullable ISpell spell, @Nullable ItemStack focusStack) {
        if (!GoetyCompat.isLoaded() || spell == null) return ItemStack.EMPTY;
        SpellType type = spell.getSpellType();
        ItemStack staff;
        if (type == null) {
            staff = new ItemStack(ModItems.DARK_WAND.get());
        } else {
            staff = switch (type) {
                case ILL -> new ItemStack(ModItems.OMINOUS_STAFF.get());
                case NECROMANCY -> new ItemStack(ModItems.NECRO_STAFF.get());
                case GEOMANCY -> new ItemStack(ModItems.GEO_STAFF.get());
                case WIND -> new ItemStack(ModItems.WIND_STAFF.get());
                case STORM -> new ItemStack(ModItems.STORM_STAFF.get());
                case FROST -> new ItemStack(ModItems.FROST_STAFF.get());
                case WILD -> new ItemStack(ModItems.WILD_STAFF.get());
                case ABYSS -> new ItemStack(ModItems.ABYSS_STAFF.get());
                case VOID -> new ItemStack(ModItems.VOID_STAFF.get());
                case NETHER -> new ItemStack(ModItems.NETHER_STAFF.get());
                default -> new ItemStack(ModItems.DARK_WAND.get());
            };
        }
        if (focusStack != null && !focusStack.isEmpty()) {
            staff.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(focusStack)));
        }
        return staff;
    }

    /**
     * 根据聚晶物品堆上所附魔的 Goety 附魔等级，强化并返回 SpellStat。
     * 基础 duration 强制不低于 1，防止随从生成后因 lifespan=0 瞬间暴毙。
     */
    public static SpellStat buildSpellStat(ServerLevel level, WandscapeNpc npc, ISpell spell, ItemStack focusStack) {
        SpellStat stat = spell.defaultStats();
        if (stat == null) {
            stat = new SpellStat(0, 1, 16, 0.0, 0, 0.0f);
        }
        if (stat.getDuration() < 1) {
            stat.setDuration(1);
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

        int potencyPower = 1;
        try {
            potencyPower = SpellConfig.PotencyPower.get();
        } catch (Exception ignored) {}

        if (potency > 0) stat.increasePotency(potency * potencyPower);
        if (range > 0) stat.increaseRange(range * 2);
        if (duration > 0) stat.increaseDuration(duration);
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
