package com.wsteam.wandscape.compat.patchouli;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ScreenEvent;
import vazkii.patchouli.api.BookDrawScreenEvent;

import java.lang.reflect.Field;

/**
 * 帕秋莉手册原生代码绘制渲染器。
 *
 * <p>完全脱离低分辨率/粗糙的书本底图，采用纯原生代码（GuiGraphics）绘制：
 * <ul>
 *   <li>黑曜靛蓝（Obsidian-Indigo）真皮书壳与双道烫金滚边</li>
 *   <li>四角欧式古典蔓藤花纹角扣（Filigree Brackets）</li>
 *   <li>中央 3D 弧面书脊与三道嵌铆金箍装订环</li>
 *   <li>双开暖象牙白羊皮纸（Antique Ivory Vellum）内页</li>
 *   <li>中缝 3D 装订弧度下凹阴影与外沿千层页厚度质感</li>
 *   <li>真丝绯红书签缎带与金丝流苏</li>
 *   <li>矢量翻页箭头与衔尾蛇风格返回键悬浮微光动效</li>
 * </ul>
 */
public final class PatchouliBookRenderer {

    // === 调色板：奥术秘典风格 ===
    private static final int COLOR_COVER_BG = 0xFF131627;
    private static final int COLOR_COVER_HIGHLIGHT = 0xFF252A48;
    private static final int COLOR_COVER_SHADOW = 0xFF0A0C16;
    private static final int COLOR_GOLD_OUTER = 0xFFC5A059;
    private static final int COLOR_GOLD_INNER = 0xFFE2C172;
    private static final int COLOR_GOLD_BRIGHT = 0xFFFBE8A6;
    private static final int COLOR_SEAM_CREASE = 0xFF140C04;

    // 羊皮纸渐变
    private static final int COLOR_PAGE_TOP = 0xFFF8F4EA;
    private static final int COLOR_PAGE_BOTTOM = 0xFFF0E8D4;
    private static final int COLOR_PAGE_BORDER = 0xFFDDD2B8;
    private static final int COLOR_PAGE_EDGE_1 = 0xFFD6CBB0;
    private static final int COLOR_PAGE_EDGE_2 = 0xFFE2D7BE;

    // 矢量按键调色板
    private static final int COLOR_BTN_BG_NORMAL = 0xF01A1D2E;
    private static final int COLOR_BTN_BG_HOVER = 0xFF2C314E;
    private static final int COLOR_BTN_RIM_NORMAL = 0xFFC5A059;
    private static final int COLOR_BTN_RIM_HOVER = 0xFFFFE890;
    private static final int COLOR_GLYPH_NORMAL = 0xFFE5C378;
    private static final int COLOR_GLYPH_HOVER = 0xFFFFFBEA;

    // === 运行时反射缓存（隔离非 API 类） ===
    private static final Field SCALE_FACTOR_FIELD;
    private static final Field BOOK_LEFT_FIELD;
    private static final Field BOOK_TOP_FIELD;
    private static final Field BOOK_FIELD;
    private static final Field BOOK_ID_FIELD;

    static {
        Field sf = null, bl = null, bt = null, b = null, bid = null;
        try {
            Class<?> guiBookClass = Class.forName("vazkii.patchouli.client.book.gui.GuiBook");
            sf = guiBookClass.getDeclaredField("scaleFactor");
            sf.setAccessible(true);
            bl = guiBookClass.getField("bookLeft");
            bt = guiBookClass.getField("bookTop");
            b = guiBookClass.getField("book");
            Class<?> bookClass = Class.forName("vazkii.patchouli.common.book.Book");
            bid = bookClass.getField("id");
        } catch (Throwable ignored) {}
        SCALE_FACTOR_FIELD = sf;
        BOOK_LEFT_FIELD = bl;
        BOOK_TOP_FIELD = bt;
        BOOK_FIELD = b;
        BOOK_ID_FIELD = bid;
    }

    private PatchouliBookRenderer() {}

