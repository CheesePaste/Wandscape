package com.wsteam.wandscape.npc.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.systems.RenderSystem;
import com.wsteam.wandscape.npc.network.NpcDataPacket;
import com.wsteam.wandscape.npc.network.NpcStrategyPacket;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.component.ScrollableList;
import com.wsteam.wandscape.shared.ui.skin.SkinRender;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 施法策略屏（两层模型）：顶部 4 个总体策略按钮（管分类优先级）+ 一排 4 个分类按钮
 * （单体攻击/群体攻击/防御/增益）+ 所选分类的魔法列表（滚轮可滚动，每行 ↑/↓/开关，
 * 样式参考 Workstation Queue 面板）。
 *
 * <p>客户端维护各分类的启用列表（{@code enabledByCategory}），任意改动（点预设/上移/下移/开关）
 * 都重排成完整扁平列表发 {@link NpcStrategyPacket}（服务端始终存储）。分类顺序表与
 * {@code CastBrain.PRESET_ORDER} 一致（此处以字符串常量复制，避免跨模块 import）。
 */
public class NpcStrategyScreen extends MedievalScreen {

    private static final int PW = 300;
    private static final int PH = 252;
    private static final int BTN_W = 62;   // 预设按钮
    private static final int CAT_W = 58;   // 分类按钮
    private static final int ROW_H = 24;

    private static final int ROW_BTN_H = 14;
    private static final int ROW_UP_W = 14;
    private static final int ROW_DOWN_W = 14;
    private static final int ROW_TOGGLE_W = 22;
    private static final int ROW_BTN_GAP = 1;
    private static final int ROW_BTNS_W = ROW_UP_W + ROW_DOWN_W + ROW_TOGGLE_W + 2 * ROW_BTN_GAP; // 52
    private static final int ROW_BTNS_RIGHT_PAD = 4;

    private static final List<String> PRESET_NAMES = List.of("balanced", "offensive", "support", "defensive");

    /** 可管理的分类（与 MagicDef.Category 名小写对应；UTILITY 不进策略表）。 */
    private static final List<String> CATEGORY_NAMES =
            List.of("single_target", "aoe", "defense", "support");

    /**
     * 分类级顺序（唯一来源 {@code CastBrain.PRESET_ORDER}，此处复制为避免跨模块 import）。
     * 总体策略 = 按此顺序把各分类启用列表拼接成扁平施法优先级。
     */
    private static List<String> categoryOrder(String preset) {
        return switch (preset) {
            case "OFFENSIVE" -> List.of("single_target", "aoe", "defense", "support");
            case "SUPPORT"   -> List.of("support", "defense", "aoe", "single_target");
            case "DEFENSIVE" -> List.of("defense", "support", "aoe", "single_target");
            default          -> List.of("aoe", "single_target", "support", "defense"); // BALANCED
        };
    }

    private final int entityId;
    private String preset = "BALANCED";
    private List<String> knownSpells = List.of();
    private List<String> spellCategories = List.of();
    private List<String> priority = List.of();
    private String selectedCategory = CATEGORY_NAMES.get(0);

    private final Map<String, List<String>> knownByCategory = new LinkedHashMap<>();
    private final Map<String, List<String>> enabledByCategory = new LinkedHashMap<>();
    private final Set<String> enabledSet = new HashSet<>();

    private final Map<String, int[]> presetButtonBounds = new LinkedHashMap<>();
    private final Map<String, int[]> categoryButtonBounds = new LinkedHashMap<>();

    private SpellRowList list;
    private int cursorX;
    private int cursorY;

