package com.wsteam.wandscape.production.client;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.building.network.TaskQueueModifyPacket;
import com.wsteam.wandscape.production.network.RequestProductionTaskPacket;
import com.wsteam.wandscape.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.production.network.WorkstationDataPacket.DecomposableEntry;
import com.wsteam.wandscape.production.network.WorkstationDataPacket.SynthesizeEntry;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.QuantitySlider;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.component.TabBar;
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

public class WorkstationScreen extends MedievalScreen {

    private static final int PW = 400;
    private static final int PH = 220;
    private static final int MAX_QTY = 64;
    // Left panel width (existing content)
    private static final int LEFT_PW = 240;
    // Right panel (TaskQueuePanel) — shorter, starts higher
    private static final int QUEUE_PW = 140;

    private BlockPos stationPos = BlockPos.ZERO;
    private int activeTab = 0;

    private List<DecomposableEntry> decomposableItems = new ArrayList<>();
    private List<SynthesizeEntry> synthesizeRecipes = new ArrayList<>();

    private TabBar tabBar;
    private ScrollableList<?> currentList;
    private ScrollableList<DecomposableEntry> decomposeList;
    private ScrollableList<SynthesizeEntry> synthesizeList;
    private QuantitySlider quantitySlider;
    private MedievalButton submitBtn;
    private TaskQueuePanel taskQueuePanel;

    public WorkstationScreen() {
        super(Component.literal("Workstation"), PW, PH);
        setTitleBar("Workstation");
    }

