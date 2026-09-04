package com.wsteam.wandscape.foundation.ui.component;

import com.wsteam.wandscape.content.building.projection.client.BuildingDebugClientState;
import com.wsteam.wandscape.content.building.projection.network.BuildingActionPacket;
import com.wsteam.wandscape.content.building.projection.network.BuildingDebugResponsePacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.ReplayProtectedScreen;
import com.wsteam.wandscape.foundation.ui.animation.MedievalAnimation;
import com.wsteam.wandscape.foundation.ui.skin.SkinRender;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Base screen for all Wandscape single-page UIs.
 * Provides gradient glass panel, glow border, purple header, and {@link MedievalColors} palette.
 */
public abstract class MedievalScreen extends Screen implements ReplayProtectedScreen, ScreenFeedbackHost {

    protected int leftPos, topPos;
    protected final int panelWidth;
    protected final int panelHeight;
    protected int headerHeight = 22;
    protected Component titleBarText;
    protected int titleXOffset = 10;
    protected final List<MedievalAnimation> animations = new ArrayList<>();

    // ── Building Header & Actions (for building screens) ──
    protected boolean isBuildingScreen = false;
    @Nullable protected UUID buildingId;
    @Nullable protected BlockPos buildingPos;
    @Nullable protected BuildingDebugResponsePacket buildingData;

    protected MedievalButton btnRepair;
    protected MedievalButton btnDemolish;
    protected int actionButtonsX = -1;
    protected int actionButtonsY = -1;
    protected int actionButtonsOffsetX = -1;
    protected int actionButtonsOffsetY = -1;

    // Hitboxes for header tooltips
    private int statusBadgeX, statusBadgeY, statusBadgeW, statusBadgeH;
    private int comfortIconX, comfortIconY, comfortIconW, comfortIconH;
    private int magicIconX, magicIconY, magicIconW, magicIconH;
    private int wonderIconX, wonderIconY, wonderIconW, wonderIconH;

    public void setBuildingContext(@Nullable UUID id, @Nullable BlockPos pos) {
        this.buildingId = id;
        this.buildingPos = pos;
        this.isBuildingScreen = true;
    }

    public void setActionButtonsOffset(int offsetX, int offsetY) {
        this.actionButtonsOffsetX = offsetX;
        this.actionButtonsOffsetY = offsetY;
    }

    public void setBuildingActionPosition(int x, int y) {
        this.actionButtonsX = x;
        this.actionButtonsY = y;
        if (btnRepair != null && btnDemolish != null) {
            btnRepair.setX(x);
            btnRepair.setY(y);
            btnDemolish.setX(x + btnRepair.getWidth() + 4);
            btnDemolish.setY(y);
        }
    }

    public void setBuildingData(BuildingDebugResponsePacket data) {
        if (!isBuildingScreen) return;
        if (!matchesBuilding(data)) return;
        this.buildingData = data;
        if (data.buildingId() != null) {
            this.buildingId = data.buildingId();
        }
        if (data.anchor() != null && this.buildingPos == null) {
            this.buildingPos = data.anchor();
        }
        if (btnRepair == null || btnDemolish == null) {
            initBuildingActionButtons();
        } else {
            updateBuildingActionButtons();
        }
    }

    protected boolean matchesBuilding(@Nullable BuildingDebugResponsePacket packet) {
        if (packet == null) return false;
        if (buildingId != null) {
            return buildingId.equals(packet.buildingId());
        }
        if (buildingPos != null && packet.anchor() != null) {
            if (buildingPos.equals(packet.anchor())) return true;
        }
        return buildingId == null;
    }

    // ── Reusable confirmation dialog (rendered above everything when open) ──
    protected final MedievalConfirmDialog confirmDialog = new MedievalConfirmDialog();

    /** Open the built-in confirm dialog; on confirm the given action runs. */
    protected void openConfirmDialog(Component message, Runnable onConfirm) {
        confirmDialog.open(message, onConfirm);
    }

    /** Open the built-in confirm dialog with custom title; on confirm the given action runs. */
    protected void openConfirmDialog(Component title, Component message, Runnable onConfirm) {
        confirmDialog.open(title, message, onConfirm);
    }

