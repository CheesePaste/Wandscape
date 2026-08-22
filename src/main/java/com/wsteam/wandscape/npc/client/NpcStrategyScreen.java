package com.wsteam.wandscape.npc.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.component.EquippedMagicComponent;
import com.wsteam.wandscape.magic.item.SpellItem;
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
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 施法策略屏（装备制，B 阶段）：顶部 4 个总体策略按钮（管跨类施法先后，保留）+ 中部 4 分类×3
 * 槽位面板（槽位序 = 类内优先级，点已占槽卸载）+ 右侧玩家背包卷轴源列表（点卷轴装备到对应分类
 * 首个空槽，卷轴消耗由服务端校验——每类 ≤3、去重、UTILITY 不可装备）。
 *
 * <p>本地维护各分类装备桶（{@code equippedByCategory}），任意改动重排成完整扁平装备态
 * （分类固定序 × 桶内槽位序）连同本次消耗的背包槽发 {@link NpcStrategyPacket}；服务端按真实
 * 分类装桶校验后回发 {@link NpcDataPacket} 对账（权威状态始终在服务端）。
 * 分类名/上限与 {@code EquippedMagicComponent.CATEGORIES} 一致。
 */
public class NpcStrategyScreen extends MedievalScreen {

    private static final int PW = 360;
    private static final int PH = 250;
    private static final int BTN_W = 62;   // 预设按钮
    private static final int SLOT = 18;    // 槽位
    private static final int SLOT_PITCH = 20;
    private static final int ROW_H = 22;   // 分类行高
    private static final int LABEL_W = 92; // 分类标签宽

    private static final List<String> PRESET_NAMES = List.of("balanced", "offensive", "support", "defensive");

    private final int entityId;
    private String preset = "BALANCED";
    /** 服务端权威装备（knownSpells + spellCategories 并行同序），本地镜像重建依据。 */
    private List<String> knownSpells = List.of();
    private List<String> spellCategories = List.of();
    /** 各分类已装备魔法（本地镜像，服务端为权威；桶键 = 分类固定序保持一致）。 */
    private final Map<String, List<String>> equippedByCategory = new LinkedHashMap<>();
    /** 战斗魔法目录（id → 分类小写）：识别背包卷轴归属分类。 */
    private final Map<String, String> magicCatalog = new HashMap<>();
    /** 背包中 SpellItem 的槽位列表（源列表）。 */
    private List<Integer> sourceSlots = List.of();

    private final Map<String, int[]> presetButtonBounds = new LinkedHashMap<>();
    /** "单分类:第i槽" → {x,y}。 */
    private final Map<String, int[]> slotRects = new LinkedHashMap<>();

    private SpellSourceList sourceList;
    private int equipTop;
    private int sourceListX;

    public NpcStrategyScreen(int entityId, String preset, List<String> knownSpells,
                             List<String> spellCategories, Map<String, String> magicCatalog) {
        super(Component.literal("Cast Strategy"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.strategy.title", "Cast Strategy"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "strategy_guide";
        this.entityId = entityId;
        this.preset = preset != null ? preset : "BALANCED";
        this.magicCatalog.clear();
        if (magicCatalog != null) this.magicCatalog.putAll(magicCatalog);
        this.knownSpells = List.copyOf(knownSpells != null ? knownSpells : List.of());
        this.spellCategories = List.copyOf(spellCategories != null ? spellCategories : List.of());
        rebuildBoxes();
    }

    /** 服务端回发策略数据时刷新（保持本屏打开）。 */
    public void apply(NpcDataPacket packet) {
        this.preset = packet.strategyPreset();
        this.magicCatalog.clear();
        if (packet.magicCatalog() != null) this.magicCatalog.putAll(packet.magicCatalog());
        this.knownSpells = List.copyOf(packet.knownSpells());
        this.spellCategories = List.copyOf(packet.spellCategories());
        rebuildBoxes();
    }

    // ── 状态重建 ──

    /** 从 knownSpells/spellCategories 重建分类装备桶；刷新源列表与当前显示。 */
    private void rebuildBoxes() {
        equippedByCategory.clear();
        for (String cat : EquippedMagicComponent.CATEGORIES) {
            equippedByCategory.put(cat, new ArrayList<>());
        }
        for (int i = 0; i < knownSpells.size(); i++) {
            String cat = normalizeCategory(i < spellCategories.size() ? spellCategories.get(i) : null);
            if (cat != null) {
                equippedByCategory.get(cat).add(knownSpells.get(i));
            }
        }
        refreshSource();
        if (sourceList != null) {
            sourceList.setItems(toStringList(sourceSlots));
        }
    }

    private void refreshSource() {
        var player = Minecraft.getInstance().player;
        sourceSlots = List.of();
        if (player == null) return;
        List<Integer> slots = new ArrayList<>();
        var items = player.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && stack.getItem() instanceof SpellItem) {
                slots.add(i);
            }
        }
        sourceSlots = slots;
    }

