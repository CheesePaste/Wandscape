package com.wsteam.wandscape.npc;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.ui.vanilla.VanillaPlayerInventory;
import com.wsteam.wandscape.wand.item.WandItem;

import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * NPC 装备容器菜单：4 盔甲槽（原版 ArmorSlot 语义，直接写 NPC 的 vanilla 装备槽，
 * 经 {@code setItemSlot} 由原版装备结算接管属性）+ 1 法杖槽（变更同步手持/默认法杖）
 * + 原版玩家背包槽（{@link VanillaPlayerInventory}）。全部槽位都是真实 vanilla 槽——
 * 左键/右键/Shift/拖拽与快捷键全部生效，与原版背包装备槽位一致。
 *
 * <p>客户端工厂仅带玩家背包（数据经服务端槽同步填充）；服务端工厂持有 NPC 引用，
 * 槽变更（{@link #slotChanged}）即时写回实体。
 */
public class NpcMenu extends AbstractContainerMenu {

    public static final int PANEL_W = 300;
    public static final int PANEL_H = 230;
    public static final int SLOT = 18;
    public static final int ARMOR_COUNT = WandscapeNpc.ARMOR_SLOT_COUNT; // 4
    public static final int WAND_SLOT_INDEX = ARMOR_COUNT;               // 4
    public static final int EQUIP_SLOT_COUNT = ARMOR_COUNT + 1;          // 5
    /** 装备槽左列（相对面板，x 与 vanilla 背包格一致）。 */
    public static final int EQUIP_X = 8;
    public static final int EQUIP_Y = 30;
    /** 玩家背包槽区顶/快捷栏顶（装备区与按钮行下方，避开 header 与装备区）。 */
    public static final int PLAYER_INV_Y = 148;
    public static final int PLAYER_HOTBAR_Y = 206;

    @Nullable
    private final WandscapeNpc npc;
    private final Container armorContainer;
    private final SimpleContainer wandContainer = new SimpleContainer(1);

    /** Client-side factory (MenuType): no NPC context — contents arrive via sync. */
    public NpcMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null);
    }

    /** Server-side factory (MenuProvider). */
    public NpcMenu(int containerId, Inventory playerInventory, @Nullable WandscapeNpc npc) {
        super(Wandscape.NPC_MENU.get(), containerId);
        this.npc = npc;
        // 盔甲容器：服务端包装 NPC 的 4 个 vanilla 装备槽（写 setItemSlot → 原版装备结算接管属性），
        // 客户端无 NPC 引用用空容器（槽内容经同步填充）。
        this.armorContainer = npc != null ? new NpcArmorContainer(npc) : new SimpleContainer(ARMOR_COUNT);
        if (npc != null && !npc.hasDefaultWand()) {
            ItemStack held = npc.getItemInHand(InteractionHand.MAIN_HAND);
            if (!held.isEmpty()) {
                wandContainer.setItem(0, held.copy());
            }
        }
        for (int i = 0; i < ARMOR_COUNT; i++) {
            addSlot(new NpcArmorSlot(armorContainer, i, EQUIP_X, EQUIP_Y + i * SLOT,
                    WandscapeNpc.ARMOR_VANILLA_SLOTS[i], npc));
        }
        addSlot(new WandSlot(wandContainer, 0, EQUIP_X, EQUIP_Y + WAND_SLOT_INDEX * SLOT));
        VanillaPlayerInventory.addTo(this::addSlot, playerInventory, PLAYER_INV_Y, PLAYER_HOTBAR_Y);
    }

    public int getEntityId() {
        return npc != null ? npc.getId() : -1;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack result = slot.getItem().copy();
        if (index >= EQUIP_SLOT_COUNT) {
            // 玩家槽 → 装备区（法杖 → 法杖槽，其余 → 盔甲槽，moveItemStackTo 尊重 mayPlace）
            boolean toWand = slot.getItem().getItem() instanceof WandItem;
            if (!moveItemStackTo(slot.getItem(),
                    toWand ? WAND_SLOT_INDEX : 0,
                    toWand ? EQUIP_SLOT_COUNT : ARMOR_COUNT, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 装备区 → 玩家背包
            if (!moveItemStackTo(slot.getItem(), EQUIP_SLOT_COUNT, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        }
        // Shift 移动可能改动盔甲槽（铁魔法属性桥进 ECS），同步一次。
        if (npc != null) {
            npc.syncIronArmorAttributes();
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        if (npc == null) return true;
        return !npc.isRemoved() && player.canInteractWithEntity(npc, 64.0);
    }

    /** 任意槽操作（拾取/放置/Shift/拖拽）后同步装备态到 NPC 实体。 */
    @Override
    public void clicked(int slotId, int dragType, net.minecraft.world.inventory.ClickType clickType, Player player) {
        super.clicked(slotId, dragType, clickType, player);
        if (npc == null || npc.isRemoved()) return;
        ItemStack stack = wandContainer.getItem(0);
        if (stack.isEmpty()) {
            npc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Wandscape.WAND.get()));
            npc.setHasDefaultWand(true);
        } else {
            npc.setItemInHand(InteractionHand.MAIN_HAND, stack);
            npc.setHasDefaultWand(false);
        }
        npc.syncIronArmorAttributes();
        npc.syncWandAttributes();
    }

    /** 包装 NPC 的 4 个 vanilla 盔甲槽为 {@link Container}：菜单槽写 {@code setItemSlot}，
     *  原版每 tick 装备结算接管属性与附魔；铁魔法自有属性另经 {@link WandscapeNpc#syncIronArmorAttributes}。 */
    private static final class NpcArmorContainer implements Container {
        private final WandscapeNpc npc;

        NpcArmorContainer(WandscapeNpc npc) {
            this.npc = npc;
        }

        @Override
        public int getContainerSize() {
            return WandscapeNpc.ARMOR_SLOT_COUNT;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < getContainerSize(); i++) {
                if (!getItem(i).isEmpty()) return false;
            }
            return true;
        }

        @Override
        public ItemStack getItem(int index) {
            return npc.getItemBySlot(WandscapeNpc.ARMOR_VANILLA_SLOTS[index]);
        }

        @Override
        public ItemStack removeItem(int index, int count) {
            if (count < 0) throw new IllegalArgumentException("count should be >= 0");
            if (count == 0) return ItemStack.EMPTY;
            ItemStack stack = getItem(index);
            if (stack.isEmpty()) return ItemStack.EMPTY;
            if (count >= stack.getCount()) return removeItemNoUpdate(index);
            ItemStack part = stack.split(count);
            if (stack.isEmpty()) {
                npc.setItemSlot(WandscapeNpc.ARMOR_VANILLA_SLOTS[index], ItemStack.EMPTY);
            }
            return part;
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            ItemStack stack = getItem(index);
            if (stack.isEmpty()) return ItemStack.EMPTY;
            npc.setItemSlot(WandscapeNpc.ARMOR_VANILLA_SLOTS[index], ItemStack.EMPTY);
            return stack;
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            npc.setItemSlot(WandscapeNpc.ARMOR_VANILLA_SLOTS[index], stack);
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return !npc.isRemoved();
        }

        @Override
        public void clearContent() {
            for (int i = 0; i < getContainerSize(); i++) {
                npc.setItemSlot(WandscapeNpc.ARMOR_VANILLA_SLOTS[i], ItemStack.EMPTY);
            }
        }
    }

    /** 盔甲槽：只接受对应部位盔甲，空槽显示原版部位图标。 */
    public static final class NpcArmorSlot extends Slot {
        private final EquipmentSlot equipmentSlot;
        @Nullable
        private final net.minecraft.world.entity.LivingEntity owner;

        public NpcArmorSlot(Container container, int index, int x, int y,
                            EquipmentSlot equipmentSlot, @Nullable WandscapeNpc owner) {
            super(container, index, x, y);
            this.equipmentSlot = equipmentSlot;
            this.owner = owner;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (stack.isEmpty()) return false;
            // 客户端无 owner 时宽松放行（仅提示用），服务端做权威部位校验。
            return owner == null || owner.getEquipmentSlotForItem(stack) == equipmentSlot;
        }

        @Override
        @Nullable
        public com.mojang.datafixers.util.Pair<net.minecraft.resources.ResourceLocation, net.minecraft.resources.ResourceLocation> getNoItemIcon() {
            return com.mojang.datafixers.util.Pair.of(InventoryMenu.BLOCK_ATLAS, switch (equipmentSlot) {
                case HEAD -> InventoryMenu.EMPTY_ARMOR_SLOT_HELMET;
                case CHEST -> InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE;
                case LEGS -> InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS;
                case FEET -> InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS;
                default -> null;
            });
        }
    }

    /** 法杖槽：只接受法杖，单格。 */
    public static final class WandSlot extends Slot {
        public WandSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof WandItem;
        }
    }
}