    // ── Building creator footer ──
    /** Vertical space reserved at the bottom for the creator label (subclasses use it in layout math). */
    protected static final int CREATOR_FOOTER_H = 24;
    private String buildingCreator = "";

    /** Set the building designer's name to show at the bottom-left of the panel. */
    public void setCreator(String creator) {
        this.buildingCreator = creator != null ? creator : "";
    }

    /** Draw the creator label at the bottom-left at the default font size. */
    protected void renderCreatorFooter(GuiGraphics g) {
        if (buildingCreator.isBlank()) return;
        String text = I18n.name("gui.wandscape.common.creator_label", "Creator").getString()
                + ": " + buildingCreator;
        g.drawString(font, text, leftPos + 16, topPos + panelHeight - CREATOR_FOOTER_H,
                MedievalColors.TEXT_DIM);
    }

    // ── Built-in close button ──
    protected boolean showCloseButton;
    protected int closeBtnX, closeBtnY, closeBtnW = 18, closeBtnH = 14;
    protected int closeBtnState;

    // ── Built-in help button & document ──
    protected boolean showHelpButton;
    protected String helpDocumentPath;
    protected HelpButton helpButton;

    // ── Glass panel gradient (Dark opaque medieval theme) ──
    private static final int GLASS_TOP       = 0xF5261A10;
    private static final int GLASS_BOTTOM    = 0xF5120804;
    private static final int GLASS_BOX_TOP    = 0xDD3A2818;
    private static final int GLASS_BOX_BOTTOM = 0xDD1E100A;

    // ── Transient feedback toast (drawn over the screen, does not resize the panel) ──
    private static final long FEEDBACK_DURATION_MS = 3000L;
    private Component feedback;
    private int feedbackColor;
    private long feedbackExpireTick;

    /** Show a transient message at the top-center of the screen for ~3s. */
    public void showFeedback(Component message, int color) {
        this.feedback = message;
        this.feedbackColor = color;
        this.feedbackExpireTick = System.currentTimeMillis() + FEEDBACK_DURATION_MS;
    }

    protected MedievalScreen(Component title, int panelWidth, int panelHeight) {
        super(title);
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
    }

    protected void setTitleBar(Component title) {
        this.titleBarText = title;
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - panelWidth) / 2;
        this.topPos = Math.max(2, (this.height - panelHeight) / 2);
        if (showCloseButton) {
            closeBtnX = leftPos + panelWidth - closeBtnW - 6;
            closeBtnY = topPos + (headerHeight - closeBtnH) / 2;
        }
        if (showHelpButton && helpDocumentPath != null) {
            int helpW = 14;
            int helpH = 14;
            int helpX = showCloseButton ? closeBtnX - helpW - 4 : leftPos + panelWidth - helpW - 6;
            int helpY = topPos + (headerHeight - helpH) / 2;
            helpButton = new HelpButton(helpX, helpY, helpW, helpH, this::openHelpDocument);
            addRenderableWidget(helpButton);
        }

