package com.wsteam.wandscape.warehouse;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.shared.api.WarehouseApi;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Real container menu for the colony warehouse.
 *
 * <p>Layout (shared with {@link WarehouseScreen}, indices must match on both
 * sides): 54 read-only warehouse slots (0-53) + 36 vanilla player slots (54-89).
 * Player slots behave exactly like vanilla — all shortcuts and inventory-sorting
 * mods work on them. Warehouse slots are {@link WarehouseSlot} placeholders that
 * are inert to vanilla click machinery; all warehouse interactions arrive via
 * {@code WarehouseActionPacket} and mutate through the bank.
 */
public class WarehouseMenu extends AbstractContainerMenu {

    private static final String TAG = "WarehouseMenu";

    // ── Layout (kept here so client and server menus use identical coordinates) ──
    public static final int PANEL_W = 380;
    public static final int PANEL_H = 300;
    public static final int HEADER_H = 22;
    public static final int TAB_H = 18;
    public static final int ELEMENT_PANEL_W = 130;
    public static final int SLOT = 18;
    public static final int GRID_COLS = 9;
    public static final int GRID_ROWS = 6;
    public static final int WAREHOUSE_SLOT_COUNT = GRID_COLS * GRID_ROWS; // 54
    public static final int PLAYER_SLOT_COUNT = 36;
    public static final int GRID_X = 8 + ELEMENT_PANEL_W + 10; // 148
    public static final int CONTENT_Y = HEADER_H + 2 + TAB_H + 6; // 48
    public static final int SEARCH_H = 20;
    public static final int PLAYER_INV_Y = CONTENT_Y + GRID_ROWS * SLOT + 6 + SEARCH_H + 4;

    private static final int MAX_CURSOR_COUNT = Integer.MAX_VALUE;

    @Nullable
    private final UUID colonyId;
    @Nullable
    private final BlockPos buildingPos;

