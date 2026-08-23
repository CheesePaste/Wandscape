package com.wsteam.wandscape.shared.ui.vanilla;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Reusable vanilla player-inventory section: the 3×9 main bag plus the 1×9
 * hotbar, built as {@link ToggleableSlot}s so the whole bag can be shown/hidden
 * per page. Player slots keep full vanilla semantics (shortcuts, sorting mods).
 *
 * <p>Coordinates are relative to the container panel's top-left. Chest-style
 * panels pick their bag offset from {@link #inventoryTop}/{@link #hotbarTop}
 * (same formula as vanilla {@code ChestMenu}); other panels pass explicit tops.
 *
 * <p>Client rendering is vanilla ({@code AbstractContainerScreen} renders the
 * slots); the bag background belongs to the container panel texture.
 */
public final class VanillaPlayerInventory {

    public static final int SLOT = 18;
    public static final int INVENTORY_X = 8;

    private static final ResourceLocation INVENTORY_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");

    private VanillaPlayerInventory() {
    }

    /** 画原版槽底（inventory.png 槽区采样）；x/y 为槽外框左上角，局部/绝对坐标均可。 */
    public static void blitSlotBackground(GuiGraphics g, int x, int y) {
        g.blit(INVENTORY_TEXTURE, x + 1, y + 1, 9, 85, 16, 16);
    }

    /** Adds the player's 36 slots as toggleable slots; returns them for later binding. */
    public static List<ToggleableSlot> addTo(Consumer<Slot> slotAdder, Inventory playerInventory,
                                             int playerInvTop, int hotbarTop) {
        List<ToggleableSlot> created = new ArrayList<>(36);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                ToggleableSlot slot = new ToggleableSlot(playerInventory, col + row * 9 + 9,
                        INVENTORY_X + col * SLOT, playerInvTop + row * SLOT);
                slotAdder.accept(slot);
                created.add(slot);
            }
        }
        for (int col = 0; col < 9; col++) {
            ToggleableSlot slot = new ToggleableSlot(playerInventory, col,
                    INVENTORY_X + col * SLOT, hotbarTop);
            slotAdder.accept(slot);
            created.add(slot);
        }
        return created;
    }

    /** Chest-style panel: top Y of the 3×9 main bag (vanilla ChestMenu formula). */
    public static int inventoryTop(int containerRows) {
        return 103 + (containerRows - 4) * 18;
    }

    /** Chest-style panel: top Y of the 1×9 hotbar (vanilla ChestMenu formula). */
    public static int hotbarTop(int containerRows) {
        return 161 + (containerRows - 4) * 18;
    }

    /** Toggles visibility of a previously built bag section. */
    public static void bind(List<ToggleableSlot> bag, BooleanSupplier active) {
        for (ToggleableSlot slot : bag) {
            slot.setActiveSupplier(active);
        }
    }
}