package com.wsteam.wandscape.integration.jei;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.log.Log;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 「Wandscape 元素」配方分类：同一分类内用 kind 区分合成 / 分解。
 * 图标为法杖，JEI 物品配方视图顶部据此显示模组标签页。
 *
 * <p>布局：
 * <ul>
 *   <li>合成：左侧元素物品（带数量）→ 中间箭头（上方站名标签）→ 右侧物品。</li>
 *   <li>分解：左侧物品 → 中间箭头（上方站名标签，下方 ÷N）→ 右侧元素物品。</li>
 *   <li>药剂配方的额外原料（如玻璃瓶）追加在元素之后。</li>
 * </ul>
 */
public class ElementRecipeCategory implements IRecipeCategory<ElementRecipe> {

    public static final RecipeType<ElementRecipe> TYPE =
            RecipeType.create("wandscape", "element", ElementRecipe.class);

    private static final int WIDTH = 128;
    private static final int HEIGHT = 92;
    private static final int CENTER_Y = 46;

    private final IDrawable icon;
    private final IDrawable background;
    private final IDrawable arrow;

    // 元素网格：合成时居左、分解时居右
    private static final int ELEMENT_GRID_X_LEFT = 8;
    private static final int ELEMENT_GRID_X_RIGHT = 66;
    private static final int ELEMENT_COLS = 3;
    private static final int SLOT_WH = 18;
    private static final int ROW_SPAN = 18;
    // 物品槽：合成时居右、分解时居左
    private static final int ITEM_X = 106;
    // 中间箭头
    private static final int ARROW_X_LEFT = 35;
    private static final int ARROW_X_RIGHT = 70;
    private static final int ARROW_Y = CENTER_Y - 9;

