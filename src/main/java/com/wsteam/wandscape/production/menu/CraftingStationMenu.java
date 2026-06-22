package com.wsteam.wandscape.production.menu;

import java.util.Collection;

import com.wsteam.wandscape.production.data.CraftWandRecipe;
import com.wsteam.wandscape.production.network.CraftingStationPacket;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static com.wsteam.wandscape.Wandscape.CRAFTING_STATION_MENU;

/**
 * ContainerMenu for the crafting station GUI.
 */
public class CraftingStationMenu extends AbstractContainerMenu {

    private static final int SLOT_SIZE = 18;
    private static final int HOTBAR_Y = 162;
    private static final int INV_Y = 104;
    private static final int INV_X = 8;

    private final Inventory playerInventory;

    public CraftingStationMenu(int containerId, Inventory playerInv) {
        super(CRAFTING_STATION_MENU.get(), containerId);
        this.playerInventory = playerInv;
        addPlayerSlots(playerInv);
    }

    public CraftingStationMenu(int containerId, Inventory playerInv, FriendlyByteBuf extraData) {
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

    public static net.minecraft.world.MenuProvider createMenuProvider(
            net.minecraft.core.BlockPos stationPos,
            Collection<CraftWandRecipe> recipes) {
        return new net.minecraft.world.MenuProvider() {
            @Override
            public net.minecraft.network.chat.Component getDisplayName() {
                return net.minecraft.network.chat.Component.literal("Crafting Station");
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
                var menu = new CraftingStationMenu(containerId, playerInv);
                var sp = (net.minecraft.server.level.ServerPlayer) player;
                var pkt = CraftingStationPacket.from(stationPos, recipes);
                sp.getServer().execute(() ->
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, pkt));
                return menu;
            }
        };
    }
}
