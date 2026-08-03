package com.wsteam.wandscape.building.client;

import java.util.UUID;

import com.wsteam.wandscape.shared.network.ColonyNameUpdatePacket;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Town Hall info screen — colony name (editable), level, experience bar, and progression.
 * Uses {@link MedievalScreen} MINIMAL theme with {@link MedievalColors}.
 */
public class TownHallScreen extends MedievalScreen {

    private static final int PW = 300;
    private static final int PH = 230;
    private static final int EXP_BAR_W = 200;
    private static final int EXP_BAR_H = 12;

    private final BlockPos buildingPos;
    private final UUID colonyId;
    private String colonyName;
    private final int level;
    private final int experience;
    private final int expToNext;
    private final String founderName;

    private EditBox nameBox;

    public TownHallScreen(BlockPos buildingPos, UUID colonyId,
                          String colonyName, int level, int experience, int expToNext,
                          String founderName) {
        super(Component.literal("Town Hall"), PW, PH);
        setTitleBar("市政厅");
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "townhall_guide";
        this.buildingPos = buildingPos;
        this.colonyId = colonyId;
        this.colonyName = colonyName != null ? colonyName : "";
        this.level = level;
        this.experience = experience;
        this.expToNext = expToNext;
        this.founderName = founderName;
    }

    @Override
    protected void init() {
        super.init();

        int cx = leftPos + PW / 2;
        int ebY = topPos + headerHeight + 13;

        nameBox = new EditBox(font, cx - 80, ebY, 160, font.lineHeight + 2,
                Component.literal("Colony name"));
        nameBox.setValue(colonyName);
        nameBox.setMaxLength(30);
        nameBox.setBordered(false);
        nameBox.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        nameBox.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        nameBox.setCanLoseFocus(true);
        nameBox.setResponder(this::onNameChanged);
        addRenderableWidget(nameBox);
    }

    private void onNameChanged(String newName) {
        String trimmed = newName.trim();
        if (!trimmed.isEmpty() && !trimmed.equals(colonyName)) {
            colonyName = trimmed;
            PacketDistributor.sendToServer(new ColonyNameUpdatePacket(colonyId, trimmed));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        renderMinimalHeader(g);
        renderCloseButton(g, mouseX, mouseY);
        renderContent(g);

        for (Renderable r : this.renderables) {
            r.render(g, mouseX, mouseY, partialTick);
        }
    }

    private void renderContent(GuiGraphics g) {
        int cx = leftPos + PW / 2;
        int leftX = leftPos + 16;

        // Edit box background
        int ebX = cx - 82;
        int ebY = topPos + headerHeight + 11;
        int ebW = 164;
        int ebH = font.lineHeight + 6;
        drawInsetField(g, ebX, ebY, ebW, ebH);

        // Colony founder
        int y = ebY + ebH + 16;
        String founderText = "创建者："
                + (founderName != null && !founderName.isEmpty() ? founderName : "—");
        g.drawString(font, founderText, cx - font.width(founderText) / 2, y,
                MedievalColors.TEXT_WARM_WHITE);
        y += font.lineHeight + 8;

        // Colony level
        String levelText = "殖民地等级 " + level;
        g.drawString(font, levelText, cx - font.width(levelText) / 2, y,
                MedievalColors.BORDER_GOLD);
        y += font.lineHeight + 10;

        // Experience bar
        renderExpBar(g, y);
        y += EXP_BAR_H + 14;

        // Experience source info
        g.drawString(font, "经验来源（游客满意度100%时）：", leftX, y,
                MedievalColors.TEXT_MUTED);
        y += font.lineHeight + 3;

        String[] expLines = {
            "游客等级 < 殖民地等级 → 0 经验",
            "游客等级 = 殖民地等级 → 100 经验",
            "游客等级 > 殖民地等级 → 500 经验"
        };
        for (String line : expLines) {
            g.drawString(font, "  " + line, leftX + 4, y, MedievalColors.TEXT_MUTED);
            y += font.lineHeight + 2;
        }

        y += 4;
        String hint = "点击名称框修改殖民地名称，输入完成自动保存";
        g.drawString(font, hint, cx - font.width(hint) / 2, y, MedievalColors.TEXT_MUTED);
    }

    private void renderExpBar(GuiGraphics g, int barY) {
        int barX = leftPos + (PW - EXP_BAR_W) / 2;
        int cx = leftPos + PW / 2;
        float ratio = expToNext > 0 ? (float) experience / expToNext : 0;
        int fillW = (int) (EXP_BAR_W * Math.min(1.0f, ratio));

        g.fill(barX, barY, barX + EXP_BAR_W, barY + EXP_BAR_H, 0x28000000);
        if (fillW > 0) {
            g.fill(barX, barY, barX + fillW, barY + EXP_BAR_H, MedievalColors.BORDER_GOLD);
        }
        int border = MedievalColors.BORDER_GOLD_DARK;
        g.fill(barX, barY, barX + EXP_BAR_W, barY + 1, border);
        g.fill(barX, barY + EXP_BAR_H - 1, barX + EXP_BAR_W, barY + EXP_BAR_H, border);
        g.fill(barX, barY, barX + 1, barY + EXP_BAR_H, border);
        g.fill(barX + EXP_BAR_W - 1, barY, barX + EXP_BAR_W, barY + EXP_BAR_H, border);

        String expText = experience + " / " + expToNext;
        g.drawString(font, expText,
                cx - font.width(expText) / 2,
                barY + (EXP_BAR_H - font.lineHeight) / 2,
                MedievalColors.TEXT_WARM_WHITE);
    }
}
