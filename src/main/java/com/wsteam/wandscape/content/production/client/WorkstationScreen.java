package com.wsteam.wandscape.content.production.client;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.building.network.OpenWarehousePacket;
import com.wsteam.wandscape.content.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.content.building.network.TaskQueueModifyPacket;
import com.wsteam.wandscape.content.production.network.RequestProductionTaskPacket;
import com.wsteam.wandscape.content.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.content.production.network.WorkstationDataPacket.DecomposableEntry;
import com.wsteam.wandscape.content.production.network.WorkstationDataPacket.SynthesizeEntry;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.foundation.ui.component.*;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme;
import com.wsteam.wandscape.foundation.ui.util.ItemStackUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
public class WorkstationScreen extends MedievalScreen {

    private static final String TAG = "WorkstationScreen";

    private static final int PW = 400;
    private static final int PH = 220;
    // Left panel width (existing content)
    private static final int LEFT_PW = 240;
    // Right panel (TaskQueuePanel) — narrower to stay inside the PW=400 window
    private static final int QUEUE_PW = 148;

    private BlockPos stationPos = BlockPos.ZERO;
    private int activeTab = 0;

    private List<DecomposableEntry> decomposableItems = new ArrayList<>();
    private List<SynthesizeEntry> synthesizeRecipes = new ArrayList<>();
    private List<DecomposableEntry> decomposeFiltered = new ArrayList<>();
    private List<SynthesizeEntry> synthesizeFiltered = new ArrayList<>();

    private TabBar tabBar;
    private SearchBox searchInput;
    private ScrollableList<?> currentList;
    private ScrollableList<DecomposableEntry> decomposeList;
    private ScrollableList<SynthesizeEntry> synthesizeList;
    private QuantityStepper stepper;
    private MedievalButton submitBtn;
    private TaskQueuePanel taskQueuePanel;

    public WorkstationScreen() {
        super(Component.literal("Workstation"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.workstation.title", "Workstation"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "workstation_guide";
        this.isBuildingScreen = true;
    }

    public void updateData(WorkstationDataPacket packet) {
        this.stationPos = packet.stationPos();
        setCreator(packet.creator());
        setBuildingContext(null, packet.stationPos());
        this.decomposableItems = packet.decomposableEntries();
        this.synthesizeRecipes = packet.synthesizeEntries();
        // Re-apply the current search filter to the refreshed data
        applySearch(searchInput != null ? searchInput.getValue() : "");
        // Reset slider on new data
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
                        qe.blueprintId(), qe.summary(), qe.insufficient(), qe.missingElements(),
                        qe.capacityBlocked()));
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
                            e.quantity(), e.blueprintId(), e.summary(), false, List.of(), false),
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

        // Tab bar
        tabBar = new TabBar(contentX, contentY, contentW,
                List.of(
                        I18n.name("gui.wandscape.workstation.decompose", "Decompose").getString(),
                        I18n.name("gui.wandscape.workstation.synthesize", "Synthesize").getString()),
                activeTab, this::onTabChanged);
        addRenderableWidget(tabBar);

        // Search box between tabs and list (warehouse-style inset field)
        int searchH = font.lineHeight + 6;
        searchInput = new SearchBox(font, contentX + 1, contentY + 20 + 2, contentW - 2,
                I18n.name("gui.wandscape.common.search", "Search"));
        searchInput.setResponder(this::applySearch);
        addRenderableWidget(searchInput);

        // Lists — shrink by the creator footer strip so the slider/submit row stays clear
        int listY = contentY + 20 + searchH + 4;
        int listH = PH - headerHeight - 4 - (20 + searchH + 4) - 44 - CREATOR_FOOTER_H - 4;

