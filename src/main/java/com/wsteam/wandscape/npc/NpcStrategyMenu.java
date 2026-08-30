package com.wsteam.wandscape.npc;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.component.EquippedMagicComponent;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.magic.item.SpellItem;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.vanilla.VanillaPlayerInventory;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/**
 * NPC 施法策略容器菜单：12 个卷轴槽（4 分类 × 每类 3，槽位序 = 类内施法优先级）+
 * 原版玩家背包槽。槽位是真实 vanilla 槽——放卷轴/取出/Shift 快速转移全部生效；
 * 每次槽变更通过 {@link #slotChanged} 把 12 槽状态重建成扁平装备态写回 NPC 的
 * {@code EquippedMagicComponent}（服务端权威，槽内卷轴即装备物，取出 = 拿回卷轴）。
 *
 * <p>槽仅接受魔法卷轴（{@link SpellSlot#mayPlace} 校验可装备/去重/每类上限，不做分类匹配）；
 * 预设由客户端按钮经 {@code NpcStrategyPacket} 修改，本菜单不处理。
 */
public class NpcStrategyMenu extends AbstractContainerMenu {

    private static final String TAG = "NpcStrategyMenu";

    public static final int PANEL_W = 300;
    public static final int PANEL_H = 230;
    public static final int SLOT = 18;
    public static final int CATEGORY_COUNT = EquippedMagicComponent.CATEGORIES.size(); // 4
    public static final int SLOTS_PER_CATEGORY = EquippedMagicComponent.MAX_PER_CATEGORY; // 3
    public static final int SPELL_SLOT_COUNT = CATEGORY_COUNT * SLOTS_PER_CATEGORY;       // 12
    public static final int SPELL_X = 100;
    public static final int SPELL_Y = 52;
    public static final int ROW_PITCH = 20;
    /** 玩家背包槽区顶（原版 6 行箱坐标，由共享组件计算）。 */
    public static final int PLAYER_INV_Y = VanillaPlayerInventory.inventoryTop(6);

    @Nullable
    private final WandscapeNpc npc;
    private final SimpleContainer spellSlots = new SimpleContainer(SPELL_SLOT_COUNT);

