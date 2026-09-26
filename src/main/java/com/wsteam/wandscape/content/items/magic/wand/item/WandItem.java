package com.wsteam.wandscape.content.items.magic.wand.item;

import com.wsteam.wandscape.api.NpcInteractHook;
import com.wsteam.wandscape.api.NpcSneakInteractHook;
import com.wsteam.wandscape.api.WandApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.items.magic.wand.internal.WandModeService;
import com.wsteam.wandscape.content.npc.WandscapeAttributes;
import com.wsteam.wandscape.content.npc.types.NpcAttributeModifier;
import com.wsteam.wandscape.foundation.util.ItemData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

public class WandItem extends Item implements NpcInteractHook, NpcSneakInteractHook {

    /** 物品自定义数据（见 {@link ItemData}）中存预设 id 的键。 */
    public static final String PRESET_KEY = "preset_id";
    /** 物品自定义数据（见 {@link ItemData}）中存染色（{@code #RRGGBB}）的键。 */
    public static final String COLOR_KEY = "wand_color";
    /** 物品自定义数据（见 {@link ItemData}）中存当前指挥模式（{@link WandMode} 名）的键。 */
    public static final String MODE_KEY = "mode";

    public WandItem(Properties properties) {
        super(properties);
    }

    // ── 指挥模式（玩家手持时的右键行为；见 WandMode）──

    /** 读当前模式；无记录或非法值回退出厂模式 {@link WandMode#DEFAULT}。 */
    public static WandMode getMode(ItemStack stack) {
        String name = ItemData.getString(stack, MODE_KEY);
        if (!name.isEmpty()) {
            try {
                return WandMode.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // 非法值回落默认
            }
        }
        return WandMode.DEFAULT;
    }

    /** 写入当前模式到物品自定义数据（就地修改传入的 stack）。 */
    public static void setMode(ItemStack stack, WandMode mode) {
        ItemData.setString(stack, MODE_KEY, mode.name());
    }

    /**
     * 服务端循环到下一模式并同步手持槽，上屏提示。
     *
     * <p>{@code player.setItemInHand} 是让客户端当 tick 就更新 tooltip 的关键——模式住在物品数据里，
     * 不主动回写手持槽的话客户端要等到下一次背包同步才看得到新值。
     */
    public static void cycleMode(ServerPlayer player, ItemStack stack, InteractionHand hand) {
        WandMode[] modes = WandMode.values();
        WandMode next = modes[(getMode(stack).ordinal() + 1) % modes.length];
        setMode(stack, next);
        player.setItemInHand(hand, stack);
        player.displayClientMessage(modeInfo(next), true);
    }

    /** 模式显示名组件（lang {@code mode.wandscape.wand.<name>}）。 */
    public static Component modeText(WandMode mode) {
        return Component.translatable(mode.langKey());
    }

    /** "当前模式：%s" 组件。 */
    public static Component modeInfo(WandMode mode) {
        return Component.translatable("item.wandscape.wand.mode_current", modeText(mode));
    }

    /** 非潜行右键本镇法师：按当前模式执行（庇护/敌对作用于该法师；集合/鉴定给提示）。 */
    @Override
    public void onInteractNpc(ServerPlayer player, Mob mage, InteractionHand hand) {
        WandModeService.onInteractNpc(player, mage, getMode(player.getItemInHand(hand)));
    }

    /** 潜行右键本镇法师：循环模式（与右键空气/方块同一入口）。 */
    @Override
    public void onShiftClickNpc(ServerPlayer player, Mob npc, InteractionHand hand) {
        cycleMode(player, player.getItemInHand(hand), hand);
    }

