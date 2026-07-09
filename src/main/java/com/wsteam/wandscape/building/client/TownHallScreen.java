package com.wsteam.wandscape.building.client;

import java.util.UUID;

import com.wsteam.wandscape.shared.network.ColonyNameUpdatePacket;
import com.wsteam.wandscape.shared.ui.theme.WandscapeTheme;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Town Hall info screen — shows colony name (editable), level, experience bar, and progression.
 * Uses the {@link WandscapeTheme} RTS HUD style to match the V-panel overlay.
 *
 * <p>Visual: dark translucent panel with 1px crisp borders, green accent highlights,
 * and soft white text. No vanilla textures or parchment backgrounds.
 */
public class TownHallScreen extends Screen {

    private static final int PW = 280;
    private static final int PH = 210;
    private static final int HEADER_H = 22;

    private static final int EXP_BAR_W = 200;
    private static final int EXP_BAR_H = 12;

    private final BlockPos buildingPos;
    private final UUID colonyId;
    private String colonyName;
    private final int level;
    private final int experience;
    private final int expToNext;

    private int leftPos;
    private int topPos;
    private EditBox nameBox;

    public TownHallScreen(BlockPos buildingPos, UUID colonyId,
                          String colonyName, int level, int experience, int expToNext) {
        super(Component.literal("Town Hall"));
        this.buildingPos = buildingPos;
        this.colonyId = colonyId;
        this.colonyName = colonyName != null ? colonyName : "";
        this.level = level;
        this.experience = experience;
        this.expToNext = expToNext;
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - PW) / 2;
        this.topPos = (this.height - PH) / 2;

        int cx = leftPos + PW / 2;
        int ebY = topPos + HEADER_H + 13; // header + separator + gap

        nameBox = new EditBox(font, cx - 80, ebY, 160, font.lineHeight + 2,
                Component.literal("Colony name"));
        nameBox.setValue(colonyName);
        nameBox.setMaxLength(30);
        nameBox.setBordered(false);
        nameBox.setTextColor(WandscapeTheme.COLOR_TEXT_NORMAL);
        nameBox.setTextColorUneditable(WandscapeTheme.COLOR_TEXT_DIM);
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

    // ── Render ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 1. Darken world + draw main RTS panel border
        renderBackground(g, mouseX, mouseY, partialTick);

        // 2. Header bar with title + close button
        renderHeader(g, mouseX, mouseY);

        // 3. Content: edit field bg, level, exp bar, info
        renderContent(g);

