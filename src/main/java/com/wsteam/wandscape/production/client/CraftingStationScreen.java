package com.wsteam.wandscape.production.client;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.building.network.TaskQueueModifyPacket;
import com.wsteam.wandscape.production.network.CraftingStationPacket;
import com.wsteam.wandscape.production.network.CraftingStationPacket.RecipeEntry;
import com.wsteam.wandscape.production.network.RequestProductionTaskPacket;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.QuantitySlider;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.component.TaskQueuePanel;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

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
    private QuantitySlider quantitySlider;
    private TaskQueuePanel taskQueuePanel;

    public CraftingStationScreen() {
        super(Component.literal("Crafting Station"), PW, PH);
        setTitleBar("Crafting Station");
    }

    public void updateData(CraftingStationPacket packet) {
        this.stationPos = packet.stationPos();
        this.recipes = packet.entries();
        if (recipeList != null) recipeList.setItems(recipes);
        if (quantitySlider != null) {
            quantitySlider.setMax(1);
            quantitySlider.setValue(1);
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
                boolean canAfford = item.maxAffordable() > 0;
                var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.outputItem()));
                if (registryItem != null && registryItem != Items.AIR) {
                    g.renderItem(new ItemStack(registryItem), x, y + 2);
                }

                int textColor = !canAfford ? MedievalColors.TEXT_DIM
                        : selected ? MedievalColors.ACCENT_GOLD
                        : hovered ? MedievalColors.TEXT_WARM_WHITE
                        : MedievalColors.TEXT_MUTED;
                g.drawString(Minecraft.getInstance().font, formatItemName(item.outputItem()),
                        x + 20, y + 2, textColor);

                StringBuilder costStr = new StringBuilder();
                item.cost().forEach((elem, amt) -> {
                    if (!costStr.isEmpty()) costStr.append(", ");
                    costStr.append(elem.name().toLowerCase()).append(":").append(amt);
                });
                g.drawString(Minecraft.getInstance().font, costStr.toString(),
                        x + 20, y + 12, MedievalColors.TEXT_DIM);

                if (item.requiredLevel() > 1) {
                    String lvl = "Lv." + item.requiredLevel();
                    int lw = Minecraft.getInstance().font.width(lvl);
                    g.drawString(Minecraft.getInstance().font, lvl,
                            x + getWidth() - scrollbarWidth - lw - 6, y + 2,
                            MedievalColors.ACCENT_GOLD);
                }
            }
        };
        recipeList.setItems(recipes);
        recipeList.setOnSelect(i -> updateSliderForRecipe(recipes.get(i)));
        addRenderableWidget(recipeList);

        // Quantity slider + submit
        int controlY = contentY + listH + 6;
        quantitySlider = new QuantitySlider(contentX, controlY, 120, 1, 1, 1, v -> {});
        addRenderableWidget(quantitySlider);

        addRenderableWidget(new MedievalButton(
                contentX + contentW - 70, controlY + 4, 70, 18,
                Component.literal("Submit"), this::onSubmit));

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
            quantitySlider.setMax(1);
            quantitySlider.setValue(1);
            return;
        }
        int max = entry.maxAffordable();
        quantitySlider.setMax(Math.max(1, max));
        quantitySlider.setValue(Math.min(quantitySlider.getValue(), max));
    }

    private void onSubmit() {
        RecipeEntry sel = recipeList.getSelected();
        if (sel == null || sel.maxAffordable() <= 0) return;
        int qty = quantitySlider.getValue();
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

    private static String formatItemName(String itemId) {
        int colon = itemId.indexOf(':');
        String path = colon >= 0 ? itemId.substring(colon + 1) : itemId;
        return path.replace('_', ' ');
    }
}
