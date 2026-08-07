package com.wsteam.wandscape.npc.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.npc.network.NpcDataPacket;
import com.wsteam.wandscape.npc.network.NpcStrategyPacket;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 施法策略屏（P3）：4 个预设按钮 + 每魔法启停列表。
 *
 * <p>点预设 → 发 {@code NpcStrategyPacket(preset, 空)}，服务端按分类默认排序重算优先级；
 * 点某魔法行 → 切换启停，发 {@code CUSTOM + 当前启用顺序}（自定义预设）。服务端回发
 * {@link NpcDataPacket} 刷新本屏（{@link #apply}）。列表按「启用（优先级序）在前、停用在后」排。
 */
public class NpcStrategyScreen extends MedievalScreen {

    private static final int PW = 300;
    private static final int PH = 230;
    private static final int ROW_H = 24;
    private static final int BTN_W = 62;

    private static final List<String> PRESET_NAMES = List.of("balanced", "offensive", "support", "defensive");

    private final int entityId;
    private String preset = "BALANCED";
    private List<String> knownSpells = List.of();
    private List<String> priority = List.of();

    private SpellList spellList;
    private final Map<String, int[]> presetButtonBounds = new LinkedHashMap<>();

    public NpcStrategyScreen(int entityId, String preset, List<String> knownSpells, List<String> priority) {
        super(Component.literal("Cast Strategy"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.strategy.title", "Cast Strategy"));
        this.showCloseButton = true;
        this.entityId = entityId;
        this.preset = preset;
        this.knownSpells = List.copyOf(knownSpells);
        this.priority = List.copyOf(priority);
    }

    /** 服务端回发策略数据时刷新（保持本屏打开）。 */
    public void apply(NpcDataPacket packet) {
        this.preset = packet.strategyPreset();
        this.knownSpells = packet.knownSpells();
        this.priority = packet.priority();
        if (spellList != null) {
            spellList.setItems(displayItems());
        }
    }

    /** 显示顺序：启用的魔法（按优先级序）在前，停用的（按 spellbook 序）在后。 */
    private List<String> displayItems() {
        List<String> out = new ArrayList<>(priority);
        for (String id : knownSpells) {
            if (!priority.contains(id)) out.add(id);
        }
        return out;
    }

    @Override
    protected void init() {
        super.init();
        presetButtonBounds.clear();

        int left = leftPos + 12;
        int y = topPos + headerHeight + 8;
        int x = left;
        for (String name : PRESET_NAMES) {
            final String p = name.toUpperCase();
            MedievalButton btn = new MedievalButton(x, y, BTN_W, 16,
                    I18n.name("gui.wandscape.strategy.preset." + name, name),
                    () -> sendPreset(p));
            addRenderableWidget(btn);
            presetButtonBounds.put(p, new int[]{x, y});
            x += BTN_W + 6;
        }

        int listY = y + 34;
        spellList = new SpellList(left, listY, PW - 24, PH - headerHeight - 74, ROW_H);
        spellList.setItems(displayItems());
        spellList.setOnRowClick((spell, index, button) -> toggleSpell(spell));
        addRenderableWidget(spellList);

        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                I18n.name("gui.wandscape.common.close", "Close"),
                () -> Minecraft.getInstance().setScreen(null)));
    }

    private void sendPreset(String presetName) {
        PacketDistributor.sendToServer(new NpcStrategyPacket(entityId, presetName, List.of()));
    }

    /** 启停切换：改完后以 CUSTOM 预设发完整启用顺序（服务端按显式顺序重算）。 */
    private void toggleSpell(String spellId) {
        List<String> next = new ArrayList<>(priority);
        if (next.contains(spellId)) {
            next.remove(spellId);
        } else {
            next.add(spellId);
        }
        PacketDistributor.sendToServer(new NpcStrategyPacket(entityId, "CUSTOM", next));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        var font = Minecraft.getInstance().font;
        int left = leftPos + 12;

        // 当前预设高亮（金框）
        int[] bounds = presetButtonBounds.get(preset);
        if (bounds != null) {
            drawGlowBorder(g, bounds[0], bounds[1], BTN_W, 16, MedievalColors.BORDER_GOLD);
        }

        g.drawString(font, I18n.name("gui.wandscape.strategy.priority", "Cast priority (click to toggle)"),
                left, topPos + headerHeight + 26, MedievalColors.TEXT_MUTED);
        g.drawString(font, I18n.name("gui.wandscape.strategy.hint",
                        "Preset orders spells by category; toggling a spell switches to custom."),
                left, topPos + PH - 16, MedievalColors.TEXT_DIM);
    }

    /** 魔法行：名称 + 启停状态。 */
    private final class SpellList extends ScrollableList<String> {
        SpellList(int x, int y, int w, int h, int rowH) {
            super(x, y, w, h, rowH);
        }

        @Override
        protected void renderRow(GuiGraphics g, String spellId, int x, int y,
                                 int index, boolean selected, boolean hovered) {
            var font = Minecraft.getInstance().font;
            boolean enabled = priority.contains(spellId);
            String name = I18n.name("magic.wandscape." + spellId, spellId).getString();
            g.drawString(font, name, x + 2, y + 2,
                    enabled ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED);

            String state = I18n.name(enabled ? "gui.wandscape.strategy.enabled" : "gui.wandscape.strategy.disabled",
                    enabled ? "ON" : "OFF").getString();
            g.drawString(font, state, getX() + width - scrollbarWidth - 52, y + 2,
                    enabled ? MedievalColors.MANA_BLUE : MedievalColors.DANGER_RED);
        }
    }
}
