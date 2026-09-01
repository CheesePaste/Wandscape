package com.wsteam.wandscape.content.production.client;

import com.wsteam.wandscape.content.building.network.OpenWarehousePacket;
import com.wsteam.wandscape.content.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.content.building.network.TaskQueueModifyPacket;
import com.wsteam.wandscape.content.items.SpellItem;
import com.wsteam.wandscape.content.production.network.MagicStationPacket;
import com.wsteam.wandscape.content.production.network.MagicStationPacket.SpellEntry;
import com.wsteam.wandscape.content.production.network.RequestProductionTaskPacket;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.ui.I18n;
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
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
public class MagicStationScreen extends MedievalScreen {

    private static final int PW = 400;
    private static final int PH = 220;
    private static final int LEFT_PW = 240;
    // Right panel (TaskQueuePanel) — narrower to stay inside the PW=400 window
    private static final int QUEUE_PW = 148;
    private static final int QUEUE_PH = PH - 28; // headerHeight (20) + padding (8)
    private BlockPos stationPos = BlockPos.ZERO;
    private List<SpellEntry> recipes = new ArrayList<>();
    private List<SpellEntry> filteredRecipes = new ArrayList<>();

    private ScrollableList<SpellEntry> recipeList;
    private SearchBox searchInput;
    private QuantityStepper stepper;
    private TaskQueuePanel taskQueuePanel;

    public MagicStationScreen() {
        super(Component.literal("Magic Station"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.magic_station.title", "Magic Station"));
        this.showCloseButton = true;
    }

    public void updateData(MagicStationPacket packet) {
        this.stationPos = packet.stationPos();
        setCreator(packet.creator());
        this.recipes = packet.entries();
        applySearch(searchInput != null ? searchInput.getValue() : "");
        if (stepper != null) {
            stepper.setTotalMax(1);
        }
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

        int contentX = leftPos + 8;
        int contentY = topPos + headerHeight + 4;
        int contentW = LEFT_PW - 16;

        int searchH = font.lineHeight + 6;
        searchInput = new SearchBox(font, contentX + 1, contentY + 2, contentW - 2,
                I18n.name("gui.wandscape.common.search", "Search"));
        searchInput.setResponder(this::applySearch);
        addRenderableWidget(searchInput);

        int listY = contentY + searchH + 4;
        int listH = PH - headerHeight - 4 - searchH - 4 - 44 - CREATOR_FOOTER_H - 4;
        recipeList = new ScrollableList<>(contentX, listY, contentW, listH, 22) {
            @Override
            protected void renderRow(GuiGraphics g, SpellEntry item, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                boolean isLocked = !"unlocked".equals(item.lockedReason());
                boolean canAfford = !isLocked && item.maxAffordable() > 0;

                ItemStack scroll = new ItemStack(BuiltInRegistries.ITEM.get(
                        ResourceLocation.tryParse(item.outputItem())), 1);
                if (!scroll.isEmpty()) {
                    g.renderItem(scroll, x, y + 2);
                }

                int nameColor;
                if (isLocked) {
                    nameColor = MedievalColors.TEXT_DIM;
                } else if (canAfford) {
                    nameColor = selected ? MedievalColors.ACCENT_GOLD
                            : hovered ? MedievalColors.TEXT_WARM_WHITE
                            : MedievalColors.TEXT_MUTED;
                } else {
                    nameColor = MedievalColors.TEXT_DIM;
                }

                int textX = x + 20;
                
                Component spellName = I18n.name(
                        "magic.wandscape." + item.magicId(), item.magicId());
                g.drawString(Minecraft.getInstance().font, spellName, textX, y + 1, nameColor);

                String reason = item.lockedReason();
                if ("colony".equals(reason)) {
                    StringBuilder costStr = new StringBuilder();
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
        recipeList.setOnSelect(i -> updateSliderForRecipe(filteredRecipes.get(i)));
        recipeList.setTooltipProvider((item, index) -> {
            ItemStack stack = ItemStackUtil.fromId(item.outputItem());
            // 实际产出的卷轴带 magic_id 绑定，tooltip 才会显示魔法名/耗蓝/冷却
            if (stack.getItem() instanceof SpellItem) {
                SpellItem.setMagicId(stack, item.magicId());
            }
            return stack;
        });
        addRenderableWidget(recipeList);

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
        filteredRecipes = SearchBox.filter(recipes, query, MagicStationScreen::recipeSearchText);
        if (recipeList != null) recipeList.setItems(filteredRecipes);
    }

    /** Searchable text for a spell: localized magic name + recipe/output ids. */
    private static String recipeSearchText(SpellEntry r) {
        String name = I18n.name("magic.wandscape." + r.magicId(), r.magicId()).getString();
        return name + " " + r.outputItem() + " " + r.recipeId();
    }

    private void updateSliderForRecipe(SpellEntry entry) {
        if (entry == null) {
            stepper.setTotalMax(1);
            return;
        }
        boolean locked = !"unlocked".equals(entry.lockedReason());
        int max = locked ? 1 : entry.maxAffordable();
        stepper.setTotalMax(max);
    }

    private void onSubmit() {
        SpellEntry sel = recipeList.getSelected();
        if (sel == null || !"unlocked".equals(sel.lockedReason())) return;
        int qty = stepper.getValue();
        PacketDistributor.sendToServer(new RequestProductionTaskPacket(
                stationPos, "craft_spell", sel.recipeId(), qty));
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