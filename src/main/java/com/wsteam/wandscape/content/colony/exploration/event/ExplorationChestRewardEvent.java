package com.wsteam.wandscape.content.colony.exploration.event;

import com.wsteam.wandscape.content.element.data.ElementType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fired on the server after an exploration chest reward is rolled but <b>before</b> it is
 * credited to the colony and shown on the HUD, so whatever a listener leaves in
 * {@link #exp()} / {@link #elements()} is exactly what the player gets.
 *
 * <p>This is the escape hatch for what a datapack cannot express: deciding the payout from
 * runtime state, adding non-element output of your own, or suppressing the reward. Anything
 * a region JSON can already say ({@code reward.value}, {@code reward.mode}, the tuning
 * parameters) belongs in the datapack, not here.
 *
 * <pre>{@code
 * @SubscribeEvent
 * public static void onExplore(ExplorationChestRewardEvent event) {
 *     event.setExp(event.exp() * 2);
 *     event.setCanceled(true);   // no colony EXP, no elements
 * }
 * }</pre>
 *
 * <p>Not fired when the player belongs to no colony: the chest still yields its vanilla
 * loot, but nothing is credited and there is nothing to modify.
 */
public class ExplorationChestRewardEvent extends Event implements ICancellableEvent {

    private final ServerPlayer player;
    private final UUID colonyId;
    private final ResourceKey<LootTable> lootTable;
    private final BlockPos pos;
    private final String regionName;
    private final boolean degenerate;

    private int exp;
    private Map<ElementType, Long> elements;

    public ExplorationChestRewardEvent(ServerPlayer player, UUID colonyId, ResourceKey<LootTable> lootTable,
                                       BlockPos pos, String regionName, boolean degenerate,
                                       int exp, Map<ElementType, Long> elements) {
        this.player = player;
        this.colonyId = colonyId;
        this.lootTable = lootTable;
        this.pos = pos.immutable();
        this.regionName = regionName;
        this.degenerate = degenerate;
        this.exp = exp;
        setElements(elements);
    }

    /** The player who opened or broke the chest. */
    public ServerPlayer player() {
        return player;
    }

    /** Colony receiving the reward. Never null — this event is not fired without one. */
    public UUID colonyId() {
        return colonyId;
    }

    /** Loot table the container was seeded with. */
    public ResourceKey<LootTable> lootTable() {
        return lootTable;
    }

    public BlockPos pos() {
        return pos;
    }

    /** Display name of the matched region, or one derived from the loot table id. */
    public String regionName() {
        return regionName;
    }

    /**
     * True when the roll fell back to a flat loot-independent payout because no item in the
     * loot table could be priced — see {@code ExplorationExpectationCalculator}.
     */
    public boolean degenerate() {
        return degenerate;
    }

    /** Colony experience about to be granted; {@link #setExp} to change it. */
    public int exp() {
        return exp;
    }

    public void setExp(int exp) {
        this.exp = Math.max(0, exp);
    }

    /** Elements about to be deposited into the colony bank. */
    public Map<ElementType, Long> elements() {
        return elements;
    }

    /** Replace the element payout outright; null or empty pays no elements. */
    public void setElements(@Nullable Map<ElementType, Long> elements) {
        if (elements == null || elements.isEmpty()) {
            this.elements = Map.of();
            return;
        }
        Map<ElementType, Long> copy = new LinkedHashMap<>();
        for (Map.Entry<ElementType, Long> entry : elements.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        this.elements = Collections.unmodifiableMap(copy);
    }

    /** Add one element on top of the current payout, e.g. a mod's own currency. */
    public void addElement(ElementType type, long amount) {
        if (type == null || amount <= 0) return;
        Map<ElementType, Long> merged = new LinkedHashMap<>(elements);
        merged.merge(type, amount, Long::sum);
        setElements(merged);
    }
}
