package com.wsteam.wandscape.compat.jei;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.content.items.magic.SpellItem;
import com.wsteam.wandscape.content.items.magic.wand.internal.WandPresetLoader.WandPreset;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * Wandscape 与 JEI 的集成插件（可选依赖，无 JEI 时不加载该类）。
 *
 * <p>注册单个「Wandscape 元素」配方分类，配方通过懒查询插件动态提供，
 * 元素映射数据 `/reload` 后自动同步刷新。
 *
 * <p>另为每个已绑定魔法的卷轴（spell_scroll）注册 JEI 信息页：在 JEI 中查看卷轴的
 * 配方/用途时展示该魔法的介绍文本（来源 {@code magic_spells/*.json} 的 {@code description}，
 * 经 {@code magic.wandscape.<id>.desc} 语言键本地化，缺省回退原文）。
 *
 * <p>小道具（{@link #INFO_ITEMS}）与法杖同样各挂一条信息页，文案取语言键
 * {@code item.wandscape.<id>.desc}——与帕秋莉手册对应条目（{@code *_guide.md}）同文，
 * 改文案要两边一起改。
 */
@JeiPlugin
public class WandscapeJeiPlugin implements IModPlugin {

    /**
     * 需要 JEI 信息页的小道具（权杖/戒指/罗盘/终端）。法杖不在此列——它是「单物品 + 预设 NBT」，
     * 每个预设变体单独处理；卷轴同理，按魔法逐个注册。文案统一取 lang 的
     * {@code <物品描述键>.desc}，与手册 {@code *_guide.md} 同文。
     */
    private static final List<Item> INFO_ITEMS = List.of(
            Wandscape.OATH_RING.get(),
            Wandscape.OATH_RING_MID.get(),
            Wandscape.OATH_RING_HIGH.get(),
            Wandscape.PEACE_WAND.get(),
            Wandscape.FOLLOW_WAND.get(),
            Wandscape.SHELTER_WAND.get(),
            Wandscape.HOSTILE_WAND.get(),
            Wandscape.OMNI_SCEPTER.get(),
            Wandscape.MAGIC_COMPASS.get(),
            Wandscape.ADVANCED_MAGIC_COMPASS.get(),
            Wandscape.ULTIMATE_MAGIC_COMPASS.get(),
            Wandscape.WAREHOUSE_TERMINAL.get());

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "jei_plugin");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(
                new ElementRecipeCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        for (SpellInfoEntry entry : SpellInfoCollector.fromDefs(SpellbookLoader.getAllSpecs().values())) {
            ItemStack stack = new ItemStack(Wandscape.SPELL_SCROLL.get());
            SpellItem.setMagicId(stack, entry.magicId());
            registration.addItemStackInfo(stack, Component.translatableWithFallback(
                    "magic.wandscape." + entry.magicId() + ".desc", entry.description()));
        }

        for (Item item : INFO_ITEMS) {
            registerItemInfo(registration, new ItemStack(item));
        }

        // 法杖的裸物品与 12 个预设变体都挂同一条信息（预设存在 CUSTOM_DATA，JEI 按精确 stack 匹配）
        registerItemInfo(registration, new ItemStack(Wandscape.WAND.get()));
        for (WandPreset preset : Wandscape.WAND_PRESET_LOADER.getAllPresets().values()) {
            ItemStack stack = new ItemStack(Wandscape.WAND.get());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(preset.nbt().copy()));
            registerItemInfo(registration, stack);
        }
    }

    /** 物品信息页文案 = lang 的 {@code <物品描述键>.desc}（键由物品自身描述键派生，避免手抄 id）。 */
    private static void registerItemInfo(IRecipeRegistration registration, ItemStack stack) {
        registration.addItemStackInfo(stack,
                Component.translatable(stack.getItem().getDescriptionId() + ".desc"));
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        registration.addTypedRecipeManagerPlugin(ElementRecipeCategory.TYPE,
                new ElementRecipeManagerPlugin());
    }
}
