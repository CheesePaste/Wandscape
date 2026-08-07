package com.wsteam.wandscape.building.client;

import java.util.List;
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
 * 祭坛 GUI：列出祭坛可施放的 altarOnly 魔法（名称/蓝耗/CD/时长/当前冷却状态），
 * 点选一行 → 发送 {@link AltarCastRequestPacket} 请求祭坛施法。
 */
public class AltarScreen extends MedievalScreen {

    private static final int PW = 320;
    private static final int PH = 240;
    private static final int ROW_H = 20;

    private final BlockPos buildingPos;
    private final UUID colonyId;
    private final UUID buildingId;
    private final List<AltarSpellInfo> spells;

    public AltarScreen(BlockPos buildingPos, UUID colonyId, UUID buildingId,
                       List<AltarSpellInfo> spells) {
        super(Component.literal("Altar"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.altar.title", "Altar"));
        this.showCloseButton = true;
        this.buildingPos = buildingPos;
        this.colonyId = colonyId;
        this.buildingId = buildingId;
        this.spells = List.copyOf(spells);
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                I18n.name("gui.wandscape.common.close", "Close"), this::onClose));

        SpellList list = new SpellList(leftPos + 12, topPos + headerHeight + 16,
                PW - 24, PH - headerHeight - 58, ROW_H);
        list.setItems(spells);
        list.setOnRowClick((spell, index, button) -> {
            if (spell != null && spell.cooldownRemaining() <= 0) {
                PacketDistributor.sendToServer(new AltarCastRequestPacket(buildingId, spell.magicId()));
            }
        });
        addRenderableWidget(list);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        var font = Minecraft.getInstance().font;

        g.drawString(font, I18n.name("gui.wandscape.altar.hint",
                "Select a spell — a colony mage will walk over and cast it (costs its mana)."),
                leftPos + 14, topPos + headerHeight + 3, MedievalColors.TEXT_MUTED);

        String bldText = I18n.name("gui.wandscape.common.building_label", "Building").getString()
                + ": " + buildingId.toString().substring(0, 8);
        g.drawString(font, bldText, leftPos + 14, topPos + PH - 24, MedievalColors.TEXT_DIM);
    }

    /** 魔法列表行：名称 / 蓝耗·CD·时长 / 冷却状态（两行）。 */
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

            String info = "蓝 " + spell.manaCost() + " · CD " + formatSeconds(spell.cooldownTicks())
                    + " · 时长 " + formatSeconds(spell.durationTicks());
            g.drawString(font, info, x + 110, y + 2, MedievalColors.TEXT_DIM);

            if (spell.cooldownRemaining() > 0) {
                g.drawString(font, I18n.name("gui.wandscape.altar.cooldown", "冷却中 {}")
                                .getString().replace("{}", formatSeconds(spell.cooldownRemaining())),
                        x + 2, y + 11, MedievalColors.DANGER_RED);
            } else {
                g.drawString(font, I18n.name("gui.wandscape.altar.ready", "可施放"),
                        x + 2, y + 11, MedievalColors.MANA_BLUE);
            }
        }
    }

    private static String formatSeconds(int ticks) {
        return String.format("%.1fs", ticks / 20.0);
    }
}
