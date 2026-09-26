package com.wsteam.wandscape.content.production.client;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.production.network.RecipeBookDataPacket;
import com.wsteam.wandscape.content.production.network.RecipeBookDataPacket.RecipeBookEntry;
import com.wsteam.wandscape.content.production.network.UnlockRecipeByBlueprintPacket;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.component.*;
import com.wsteam.wandscape.foundation.ui.skin.SkinRender;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Screen displaying synthesize recipes with unlocked status and blueprint-based unlock.
 */
public class RecipeBookScreen extends MedievalScreen {

    private static final int PW = 270;
    private static final int PH = 240;
    private static final int ROW_H = 26;

    private UUID colonyId;
    private int blueprintCount;
    private List<RecipeBookEntry> allRecipes = List.of();
    private List<RecipeBookEntry> filteredRecipes = List.of();

    private SearchBox searchInput;
    private TabBar filterTabBar;
    private ScrollableList<RecipeBookEntry> recipeList;
    private int filterMode = 0; // 0: ALL, 1: UNLOCKED, 2: LOCKED

    private int bpX, bpY;

    public RecipeBookScreen(RecipeBookDataPacket packet) {
        super(I18n.name("gui.wandscape.recipe_book.title", "合成配方图鉴"), PW, PH);
        this.showCloseButton = true;
        this.colonyId = packet.colonyId();
        this.blueprintCount = packet.blueprintCount();
        this.allRecipes = new ArrayList<>(packet.recipeEntries());
        this.allRecipes.sort(Comparator.comparing(RecipeBookEntry::outputItem));
    }

    public boolean isMatchingColony(@Nullable UUID colonyId) {
        return this.colonyId != null && this.colonyId.equals(colonyId);
    }

    public void updateData(RecipeBookDataPacket packet) {
        this.colonyId = packet.colonyId();
        this.blueprintCount = packet.blueprintCount();
        this.allRecipes = new ArrayList<>(packet.recipeEntries());
        this.allRecipes.sort(Comparator.comparing(RecipeBookEntry::outputItem));
        applyFilter();
    }

