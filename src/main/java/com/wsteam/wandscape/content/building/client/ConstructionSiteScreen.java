package com.wsteam.wandscape.content.building.client;

import com.wsteam.wandscape.content.building.network.ConstructionCraftAllPacket;
import com.wsteam.wandscape.content.building.network.ConstructionSiteDataPacket;
import com.wsteam.wandscape.content.building.network.ConstructionSiteDataPacket.MaterialEntry;
import com.wsteam.wandscape.content.building.projection.network.BuildingActionPacket;
import com.wsteam.wandscape.content.road.network.RoadWithdrawPacket;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.component.MedievalButton;
import com.wsteam.wandscape.foundation.ui.component.MedievalScreen;
import com.wsteam.wandscape.foundation.ui.component.ScrollableList;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 工地面板：展示未建成建筑的建造材料需求与供应状态。
 *
 * <p>顶部两条预计时间（开工/完工），下方 {@link ScrollableList} 逐行列出方块：
 * 左图标+名、中需求数量、右供应状态（已备齐/制作中/待制作）。尺寸与
 * {@code WorkstationScreen} 一致（400×220）。数据在打开面板时由服务端算一次快照，
 * 不做周期刷新（避免超大建筑反复扫描世界）。
 */
public class ConstructionSiteScreen extends MedievalScreen {

    private static final int PW = 400;
    private static final int PH = 220;

    // Time strip: two lines just below the header, before the list.
    private static final int TIME_LINE1_Y = 6;
    private static final int TIME_LINE_GAP = 10;
    private static final int TIME_STRIP_H = 34;

    // Right edge of the middle "x需求" column (relative to row x).
    private static final int MID_COL_X = 210;

    private UUID buildingId;
    private String buildingTypeId = "";
    private String buildingName = "";
    private List<MaterialEntry> materials = new ArrayList<>();
    private int estStartTicks;
    private int estCompleteTicks;
    private boolean canEstimate = true;
    private boolean completed;
    private int kind;

    private ScrollableList<MaterialEntry> list;

    public ConstructionSiteScreen(ConstructionSiteDataPacket packet) {
        super(Component.literal("Construction Site"), PW, PH);
        this.showCloseButton = true;
        apply(packet);
    }

    public boolean matches(UUID buildingId) {
        return this.buildingId != null && this.buildingId.equals(buildingId);
    }

    public void updateData(ConstructionSiteDataPacket packet) {
        apply(packet);
    }

    private void apply(ConstructionSiteDataPacket packet) {
        this.buildingId = packet.buildingId();
        this.buildingTypeId = packet.buildingTypeId();
        this.buildingName = packet.buildingName();
        this.materials = new ArrayList<>(packet.materials());
        this.estStartTicks = packet.estStartTicks();
        this.estCompleteTicks = packet.estCompleteTicks();
        this.canEstimate = packet.canEstimate();
        this.completed = packet.completed();
        this.kind = packet.kind();
        setCreator(packet.creator());
        // 路段的 buildingTypeId 装的是道路预设 id，查的是 road preset 那套键而不是建筑键。
        setTitleBar(kind == ConstructionSiteDataPacket.KIND_ROAD
                ? I18n.datapackName(com.wsteam.wandscape.content.road.data.RoadPreset.langKeyFor(buildingTypeId),
                        buildingName, null)
                : I18n.buildingName(buildingTypeId, buildingName));
        if (kind != ConstructionSiteDataPacket.KIND_ROAD) {
            setBuildingContext(packet.buildingId(), null);
        }
        if (list != null) {
            list.setItems(materials);
        }
    }

