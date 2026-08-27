package com.wsteam.wandscape.production.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.building.network.OpenWarehousePacket;
import com.wsteam.wandscape.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.building.network.TaskQueueModifyPacket;
import com.wsteam.wandscape.production.network.CraftingStationPacket;
import com.wsteam.wandscape.production.network.CraftingStationPacket.RecipeEntry;
import com.wsteam.wandscape.production.network.RequestProductionTaskPacket;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.component.SearchBox;
import com.wsteam.wandscape.shared.ui.component.TaskQueuePanel;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.shared.ui.theme.WandscapeTheme;
import com.wsteam.wandscape.shared.ui.util.ItemStackUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
public class CraftingStationScreen extends MedievalScreen {

    private static final int PW = 400;
    private static final int PH = 220;
    // Left panel width (existing content)
    private static final int LEFT_PW = 240;
    // Right panel (TaskQueuePanel) — narrower to stay inside the PW=400 window
    private static final int QUEUE_PW = 148;
    private static final int QUEUE_PH = PH - 28; // headerHeight (20) + padding (8)
    private BlockPos stationPos = BlockPos.ZERO;
    private List<RecipeEntry> recipes = new ArrayList<>();
    private List<RecipeEntry> filteredRecipes = new ArrayList<>();

    private ScrollableList<RecipeEntry> recipeList;
    private SearchBox searchInput;
    private QuantityStepper stepper;
    private TaskQueuePanel taskQueuePanel;

