package com.wsteam.wandscape.production.network;

import java.util.*;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import com.wsteam.wandscape.production.data.RecipeUnlockRequirement;
import com.wsteam.wandscape.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;

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
 * Server→client packet carrying workstation GUI data:
 * decomposable items in warehouse + available synthesize recipes.
 */
public record WorkstationDataPacket(BlockPos stationPos, ListTag items, ListTag recipes) implements CustomPacketPayload {

    public static final Type<WorkstationDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "workstation_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WorkstationDataPacket> STREAM_CODEC =
            StreamCodec.of(WorkstationDataPacket::write, WorkstationDataPacket::read);

    private static final int MAX_PER_OPERATION = 64;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static WorkstationDataPacket from(
            BlockPos stationPos,
            Map<ItemKey, Long> decomposableItems,
            Collection<SynthesizeRecipe> synthRecipes,
            Map<ElementType, Long> elementMap,
            @Nullable UUID colonyId) {
        ListTag itemList = new ListTag();
        for (var entry : decomposableItems.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("key", entry.getKey().itemId());
            if (entry.getKey().nbt() != null) {
                tag.put("nbt", entry.getKey().nbt());
            }
            tag.putLong("count", entry.getValue());
            itemList.add(tag);
        }

        ListTag recipeList = new ListTag();
        for (SynthesizeRecipe r : synthRecipes) {
            // Service-side check only — log but DO NOT filter from the packet.
            // The client renders locked vs unlocked state locally using the
            // unlock_requirement NBT.  Server-side re-validation happens in
            // RequestProductionTaskPacket.handleServer() to prevent tampering.
            boolean unlocked = com.wsteam.wandscape.production.internal.RecipeUnlockChecker
                    .isUnlocked(colonyId, r.unlockRequirement());
            int maxAffordable = computeMaxAffordable(r.cost(), elementMap);
            CompoundTag tag = new CompoundTag();
            tag.putString("id", r.id());
            tag.putString("output", r.outputItem());
            CompoundTag costTag = new CompoundTag();
            for (var e : r.cost().entrySet()) {
                costTag.putLong(e.getKey().name().toLowerCase(), e.getValue());
            }
            tag.put("cost", costTag);
            tag.putInt("required_level", r.requiredLevel());
            // Only send max_affordable for unlocked recipes; locked recipes get 0
            tag.putInt("max_affordable", unlocked ? maxAffordable : 0);
            // Always serialise unlock requirement for client-side display
            if (r.unlockRequirement() != RecipeUnlockRequirement.NONE) {
                CompoundTag unlockTag = new CompoundTag();
                unlockTag.putInt("min_comfort", r.unlockRequirement().minComfort());
                unlockTag.putInt("min_magic",   r.unlockRequirement().minMagic());
                unlockTag.putInt("min_wonder",  r.unlockRequirement().minWonder());
                tag.put("unlock_requirement", unlockTag);
            }
            recipeList.add(tag);
        }

        return new WorkstationDataPacket(stationPos, itemList, recipeList);
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

    public List<DecomposableEntry> decomposableEntries() {
        List<DecomposableEntry> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            CompoundTag tag = items.getCompound(i);
            String key = tag.getString("key");
            CompoundTag nbt = tag.contains("nbt") ? tag.getCompound("nbt") : null;
            long count = tag.getLong("count");
            if (!key.isEmpty() && count > 0) {
                result.add(new DecomposableEntry(key, nbt, count));
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
            int requiredLevel = tag.getInt("required_level");
            int maxAffordable = tag.getInt("max_affordable");
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
            result.add(new SynthesizeEntry(id, output, cost, requiredLevel, maxAffordable, unlockReq));
        }
        return result;
    }

    public record DecomposableEntry(String itemId, @javax.annotation.Nullable CompoundTag nbt, long count) {}

    /**
     * @param recipeId          recipe identifier
     * @param outputItem        output item id
     * @param cost              element cost per unit
     * @param requiredLevel     minimum NPC behaviour level
     * @param maxAffordable     maximum quantity affordable with current elements
     * @param unlockRequirement colony evaluation thresholds required to see this recipe
     */
    public record SynthesizeEntry(
            String recipeId,
            String outputItem,
            Map<ElementType, Long> cost,
            int requiredLevel,
            int maxAffordable,
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
        buf.writeNbt(wrapper);
    }

    static WorkstationDataPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag wrapper = buf.readNbt();
        if (wrapper == null) return new WorkstationDataPacket(BlockPos.ZERO, new ListTag(), new ListTag());
        BlockPos pos = BlockPos.of(wrapper.getLong("pos"));
        ListTag items = wrapper.getList("items", Tag.TAG_COMPOUND);
        ListTag recipes = wrapper.getList("recipes", Tag.TAG_COMPOUND);
        return new WorkstationDataPacket(pos, items, recipes);
    }
}