        decomposeList = new ScrollableList<>(contentX, listY, contentW, listH, 20) {
            @Override
            protected void renderRow(GuiGraphics g, DecomposableEntry item, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                boolean canAfford = item.count() > 0;
                ItemStack display = decomposeDisplay(item);
                if (!display.isEmpty()) {
                    g.renderItem(display, x, y + 1);
                }
                int textColor = !canAfford ? MedievalColors.TEXT_DIM
                        : selected ? MedievalColors.ACCENT_GOLD
                        : hovered ? MedievalColors.TEXT_WARM_WHITE
                        : MedievalColors.TEXT_MUTED;
                Component name = !display.isEmpty()
                        ? display.getHoverName()
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
        decomposeList.setTooltipProvider((item, index) -> decomposeDisplay(item));

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
                
                Component recipeName = (registryItem != null && registryItem != Items.AIR)
                        ? new ItemStack(registryItem).getHoverName()
                        : Component.literal(item.outputItem());
                g.drawString(Minecraft.getInstance().font, recipeName, textX, y + 1, nameColor);

                // Requirement / cost row
                String reason = item.lockedReason();
                if ("colony".equals(reason)) {
                    StringBuilder costStr = new StringBuilder();
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
        synthesizeList.setTooltipProvider((item, index) -> ItemStackUtil.fromId(item.outputItem()));

        // Quantity slider + submit
        int controlY = listY + listH + 6;
        stepper = new QuantityStepper(contentX, controlY);
        addRenderableWidget(stepper.slider());
        addRenderableWidget(stepper.minusBtn());
        addRenderableWidget(stepper.plusBtn());

        submitBtn = new MedievalButton(contentX + contentW - 70, controlY + 4, 70, 18,
                I18n.name("gui.wandscape.common.submit", "Submit"), this::onSubmit);
        addRenderableWidget(submitBtn);

        // Open the colony warehouse to view remaining element counts and stored items
        addRenderableWidget(new MedievalButton(contentX + contentW - 70, controlY + 24, 70, 18,
                I18n.name("gui.wandscape.common.open_warehouse", "Open Warehouse"), this::onOpenWarehouse));

        // Show active tab
        showTab(activeTab);
        applySearch(searchInput.getValue());

        // ── Right panel: Task Queue ──
        // Shorter panel: leaves footer space for action buttons
        int queuePh = PH - headerHeight - 8 - 24;
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
            stepper.setTotalMax(1);
            return;
        }
        stepper.setTotalMax((int) Math.min(entry.count(), Integer.MAX_VALUE));
    }

    private void updateSliderForSynthesize(SynthesizeEntry entry) {
        if (entry == null) {
            stepper.setTotalMax(1);
            return;
        }
        // Locked recipes (colony / elements) show max_affordable=0; keep slider at 1
        boolean locked = !"unlocked".equals(entry.lockedReason());
        int max = locked ? 1 : entry.maxAffordable();
        stepper.setTotalMax(max);
    }

    private void onTabChanged(int tabIndex) {
        activeTab = tabIndex;
        showTab(tabIndex);
        // Re-apply search to the newly shown tab
        applySearch(searchInput.getValue());
        // Reset slider for new tab
        stepper.setTotalMax(1);
    }

    private void showTab(int tabIndex) {
        if (currentList != null) {
            removeWidget(currentList);
        }
        currentList = (tabIndex == 0) ? decomposeList : synthesizeList;
        addRenderableWidget(currentList);
    }

    @Override
    protected void renderForeground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 悬停列表行时在光标处显示标准物品 tooltip（与物品栏一致，置于所有控件之上）
        ItemStack tooltip = currentList != null ? currentList.hoveredTooltipStack() : null;
        if (tooltip != null) {
            g.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private void onSubmit() {
        int qty = stepper.getValue();
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

    /** Open the colony warehouse to check remaining element counts. */
    private void onOpenWarehouse() {
        if (stationPos == null || stationPos.equals(BlockPos.ZERO)) return;
        PacketDistributor.sendToServer(new OpenWarehousePacket(stationPos));
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
        decomposeFiltered = SearchBox.filter(decomposableItems, query, WorkstationScreen::decomposeSearchText);
        if (decomposeList != null) decomposeList.setItems(decomposeFiltered);
        synthesizeFiltered = SearchBox.filter(synthesizeRecipes, query, WorkstationScreen::synthesizeSearchText);
        if (synthesizeList != null) synthesizeList.setItems(synthesizeFiltered);
    }

    /** Searchable text for a decomposable item: localized name + raw id. */
    private static String decomposeSearchText(DecomposableEntry d) {
        return SearchBox.itemSearchText(d.itemId());
    }

    /** Searchable text for a synthesize recipe: localized name + output/recipe ids. */
    private static String synthesizeSearchText(SynthesizeEntry s) {
        return SearchBox.itemSearchText(s.outputItem()) + " " + s.recipeId();
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
     * Draw the per-item decompose yield as [icon]xY.Z — 1/decomposeDivisor of the item's
     * element value. The divisor comes from the server-side Config (default 5 = 1/5).
     */
    private static void drawElementYield(GuiGraphics g, Map<ElementType, Long> value, int x, int y) {
        if (value == null || value.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        double divisor = Config.ELEMENT_DECOMPOSE_DIVISOR.get();
        int cx = x;
        for (var e : value.entrySet()) {
            String id = e.getKey().getId();
            int tint = WandscapeTheme.elementColor(id);
            WandscapeTheme.drawIcon(g, WandscapeTheme.elementIcon(id), cx, y - 2, 9, 9, tint);
            cx += 11;
            String text = "x" + String.format("%.1f", e.getValue() / divisor);
            g.drawString(font, text, cx, y, tint);
            cx += font.width(text) + 6;
        }
    }

    private static String formatCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }

    /** 分解列表图标/悬停：仓库载荷是完整物品序列化（ItemKey 语义），解码还原全组件。 */
    private static ItemStack decomposeDisplay(DecomposableEntry entry) {
        var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.itemId()));
        if (registryItem == null || registryItem == Items.AIR) return ItemStack.EMPTY;
        var level = Minecraft.getInstance().level;
        if (level == null) return new ItemStack(registryItem, 1);
        return ItemKey.of(entry.itemId(), entry.nbt()).toStack(1, level.registryAccess());
    }
}
