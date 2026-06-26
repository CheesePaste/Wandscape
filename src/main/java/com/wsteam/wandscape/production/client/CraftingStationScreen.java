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
import com.wsteam.wandscape.shared.ui.component.Slider;
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
    private Slider slider;
    private TaskQueuePanel taskQueuePanel;

    public CraftingStationScreen() {
        super(Component.literal("Crafting Station"), PW, PH);
        setTitleBar("Crafting Station");
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
                g.drawString(Minecraft.getInstance().font, formatItemName(item.outputItem()),
                        textX, y + 1, nameColor);

                // Requirement / cost row
                StringBuilder costStr = new StringBuilder();
                String reason = item.lockedReason();
                if ("colony".equals(reason)) {
                    costStr.append("🔒 ");
                    var req = item.unlockRequirement();
                    if (req.minComfort() > 0) costStr.append("C>=").append(req.minComfort()).append(" ");
                    if (req.minMagic()   > 0) costStr.append("M>=").append(req.minMagic()).append(" ");
                    if (req.minWonder()  > 0) costStr.append("W>=").append(req.minWonder());
                } else if ("wand_level".equals(reason)) {
                    costStr.append("🔒 ");
                    if (item.wandLevel() != null) {
                        for (var e : item.wandLevel().entrySet()) {
                            if (!costStr.isEmpty() && costStr.charAt(costStr.length() - 1) != ' ') costStr.append(" ");
                            costStr.append(e.getKey().toUpperCase()).append(":").append(e.getValue());
                        }
                    }
                } else {
                    item.cost().forEach((elem, amt) -> {
                        if (!costStr.isEmpty()) costStr.append(", ");
                        costStr.append(elem.name().toLowerCase()).append(":").append(amt);
                    });
                }
                g.drawString(Minecraft.getInstance().font, costStr.toString(),
                        x + 20, y + 12, MedievalColors.TEXT_DIM);
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
            slider.setMax(1);
            slider.setValue(1);
            return;
        }
        // Locked recipes (colony / elements / wand_level) show max_affordable=0; keep slider at 1
        boolean locked = !"unlocked".equals(entry.lockedReason());
        int max = locked ? 1 : entry.maxAffordable();
        slider.setMax(Math.max(1, max));
        slider.setValue(Math.min(slider.getValue(), max));
    }

    private void onSubmit() {
        RecipeEntry sel = recipeList.getSelected();
        // Block submission when recipe is locked (colony / elements / wand_level)
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

    private static String formatItemName(String itemId) {
        int colon = itemId.indexOf(':');
        String path = colon >= 0 ? itemId.substring(colon + 1) : itemId;
        return path.replace('_', ' ');
    }
}
