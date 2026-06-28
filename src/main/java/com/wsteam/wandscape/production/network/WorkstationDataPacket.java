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
            // locked_reason NBT.  Server-side re-validation happens in
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

            // Determine locked reason before setting max_affordable
            String lockedReason;
            if (!unlocked) {
                lockedReason = "colony";
                maxAffordable = 0;
            } else if (maxAffordable == 0 && hasNonZeroWandLevel(r.wandLevel())) {
                lockedReason = "wand_level";
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
            // Serialise wand_level only when it is the locking reason
            if ("wand_level".equals(lockedReason) && r.wandLevel() != null) {
                CompoundTag wlTag = new CompoundTag();
                for (var e : r.wandLevel().entrySet()) {
                    wlTag.putInt(e.getKey(), e.getValue());
                }
                tag.put("wand_level", wlTag);
            }
            recipeList.add(tag);
        }

        return new WorkstationDataPacket(stationPos, itemList, recipeList);
    }

    private static boolean hasNonZeroWandLevel(@Nullable Map<String, Integer> wandLevel) {
        if (wandLevel == null) return false;
        for (int v : wandLevel.values()) {
            if (v > 0) return true;
        }
        return false;
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
            // Read wand_level NBT (serialised by from() when locked_reason == "wand_level")
            Map<String, Integer> wandLevel = null;
            if ("wand_level".equals(lockedReason) && tag.contains("wand_level")) {
                CompoundTag wlTag = tag.getCompound("wand_level");
                wandLevel = new LinkedHashMap<>();
                for (String key : wlTag.getAllKeys()) {
                    wandLevel.put(key, wlTag.getInt(key));
                }
            }
            result.add(new SynthesizeEntry(id, output, cost, maxAffordable, lockedReason, unlockReq, wandLevel));
        }
        return result;
    }

    public record DecomposableEntry(String itemId, @javax.annotation.Nullable CompoundTag nbt, long count) {}

    /**
     * @param recipeId          recipe identifier
     * @param outputItem        output item id
     * @param cost              element cost per unit
     * @param maxAffordable     maximum quantity affordable with current elements (0 when locked)
     * @param lockedReason      "unlocked" / "colony" / "elements" / "wand_level" — client uses this to pick lock hint
     * @param unlockRequirement colony evaluation thresholds (only meaningful when lockedReason=colony)
     * @param wandLevel         wand ability overrides from recipe JSON (only when lockedReason=wand_level)
     */
    public record SynthesizeEntry(
            String recipeId,
            String outputItem,
            Map<ElementType, Long> cost,
            int maxAffordable,
            String lockedReason,
            RecipeUnlockRequirement unlockRequirement,
            @javax.annotation.Nullable Map<String, Integer> wandLevel
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