    /** Client-side factory (MenuType): contents arrive via sync. */
    public NpcStrategyMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null);
    }

    /** Server-side factory (MenuProvider). */
    public NpcStrategyMenu(int containerId, Inventory playerInventory, @Nullable WandscapeNpc npc) {
        super(Wandscape.NPC_STRATEGY_MENU.get(), containerId);
        this.npc = npc;
        if (npc != null) {
            for (int cat = 0; cat < CATEGORY_COUNT; cat++) {
                List<EquippedMagicComponent.SpellEntry> bucket =
                        npc.equippedMagic.listEntries(EquippedMagicComponent.CATEGORIES.get(cat));
                for (int s = 0; s < SLOTS_PER_CATEGORY && s < bucket.size(); s++) {
                    spellSlots.setItem(cat * SLOTS_PER_CATEGORY + s, scrollFor(bucket.get(s)));
                }
            }
        }
        for (int cat = 0; cat < CATEGORY_COUNT; cat++) {
            for (int s = 0; s < SLOTS_PER_CATEGORY; s++) {
                addSlot(new SpellSlot(spellSlots, cat * SLOTS_PER_CATEGORY + s,
                        SPELL_X + s * SLOT, SPELL_Y + cat * ROW_PITCH,
                        EquippedMagicComponent.CATEGORIES.get(cat)));
            }
        }
        VanillaPlayerInventory.addTo(this::addSlot, playerInventory,
                VanillaPlayerInventory.inventoryTop(6), VanillaPlayerInventory.hotbarTop(6));
    }

    public int getEntityId() {
        return npc != null ? npc.getId() : -1;
    }

    private static ItemStack scrollFor(EquippedMagicComponent.SpellEntry entry) {
        if (entry == null || entry.id().isBlank()) return ItemStack.EMPTY;
        if (com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()
                && com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.isValidSpell(entry.id())) {
            return com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.createScroll(entry.id(), entry.level());
        }
        ItemStack scroll = new ItemStack(Wandscape.SPELL_SCROLL.get());
        SpellItem.setMagicId(scroll, entry.id());
        return scroll;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack result = slot.getItem().copy();
        if (index >= SPELL_SLOT_COUNT) {
            // 玩家卷轴 → 策略槽（mayPlace 校验分类/去重/上限）
            if (!moveItemStackTo(slot.getItem(), 0, SPELL_SLOT_COUNT, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 策略槽 → 玩家背包（拿回卷轴）
            if (!moveItemStackTo(slot.getItem(), SPELL_SLOT_COUNT, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        if (npc == null) return true;
        // 与 NpcMenu.stillValid 一致：远程管理站语义，只看 NPC 存活不要求玩家靠近——
        // 法师小屋面板远程打开的策略菜单，法师距玩家 >64 格时按原版距离检查会在下一
        // tick 被服务端关闭。死亡/区块卸载仍关闭。
        return !npc.isRemoved() && npc.isAlive();
    }

    /** 任意槽操作（放卷轴/取出/Shift/拖拽）后重建装备态写回 NPC。 */
    @Override
    public void clicked(int slotId, int dragType, net.minecraft.world.inventory.ClickType clickType, Player player) {
        super.clicked(slotId, dragType, clickType, player);
        if (npc == null || npc.isRemoved()) return;
        // 拖拽多格结束会以 slotId=-999 回调，仍需同步。QUICK_MOVE（Shift 快速转移）的
        // 源槽在玩家背包（索引 ≥ SPELL_SLOT_COUNT），范围判定不命中——必须按 clickType 捕获，
        // 否则 Shift 放的卷轴不会写回 equippedMagic，退出重进即丢失。
        if (slotId >= 0 && slotId < SPELL_SLOT_COUNT
                || clickType == net.minecraft.world.inventory.ClickType.QUICK_MOVE
                || clickType == net.minecraft.world.inventory.ClickType.QUICK_CRAFT) {
            syncEquipped();
        }
    }

    private void syncEquipped() {
        EquippedMagicComponent newEquipped = new EquippedMagicComponent();
        for (int cat = 0; cat < CATEGORY_COUNT; cat++) {
            String categoryName = EquippedMagicComponent.CATEGORIES.get(cat);
            for (int s = 0; s < SLOTS_PER_CATEGORY; s++) {
                ItemStack item = spellSlots.getItem(cat * SLOTS_PER_CATEGORY + s);
                if (item.isEmpty()) continue;
                if (item.getItem() instanceof SpellItem) {
                    String magicId = SpellItem.getMagicId(item);
                    if (magicId != null) {
                        newEquipped.equip(categoryName, magicId);
                    }
                } else if (com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()
                        && com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.isScroll(item)) {
                    String spellId = com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.getSpellId(item);
                    int level = com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.getSpellLevel(item);
                    if (spellId != null && com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.isValidSpell(spellId)) {
                        newEquipped.equip(categoryName, spellId, level);
                    }
                }
            }
        }
        npc.equippedMagic.replaceWith(newEquipped);
        Log.info(TAG, "[Strategy] {} equipped {} spells (npc={})",
                npc.getUUID().toString().substring(0, 8), newEquipped.flattened().size());
    }

    /** 卷轴槽：接受原生魔法卷轴与铁魔法卷轴，且去重、每类 ≤ 上限。 */
    public static final class SpellSlot extends Slot {
        private final String category;

        public SpellSlot(Container container, int index, int x, int y, String category) {
            super(container, index, x, y);
            this.category = category;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;

            String magicId = null;
            if (stack.getItem() instanceof SpellItem) {
                magicId = SpellItem.getMagicId(stack);
                if (magicId == null) return false;
                MagicDef def = SpellbookLoader.getSpec(magicId);
                if (def == null) return false;
                if (def.category() == MagicDef.Category.ALTAR) return false; // revive：祭坛专属
                if ("teleport".equals(def.id())) return false;               // 传送：导航回退专用，拒绝装备
                // 其余（含 SPECIAL 的 heal 与 normal 法术）自由放置，不校验分类匹配
            } else if (com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()
                    && com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.isScroll(stack)) {
                magicId = com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.getSpellId(stack);
                if (magicId == null || !com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.isValidSpell(magicId)) {
                    return false;
                }
                // 铁魔法卷轴可自由放置于任意 4 大门类槽位中
            } else {
                return false;
            }

            int sameCategory = 0;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (s.isEmpty()) continue;
                String existingId = null;
                if (s.getItem() instanceof SpellItem) {
                    existingId = SpellItem.getMagicId(s);
                } else if (com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()
                        && com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.isScroll(s)) {
                    existingId = com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.getSpellId(s);
                }
                if (magicId.equals(existingId)) return false;

                int row = i / SLOTS_PER_CATEGORY;
                if (row >= 0 && row < CATEGORY_COUNT && EquippedMagicComponent.CATEGORIES.get(row).equals(category)) {
                    sameCategory++;
                }
            }
            return sameCategory < EquippedMagicComponent.MAX_PER_CATEGORY;
        }
    }
}