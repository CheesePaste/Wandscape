package com.wsteam.wandscape.production.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.building.network.TaskQueueModifyPacket;
import com.wsteam.wandscape.production.network.MagicStationPacket;
import com.wsteam.wandscape.production.network.MagicStationPacket.SpellEntry;
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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.network.PacketDistributor;
public class MagicStationScreen extends MedievalScreen {

    private static final int PW = 400;
    private static final int PH = 220;
    private static final int LEFT_PW = 240;
    private static final int QUEUE_PW = 140;
    private static final int QUEUE_PH = PH - 28; // headerHeight (20) + padding (8)
    private BlockPos stationPos = BlockPos.ZERO;
    private List<SpellEntry> recipes = new ArrayList<>();
    private List<SpellEntry> filteredRecipes = new ArrayList<>();

    private ScrollableList<SpellEntry> recipeList;
    private EditBox searchInput;
    private Slider slider;
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
        if (slider != null) {
            slider.setMax(1);
            slider.setValue(1);
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
                        qe.blueprintId(), qe.summary()));
            }
            taskQueuePanel.setEntries(entries);
            taskQueuePanel.setCurrent(toPanelCurrent(packet.current()));
        }
    }

    private static TaskQueuePanel.CurrentInfo toPanelCurrent(TaskQueueDataPacket.CurrentTask ct) {
        if (ct == null) return null;
        TaskQueueDataPacket.QueueEntry e = ct.entry();
        return new TaskQueuePanel.CurrentInfo(
                new TaskQueuePanel.Entry(e.index(), e.category(), e.itemOrRecipeId(),
                        e.quantity(), e.blueprintId(), e.summary()),
                ct.stepIndex(), ct.totalSteps(),
                ct.channelRemainingTicks(), ct.channelTotalTicks(),
                ct.pending());
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
        searchInput = new EditBox(font, contentX + 1, contentY + 2, contentW - 2, font.lineHeight,
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
                if (isLocked) {
                    g.drawString(Minecraft.getInstance().font, "🔒", textX, y + 1, MedievalColors.TEXT_DIM);
                    textX += 14;
                }
                Component spellName = I18n.name(
                        "magic.wandscape." + item.magicId(), item.magicId());
                g.drawString(Minecraft.getInstance().font, spellName, textX, y + 1, nameColor);

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
        recipeList.setOnSelect(i -> updateSliderForRecipe(filteredRecipes.get(i)));
        addRenderableWidget(recipeList);

        int controlY = listY + listH + 6;
        slider = new Slider(contentX, controlY, 120, 1, 1, 1, v -> {});
        addRenderableWidget(slider);

        addRenderableWidget(new MedievalButton(
                contentX + contentW - 70, controlY + 4, 70, 18,
                I18n.name("gui.wandscape.common.submit", "Submit"), this::onSubmit));

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

    /** Filter the recipe list by the search query, keeping it in sync with selection indexes. */
    private void applySearch(String query) {
        String lower = (query == null ? "" : query.trim()).toLowerCase();
        filteredRecipes = lower.isEmpty()
                ? new ArrayList<>(recipes)
                : recipes.stream()
                        .filter(r -> recipeSearchText(r).toLowerCase().contains(lower))
                        .toList();
        if (recipeList != null) recipeList.setItems(filteredRecipes);
    }

    /** Searchable text for a spell: localized magic name + recipe/output ids. */
    private static String recipeSearchText(SpellEntry r) {
        String name = I18n.name("magic.wandscape." + r.magicId(), r.magicId()).getString();
        return name + " " + r.outputItem() + " " + r.recipeId();
    }

    private void updateSliderForRecipe(SpellEntry entry) {
        if (entry == null) {
            slider.setMax(1);
            slider.setValue(1);
            return;
        }
        boolean locked = !"unlocked".equals(entry.lockedReason());
        int max = locked ? 1 : entry.maxAffordable();
        slider.setMax(Math.max(1, max));
        slider.setValue(Math.min(slider.getValue(), max));
    }

    private void onSubmit() {
        SpellEntry sel = recipeList.getSelected();
        if (sel == null || !"unlocked".equals(sel.lockedReason())) return;
        int qty = slider.getValue();
        PacketDistributor.sendToServer(new RequestProductionTaskPacket(
                stationPos, "craft_spell", sel.recipeId(), qty));
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