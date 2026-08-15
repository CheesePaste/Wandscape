package com.wsteam.wandscape.building.client;

import java.util.UUID;

import com.wsteam.wandscape.building.network.TownHallNameStylePacket;
import com.wsteam.wandscape.building.network.TownHallWarehouseRequestPacket;
import com.wsteam.wandscape.shared.data.NameStyle;
import com.wsteam.wandscape.shared.network.ColonyNameUpdatePacket;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Town Hall info screen — colony name (editable), level, experience bar, progression
 * and the character naming rule switcher (western fantasy / Chinese / English).
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
    /** True when the colony has no storage building — show the warehouse access button. */
    private final boolean canUseWarehouse;

    private EditBox nameBox;
    private NameStyle namingStyle;
    private final MedievalButton[] styleButtons = new MedievalButton[NameStyle.values().length];

    public TownHallScreen(BlockPos buildingPos, UUID colonyId,
                          String colonyName, int level, int experience, int expToNext,
                          String founderName, boolean canUseWarehouse, int namingStyleOrdinal) {
        super(I18n.name("gui.wandscape.townhall.title", "Town Hall"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.townhall.title", "市政厅"));
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
        this.canUseWarehouse = canUseWarehouse;
        this.namingStyle = ordinalToStyle(namingStyleOrdinal);
    }

    private static NameStyle ordinalToStyle(int ordinal) {
        NameStyle[] values = NameStyle.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NameStyle.FANTASY;
    }

    @Override
    protected void init() {
        super.init();

        int cx = leftPos + PW / 2;
        int ebY = topPos + headerHeight + 11;

        nameBox = new EditBox(font, cx - 80, ebY, 160, font.lineHeight + 2,
                I18n.name("gui.wandscape.townhall.name_hint", "魔法小镇名称"));
        nameBox.setValue(colonyName);
        nameBox.setMaxLength(30);
        nameBox.setBordered(false);
        nameBox.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        nameBox.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        nameBox.setCanLoseFocus(true);
        nameBox.setResponder(this::onNameChanged);
        addRenderableWidget(nameBox);

        // Character naming rule switcher (fantasy / chinese / english)
        int sbW = 64;
        int sbH = 14;
        int sbGap = 6;
        int sbTotal = sbW * styleButtons.length + sbGap * (styleButtons.length - 1);
        int sbX = leftPos + (PW - sbTotal) / 2;
        int sbY = ebY + 2 * font.lineHeight + 16;
        for (int i = 0; i < styleButtons.length; i++) {
            NameStyle style = NameStyle.values()[i];
            int x = sbX + i * (sbW + sbGap);
            styleButtons[i] = new MedievalButton(x, sbY, sbW, sbH,
                    styleButtonLabel(style), () -> switchNamingStyle(style));
            addRenderableWidget(styleButtons[i]);
        }

        if (canUseWarehouse) {
            int bw = 120;
            int bh = 16;
            int bx = leftPos + (PW - bw) / 2;
            int by = topPos + PH - bh - 12;
            addRenderableWidget(new MedievalButton(bx, by, bw, bh,
                    I18n.name("gui.wandscape.townhall.warehouse", "仓库存取"),
                    this::onWarehouseAccess));
        }
    }

    private static Component styleButtonLabel(NameStyle style) {
        return switch (style) {
            case FANTASY -> I18n.name("gui.wandscape.townhall.style_fantasy", "西幻");
            case CHINESE -> I18n.name("gui.wandscape.townhall.style_chinese", "中文");
            case ENGLISH -> I18n.name("gui.wandscape.townhall.style_english", "英文");
        };
    }

    private void switchNamingStyle(NameStyle style) {
        if (style == namingStyle) return;
        namingStyle = style;
        PacketDistributor.sendToServer(new TownHallNameStylePacket(colonyId, style.ordinal()));
    }

    private void onWarehouseAccess() {
        PacketDistributor.sendToServer(new TownHallWarehouseRequestPacket(buildingPos, colonyId));
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
        renderSelectedStyleHighlight(g);

        for (Renderable r : this.renderables) {
            r.render(g, mouseX, mouseY, partialTick);
        }
    }

    /** Gold border around the currently active naming-rule button. */
    private void renderSelectedStyleHighlight(GuiGraphics g) {
        MedievalButton sel = styleButtons[namingStyle.ordinal()];
        if (sel == null || !sel.visible) return;
        int bx = sel.getX();
        int by = sel.getY();
        int bw = sel.getWidth();
        int bh = sel.getHeight();
        g.fill(bx, by, bx + bw, by + 1, MedievalColors.BORDER_GOLD);
        g.fill(bx, by + bh - 1, bx + bw, by + bh, MedievalColors.BORDER_GOLD);
        g.fill(bx, by, bx + 1, by + bh, MedievalColors.BORDER_GOLD);
        g.fill(bx + bw - 1, by, bx + bw, by + bh, MedievalColors.BORDER_GOLD);
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

        // Naming rule label (buttons are renderables drawn after this)
        int styleLabelY = ebY + ebH + 6;
        Component styleLabel = I18n.name("gui.wandscape.townhall.naming_style", "命名风格");
        g.drawString(font, styleLabel, cx - font.width(styleLabel) / 2, styleLabelY,
                MedievalColors.TEXT_MUTED);

        // Colony founder
        int y = styleLabelY + font.lineHeight + 4 + 14 + 8;
        Component founderText = I18n.name("gui.wandscape.townhall.founder", "创建者：%s",
                founderName != null && !founderName.isEmpty() ? founderName : "—");
        g.drawString(font, founderText, cx - font.width(founderText) / 2, y,
                MedievalColors.TEXT_WARM_WHITE);
        y += font.lineHeight + 8;

        // Colony level
        Component levelText = I18n.name("gui.wandscape.townhall.level", "魔法小镇等级 %s", level);
        g.drawString(font, levelText, cx - font.width(levelText) / 2, y,
                MedievalColors.BORDER_GOLD);
        y += font.lineHeight + 10;

        // Experience bar
        renderExpBar(g, y);
        y += EXP_BAR_H + 14;

        // Experience source info
        g.drawString(font, I18n.name("gui.wandscape.townhall.exp_source", "经验来源（游客满意度100%时）："),
                leftX, y, MedievalColors.TEXT_MUTED);
        y += font.lineHeight + 3;

        Component[] expLines = {
            I18n.name("gui.wandscape.townhall.exp_lt", "游客等级 < 魔法小镇等级 → 0 经验"),
            I18n.name("gui.wandscape.townhall.exp_eq", "游客等级 = 魔法小镇等级 → 200 经验"),
            I18n.name("gui.wandscape.townhall.exp_gt", "游客等级 > 魔法小镇等级 → 500 经验")
        };
        for (Component line : expLines) {
            g.drawString(font, Component.literal("  ").copy().append(line), leftX + 4, y,
                    MedievalColors.TEXT_MUTED);
            y += font.lineHeight + 2;
        }

        y += 4;
        Component hint = I18n.name("gui.wandscape.townhall.hint",
                "点击名称框修改魔法小镇名称，输入完成自动保存");
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