    @Override
    protected void init() {
        super.init();

        int topY = topPos + headerHeight + 5;

        // 1. Search Box
        int searchW = 110;
        searchInput = new SearchBox(font, leftPos + 10, topY, searchW,
                I18n.name("gui.wandscape.recipe_book.search", "搜索配方…"));
        searchInput.setResponder(t -> applyFilter());
        addRenderableWidget(searchInput);

        // Blueprint counter position
        bpX = leftPos + searchW + 18;
        bpY = topY - 1;

        // 2. Filter Tab Bar
        int tabY = topY + 20;
        List<String> tabs = List.of(
                I18n.name("gui.wandscape.recipe_book.tab_all", "全部").getString(),
                I18n.name("gui.wandscape.recipe_book.tab_unlocked", "已解锁").getString(),
                I18n.name("gui.wandscape.recipe_book.tab_locked", "未解锁").getString()
        );
        filterTabBar = new TabBar(leftPos + 10, tabY, PW - 20, tabs, filterMode, idx -> {
            filterMode = idx;
            applyFilter();
        });
        addRenderableWidget(filterTabBar);

        // 3. Scrollable Recipe List
        int listY = tabY + 20;
        int listW = PW - 20;
        int listH = topPos + PH - listY - 8;

        recipeList = new ScrollableList<>(leftPos + 10, listY, listW, listH, ROW_H) {
            @Override
            protected void renderRow(GuiGraphics g, RecipeBookEntry item, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.outputItem()));
                if (registryItem != null && registryItem != Items.AIR) {
                    g.renderItem(new ItemStack(registryItem), x + 2, y + 4);
                }

                // Name row
                Component recipeName = (registryItem != null && registryItem != Items.AIR)
                        ? new ItemStack(registryItem).getHoverName()
                        : Component.literal(item.outputItem());
                int nameColor = item.unlocked() ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED;
                g.drawString(font, recipeName, x + 22, y + 3, nameColor);

                // Cost row
                drawElementCost(g, item.cost(), x + 22, y + 14);

                // Status badge / Unlock button
                int actionW = 46;
                int actionH = 14;
                int ax = x + width - actionW - 10;
                int ay = y + 6;

                if (item.unlocked()) {
                    SkinRender.drawButton(g, ax, ay, actionW, actionH, 3);
                    g.drawCenteredString(font,
                            I18n.name("gui.wandscape.recipe_book.badge_unlocked", "已解锁"),
                            ax + actionW / 2, ay + (actionH - font.lineHeight) / 2, 0x55FF55);
                } else {
                    boolean canAfford = blueprintCount > 0;
                    SkinRender.drawButton(g, ax, ay, actionW, actionH, canAfford ? 0 : 3);
                    int textColor = canAfford ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_DIM;
                    g.drawCenteredString(font,
                            I18n.name("gui.wandscape.recipe_book.btn_unlock", "解锁"),
                            ax + actionW / 2, ay + (actionH - font.lineHeight) / 2, textColor);
                }
            }
        };

        recipeList.setTooltipProvider((item, index) -> {
            var regItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.outputItem()));
            return (regItem != null && regItem != Items.AIR) ? new ItemStack(regItem) : null;
        });

        recipeList.setOnRowClick((item, index, button) -> {
            if (button == 0 && !item.unlocked()) {
                onAttemptUnlock(item);
            }
        });

        addRenderableWidget(recipeList);

        applyFilter();
    }

    private void applyFilter() {
        String query = searchInput != null ? searchInput.getValue() : "";
        List<RecipeBookEntry> list = new ArrayList<>();
        for (RecipeBookEntry entry : allRecipes) {
            if (filterMode == 1 && !entry.unlocked()) continue;
            if (filterMode == 2 && entry.unlocked()) continue;
            if (!query.isEmpty() && !SearchBox.matches(SearchBox.itemSearchText(entry.outputItem()), query)) {
                continue;
            }
            list.add(entry);
        }
        filteredRecipes = list;
        if (recipeList != null) {
            recipeList.setItems(filteredRecipes);
        }
    }

    private void onAttemptUnlock(RecipeBookEntry item) {
        if (blueprintCount <= 0) {
            showFeedback(I18n.name("gui.wandscape.recipe.no_blueprint", "缺少物品图纸"),
                    MedievalColors.DANGER_RED);
            return;
        }

        var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.outputItem()));
        Component itemName = (registryItem != null && registryItem != Items.AIR)
                ? new ItemStack(registryItem).getHoverName()
                : Component.literal(item.outputItem());

        Component prompt = I18n.name("gui.wandscape.recipe_book.confirm_prompt",
                "确定消耗 1 张图纸解锁「%s」的合成配方吗？", itemName.getString());

        openConfirmDialog(I18n.name("gui.wandscape.recipe_book.confirm_title", "解锁配方"),
                prompt, () -> {
                    Net.toServer(new UnlockRecipeByBlueprintPacket(colonyId, item.id()));
                });
    }

    private void drawElementCost(GuiGraphics g, Map<ElementType, Long> cost, int x, int y) {
        int cx = x;
        for (var e : cost.entrySet()) {
            if (e.getValue() <= 0) continue;
            String id = e.getKey().getId();
            int tint = WandscapeTheme.elementColor(id);
            WandscapeTheme.drawIcon(g, WandscapeTheme.elementIcon(id), cx, y - 2, 9, 9, tint);
            cx += 11;
            String text = "x" + e.getValue();
            g.drawString(font, text, cx, y, tint);
            cx += font.width(text) + 6;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        // Render blueprint icon and counter
        ItemStack bpStack = new ItemStack(Wandscape.ITEM_BLUEPRINT.get());
        g.renderItem(bpStack, bpX, bpY + 1);
        String bpText = I18n.name("gui.wandscape.recipe_book.blueprints",
                "可用图纸: %s", blueprintCount).getString();
        g.drawString(font, bpText, bpX + 18, bpY + 5, MedievalColors.BORDER_GOLD);

        // Tooltip for blueprints area
        if (mouseX >= bpX && mouseX <= bpX + 18 + font.width(bpText)
                && mouseY >= bpY && mouseY <= bpY + 18) {
            g.renderTooltip(font, bpStack, mouseX, mouseY);
        }

        // Row item tooltip
        if (recipeList != null) {
            ItemStack hovered = recipeList.hoveredTooltipStack();
            if (hovered != null && !confirmDialog.isOpen()) {
                g.renderTooltip(font, hovered, mouseX, mouseY);
            }
        }
    }

    @Override
    protected void renderCreatorFooter(GuiGraphics g) {
        // Suppress creator footer for RecipeBookScreen
    }
}
