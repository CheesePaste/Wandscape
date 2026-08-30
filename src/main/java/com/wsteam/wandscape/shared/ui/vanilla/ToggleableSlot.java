package com.wsteam.wandscape.shared.ui.vanilla;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;

import java.util.function.BooleanSupplier;

/**
 * Vanilla slot that can be hidden by the surrounding UI through a condition
 * supplier (e.g. a player-inventory slot shown only on one page of a multi-tab
 * container screen). Keeps all vanilla slot semantics — sorting mods still
 * recognise it via its container.
 *
 * <p>Shared replacement for the warehouse's former {@code TabAwareSlot}; any
 * container menu that wants conditionally-visible vanilla slots (typically a
 * player bag built by {@link VanillaPlayerInventory}) can use it.
 */
public class ToggleableSlot extends Slot {

    private BooleanSupplier active = () -> true;

    public ToggleableSlot(Container container, int slot, int x, int y) {
        super(container, slot, x, y);
    }

    public void setActiveSupplier(BooleanSupplier active) {
        this.active = active;
    }

    @Override
    public boolean isActive() {
        return active.getAsBoolean();
    }
}