    /**
     * 在客户端生命周期初始化注册。
     */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Pre.class, PatchouliBookRenderer::onScreenRenderPre);
        NeoForge.EVENT_BUS.addListener(BookDrawScreenEvent.class, PatchouliBookRenderer::onBookDrawScreen);
    }

    /**
     * 判断当前屏幕是否为本模组的帕秋莉手册。
     */
    private static boolean isTargetBookScreen(Screen screen) {
        if (screen == null) return false;
        if (BOOK_FIELD != null && BOOK_ID_FIELD != null) {
            try {
                Object bookObj = BOOK_FIELD.get(screen);
                if (bookObj != null) {
                    Object idObj = BOOK_ID_FIELD.get(bookObj);
                    return PatchouliCompat.BOOK_ID.equals(idObj);
                }
            } catch (Throwable ignored) {}
        }
        return PatchouliCompat.isBookOpen();
    }

    private static float getScaleFactor(Screen screen) {
        if (SCALE_FACTOR_FIELD != null && screen != null) {
            try {
                return SCALE_FACTOR_FIELD.getFloat(screen);
            } catch (Throwable ignored) {}
        }
        return 1.0f;
    }

    private static int getBookLeft(Screen screen, float scaleFactor) {
        if (BOOK_LEFT_FIELD != null && screen != null) {
            try {
                return BOOK_LEFT_FIELD.getInt(screen);
            } catch (Throwable ignored) {}
        }
        int w = scaleFactor != 1.0f ? (int) (screen.width / scaleFactor) : screen.width;
        return w / 2 - 136;
    }

    private static int getBookTop(Screen screen, float scaleFactor) {
        if (BOOK_TOP_FIELD != null && screen != null) {
            try {
                return BOOK_TOP_FIELD.getInt(screen);
            } catch (Throwable ignored) {}
        }
        int h = scaleFactor != 1.0f ? (int) (screen.height / scaleFactor) : screen.height;
        return h / 2 - 90;
    }

    /**
     * 屏幕绘制前：原生绘制精美秘典外壳、装订书脊与双开羊皮纸底图。
     */
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        Screen screen = event.getScreen();
        if (!isTargetBookScreen(screen)) {
            return;
        }

        float scaleFactor = getScaleFactor(screen);
        int bookLeft = getBookLeft(screen, scaleFactor);
        int bookTop = getBookTop(screen, scaleFactor);

        GuiGraphics graphics = event.getGuiGraphics();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        if (scaleFactor != 1.0f) {
            pose.scale(scaleFactor, scaleFactor, scaleFactor);
        }

        renderTomeBackground(graphics, bookLeft, bookTop);

        pose.popPose();
    }

    /**
     * 绘制秘典全套底图体系。
     */
    private static void renderTomeBackground(GuiGraphics g, int bookLeft, int bookTop) {
        // 1. 周围环境柔和多层下沉阴影
        drawDropShadow(g, bookLeft, bookTop);

        // 2. 黑曜皮革硬质封皮（外扩保护内页）
        int coverX = bookLeft - 7;
        int coverY = bookTop - 6;
        int coverW = 286;
        int coverH = 192;
        drawCover(g, coverX, coverY, coverW, coverH);

        // 3. 中央 3D 弧面立体书脊与金属金箍
        int spineX = bookLeft + 136;
        drawSpine(g, spineX, coverY, coverH);

        // 4. 双开羊皮纸内页（左页与右页）
        drawPage(g, bookLeft + 8, bookTop + 5, 125, 170, true);
        drawPage(g, bookLeft + 139, bookTop + 5, 125, 170, false);

        // 5. 顶端真丝书签缎带
        drawBookmarkRibbon(g, bookLeft + 80, bookTop);
    }

    /**
     * 多层递减下沉漫反射阴影。
     */
    private static void drawDropShadow(GuiGraphics g, int bookLeft, int bookTop) {
        int[][] shadowRings = {
                {10, 0x12000000},
                {7,  0x22000000},
                {4,  0x35000000},
                {2,  0x55000000}
        };
        for (int[] ring : shadowRings) {
            int d = ring[0];
            int col = ring[1];
            g.fill(bookLeft - 7 - d, bookTop - 6 - d, bookLeft + 279 + d, bookTop + 186 + d, col);
        }
    }

    /**
     * 绘制外封皮、金色滚边与四角蔓藤角扣。
     */
    private static void drawCover(GuiGraphics g, int x, int y, int w, int h) {
        // 封皮基底
        g.fill(x, y, x + w, y + h, COLOR_COVER_BG);

        // 外层明暗立体倒角
        g.hLine(x, x + w - 1, y, COLOR_COVER_HIGHLIGHT);
        g.vLine(x, y, y + h - 1, COLOR_COVER_HIGHLIGHT);
        g.hLine(x, x + w - 1, y + h - 1, COLOR_COVER_SHADOW);
        g.vLine(x + w - 1, y, y + h - 1, COLOR_COVER_SHADOW);

        // 第一道古金箔饰线（距边缘 2 像素）
        int oX = x + 2;
        int oY = y + 2;
        int oW = w - 4;
        int oH = h - 4;
        drawRectOutline(g, oX, oY, oW, oH, COLOR_GOLD_OUTER);

        // 第二道内圈纤细金线（距边缘 4 像素）
        int iX = x + 4;
        int iY = y + 4;
        int iW = w - 8;
        int iH = h - 8;
        drawRectOutline(g, iX, iY, iW, iH, COLOR_GOLD_INNER);

        // 四角典雅角扣蔓藤饰片
        drawCornerBracket(g, x + 3, y + 3, 1, 1);
        drawCornerBracket(g, x + w - 4, y + 3, -1, 1);
        drawCornerBracket(g, x + 3, y + h - 4, 1, -1);
        drawCornerBracket(g, x + w - 4, y + h - 4, -1, -1);
    }

    /**
     * 绘制四角古典角扣。
     */
    private static void drawCornerBracket(GuiGraphics g, int cx, int cy, int dx, int dy) {
        // L 形金边
        g.hLine(Math.min(cx, cx + dx * 6), Math.max(cx, cx + dx * 6), cy, COLOR_GOLD_BRIGHT);
        g.vLine(cx, Math.min(cy, cy + dy * 6), Math.max(cy, cy + dy * 6), COLOR_GOLD_BRIGHT);
        // 角心高光金铆钉
        int rx1 = Math.min(cx + dx * 2, cx + dx * 3);
        int rx2 = Math.max(cx + dx * 2, cx + dx * 3);
        int ry1 = Math.min(cy + dy * 2, cy + dy * 3);
        int ry2 = Math.max(cy + dy * 2, cy + dy * 3);
        g.fill(rx1, ry1, rx2 + 1, ry2 + 1, COLOR_GOLD_BRIGHT);
    }

    /**
     * 绘制立体圆弧书脊与三道嵌铆金箍。
     */
    private static void drawSpine(GuiGraphics g, int spineX, int coverY, int coverH) {
        // 弧面渐变柱体（15 像素宽度）
        for (int i = -7; i <= 7; i++) {
            float t = Math.abs(i) / 7.0f;
            int r = (int) (44 * (1.0f - t) + 14 * t);
            int gr = (int) (49 * (1.0f - t) + 16 * t);
            int b = (int) (82 * (1.0f - t) + 28 * t);
            int col = 0xFF000000 | (r << 16) | (gr << 8) | b;
            g.vLine(spineX + i, coverY + 1, coverY + coverH - 2, col);
        }

        // 三道金属金箍（上、中、下）
        int[] ribYs = {coverY + 24, coverY + coverH / 2, coverY + coverH - 24};
        for (int ry : ribYs) {
            g.fill(spineX - 7, ry - 1, spineX + 8, ry + 2, COLOR_GOLD_OUTER);
            g.hLine(spineX - 7, spineX + 7, ry - 1, COLOR_GOLD_INNER);
            g.hLine(spineX - 7, spineX + 7, ry + 1, 0xFF7A5818);
            // 左右小金铆钉
            g.fill(spineX - 5, ry, spineX - 3, ry + 1, COLOR_GOLD_BRIGHT);
            g.fill(spineX + 4, ry, spineX + 6, ry + 1, COLOR_GOLD_BRIGHT);
        }

        // 中央缝线装订深痕
        g.vLine(spineX, coverY + 8, coverY + coverH - 9, COLOR_SEAM_CREASE);
    }

    /**
     * 绘制单页羊皮纸、内饰框线与装订中缝渐变阴影。
     */
    private static void drawPage(GuiGraphics g, int x, int y, int w, int h, boolean isLeft) {
        // 1. 侧沿叠纸厚度层次
        if (isLeft) {
            g.vLine(x - 2, y + 2, y + h - 3, COLOR_PAGE_EDGE_1);
            g.vLine(x - 1, y + 1, y + h - 2, COLOR_PAGE_EDGE_2);
        } else {
            g.vLine(x + w + 1, y + 2, y + h - 3, COLOR_PAGE_EDGE_1);
            g.vLine(x + w, y + 1, y + h - 2, COLOR_PAGE_EDGE_2);
        }

        // 2. 羊皮纸基底（纵向自然温润渐变）
        g.fillGradient(x, y, x + w, y + h, COLOR_PAGE_TOP, COLOR_PAGE_BOTTOM);

        // 3. 内饰纤细框线（内缩 4 像素）与微型金角
        int fx = x + 4;
        int fy = y + 4;
        int fw = w - 8;
        int fh = h - 8;
        drawRectOutline(g, fx, fy, fw, fh, COLOR_PAGE_BORDER);
        g.fill(fx, fy, fx + 2, fy + 2, COLOR_GOLD_OUTER);
        g.fill(fx + fw - 2, fy, fx + fw, fy + 2, COLOR_GOLD_OUTER);
        g.fill(fx, fy + fh - 2, fx + 2, fy + fh, COLOR_GOLD_OUTER);
        g.fill(fx + fw - 2, fy + fh - 2, fx + fw, fy + fh, COLOR_GOLD_OUTER);

        // 4. 中缝装订弧度下凹阴影（16 像素平滑衰减）
        int gutterW = 16;
        if (isLeft) {
            int gStart = x + w - gutterW;
            for (int i = 0; i < gutterW; i++) {
                float factor = (float) Math.pow((float) i / gutterW, 1.6);
                int alpha = (int) (65 * factor);
                int shadowCol = (alpha << 24) | 0x24180A;
                g.vLine(gStart + i, y, y + h - 1, shadowCol);
            }
        } else {
            int gStart = x;
            for (int i = 0; i < gutterW; i++) {
                float factor = (float) Math.pow((float) (gutterW - i) / gutterW, 1.6);
                int alpha = (int) (65 * factor);
                int shadowCol = (alpha << 24) | 0x24180A;
                g.vLine(gStart + i, y, y + h - 1, shadowCol);
            }
        }
    }

    /**
     * 顶端飘垂的真丝书签缎带。
     */
    private static void drawBookmarkRibbon(GuiGraphics g, int rx, int bookTop) {
        int rw = 10;
        int rTop = bookTop - 9;
        int rBottom = bookTop + 16;

        // 缎带主体（绯红织锦）
        g.fill(rx, rTop, rx + rw, rBottom, 0xFFA0202D);
        // 左缘高光亮线
        g.vLine(rx, rTop, rBottom, 0xFFC03241);
        // 右缘阴影沉线
        g.vLine(rx + rw - 1, rTop, rBottom, 0xFF6E101A);
        // 缎带底部金丝流苏嵌条
        g.fill(rx, rBottom - 2, rx + rw, rBottom, COLOR_GOLD_OUTER);
    }

    /**
     * 屏幕绘制后（Tooltips 前）：原生绘制矢量翻页按钮与返回按键。
     */
    public static void onBookDrawScreen(BookDrawScreenEvent event) {
        if (!PatchouliCompat.BOOK_ID.equals(event.getBook())) {
            return;
        }

        Screen screen = event.getScreen();
        if (screen == null) return;

        GuiGraphics g = event.getGraphics();
        float scaleFactor = getScaleFactor(screen);
        int bookLeft = getBookLeft(screen, scaleFactor);

        for (GuiEventListener listener : screen.children()) {
            if (!(listener instanceof AbstractWidget widget)) {
                continue;
            }
            if (!widget.visible || !widget.active) {
                continue;
            }

            String className = widget.getClass().getSimpleName();
            int wx = widget.getX();
            int wy = widget.getY();
            int ww = widget.getWidth();
            int wh = widget.getHeight();
            boolean hovered = widget.isHoveredOrFocused();

            if (className.contains("Arrow")) {
                // 翻页箭头按键：左箭头在左半部，右箭头在右半部
                boolean isLeft = wx < (bookLeft + 136);
                drawVectorArrowButton(g, wx, wy, ww, wh, isLeft, hovered);
            } else if (className.contains("Bookmark")) {
                // 书签按键
                drawVectorBookmarkTab(g, wx, wy, ww, wh, hovered);
            } else if ("GuiButtonBook".equals(className) && ww == 18 && wh == 9) {
                // 返回主页/上一级按键（18x9，位于中央书脊下方）
                drawVectorBackButton(g, wx, wy, ww, wh, hovered);
            }
        }
    }

    /**
     * 绘制矢量翻页箭头按键（◄ / ►）。
     */
    private static void drawVectorArrowButton(GuiGraphics g, int x, int y, int w, int h, boolean isLeft, boolean hovered) {
        if (hovered) {
            g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x44D4AF37);
        }

        int bg = hovered ? COLOR_BTN_BG_HOVER : COLOR_BTN_BG_NORMAL;
        int rim = hovered ? COLOR_BTN_RIM_HOVER : COLOR_BTN_RIM_NORMAL;
        g.fill(x, y, x + w, y + h, bg);
        drawRectOutline(g, x, y, w, h, rim);

        int glyphCol = hovered ? COLOR_GLYPH_HOVER : COLOR_GLYPH_NORMAL;
        int cx = x + w / 2;
        int cy = y + h / 2;

        if (isLeft) {
            // ◄ 左箭头
            g.vLine(cx - 3, cy, cy, glyphCol);
            g.vLine(cx - 2, cy - 1, cy + 1, glyphCol);
            g.vLine(cx - 1, cy - 2, cy + 2, glyphCol);
            g.vLine(cx, cy - 3, cy + 3, glyphCol);
            g.hLine(cx, cx + 3, cy, glyphCol);
        } else {
            // ► 右箭头
            g.vLine(cx + 3, cy, cy, glyphCol);
            g.vLine(cx + 2, cy - 1, cy + 1, glyphCol);
            g.vLine(cx + 1, cy - 2, cy + 2, glyphCol);
            g.vLine(cx, cy - 3, cy + 3, glyphCol);
            g.hLine(cx - 3, cx, cy, glyphCol);
        }
    }

    /**
     * 绘制矢量返回按键（↩ 弧形返回箭标）。
     */
    private static void drawVectorBackButton(GuiGraphics g, int x, int y, int w, int h, boolean hovered) {
        if (hovered) {
            g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x44D4AF37);
        }

        int bg = hovered ? COLOR_BTN_BG_HOVER : COLOR_BTN_BG_NORMAL;
        int rim = hovered ? COLOR_BTN_RIM_HOVER : COLOR_BTN_RIM_NORMAL;
        g.fill(x, y, x + w, y + h, bg);
        drawRectOutline(g, x, y, w, h, rim);

        int glyphCol = hovered ? COLOR_GLYPH_HOVER : COLOR_GLYPH_NORMAL;
        int cx = x + w / 2;
        int cy = y + h / 2;

        g.hLine(cx - 3, cx + 2, cy - 1, glyphCol);
        g.vLine(cx + 2, cy - 1, cy + 2, glyphCol);
        g.hLine(cx + 1, cx + 2, cy + 2, glyphCol);
        g.vLine(cx - 3, cy - 2, cy, glyphCol);
        g.vLine(cx - 2, cy - 3, cy + 1, glyphCol);
    }

    /**
     * 绘制矢量书签外沿标签。
     */
    private static void drawVectorBookmarkTab(GuiGraphics g, int x, int y, int w, int h, boolean hovered) {
        int bg = hovered ? 0xFF9E2432 : 0xFF7A1420;
        int rim = hovered ? COLOR_GOLD_BRIGHT : COLOR_GOLD_OUTER;
        g.fill(x, y, x + w, y + h, bg);
        drawRectOutline(g, x, y, w, h, rim);
    }

    /**
     * 辅助方法：绘制 1 像素空心矩形线框。
     */
    private static void drawRectOutline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.hLine(x, x + w - 1, y, color);
        g.hLine(x, x + w - 1, y + h - 1, color);
        g.vLine(x, y + 1, y + h - 2, color);
        g.vLine(x + w - 1, y + 1, y + h - 2, color);
    }
}
