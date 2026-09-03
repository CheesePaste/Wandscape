package com.wsteam.wandscape.content.items.magic;

import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.internal.MagicSpellExecutors;
import com.wsteam.wandscape.content.magic.internal.SpellbookLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 魔法的物品形态（通用件，CUSTOM_DATA 存 magicId）。
 *
 * <p>创造模式右键在当前位置施放所绑魔法（测试用）；生存模式不予施放。
 * tooltip 显示魔法名 + 耗蓝 / 冷却 / 施法时间（读 MagicDef 数据）。
 * 只允许绑定战斗魔法 + 特殊魔法（heal/teleport）——revive（祭坛专属，ALTAR）不物品化；
 * teleport 卷轴创造模式不可施放（导航回退魔法，无原地施法语义）。
 */
public class SpellItem extends Item {

    /** {@link DataComponents#CUSTOM_DATA} 中存 magicId 的键。 */
    public static final String MAGIC_ID_KEY = "magic_id";

    public SpellItem(Properties properties) {
        super(properties);
    }

    /** 读绑定魔法 id；未绑定返回 null。 */
    @Nullable
    public static String getMagicId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.contains(MAGIC_ID_KEY)) {
            return data.copyTag().getString(MAGIC_ID_KEY);
        }
        return null;
    }

    /** 写入绑定魔法 id。 */
    public static void setMagicId(ItemStack stack, String magicId) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        var tag = data != null ? data.copyTag() : new CompoundTag();
        tag.putString(MAGIC_ID_KEY, magicId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer sp) || !sp.getAbilities().instabuild) {
            player.displayClientMessage(Component.translatable("item.wandscape.spell.creative_only"), true);
            return InteractionResultHolder.fail(stack);
        }
        String magicId = getMagicId(stack);
        if (magicId == null) {
            sp.displayClientMessage(Component.translatable("item.wandscape.spell.not_bound"), true);
            return InteractionResultHolder.fail(stack);
        }
        MagicDef def = SpellbookLoader.getSpec(magicId);
        if (def == null || def.category() == MagicDef.Category.ALTAR
                || "teleport".equals(magicId)) {
            sp.displayClientMessage(Component.translatable("item.wandscape.spell.invalid"), true);
            return InteractionResultHolder.fail(stack);
        }
        boolean ok = MagicSpellExecutors.castForPlayer(sp, def);
        if (!ok) {
            sp.displayClientMessage(Component.translatable("item.wandscape.spell.cast_failed"), true);
        }
        return ok ? InteractionResultHolder.success(stack) : InteractionResultHolder.fail(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        String magicId = getMagicId(stack);
        if (magicId == null) {
            tooltipComponents.add(Component.translatable("item.wandscape.spell.unbound"));
            return;
        }
        MagicDef def = SpellbookLoader.getSpec(magicId);
        tooltipComponents.add(Component.translatable("item.wandscape.spell.name", magicName(magicId)));
        if (def == null) {
            tooltipComponents.add(Component.translatable("item.wandscape.spell.unknown_data"));
            return;
        }
        tooltipComponents.add(Component.translatable("item.wandscape.spell.mana_cost", def.manaCost()));
        tooltipComponents.add(Component.translatable("item.wandscape.spell.cooldown", seconds(def.baseCooldown())));
        tooltipComponents.add(Component.translatable("item.wandscape.spell.cast_time", seconds(def.castTime())));
        tooltipComponents.add(Component.translatable("item.wandscape.spell.creative_hint"));
    }

    private static Component magicName(String magicId) {
        return Component.translatableWithFallback("magic.wandscape." + magicId, magicId);
    }

    /** tick → 秒字符串（一位小数）。 */
    private static String seconds(int ticks) {
        return String.format("%.1f", ticks / 20.0);
    }
}