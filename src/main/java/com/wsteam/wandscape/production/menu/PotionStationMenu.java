package com.wsteam.wandscape.production.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static com.wsteam.wandscape.Wandscape.POTION_STATION_MENU;

/**
 * ContainerMenu for the potion station GUI.
 * MVP stub — no interactive screen yet.
 */
public class PotionStationMenu extends AbstractContainerMenu {

    private static final int SLOT_SIZE = 18;
    private static final int HOTBAR_Y = 162;
    private static final int INV_Y = 104;
    private static final int INV_X = 8;

    private final Inventory playerInventory;

    public PotionStationMenu(int containerId, Inventory playerInv) {
        super(POTION_STATION_MENU.get(), containerId);
        this.playerInventory = playerInv;
        addPlayerSlots(playerInv);
    }

    public PotionStationMenu(int containerId, Inventory playerInv, FriendlyByteBuf extraData) {
        this(containerId, playerInv);
    }

    private void addPlayerSlots(Inventory playerInv) {
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, INV_X + col * SLOT_SIZE, HOTBAR_Y));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, 9 + row * 9 + col,
                        INV_X + col * SLOT_SIZE, INV_Y + row * SLOT_SIZE));
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return playerInventory.stillValid(player);
    }
}
