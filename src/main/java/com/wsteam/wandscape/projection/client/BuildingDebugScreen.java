package com.wsteam.wandscape.projection.client;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.projection.network.BuildingDebugResponsePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class BuildingDebugScreen extends MedievalScreen {

    private static final int ROW_H = 14;
    private static final int COL_X1 = 12;
    private static final int COL_X2 = 150;
    private static final int START_Y = 28;

    private final BuildingDebugResponsePacket data;

    public BuildingDebugScreen(BuildingDebugResponsePacket data) {
        super(Component.literal(""), 380, 420);
        this.data = data;
    }

    @Override
    protected void init() {
        super.init();
        setTitleBar("Debug: " + data.buildingTypeId());

        addRenderableWidget(new MedievalButton(
                leftPos + panelWidth / 2 - 50,
                topPos + 360,
                100, 18,
                Component.literal("Close"),
                () -> Minecraft.getInstance().setScreen((BuildingDebugScreen) null)
        ));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int y = topPos + START_Y;

        // Identity
        g.drawString(font, "Identity", leftPos + COL_X1, y, 0xFFFFDD); y += ROW_H;
        drawKV(g, y, "buildingId",   truncate(data.buildingId())); y += ROW_H;
        drawKV(g, y, "typeId",       data.buildingTypeId());        y += ROW_H;
        drawKV(g, y, "category",     data.category());              y += ROW_H;
        drawKV(g, y, "colonyId",     data.colonyId() != null ? data.colonyId().toString() : "null"); y += ROW_H;
        drawKV(g, y, "anchor",       posStr(data.anchor()));      y += ROW_H;
        y += 4;
        g.fill(leftPos + COL_X1, y, leftPos + panelWidth - COL_X1, y + 1, 0x664422);
        y += ROW_H;

        // Status
        g.drawString(font, "Status", leftPos + COL_X1, y, 0xFFFFDD); y += ROW_H;
        drawKV(g, y, "intact",    String.valueOf(data.intact())); y += ROW_H;
        drawKV(g, y, "shutdown",  String.valueOf(data.shutdown())); y += ROW_H;
        y += 4;
        g.fill(leftPos + COL_X1, y, leftPos + panelWidth - COL_X1, y + 1, 0x664422);
        y += ROW_H;

        // Stats
        g.drawString(font, "Stats", leftPos + COL_X1, y, 0xFFFFDD); y += ROW_H;
        drawKV(g, y, "comfort",   String.valueOf(data.comfort()));      y += ROW_H;
        drawKV(g, y, "magic",     String.valueOf(data.magic()));        y += ROW_H;
        drawKV(g, y, "wonder",    String.valueOf(data.wonder()));       y += ROW_H;
        drawKV(g, y, "queueCap",  String.valueOf(data.queueCapacity())); y += ROW_H;
        y += 4;
        g.fill(leftPos + COL_X1, y, leftPos + panelWidth - COL_X1, y + 1, 0x664422);
        y += ROW_H;

        // Task queue
        List<WorkItem> queue = data.queue();
        int qSize = queue != null ? queue.size() : 0;
        g.drawString(font, "Task Queue (" + qSize + ")", leftPos + COL_X1, y, 0xFFFFDD); y += ROW_H;
        if (queue != null && !queue.isEmpty()) {
            int shown = Math.min(qSize, 20);
            for (int i = 0; i < shown; i++) {
                drawTaskRow(g, y, queue.get(i), i);
                y += ROW_H;
            }
            if (qSize > 20) {
                g.drawString(font, "... and " + (qSize - 20) + " more", leftPos + COL_X2, y, 0xFF8888);
                y += ROW_H;
            }
        }
        y += 4;
        g.fill(leftPos + COL_X1, y, leftPos + panelWidth - COL_X1, y + 1, 0x664422);
        y += ROW_H;

        // Current task
        g.drawString(font, "Current", leftPos + COL_X1, y, 0xFFFFDD); y += ROW_H;
        drawKV(g, y, "currentTaskId", data.currentTaskId() != null ? data.currentTaskId().toString() : "none"); y += ROW_H;
    }

    // ── Rendering helpers ──

    private void drawKV(GuiGraphics g, int y, String key, String value) {
        g.drawString(font, key + ":", leftPos + COL_X1, y, 0xFFAA88);
        g.drawString(font, value, leftPos + COL_X2, y, 0xFFCCAA);
    }

    private void drawTaskRow(GuiGraphics g, int y, WorkItem item, int index) {
        String val = item.blueprintId() + " (p=" + item.priority() + ")";
        g.drawString(font, "[" + index + "]", leftPos + COL_X1, y, 0xFFAA88);
        g.drawString(font, val, leftPos + COL_X2, y, 0xFFCCAA);
    }

    // ── Helpers ──

    private static String truncate(UUID id) {
        return id != null ? id.toString().substring(0, 8) : "null";
    }

    private static String posStr(net.minecraft.core.BlockPos pos) {
        if (pos == null) return "null";
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 1) {
            Minecraft.getInstance().setScreen((BuildingDebugScreen) null);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_G) {
            BuildingDebugClientState.setActive(false);
            Minecraft.getInstance().setScreen((BuildingDebugScreen) null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