    public CraftingStationScreen() {
        super(Component.literal("Crafting Station"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.crafting_station.title", "Crafting Station"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "crafting_guide";
    }

    public void updateData(CraftingStationPacket packet) {
        this.stationPos = packet.stationPos();
        setCreator(packet.creator());
        this.recipes = packet.entries();
        // Re-apply the current search filter to the refreshed data
        applySearch(searchInput != null ? searchInput.getValue() : "");
        if (stepper != null) {
            stepper.setTotalMax(1);
        }
        // Request current queue data from server
        requestQueueRefresh();
    }

    /** Called when a TaskQueueDataPacket arrives from the server. */
    public void updateQueueData(TaskQueueDataPacket packet) {
        if (packet.stationPos().equals(this.stationPos) && taskQueuePanel != null) {
            List<TaskQueuePanel.Entry> entries = new ArrayList<>();
            for (TaskQueueDataPacket.QueueEntry qe : packet.entries()) {
                entries.add(new TaskQueuePanel.Entry(
                        qe.index(), qe.category(), qe.itemOrRecipeId(), qe.quantity(),
                        qe.blueprintId(), qe.summary(), qe.insufficient(), qe.missingElements()));
            }
            taskQueuePanel.setEntries(entries);
            taskQueuePanel.setCurrents(toPanelCurrents(packet.currents()));
        }
    }

    /** Convert the packet's running-task records to the panel's CurrentInfo list. */
    private static List<TaskQueuePanel.CurrentInfo> toPanelCurrents(List<TaskQueueDataPacket.CurrentTask> cts) {
        List<TaskQueuePanel.CurrentInfo> result = new ArrayList<>();
        if (cts == null) return result;
        for (TaskQueueDataPacket.CurrentTask ct : cts) {
            if (ct == null) continue;
            TaskQueueDataPacket.QueueEntry e = ct.entry();
            result.add(new TaskQueuePanel.CurrentInfo(
                    new TaskQueuePanel.Entry(e.index(), e.category(), e.itemOrRecipeId(),
                            e.quantity(), e.blueprintId(), e.summary(), false, List.of()),
                    ct.stepIndex(), ct.totalSteps(),
                    ct.channelRemainingTicks(), ct.channelTotalTicks(),
                    ct.pending()));
        }
        return result;
    }

    /** Send a REFRESH request to the server to get the current task queue. */
    private void requestQueueRefresh() {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(stationPos, "refresh", 0));
    }

    private int queueRefreshCounter;

    @Override
    public void tick() {
        super.tick();
        if (taskQueuePanel != null) {
            taskQueuePanel.tickProgress();
            if (++queueRefreshCounter >= 20) {
                queueRefreshCounter = 0;
                requestQueueRefresh();
            }
        }
    }

    @Override
    protected void init() {
        super.init();

        // Left panel content (existing widgets)
        int contentX = leftPos + 8;
        int contentY = topPos + headerHeight + 4;
        int contentW = LEFT_PW - 16;

        // Search box above the recipe list (warehouse-style inset field)
        int searchH = font.lineHeight + 6;
        searchInput = new SearchBox(font, contentX + 1, contentY + 2, contentW - 2,
                I18n.name("gui.wandscape.common.search", "Search"));
        searchInput.setResponder(this::applySearch);
        addRenderableWidget(searchInput);

        // Recipe list — shrink by the creator footer strip so the slider/submit row stays clear
        int listY = contentY + searchH + 4;
        int listH = PH - headerHeight - 4 - searchH - 4 - 44 - CREATOR_FOOTER_H - 4;
        recipeList = new ScrollableList<>(contentX, listY, contentW, listH, 22) {
            @Override
            protected void renderRow(GuiGraphics g, RecipeEntry item, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                boolean isLocked = !"unlocked".equals(item.lockedReason());
                boolean canAfford = !isLocked && item.maxAffordable() > 0;

                var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.outputItem()));
                if (registryItem != null && registryItem != Items.AIR) {
                    g.renderItem(new ItemStack(registryItem), x, y + 2);
                }

                // Name row
                int nameColor;
                if (isLocked) {
                    nameColor = MedievalColors.TEXT_DIM;
                } else if (canAfford) {
                    nameColor = selected ? MedievalColors.ACCENT_GOLD
                            : hovered ? MedievalColors.TEXT_WARM_WHITE
                            : MedievalColors.TEXT_MUTED;
                } else {
                    // elements insufficient but recipe is unlocked
                    nameColor = MedievalColors.TEXT_DIM;
                }

                int textX = x + 20;
                
                String itemFallback = (registryItem != null && registryItem != Items.AIR)
                        ? new ItemStack(registryItem).getHoverName().getString()
                        : item.outputItem();
                Component recipeName = com.wsteam.wandscape.shared.ui.I18n.name(
                        "craft_recipe.wandscape." + item.recipeId(), itemFallback);
                g.drawString(Minecraft.getInstance().font, recipeName, textX, y + 1, nameColor);

                // Requirement / cost row
                String reason = item.lockedReason();
                if ("colony".equals(reason)) {
                    StringBuilder costStr = new StringBuilder();
                    var req = item.unlockRequirement();
                    costStr.append(I18n.name("gui.wandscape.recipe.colony_level",
                            "Colony Lv>=%s", req.minColonyLevel()).getString());
                    g.drawString(Minecraft.getInstance().font, costStr.toString(),
                            x + 20, y + 12, MedievalColors.TEXT_DIM);
                } else {
                    int endX = drawElementCost(g, item.cost(), x + 20, y + 12);
                    if (!item.extraInputs().isEmpty()) {
                        drawExtraInputs(g, item.extraInputs(), endX, y + 12);
                    }
                }
            }
        };
        recipeList.setOnSelect(i -> updateSliderForRecipe(filteredRecipes.get(i)));
        recipeList.setTooltipProvider((item, index) -> ItemStackUtil.fromIdWithNbt(item.outputItem(), item.nbt()));
        addRenderableWidget(recipeList);

        // Quantity slider + submit
        int controlY = listY + listH + 6;
        stepper = new QuantityStepper(contentX, controlY);
        addRenderableWidget(stepper.slider());
        addRenderableWidget(stepper.minusBtn());
        addRenderableWidget(stepper.plusBtn());

        addRenderableWidget(new MedievalButton(
                contentX + contentW - 70, controlY + 4, 70, 18,
                I18n.name("gui.wandscape.common.submit", "Submit"), this::onSubmit));

        // Open the colony warehouse to view remaining element counts and stored items
        addRenderableWidget(new MedievalButton(
                contentX + contentW - 70, controlY + 24, 70, 18,
                I18n.name("gui.wandscape.common.open_warehouse", "Open Warehouse"), this::onOpenWarehouse));

        applySearch(searchInput.getValue());

