package com.wsteam.wandscape.content.production.network;

import com.wsteam.wandscape.content.production.data.CraftSpellRecipe;
import com.wsteam.wandscape.content.production.data.RecipeUnlockRequirement;
import com.wsteam.wandscape.content.production.internal.ProductionAffordability;
import com.wsteam.wandscape.content.element.data.ElementType;
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
 * Server→client packet carrying spell scroll recipe data for the magic station GUI.
 */
public record MagicStationPacket(BlockPos stationPos, ListTag recipes, String creator)
        implements CustomPacketPayload {

    public static final Type<MagicStationPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "magic_station_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MagicStationPacket> STREAM_CODEC =
            StreamCodec.of(MagicStationPacket::write, MagicStationPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static MagicStationPacket from(BlockPos stationPos,
                                          Collection<CraftSpellRecipe> recipes,
                                          Map<ElementType, Long> elementMap,
                                          @Nullable UUID colonyId,
                                          String creator) {
        ListTag list = new ListTag();
        for (CraftSpellRecipe r : recipes) {
            boolean unlocked = com.wsteam.wandscape.content.production.internal.RecipeUnlockChecker
                    .isUnlocked(colonyId, r.unlockRequirement());
            int maxAffordable = ProductionAffordability.computeMaxAffordable(r.cost(), elementMap);

            CompoundTag tag = new CompoundTag();
            tag.putString("id", r.id());
            tag.putString("output", r.outputItem());
            tag.putString("magic_id", r.magicId());

            CompoundTag costTag = new CompoundTag();
            for (var e : r.cost().entrySet()) {
                costTag.putLong(e.getKey().name().toLowerCase(), e.getValue());
            }
            tag.put("cost", costTag);

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

            if (!r.unlockRequirement().equals(RecipeUnlockRequirement.NONE)) {
                CompoundTag unlockTag = new CompoundTag();
                unlockTag.putInt("min_colony_level", r.unlockRequirement().minColonyLevel());
                tag.put("unlock_requirement", unlockTag);
            }
            list.add(tag);
        }
        return new MagicStationPacket(stationPos, list, creator);
    }

    public List<SpellEntry> entries() {
        List<SpellEntry> result = new ArrayList<>();
        for (int i = 0; i < recipes.size(); i++) {
            CompoundTag tag = recipes.getCompound(i);
            String id = tag.getString("id");
            String output = tag.getString("output");
            String magicId = tag.getString("magic_id");
            Map<ElementType, Long> cost = new LinkedHashMap<>();
            CompoundTag costTag = tag.getCompound("cost");
            for (String key : costTag.getAllKeys()) {
                cost.put(ElementType.valueOf(key.toUpperCase()), costTag.getLong(key));
            }
            int maxAffordable = tag.getInt("max_affordable");
            String lockedReason = tag.getString("locked_reason");
            RecipeUnlockRequirement unlockReq = RecipeUnlockRequirement.NONE;
            if (tag.contains("unlock_requirement")) {
                CompoundTag urTag = tag.getCompound("unlock_requirement");
                int level = urTag.contains("min_colony_level") ? urTag.getInt("min_colony_level") : 1;
                unlockReq = new RecipeUnlockRequirement(level);
            }
            result.add(new SpellEntry(id, output, magicId, cost, maxAffordable, lockedReason, unlockReq));
        }
        return result;
    }

    /**
     * @param recipeId       recipe identifier
     * @param outputItem     output item id (spell_scroll)
     * @param magicId        bound magic id
     * @param cost           element cost per unit
     * @param maxAffordable  maximum quantity affordable with current elements (0 when locked)
     * @param lockedReason   "unlocked" / "colony" / "elements" — client uses this to pick lock hint
     * @param unlockRequirement minimum colony level (only meaningful when lockedReason=colony)
     */
    public record SpellEntry(
            String recipeId,
            String outputItem,
            String magicId,
            Map<ElementType, Long> cost,
            int maxAffordable,
            String lockedReason,
            RecipeUnlockRequirement unlockRequirement
    ) {}

    private static Consumer<MagicStationPacket> clientHandler;

    public static void setClientHandler(Consumer<MagicStationPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(MagicStationPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    static void write(RegistryFriendlyByteBuf buf, MagicStationPacket pkt) {
        CompoundTag wrapper = new CompoundTag();
        wrapper.putLong("pos", pkt.stationPos.asLong());
        wrapper.put("recipes", pkt.recipes);
        wrapper.putString("creator", pkt.creator != null ? pkt.creator : "");
        buf.writeNbt(wrapper);
    }

    static MagicStationPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag wrapper = buf.readNbt();
        if (wrapper == null) return new MagicStationPacket(BlockPos.ZERO, new ListTag(), "");
        BlockPos pos = BlockPos.of(wrapper.getLong("pos"));
        String creator = wrapper.getString("creator");
        return new MagicStationPacket(pos, wrapper.getList("recipes", Tag.TAG_COMPOUND), creator);
    }
}