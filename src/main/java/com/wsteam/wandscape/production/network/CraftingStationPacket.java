package com.wsteam.wandscape.production.network;

import java.util.*;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import com.wsteam.wandscape.production.data.CraftWandRecipe;
import com.wsteam.wandscape.production.data.RecipeUnlockRequirement;
import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet carrying craft_wand recipe data for the crafting station GUI.
 */
public record CraftingStationPacket(BlockPos stationPos, ListTag recipes) implements CustomPacketPayload {

    public static final Type<CraftingStationPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "crafting_station_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftingStationPacket> STREAM_CODEC =
            StreamCodec.of(CraftingStationPacket::write, CraftingStationPacket::read);

    private static final int MAX_PER_OPERATION = 64;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static CraftingStationPacket from(BlockPos stationPos,
                                              Collection<CraftWandRecipe> wandRecipes,
                                              Map<ElementType, Long> elementMap,
                                              @Nullable UUID colonyId) {
        ListTag list = new ListTag();
        for (CraftWandRecipe r : wandRecipes) {
            // Service-side check only — log but DO NOT filter from the packet.
            // The client renders locked vs unlocked state locally.
            // Server-side re-validation in RequestProductionTaskPacket prevents tampering.
            boolean unlocked = com.wsteam.wandscape.production.internal.RecipeUnlockChecker
                    .isUnlocked(colonyId, r.unlockRequirement());
            int maxAffordable = computeMaxAffordable(r.cost(), elementMap);
            CompoundTag tag = new CompoundTag();
            tag.putString("id", r.id());
            tag.putString("output", r.outputItem());
            if (r.outputNbt() != null && !r.outputNbt().isEmpty()) {
                tag.put("nbt", r.outputNbt());
            }
            CompoundTag costTag = new CompoundTag();
            for (var e : r.cost().entrySet()) {
                costTag.putLong(e.getKey().name().toLowerCase(), e.getValue());
            }
            tag.put("cost", costTag);

            // Determine locked reason before setting max_affordable
            String lockedReason;
            if (!unlocked) {
                lockedReason = "colony";
                maxAffordable = 0;
            } else if (maxAffordable == 0) {
                lockedReason = "elements";
            } else {
                lockedReason = "unlocked";
            }
            tag.putInt("max_affordable", maxAffordable);
            tag.putString("locked_reason", lockedReason);

            // Serialise colony unlock requirement when not yet satisfied
            if (!r.unlockRequirement().equals(RecipeUnlockRequirement.NONE)) {
                CompoundTag unlockTag = new CompoundTag();
                unlockTag.putInt("min_comfort", r.unlockRequirement().minComfort());
                unlockTag.putInt("min_magic",   r.unlockRequirement().minMagic());
                unlockTag.putInt("min_wonder",  r.unlockRequirement().minWonder());
                tag.put("unlock_requirement", unlockTag);
            }
            list.add(tag);
        }
        return new CraftingStationPacket(stationPos, list);
    }

    private static int computeMaxAffordable(Map<ElementType, Long> costPerUnit, Map<ElementType, Long> elements) {
        int max = MAX_PER_OPERATION;
        for (var entry : costPerUnit.entrySet()) {
            long available = elements.getOrDefault(entry.getKey(), 0L);
            if (entry.getValue() <= 0) continue;
            int canAfford = (int) (available / entry.getValue());
            if (canAfford < max) max = canAfford;
        }
        return max;
    }

    public List<RecipeEntry> entries() {
        List<RecipeEntry> result = new ArrayList<>();
        for (int i = 0; i < recipes.size(); i++) {
            CompoundTag tag = recipes.getCompound(i);
            String id = tag.getString("id");
            String output = tag.getString("output");
            CompoundTag nbt = tag.contains("nbt") ? tag.getCompound("nbt") : null;
            Map<ElementType, Long> cost = new LinkedHashMap<>();
            CompoundTag costTag = tag.getCompound("cost");
            for (String key : costTag.getAllKeys()) {
                cost.put(ElementType.valueOf(key.toUpperCase()), costTag.getLong(key));
            }
            int maxAffordable = tag.getInt("max_affordable");
            String lockedReason = tag.getString("locked_reason");
            // Read unlock requirement from NBT (serialised by from() on the server)
            RecipeUnlockRequirement unlockReq = RecipeUnlockRequirement.NONE;
            if (tag.contains("unlock_requirement")) {
                CompoundTag urTag = tag.getCompound("unlock_requirement");
                unlockReq = new RecipeUnlockRequirement(
                        urTag.getInt("min_comfort"),
                        urTag.getInt("min_magic"),
                        urTag.getInt("min_wonder")
                );
            }
            result.add(new RecipeEntry(id, output, nbt, cost, maxAffordable, lockedReason, unlockReq));
        }
        return result;
    }

    /**
     * @param recipeId          recipe identifier
     * @param outputItem        output item id
     * @param nbt               output item NBT (nullable)
     * @param cost              element cost per unit
     * @param maxAffordable     maximum quantity affordable with current elements (0 when locked)
     * @param lockedReason      "unlocked" / "colony" / "elements" — client uses this to pick lock hint
     * @param unlockRequirement colony evaluation thresholds (only meaningful when lockedReason=colony)
     */
    public record RecipeEntry(
            String recipeId,
            String outputItem,
            @javax.annotation.Nullable CompoundTag nbt,
            Map<ElementType, Long> cost,
            int maxAffordable,
            String lockedReason,
            RecipeUnlockRequirement unlockRequirement
    ) {}

    private static Consumer<CraftingStationPacket> clientHandler;

    public static void setClientHandler(Consumer<CraftingStationPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(CraftingStationPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    static void write(RegistryFriendlyByteBuf buf, CraftingStationPacket pkt) {
        CompoundTag wrapper = new CompoundTag();
        wrapper.putLong("pos", pkt.stationPos.asLong());
        wrapper.put("recipes", pkt.recipes);
        buf.writeNbt(wrapper);
    }

    static CraftingStationPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag wrapper = buf.readNbt();
        if (wrapper == null) return new CraftingStationPacket(BlockPos.ZERO, new ListTag());
        BlockPos pos = BlockPos.of(wrapper.getLong("pos"));
        return new CraftingStationPacket(pos, wrapper.getList("recipes", Tag.TAG_COMPOUND));
    }
}
