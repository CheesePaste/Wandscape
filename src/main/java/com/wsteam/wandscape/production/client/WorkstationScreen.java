package com.wsteam.wandscape.production.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.building.network.TaskQueueModifyPacket;
import com.wsteam.wandscape.production.network.RequestProductionTaskPacket;
import com.wsteam.wandscape.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.production.network.WorkstationDataPacket.DecomposableEntry;
import com.wsteam.wandscape.production.network.WorkstationDataPacket.SynthesizeEntry;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.Slider;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.component.TabBar;
import com.wsteam.wandscape.shared.ui.component.TaskQueuePanel;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.shared.ui.theme.WandscapeTheme;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
public class WorkstationScreen extends MedievalScreen {

    private static final String TAG = "WorkstationScreen";

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
    private List<DecomposableEntry> decomposeFiltered = new ArrayList<>();
    private List<SynthesizeEntry> synthesizeFiltered = new ArrayList<>();

    private TabBar tabBar;
    private EditBox searchInput;
    private ScrollableList<?> currentList;
    private ScrollableList<DecomposableEntry> decomposeList;
    private ScrollableList<SynthesizeEntry> synthesizeList;
    private Slider slider;
    private MedievalButton submitBtn;
    private TaskQueuePanel taskQueuePanel;

    public WorkstationScreen() {
        super(Component.literal("Workstation"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.workstation.title", "Workstation"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "workstation_guide";
    }

    public void updateData(WorkstationDataPacket packet) {
        this.stationPos = packet.stationPos();
        this.decomposableItems = packet.decomposableEntries();
        this.synthesizeRecipes = packet.synthesizeEntries();
        // Re-apply the current search filter to the refreshed data
        applySearch(searchInput != null ? searchInput.getValue() : "");
        // Reset slider on new data
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
            taskQueuePanel.setCurrent(toPanelCurrent(packet.current()));
        }
    }

    /** Convert the packet's current-task record to the panel's CurrentInfo (or null). */
    private static TaskQueuePanel.CurrentInfo toPanelCurrent(TaskQueueDataPacket.CurrentTask ct) {
        if (ct == null) return null;
        TaskQueueDataPacket.QueueEntry e = ct.entry();
        return new TaskQueuePanel.CurrentInfo(
                new TaskQueuePanel.Entry(e.index(), e.category(), e.itemOrRecipeId(),
                        e.quantity(), e.blueprintId(), e.summary()),
                ct.stepIndex(), ct.totalSteps(),
                ct.channelRemainingTicks(), ct.channelTotalTicks());
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

        // Tab bar
        tabBar = new TabBar(contentX, contentY, contentW,
                List.of(
                        I18n.name("gui.wandscape.workstation.decompose", "Decompose").getString(),
                        I18n.name("gui.wandscape.workstation.synthesize", "Synthesize").getString()),
                activeTab, this::onTabChanged);
        addRenderableWidget(tabBar);

        // Search box between tabs and list (warehouse-style inset field)
        int searchH = font.lineHeight + 6;
        searchInput = new EditBox(font, contentX + 1, contentY + 20 + 2, contentW - 2, font.lineHeight,
                I18n.name("gui.wandscape.common.search", "Search")) {
            @Override
            public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                drawInsetField(g, getX() - 1, getY() - 2, getWidth() + 2, getHeight() + 4);
                super.renderWidget(g, mouseX, mouseY, partialTick);
            }
        };
        searchInput.setBordered(false);
        searchInput.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        searchInput.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        searchInput.setHint(I18n.name("gui.wandscape.common.search", "Search"));
        searchInput.setCanLoseFocus(true);
        searchInput.setResponder(this::applySearch);
        addRenderableWidget(searchInput);

        // Lists
        int listY = contentY + 20 + searchH + 4;
        int listH = PH - headerHeight - 4 - (20 + searchH + 4) - 44;

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
                Component name = (registryItem != null && registryItem != Items.AIR)
                        ? new ItemStack(registryItem).getHoverName()
                        : Component.literal(item.itemId());
                g.drawString(Minecraft.getInstance().font, name, x + 20, y + 2, textColor);
                String count = "x" + formatCount(item.count());
                int cw = Minecraft.getInstance().font.width(count);
                g.drawString(Minecraft.getInstance().font, count,
                        x + getWidth() - scrollbarWidth - cw - 6, y + 2, MedievalColors.TEXT_DIM);
                drawElementYield(g, item.elementValue(), x + 20, y + 10);
            }
        };
        decomposeList.setOnSelect(i -> updateSliderForDecompose(decomposeFiltered.get(i)));

        synthesizeList = new ScrollableList<>(contentX, listY, contentW, listH, 20) {
            @Override
            protected void renderRow(GuiGraphics g, SynthesizeEntry item, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                boolean isLocked = !"unlocked".equals(item.lockedReason());
                boolean canAfford = !isLocked && item.maxAffordable() > 0;

                var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.outputItem()));
                if (registryItem != null && registryItem != Items.AIR) {
                    g.renderItem(new ItemStack(registryItem), x, y + 1);
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
                Component recipeName = (registryItem != null && registryItem != Items.AIR)
                        ? new ItemStack(registryItem).getHoverName()
                        : Component.literal(item.outputItem());
                g.drawString(Minecraft.getInstance().font, recipeName, textX, y + 1, nameColor);

                // Requirement / cost row
                String reason = item.lockedReason();
                if ("colony".equals(reason)) {
                    StringBuilder costStr = new StringBuilder("🔒 ");
                    var req = item.unlockRequirement();
                    costStr.append(I18n.name("gui.wandscape.recipe.colony_level",
                            "Colony Lv>=%s", req.minColonyLevel()).getString());
                    g.drawString(Minecraft.getInstance().font, costStr.toString(),
                            x + 20, y + 10, MedievalColors.TEXT_DIM);
                } else {
                    drawElementCost(g, item.cost(), x + 20, y + 10);
                }
            }
        };
        synthesizeList.setOnSelect(i -> updateSliderForSynthesize(synthesizeFiltered.get(i)));

        // Quantity slider + submit
        int controlY = listY + listH + 6;
        slider = new Slider(contentX, controlY, 120, 1, 1, 1, v -> {});
        addRenderableWidget(slider);

        submitBtn = new MedievalButton(contentX + contentW - 70, controlY + 4, 70, 18,
                I18n.name("gui.wandscape.common.submit", "Submit"), this::onSubmit);
        addRenderableWidget(submitBtn);

        // Show active tab
        showTab(activeTab);
        applySearch(searchInput.getValue());

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
            slider.setMax(1);
            slider.setValue(1);
            return;
        }
        int max = (int) Math.min(entry.count(), MAX_QTY);
        slider.setMax(Math.max(1, max));
        slider.setValue(Math.min(slider.getValue(), max));
    }

    private void updateSliderForSynthesize(SynthesizeEntry entry) {
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

    private void onTabChanged(int tabIndex) {
        activeTab = tabIndex;
        showTab(tabIndex);
        // Re-apply search to the newly shown tab
        applySearch(searchInput.getValue());
        // Reset slider for new tab
        slider.setMax(1);
        slider.setValue(1);
    }

    private void showTab(int tabIndex) {
        if (currentList != null) {
            removeWidget(currentList);
        }
        currentList = (tabIndex == 0) ? decomposeList : synthesizeList;
        addRenderableWidget(currentList);
    }

    private void onSubmit() {
        int qty = slider.getValue();
        if (activeTab == 0) {
            DecomposableEntry sel = decomposeList.getSelected();
            if (sel == null || sel.count() <= 0) return;
            PacketDistributor.sendToServer(new RequestProductionTaskPacket(
                    stationPos, "decompose", sel.itemId(), qty));
        } else {
            SynthesizeEntry sel = synthesizeList.getSelected();
            // Block submission when recipe is locked (colony / elements)
            if (sel == null || !"unlocked".equals(sel.lockedReason())) return;
            PacketDistributor.sendToServer(new RequestProductionTaskPacket(
                    stationPos, "synthesize", sel.recipeId(), qty));
        }
        // Refresh queue after submitting a new task
        requestQueueRefresh();
    }

    // ── Task queue callbacks ──

    private void onQueueDelete(int index) {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        Log.info(TAG,"[TaskQueue] DELETE index={} pos={}", index, stationPos);
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(stationPos, "delete", index));
    }

    private void onQueueMoveUp(int index) {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        Log.info(TAG,"[TaskQueue] MOVE_UP index={} pos={}", index, stationPos);
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(stationPos, "move_up", index));
    }

    private void onQueueMoveDown(int index) {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        Log.info(TAG,"[TaskQueue] MOVE_DOWN index={} pos={}", index, stationPos);
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(stationPos, "move_down", index));
    }

    /** Filter both lists by the search query, keeping the lists in sync with selection indexes. */
    private void applySearch(String query) {
        String lower = (query == null ? "" : query.trim()).toLowerCase();
        decomposeFiltered = lower.isEmpty()
                ? new ArrayList<>(decomposableItems)
                : decomposableItems.stream()
                        .filter(d -> decomposeSearchText(d).toLowerCase().contains(lower))
                        .toList();
        if (decomposeList != null) decomposeList.setItems(decomposeFiltered);
        synthesizeFiltered = lower.isEmpty()
                ? new ArrayList<>(synthesizeRecipes)
                : synthesizeRecipes.stream()
                        .filter(s -> synthesizeSearchText(s).toLowerCase().contains(lower))
                        .toList();
        if (synthesizeList != null) synthesizeList.setItems(synthesizeFiltered);
    }

    /** Searchable text for a decomposable item: localized name + raw id. */
    private static String decomposeSearchText(DecomposableEntry d) {
        var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(d.itemId()));
        String name = (registryItem != null && registryItem != Items.AIR)
                ? new ItemStack(registryItem).getHoverName().getString()
                : d.itemId();
        return name + " " + d.itemId();
    }

    /** Searchable text for a synthesize recipe: localized name + output/recipe ids. */
    private static String synthesizeSearchText(SynthesizeEntry s) {
        var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(s.outputItem()));
        String name = (registryItem != null && registryItem != Items.AIR)
                ? new ItemStack(registryItem).getHoverName().getString()
                : s.outputItem();
        return name + " " + s.outputItem() + " " + s.recipeId();
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

    /**
     * Draw the per-item decompose yield as [icon]xY.Z — 1/DECOMPOSE_DIVISOR of the item's
     * element value. Integer value / 5 is always a multiple of 0.2, so one decimal is exact.
     */
    private static void drawElementYield(GuiGraphics g, Map<ElementType, Long> value, int x, int y) {
        if (value == null || value.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        int cx = x;
        for (var e : value.entrySet()) {
            String id = e.getKey().getId();
            int tint = WandscapeTheme.elementColor(id);
            WandscapeTheme.drawIcon(g, WandscapeTheme.elementIcon(id), cx, y - 2, 9, 9, tint);
            cx += 11;
            String text = "x" + String.format("%.1f", e.getValue() / (double) WandscapeConstants.DECOMPOSE_DIVISOR);
            g.drawString(font, text, cx, y, tint);
            cx += font.width(text) + 6;
        }
    }

    private static String formatCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }
}