    public ElementRecipeCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemStack(new ItemStack(Wandscape.WAND.get()));
        this.background = guiHelper.createBlankDrawable(WIDTH, HEIGHT);
        this.arrow = guiHelper.getRecipeArrow();
    }

    @Override
    public RecipeType<ElementRecipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("gui.wandscape.jei.title");
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, ElementRecipe recipe, IFocusGroup focuses) {
        int itemY = CENTER_Y - SLOT_WH / 2;
        if (recipe.kind() == ElementRecipeKind.SYNTHESIZE) {
            // 元素(左) → 箭头 → 物品(右)
            int total = sorted(recipe.elements()).size() + recipe.extraInputs().size();
            int rows = elementRows(total);
            int index = addElementInputs(builder, recipe.elements(), ELEMENT_GRID_X_LEFT, rows, 0);
            index = addExtraInputs(builder, recipe.extraInputs(), rows, index);
            builder.addOutputSlot(ITEM_X, itemY).addItemStack(resolveItem(recipe.itemId()));
        } else {
            // 分解：物品(左) → 箭头 → 元素(右)
            int total = sorted(recipe.elements()).size() + recipe.extraInputs().size();
            int rows = elementRows(total);
            builder.addInputSlot(ELEMENT_GRID_X_LEFT, itemY).addItemStack(resolveItem(recipe.itemId()));
            int index = addElementOutputs(builder, recipe, ELEMENT_GRID_X_RIGHT, rows, 0);
            addExtraInputs(builder, recipe.extraInputs(), rows, index);
        }
    }

    private static int elementRows(int slotCount) {
        return (slotCount + ELEMENT_COLS - 1) / ELEMENT_COLS;
    }

    /** 添加元素槽（合成=数量=cost，分解=数量=完整价值），返回下一个可用 slot 索引。 */
    private int addElementInputs(IRecipeLayoutBuilder builder, Map<ElementType, Long> elements,
                                 int gridX, int rows, int start) {
        int i = start;
        for (var entry : sorted(elements)) {
            IRecipeSlotBuilder slot = builder.addInputSlot(slotX(gridX, i), slotY(rows, i))
                    .setStandardSlotBackground()
                    .addItemStack(elementStack(entry.getKey(), entry.getValue()));
            i++;
        }
        return i;
    }

    private int addExtraInputs(IRecipeLayoutBuilder builder, List<String> extraInputs,
                               int rows, int start) {
        int i = start;
        for (String itemId : extraInputs) {
            builder.addInputSlot(slotX(ELEMENT_GRID_X_LEFT, i), slotY(rows, i))
                    .setStandardSlotBackground()
                    .addItemStack(resolveItem(itemId));
            i++;
        }
        return i;
    }

    private int addElementOutputs(IRecipeLayoutBuilder builder, ElementRecipe recipe,
                                  int gridX, int rows, int start) {
        int i = start;
        for (var entry : sorted(recipe.elements())) {
            builder.addOutputSlot(slotX(gridX, i), slotY(rows, i))
                    .setStandardSlotBackground()
                    .addItemStack(elementStack(entry.getKey(), entry.getValue()));
            i++;
        }
        return i;
    }

    private static int slotX(int gridX, int index) {
        return gridX + (index % ELEMENT_COLS) * SLOT_WH;
    }

    /** 元素网格垂直居中：gridTop 使网格整体中心落在 CENTER_Y。 */
    private static int slotY(int rows, int index) {
        int gridTop = CENTER_Y - rows * ROW_SPAN / 2;
        return gridTop + (index / ELEMENT_COLS) * ROW_SPAN;
    }

    private java.util.List<Map.Entry<ElementType, Long>> sorted(Map<ElementType, Long> elements) {
        return elements.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
    }

    private static ItemStack elementStack(ElementType type, long count) {
        Item item = Wandscape.ELEMENT_ITEMS.get(type).get();
        if (item == null) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item);
        if (count > 1) stack.setCount((int) Math.min(count, 64));
        return stack;
    }

    private static ItemStack resolveItem(String itemId) {
        if (itemId == null) return ItemStack.EMPTY;
        try {
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) return ItemStack.EMPTY;
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(rl));
            return stack.isEmpty() ? ItemStack.EMPTY : stack;
        } catch (RuntimeException e) {
            Log.warn("ElementRecipeCategory", "解析物品 " + itemId + " 失败，跳过该槽位", e);
            return ItemStack.EMPTY;
        }
    }

    private static double getDecomposeDivisor() {
        return com.wsteam.wandscape.Config.ELEMENT_DECOMPOSE_DIVISOR.get();
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, ElementRecipe recipe, IFocusGroup focuses) {
        builder.addDrawable(background, 0, 0);
        if(recipe.kind()==ElementRecipeKind.SYNTHESIZE) {
                builder.addDrawable(arrow, ARROW_X_RIGHT, ARROW_Y);
        }
        else
        {
            builder.addDrawable(arrow, ARROW_X_LEFT, ARROW_Y);
        }
    }

    @Override
    public void draw(ElementRecipe recipe, IRecipeSlotsView slotsView, GuiGraphics guiGraphics,
                     double mouseX, double mouseY) {
        int ARROW_X=0;
        if(recipe.kind()==ElementRecipeKind.SYNTHESIZE)
        {
            ARROW_X=ARROW_X_RIGHT;
        }
        else
        {
            ARROW_X=ARROW_X_LEFT;
        }
        Font font = Minecraft.getInstance().font;
        // 站名标签：箭头正上方
        Component station = stationName(recipe);
        guiGraphics.drawString(font, station, ARROW_X, ARROW_Y - 8, 0xff404040, false);

        if (recipe.kind() == ElementRecipeKind.DECOMPOSE) {
            Component divide = Component.translatable("gui.wandscape.jei.decompose.divide",
                    (long) getDecomposeDivisor());
            guiGraphics.drawString(font, divide, ARROW_X, ARROW_Y + 18, 0xff404040, false);
        }
    }

    private static Component stationName(ElementRecipe recipe) {
        switch (recipe.stationKey()) {
            case ElementRecipeCollector.STATION_CRAFTING:
                return Component.translatable("gui.wandscape.jei.station.crafting");
            case ElementRecipeCollector.STATION_POTION:
                return Component.translatable("gui.wandscape.jei.station.potion");
            case ElementRecipeCollector.STATION_WORKSTATION:
            default:
                return Component.translatable("gui.wandscape.jei.station.workstation");
        }
    }
}