        // 4. Widgets: EditBox (renders text on top of its background)
        for (Renderable r : this.renderables) {
            r.render(g, mouseX, mouseY, partialTick);
        }
    }

    // ── Background ──

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Transparent dim over the world
        renderTransparentBackground(g);

        // Main panel — RTS-crisp translucent box
        WandscapeTheme.drawRtsBox(g, leftPos, topPos, PW, PH, false, false);
    }

    // ── Header ──

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        int hx = leftPos + 1;
        int hy = topPos + 1;
        int hw = PW - 2;

        // Header background (slightly lighter than panel)
        g.fill(hx, hy, hx + hw, hy + HEADER_H, WandscapeTheme.COLOR_BG_HOVER);

        // Bottom separator line
        g.fill(hx, hy + HEADER_H, hx + hw, hy + HEADER_H + 1, WandscapeTheme.COLOR_BORDER_NORMAL);

        // Green accent bar on left edge
        g.fill(hx, hy, hx + 3, hy + HEADER_H, WandscapeTheme.COLOR_BORDER_ACTIVE);

        // Title text
        g.drawString(font, "市政厅", hx + 10,
                hy + (HEADER_H - font.lineHeight) / 2,
                WandscapeTheme.COLOR_TEXT_NORMAL);

        // Close button
        renderCloseBtn(g, mouseX, mouseY);
    }

    private void renderCloseBtn(GuiGraphics g, int mouseX, int mouseY) {
        int bw = 20;
        int bh = 16;
        int bx = leftPos + PW - bw - 5;
        int by = topPos + (HEADER_H - bh) / 2 + 1;

        boolean hovered = isInRect(mouseX, mouseY, bx, by, bw, bh);
        if (hovered) {
            g.fill(bx, by, bx + bw, by + bh, 0x33FFFFFF);
        }

        String cross = "✕";
        int textW = font.width(cross);
        int textColor = hovered ? WandscapeTheme.COLOR_TEXT_NORMAL : WandscapeTheme.COLOR_TEXT_DIM;
        g.drawString(font, cross,
                bx + (bw - textW) / 2,
                by + (bh - font.lineHeight) / 2,
                textColor);
    }

    // ── Content ──

    private void renderContent(GuiGraphics g) {
        int cx = leftPos + PW / 2;
        int leftX = leftPos + 16;

        // ── Edit box background (inset dark field) ──
        int ebX = cx - 82;
        int ebY = topPos + HEADER_H + 11;
        int ebW = 164;
        int ebH = font.lineHeight + 6;

        g.fill(ebX, ebY, ebX + ebW, ebY + ebH, 0x28000000);
        // 1px border
        int border = WandscapeTheme.COLOR_BORDER_NORMAL;
        g.fill(ebX, ebY, ebX + ebW, ebY + 1, border);
        g.fill(ebX, ebY + ebH - 1, ebX + ebW, ebY + ebH, border);
        g.fill(ebX, ebY, ebX + 1, ebY + ebH, border);
        g.fill(ebX + ebW - 1, ebY, ebX + ebW, ebY + ebH, border);

        // ── Colony level ──
        int y = ebY + ebH + 16;
        String levelText = "殖民地等级 " + level;
        g.drawString(font, levelText, cx - font.width(levelText) / 2, y,
                WandscapeTheme.COLOR_TEXT_ACTIVE); // green accent
        y += font.lineHeight + 10;

        // ── Experience bar ──
        renderExpBar(g, y);
        y += EXP_BAR_H + 14;

        // ── Experience source info ──
        g.drawString(font, "经验来源（游客满意度100%时）：", leftX, y,
                WandscapeTheme.COLOR_TEXT_DIM);
        y += font.lineHeight + 3;

        String[] expLines = {
            "游客等级 < 殖民地等级 → 0 经验",
            "游客等级 = 殖民地等级 → 100 经验",
            "游客等级 > 殖民地等级 → 500 经验"
        };
        for (String line : expLines) {
            g.drawString(font, "  " + line, leftX + 4, y, WandscapeTheme.COLOR_TEXT_DIM);
            y += font.lineHeight + 2;
        }

        // ── Hint ──
        y += 4;
        String hint = "点击名称框修改殖民地名称，输入完成自动保存";
        g.drawString(font, hint, cx - font.width(hint) / 2, y,
                WandscapeTheme.COLOR_TEXT_DIM);
    }

    private void renderExpBar(GuiGraphics g, int barY) {
        int barX = leftPos + (PW - EXP_BAR_W) / 2;
        int cx = leftPos + PW / 2;
        float ratio = expToNext > 0 ? (float) experience / expToNext : 0;
        int fillW = (int) (EXP_BAR_W * Math.min(1.0f, ratio));

        // Track background
        g.fill(barX, barY, barX + EXP_BAR_W, barY + EXP_BAR_H, 0x28000000);

        // Fill (green accent)
        if (fillW > 0) {
            g.fill(barX, barY, barX + fillW, barY + EXP_BAR_H, WandscapeTheme.COLOR_BORDER_ACTIVE);
        }

        // 1px border
        int border = WandscapeTheme.COLOR_BORDER_NORMAL;
        g.fill(barX, barY, barX + EXP_BAR_W, barY + 1, border);
        g.fill(barX, barY + EXP_BAR_H - 1, barX + EXP_BAR_W, barY + EXP_BAR_H, border);
        g.fill(barX, barY, barX + 1, barY + EXP_BAR_H, border);
        g.fill(barX + EXP_BAR_W - 1, barY, barX + EXP_BAR_W, barY + EXP_BAR_H, border);

        // Centered text
        String expText = experience + " / " + expToNext;
        g.drawString(font, expText,
                cx - font.width(expText) / 2,
                barY + (EXP_BAR_H - font.lineHeight) / 2,
                WandscapeTheme.COLOR_TEXT_NORMAL);
    }

    // ── Input ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isCloseHit(mouseX, mouseY)) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isCloseHit(double mouseX, double mouseY) {
        int bw = 20, bh = 16;
        int bx = leftPos + PW - bw - 5;
        int by = topPos + (HEADER_H - bh) / 2 + 1;
        return isInRect(mouseX, mouseY, bx, by, bw, bh);
    }

    private static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