    @Override
    protected void init() {
        setActionButtonsOffset(PW - 16 - 92, PH - 20);
        super.init();
        int contentX = leftPos + 8;
        int listY = topPos + headerHeight + TIME_STRIP_H + 2;
        int listH = PH - headerHeight - 4 - TIME_STRIP_H - 4 - CREATOR_FOOTER_H - 4;

        list = new ScrollableList<MaterialEntry>(contentX, listY, PW - 16, listH, 20) {
            @Override
            protected void renderRow(GuiGraphics g, MaterialEntry item, int x, int y, int index,
                                     boolean selected, boolean hovered) {
                var font = Minecraft.getInstance().font;
                var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.blockId()));
                if (registryItem != null && registryItem != Items.AIR) {
                    g.renderItem(new ItemStack(registryItem), x + 2, y + 2);
                }
                Component name = (registryItem != null && registryItem != Items.AIR)
                        ? new ItemStack(registryItem).getHoverName()
                        : Component.literal(item.blockId());
                g.drawString(font, name, x + 22, y + 6,
                        selected ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE);

                // Middle: required quantity, right-aligned to MID_COL_X.
                String countText = "×" + item.required();
                g.drawString(font, countText, x + MID_COL_X - font.width(countText), y + 6,
                        MedievalColors.TEXT_MUTED);

                // Right: supply status pill badge
                String statusText = statusText(item.status());
                int stColor = statusColor(item.status());
                int badgeW = font.width(statusText) + 8;
                int badgeH = 12;
                int badgeX = x + getWidth() - scrollbarWidth - badgeW - 6;
                int badgeY = y + 4;
                int badgeBg = switch (item.status()) {
                    case ConstructionSiteDataPacket.STATUS_READY -> 0x3366BB6A;
                    case ConstructionSiteDataPacket.STATUS_CRAFTING -> 0x33FFA726;
                    case ConstructionSiteDataPacket.STATUS_LOCKED -> 0x33EF5350;
                    default -> 0x33445068;
                };
                g.fill(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, badgeBg);
                g.drawString(font, statusText, badgeX + 4, badgeY + 2, stColor);
            }
        };
        list.setTooltipProvider((item, idx) -> {
            var registryItem = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item.blockId()));
            return (registryItem != null && registryItem != Items.AIR) ? new ItemStack(registryItem) : null;
        });
        list.setItems(materials);
        addRenderableWidget(list);

        // 一键制作：按当前缺口把建材下成物品工坊合成任务。放下建筑/道路时已经自动补过一次，
        // 玩家把那些任务删掉后不再自动重发（缺料也不会自己长回来），缺料就靠这个按钮补。
        if (!completed) {
            int btnW = 80, btnH = 18;
            addRenderableWidget(new MedievalButton(
                    leftPos + PW - 8 - btnW, topPos + headerHeight + 8, btnW, btnH,
                    I18n.name("gui.wandscape.constructionsite.craft_all", "一键制作"),
                    this::onCraftAll));
        }

        // Withdraw button — road construction sites keep dedicated withdraw button;
        // buildings use MedievalScreen's unified cancel/demolish buttons.
        if (!completed && kind == ConstructionSiteDataPacket.KIND_ROAD) {
            int btnW = 80, btnH = 18;
            int btnX = leftPos + PW - 8 - btnW;
            int btnY = topPos + PH - CREATOR_FOOTER_H + 2;
            addRenderableWidget(new MedievalButton(
                    btnX, btnY, btnW, btnH,
                    I18n.name("gui.wandscape.constructionsite.withdraw", "撤回"),
                    this::onWithdraw));
        }
    }

    private void onCraftAll() {
        if (buildingId == null) return;
        Net.toServer(new ConstructionCraftAllPacket(
                buildingId, kind == ConstructionSiteDataPacket.KIND_ROAD));
    }

    private void onWithdraw() {
        if (buildingId == null) return;
        if (kind == ConstructionSiteDataPacket.KIND_ROAD) {
            Net.toServer(new RoadWithdrawPacket(buildingId));
        } else {
            Net.toServer(new BuildingActionPacket(buildingId, "cancel"));
        }
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    protected void renderForeground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderForeground(g, mouseX, mouseY, partialTick);
        if (list != null) {
            ItemStack stack = list.hoveredTooltipStack();
            if (stack != null && !stack.isEmpty()) {
                g.renderTooltip(font, stack, mouseX, mouseY);
            }
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int cardX = leftPos + 8;
        int cardY = topPos + headerHeight + 4;
        int cardW = PW - 16;
        int cardH = TIME_STRIP_H;

        // Decorative background well for time estimate
        g.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0x330D1018);
        g.fill(cardX, cardY, cardX + cardW, cardY + 1, 0x553A455C);
        g.fill(cardX, cardY + cardH - 1, cardX + cardW, cardY + cardH, 0x553A455C);
        g.fill(cardX, cardY, cardX + 2, cardY + cardH, MedievalColors.ACCENT_GOLD);

        int textX = cardX + 8;
        int lineY = cardY + 5;
        g.drawString(font, I18n.name("gui.wandscape.constructionsite.start_time", "预计开工").getString()
                + ": ", textX, lineY, MedievalColors.TEXT_MUTED);
        g.drawString(font, startLabel(), textX + 54, lineY, MedievalColors.TEXT_WARM_WHITE);

        g.drawString(font, I18n.name("gui.wandscape.constructionsite.complete_time", "预计完工").getString()
                + ": ", textX, lineY + TIME_LINE_GAP + 2, MedievalColors.TEXT_MUTED);
        g.drawString(font, completeLabel(), textX + 54, lineY + TIME_LINE_GAP + 2, MedievalColors.ACCENT_GOLD);

        // 建材由殖民地物品工坊合成后送到工地，玩家常不知道来源；列表底与 creator 页脚之间正好一行。
        g.drawString(font, I18n.name("gui.wandscape.constructionsite.craft_hint",
                        "Materials can be crafted at the workstation"),
                leftPos + 16, topPos + PH - CREATOR_FOOTER_H - font.lineHeight,
                MedievalColors.TEXT_DIM);
    }

    private String startLabel() {
        if (completed) return I18n.name("gui.wandscape.constructionsite.completed", "已完工").getString();
        if (!canEstimate) {
            return I18n.name("gui.wandscape.constructionsite.waiting_workstation", "等待物品工坊").getString();
        }
        if (estStartTicks <= 0) {
            return I18n.name("gui.wandscape.constructionsite.ready_now", "即刻开工").getString();
        }
        return formatSeconds(estStartTicks);
    }

    private String completeLabel() {
        if (completed) return I18n.name("gui.wandscape.constructionsite.completed", "已完工").getString();
        if (!canEstimate) return "—";
        return formatSeconds(estCompleteTicks);
    }

    private static String formatSeconds(int ticks) {
        int sec = (int) Math.ceil(ticks / 20.0);
        if (sec < 60) return sec + "s";
        int m = sec / 60;
        int s = sec % 60;
        return m + "m" + (s > 0 ? s + "s" : "");
    }

    private static String statusText(int status) {
        return switch (status) {
            case ConstructionSiteDataPacket.STATUS_READY ->
                    I18n.name("gui.wandscape.constructionsite.status.ready", "已备齐").getString();
            case ConstructionSiteDataPacket.STATUS_CRAFTING ->
                    I18n.name("gui.wandscape.constructionsite.status.crafting", "制作中").getString();
            case ConstructionSiteDataPacket.STATUS_LOCKED ->
                    I18n.name("gui.wandscape.constructionsite.status.locked", "未解锁").getString();
            default ->
                    I18n.name("gui.wandscape.constructionsite.status.pending", "待制作").getString();
        };
    }

    private static int statusColor(int status) {
        return switch (status) {
            case ConstructionSiteDataPacket.STATUS_READY -> MedievalColors.SUCCESS_GREEN;
            case ConstructionSiteDataPacket.STATUS_CRAFTING -> MedievalColors.ACCENT_GOLD;
            case ConstructionSiteDataPacket.STATUS_LOCKED -> MedievalColors.DANGER_RED;
            default -> MedievalColors.TEXT_DIM;
        };
    }
}
