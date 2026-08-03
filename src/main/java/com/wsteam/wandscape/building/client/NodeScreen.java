package com.wsteam.wandscape.building.client;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.building.network.NodeDataPacket;
import com.wsteam.wandscape.building.network.RequestGatherTaskPacket;
import com.wsteam.wandscape.building.network.TaskQueueDataPacket;
import com.wsteam.wandscape.building.network.TaskQueueModifyPacket;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.Slider;
import com.wsteam.wandscape.shared.ui.component.TaskQueuePanel;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Right-click panel for node (gather) buildings.
 * Lets the player publish a gather task (harvest count via slider) and
 * cancel queued gather tasks via the {@link TaskQueuePanel}.
 */
public class NodeScreen extends MedievalScreen {

    private static final String TAG = "NodeScreen";

    private static final int PW = 400;
    private static final int PH = 220;
    private static final int LEFT_PW = 240;
    private static final int QUEUE_PW = 140;
    private static final int MAX_HARVESTS = 10;
    private static final int INFO_ROWS = 4;
    private static final int INFO_ROW_H = 12;

    private BlockPos nodePos = BlockPos.ZERO;
    private String element = "";
    private int amountPerHarvest = 1;
    private int channelTicks = 0;
    private int manaCost = 0;

    private int contentX;
    private int controlY;
    private Slider slider;
    private MedievalButton submitBtn;
    private TaskQueuePanel taskQueuePanel;

    public NodeScreen() {
        super(Component.literal("Node"), PW, PH);
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "node_guide";
    }

    public void updateData(NodeDataPacket packet) {
        this.nodePos = packet.nodePos();
        this.element = packet.element();
        this.amountPerHarvest = packet.amountPerHarvest();
        this.channelTicks = packet.channelTicks();
        this.manaCost = packet.manaCost();
        setTitleBar(com.wsteam.wandscape.shared.ui.I18n.name(
                "building.wandscape." + packet.buildingTypeId(), packet.buildingTypeId()));
        if (slider != null) {
            slider.setMax(MAX_HARVESTS);
            slider.setValue(1);
        }
        requestQueueRefresh();
    }

    /** Called when a TaskQueueDataPacket arrives from the server. */
    public void updateQueueData(TaskQueueDataPacket packet) {
        if (packet.stationPos().equals(this.nodePos) && taskQueuePanel != null) {
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
        if (nodePos == null || nodePos.equals(BlockPos.ZERO)) return;
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(nodePos, "refresh", 0));
    }

    @Override
    protected void init() {
        super.init();

        contentX = leftPos + 8;
        int contentY = topPos + headerHeight + 4;
        int contentW = LEFT_PW - 16;

        controlY = contentY + INFO_ROWS * INFO_ROW_H + 8;

        // Harvest-count slider + publish button
        slider = new Slider(contentX, controlY, 120, 1, MAX_HARVESTS, 1, v -> {});
        addRenderableWidget(slider);

        submitBtn = new MedievalButton(contentX + contentW - 70, controlY + 4, 70, 18,
                com.wsteam.wandscape.shared.ui.I18n.name("gui.wandscape.node.publish_gather", "Publish Gather"),
                this::onSubmit);
        addRenderableWidget(submitBtn);

        // ── Right panel: Task Queue (cancel / reorder gather tasks) ──
        int queuePh = PH - headerHeight - 8;
        int queueX = leftPos + LEFT_PW + 4;
        int queueY = topPos + headerHeight + 4;
        taskQueuePanel = new TaskQueuePanel(queueX, queueY, QUEUE_PW, queuePh);
        taskQueuePanel.setOnDelete(this::onQueueDelete);
        taskQueuePanel.setOnMoveUp(this::onQueueMoveUp);
        taskQueuePanel.setOnMoveDown(this::onQueueMoveDown);
        addRenderableWidget(taskQueuePanel);

        if (nodePos != null && !nodePos.equals(BlockPos.ZERO)) {
            requestQueueRefresh();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int y = topPos + headerHeight + 8;
        drawInfoLine(g, y, i18n("gui.wandscape.node.element", "Element"),
                i18n("element.wandscape." + element, element));
        y += INFO_ROW_H;
        drawInfoLine(g, y, i18n("gui.wandscape.node.per_harvest", "Per Harvest"), String.valueOf(amountPerHarvest));
        y += INFO_ROW_H;
        drawInfoLine(g, y, i18n("gui.wandscape.node.channel", "Channel"),
                i18n("gui.wandscape.node.channel_ticks", "%s ticks", channelTicks));
        y += INFO_ROW_H;
        drawInfoLine(g, y, i18n("gui.wandscape.node.mana_per_harvest", "Mana / Harvest"), String.valueOf(manaCost));

        // Live totals below the slider
        int n = slider != null ? slider.getValue() : 1;
        String totals = i18n("gui.wandscape.node.total_line", "Total %1$s | Mana %2$s",
                amountPerHarvest * n, manaCost * n);
        g.drawString(Minecraft.getInstance().font, totals,
                contentX, controlY + 26, MedievalColors.TEXT_MUTED);
    }

    private static String i18n(String key, String fallback, Object... args) {
        return com.wsteam.wandscape.shared.ui.I18n.name(key, fallback, args).getString();
    }

    private void drawInfoLine(GuiGraphics g, int y, String label, String value) {
        var font = Minecraft.getInstance().font;
        g.drawString(font, label, contentX, y, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, value, contentX + 96, y, MedievalColors.TEXT_MUTED);
    }

    private void onSubmit() {
        int harvests = slider != null ? slider.getValue() : 1;
        if (nodePos == null || nodePos.equals(BlockPos.ZERO)) return;
        Log.info(TAG, "[Node] publish gather x{} at {}", harvests, nodePos);
        PacketDistributor.sendToServer(new RequestGatherTaskPacket(nodePos, harvests));
        requestQueueRefresh();
    }

    // ── Task queue callbacks ──

    private void onQueueDelete(int index) {
        if (nodePos == null || nodePos.equals(BlockPos.ZERO)) return;
        Log.info(TAG, "[Node] queue delete index={} pos={}", index, nodePos);
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(nodePos, "delete", index));
    }

    private void onQueueMoveUp(int index) {
        if (nodePos == null || nodePos.equals(BlockPos.ZERO)) return;
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(nodePos, "move_up", index));
    }

    private void onQueueMoveDown(int index) {
        if (nodePos == null || nodePos.equals(BlockPos.ZERO)) return;
        PacketDistributor.sendToServer(new TaskQueueModifyPacket(nodePos, "move_down", index));
    }
}
