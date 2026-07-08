package com.wsteam.wandscape.building.client;

import java.util.UUID;

import com.wsteam.wandscape.shared.network.ColonyNameUpdatePacket;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Town Hall info screen — shows colony name (editable), level, experience bar, and progression.
 */
public class TownHallScreen extends MedievalScreen {

    private static final int PW = 280;
    private static final int PH = 200;

    private static final int EXP_BAR_W = 200;
    private static final int EXP_BAR_H = 12;

    private final BlockPos buildingPos;
    private final UUID colonyId;
    private String colonyName;
    private final int level;
    private final int experience;
    private final int expToNext;

    private EditBox nameBox;
    private boolean editing;

    public TownHallScreen(BlockPos buildingPos, UUID colonyId,
                          String colonyName, int level, int experience, int expToNext) {
        super(Component.literal("Town Hall"), PW, PH);
        setTitleBar("市政厅");
        this.buildingPos = buildingPos;
        this.colonyId = colonyId;
        this.colonyName = colonyName != null ? colonyName : "";
        this.level = level;
        this.experience = experience;
        this.expToNext = expToNext;
    }

    @Override
    protected void init() {
        super.init();

        int cx = leftPos + PW / 2;

        // Name edit box (centered, initially hidden focus)
        nameBox = new EditBox(font, cx - 80, topPos + 32, 160, 16,
                Component.literal("Colony name"));
        nameBox.setValue(colonyName);
        nameBox.setMaxLength(30);
        nameBox.setBordered(true);
        nameBox.setCanLoseFocus(true);
        nameBox.setResponder(this::onNameChanged);
        addRenderableWidget(nameBox);

        // Close button
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                Component.literal("Close"), this::onClose));
    }

    private void onNameChanged(String newName) {
        // Trim and update local name, send to server
        String trimmed = newName.trim();
        if (!trimmed.isEmpty() && !trimmed.equals(colonyName)) {
            colonyName = trimmed;
            PacketDistributor.sendToServer(new ColonyNameUpdatePacket(colonyId, trimmed));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int cx = leftPos + PW / 2;
        int x = leftPos + 20;
        int y = topPos + 58;
        int lineH = font.lineHeight + 4;

        // Colony level
        drawCenteredString(g, "§e✦ 殖民地等级 " + level + " ✦", cx, y, 0xFFD700);
        y += lineH + 6;

        // Experience bar
        int barX = leftPos + (PW - EXP_BAR_W) / 2;
        int barY = y;
        float ratio = expToNext > 0 ? (float) experience / expToNext : 0;

        // Background
        g.fill(barX - 1, barY - 1, barX + EXP_BAR_W + 1, barY + EXP_BAR_H + 1, 0xFF8B4513);
        // Fill
        int fillW = (int) (EXP_BAR_W * Math.min(1.0f, ratio));
        if (fillW > 0) {
            g.fill(barX, barY, barX + fillW, barY + EXP_BAR_H, 0xFFDAA520);
        }
        // Border
        g.fill(barX - 1, barY - 1, barX + EXP_BAR_W + 1, barY, 0xFFDAA520);
        g.fill(barX - 1, barY + EXP_BAR_H, barX + EXP_BAR_W + 1, barY + EXP_BAR_H + 1, 0xFFDAA520);
        g.fill(barX - 1, barY - 1, barX, barY + EXP_BAR_H + 1, 0xFFDAA520);
        g.fill(barX + EXP_BAR_W, barY - 1, barX + EXP_BAR_W + 1, barY + EXP_BAR_H + 1, 0xFFDAA520);

        // Exp text overlay
        String expText = experience + " / " + expToNext;
        drawCenteredString(g, expText, cx, barY + (EXP_BAR_H - font.lineHeight) / 2, 0xFFFFFFFF);

        y = barY + EXP_BAR_H + 8;

        // Experience source info
        drawString(g, "§7经验来源（游客满意度100%时）：", x, y, 0xFFAAAAAA);
        y += lineH;
        drawString(g, "  游客等级 < 殖民地等级 → §e0§7 经验", x + 4, y, 0xFFAAAAAA);
        y += lineH;
        drawString(g, "  游客等级 = 殖民地等级 → §e100§7 经验", x + 4, y, 0xFFAAAAAA);
        y += lineH;
        drawString(g, "  游客等级 > 殖民地等级 → §e500§7 经验", x + 4, y, 0xFFAAAAAA);
        y += lineH + 4;

        // Hint
        drawCenteredString(g, "§7点击名称框修改殖民地名称，输入完成自动保存", cx, y, 0xFF888888);
    }

    private void drawString(GuiGraphics g, String text, int x, int y, int color) {
        g.drawString(font, text, x, y, color);
    }

    private void drawCenteredString(GuiGraphics g, String text, int x, int y, int color) {
        g.drawString(font, text, x - font.width(text) / 2, y, color);
    }
}
