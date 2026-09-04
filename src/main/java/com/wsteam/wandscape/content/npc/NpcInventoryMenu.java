package com.wsteam.wandscape.content.npc;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.ui.vanilla.VanillaPlayerInventory;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 法师背包容器菜单：法师 27 格背包（3×9 网格）+ 原版玩家背包槽（3×9 背包 + 9 快捷栏）。
 * 全部槽位为真实 vanilla 槽——拾取、放置、Shift 快速转移全部生效。
 * 服务端直接绑定 NPC 的 {@link WandscapeNpc#inventory}。
 */
public class NpcInventoryMenu extends AbstractContainerMenu {

    public static final int PANEL_W = 176;
    public static final int PANEL_H = 180;
    public static final int SLOT = 18;
    public static final int COLUMNS = 9;
    public static final int NPC_INV_ROWS = 3;
    public static final int NPC_SLOT_COUNT = COLUMNS * NPC_INV_ROWS; // 27

    public static final int INV_X = 8;
    public static final int INV_Y = 32;
    public static final int PLAYER_INV_Y = 96;
    public static final int PLAYER_HOTBAR_Y = 154;

    @Nullable
    private final WandscapeNpc npc;
    private final int entityId;
    private final Container inventory;

    /** Client-side factory (IMenuTypeExtension): entityId arrives via extraData. */
    public NpcInventoryMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, playerInventory, null, extraData.readInt());
    }

    /** Server-side factory (MenuProvider). */
    public NpcInventoryMenu(int containerId, Inventory playerInventory, @Nullable WandscapeNpc npc) {
        this(containerId, playerInventory, npc, npc != null ? npc.getId() : -1);
    }

    private NpcInventoryMenu(int containerId, Inventory playerInventory,
                             @Nullable WandscapeNpc npc, int entityId) {
        super(Wandscape.NPC_INVENTORY_MENU.get(), containerId);
        this.npc = npc;
        this.entityId = entityId;
        this.inventory = npc != null ? npc.inventory : new SimpleContainer(NPC_SLOT_COUNT);

        // 27 NPC Inventory slots (3 rows x 9 columns)
        for (int row = 0; row < NPC_INV_ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int index = col + row * COLUMNS;
                addSlot(new Slot(this.inventory, index, INV_X + col * SLOT, INV_Y + row * SLOT));
            }
        }

        // 36 Player Inventory slots (3x9 inventory + 1x9 hotbar)
        VanillaPlayerInventory.addTo(this::addSlot, playerInventory, PLAYER_INV_Y, PLAYER_HOTBAR_Y);
    }

    public int getEntityId() {
        return entityId;
    }

    public int getNpcSlotCount() {
        return NPC_SLOT_COUNT;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack result = slot.getItem().copy();
        if (index < NPC_SLOT_COUNT) {
            // NPC 背包 → 玩家背包
            if (!moveItemStackTo(slot.getItem(), NPC_SLOT_COUNT, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → NPC 背包
            if (!moveItemStackTo(slot.getItem(), 0, NPC_SLOT_COUNT, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (slot.getItem().isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        if (npc == null) return true;
        // 与 NpcMenu / NpcCuriosMenu 一致：只看法师存活，不限距离
        return !npc.isRemoved() && npc.isAlive();
    }
}
