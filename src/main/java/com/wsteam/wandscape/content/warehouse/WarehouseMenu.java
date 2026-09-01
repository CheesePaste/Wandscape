package com.wsteam.wandscape.content.warehouse;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.foundation.sound.SoundService;
import com.wsteam.wandscape.foundation.registry.WandscapeSounds;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.ui.vanilla.ToggleableSlot;
import com.wsteam.wandscape.foundation.ui.vanilla.VanillaPlayerInventory;
import com.wsteam.wandscape.content.warehouse.network.WarehouseDataPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/**
 * Real container menu for the colony warehouse.
 *
 * <p>Layout matches a vanilla 6-row chest (the {@code generic_54} texture, shared
 * with {@link WarehouseScreen}, indices must match on both sides): 54 read-only
 * warehouse slots (0-53) at the chest grid + 36 vanilla player slots (54-89)
 * below. Player slots behave exactly like vanilla — all shortcuts and
 * inventory-sorting mods work on them. Warehouse slots are {@link WarehouseSlot}
 * placeholders that are inert to vanilla click machinery; all warehouse
 * interactions arrive via {@code WarehouseActionPacket} and mutate through the
 * bank.
 */
public class WarehouseMenu extends AbstractContainerMenu {

    private static final String TAG = "WarehouseMenu";

    // ── Layout (kept here so client and server menus use identical coordinates) ──
    public static final int PANEL_W = 300;   // 与市政厅面板统一
    public static final int PANEL_H = 230;
    public static final int SLOT = 18;
    public static final int GRID_COLS = 9;
    public static final int GRID_ROWS = 6;
    public static final int WAREHOUSE_SLOT_COUNT = GRID_COLS * GRID_ROWS; // 54
    public static final int PLAYER_SLOT_COUNT = 36;
    public static final int GRID_X = 8;
    public static final int GRID_Y = 18;
    // 玩家背包 3×9+快捷栏 由共享组件 VanillaPlayerInventory 构建（坐标相对本面板左上，
    // 落在 Exchange 页 blit 的原版 6 行箱纹理内：inventoryTop(6)=139 / hotbarTop(6)=197）

    private static final int MAX_CURSOR_COUNT = Integer.MAX_VALUE;

    @Nullable
    private final UUID colonyId;
    @Nullable
    private final BlockPos buildingPos;
    /** 玩家背包 36 槽（可显隐），由共享组件构建。 */
    private final List<ToggleableSlot> playerSlots;

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
            addSlot(new WarehouseSlot(i, GRID_X + col * SLOT, GRID_Y + row * SLOT));
        }
        this.playerSlots = VanillaPlayerInventory.addTo(this::addSlot, playerInventory,
                VanillaPlayerInventory.inventoryTop(GRID_ROWS),
                VanillaPlayerInventory.hotbarTop(GRID_ROWS));
    }

    /** Client: point the read-only warehouse slots at the screen's display data. */
    public void bindSlots(IntFunction<ItemStack> entryFor, BooleanSupplier active) {
        for (Slot slot : this.slots) {
            if (slot instanceof WarehouseSlot ws) {
                final int index = ws.index;
                ws.bind(() -> entryFor.apply(index), active);
            }
        }
        VanillaPlayerInventory.bind(playerSlots, active);
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
        Log.debug(LogCategory.WAREHOUSE, "menu", "{}x {} to cursor (colony={})", take, key.itemId(),
                colonyId.toString().substring(0, 8));
        playSound(player);
    }

    /** Shift-click on a warehouse slot: take the whole entry into the player inventory (64-chunks). */
    public void takeToInventory(ItemKey key, ServerPlayer player) {
        if (colonyId == null) return;
        var api = WandscapeApis.getWarehouseApiSilently();
        if (api == null) return;

        long taken = api.extractItem(colonyId, key, Long.MAX_VALUE, player.getInventory());
        if (taken > 0) {
            Log.debug(LogCategory.WAREHOUSE, "menu", "{}x {} to inventory (colony={})", taken, key.itemId(),
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

    /** 玩家背包中同类型物品全部存入仓库（RS 滚轮: INVENTORY_TO_GRID）。 */
    public void depositInventoryType(ItemKey key, ServerPlayer player) {
        if (colonyId == null) return;
        var api = WandscapeApis.getWarehouseApiSilently();
        if (api == null) return;
        Inventory inv = player.getInventory();
        List<ItemStack> found = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && matches(s, key)) {
                found.add(s.copy());
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (found.isEmpty()) return;
        api.insertItems(colonyId, found);
        recordDeposit(player);
        playSound(player);
    }

    /** 指定玩家槽全部存入仓库（RS 滚轮在玩家槽上移）。 */
    public void depositSlot(int slotIndex, ServerPlayer player) {
        if (colonyId == null) return;
        var api = WandscapeApis.getWarehouseApiSilently();
        if (api == null) return;
        Inventory inv = player.getInventory();
        if (slotIndex < 0 || slotIndex >= inv.getContainerSize()) return;
        ItemStack stack = inv.getItem(slotIndex);
        if (stack.isEmpty()) return;
        api.insertItems(colonyId, List.of(stack.copy()));
        inv.setItem(slotIndex, ItemStack.EMPTY);
        recordDeposit(player);
        playSound(player);
    }

    /** 仓库条目取到指定玩家槽（尽量填满 64；目标槽为空或同类型才可）。 */
    public void takeToSlot(ItemKey key, ServerPlayer player, int slotIndex) {
        if (colonyId == null) return;
        Inventory inv = player.getInventory();
        if (slotIndex < 0 || slotIndex >= inv.getContainerSize()) return;
        var bank = ColonyItemBank.get(player.serverLevel());
        if (bank == null) return;
        ItemStack sample = WarehouseManager.toItemStack(key, 1);
        if (sample.isEmpty()) return;
        ItemStack target = inv.getItem(slotIndex);
        if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, sample)) return;
        long take = Math.min(bank.available(colonyId, key), 64 - target.getCount());
        if (take <= 0) return;
        if (!bank.consume(colonyId, key, take)) return;
        if (target.isEmpty()) {
            inv.setItem(slotIndex, WarehouseManager.toItemStack(key, (int) take));
        } else {
            target.grow((int) take);
            inv.setItem(slotIndex, target);
        }
        playSound(player);
    }

    private static boolean matches(ItemStack stack, ItemKey key) {
        var rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (rl == null || !rl.toString().equals(key.itemId())) return false;
        CompoundTag nbt = stack.has(DataComponents.CUSTOM_DATA)
                ? stack.get(DataComponents.CUSTOM_DATA).copyTag() : null;
        return Objects.equals(nbt, key.nbt());
    }

    private void depositCursor(ServerPlayer player, ItemStack toDeposit) {
        if (colonyId == null || toDeposit.isEmpty()) return;
        var api = WandscapeApis.getWarehouseApiSilently();
        if (api == null) return;

        var rl = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(toDeposit.getItem());
        Log.debug(LogCategory.WAREHOUSE, "menu", "{}x {} deposited from cursor (colony={})",
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
        var tutorialApi = WandscapeApis.getTutorialApiSilently();
        if (tutorialApi != null) tutorialApi.sendToPlayer(player, colonyId);
    }

    private void playSound(ServerPlayer player) {
        if (buildingPos == null) return;
        SoundService.playAt(player.serverLevel(), buildingPos,
                WandscapeSounds.WAREHOUSE, SoundSource.BLOCKS, 0.5f, 1.0f);
    }
}
