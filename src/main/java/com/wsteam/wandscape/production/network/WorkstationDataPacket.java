package com.wsteam.wandscape.production.network;

import java.util.*;
import java.util.function.Consumer;

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
            Map<ElementType, Long> elementMap) {
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
            tag.putInt("max_affordable", maxAffordable);
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
            result.add(new SynthesizeEntry(id, output, cost, requiredLevel, maxAffordable));
        }
        return result;
    }

    public record DecomposableEntry(String itemId, @javax.annotation.Nullable CompoundTag nbt, long count) {}

    public record SynthesizeEntry(String recipeId, String outputItem, Map<ElementType, Long> cost,
                                  int requiredLevel, int maxAffordable) {}

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
