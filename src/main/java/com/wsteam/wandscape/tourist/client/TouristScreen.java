package com.wsteam.wandscape.tourist.client;

import java.util.List;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.data.Activity;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.tourist.network.TouristDataPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
/**
 * Tourist info screen.
 *
 * <p>Block 2：三条需求条（Comfort/Magic/Wonder fill/need）+ 画像标签 + 活动 + 停留 + 钱包/旅费；
 * 行程逐维增量；删除单一满意度。
 */
public class TouristScreen extends MedievalScreen {

    private static final int PW = 300;
    private static final int PH = 300;

    private final int entityId;
    private String touristName;
    private int energy;
    private int level;
    private int wallet;
    private int travelFund;
    private int comfortSat, magicSat, wonderSat;
    private int comfortNeed, magicNeed, wonderNeed;
    @Nullable
    private Activity currentActivity;
    private int nightsStayed;
    private int stayDaysTotal;
    private List<TouristDataPacket.VisitEntry> recentVisits;

    public TouristScreen(TouristDataPacket packet) {
        super(Component.literal("Tourist Info"), PW, PH);
        setTitleBar(Component.literal("Tourist Info"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "tourist_guide";
        this.entityId = packet.entityId();
        apply(packet);
    }

    public void apply(TouristDataPacket packet) {
        this.touristName = packet.touristName();
        this.energy = packet.energy();
        this.level = packet.level();
        this.wallet = packet.wallet();
        this.travelFund = packet.travelFund();
        this.comfortSat = packet.comfortSat();
        this.magicSat = packet.magicSat();
        this.wonderSat = packet.wonderSat();
        this.comfortNeed = packet.comfortNeed();
        this.magicNeed = packet.magicNeed();
        this.wonderNeed = packet.wonderNeed();
        this.currentActivity = packet.currentActivity();
        this.nightsStayed = packet.nightsStayed();
        this.stayDaysTotal = packet.stayDaysTotal();
        this.recentVisits = packet.recentVisits();
        setTitleBar(Component.literal(touristName));
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                Component.literal("关闭"), () -> Minecraft.getInstance().setScreen(null)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        var font = Minecraft.getInstance().font;
        int leftCol = leftPos + 12;
        int contentTop = topPos + headerHeight + 6;

        // ── 画像 + 三条需求条 ──
        g.drawString(font, personaLabel(), leftCol, contentTop, MedievalColors.ACCENT_GOLD);
        int sepY = contentTop + 8;
        g.fill(leftCol, sepY, leftCol + 50, sepY + 1, MedievalColors.BORDER_GOLD_DARK);

        int statY = sepY + 5;
        int labelW = 32;
        int barW = 100;

        // Comfort bar
        g.drawString(font, "舒适:", leftCol, statY, MedievalColors.TEXT_WARM_WHITE);
        drawStatBar(g, leftCol + labelW, statY, barW, 10,
                ratio(comfortSat, comfortNeed),
                comfortSat + "/" + comfortNeed,
                MedievalColors.SUCCESS_GREEN);
        statY += 11;

        // Magic bar
        g.drawString(font, "魔法:", leftCol, statY, MedievalColors.TEXT_WARM_WHITE);
        drawStatBar(g, leftCol + labelW, statY, barW, 10,
                ratio(magicSat, magicNeed),
                magicSat + "/" + magicNeed,
                MedievalColors.ACCENT_GOLD);
        statY += 11;

        // Wonder bar
        g.drawString(font, "奇观:", leftCol, statY, MedievalColors.TEXT_WARM_WHITE);
        drawStatBar(g, leftCol + labelW, statY, barW, 10,
                ratio(wonderSat, wonderNeed),
                wonderSat + "/" + wonderNeed,
                MedievalColors.INFO_BLUE);
        statY += 11;

        // Energy bar
        g.drawString(font, "精力:", leftCol, statY, MedievalColors.TEXT_WARM_WHITE);
        drawStatBar(g, leftCol + labelW, statY, barW, 10,
                Math.clamp((float) energy / WandscapeConstants.TOURIST_MAX_ENERGY, 0f, 1f),
                energy + "/" + WandscapeConstants.TOURIST_MAX_ENERGY,
                MedievalColors.SUCCESS_GREEN);
        statY += 11;

        // Level text
        g.drawString(font, "等级:", leftCol, statY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, String.valueOf(level), leftCol + labelW, statY, MedievalColors.TEXT_MUTED);
        statY += 11;

        // Wallet / travel fund
        g.drawString(font, "钱包:", leftCol, statY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, wallet + "  旅费:" + travelFund, leftCol + labelW, statY, MedievalColors.TEXT_MUTED);
        statY += 11;

        // Stay
        g.drawString(font, "停留:", leftCol, statY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, "已住 " + nightsStayed + " 晚 / 共 " + stayDaysTotal + " 天", leftCol + labelW, statY, MedievalColors.TEXT_MUTED);
        statY += 11;

        // Activity
        g.drawString(font, "活动:", leftCol, statY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, activityLabel(), leftCol + labelW, statY, MedievalColors.TEXT_MUTED);
        statY += 11;

        // ── Visits ──
        int visitsTop = statY + 20;
        g.drawString(font, "行程", leftCol, visitsTop, MedievalColors.ACCENT_GOLD);
        g.fill(leftCol, visitsTop + 10, leftPos + PW - 12, visitsTop + 11, MedievalColors.BORDER_GOLD_DARK);

        if (recentVisits.isEmpty()) {
            g.drawString(font, "暂无行程记录", leftCol, visitsTop + 22, MedievalColors.TEXT_MUTED);
        } else {
            int visitY = visitsTop + 17;
            int maxLines = (topPos + PH - 24 - visitY) / 10;
            int count = 0;
            for (var visit : recentVisits) {
                if (count >= maxLines) break;

                String outcomes = "舒适" + formatDelta(visit.comfortDelta())
                        + " 魔法" + formatDelta(visit.magicDelta())
                        + " 奇观" + formatDelta(visit.wonderDelta())
                        + " · 精力" + formatDelta(visit.energyDelta());
                Component building = (visit.buildingTypeId() == null || visit.buildingTypeId().isEmpty())
                        ? Component.literal(visit.buildingName())
                        : I18n.name("building.wandscape." + visit.buildingTypeId(), visit.buildingName());
                Component line = building.copy()
                        .append(Component.literal(": "))
                        .append(localizeItemName(visit.whatHappened()))
                        .append(Component.literal(" (" + outcomes + ")"));

                g.drawString(font, line, leftCol, visitY, MedievalColors.TEXT_MUTED);
                visitY += 10;
                count++;
            }
        }
    }

    /** 画像标签：need 最高维 = 偏爱；三条相等 = 均衡。 */
    private String personaLabel() {
        if (comfortNeed == magicNeed && magicNeed == wonderNeed) return "均衡";
        if (comfortNeed >= magicNeed && comfortNeed >= wonderNeed) return "偏爱舒适";
        if (magicNeed >= wonderNeed) return "偏爱魔法";
        return "偏爱奇观";
    }

    /** 活动名（中文直显，与面板其余文案一致；null = 无活动）。 */
    private String activityLabel() {
        if (currentActivity == null) return "—";
        return switch (currentActivity) {
            case TRAVEL -> "赶路";
            case QUEUE -> "排队";
            case BROWSE -> "浏览";
            case EAT -> "用餐";
            case BATHE -> "泡澡";
            case VIEW -> "看展";
            case PAY -> "付钱";
            case SLEEP -> "睡觉";
            case REST -> "歇脚";
            case WITHDRAW -> "取现";
        };
    }

    private static float ratio(int sat, int need) {
        return need <= 0 ? 0f : (float) sat / need;
    }

    private static String formatDelta(int delta) {
        return delta >= 0 ? "+" + delta : String.valueOf(delta);
    }

    /**
     * Localize an item registry id (optionally with a " ×N" count suffix) from a
     * visit log entry, falling back to the raw text for non-item entries (e.g. "服务").
     */
    private static Component localizeItemName(String whatHappened) {
        String id = whatHappened;
        String suffix = "";
        int sep = whatHappened.indexOf(" ×");
        if (sep > 0) {
            id = whatHappened.substring(0, sep);
            suffix = whatHappened.substring(sep);
        }
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
        if (item != null && item != Items.AIR) {
            return new ItemStack(item).getHoverName().copy().append(Component.literal(suffix));
        }
        return Component.literal(whatHappened);
    }

    /** Draw a compact stat bar. */
    private void drawStatBar(GuiGraphics g, int x, int y, int barWidth, int barHeight,
                             float ratio, String label, int fillColor) {
        g.fill(x, y, x + barWidth, y + barHeight, MedievalColors.PROGRESS_BG);
        int fillW = (int) (barWidth * Math.clamp(ratio, 0f, 1f));
        if (fillW > 0) {
            g.fill(x, y, x + fillW, y + barHeight, fillColor);
        }
        var font = Minecraft.getInstance().font;
        g.drawCenteredString(font, label, x + barWidth / 2, y + (barHeight - 9) / 2,
                MedievalColors.TEXT_WARM_WHITE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
