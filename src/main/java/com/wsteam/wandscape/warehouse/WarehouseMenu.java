package com.wsteam.wandscape.warehouse;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static com.wsteam.wandscape.Wandscape.WAREHOUSE_MENU;

/**
 * ContainerMenu for the warehouse GUI.
 *
 * <p>Holds only the player's hotbar and main inventory (36 slots).
 * The warehouse item grid is custom-rendered by {@code WarehouseScreen}
 * and synced via {@code WarehouseDataPacket}.
 */
public class WarehouseMenu extends AbstractContainerMenu {

    private static final int SLOT_SIZE = 18;
    private static final int HOTBAR_Y = 162;
    private static final int INV_Y = 104;
    private static final int INV_X = 8;
    private static final int PLAYER_SLOTS = 36;

    private final Container playerInventory;

    /** Server-side constructor. */
    public WarehouseMenu(int containerId, Inventory playerInv) {
        super(WAREHOUSE_MENU.get(), containerId);
        this.playerInventory = playerInv;
        addPlayerSlots(playerInv);
    }

    /** Client-side constructor (from packet). */
    public WarehouseMenu(int containerId, Inventory playerInv, FriendlyByteBuf extraData) {
        this(containerId, playerInv);
    }

    private void addPlayerSlots(Inventory playerInv) {
        // Hotbar (0-8)
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, INV_X + col * SLOT_SIZE, HOTBAR_Y));
        }
        // Main inventory (9-35)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, 9 + row * 9 + col,
                        INV_X + col * SLOT_SIZE, INV_Y + row * SLOT_SIZE));
            }
        }
    }

    // ── shift-click logic ──

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // All slots are player-inventory → no-op for shift-click
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return playerInventory.stillValid(player);
    }

    // ── MenuProvider factory ──

    /** Create a MenuProvider that bundles the item data packet. */
    public static net.minecraft.world.MenuProvider createMenuProvider(WarehouseBE be) {
        return new net.minecraft.world.MenuProvider() {
            @Override
            public net.minecraft.network.chat.Component getDisplayName() {
                return net.minecraft.network.chat.Component.literal("Colony Warehouse");
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
                var menu = new WarehouseMenu(containerId, playerInv);
                // Send data packet right after opening (server→client)
                var snapshot = be.getItemsSnapshot();
                if (!snapshot.isEmpty()) {
                    var packet = com.wsteam.wandscape.warehouse.network.WarehouseDataPacket.from(snapshot);
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                            (net.minecraft.server.level.ServerPlayer) player, packet);
                }
                return menu;
            }
        };
    }
}
