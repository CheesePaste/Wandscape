package com.wsteam.wandscape.production.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.building.network.TaskQueueModifyPacket;
import com.wsteam.wandscape.production.network.CraftingStationPacket;
import com.wsteam.wandscape.production.network.CraftingStationPacket.RecipeEntry;
import com.wsteam.wandscape.production.network.RequestProductionTaskPacket;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.Slider;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.component.TaskQueuePanel;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.shared.ui.theme.WandscapeTheme;

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
    // Right panel (TaskQueuePanel)
    private static final int QUEUE_PW = 140;
    private static final int QUEUE_PH = PH - 28; // headerHeight (20) + padding (8)
    private BlockPos stationPos = BlockPos.ZERO;
    private List<RecipeEntry> recipes = new ArrayList<>();

    private ScrollableList<RecipeEntry> recipeList;
    private Slider slider;
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
        this.recipes = packet.entries();
        if (recipeList != null) recipeList.setItems(recipes);
        if (slider != null) {
            slider.setMax(1);
            slider.setValue(1);
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
                        qe.blueprintId(), qe.summary()));
            }
            taskQueuePanel.setEntries(entries);
        }
    }

    /** Send a REFRESH request to the server to get the current task queue. */
    private void requestQueueRefresh() {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(stationPos, "refresh", 0));
    }

    @Override
    protected void init() {
        super.init();

        // Left panel content (existing widgets)
        int contentX = leftPos + 8;
        int contentY = topPos + headerHeight + 4;
        int contentW = LEFT_PW - 16;

        // Recipe list
        int listH = PH - headerHeight - 4 - 44;
        recipeList = new ScrollableList<>(contentX, contentY, contentW, listH, 22) {
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
                if (isLocked) {
                    g.drawString(Minecraft.getInstance().font, "🔒", textX, y + 1, MedievalColors.TEXT_DIM);
                    textX += 14;
                }
                String itemFallback = (registryItem != null && registryItem != Items.AIR)
                        ? new ItemStack(registryItem).getHoverName().getString()
                        : item.outputItem();
                Component recipeName = com.wsteam.wandscape.shared.ui.I18n.name(
                        "craft_recipe.wandscape." + item.recipeId(), itemFallback);
                g.drawString(Minecraft.getInstance().font, recipeName, textX, y + 1, nameColor);

                // Requirement / cost row
                String reason = item.lockedReason();
                if ("colony".equals(reason)) {
                    StringBuilder costStr = new StringBuilder("🔒 ");
                    var req = item.unlockRequirement();
                    costStr.append(I18n.name("gui.wandscape.recipe.colony_level",
                            "Colony Lv>=%s", req.minColonyLevel()).getString());
                    g.drawString(Minecraft.getInstance().font, costStr.toString(),
                            x + 20, y + 12, MedievalColors.TEXT_DIM);
                } else {
                    drawElementCost(g, item.cost(), x + 20, y + 12);
                }
            }
        };
        recipeList.setItems(recipes);
        recipeList.setOnSelect(i -> updateSliderForRecipe(recipes.get(i)));
        addRenderableWidget(recipeList);

        // Quantity slider + submit
        int controlY = contentY + listH + 6;
        slider = new Slider(contentX, controlY, 120, 1, 1, 1, v -> {});
        addRenderableWidget(slider);

        addRenderableWidget(new MedievalButton(
                contentX + contentW - 70, controlY + 4, 70, 18,
                I18n.name("gui.wandscape.common.submit", "Submit"), this::onSubmit));

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

    private void updateSliderForRecipe(RecipeEntry entry) {
        if (entry == null) {
            slider.setMax(1);
            slider.setValue(1);
            return;
        }
        // Locked recipes (colony / elements) show max_affordable=0; keep slider at 1
        boolean locked = !"unlocked".equals(entry.lockedReason());
        int max = locked ? 1 : entry.maxAffordable();
        slider.setMax(Math.max(1, max));
        slider.setValue(Math.min(slider.getValue(), max));
    }

    private void onSubmit() {
        RecipeEntry sel = recipeList.getSelected();
        // Block submission when recipe is locked (colony / elements)
        if (sel == null || !"unlocked".equals(sel.lockedReason())) return;
        int qty = slider.getValue();
        PacketDistributor.sendToServer(new RequestProductionTaskPacket(
                stationPos, "craft_wand", sel.recipeId(), qty));
        // Refresh queue after submitting a new task
        requestQueueRefresh();
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

    /** Draw an element cost as [icon]xN (icon tinted per element, like the V-key panel). */
    private static void drawElementCost(GuiGraphics g, Map<ElementType, Long> cost, int x, int y) {
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
    }
}
