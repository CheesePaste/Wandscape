package com.wsteam.wandscape.production.network;

import java.util.*;
import java.util.function.Consumer;

import com.wsteam.wandscape.production.data.CraftWandRecipe;
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
                                              Map<ElementType, Long> elementMap) {
        ListTag list = new ListTag();
        for (CraftWandRecipe r : wandRecipes) {
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
            tag.putInt("required_level", r.requiredLevel());
            tag.putInt("max_affordable", maxAffordable);
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
            int requiredLevel = tag.getInt("required_level");
            int maxAffordable = tag.getInt("max_affordable");
            result.add(new RecipeEntry(id, output, nbt, cost, requiredLevel, maxAffordable));
        }
        return result;
    }

    public record RecipeEntry(String recipeId, String outputItem, @javax.annotation.Nullable CompoundTag nbt,
                              Map<ElementType, Long> cost, int requiredLevel, int maxAffordable) {}

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
