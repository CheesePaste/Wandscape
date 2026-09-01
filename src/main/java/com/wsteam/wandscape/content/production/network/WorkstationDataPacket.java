package com.wsteam.wandscape.content.production.network;
import com.wsteam.wandscape.foundation.log.Log;

import com.wsteam.wandscape.content.production.data.RecipeUnlockRequirement;
import com.wsteam.wandscape.content.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.content.production.internal.ProductionAffordability;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.util.ItemKey;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet carrying workstation GUI data:
 * decomposable items in warehouse + available synthesize recipes.
 */
public record WorkstationDataPacket(BlockPos stationPos, ListTag items, ListTag recipes, String creator)
        implements CustomPacketPayload {

    public static final Type<WorkstationDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "workstation_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WorkstationDataPacket> STREAM_CODEC =
            StreamCodec.of(WorkstationDataPacket::write, WorkstationDataPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static WorkstationDataPacket from(
            BlockPos stationPos,
            Map<ItemKey, Long> decomposableItems,
            Collection<SynthesizeRecipe> synthRecipes,
            Map<ElementType, Long> elementMap,
            @Nullable UUID colonyId,
            Map<String, Map<ElementType, Long>> itemElementValues,
            String creator) {
        ListTag itemList = new ListTag();
        for (var entry : decomposableItems.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("key", entry.getKey().itemId());
            if (entry.getKey().nbt() != null) {
                tag.put("nbt", entry.getKey().nbt());
            }
            tag.putLong("count", entry.getValue());
            // Canonical element value (= build_cost); decompose yields 1/decomposeDivisor of it (Config).
            Map<ElementType, Long> value = itemElementValues.get(entry.getKey().itemId());
            if (value != null && !value.isEmpty()) {
                CompoundTag yieldTag = new CompoundTag();
                for (var e : value.entrySet()) {
                    yieldTag.putLong(e.getKey().name().toLowerCase(), e.getValue());
                }
                tag.put("yield", yieldTag);
            }
            itemList.add(tag);
        }

        ListTag recipeList = new ListTag();
        for (SynthesizeRecipe r : synthRecipes) {
            // Service-side check only — log but DO NOT filter from the packet.
            // The client renders locked vs unlocked state locally using the
            // locked_reason NBT.  Server-side re-validation happens in
            // RequestProductionTaskPacket.handleServer() to prevent tampering.
            boolean unlocked = com.wsteam.wandscape.content.production.internal.RecipeUnlockChecker
                    .isUnlocked(colonyId, r.unlockRequirement());
            int maxAffordable = ProductionAffordability.computeMaxAffordable(r.cost(), elementMap);
            CompoundTag tag = new CompoundTag();
            tag.putString("id", r.id());
            tag.putString("output", r.outputItem());
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
                unlockTag.putInt("min_colony_level", r.unlockRequirement().minColonyLevel());
                tag.put("unlock_requirement", unlockTag);
            }
            recipeList.add(tag);
        }

        return new WorkstationDataPacket(stationPos, itemList, recipeList, creator);
    }

    public List<DecomposableEntry> decomposableEntries() {
        List<DecomposableEntry> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            CompoundTag tag = items.getCompound(i);
            String key = tag.getString("key");
            CompoundTag nbt = tag.contains("nbt") ? tag.getCompound("nbt") : null;
            long count = tag.getLong("count");
            if (!key.isEmpty() && count > 0) {
                result.add(new DecomposableEntry(key, nbt, count, readElementMap(tag, "yield")));
            }
        }
        return result;
    }

    private static Map<ElementType, Long> readElementMap(CompoundTag tag, String keyName) {
        Map<ElementType, Long> result = new LinkedHashMap<>();
        if (tag.contains(keyName)) {
            CompoundTag mapTag = tag.getCompound(keyName);
            for (String key : mapTag.getAllKeys()) {
                ElementType type = ElementType.valueOf(key.toUpperCase());
                result.put(type, mapTag.getLong(key));
            }
        }
        return result;
    }

    public List<SynthesizeEntry> synthesizeEntries() {
        List<SynthesizeEntry> result = new ArrayList<>();
        for (int i = 0; i < recipes.size(); i++) {
            CompoundTag tag = recipes.getCompound(i);
            String id = tag.getString("id");
            String output = tag.getString("output");
            Map<ElementType, Long> cost = new LinkedHashMap<>();
            CompoundTag costTag = tag.getCompound("cost");
            for (String key : costTag.getAllKeys()) {
                ElementType type = ElementType.valueOf(key.toUpperCase());
                cost.put(type, costTag.getLong(key));
            }
            int maxAffordable = tag.getInt("max_affordable");
            String lockedReason = tag.getString("locked_reason");
            // Read unlock requirement from NBT (serialised by from() on the server)
            RecipeUnlockRequirement unlockReq = RecipeUnlockRequirement.NONE;
            if (tag.contains("unlock_requirement")) {
                CompoundTag urTag = tag.getCompound("unlock_requirement");
                int level = urTag.contains("min_colony_level") ? urTag.getInt("min_colony_level") : 1;
                unlockReq = new RecipeUnlockRequirement(level);
            }
            result.add(new SynthesizeEntry(id, output, cost, maxAffordable, lockedReason, unlockReq));
        }
        return result;
    }

    public record DecomposableEntry(String itemId, @javax.annotation.Nullable CompoundTag nbt, long count,
                                    Map<ElementType, Long> elementValue) {}

    /**
     * @param recipeId          recipe identifier
     * @param outputItem        output item id
     * @param cost              element cost per unit
     * @param maxAffordable     maximum quantity affordable with current elements (0 when locked)
     * @param lockedReason      "unlocked" / "colony" / "elements" — client uses this to pick lock hint
     * @param unlockRequirement minimum colony level (only meaningful when lockedReason=colony)
     */
    public record SynthesizeEntry(
            String recipeId,
            String outputItem,
            Map<ElementType, Long> cost,
            int maxAffordable,
            String lockedReason,
            RecipeUnlockRequirement unlockRequirement
    ) {}

    private static Consumer<WorkstationDataPacket> clientHandler;

    public static void setClientHandler(Consumer<WorkstationDataPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(WorkstationDataPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    static void write(RegistryFriendlyByteBuf buf, WorkstationDataPacket pkt) {
        CompoundTag wrapper = new CompoundTag();
        wrapper.putLong("pos", pkt.stationPos.asLong());
        wrapper.put("items", pkt.items);
        wrapper.put("recipes", pkt.recipes);
        wrapper.putString("creator", pkt.creator != null ? pkt.creator : "");
        buf.writeNbt(wrapper);
    }

    static WorkstationDataPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag wrapper = buf.readNbt();
        if (wrapper == null) return new WorkstationDataPacket(BlockPos.ZERO, new ListTag(), new ListTag(), "");
        BlockPos pos = BlockPos.of(wrapper.getLong("pos"));
        ListTag items = wrapper.getList("items", Tag.TAG_COMPOUND);
        ListTag recipes = wrapper.getList("recipes", Tag.TAG_COMPOUND);
        String creator = wrapper.getString("creator");
        return new WorkstationDataPacket(pos, items, recipes, creator);
    }
}