    public NpcStrategyScreen(int entityId, String preset, List<String> knownSpells,
                             List<String> spellCategories, List<String> priority) {
        super(Component.literal("Cast Strategy"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.strategy.title", "Cast Strategy"));
        this.showCloseButton = true;
        this.entityId = entityId;
        this.preset = preset;
        this.knownSpells = List.copyOf(knownSpells);
        this.spellCategories = List.copyOf(spellCategories);
        this.priority = List.copyOf(priority);
    }

    /** 服务端回发策略数据时刷新（保持本屏打开）。 */
    public void apply(NpcDataPacket packet) {
        this.preset = packet.strategyPreset();
        this.knownSpells = packet.knownSpells();
        this.spellCategories = packet.spellCategories();
        this.priority = packet.priority();
        rebuild();
    }

    // ── 状态重建 ──

    /** 从 knownSpells/priority 重建分类分组；刷新当前列表。 */
    private void rebuild() {
        knownByCategory.clear();
        enabledByCategory.clear();
        enabledSet.clear();
        for (String cat : CATEGORY_NAMES) {
            knownByCategory.put(cat, new ArrayList<>());
            enabledByCategory.put(cat, new ArrayList<>());
        }
        for (int i = 0; i < knownSpells.size(); i++) {
            String cat = normalizeCategory(i < spellCategories.size() ? spellCategories.get(i) : "unknown");
            if (cat != null) {
                knownByCategory.get(cat).add(knownSpells.get(i));
            }
        }
        for (String id : priority) {
            String cat = categoryOf(id);
            if (cat != null) {
                enabledByCategory.get(cat).add(id);
                enabledSet.add(id);
            }
        }
        if (!CATEGORY_NAMES.contains(selectedCategory)) {
            selectedCategory = defaultCategory();
        }
        if (list != null) {
            list.setItems(displayItems());
        }
    }

    /** 所选分类的显示顺序：启用的在前（优先级序），停用的按 spellbook 序在后。 */
    private List<String> displayItems() {
        List<String> enabled = enabledByCategory.getOrDefault(selectedCategory, List.of());
        List<String> known = knownByCategory.getOrDefault(selectedCategory, List.of());
        List<String> out = new ArrayList<>(enabled);
        for (String id : known) {
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    /** 按当前总体策略的分类顺序，把各分类启用列表拼成完整扁平优先级。 */
    private List<String> buildFlatList() {
        List<String> out = new ArrayList<>();
        for (String cat : categoryOrder(preset)) {
            out.addAll(enabledByCategory.getOrDefault(cat, List.of()));
        }
        return out;
    }

    private String defaultCategory() {
        for (String cat : CATEGORY_NAMES) {
            if (!knownByCategory.getOrDefault(cat, List.of()).isEmpty()) {
                return cat;
            }
        }
        return CATEGORY_NAMES.get(0);
    }

    private static String normalizeCategory(String name) {
        if (name == null) return null;
        String c = name.toLowerCase(Locale.ROOT);
        return CATEGORY_NAMES.contains(c) ? c : null;
    }

    private String categoryOf(String id) {
        int idx = knownSpells.indexOf(id);
        if (idx < 0) return null;
        return normalizeCategory(idx < spellCategories.size() ? spellCategories.get(idx) : "unknown");
    }

    private boolean isEnabled(String spellId) {
        return enabledSet.contains(spellId);
    }

    // ── 交互 ──

    private void send() {
        PacketDistributor.sendToServer(new NpcStrategyPacket(entityId, preset, buildFlatList()));
    }

    private void onPreset(String newPreset) {
        this.preset = newPreset;
        send();
    }

    private void onCategory(String cat) {
        this.selectedCategory = cat;
        if (list != null) list.setItems(displayItems());
    }

    private void onToggle(String spellId) {
        String cat = categoryOf(spellId);
        if (cat == null) return;
        List<String> en = enabledByCategory.get(cat);
        if (en.contains(spellId)) {
            en.remove(spellId);
            enabledSet.remove(spellId);
        } else {
            en.add(spellId);
            enabledSet.add(spellId);
        }
        refreshAfterEdit();
    }

    private void onMoveUp(String spellId) {
        String cat = categoryOf(spellId);
        if (cat == null) return;
        List<String> en = enabledByCategory.get(cat);
        int i = en.indexOf(spellId);
        if (i > 0) {
            Collections.swap(en, i, i - 1);
            refreshAfterEdit();
        }
    }

    private void onMoveDown(String spellId) {
        String cat = categoryOf(spellId);
        if (cat == null) return;
        List<String> en = enabledByCategory.get(cat);
        int i = en.indexOf(spellId);
        if (i >= 0 && i < en.size() - 1) {
            Collections.swap(en, i, i + 1);
            refreshAfterEdit();
        }
    }

    /** 本地即时刷新列表（服务端回包 apply() 会再对账），并发包。 */
    private void refreshAfterEdit() {
        if (list != null) list.setItems(displayItems());
        send();
    }

    // ── 布局 ──

    @Override
    protected void init() {
        super.init();
        presetButtonBounds.clear();
        categoryButtonBounds.clear();

        int left = leftPos + 12;
        int presetY = topPos + headerHeight + 8;
        int x = left;
        for (String name : PRESET_NAMES) {
            final String p = name.toUpperCase();
            MedievalButton btn = new MedievalButton(x, presetY, BTN_W, 16,
                    I18n.name("gui.wandscape.strategy.preset." + name, name),
                    () -> onPreset(p));
            addRenderableWidget(btn);
            presetButtonBounds.put(p, new int[]{x, presetY});
            x += BTN_W + 6;
        }

        int catY = presetY + 22;
        x = left;
        for (String cat : CATEGORY_NAMES) {
            MedievalButton btn = new MedievalButton(x, catY, CAT_W, 16,
                    I18n.name("gui.wandscape.strategy.category." + cat, cat),
                    () -> onCategory(cat));
            addRenderableWidget(btn);
            categoryButtonBounds.put(cat, new int[]{x, catY});
            x += CAT_W + 6;
        }

        int listY = catY + 20;
        int listH = PH - (listY - topPos) - 26;
        list = new SpellRowList(left, listY, PW - 24, listH, ROW_H);
        addRenderableWidget(list);

        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                I18n.name("gui.wandscape.common.close", "Close"),
                () -> Minecraft.getInstance().setScreen(null)));

        rebuild();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.cursorX = mouseX;
        this.cursorY = mouseY;
        super.render(g, mouseX, mouseY, partialTick);

        int[] pb = presetButtonBounds.get(preset);
        if (pb != null) {
            drawGlowBorder(g, pb[0], pb[1], BTN_W, 16, MedievalColors.BORDER_GOLD);
        }
        int[] cb = categoryButtonBounds.get(selectedCategory);
        if (cb != null) {
            drawGlowBorder(g, cb[0], cb[1], CAT_W, 16, MedievalColors.BORDER_GOLD);
        }
    }

    // ── 魔法行列表：名称 + ↑/↓/开关 ──

    private final class SpellRowList extends ScrollableList<String> {

        SpellRowList(int x, int y, int w, int h, int rowH) {
            super(x, y, w, h, rowH);
        }

        private int buttonAreaLeft() {
            return getX() + width - scrollbarWidth - ROW_BTNS_RIGHT_PAD - ROW_BTNS_W;
        }

        @Override
        protected void renderRow(GuiGraphics g, String spellId, int x, int y,
                                 int index, boolean selected, boolean hovered) {
            var font = Minecraft.getInstance().font;
            boolean enabled = isEnabled(spellId);

            int btnY = y + (rowHeight - ROW_BTN_H) / 2;
            int upLeft = buttonAreaLeft();
            int downLeft = upLeft + ROW_UP_W + ROW_BTN_GAP;
            int toggleLeft = downLeft + ROW_DOWN_W + ROW_BTN_GAP;

            // 名称（超宽截断，避免盖住按钮）
            String name = I18n.name("magic.wandscape." + spellId, spellId).getString();
            int nameMax = upLeft - (x + 2) - 6;
            if (nameMax > 0 && font.width(name) > nameMax) {
                name = font.plainSubstrByWidth(name, nameMax);
            }
            g.drawString(font, name, x + 2, y + 2,
                    enabled ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_DIM);

            // ↑/↓：仅对「启用且相邻同为启用」的行可用（启用块在最前，禁用行不可移动）
            boolean prevEnabled = index > 0 && isEnabled(items.get(index - 1));
            boolean nextEnabled = index < items.size() - 1 && isEnabled(items.get(index + 1));
            boolean canUp = enabled && prevEnabled;
            boolean canDown = enabled && nextEnabled;
            boolean overUp = cursorX >= upLeft && cursorX < upLeft + ROW_UP_W
                    && cursorY >= btnY && cursorY < btnY + ROW_BTN_H;
            boolean overDown = cursorX >= downLeft && cursorX < downLeft + ROW_DOWN_W
                    && cursorY >= btnY && cursorY < btnY + ROW_BTN_H;

            drawArrow(g, upLeft, btnY, true, canUp, overUp);
            drawArrow(g, downLeft, btnY, false, canDown, overDown);

            // 开关按钮
            boolean overToggle = cursorX >= toggleLeft && cursorX < toggleLeft + ROW_TOGGLE_W
                    && cursorY >= btnY && cursorY < btnY + ROW_BTN_H;
            SkinRender.drawButton(g, toggleLeft, btnY, ROW_TOGGLE_W, ROW_BTN_H, 0);
            if (overToggle) {
                g.fill(toggleLeft + 1, btnY + 1, toggleLeft + ROW_TOGGLE_W - 1, btnY + ROW_BTN_H - 1, 0x30FFFFFF);
            }
            String toggleText = I18n.name(
                    enabled ? "gui.wandscape.strategy.toggle.on" : "gui.wandscape.strategy.toggle.off",
                    enabled ? "On" : "Off").getString();
            g.drawCenteredString(font, toggleText, toggleLeft + ROW_TOGGLE_W / 2,
                    btnY + (ROW_BTN_H - 9) / 2,
                    enabled ? MedievalColors.MANA_BLUE : MedievalColors.DANGER_RED);
        }

        private void drawArrow(GuiGraphics g, int x, int y, boolean up, boolean canUse, boolean over) {
            int state = canUse ? (over ? 1 : 0) : 2;
            if (canUse && over) {
                RenderSystem.setShaderColor(1.6F, 1.6F, 1.6F, 1.0F);
            }
            if (up) {
                SkinRender.drawUpArrow(g, x, y, ROW_UP_W, ROW_BTN_H, state);
            } else {
                SkinRender.drawDownArrow(g, x, y, ROW_DOWN_W, ROW_BTN_H, state);
            }
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!visible || !active || button != 0) return false;

            int sbX = getX() + width - scrollbarWidth;
            if (mouseX >= sbX && mouseX < getX() + width) {
                return super.mouseClicked(mouseX, mouseY, button);
            }
            int contentRight = getX() + width - scrollbarWidth;
            if (mouseX < getX() || mouseX >= contentRight) return false;

            int relY = (int) mouseY - getY();
            if (relY < 0 || relY >= height) return false;
            int row = (relY / rowHeight) + (scrollOffset / rowHeight);
            if (row < 0 || row >= items.size()) return false;

            int rowY = getY() + row * rowHeight - scrollOffset;
            if (mouseY < rowY || mouseY >= rowY + rowHeight) return false;

            int mx = (int) mouseX;
            int btnY = rowY + (rowHeight - ROW_BTN_H) / 2;
            if (mouseY >= btnY && mouseY < btnY + ROW_BTN_H) {
                int upLeft = buttonAreaLeft();
                int downLeft = upLeft + ROW_UP_W + ROW_BTN_GAP;
                int toggleLeft = downLeft + ROW_DOWN_W + ROW_BTN_GAP;
                if (mx >= upLeft && mx < upLeft + ROW_UP_W) {
                    onMoveUp(items.get(row));
                    return true;
                }
                if (mx >= downLeft && mx < downLeft + ROW_DOWN_W) {
                    onMoveDown(items.get(row));
                    return true;
                }
                if (mx >= toggleLeft && mx < toggleLeft + ROW_TOGGLE_W) {
                    onToggle(items.get(row));
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }
}
