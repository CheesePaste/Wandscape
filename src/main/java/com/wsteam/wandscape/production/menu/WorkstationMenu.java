package com.wsteam.wandscape.production.menu;

import java.util.Collection;
import java.util.Map;

import com.wsteam.wandscape.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.shared.data.ItemKey;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static com.wsteam.wandscape.Wandscape.WORKSTATION_MENU;

/**
 * ContainerMenu for the workstation GUI (decompose + synthesize).
 */
public class WorkstationMenu extends AbstractContainerMenu {

    private static final int SLOT_SIZE = 18;
    private static final int HOTBAR_Y = 162;
    private static final int INV_Y = 104;
    private static final int INV_X = 8;

    private final Inventory playerInventory;

    /** Server-side constructor. */
    public WorkstationMenu(int containerId, Inventory playerInv) {
        super(WORKSTATION_MENU.get(), containerId);
        this.playerInventory = playerInv;
        addPlayerSlots(playerInv);
    }

    /** Client-side constructor (from packet). */
    public WorkstationMenu(int containerId, Inventory playerInv, FriendlyByteBuf extraData) {
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
            Map<ItemKey, Long> decomposableItems,
            Collection<SynthesizeRecipe> synthRecipes) {
        return new net.minecraft.world.MenuProvider() {
            @Override
            public net.minecraft.network.chat.Component getDisplayName() {
                return net.minecraft.network.chat.Component.literal("Workstation");
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
                var menu = new WorkstationMenu(containerId, playerInv);
                var sp = (net.minecraft.server.level.ServerPlayer) player;
                var pkt = WorkstationDataPacket.from(stationPos, decomposableItems, synthRecipes);
                sp.getServer().execute(() ->
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, pkt));
                return menu;
            }
        };
    }
}
