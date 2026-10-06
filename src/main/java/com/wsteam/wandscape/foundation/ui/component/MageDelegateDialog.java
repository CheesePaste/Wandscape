package com.wsteam.wandscape.foundation.ui.component;

import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 「委派法师」选择框（模态）——建筑委派功能的选人界面，自包含、不依赖 widget 生命周期，
 * 接入姿势与 {@link MedievalConfirmDialog} 完全一致：宿主 Screen 在渲染末尾调
 * {@link #render}，在 mouseClicked / keyPressed / mouseScrolled 开头调对应方法。
 *
 * <p>泛化在基类里：本框只认「一行 uuid + 名字 + 状态文案」，具体哪些建筑支持委派、
 * 委派后任务怎么派，全在 {@code BuildingDelegation} 与任务域，与 UI 无关。
 *
 * <p>一次委派 = 点一名法师所在行（点击即生效，不再弹二次确认）；底部另有
 * 「取消委派」（仅当前已有委派时可点）与「关闭」。
 */
public final class MageDelegateDialog {

    private static final int BOX_W = 300;
    private static final int HEADER_H = 16;
    private static final int PAD = 12;
    private static final int ROW_H = 24;
    private static final int MAX_LIST_H = 144;
    private static final int BTN_H = 18;
    private static final int BTN_W = 84;
    private static final int BTN_GAP = 10;

    private static final int DIM_COLOR = 0xC0000000;
    private static final int HEADER_TOP = 0xFF502870;
    private static final int HEADER_BOTTOM = 0xFF1A0830;
    private static final int HEADER_ACCENT_TOP = 0xFFD4A840;
    private static final int HEADER_ACCENT_BOTTOM = 0xFF6A4020;
    private static final int BOX_TOP = 0xFF2A1C12;
    private static final int BOX_BOTTOM = 0xFF140A06;

    /** 一名候选法师（与 {@code BuildingDelegateDataPacket.Row} 同形，UI 层不依赖网络类型）。 */
    public record Row(UUID mageUuid, String name, String state,
                      @Nullable UUID delegatedBuilding, String delegatedBuildingName) {}

    private final MageList list = new MageList(0, 0, BOX_W - PAD * 2, MAX_LIST_H);

    private boolean open;
    private UUID buildingId;
    private boolean hasCurrent;
    private Consumer<Row> onPick;
    private Runnable onClear;

    // 渲染期算出的命中矩形
    private int clearX, clearY, clearW, clearH;
    private int closeX, closeY, closeW, closeH;

    public MageDelegateDialog() {
        // 点行即委派：行点击回调比"读返回值 + 取 selected"更准（拖滚动条不会误触发行选择）
        list.setOnRowClick((row, index, button) -> {
            if (button != 0) return;
            Consumer<Row> action = onPick;
            close();
            if (action != null) action.accept(row);
        });
    }

    /**
     * 打开选择框。
     *
     * @param buildingId 目标建筑（列表里标「当前委派」用）
     * @param hasCurrent 该建筑当前是否已有委派法师（决定「取消委派」按钮是否可用）
     * @param rows       候选法师
     * @param onPick     选中某名法师
     * @param onClear    点「取消委派」
     */
    public void open(UUID buildingId, boolean hasCurrent, List<Row> rows,
                     Consumer<Row> onPick, Runnable onClear) {
        this.buildingId = buildingId;
        this.hasCurrent = hasCurrent;
        this.onPick = onPick;
        this.onClear = onClear;
        this.list.setItems(rows != null ? rows : List.of());
        this.open = true;
    }

    public void close() {
        this.open = false;
        this.onPick = null;
        this.onClear = null;
        this.list.setItems(List.of());
    }

    public boolean isOpen() {
        return open;
    }

    // ── 输入 ──

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!open) return false;
        if (button == 0) {
            if (isInRect(mouseX, mouseY, closeX, closeY, closeW, closeH)) {
                close();
                return true;
            }
            if (hasCurrent && isInRect(mouseX, mouseY, clearX, clearY, clearW, clearH)) {
                Runnable action = onClear;
                close();
                if (action != null) action.run();
                return true;
            }
        }
        if (list.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return true; // 模态：点背景一律吞掉
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!open) return false;
        list.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        return true;
    }

    /** 拖拽转发给列表（滚动条拖动）；模态期间一律消费。 */
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!open) return false;
        list.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!open) return false;
        list.mouseReleased(mouseX, mouseY, button);
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!open) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
        }
        return true;
    }

    // ── 渲染 ──

    public void render(GuiGraphics g, int screenW, int screenH, int mouseX, int mouseY) {
        if (!open) return;
        Font font = Minecraft.getInstance().font;
        List<Row> rows = list.items;
        boolean empty = rows.isEmpty();

        int listH = empty ? 20 : Math.min(MAX_LIST_H, Math.max(ROW_H, rows.size() * ROW_H));
        int boxH = HEADER_H + PAD + listH + PAD + BTN_H + PAD;
        int bx = (screenW - BOX_W) / 2;
        int by = (screenH - boxH) / 2;

        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 500.0F);

        g.fillGradient(0, 0, screenW, screenH, DIM_COLOR, DIM_COLOR);

        g.fillGradient(bx, by, bx + BOX_W, by + boxH, BOX_TOP, BOX_BOTTOM);
        MedievalScreen.drawGlowBorder(g, bx, by, BOX_W, boxH, MedievalColors.BORDER_GOLD);

        // 标题栏（与各面板 header 同款）
        g.fillGradient(bx + 1, by + 1, bx + BOX_W - 1, by + HEADER_H, HEADER_TOP, HEADER_BOTTOM);
        g.fill(bx + 1, by + HEADER_H, bx + BOX_W - 1, by + HEADER_H + 1, MedievalColors.BORDER_GOLD);
        g.fillGradient(bx + 1, by + 1, bx + 4, by + HEADER_H, HEADER_ACCENT_TOP, HEADER_ACCENT_BOTTOM);
        g.drawString(font, I18n.name("gui.wandscape.delegate.title", "委派法师"),
                bx + 8, by + (HEADER_H - font.lineHeight) / 2 + 1, MedievalColors.TEXT_WARM_WHITE);

        int listX = bx + PAD;
        int listY = by + HEADER_H + PAD;

        if (empty) {
            g.drawString(font, I18n.name("gui.wandscape.delegate.empty", "本镇暂无可委派的法师"),
                    listX + 2, listY + 6, MedievalColors.TEXT_MUTED);
        } else {
            list.setX(listX);
            list.setY(listY);
            // 高度随行数收缩：列表的裁剪框与滚动条用的是自身 height，
            // 固定 144 会让滚动条拖到按钮行上去。
            list.setHeight(listH);
            list.render(g, mouseX, mouseY, 0f);
        }

        // 底部按钮：取消委派（左，仅已有委派时）/ 关闭（右）
        int btnY = by + boxH - BTN_H - PAD;
        if (hasCurrent) {
            clearW = BTN_W;
            clearH = BTN_H;
            clearX = bx + PAD;
            clearY = btnY;
            drawButton(g, font, clearX, clearY, clearW, clearH,
                    I18n.name("gui.wandscape.delegate.clear", "取消委派"),
                    isInRect(mouseX, mouseY, clearX, clearY, clearW, clearH), false);
        } else {
            clearW = 0;
        }
        closeW = BTN_W;
        closeH = BTN_H;
        closeX = bx + BOX_W - PAD - BTN_W;
        closeY = btnY;
        drawButton(g, font, closeX, closeY, closeW, closeH,
                I18n.name("gui.wandscape.delegate.close", "关闭"),
                isInRect(mouseX, mouseY, closeX, closeY, closeW, closeH), true);

        g.flush();
        g.pose().popPose();
    }

    private static void drawButton(GuiGraphics g, Font font, int x, int y, int w, int h,
                                   Component label, boolean hovered, boolean primary) {
        int bgTop = hovered ? MedievalColors.BUTTON_BG_HOVER : (primary ? 0xFF3A2818 : 0xFF2A1E18);
        int bgBottom = hovered ? MedievalColors.PANEL_TITLE_BG : (primary ? 0xFF1E100A : 0xFF140C08);
        int border = primary ? MedievalColors.BORDER_GOLD
                : (hovered ? MedievalColors.BORDER_GOLD : MedievalColors.BORDER_GOLD_DARK);
        g.fillGradient(x, y, x + w, y + h, bgTop, bgBottom);
        MedievalScreen.drawGlowBorder(g, x, y, w, h, border);
        int color = primary ? (hovered ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE)
                : (hovered ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED);
        g.drawCenteredString(font, label, x + w / 2, y + (h - font.lineHeight) / 2, color);
    }

    private static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return w > 0 && h > 0 && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 两行一行的滚动列表：第一行名字，第二行状态（空闲/执行中/已委派某建筑）。 */
    private final class MageList extends ScrollableList<Row> {

        MageList(int x, int y, int width, int height) {
            super(x, y, width, height, ROW_H);
        }

        @Override
        protected void renderRow(GuiGraphics g, Row item, int x, int y,
                                 int index, boolean selected, boolean hovered) {
            Font font = Minecraft.getInstance().font;
            int nameColor = selected ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE;
            g.drawString(font, item.name() != null ? item.name() : "?", x, y + 3, nameColor);
            g.drawString(font, detail(item), x, y + 3 + font.lineHeight, MedievalColors.TEXT_MUTED);
        }

        private Component detail(Row row) {
            if (row.delegatedBuilding() != null) {
                if (row.delegatedBuilding().equals(buildingId)) {
                    return I18n.name("gui.wandscape.delegate.row_current", "当前委派");
                }
                return I18n.name("gui.wandscape.delegate.row_delegated", "已委派: %s",
                        row.delegatedBuildingName());
            }
            return switch (row.state() != null ? row.state() : "IDLE") {
                case "FOLLOWING" -> I18n.name("gui.wandscape.delegate.row_following", "跟随玩家中");
                case "BUSY" -> I18n.name("gui.wandscape.delegate.row_busy", "执行任务中");
                default -> I18n.name("gui.wandscape.delegate.row_idle", "空闲");
            };
        }
    }
}
