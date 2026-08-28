package com.wsteam.wandscape.production.network;

import java.util.*;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import com.wsteam.wandscape.production.data.BrewPotionRecipe;
import com.wsteam.wandscape.production.data.CraftWandRecipe;
import com.wsteam.wandscape.production.data.MiscRecipe;
import com.wsteam.wandscape.production.data.RecipeUnlockRequirement;
import com.wsteam.wandscape.production.internal.ProductionAffordability;
import com.wsteam.wandscape.production.internal.RecipeUnlockChecker;
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
 * Server→client packet carrying craft recipe data (wand / potion / misc) for the crafting station GUI.
 */
public record CraftingStationPacket(BlockPos stationPos, ListTag recipes, String creator)
        implements CustomPacketPayload {

    public static final Type<CraftingStationPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "crafting_station_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftingStationPacket> STREAM_CODEC =
            StreamCodec.of(CraftingStationPacket::write, CraftingStationPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static CraftingStationPacket from(BlockPos stationPos,
                                              Collection<CraftWandRecipe> wandRecipes,
                                              Collection<BrewPotionRecipe> potionRecipes,
                                              Collection<MiscRecipe> miscRecipes,
                                              Map<ElementType, Long> elementMap,
                                              @Nullable UUID colonyId,
                                              String creator) {
        ListTag list = new ListTag();
        for (CraftWandRecipe r : wandRecipes) {
            // Service-side check only — log but DO NOT filter from the packet.
            // The client renders locked vs unlocked state locally.
            // Server-side re-validation in RequestProductionTaskPacket prevents tampering.
            list.add(buildRecipeTag(r.id(), "wand", r.outputItem(), r.outputNbt(), List.of(),
                    r.cost(), r.unlockRequirement(), colonyId, elementMap));
        }
        for (BrewPotionRecipe r : potionRecipes) {
            list.add(buildRecipeTag(r.id(), "potion", r.outputItem(), null, r.inputItems(),
                    r.cost(), r.unlockRequirement(), colonyId, elementMap));
        }
        for (MiscRecipe r : miscRecipes) {
            list.add(buildRecipeTag(r.id(), "misc", r.outputItem(), null, List.of(),
                    r.cost(), r.unlockRequirement(), colonyId, elementMap));
        }
        return new CraftingStationPacket(stationPos, list, creator);
    }

    /** Serialize one recipe as a CompoundTag with common fields (unlock/maxAffordable/type/inputs). */
    private static CompoundTag buildRecipeTag(String id, String type, String outputItem,
                                              @Nullable CompoundTag outputNbt, List<String> extraInputs,
                                              Map<ElementType, Long> cost,
                                              RecipeUnlockRequirement unlockRequirement,
                                              @Nullable UUID colonyId,
                                              Map<ElementType, Long> elementMap) {
        boolean unlocked = RecipeUnlockChecker.isUnlocked(colonyId, unlockRequirement);
        int maxAffordable = ProductionAffordability.computeMaxAffordable(cost, elementMap);

        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("type", type);
        tag.putString("output", outputItem);
        if (outputNbt != null && !outputNbt.isEmpty()) {
            tag.put("nbt", outputNbt);
        }
        CompoundTag costTag = new CompoundTag();
        for (var e : cost.entrySet()) {
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

        if (!extraInputs.isEmpty()) {
            ListTag inputs = new ListTag();
            for (String item : extraInputs) {
                inputs.add(net.minecraft.nbt.StringTag.valueOf(item));
            }
            tag.put("extra_inputs", inputs);
        }

        // Serialise colony unlock requirement when not yet satisfied
        if (!unlockRequirement.equals(RecipeUnlockRequirement.NONE)) {
            CompoundTag unlockTag = new CompoundTag();
            unlockTag.putInt("min_colony_level", unlockRequirement.minColonyLevel());
            tag.put("unlock_requirement", unlockTag);
        }
        return tag;
    }

    public List<RecipeEntry> entries() {
        List<RecipeEntry> result = new ArrayList<>();
        for (int i = 0; i < recipes.size(); i++) {
            CompoundTag tag = recipes.getCompound(i);
            String id = tag.getString("id");
            String type = tag.contains("type") ? tag.getString("type") : "wand";
            String output = tag.getString("output");
            CompoundTag nbt = tag.contains("nbt") ? tag.getCompound("nbt") : null;
            List<String> extraInputs = new ArrayList<>();
            if (tag.contains("extra_inputs")) {
                ListTag extra = tag.getList("extra_inputs", Tag.TAG_STRING);
                for (int j = 0; j < extra.size(); j++) {
                    extraInputs.add(extra.getString(j));
                }
            }
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
                int level = urTag.contains("min_colony_level") ? urTag.getInt("min_colony_level") : 1;
                unlockReq = new RecipeUnlockRequirement(level);
            }
            result.add(new RecipeEntry(id, type, output, nbt, extraInputs, cost,
                    maxAffordable, lockedReason, unlockReq));
        }
        return result;
    }

    /**
     * @param recipeId          recipe identifier
     * @param type              recipe kind: "wand" / "potion" / "misc" / "spell" (station screen submits "craft")
     * @param outputItem        output item id
     * @param nbt               output item NBT (nullable)
     * @param extraInputs       extra non-element inputs (potion glass bottles etc, may be empty)
     * @param cost              element cost per unit
     * @param maxAffordable     maximum quantity affordable with current elements (0 when locked)
     * @param lockedReason      "unlocked" / "colony" / "elements" — client uses this to pick lock hint
     * @param unlockRequirement minimum colony level (only meaningful when lockedReason=colony)
     */
    public record RecipeEntry(
            String recipeId,
            String type,
            String outputItem,
            @javax.annotation.Nullable CompoundTag nbt,
            List<String> extraInputs,
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
        wrapper.putString("creator", pkt.creator != null ? pkt.creator : "");
        buf.writeNbt(wrapper);
    }

    static CraftingStationPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag wrapper = buf.readNbt();
        if (wrapper == null) return new CraftingStationPacket(BlockPos.ZERO, new ListTag(), "");
        BlockPos pos = BlockPos.of(wrapper.getLong("pos"));
        String creator = wrapper.getString("creator");
        return new CraftingStationPacket(pos, wrapper.getList("recipes", Tag.TAG_COMPOUND), creator);
    }
}