        // ── Right panel: Task Queue ──
        // Shorter panel: header + 4px top + 4px bottom = 8px total vertical padding
        int queuePh = PH - headerHeight - 8;
        int queueX = leftPos + LEFT_PW + 4;
        int queueY = topPos + headerHeight + 4;
        taskQueuePanel = new TaskQueuePanel(queueX, queueY, QUEUE_PW, queuePh);
        taskQueuePanel.setOnDelete(this::onQueueDelete);
        taskQueuePanel.setOnMoveUp(this::onQueueMoveUp);
        taskQueuePanel.setOnMoveDown(this::onQueueMoveDown);
        addRenderableWidget(taskQueuePanel);
    }

    @Override
    protected void renderForeground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 悬停列表行时在光标处显示标准物品 tooltip（与物品栏一致，置于所有控件之上）
        ItemStack tooltip = recipeList != null ? recipeList.hoveredTooltipStack() : null;
        if (tooltip != null) {
            g.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    /** Filter the recipe list by the search query, keeping it in sync with selection indexes. */
    private void applySearch(String query) {
        filteredRecipes = SearchBox.filter(recipes, query, CraftingStationScreen::recipeSearchText);
        if (recipeList != null) recipeList.setItems(filteredRecipes);
    }

    /** Searchable text for a recipe: localized name + output/recipe ids. */
    private static String recipeSearchText(RecipeEntry r) {
        var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(r.outputItem()));
        String fallback = (registryItem != null && registryItem != Items.AIR)
                ? new ItemStack(registryItem).getHoverName().getString()
                : r.outputItem();
        String name = I18n.name("craft_recipe.wandscape." + r.recipeId(), fallback).getString();
        return name + " " + r.outputItem() + " " + r.recipeId();
    }

    private void updateSliderForRecipe(RecipeEntry entry) {
        if (entry == null) {
            stepper.setTotalMax(1);
            return;
        }
        // Locked recipes (colony / elements) show max_affordable=0; keep slider at 1
        boolean locked = !"unlocked".equals(entry.lockedReason());
        int max = locked ? 1 : entry.maxAffordable();
        stepper.setTotalMax(max);
    }

    private void onSubmit() {
        RecipeEntry sel = recipeList.getSelected();
        // Block submission when recipe is locked (colony / elements)
        if (sel == null || !"unlocked".equals(sel.lockedReason())) return;
        int qty = stepper.getValue();
        // 药水配方走 brew_potion（校验额外原料），法杖配方走 craft_wand。
        String action = "potion".equals(sel.type()) ? "brew_potion" : "craft_wand";
        PacketDistributor.sendToServer(new RequestProductionTaskPacket(
                stationPos, action, sel.recipeId(), qty));
        // Refresh queue after submitting a new task
        requestQueueRefresh();
    }

    /** Open the colony warehouse to check remaining element counts. */
    private void onOpenWarehouse() {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        PacketDistributor.sendToServer(new OpenWarehousePacket(stationPos));
    }

    // ── Task queue callbacks ──

    private void onQueueDelete(int index) {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(stationPos, "delete", index));
    }

    private void onQueueMoveUp(int index) {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(stationPos, "move_up", index));
    }

    private void onQueueMoveDown(int index) {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(stationPos, "move_down", index));
    }

    /** Draw an element cost as [icon]xN (icon tinted per element, like the V-key panel). Returns end x. */
    private static int drawElementCost(GuiGraphics g, Map<ElementType, Long> cost, int x, int y) {
        var font = Minecraft.getInstance().font;
        int cx = x;
        for (var e : cost.entrySet()) {
            String id = e.getKey().getId();
            int tint = WandscapeTheme.elementColor(id);
            WandscapeTheme.drawIcon(g, WandscapeTheme.elementIcon(id), cx, y - 2, 9, 9, tint);
            cx += 11;
            String text = "x" + e.getValue();
            g.drawString(font, text, cx, y, tint);
            cx += font.width(text) + 6;
        }
        return cx;
    }

    /** Draw extra non-element inputs (e.g. potion glass bottles) in muted text after the element cost. */
    private static void drawExtraInputs(GuiGraphics g, List<String> extraInputs, int x, int y) {
        var font = Minecraft.getInstance().font;
        int cx = x;
        for (String itemId : extraInputs) {
            var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
            String label = (registryItem != null && registryItem != net.minecraft.world.item.Items.AIR)
                    ? new ItemStack(registryItem).getHoverName().getString() : itemId;
            g.drawString(font, "+" + label, cx, y, MedievalColors.TEXT_DIM);
            cx += font.width("+" + label) + 8;
        }
    }
}