    /** Client-side factory (MenuType): no colony context yet — data arrives via packet. */
    public WarehouseMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null, null);
    }

    /** Server-side factory (MenuProvider). */
    public WarehouseMenu(int containerId, Inventory playerInventory,
                         @Nullable UUID colonyId, @Nullable BlockPos buildingPos) {
        super(Wandscape.WAREHOUSE_MENU.get(), containerId);
        this.colonyId = colonyId;
        this.buildingPos = buildingPos;

        for (int i = 0; i < WAREHOUSE_SLOT_COUNT; i++) {
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            addSlot(new WarehouseSlot(i, GRID_X + col * SLOT, CONTENT_Y + row * SLOT));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new TabAwareSlot(playerInventory, col + row * 9 + 9,
                        GRID_X + col * SLOT, PLAYER_INV_Y + row * SLOT));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new TabAwareSlot(playerInventory, col,
                    GRID_X + col * SLOT, PLAYER_INV_Y + 3 * SLOT));
        }
    }

    /** Client: point the read-only warehouse slots at the screen's display data. */
    public void bindSlots(IntFunction<ItemStack> entryFor, BooleanSupplier active) {
        for (Slot slot : this.slots) {
            if (slot instanceof WarehouseSlot ws) {
                final int index = ws.index;
                ws.bind(() -> entryFor.apply(index), active);
            } else if (slot instanceof TabAwareSlot ts) {
                ts.setActive(active);
            }
        }
    }

    /**
     * Vanilla player slot that can be hidden by the UI (tab switching). Keeps all
     * vanilla slot semantics — sorting mods still recognise it via its container.
     */
    public static final class TabAwareSlot extends Slot {
        private BooleanSupplier active = () -> true;

        public TabAwareSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        public void setActive(BooleanSupplier active) {
            this.active = active;
        }

        @Override
        public boolean isActive() {
            return active.getAsBoolean();
        }
    }

    // ── Vanilla quick-move (shift-click): player slot → deposit into the bank ──

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        // Warehouse slots are inert here — sorting mods cannot move bank items.
        if (index < WAREHOUSE_SLOT_COUNT || colonyId == null) return ItemStack.EMPTY;
        if (!(player instanceof ServerPlayer sp)) return ItemStack.EMPTY;

        var api = WandscapeApis.getWarehouseApiSilently();
        if (api == null) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack result = stack.copy();
        api.insertItems(colonyId, List.of(stack));
        stack.setCount(0);
        slot.setChanged();
        recordDeposit(sp);
        playSound(sp);
        sendRefresh(sp);
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return buildingPos == null || player.canInteractWithBlock(buildingPos, 64.0);
    }

    // ── Server-side actions (dispatched from WarehouseActionPacket) ──

    /** Take the whole entry into the cursor (count may exceed 64, AE2/RS style). */
    public void cursorTakeAll(ItemKey key, ServerPlayer player) {
        takeToCursor(key, player, Long.MAX_VALUE);
    }

    /** Take half (rounded up) of the entry into the cursor. */
    public void cursorTakeHalf(ItemKey key, ServerPlayer player) {
        long available = available(player, key);
        if (available <= 0) return;
        takeToCursor(key, player, (available + 1) / 2);
    }

    private void takeToCursor(ItemKey key, ServerPlayer player, long maxTake) {
        if (colonyId == null) return;
        long available = available(player, key);
        if (available <= 0) return;
        int take = (int) Math.min(Math.min(available, maxTake), MAX_CURSOR_COUNT);
        if (take <= 0) return;

        ItemStack stack = WarehouseManager.toItemStack(key, take);
        if (stack.isEmpty()) return;

        var bank = ColonyItemBank.get(player.serverLevel());
        if (bank == null || !bank.consume(colonyId, key, take)) return;
        setCarried(stack);
        Log.info(TAG, "[Warehouse] {}x {} to cursor (colony={})", take, key.itemId(),
                colonyId.toString().substring(0, 8));
        playSound(player);
    }

    /** Shift-click on a warehouse slot: take the whole entry into the player inventory (64-chunks). */
    public void takeToInventory(ItemKey key, ServerPlayer player) {
        if (colonyId == null) return;
        var api = WandscapeApis.getWarehouseApiSilently();
        if (api == null) return;

        long remaining = Long.MAX_VALUE;
        long totalTaken = 0;
        while (remaining > 0) {
            long take = Math.min(remaining, 64);
            if (!api.extractItem(colonyId, key, take, player.getInventory())) break;
            remaining -= take;
            totalTaken += take;
        }
        if (totalTaken > 0) {
            Log.info(TAG, "[Warehouse] {}x {} to inventory (colony={})", totalTaken, key.itemId(),
                    colonyId.toString().substring(0, 8));
            playSound(player);
        }
    }

    /** Click a warehouse slot with a carried stack: deposit the whole cursor. */
    public void cursorDepositAll(ServerPlayer player) {
        if (getCarried().isEmpty()) return;
        depositCursor(player, getCarried());
        setCarried(ItemStack.EMPTY);
    }

    /** Right-click a warehouse slot with a carried stack: deposit one item. */
    public void cursorDepositOne(ServerPlayer player) {
        ItemStack carried = getCarried();
        if (carried.isEmpty()) return;
        // split() shrinks the carried stack in place; the remainder stays on the cursor.
        depositCursor(player, carried.split(1));
    }

    private void depositCursor(ServerPlayer player, ItemStack toDeposit) {
        if (colonyId == null || toDeposit.isEmpty()) return;
        var api = WandscapeApis.getWarehouseApiSilently();
        if (api == null) return;

        var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(toDeposit.getItem());
        Log.info(TAG, "[Warehouse] {}x {} deposited from cursor (colony={})",
                toDeposit.getCount(), rl != null ? rl : "?", colonyId.toString().substring(0, 8));
        api.insertItems(colonyId, List.of(toDeposit));
        recordDeposit(player);
        playSound(player);
    }

    /** Push a fresh data packet so the client refreshes elements and the item list. */
    public void sendRefresh(ServerPlayer player) {
        if (colonyId == null) return;
        var bank = ColonyItemBank.get(player.serverLevel());
        if (bank == null) return;
        Map<ItemKey, Long> itemSnapshot = bank.getSnapshot(colonyId);
        Map<ElementType, Long> elemSnapshot = bank.getElementSnapshot(colonyId);
        PacketDistributor.sendToPlayer(player,
                WarehouseDataPacket.from(buildingPos, colonyId, itemSnapshot, elemSnapshot));
    }

    // ── Helpers ──

    private long available(ServerPlayer player, ItemKey key) {
        if (colonyId == null) return 0;
        var bank = ColonyItemBank.get(player.serverLevel());
        return bank != null ? bank.available(colonyId, key) : 0;
    }

    private void recordDeposit(ServerPlayer player) {
        if (colonyId == null) return;
        var bank = ColonyItemBank.get(player.serverLevel());
        if (bank != null) bank.recordPlayerDeposit(colonyId);
        var guideApi = WandscapeApis.getGuideProgressApiSilently();
        if (guideApi != null) guideApi.sendToPlayer(player, colonyId);
    }

    private void playSound(ServerPlayer player) {
        if (buildingPos == null) return;
        SoundService.playAt(player.serverLevel(), buildingPos,
                WandscapeSounds.WAREHOUSE, SoundSource.BLOCKS, 0.5f, 1.0f);
    }
}
