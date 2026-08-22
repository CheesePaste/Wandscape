package com.wsteam.wandscape.warehouse;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Read-only slot for warehouse items (AE2-style {@code ClientReadOnlySlot}).
 *
 * <p>Rendered in the menu like a vanilla slot but completely inert to vanilla
 * click machinery: {@code mayPickup}/{@code mayPlace} are false (Q, number keys,
 * shift-click and inventory-sorting mods skip it server-side), {@code set()} is
 * a no-op (menu slot sync cannot overwrite the client-side display data), and
 * {@code getItem()} reads from a bound supplier so the client screen can show
 * the current page's entries.
 *
 * <p>On the server this slot is a pure placeholder (supplier defaults to empty)
 * — warehouse interactions go through {@link WarehouseActionPacket}, not clicks.
 */
public class WarehouseSlot extends Slot {

    private static final SimpleContainer EMPTY = new SimpleContainer(54);

    private Supplier<ItemStack> supplier = () -> ItemStack.EMPTY;
    private BooleanSupplier active = () -> true;

    public WarehouseSlot(int index, int x, int y) {
        super(EMPTY, index, x, y);
    }

    /** Bind client-side display data; the screen calls this once in {@code init()}. */
    public void bind(Supplier<ItemStack> supplier, BooleanSupplier active) {
        this.supplier = supplier;
        this.active = active;
    }

    @Override
    public ItemStack getItem() {
        return supplier.get();
    }

    @Override
    public boolean hasItem() {
        return !supplier.get().isEmpty();
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    @Override
    public boolean mayPickup(Player player) {
        return false;
    }

    @Override
    public void set(ItemStack stack) {
        // No-op: menu slot sync must not overwrite the supplier-driven display.
    }

    @Override
    public ItemStack remove(int amount) {
        return ItemStack.EMPTY;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean isActive() {
        return active.getAsBoolean();
    }
}