        if (isBuildingScreen) {
            if (this.buildingData == null && BuildingDebugClientState.getCachedData() != null) {
                var cached = BuildingDebugClientState.getCachedData();
                if (matchesBuilding(cached)) {
                    this.buildingData = cached;
                    if (this.buildingId == null) this.buildingId = cached.buildingId();
                    if (this.buildingPos == null) this.buildingPos = cached.anchor();
                }
            }
            if (this.buildingData == null && this.buildingPos != null) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.wsteam.wandscape.content.building.projection.network.BuildingDebugRequestPacket(this.buildingPos));
            }
            initBuildingActionButtons();
        }
    }

    public void openHelpDocument() {
        if (helpDocumentPath != null && minecraft != null) {
            String content = com.wsteam.wandscape.foundation.ui.markdown.navigation.DocumentLoader.loadMarkdown(helpDocumentPath);
            var screen = new com.wsteam.wandscape.foundation.ui.guidebook.GuidebookScreen(this, content, helpDocumentPath);
            minecraft.setScreen(screen);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Confirm dialog open: swallow everything (Esc cancel / Enter confirm handled inside).
        if (confirmDialog.isOpen()) {
            return confirmDialog.keyPressed(keyCode, scanCode, modifiers);
        }
        // Let a focused text box consume the key first (typing letters incl. H);
        // only open the help document when H is pressed outside any edit box.
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (showHelpButton && helpDocumentPath != null
                && !(getFocused() instanceof EditBox box && box.canConsumeInput())
                && com.wsteam.wandscape.WandscapeClient.GUIDEBOOK_TOGGLE.matches(keyCode, scanCode)) {
            openHelpDocument();
            return true;
        }
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);

        if (titleBarText != null) {
            renderMinimalHeader(g);
        }

        if (showCloseButton) {
            renderCloseButton(g, mouseX, mouseY);
        }

        for (MedievalAnimation a : animations) a.tick();

        renderContent(g, mouseX, mouseY, partialTick);

        for (Renderable renderable : this.renderables) {
            renderable.render(g, mouseX, mouseY, partialTick);
        }

        animations.removeIf(MedievalAnimation::isComplete);
        for (MedievalAnimation a : animations) {
            a.render(g, mouseX, mouseY, partialTick);
        }

        renderCreatorFooter(g);
        renderFeedback(g);
        if (!confirmDialog.isOpen()) {
            renderForeground(g, mouseX, mouseY, partialTick);
            if (isBuildingScreen && buildingData != null) {
                renderBuildingHeaderTooltips(g, mouseX, mouseY);
            }
        }

        if (confirmDialog.isOpen()) {
            confirmDialog.render(g, width, height, mouseX, mouseY);
        }
    }

    /** Hook for drawing screen content (cards, background frames, labels) behind widgets. */
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    /** Hook for drawing foreground elements (tooltips, overlays) in front of widgets. */
    protected void renderForeground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    /** Draw the transient feedback toast, if any, at the top-center of the screen. */
    protected void renderFeedback(GuiGraphics g) {
        if (feedback == null) return;
        if (System.currentTimeMillis() > feedbackExpireTick) {
            feedback = null;
            return;
        }
        int textW = font.width(feedback);
        int pad = 8;
        int w = textW + pad * 2;
        int h = font.lineHeight + 6;
        int x = (this.width - w) / 2;
        int y = Math.max(6, topPos - h - 3);

        // Dark medieval box with colored glow border
        g.fillGradient(x, y, x + w, y + h, 0xEE2A1C14, 0xEE120804);
        int borderCol = (feedbackColor & 0x00FFFFFF) | 0xDD000000;
        g.fill(x, y, x + w, y + 1, borderCol);
        g.fill(x, y + h - 1, x + w, y + h, borderCol);
        g.fill(x, y, x + 1, y + h, borderCol);
        g.fill(x + w - 1, y, x + w, y + h, borderCol);

        g.drawString(font, feedback, x + pad, y + (h - font.lineHeight) / 2, feedbackColor);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(g);

        // Gradient glass panel
        g.fillGradient(leftPos, topPos, leftPos + panelWidth, topPos + panelHeight,
                GLASS_TOP, GLASS_BOTTOM);
        // Glow border
        drawGlowBorder(g, leftPos, topPos, panelWidth, panelHeight,
                MedievalColors.BORDER_GOLD);
    }

    // ── MINIMAL header ──

    protected void renderMinimalHeader(GuiGraphics g) {
        int hx = leftPos + 1;
        int hy = topPos + 1;
        int hw = panelWidth - 2;

        g.fillGradient(hx, hy, hx + hw, hy + headerHeight,
                0xFF502870, 0xFF1A0830);

        // Gold bottom separator — 2-ring glow fade
        int sepY = hy + headerHeight;
        int sc = MedievalColors.BORDER_GOLD;
        g.fill(hx, sepY, hx + hw, sepY + 1, sc);
        g.fill(hx, sepY + 1, hx + hw, sepY + 2, (sc & 0x00FFFFFF) | 0x66000000);

        // Gold left accent — gradient
        g.fillGradient(hx, hy, hx + 3, hy + headerHeight,
                0xFFD4A840, 0xFF6A4020);

        int currentX = hx + titleXOffset;
        if (titleBarText != null) {
            g.drawString(font, titleBarText, currentX,
                    hy + (headerHeight - font.lineHeight) / 2,
                    MedievalColors.TEXT_WARM_WHITE);
            currentX += font.width(titleBarText) + 8;
        }

        if (isBuildingScreen && buildingData != null) {
            renderBuildingHeaderInfo(g, hx, hy, hw, currentX);
        }
    }

    protected void initBuildingActionButtons() {
        if (!isBuildingScreen) return;

        int btnW = 44;
        int btnH = 16;
        int gap = 4;

        int bx;
        int by;
        if (actionButtonsOffsetX >= 0 && actionButtonsOffsetY >= 0) {
            bx = leftPos + actionButtonsOffsetX;
            by = topPos + actionButtonsOffsetY;
        } else if (actionButtonsX >= leftPos && actionButtonsY >= topPos) {
            bx = actionButtonsX;
            by = actionButtonsY;
        } else if (panelWidth >= 400) {
            // Panels with right task queues (e.g. Workstation/Crafting/Magic/Node):
            // Place directly below the TaskQueuePanel on the right side footer
            bx = leftPos + panelWidth - 12 - (btnW * 2 + gap);
            by = topPos + panelHeight - 20;
        } else {
            // Standard panels (300 ~ 380 wide):
            // Place at the bottom right before the Close button (Close takes leftPos + PW - 54 to PW - 8)
            bx = leftPos + panelWidth - 54 - (btnW * 2 + gap);
            by = topPos + panelHeight - 20;
        }

        btnRepair = new MedievalButton(bx, by, btnW, btnH,
                I18n.name("gui.wandscape.building_action.repair", "修复"),
                this::onBuildingRepairClicked) {
            @Override
            protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                super.renderWidget(g, mouseX, mouseY, partialTick);
                if (visible && active && buildingData != null && buildingData.needsRepair()) {
                    g.fill(getX() + 2, getY() + height - 3, getX() + width - 2, getY() + height - 2, 0xAA2E7D32);
                }
            }
        };

        btnDemolish = new MedievalButton(bx + btnW + gap, by, btnW, btnH,
                I18n.name("gui.wandscape.building_action.destroy", "拆除"),
                this::onBuildingDemolishClicked) {
            @Override
            protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                super.renderWidget(g, mouseX, mouseY, partialTick);
                if (visible && active) {
                    g.fill(getX() + 2, getY() + height - 3, getX() + width - 2, getY() + height - 2, 0xAA8B0000);
                }
            }
        };

        addRenderableWidget(btnRepair);
        addRenderableWidget(btnDemolish);

        updateBuildingActionButtons();
    }

    protected void updateBuildingActionButtons() {
        if (btnRepair == null || btnDemolish == null) return;
        if (buildingData == null) {
            btnRepair.visible = false;
            btnDemolish.visible = false;
            return;
        }

        btnRepair.visible = true;
        btnDemolish.visible = true;

        boolean demolishing = buildingData.demolishing();
        boolean underConstruction = buildingData.underConstruction();
        boolean needsRepair = buildingData.needsRepair();

        // 1. Demolish button state
        if (demolishing) {
            btnDemolish.setMessage(I18n.name("gui.wandscape.building_action.demolishing", "拆除中..."));
            btnDemolish.active = false;
        } else {
            btnDemolish.setMessage(I18n.name("gui.wandscape.building_action.destroy", "拆除"));
            btnDemolish.active = true;
        }

        // 2. Repair / Undo button state
        if (demolishing) {
            btnRepair.setMessage(I18n.name("gui.wandscape.building_action.repair", "修复"));
            btnRepair.active = false;
        } else if (underConstruction) {
            btnRepair.setMessage(I18n.name("gui.wandscape.building_action.cancel", "撤销"));
            btnRepair.active = true;
        } else {
            btnRepair.setMessage(I18n.name("gui.wandscape.building_action.repair", "修复"));
            btnRepair.active = needsRepair;
        }
    }

    protected void onBuildingRepairClicked() {
        if (buildingData == null) return;
        UUID targetId = buildingData.buildingId();
        if (targetId == null) targetId = this.buildingId;
        if (targetId == null) return;

        if (buildingData.underConstruction()) {
            final UUID cancelId = targetId;
            String name = getBuildingDisplayName();
            openConfirmDialog(
                    I18n.name("gui.wandscape.confirm.cancel.title", "确认撤销"),
                    I18n.name("gui.wandscape.confirm.cancel.msg", "确定要撤销「%s」的建造吗？将清除施工地并返还已分配建材。", name),
                    () -> {
                        PacketDistributor.sendToServer(new BuildingActionPacket(cancelId, "cancel"));
                        this.onClose();
                    }
            );
            return;
        }

        if (buildingData.needsRepair() && !buildingData.demolishing()) {
            PacketDistributor.sendToServer(new BuildingActionPacket(targetId, "repair"));
            showFeedback(I18n.name("gui.wandscape.building_action.repair_sent", "已下发修复任务"), MedievalColors.SUCCESS_GREEN);
        }
    }

    protected void onBuildingDemolishClicked() {
        if (buildingData == null || buildingData.demolishing()) return;
        UUID targetId = buildingData.buildingId();
        if (targetId == null) targetId = this.buildingId;
        if (targetId == null) return;

        final UUID destroyId = targetId;
        String name = getBuildingDisplayName();
        openConfirmDialog(
                I18n.name("gui.wandscape.confirm.demolish.title", "确认拆除"),
                I18n.name("gui.wandscape.confirm.demolish.msg", "确定要拆除「%s」吗？已下发的工作将中断，部分建材将返还。", name),
                () -> {
                    PacketDistributor.sendToServer(new BuildingActionPacket(destroyId, "destroy"));
                    this.onClose();
                }
        );
    }

    protected String getBuildingDisplayName() {
        if (buildingData != null && buildingData.displayName() != null && !buildingData.displayName().isEmpty()) {
            return buildingData.displayName();
        }
        if (titleBarText != null) {
            return titleBarText.getString();
        }
        return "建筑";
    }

    protected void renderBuildingHeaderInfo(GuiGraphics g, int hx, int hy, int hw, int statusBadgeStartX) {
        // 1. Status Badge
        Component statusText = getStatusBadgeText(buildingData);
        int statusColor = getStatusBadgeColor(buildingData);
        int badgeW = font.width(statusText) + 8;
        int badgeH = 12;
        int badgeX = statusBadgeStartX;
        int badgeY = hy + (headerHeight - badgeH) / 2;

        int badgeBg = 0xAA180E14;
        int borderCol = (statusColor & 0x00FFFFFF) | 0x88000000;
        g.fill(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, badgeBg);
        g.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 1, borderCol);
        g.fill(badgeX, badgeY + badgeH - 1, badgeX + badgeW, badgeY + badgeH, borderCol);
        g.fill(badgeX, badgeY, badgeX + 1, badgeY + badgeH, borderCol);
        g.fill(badgeX + badgeW - 1, badgeY, badgeX + badgeW, badgeY + badgeH, borderCol);

        g.drawString(font, statusText, badgeX + 4, badgeY + 2, statusColor);

        statusBadgeX = badgeX;
        statusBadgeY = badgeY;
        statusBadgeW = badgeW;
        statusBadgeH = badgeH;

        // 2. Stats (Comfort, Magic, Wonder) on the right side
        int rightMargin = leftPos + panelWidth - 6;
        if (showCloseButton) rightMargin = closeBtnX - 4;
        if (showHelpButton && helpDocumentPath != null && helpButton != null) {
            rightMargin = helpButton.getX() - 4;
        }

        int iconS = 9;
        String comfortStr = String.valueOf(buildingData.comfort());
        String magicStr = String.valueOf(buildingData.magic());
        String wonderStr = String.valueOf(buildingData.wonder());

        int cW = iconS + 2 + font.width(comfortStr);
        int mW = iconS + 2 + font.width(magicStr);
        int wW = iconS + 2 + font.width(wonderStr);
        int statGap = 8;
        int totalStatsW = cW + statGap + mW + statGap + wW;

        int statsStartX = rightMargin - totalStatsW - 6;

        if (statsStartX > badgeX + badgeW + 6) {
            int statY = hy + (headerHeight - iconS) / 2;
            int textY = hy + (headerHeight - font.lineHeight) / 2;
            int curX = statsStartX;

            // Comfort
            WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_COMFORT, curX, statY, iconS, iconS, WandscapeTheme.COLOR_COMFORT);
            comfortIconX = curX; comfortIconY = statY; comfortIconW = cW; comfortIconH = iconS;
            curX += iconS + 2;
            g.drawString(font, comfortStr, curX, textY, WandscapeTheme.COLOR_COMFORT);
            curX += font.width(comfortStr) + statGap;

            // Magic
            WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_MAGIC, curX, statY, iconS, iconS, WandscapeTheme.COLOR_MAGIC);
            magicIconX = curX; magicIconY = statY; magicIconW = mW; magicIconH = iconS;
            curX += iconS + 2;
            g.drawString(font, magicStr, curX, textY, WandscapeTheme.COLOR_MAGIC);
            curX += font.width(magicStr) + statGap;

            // Wonder
            WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_WONDER, curX, statY, iconS, iconS, WandscapeTheme.COLOR_WONDER);
            wonderIconX = curX; wonderIconY = statY; wonderIconW = wW; wonderIconH = iconS;
            curX += iconS + 2;
            g.drawString(font, wonderStr, curX, textY, WandscapeTheme.COLOR_WONDER);
        } else {
            comfortIconW = magicIconW = wonderIconW = 0;
        }
    }

    protected void renderBuildingHeaderTooltips(GuiGraphics g, int mouseX, int mouseY) {
        if (!isBuildingScreen || buildingData == null) return;

        if (isInRect(mouseX, mouseY, statusBadgeX, statusBadgeY, statusBadgeW, statusBadgeH)) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(Component.literal("§6" + getBuildingDisplayName() + " §7(" + buildingData.category() + ")"));
            tooltip.add(getStatusTooltip(buildingData));
            g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
            return;
        }

        if (comfortIconW > 0 && isInRect(mouseX, mouseY, comfortIconX, comfortIconY, comfortIconW, comfortIconH)) {
            g.renderTooltip(font, Component.literal("§d舒适度: " + buildingData.comfort()), mouseX, mouseY);
            return;
        }
        if (magicIconW > 0 && isInRect(mouseX, mouseY, magicIconX, magicIconY, magicIconW, magicIconH)) {
            g.renderTooltip(font, Component.literal("§9魔力: " + buildingData.magic()), mouseX, mouseY);
            return;
        }
        if (wonderIconW > 0 && isInRect(mouseX, mouseY, wonderIconX, wonderIconY, wonderIconW, wonderIconH)) {
            g.renderTooltip(font, Component.literal("§e奇迹度: " + buildingData.wonder()), mouseX, mouseY);
            return;
        }

        if (btnRepair != null && btnRepair.visible && btnRepair.isHoveredOrFocused()) {
            if (buildingData.demolishing()) {
                g.renderTooltip(font, Component.literal("建筑正在拆除中"), mouseX, mouseY);
            } else if (buildingData.underConstruction()) {
                g.renderTooltip(font, Component.literal("撤销建造施工并返还建材"), mouseX, mouseY);
            } else if (!btnRepair.active) {
                g.renderTooltip(font, Component.literal("建筑结构完好，无需维修"), mouseX, mouseY);
            } else {
                g.renderTooltip(font, Component.literal("下发修复任务以恢复受损方块"), mouseX, mouseY);
            }
            return;
        }

        if (btnDemolish != null && btnDemolish.visible && btnDemolish.isHoveredOrFocused()) {
            if (buildingData.demolishing()) {
                g.renderTooltip(font, Component.literal("拆除任务执行中..."), mouseX, mouseY);
            } else {
                g.renderTooltip(font, Component.literal("拆除该建筑并返还建材（需确认）"), mouseX, mouseY);
            }
            return;
        }
    }

    protected static Component getStatusBadgeText(BuildingDebugResponsePacket data) {
        if (data.demolishing()) return I18n.name("gui.wandscape.building_status.demolishing", "拆除中");
        if (data.underConstruction()) {
            return data.constructionStarted()
                    ? I18n.name("gui.wandscape.building_status.under_construction", "施工中")
                    : I18n.name("gui.wandscape.building_status.waiting_materials", "等待材料");
        }
        if (data.needsRepair()) {
            return I18n.name("gui.wandscape.building_status.needs_repair", "受损需修");
        }
        return I18n.name("gui.wandscape.building_status.ok", "正常运转");
    }

    protected static int getStatusBadgeColor(BuildingDebugResponsePacket data) {
        if (data.demolishing()) return 0xFFFF6666;
        if (data.underConstruction()) {
            return data.constructionStarted() ? 0xFF88AAFF : 0xFFFFCC66;
        }
        if (data.needsRepair()) return 0xFFFF9944;
        return 0xFF88CC88;
    }

    protected static Component getStatusTooltip(BuildingDebugResponsePacket data) {
        if (data.demolishing()) {
            return Component.literal("§c状态: 正在拆除中，NPC 正在清理结构方块。");
        }
        if (data.underConstruction()) {
            return data.constructionStarted()
                    ? Component.literal("§b状态: 施工中，NPC 正在搬运材料与砌筑。")
                    : Component.literal("§e状态: 等待材料中，仓库备齐建材后方可动工。");
        }
        if (data.needsRepair()) {
            return Component.literal("§6状态: 结构部分受损，点击下方「修复」按钮下发维修任务。");
        }
        return Component.literal("§a状态: 正常运作，结构完好。");
    }

    // ── Close button ──

    protected void renderCloseButton(GuiGraphics g, int mouseX, int mouseY) {
        closeBtnState = isInRect(mouseX, mouseY, closeBtnX, closeBtnY, closeBtnW, closeBtnH) ? 1 : 0;
        SkinRender.drawCloseButton(g, closeBtnX, closeBtnY, closeBtnW, closeBtnH, closeBtnState);
    }

    protected boolean isCloseHit(double mouseX, double mouseY) {
        if (!showCloseButton) return false;
        return isInRect(mouseX, mouseY, closeBtnX, closeBtnY, closeBtnW, closeBtnH);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Confirm dialog open: it consumes all clicks, blocking the screen behind.
        if (confirmDialog.isOpen()) {
            return confirmDialog.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 && isCloseHit(mouseX, mouseY)) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ── Drawing helpers ──

    /**
     * Glow border — 2 rings fading from {@code color} at the edge
     * into transparency. Each ring uniform on all 4 sides — no corner seams.
     */
    protected static void drawGlowBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        int c0 = color;
        int c1 = (color & 0x00FFFFFF) | 0x66000000;

        // Ring 0 (outermost)
        g.fill(x, y, x + w, y + 1, c0);
        g.fill(x, y + h - 1, x + w, y + h, c0);
        g.fill(x, y, x + 1, y + h, c0);
        g.fill(x + w - 1, y, x + w, y + h, c0);

        // Ring 1 (fade)
        g.fill(x + 1, y + 1, x + w - 1, y + 2, c1);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, c1);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, c1);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, c1);
    }

    /** Gradient box with glow border for tabs, cards, etc. */
    protected static void drawMinimalBox(GuiGraphics g, int x, int y, int w, int h,
                                         boolean active, boolean hovered) {
        if (active) {
            g.fillGradient(x, y, x + w, y + h, GLASS_BOX_TOP, GLASS_BOX_BOTTOM);
            drawGlowBorder(g, x, y, w, h, MedievalColors.BORDER_GOLD);
        } else if (hovered) {
            g.fillGradient(x, y, x + w, y + h,
                    MedievalColors.BUTTON_BG_HOVER, MedievalColors.PANEL_TITLE_BG);
            drawGlowBorder(g, x, y, w, h, MedievalColors.BORDER_GOLD_DARK);
        } else {
            g.fillGradient(x, y, x + w, y + h, 0x992A1E18, 0x991A0E08);
            g.fill(x, y, x + w, y + 1, MedievalColors.BORDER_GOLD_DARK);
            g.fill(x, y + h - 1, x + w, y + h, MedievalColors.BORDER_GOLD_DARK);
            g.fill(x, y, x + 1, y + h, MedievalColors.BORDER_GOLD_DARK);
            g.fill(x + w - 1, y, x + w, y + h, MedievalColors.BORDER_GOLD_DARK);
        }
    }

    /** Inset dark field with subtle inner shadow. */
    protected static void drawInsetField(GuiGraphics g, int x, int y, int w, int h) {
        g.fillGradient(x, y, x + w, y + h, 0x44000000, 0x33000000);
        g.fill(x, y, x + w, y + 1, 0x55000000);
        g.fill(x, y, x + 1, y + h, 0x55000000);
        g.fill(x, y + h - 1, x + w, y + h, 0x22FFFFFF);
        g.fill(x + w - 1, y, x + w, y + h, 0x22FFFFFF);
    }

    protected static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public void addAnimation(MedievalAnimation animation) {
        animations.add(animation);
    }
}
