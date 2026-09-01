package com.wsteam.wandscape.compat.jei;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.content.items.SpellItem;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Wandscape 与 JEI 的集成插件（可选依赖，无 JEI 时不加载该类）。
 *
 * <p>注册单个「Wandscape 元素」配方分类，配方通过懒查询插件动态提供，
 * 元素映射数据 `/reload` 后自动同步刷新。
 *
 * <p>另为每个已绑定魔法的卷轴（spell_scroll）注册 JEI 信息页：在 JEI 中查看卷轴的
 * 配方/用途时展示该魔法的介绍文本（来源 {@code magic_spells/*.json} 的 {@code description}，
 * 经 {@code magic.wandscape.<id>.desc} 语言键本地化，缺省回退原文）。
 */
@JeiPlugin
public class WandscapeJeiPlugin implements IModPlugin {

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
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        registration.addTypedRecipeManagerPlugin(ElementRecipeCategory.TYPE,
                new ElementRecipeManagerPlugin());
    }
}
