package com.wsteam.wandscape.content.magic.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.content.magic.network.WorldResponseChoicePacket;
import com.wsteam.wandscape.content.magic.worldresponse.WorldResponse;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 《世界应答》的回应轮盘（毕业魔法的选择界面）。
 *
 * <p><b>为什么是一个 {@link Screen} 而不是浮层</b>：本模组的「抬光标 + UI 点击路由」整条链挂在
 * 面板开关上（{@code WandscapePanelController} 先判 {@code isPanelOpen()}），野外用卷轴施放时借不到；
 * 原版 {@code Screen} 自带光标接管与释放，正好省掉一整套光标状态机。
 * {@link #isPauseScreen()} 为 false —— 世界照常跑，这不是"暂停菜单"。
 *
 * <p><b>刻意不绑任何热键</b>：1-5 已被面板页签占用、1-9 是原版快捷栏，再抢数字键就是冲突源。
 * 选择完全走鼠标方向：光标离中心超过甜区即按角度归属到最近的扇区，左键确认、右键或 ESC 取消。
 *
 * <p>服务端才是权威：本屏只发「我选了哪个」；过期/冷却/合法性由 {@code WorldResponseManager} 判。
 */
public class WorldResponseScreen extends Screen {

    private static final String TAG = "WorldResponseScreen";

    /** 开场揭示：先只出那句「世界听见了你的意志。」，这段时间内不接受点击。 */
    private static final long REVEAL_MS = 350L;
    /** 客户端自兜底的存活时长：服务端 pending 是 5 秒，屏不留更久（避免界面卡住）。 */
    private static final long AUTO_CLOSE_MS = 5_000L;
    /** 每个扇区的跨度（度）：四段各 90 度、中间留缝，视觉上分得开。 */
    private static final float SECTOR_SPAN_DEG = 86f;

    // 轮盘配色：世界应答的紫（与终极指南针同色）+ 中世纪金
    private static final int ACCENT = 0xFF9B30FF;
    private static final int BORDER = MedievalColors.BORDER_GOLD_DARK;
    private static final int IDLE_TOP = 0xCC2A1040;
    private static final int IDLE_BOTTOM = 0xCC160820;
    private static final int HOVER_TOP = 0xEE6B30A0;
    private static final int HOVER_BOTTOM = 0xEE3A1060;
    private static final int HUB_BG = 0xEE1A0E24;

    private final List<WorldResponse> options;
    private final long openedAt = System.currentTimeMillis();

    private WorldResponseScreen(List<WorldResponse> options) {
        super(I18n.name("worldresponse.wandscape.title", "世界听见了你的意志。"));
        this.options = options;
    }

    /** 由 {@code WorldResponseOpenPacket} 的客户端分发调用（必须在客户端线程）。 */
    public static void open(List<String> responseIds) {
        List<WorldResponse> options = new ArrayList<>();
        for (String id : responseIds) {
            WorldResponse r = WorldResponse.byId(id);
            if (r != null) options.add(r);
        }
        if (options.isEmpty()) {
            Log.warn(TAG, "[WorldResponse] Open packet carried no known response — picker not shown");
            return;
        }
        Minecraft.getInstance().setScreen(new WorldResponseScreen(options));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ── 几何 ──

    private float centerX() {
        return this.width / 2f;
    }

    private float centerY() {
        return this.height / 2f;
    }

    private float outerRadius() {
        return Mth.clamp(Math.min(this.width, this.height) * 0.30f, 88f, 140f);
    }

    private float innerRadius() {
        return outerRadius() * 0.36f;
    }

    private float hubRadius() {
        return innerRadius() - 8f;
    }

    /** 光标下的回应；落在中心甜区内返回 null。渲染与点击共用同一套判定。 */
    private WorldResponse hoveredOption(double mouseX, double mouseY) {
        float dx = (float) mouseX - centerX();
        float dy = (float) mouseY - centerY();
        if (Mth.sqrt(dx * dx + dy * dy) < innerRadius()) return null;

        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        WorldResponse best = null;
        float bestDelta = Float.MAX_VALUE;
        for (WorldResponse option : options) {
            float delta = Math.abs(Mth.wrapDegrees(angle - option.displayAngleDeg()));
            if (delta < bestDelta) {
                bestDelta = delta;
                best = option;
            }
        }
        return best;
    }

    // ── 渲染 ──

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 世界仍要看得见：只压一层薄暗，不做模糊
        renderTransparentBackground(g);
        g.fillGradient(0, 0, this.width, this.height, 0x55100818, 0x88100818);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long elapsed = System.currentTimeMillis() - openedAt;
        if (elapsed > AUTO_CLOSE_MS) {
            Log.info(TAG, "[WorldResponse] Picker auto-closed after {} ms (server pending expires on its own)", AUTO_CLOSE_MS);
            this.onClose();
            return;
        }

        float reveal = Mth.clamp(elapsed / (float) REVEAL_MS, 0f, 1f);
        float pop = 0.88f + 0.12f * reveal;   // 轻微弹入，不改布局

        super.render(g, mouseX, mouseY, partialTick);

        float cx = centerX();
        float cy = centerY();
        float outer = outerRadius() * pop;
        float inner = innerRadius() * pop;
        WorldResponse hovered = reveal >= 1f ? hoveredOption(mouseX, mouseY) : null;

        // 1. 四段扇区：先铺一圈稍大的金边，再用填充盖住内侧 —— 省掉线段绘制
        for (WorldResponse option : options) {
            drawSector(g, cx, cy, inner - 2f, outer + 2f, option.displayAngleDeg(), SECTOR_SPAN_DEG + 4f,
                    BORDER, BORDER, reveal);
        }
        for (WorldResponse option : options) {
            boolean hot = option == hovered;
            drawSector(g, cx, cy, inner, outer, option.displayAngleDeg(), SECTOR_SPAN_DEG,
                    hot ? HOVER_TOP : IDLE_TOP, hot ? HOVER_BOTTOM : IDLE_BOTTOM, reveal);
        }

        // 2. 中心圆盘
        drawSector(g, cx, cy, 0f, hubRadius(), 0f, 360f, HUB_BG, HUB_BG, reveal);

        // 3. 扇区标签（画在中线半径上）
        for (WorldResponse option : options) {
            float mid = (float) Math.toRadians(option.displayAngleDeg());
            float radius = (inner + outer) / 2f;
            int x = Mth.floor(cx + Mth.cos(mid) * radius);
            int y = Mth.floor(cy + Mth.sin(mid) * radius) - font.lineHeight / 2;
            int color = option == hovered ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED;
            g.drawCenteredString(font, I18n.name(option.labelKey(), option.id()), x, y, color);
        }

        // 4. 中心文案：选中时给该回应的说明，否则一句引导
        Component hubText = hovered != null
                ? I18n.name(hovered.descKey(), hovered.id())
                : I18n.name("worldresponse.wandscape.hub_hint", "移动鼠标，看向你要的回应");
        drawCenteredWrapped(g, hubText, cx, cy, (int) (hubRadius() * 1.6f),
                hovered != null ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED, reveal);

        // 5. 标题与操作提示
        g.drawCenteredString(font, I18n.name("worldresponse.wandscape.title", "世界听见了你的意志。"),
                Mth.floor(cx), Mth.floor(cy - outer - 30), withAlpha(ACCENT, reveal));
        g.drawCenteredString(font, I18n.name("worldresponse.wandscape.hint", "左键确认 · 右键 / ESC 取消"),
                Mth.floor(cx), Mth.floor(cy + outer + 18), withAlpha(MedievalColors.TEXT_DIM, reveal));
    }

    /**
     * 画一段扇环（{@code rIn == 0} 时就是圆盘）。GUI 没有扇形图元，这里直接把三角形带写进
     * {@link RenderType#gui()} 的顶点缓冲，最后 flush 一次。
     */
    private void drawSector(GuiGraphics g, float cx, float cy, float rIn, float rOut,
                            float centerDeg, float spanDeg, int topColor, int bottomColor, float reveal) {
        float start = centerDeg - spanDeg / 2f;
        float end = centerDeg + spanDeg / 2f;
        int segments = Math.max(8, (int) (spanDeg / 6f));

        int cOut = withAlpha(topColor, reveal);
        int cIn = withAlpha(bottomColor, reveal);
        var pose = g.pose().last().pose();
        VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());
        for (int i = 0; i < segments; i++) {
            float a0 = (float) Math.toRadians(start + (end - start) * i / segments);
            float a1 = (float) Math.toRadians(start + (end - start) * (i + 1) / segments);
            vertex(vc, pose, cx + Mth.cos(a0) * rIn, cy + Mth.sin(a0) * rIn, cIn);
            vertex(vc, pose, cx + Mth.cos(a0) * rOut, cy + Mth.sin(a0) * rOut, cOut);
            vertex(vc, pose, cx + Mth.cos(a1) * rOut, cy + Mth.sin(a1) * rOut, cOut);
            vertex(vc, pose, cx + Mth.cos(a1) * rIn, cy + Mth.sin(a1) * rIn, cIn);
        }
        g.flush();
    }

    private static void vertex(VertexConsumer vc, org.joml.Matrix4f pose, float x, float y, int argb) {
        vc.addVertex(pose, x, y, 0f).setColor(argb);
    }

    /** 居中多行文本：{@code drawCenteredString} 没有 FormattedCharSequence 重载，手动居中。 */
    private void drawCenteredWrapped(GuiGraphics g, Component text, float cx, float cy,
                                     int maxWidth, int color, float reveal) {
        List<FormattedCharSequence> lines = font.split(text, Math.max(40, maxWidth));
        int lineH = font.lineHeight + 1;
        int y = Mth.floor(cy - (lines.size() * lineH - 1) / 2f);
        int argb = withAlpha(color, reveal);
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, Mth.floor(cx - font.width(line) / 2f), y, argb, false);
            y += lineH;
        }
    }

    private static int withAlpha(int argb, float factor) {
        int a = (int) (((argb >>> 24) & 0xFF) * Mth.clamp(factor, 0f, 1f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    // ── 输入（只用鼠标，不占热键） ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (System.currentTimeMillis() - openedAt < REVEAL_MS) {
            return true;   // 揭示期间吞掉点击，别让手快的玩家误选
        }
        if (button == 1) {
            cancel();
            return true;
        }
        if (button != 0) return false;

        WorldResponse hovered = hoveredOption(mouseX, mouseY);
        if (hovered == null) {
            return true;   // 点在甜区：什么都不做，也不发包
        }
        Log.info(TAG, "[WorldResponse] Player chose '{}'", hovered.id());
        Net.toServer(new WorldResponseChoicePacket(hovered.id()));
        this.onClose();
        return true;
    }

    /** ESC / 关闭轮盘：告诉服务端作废这次选择（只清 pending，不进冷却）。 */
    @Override
    public void onClose() {
        Net.toServer(new WorldResponseChoicePacket(""));
        super.onClose();
    }

    private void cancel() {
        Log.info(TAG, "[WorldResponse] Picker cancelled by player");
        this.onClose();
    }
}