    public void updateData(WorkstationDataPacket packet) {
        this.stationPos = packet.stationPos();
        this.decomposableItems = packet.decomposableEntries();
        this.synthesizeRecipes = packet.synthesizeEntries();
        if (decomposeList != null) decomposeList.setItems(decomposableItems);
        if (synthesizeList != null) synthesizeList.setItems(synthesizeRecipes);
        // Reset slider on new data
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

        // Tab bar
        tabBar = new TabBar(contentX, contentY, contentW,
                List.of("Decompose", "Synthesize"), activeTab, this::onTabChanged);
        addRenderableWidget(tabBar);

        // Lists
        int listY = contentY + 20;
        int listH = PH - headerHeight - 4 - 20 - 44;

        decomposeList = new ScrollableList<>(contentX, listY, contentW, listH, 20) {
            @Override
            protected void renderRow(GuiGraphics g, DecomposableEntry item, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                boolean canAfford = item.count() > 0;
                var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.itemId()));
                if (registryItem != null && registryItem != Items.AIR) {
                    g.renderItem(new ItemStack(registryItem), x, y + 1);
                }
                int textColor = !canAfford ? MedievalColors.TEXT_DIM
                        : selected ? MedievalColors.ACCENT_GOLD
                        : hovered ? MedievalColors.TEXT_WARM_WHITE
                        : MedievalColors.TEXT_MUTED;
                String name = formatItemName(item.itemId());
                g.drawString(Minecraft.getInstance().font, name, x + 20, y + 2, textColor);
                String count = "x" + formatCount(item.count());
                int cw = Minecraft.getInstance().font.width(count);
                g.drawString(Minecraft.getInstance().font, count,
                        x + getWidth() - scrollbarWidth - cw - 6, y + 2, MedievalColors.TEXT_DIM);
            }
        };
        decomposeList.setItems(decomposableItems);
        decomposeList.setOnSelect(i -> updateSliderForDecompose(decomposableItems.get(i)));

        synthesizeList = new ScrollableList<>(contentX, listY, contentW, listH, 20) {
            @Override
            protected void renderRow(GuiGraphics g, SynthesizeEntry item, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                boolean locked = item.maxAffordable() == 0
                        && item.unlockRequirement() != com.wsteam.wandscape.production.data.RecipeUnlockRequirement.NONE;
                boolean canAfford = !locked && item.maxAffordable() > 0;

                var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.outputItem()));
                if (registryItem != null && registryItem != Items.AIR) {
                    if (locked) {
                        // Dim the item icon for locked recipes
                        g.renderItem(new ItemStack(registryItem), x, y + 1);
                    } else {
                        g.renderItem(new ItemStack(registryItem), x, y + 1);
                    }
                }

                // Name row
                int nameColor;
                if (locked) {
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
                if (locked) {
                    // Show 🔒 lock symbol before name
                    g.drawString(Minecraft.getInstance().font, "🔒", textX, y + 1, MedievalColors.TEXT_DIM);
                    textX += 14;
                }
                g.drawString(Minecraft.getInstance().font, formatItemName(item.outputItem()),
                        textX, y + 1, nameColor);

                // Requirement / cost row
                StringBuilder costStr = new StringBuilder();
                if (locked) {
                    costStr.append("🔒 ");
                    var req = item.unlockRequirement();
                    if (req.minComfort() > 0) costStr.append("C>=").append(req.minComfort()).append(" ");
                    if (req.minMagic()   > 0) costStr.append("M>=").append(req.minMagic()).append(" ");
                    if (req.minWonder()  > 0) costStr.append("W>=").append(req.minWonder());
                } else {
                    item.cost().forEach((elem, amt) -> {
                        if (!costStr.isEmpty()) costStr.append(", ");
                        costStr.append(elem.name().toLowerCase()).append(":").append(amt);
                    });
                }
                g.drawString(Minecraft.getInstance().font, costStr.toString(),
                        x + 20, y + 10, MedievalColors.TEXT_DIM);
            }
        };
        synthesizeList.setItems(synthesizeRecipes);
        synthesizeList.setOnSelect(i -> updateSliderForSynthesize(synthesizeRecipes.get(i)));

        // Quantity slider + submit
        int controlY = listY + listH + 6;
        quantitySlider = new QuantitySlider(contentX, controlY, 120, 1, 1, 1, v -> {});
        addRenderableWidget(quantitySlider);

        submitBtn = new MedievalButton(contentX + contentW - 70, controlY + 4, 70, 18,
                Component.literal("Submit"), this::onSubmit);
        addRenderableWidget(submitBtn);

        // Show active tab
        showTab(activeTab);

        // ── Right panel: Task Queue ──
        // Shorter panel: header + 4px top + 4px bottom = 8px total vertical padding (was 12px)
        int queuePh = PH - headerHeight - 8;
        int queueX = leftPos + LEFT_PW + 4;
        int queueY = topPos + headerHeight + 4;
        taskQueuePanel = new TaskQueuePanel(queueX, queueY, QUEUE_PW, queuePh);
        taskQueuePanel.setOnDelete(this::onQueueDelete);
        taskQueuePanel.setOnMoveUp(this::onQueueMoveUp);
        taskQueuePanel.setOnMoveDown(this::onQueueMoveDown);
        addRenderableWidget(taskQueuePanel);
    }

    private void updateSliderForDecompose(DecomposableEntry entry) {
        if (entry == null) {
            quantitySlider.setMax(1);
            quantitySlider.setValue(1);
            return;
        }
        int max = (int) Math.min(entry.count(), MAX_QTY);
        quantitySlider.setMax(Math.max(1, max));
        quantitySlider.setValue(Math.min(quantitySlider.getValue(), max));
    }

    private void updateSliderForSynthesize(SynthesizeEntry entry) {
        if (entry == null) {
            quantitySlider.setMax(1);
            quantitySlider.setValue(1);
            return;
        }
        // Locked recipes show max_affordable=0 from server; keep slider at 1
        boolean locked = entry.unlockRequirement() != com.wsteam.wandscape.production.data.RecipeUnlockRequirement.NONE
                && entry.maxAffordable() == 0;
        int max = locked ? 1 : entry.maxAffordable();
        quantitySlider.setMax(Math.max(1, max));
        quantitySlider.setValue(Math.min(quantitySlider.getValue(), max));
    }

    private void onTabChanged(int tabIndex) {
        activeTab = tabIndex;
        showTab(tabIndex);
        // Reset slider for new tab
        quantitySlider.setMax(1);
        quantitySlider.setValue(1);
    }

    private void showTab(int tabIndex) {
        if (currentList != null) {
            removeWidget(currentList);
        }
        currentList = (tabIndex == 0) ? decomposeList : synthesizeList;
        addRenderableWidget(currentList);
    }

    private void onSubmit() {
        int qty = quantitySlider.getValue();
        if (activeTab == 0) {
            DecomposableEntry sel = decomposeList.getSelected();
            if (sel == null || sel.count() <= 0) return;
            PacketDistributor.sendToServer(new RequestProductionTaskPacket(
                    stationPos, "decompose", sel.itemId(), qty));
        } else {
            SynthesizeEntry sel = synthesizeList.getSelected();
            if (sel == null || sel.maxAffordable() <= 0) return;
            PacketDistributor.sendToServer(new RequestProductionTaskPacket(
                    stationPos, "synthesize", sel.recipeId(), qty));
        }
        // Refresh queue after submitting a new task
        requestQueueRefresh();
    }

    // ── Task queue callbacks ──

    private void onQueueDelete(int index) {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        com.mojang.logging.LogUtils.getLogger().info("[TaskQueue] DELETE index={} pos={}", index, stationPos);
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(stationPos, "delete", index));
    }

    private void onQueueMoveUp(int index) {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        com.mojang.logging.LogUtils.getLogger().info("[TaskQueue] MOVE_UP index={} pos={}", index, stationPos);
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(stationPos, "move_up", index));
    }

    private void onQueueMoveDown(int index) {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        com.mojang.logging.LogUtils.getLogger().info("[TaskQueue] MOVE_DOWN index={} pos={}", index, stationPos);
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(stationPos, "move_down", index));
    }

    private static String formatItemName(String itemId) {
        int colon = itemId.indexOf(':');
        String path = colon >= 0 ? itemId.substring(colon + 1) : itemId;
        return path.replace('_', ' ');
    }

    private static String formatCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }
}
