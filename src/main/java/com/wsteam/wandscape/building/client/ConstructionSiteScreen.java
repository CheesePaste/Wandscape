package com.wsteam.wandscape.building.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.building.network.ConstructionSiteDataPacket;
import com.wsteam.wandscape.building.network.ConstructionSiteDataPacket.MaterialEntry;
import com.wsteam.wandscape.projection.network.BuildingActionPacket;
import com.wsteam.wandscape.road.network.RoadWithdrawPacket;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

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
        setTitleBar(I18n.name("building.wandscape." + buildingTypeId, buildingName));
        if (list != null) {
            list.setItems(materials);
        }
    }

    @Override
    protected void init() {
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
                    g.renderItem(new ItemStack(registryItem), x, y + 1);
                }
                Component name = (registryItem != null && registryItem != Items.AIR)
                        ? new ItemStack(registryItem).getHoverName()
                        : Component.literal(item.blockId());
                g.drawString(font, name, x + 20, y + 2,
                        selected ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_MUTED);

                // Middle: required quantity, right-aligned to MID_COL_X.
                String countText = "x" + item.required();
                g.drawString(font, countText, x + MID_COL_X - font.width(countText), y + 2,
                        MedievalColors.TEXT_MUTED);

                // Right: supply status, right-aligned to the row edge.
                String statusText = statusText(item.status());
                g.drawString(font, statusText,
                        x + getWidth() - scrollbarWidth - font.width(statusText) - 6, y + 2,
                        statusColor(item.status()));
            }
        };
        list.setItems(materials);
        addRenderableWidget(list);

        // Withdraw button — only for under-construction sites (already-completed ones
        // show nothing and can't be withdrawn). Bottom-right, clear of the creator label.
        if (!completed) {
            int btnW = 80, btnH = 18;
            int btnX = leftPos + PW - 8 - btnW;
            int btnY = topPos + PH - CREATOR_FOOTER_H + 2;
            addRenderableWidget(new MedievalButton(
                    btnX, btnY, btnW, btnH,
                    I18n.name("gui.wandscape.constructionsite.withdraw", "撤回"),
                    this::onWithdraw));
        }
    }

    private void onWithdraw() {
        if (buildingId == null) return;
        if (kind == ConstructionSiteDataPacket.KIND_ROAD) {
            PacketDistributor.sendToServer(new RoadWithdrawPacket(buildingId));
        } else {
            PacketDistributor.sendToServer(new BuildingActionPacket(buildingId, "cancel"));
        }
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int textX = leftPos + 8;
        int lineY = topPos + headerHeight + TIME_LINE1_Y;
        g.drawString(font, I18n.name("gui.wandscape.constructionsite.start_time", "预计开工").getString()
                + ": " + startLabel(), textX, lineY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, I18n.name("gui.wandscape.constructionsite.complete_time", "预计完工").getString()
                + ": " + completeLabel(), textX, lineY + TIME_LINE_GAP, MedievalColors.TEXT_WARM_WHITE);
    }

    private String startLabel() {
        if (completed) return I18n.name("gui.wandscape.constructionsite.completed", "已完工").getString();
        if (!canEstimate) {
            return I18n.name("gui.wandscape.constructionsite.waiting_workstation", "等待工作站").getString();
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
            default ->
                    I18n.name("gui.wandscape.constructionsite.status.pending", "待制作").getString();
        };
    }

    private static int statusColor(int status) {
        return switch (status) {
            case ConstructionSiteDataPacket.STATUS_READY -> MedievalColors.SUCCESS_GREEN;
            case ConstructionSiteDataPacket.STATUS_CRAFTING -> MedievalColors.ACCENT_GOLD;
            default -> MedievalColors.TEXT_DIM;
        };
    }
}
