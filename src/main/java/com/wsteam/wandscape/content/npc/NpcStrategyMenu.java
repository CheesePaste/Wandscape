package com.wsteam.wandscape.content.npc;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.npc.component.EquippedMagicComponent;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.content.items.magic.SpellItem;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.vanilla.VanillaPlayerInventory;
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

    /**
     * 第三方魔法策略栏门控上限（服务端打开时按 NPC 算出；-1 = 客户端未知，NpcDataPacket 后补设）。
     * 铁魔法卷轴：Curios「法术书」槽容量，0 = 无书禁用；诡厄聚晶：主手持诡厄法杖为 1，否则 0。
     */
    private int ironScrollCap = -1;
    private int goetyFocusCap = -1;

    /** Client-side factory (MenuType): contents arrive via sync. */
    public NpcStrategyMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null);
    }

    /** Server-side factory (MenuProvider). */
    public NpcStrategyMenu(int containerId, Inventory playerInventory, @Nullable WandscapeNpc npc) {
        super(Wandscape.NPC_STRATEGY_MENU.get(), containerId);
        this.npc = npc;
        this.ironScrollCap = capForNpc(npc, KIND_IRON);
        this.goetyFocusCap = capForNpc(npc, KIND_GOETY);
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
                addSlot(new SpellSlot(this, spellSlots, cat * SLOTS_PER_CATEGORY + s,
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
        } else if (com.wsteam.wandscape.compat.goety.GoetyCompat.isLoaded()
                && com.wsteam.wandscape.compat.goety.GoetyHelper.isValidSpell(entry.id())) {
            return com.wsteam.wandscape.compat.goety.GoetyHelper.deserializeFocus(entry.id(), entry.customData());
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
            // 玩家卷轴/聚晶 → 策略槽（mayPlace 校验分类/去重/上限）
            if (!moveItemStackTo(slot.getItem(), 0, SPELL_SLOT_COUNT, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 策略槽 → 玩家背包（拿回卷轴/聚晶）
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
        // 服务端权威防绕过：手持第三方卷轴/聚晶直接点已占策略槽（换走非同类条目 → 同类占用净增）且
        // 突破门控时整吞本次点击——物品留在光标不入槽。空槽放入/Shift 由 mayPlace 拦（见 SpellSlot）。
        if (clickType == net.minecraft.world.inventory.ClickType.PICKUP
                && slotId >= 0 && slotId < SPELL_SLOT_COUNT
                && !spellSlots.getItem(slotId).isEmpty()
                && gateReasonForCarried(slotId) != null) {
            return;
        }
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

    // ── 第三方魔法门控：装进策略栏需装备门（2026-09-03，见 ADR）──
    // 铁魔法卷轴：需 Curios「法术书」槽，容量 = 法术书槽位（ironScrollCap）。
    // 诡厄聚晶：需主手（法杖栏）持诡厄法杖，全栏上限 1（goetyFocusCap）。
    // 原生魔法卷轴不受门控；cap==-1（未知）放行，由服务端权威兜底。

    private static final int KIND_IRON = 1;
    private static final int KIND_GOETY = 2;

    /** 按 NPC 与种类算门控上限；npc==null（客户端构造）为 -1，收到 NpcDataPacket 后由屏幕补设。 */
    private static int capForNpc(@Nullable WandscapeNpc npc, int kind) {
        if (npc == null) return -1;
        if (kind == KIND_IRON) {
            if (!com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()) return 0; // 未装 → 无此类条目
            return com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.equippedSpellbookSlots(npc);
        }
        if (kind == KIND_GOETY) {
            if (!com.wsteam.wandscape.compat.goety.GoetyCompat.isLoaded()) return 0;
            return com.wsteam.wandscape.compat.goety.GoetyCompat.isHoldingGoetyWand(npc) ? 1 : 0;
        }
        return 0;
    }

    /** 客户端收到 NpcDataPacket 后补设上限（服务端已由构造算好）。 */
    public void setIronScrollCap(int cap) {
        this.ironScrollCap = cap;
    }

    public void setGoetyFocusCap(int cap) {
        this.goetyFocusCap = cap;
    }

    private int capForKind(int kind) {
        return kind == KIND_GOETY ? goetyFocusCap : ironScrollCap;
    }

    /** 条目归属：{@link #KIND_IRON} 铁魔法卷轴 / {@link #KIND_GOETY} 诡厄聚晶 / 0 原生或未知。 */
    public static int kindOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (com.wsteam.wandscape.compat.ironspellbooks.IronSpellsCompat.isLoaded()
                && com.wsteam.wandscape.compat.ironspellbooks.IronSpellsHelper.isScroll(stack)) return KIND_IRON;
        if (com.wsteam.wandscape.compat.goety.GoetyCompat.isLoaded()
                && com.wsteam.wandscape.compat.goety.GoetyHelper.isFocus(stack)) return KIND_GOETY;
        return 0;
    }

    /** 该种类当前已占策略栏槽位数（跨 4 类合计）。 */
    public int countKind(int kind) {
        int n = 0;
        for (int i = 0; i < SPELL_SLOT_COUNT; i++) {
            if (kindOf(spellSlots.getItem(i)) == kind) n++;
        }
        return n;
    }

    /** 策略栏内当前铁魔法卷轴条目数（{@link #KIND_IRON}）。 */
    public int countIronScrolls() {
        return countKind(KIND_IRON);
    }

    /** 策略栏内当前诡厄聚晶条目数（{@link #KIND_GOETY}）。 */
    public int countGoetyFocuses() {
        return countKind(KIND_GOETY);
    }

    /**
     * mayPlace 用：第三方条目放入（空）策略槽是否被门控拦截（调用方保证目标槽为空）。
     * cap==-1 放行；cap==0 禁入；已达上限禁入。
     */
    public boolean gateBlocksEmptyInsert(ItemStack stack) {
        int kind = kindOf(stack);
        if (kind == 0) return false;
        int cap = capForKind(kind);
        if (cap < 0) return false;
        if (cap <= 0) return true;
        return countKind(kind) >= cap;
    }

    /**
     * 手持第三方条目对某策略槽「放入/换入」的门控原因：null=放行，否则 reason key
     * （iron.needs_spellbook / iron.cap_full / goety.needs_wand / goety.one_only）。
     * 换走槽内同种类条目时占用不变。读本菜单 spellSlots 与 carried，服务端与客户端同口径：
     * 服务端用于 {@link #clicked} 整吞防绕过，客户端用于策略屏红字提示。
     */
    @Nullable
    public String gateReasonForCarried(int targetSlot) {
        int kind = kindOf(this.getCarried());
        if (kind == 0) return null;
        int cap = capForKind(kind);
        if (cap < 0) return null;
        if (cap <= 0) return kind == KIND_GOETY ? "goety.needs_wand" : "iron.needs_spellbook";
        int used = countKind(kind);
        ItemStack target = targetSlot >= 0 && targetSlot < spellSlots.getContainerSize()
                ? spellSlots.getItem(targetSlot) : ItemStack.EMPTY;
        if (kindOf(target) == kind) used--;
        if (used + 1 > cap) return kind == KIND_GOETY ? "goety.one_only" : "iron.cap_full";
        return null;
    }

    /** Shift 快放（玩家背包第三方条目 → 策略栏）的门控原因：null=放行。 */
    @Nullable
    public String gateReasonForQuickAdd(ItemStack source) {
        int kind = kindOf(source);
        if (kind == 0) return null;
        int cap = capForKind(kind);
        if (cap < 0) return null;
        if (cap <= 0) return kind == KIND_GOETY ? "goety.needs_wand" : "iron.needs_spellbook";
        return countKind(kind) + 1 > cap
                ? (kind == KIND_GOETY ? "goety.one_only" : "iron.cap_full") : null;
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
                } else if (com.wsteam.wandscape.compat.goety.GoetyCompat.isLoaded()
                        && com.wsteam.wandscape.compat.goety.GoetyHelper.isFocus(item)) {
                    String focusId = com.wsteam.wandscape.compat.goety.GoetyHelper.getFocusId(item);
                    String customData = com.wsteam.wandscape.compat.goety.GoetyHelper.serializeFocus(item);
                    if (focusId != null && com.wsteam.wandscape.compat.goety.GoetyHelper.isValidSpell(focusId)) {
                        newEquipped.equip(categoryName, new EquippedMagicComponent.SpellEntry(focusId, 1, customData));
                    }
                }
            }
        }
        // 服务端权威：经 MagicApi.setEquippedAndStrategy 走装桶/≤3/去重/ALTAR-SPECIAL 校验
        // （flattenedQualified 保留槽行类别前缀→往返不挪位），预设不变。取代直写字段，
        // 保证 GUI 编辑与 API 校验一致。
        com.wsteam.wandscape.api.WandscapeApis.getMagicApi()
                .setEquippedAndStrategy(npc.getUUID(), npc.castStrategy.preset().name(),
                        newEquipped.flattenedQualified());
        Log.info(TAG, "[Strategy] {} equipped {} spells (npc={})",
                npc.getUUID().toString().substring(0, 8), newEquipped.flattened().size());
    }

    /** 卷轴槽：接受原生魔法卷轴、铁魔法卷轴与 Goety 聚晶，且去重、每类 ≤ 上限。 */
    public static final class SpellSlot extends Slot {
        private final String category;
        private final NpcStrategyMenu owner;

        public SpellSlot(NpcStrategyMenu owner, Container container, int index, int x, int y, String category) {
            super(container, index, x, y);
            this.owner = owner;
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
                // 铁魔法卷轴门控：需 NPC 佩戴可用法术书且未超其槽位容量（原生卷轴/诡厄聚晶不受限）。
                if (owner.gateBlocksEmptyInsert(stack)) return false;
                // 铁魔法卷轴可自由放置于任意 4 大门类槽位中
            } else if (com.wsteam.wandscape.compat.goety.GoetyCompat.isLoaded()
                    && com.wsteam.wandscape.compat.goety.GoetyHelper.isFocus(stack)) {
                magicId = com.wsteam.wandscape.compat.goety.GoetyHelper.getFocusId(stack);
                if (magicId == null || !com.wsteam.wandscape.compat.goety.GoetyHelper.isValidSpell(magicId)) {
                    return false;
                }
                // 诡厄聚晶门控：主手（法杖栏）持诡厄法杖才可放入，且全栏只装 1 个。
                if (owner.gateBlocksEmptyInsert(stack)) return false;
                // 诡厄巫法聚晶可自由放置于任意 4 大门类槽位中
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
                } else if (com.wsteam.wandscape.compat.goety.GoetyCompat.isLoaded()
                        && com.wsteam.wandscape.compat.goety.GoetyHelper.isFocus(s)) {
                    existingId = com.wsteam.wandscape.compat.goety.GoetyHelper.getFocusId(s);
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