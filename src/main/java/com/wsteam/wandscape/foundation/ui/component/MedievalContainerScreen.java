package com.wsteam.wandscape.foundation.ui.component;

import com.wsteam.wandscape.WandscapeClient;
import com.wsteam.wandscape.content.building.projection.client.BuildingDebugClientState;
import com.wsteam.wandscape.content.building.projection.network.BuildingActionPacket;
import com.wsteam.wandscape.content.building.projection.network.BuildingDebugRequestPacket;
import com.wsteam.wandscape.content.building.projection.network.BuildingDebugResponsePacket;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.ReplayProtectedScreen;
import com.wsteam.wandscape.foundation.ui.guidebook.GuideFacade;
import com.wsteam.wandscape.foundation.ui.skin.SkinRender;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Base screen for Wandscape UIs that need a real container menu (vanilla slots).
 *
 * <p>存在原因：{@link MedievalScreen} 继承 {@code Screen}，而带槽位的界面必须继承
 * {@link AbstractContainerScreen}（槽位点击/Shift 快捷移动/双击聚合/数字键换位/光标物品/
 * 拖拽分配全由原版实现，排序 mod 也认这些真 {@link net.minecraft.world.inventory.Slot}）。
 * Java 单继承下二者无法合一，于是本类把 {@link MedievalScreen} 的<b>面板格式</b>搬到容器屏这一支：
 * 渐变玻璃面板、金边、紫色标题栏（标题 + 建筑状态徽标 + 舒适/魔力/奇迹）、关闭/帮助按钮、
 * Feedback 浮条、创建者页脚、确认弹窗、修复/拆除按钮。
 *
 * <p>绘图助手直接复用同包的 {@link MedievalScreen} 静态方法（同一套像素），所以两支的外框
 * 逐像素一致；子类只需实现 {@link #initContent()} 与 {@link #renderContent}。
 *
 * <p>子类约定：
 * <ul>
 *   <li>面板尺寸走构造函数，落到原版的 {@code imageWidth/imageHeight}（原版据此居中）。</li>
 *   <li>面板内容从 {@code topPos + headerHeight} 开始，头部那 22px 是基类占的。</li>
 *   <li>建筑上下文用 {@link #setBuildingContext}；从非建筑入口（如便携终端）打开的界面别调，
 *       面板就只显示标题，不出现修复/拆除。</li>
 * </ul>
 */
public abstract class MedievalContainerScreen<M extends AbstractContainerMenu>
        extends AbstractContainerScreen<M> implements ReplayProtectedScreen, ScreenFeedbackHost, BuildingDataHost {

    // ── 头部 ──
    protected int headerHeight = 22;
    protected Component titleBarText;
    protected int titleXOffset = 10;

    // ── 内置关闭按钮 ──
    protected boolean showCloseButton;
    protected int closeBtnX, closeBtnY, closeBtnW = 18, closeBtnH = 14;
    protected int closeBtnState;

    // ── 内置帮助按钮 ──
    protected boolean showHelpButton;
    protected String helpDocumentPath;
    protected HelpButton helpButton;

    // ── 创建者页脚 ──
    /** 底部为创建者署名保留的高度（子类排版可用；{@link #creatorFooterY()} 默认贴面板底）。 */
    protected static final int CREATOR_FOOTER_H = 24;
    private String buildingCreator = "";

    // ── 玻璃面板配色（与 MedievalScreen 同值） ──
    private static final int GLASS_TOP = 0xF5261A10;
    private static final int GLASS_BOTTOM = 0xF5120804;

    // ── 瞬态反馈 toast ──
    private static final long FEEDBACK_DURATION_MS = 3000L;
    private Component feedback;
    private int feedbackColor;
    private long feedbackExpireTick;

    // ── 可复用确认弹窗 ──
    protected final MedievalConfirmDialog confirmDialog = new MedievalConfirmDialog();

    // ── 建筑头部与操作（有建筑上下文时才生效） ──
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

    private int statusBadgeX, statusBadgeY, statusBadgeW, statusBadgeH;
    private int comfortIconX, comfortIconY, comfortIconW, comfortIconH;
    private int magicIconX, magicIconY, magicIconW, magicIconH;
    private int wonderIconX, wonderIconY, wonderIconW, wonderIconH;

    protected MedievalContainerScreen(M menu, Inventory playerInventory, Component title,
                                      int panelWidth, int panelHeight) {
        super(menu, playerInventory, title);
        this.imageWidth = panelWidth;
        this.imageHeight = panelHeight;
        this.titleBarText = title;
    }

    protected void setTitleBar(Component title) {
        this.titleBarText = title;
    }

    // ── 建筑上下文 ──

    /**
     * 绑定建筑上下文（面板头部显示状态/属性，并出现修复/拆除按钮）。
     * 允许在 {@code init()} 之后调用（数据包晚于开屏到达的场景），此时会补建按钮并请求建筑数据。
     */
    public void setBuildingContext(@Nullable UUID id, @Nullable BlockPos pos) {
        this.buildingId = id;
        this.buildingPos = pos;
        this.isBuildingScreen = true;
        if (this.minecraft != null && this.minecraft.screen == this) {
            if (btnRepair == null) {
                initBuildingActionButtons();
            }
            if (buildingData == null && buildingPos != null) {
                Net.toServer(new BuildingDebugRequestPacket(buildingPos));
            }
        }
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

    /** 服务端建筑数据回填（{@code BuildingDebugResponsePacket} 的客户端分发口调用）。 */
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

    // ── 确认弹窗 / 反馈 / 创建者 ──

    protected void openConfirmDialog(Component message, Runnable onConfirm) {
        confirmDialog.open(message, onConfirm);
    }

    protected void openConfirmDialog(Component title, Component message, Runnable onConfirm) {
        confirmDialog.open(title, message, onConfirm);
    }

    public void setCreator(String creator) {
        this.buildingCreator = creator != null ? creator : "";
    }

    /** 创建者署名基线 Y；默认贴面板底部，子类内容占满时可覆盖。 */
    protected int creatorFooterY() {
        return topPos + imageHeight - CREATOR_FOOTER_H;
    }

    protected void renderCreatorFooter(GuiGraphics g) {
        if (buildingCreator.isBlank()) return;
        String text = I18n.name("gui.wandscape.common.creator_label", "Creator").getString()
                + ": " + buildingCreator;
        g.drawString(font, text, leftPos + 16, creatorFooterY(), MedievalColors.TEXT_DIM);
    }

    @Override
    public void showFeedback(Component message, int color) {
        this.feedback = message;
        this.feedbackColor = color;
        this.feedbackExpireTick = System.currentTimeMillis() + FEEDBACK_DURATION_MS;
    }

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

        g.fillGradient(x, y, x + w, y + h, 0xEE2A1C14, 0xEE120804);
        int borderCol = (feedbackColor & 0x00FFFFFF) | 0xDD000000;
        g.fill(x, y, x + w, y + 1, borderCol);
        g.fill(x, y + h - 1, x + w, y + h, borderCol);
        g.fill(x, y, x + 1, y + h, borderCol);
        g.fill(x + w - 1, y, x + w, y + h, borderCol);

        g.drawString(font, feedback, x + pad, y + (h - font.lineHeight) / 2, feedbackColor);
    }

    // ── 生命周期 ──

    @Override
    protected void init() {
        super.init(); // 原版据 imageWidth/imageHeight 定 leftPos/topPos
        positionPanel();

        if (showCloseButton) {
            closeBtnX = leftPos + imageWidth - closeBtnW - 6;
            closeBtnY = topPos + (headerHeight - closeBtnH) / 2;
        }
        if (showHelpButton && helpDocumentPath != null) {
            int helpW = 14;
            int helpH = 14;
            int helpX = showCloseButton ? closeBtnX - helpW - 4 : leftPos + imageWidth - helpW - 6;
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
                Net.toServer(new BuildingDebugRequestPacket(this.buildingPos));
            }
            // rebuildWidgets（如切页签）会 clearWidgets 掉旧按钮，字段引用必须一起清，
            // 否则 initBuildingActionButtons 的存在性守卫会拒绝重建。
            this.btnRepair = null;
            this.btnDemolish = null;
            initBuildingActionButtons();
        }

        initContent();
    }

    /**
     * 面板落位微调（在头部/按钮几何计算之前执行）。
     * 默认即原版居中；矮屏上想在面板外留出浮动页签等空间时覆盖本方法调整 {@code topPos}。
     */
    protected void positionPanel() {}

    /** 子类在此构建自己的控件（原版容器屏的 init 内容）。 */
    protected void initContent() {}

    public void openHelpDocument() {
        if (helpDocumentPath != null && minecraft != null) {
            GuideFacade.open(this, helpDocumentPath);
        }
    }

    // ── 渲染 ──

    /** 面板 + 头部 + 内容背景；槽位与控件随后由原版流程画在其上。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(g);
        g.fillGradient(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight,
                GLASS_TOP, GLASS_BOTTOM);
        MedievalScreen.drawGlowBorder(g, leftPos, topPos, imageWidth, imageHeight,
                MedievalColors.BORDER_GOLD);
        if (titleBarText != null) {
            renderMinimalHeader(g);
        }
        renderContent(g, mouseX, mouseY, partialTick);
    }

    /** 原版抽象方法：面板背景已由 {@link #renderBackground} 画完，这里交给内容钩子。 */
    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
    }

    /** 子类内容绘制（面板内、控件与槽位之下）。 */
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    /** 子类前景绘制（tooltip/浮层；确认弹窗未开时才调）。 */
    protected void renderForeground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    /** 标题栏由基类皮肤绘制，不画原版标题/背包标签。 */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {}

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        renderCreatorFooter(g);
        renderFeedback(g);
        // 原版容器屏的 render 不画物品 tooltip（悬停槽位由这里补）
        renderTooltip(g, mouseX, mouseY);

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

    // ── 头部 ──

    protected void renderMinimalHeader(GuiGraphics g) {
        int hx = leftPos + 1;
        int hy = topPos + 1;
        int hw = imageWidth - 2;

        g.fillGradient(hx, hy, hx + hw, hy + headerHeight, 0xFF502870, 0xFF1A0830);

        int sepY = hy + headerHeight;
        int sc = MedievalColors.BORDER_GOLD;
        g.fill(hx, sepY, hx + hw, sepY + 1, sc);
        g.fill(hx, sepY + 1, hx + hw, sepY + 2, (sc & 0x00FFFFFF) | 0x66000000);

        g.fillGradient(hx, hy, hx + 3, hy + headerHeight, 0xFFD4A840, 0xFF6A4020);

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

    protected void renderBuildingHeaderInfo(GuiGraphics g, int hx, int hy, int hw, int statusBadgeStartX) {
        Component statusText = MedievalScreen.getStatusBadgeText(buildingData);
        int statusColor = MedievalScreen.getStatusBadgeColor(buildingData);
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

        int rightMargin = leftPos + imageWidth - 6;
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

            WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_COMFORT, curX, statY, iconS, iconS, WandscapeTheme.COLOR_COMFORT);
            comfortIconX = curX; comfortIconY = statY; comfortIconW = cW; comfortIconH = iconS;
            curX += iconS + 2;
            g.drawString(font, comfortStr, curX, textY, WandscapeTheme.COLOR_COMFORT);
            curX += font.width(comfortStr) + statGap;

            WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_MAGIC, curX, statY, iconS, iconS, WandscapeTheme.COLOR_MAGIC);
            magicIconX = curX; magicIconY = statY; magicIconW = mW; magicIconH = iconS;
            curX += iconS + 2;
            g.drawString(font, magicStr, curX, textY, WandscapeTheme.COLOR_MAGIC);
            curX += font.width(magicStr) + statGap;

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

        if (MedievalScreen.isInRect(mouseX, mouseY, statusBadgeX, statusBadgeY, statusBadgeW, statusBadgeH)) {
            List<Component> tooltip = new ArrayList<>();
            String category = buildingData.category();
            tooltip.add(Component.literal("§6" + getBuildingDisplayName() + " §7("
                    + I18n.name("category.wandscape." + category, category).getString() + ")"));
            tooltip.add(MedievalScreen.getStatusTooltip(buildingData));
            g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
            return;
        }

        if (comfortIconW > 0 && MedievalScreen.isInRect(mouseX, mouseY, comfortIconX, comfortIconY, comfortIconW, comfortIconH)) {
            g.renderTooltip(font, I18n.name("gui.wandscape.building_stat.comfort_tip",
                    "§d舒适度: %s", buildingData.comfort()), mouseX, mouseY);
            return;
        }
        if (magicIconW > 0 && MedievalScreen.isInRect(mouseX, mouseY, magicIconX, magicIconY, magicIconW, magicIconH)) {
            g.renderTooltip(font, I18n.name("gui.wandscape.building_stat.magic_tip",
                    "§9魔力: %s", buildingData.magic()), mouseX, mouseY);
            return;
        }
        if (wonderIconW > 0 && MedievalScreen.isInRect(mouseX, mouseY, wonderIconX, wonderIconY, wonderIconW, wonderIconH)) {
            g.renderTooltip(font, I18n.name("gui.wandscape.building_stat.wonder_tip",
                    "§e奇迹度: %s", buildingData.wonder()), mouseX, mouseY);
            return;
        }

        if (btnRepair != null && btnRepair.visible && btnRepair.isHoveredOrFocused()) {
            if (buildingData.demolishing()) {
                g.renderTooltip(font, I18n.name("gui.wandscape.building_action.repair_demolishing",
                        "建筑正在拆除中"), mouseX, mouseY);
            } else if (buildingData.underConstruction()) {
                g.renderTooltip(font, I18n.name("gui.wandscape.building_action.repair_cancel_construction",
                        "撤销建造施工并退还尚未建成的建材"), mouseX, mouseY);
            } else if (!btnRepair.active) {
                g.renderTooltip(font, I18n.name("gui.wandscape.building_action.repair_not_needed",
                        "建筑结构完好，无需维修"), mouseX, mouseY);
            } else {
                g.renderTooltip(font, I18n.name("gui.wandscape.building_action.repair_send",
                        "下发修复任务以恢复受损方块"), mouseX, mouseY);
            }
            return;
        }

        if (btnDemolish != null && btnDemolish.visible && btnDemolish.isHoveredOrFocused()) {
            if (buildingData.demolishing()) {
                g.renderTooltip(font, I18n.name("gui.wandscape.building_action.demolish_running",
                        "拆除任务执行中..."), mouseX, mouseY);
            } else {
                g.renderTooltip(font, I18n.name("gui.wandscape.building_action.demolish_send",
                        "拆除该建筑并返还建材（需确认）"), mouseX, mouseY);
            }
        }
    }

    // ── 修复 / 拆除 ──

    protected void initBuildingActionButtons() {
        if (!isBuildingScreen || btnRepair != null) return;

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
        } else {
            bx = leftPos + imageWidth - 12 - (btnW * 2 + gap);
            by = topPos + imageHeight - 20;
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
                this::onBuildingDemolishClicked);

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

        if (demolishing) {
            btnDemolish.setMessage(I18n.name("gui.wandscape.building_action.demolishing", "拆除中..."));
            btnDemolish.active = false;
        } else {
            btnDemolish.setMessage(I18n.name("gui.wandscape.building_action.destroy", "拆除"));
            btnDemolish.active = true;
        }

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
                    I18n.name("gui.wandscape.confirm.cancel.msg", "确定要撤销「%s」的建造吗？已建部分将一并清除，只退还未开工或未建成部分的建材。", name),
                    () -> {
                        Net.toServer(new BuildingActionPacket(cancelId, "cancel"));
                        this.onClose();
                    }
            );
            return;
        }

        if (buildingData.needsRepair() && !buildingData.demolishing()) {
            Net.toServer(new BuildingActionPacket(targetId, "repair"));
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
                I18n.name("gui.wandscape.confirm.demolish.msg", "确定要拆除「%s」吗？已下发的工作将中断，拆除不再返还任何建材。", name),
                () -> {
                    Net.toServer(new BuildingActionPacket(destroyId, "destroy"));
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
        return I18n.name("gui.wandscape.common.building", "建筑").getString();
    }

    // ── 关闭按钮 ──

    protected void renderCloseButton(GuiGraphics g, int mouseX, int mouseY) {
        closeBtnState = MedievalScreen.isInRect(mouseX, mouseY, closeBtnX, closeBtnY, closeBtnW, closeBtnH) ? 1 : 0;
        SkinRender.drawCloseButton(g, closeBtnX, closeBtnY, closeBtnW, closeBtnH, closeBtnState);
    }

    protected boolean isCloseHit(double mouseX, double mouseY) {
        if (!showCloseButton) return false;
        return MedievalScreen.isInRect(mouseX, mouseY, closeBtnX, closeBtnY, closeBtnW, closeBtnH);
    }

    // ── 面板皮肤（同包直取 MedievalScreen 的同一套像素；本包外子类走这三个转发） ──

    protected static void drawGlowBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        MedievalScreen.drawGlowBorder(g, x, y, w, h, color);
    }

    protected static void drawMinimalBox(GuiGraphics g, int x, int y, int w, int h,
                                         boolean active, boolean hovered) {
        MedievalScreen.drawMinimalBox(g, x, y, w, h, active, hovered);
    }

    protected static void drawInsetField(GuiGraphics g, int x, int y, int w, int h) {
        MedievalScreen.drawInsetField(g, x, y, w, h);
    }

    protected static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return MedievalScreen.isInRect(mx, my, x, y, w, h);
    }

    // ── 输入 ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (confirmDialog.isOpen()) {
            return confirmDialog.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 && isCloseHit(mouseX, mouseY)) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (confirmDialog.isOpen()) {
            return confirmDialog.keyPressed(keyCode, scanCode, modifiers);
        }
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (showHelpButton && helpDocumentPath != null
                && !(getFocused() instanceof EditBox box && box.canConsumeInput())
                && WandscapeClient.GUIDEBOOK_TOGGLE.matches(keyCode, scanCode)) {
            openHelpDocument();
            return true;
        }
        return false;
    }
}
