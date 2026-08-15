package com.wsteam.wandscape.building.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.wsteam.wandscape.building.network.AltarCastRequestPacket;
import com.wsteam.wandscape.shared.data.AltarSpellInfo;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
/**
 * 祭坛 GUI：列出祭坛可施放的 altarOnly 魔法（名称/蓝耗/CD/时长/冷却/施法锁定状态）。
 * 左键点选一行（高亮）→ 点右下角 Submit 才发送 {@link AltarCastRequestPacket} 发布任务；
 * 发布后本地标记「已安排」，且服务端对该祭坛该魔法锁定（见 AltarCastHandler）直到施放结束。
 */
public class AltarScreen extends MedievalScreen {

    private static final int PW = 320;
    private static final int PH = 240;
    private static final int ROW_H = 20;

    private final BlockPos buildingPos;
    private final UUID colonyId;
    private final UUID buildingId;
    private final String creator;
    private final List<AltarSpellInfo> spells;
    /** 本次打开会话中已提交（发布任务）的魔法 id —— 本地锁定反馈。 */
    private final Set<String> submitted = new HashSet<>();

    private MedievalButton submitBtn;
    private SpellList list;

    public AltarScreen(BlockPos buildingPos, UUID colonyId, UUID buildingId, String creator,
                       List<AltarSpellInfo> spells) {
        super(Component.literal("Altar"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.altar.title", "Altar"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "altar_guide";
        this.buildingPos = buildingPos;
        this.colonyId = colonyId;
        this.buildingId = buildingId;
        this.creator = creator;
        this.spells = List.copyOf(spells);
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 106, topPos + PH - 22, 46, 16,
                I18n.name("gui.wandscape.common.close", "Close"), this::onClose));
        submitBtn = new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                I18n.name("gui.wandscape.common.submit", "Submit"), this::onSubmit);
        addRenderableWidget(submitBtn);

        list = new SpellList(leftPos + 12, topPos + headerHeight + 16,
                PW - 24, PH - headerHeight - 58, ROW_H);
        list.setItems(spells);
        list.setOnSelect(index -> updateSubmit());
        addRenderableWidget(list);
        updateSubmit();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        var font = Minecraft.getInstance().font;

        if (creator != null && !creator.isBlank()) {
            String creatorText = I18n.name("gui.wandscape.common.creator_label", "Creator").getString()
                    + ": " + creator;
            g.drawString(font, creatorText, leftPos + 14, topPos + PH - 24, MedievalColors.TEXT_DIM);
        }
    }

    /** 该魔法当前是否不可提交：服务端锁定（施法中）/ 冷却中 / 本会话已提交。 */
    private boolean isBusy(AltarSpellInfo spell) {
        return spell.locked() || spell.cooldownRemaining() > 0 || submitted.contains(spell.magicId());
    }

    /** 点 Submit：为选中的魔法发布祭坛施法任务，并本地标记已提交。 */
    private void onSubmit() {
        AltarSpellInfo selected = list.getSelected();
        if (selected == null || isBusy(selected)) return;
        PacketDistributor.sendToServer(new AltarCastRequestPacket(buildingId, selected.magicId()));
        submitted.add(selected.magicId());
        updateSubmit();
    }

    /** Submit 可用 = 有选中项且未锁定/未冷却/未提交。 */
    private void updateSubmit() {
        if (submitBtn == null) return;
        AltarSpellInfo selected = list != null ? list.getSelected() : null;
        submitBtn.active = selected != null && !isBusy(selected);
    }

    /** 魔法列表行：名称 / 蓝耗·CD·时长 / 状态（冷却·施法中·已安排·可施放）（两行）。 */
    private final class SpellList extends ScrollableList<AltarSpellInfo> {
        SpellList(int x, int y, int w, int h, int rowH) {
            super(x, y, w, h, rowH);
        }

        @Override
        protected void renderRow(GuiGraphics g, AltarSpellInfo spell, int x, int y,
                                 int index, boolean selected, boolean hovered) {
            var font = Minecraft.getInstance().font;
            String name = I18n.name("magic.wandscape." + spell.magicId(), spell.magicId()).getString();
            g.drawString(font, name, x + 2, y + 2, MedievalColors.TEXT_WARM_WHITE);

            String info = I18n.name("gui.wandscape.altar.cost_duration",
                    "蓝 %d · CD %s · 时长 %s",
                    spell.manaCost(), formatSeconds(spell.cooldownTicks()),
                    formatSeconds(spell.durationTicks())).getString();
            g.drawString(font, info, x + 110, y + 2, MedievalColors.TEXT_DIM);

            String status;
            int statusColor;
            if (submitted.contains(spell.magicId())) {
                status = I18n.name("gui.wandscape.altar.submitted", "已安排").getString();
                statusColor = MedievalColors.ACCENT_GOLD;
            } else if (spell.locked()) {
                status = I18n.name("gui.wandscape.altar.casting", "施法中").getString();
                statusColor = MedievalColors.ACCENT_GOLD;
            } else if (spell.cooldownRemaining() > 0) {
                status = I18n.name("gui.wandscape.altar.cooldown", "冷却中 {}")
                        .getString().replace("{}", formatSeconds(spell.cooldownRemaining()));
                statusColor = MedievalColors.DANGER_RED;
            } else {
                status = I18n.name("gui.wandscape.altar.ready", "可施放").getString();
                statusColor = MedievalColors.MANA_BLUE;
            }
            g.drawString(font, status, x + 2, y + 11, statusColor);
        }
    }

    private static String formatSeconds(int ticks) {
        return String.format("%.1fs", ticks / 20.0);
    }
}