    /** 潜行右键空气：循环模式；非潜行右键空气不做事（命令都要有落点）。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            cycleMode(sp, stack, hand);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    /**
     * 右键方块：潜行切换模式，否则按当前模式对点击位置下命令。
     *
     * <p>接管与否只由 {@link WandMode#affectsBlocks()} 决定，两端因此给出同一个结果：
     * 生物模式（庇护/敌对）原样放行方块交互，方块模式（集合/鉴定）接管。
     *
     * <p>原版先让方块自己处理右键，方块没接住才会走到这里——所以箱子/熔炉/门这些有界面的方块
     * 照旧开自己的界面，本方法只覆盖「原版本来就不响应右键」的普通方块。
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        ItemStack stack = context.getItemInHand();

        if (player.isShiftKeyDown()) {
            if (!context.getLevel().isClientSide && player instanceof ServerPlayer sp) {
                cycleMode(sp, stack, context.getHand());
            }
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }

        WandMode mode = getMode(stack);
        if (!mode.affectsBlocks()) return InteractionResult.PASS;

        if (!context.getLevel().isClientSide && player instanceof ServerPlayer sp) {
            WandModeService.onUseBlock(sp, context.getLevel(),
                    context.getClickedPos(), context.getClickedFace(), mode);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    /**
     * 读法杖染色串（{@code #RRGGBB}）；无染色 → 空串。
     *
     * <p>染色的三个消费方（法杖 API、施法光束、NPC 手持渲染、物品 tint）此前各自内联
     * 键名与解析，现统一收在这里：默认色各不相同，故本类只负责「读到什么」，
     * 「读不到用什么」由调用方定。
     */
    public static String colorHex(ItemStack stack) {
        return ItemData.getString(stack, COLOR_KEY);
    }

    /** 读法杖染色为 ARGB；无染色或格式非法 → null（默认色由调用方定）。 */
    @Nullable
    public static Integer colorArgb(ItemStack stack) {
        String hex = colorHex(stack);
        if (hex.length() != 7 || hex.charAt(0) != '#') {
            return null;
        }
        try {
            return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return false;
    }

    @Override
    public boolean isDamageable(ItemStack stack) {
        return false;
    }

    /**
     * 法杖属性只对 NPC 生效，玩家手持不生效：不再用 vanilla {@link ItemAttributeModifiers}
     * 自动结算（谁拿主手谁享属性），而是返回空。NPC 主手装备法杖时，加成由
     * {@code WandscapeNpc#syncWandAttributes} 手动桥接；玩家持法杖则无任何属性
     * （顺带避免 bastion 法杖的负移速让玩家无法行走）。
     */
    @Override
    public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack stack) {
        return ItemAttributeModifiers.EMPTY;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);

        // 玩家手持的指挥模式：这是玩家唯一能看到当前模式的地方（模式住在物品数据里，
        // 颜色仍归预设染色，不随模式变），所以无论有无预设都要显示。
        tooltipComponents.add(modeInfo(getMode(stack)));
        tooltipComponents.add(Component.translatable("item.wandscape.wand.tooltip"));

        WandApi api = WandscapeApis.getWandApiSilently();
        if (api == null) return;
        String presetId = api.getWandPresetId(stack);
        if (presetId == null) return;
        tooltipComponents.add(CommonComponents.EMPTY);
        tooltipComponents.add(Component.translatable("craft_recipe.wandscape." + presetId));

        // 法杖属性只对 NPC 生效，玩家手持无加成——但属性值仍应可查阅：默认
        // getDefaultAttributeModifiers 返回空，MC 不会自动列出属性，故在此显式渲染主手属性块。
        List<NpcAttributeModifier> mods = api.getWandModifiers(presetId);
        if (mods == null || mods.isEmpty()) return;

        tooltipComponents.add(CommonComponents.EMPTY);
        tooltipComponents.add(Component.translatable("item.modifiers." + EquipmentSlotGroup.MAINHAND.getSerializedName())
                .withStyle(ChatFormatting.GRAY));
        for (NpcAttributeModifier mod : mods) {
            Holder<Attribute> vanillaAttr = WandscapeAttributes.toVanilla(mod.type());
            if (vanillaAttr == null) continue;
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation op =
                    switch (mod.operation()) {
                        case ADDITION -> net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE;
                        case MULTIPLY_BASE -> net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
                    };
            ResourceLocation modId = ResourceLocation.fromNamespaceAndPath(
                    Wandscape.MODID, "wand_" + presetId + "_" + mod.type().name().toLowerCase(Locale.ROOT));
            net.minecraft.world.entity.ai.attributes.AttributeModifier mcMod =
                    new net.minecraft.world.entity.ai.attributes.AttributeModifier(modId, mod.amount(), op);
            tooltipComponents.add(vanillaAttr.value().toComponent(mcMod, tooltipFlag));
        }
    }
}