    private static List<String> toStringList(List<Integer> slots) {
        List<String> out = new ArrayList<>(slots.size());
        for (Integer slot : slots) {
            out.add(Integer.toString(slot));
        }
        return out;
    }

    private static String normalizeCategory(String name) {
        if (name == null) return null;
        String c = name.toLowerCase(Locale.ROOT);
        return EquippedMagicComponent.isCategory(c) ? c : null;
    }

    private boolean isEquipped(String magicId) {
        for (List<String> bucket : equippedByCategory.values()) {
            if (bucket.contains(magicId)) return true;
        }
        return false;
    }

    // ── 交互 ──

    private void send(int consumeSlot) {
        List<String> flat = new ArrayList<>();
        for (String cat : EquippedMagicComponent.CATEGORIES) {
            flat.addAll(equippedByCategory.getOrDefault(cat, List.of()));
        }
        PacketDistributor.sendToServer(new NpcStrategyPacket(entityId, preset, flat, consumeSlot));
    }

    private void onPreset(String newPreset) {
        this.preset = newPreset;
        send(NpcStrategyPacket.NO_CONSUME);
    }

    /** 点击背包卷轴 → 装备到该魔法所属分类首个空槽（本地预校验，服务端权威）。 */
    private void onEquipScroll(int invSlot) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        if (invSlot < 0 || invSlot >= player.getInventory().items.size()) return;
        ItemStack stack = player.getInventory().getItem(invSlot);
        if (stack.isEmpty() || !(stack.getItem() instanceof SpellItem)) return;
        String magicId = SpellItem.getMagicId(stack);
        if (magicId == null) {
            showFeedback(I18n.name("gui.wandscape.strategy.not_bound", "Unbound scroll"),
                    MedievalColors.DANGER_RED);
            return;
        }
        String cat = magicCatalog.get(magicId);
        if (cat == null) {
            showFeedback(I18n.name("gui.wandscape.strategy.not_equippable", "Not equippable"),
                    MedievalColors.DANGER_RED);
            return;
        }
        if (isEquipped(magicId)) {
            showFeedback(I18n.name("gui.wandscape.strategy.already_equipped", "Already equipped"),
                    MedievalColors.DANGER_RED);
            return;
        }
        List<String> bucket = equippedByCategory.get(cat);
        if (bucket == null || bucket.size() >= EquippedMagicComponent.MAX_PER_CATEGORY) {
            showFeedback(I18n.name("gui.wandscape.strategy.slot_full", "Category full (max 3)"),
                    MedievalColors.DANGER_RED);
            return;
        }
        bucket.add(magicId);
        showFeedback(I18n.name("gui.wandscape.strategy.equipped", "Equipped: ").copy()
                        .append(magicName(magicId)),
                MedievalColors.SUCCESS_GREEN);
        send(invSlot);
    }

    /** 点击已占槽 → 卸载该魔法。 */
    private void onUnequip(String category, String magicId) {
        List<String> bucket = equippedByCategory.get(category);
        if (bucket == null || !bucket.remove(magicId)) return;
        showFeedback(I18n.name("gui.wandscape.strategy.unequipped", "Unequipped: ").copy()
                        .append(magicName(magicId)),
                MedievalColors.TEXT_WARM_WHITE);
        send(NpcStrategyPacket.NO_CONSUME);
    }

    private static Component magicName(String magicId) {
        return Component.translatableWithFallback("magic.wandscape." + magicId, magicId);
    }

    // ── 布局 ──

    @Override
    protected void init() {
        super.init();
        presetButtonBounds.clear();

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

        // 中部：4 分类 × 3 槽位（右侧卷轴源列表并排）
        equipTop = presetY + 24;
        int listRight = leftPos + PW - 12;
        sourceListX = left + LABEL_W + EquippedMagicComponent.MAX_PER_CATEGORY * SLOT_PITCH + 18;
        int lsX = sourceListX;
        int lsW = listRight - lsX;
        int lsY = equipTop + 10; // 对齐第一行槽位
        sourceList = new SpellSourceList(lsX, lsY, lsW, 4 * ROW_H, ROW_H);
        sourceList.setItems(toStringList(sourceSlots));
        sourceList.setOnRowClick((slotStr, index, button) -> {
            if (button == 0) {
                onEquipScroll(Integer.parseInt(slotStr));
            }
        });
        addRenderableWidget(sourceList);

        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                I18n.name("gui.wandscape.common.close", "Close"),
                () -> Minecraft.getInstance().setScreen(null)));

        rebuildBoxes();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        // 当前预设高亮
        int[] pb = presetButtonBounds.get(preset);
        if (pb != null) {
            drawGlowBorder(g, pb[0], pb[1], BTN_W, 16, MedievalColors.BORDER_GOLD);
        }

        // 装备区标题
        g.drawString(font, I18n.name("gui.wandscape.strategy.equip_label",
                        "Equipped (max 3 per category, slot order = priority)").getString(),
                leftPos + 12, equipTop, MedievalColors.ACCENT_GOLD);
        // 源列表标题（右侧列，对齐源列表顶）
        g.drawString(font, I18n.name("gui.wandscape.strategy.source_label", "Scrolls").getString(),
                sourceListX, equipTop, MedievalColors.ACCENT_GOLD);

        slotRects.clear();
        int left = leftPos + 12;
        int slotCol = left + LABEL_W;
        for (int row = 0; row < EquippedMagicComponent.CATEGORIES.size(); row++) {
            String cat = EquippedMagicComponent.CATEGORIES.get(row);
            int rowY = equipTop + 10 + row * ROW_H;
            g.drawString(font, I18n.name("gui.wandscape.strategy.category." + cat, cat).getString(),
                    left, rowY + (ROW_H - font.lineHeight) / 2, MedievalColors.TEXT_WARM_WHITE);
            List<String> bucket = equippedByCategory.getOrDefault(cat, List.of());
            for (int s = 0; s < EquippedMagicComponent.MAX_PER_CATEGORY; s++) {
                int sx = slotCol + s * SLOT_PITCH;
                int sy = rowY + (ROW_H - SLOT) / 2;
                String key = cat + ":" + s;

                // 槽背景 + 边框
                g.fill(sx, sy, sx + SLOT, sy + SLOT, MedievalColors.PARCHMENT_DEEPEST);
                g.fill(sx, sy, sx + SLOT, sy + 1, MedievalColors.BORDER_GOLD_DARK);
                g.fill(sx, sy + SLOT - 1, sx + SLOT, sy + SLOT, MedievalColors.BORDER_GOLD_DARK);
                g.fill(sx, sy, sx + 1, sy + SLOT, MedievalColors.BORDER_GOLD_DARK);
                g.fill(sx + SLOT - 1, sy, sx + SLOT, sy + SLOT, MedievalColors.BORDER_GOLD_DARK);

                if (s < bucket.size()) {
                    String magicId = bucket.get(s);
                    // 槽位序高亮：占满边框金色
                    g.fill(sx, sy, sx + SLOT, sy + 1, MedievalColors.BORDER_GOLD);
                    g.fill(sx, sy + SLOT - 1, sx + SLOT, sy + SLOT, MedievalColors.BORDER_GOLD);
                    g.fill(sx, sy, sx + 1, sy + SLOT, MedievalColors.BORDER_GOLD);
                    g.fill(sx + SLOT - 1, sy, sx + SLOT, sy + SLOT, MedievalColors.BORDER_GOLD);
                    ItemStack scroll = new ItemStack(Wandscape.SPELL_SCROLL.get());
                    SpellItem.setMagicId(scroll, magicId);
                    g.renderItem(scroll, sx + 1, sy + 1);
                }
                slotRects.put(key, new int[]{sx, sy});
            }
        }

        // 槽位 tooltip（悬停已占槽 → 魔法名 + 提示卸载）
        for (int row = 0; row < EquippedMagicComponent.CATEGORIES.size(); row++) {
            String cat = EquippedMagicComponent.CATEGORIES.get(row);
            List<String> bucket = equippedByCategory.getOrDefault(cat, List.of());
            for (int s = 0; s < Math.min(EquippedMagicComponent.MAX_PER_CATEGORY, bucket.size()); s++) {
                int[] r = slotRects.get(cat + ":" + s);
                if (r == null) continue;
                String magicId = bucket.get(s);
                if (mouseX >= r[0] && mouseX < r[0] + SLOT && mouseY >= r[1] && mouseY < r[1] + SLOT) {
                    g.renderTooltip(font,
                            magicName(magicId).copy().append(
                                    I18n.name("gui.wandscape.strategy.tip.unequip", " — 点击卸载")),
                            mouseX, mouseY);
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        for (String key : slotRects.keySet()) {
            int[] r = slotRects.get(key);
            if (r == null) continue;
            if (mouseX >= r[0] && mouseX < r[0] + SLOT && mouseY >= r[1] && mouseY < r[1] + SLOT) {
                int colon = key.indexOf(':');
                String cat = key.substring(0, colon);
                int s = Integer.parseInt(key.substring(colon + 1));
                List<String> bucket = equippedByCategory.getOrDefault(cat, List.of());
                if (s < bucket.size()) {
                    onUnequip(cat, bucket.get(s));
                } else {
                    showFeedback(I18n.name("gui.wandscape.strategy.empty_slot", "点右侧卷轴装备"), MedievalColors.TEXT_DIM);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ── 背包卷轴源列表 ──

    private final class SpellSourceList extends ScrollableList<String> {

        SpellSourceList(int x, int y, int w, int h, int rowH) {
            super(x, y, w, h, rowH);
        }

        @Override
        protected void renderRow(GuiGraphics g, String slotStr, int x, int y,
                                 int index, boolean selected, boolean hovered) {
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            int slot = Integer.parseInt(slotStr);
            if (slot < 0 || slot >= player.getInventory().items.size()) return;
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) return;
            g.renderItem(stack, x + 1, y + 2);
            String magicId = SpellItem.getMagicId(stack);
            String name = magicId != null ? magicName(magicId).getString() : "?";
            int nameMax = getX() + width - scrollbarWidth - (x + 20);
            if (nameMax > 0 && font.width(name) > nameMax) {
                name = font.plainSubstrByWidth(name, nameMax);
            }
            g.drawString(font, name, x + 20, y + (rowHeight - font.lineHeight) / 2,
                    MedievalColors.TEXT_WARM_WHITE);
        }
    }
}