package com.wsteam.wandscape.production.network;

import java.util.*;

import com.wsteam.wandscape.production.data.BrewPotionRecipe;
import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet carrying potion recipe data for the potion station GUI.
 * MVP stub — no client screen yet.
 */
public record PotionStationPacket(ListTag recipes) implements CustomPacketPayload {

    public static final Type<PotionStationPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "potion_station_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PotionStationPacket> STREAM_CODEC =
            StreamCodec.of(PotionStationPacket::write, PotionStationPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static PotionStationPacket from(Collection<BrewPotionRecipe> potionRecipes) {
        ListTag list = new ListTag();
        for (var r : potionRecipes) {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", r.id());
            tag.putString("output", r.outputItem());
            CompoundTag costTag = new CompoundTag();
            for (var e : r.cost().entrySet()) {
                costTag.putLong(e.getKey().name().toLowerCase(), e.getValue());
            }
            tag.put("cost", costTag);
            list.add(tag);
        }
        return new PotionStationPacket(list);
    }

    public static void handleClient(PotionStationPacket packet) {
        // MVP: no screen yet — stub handler
    }

    static void write(RegistryFriendlyByteBuf buf, PotionStationPacket pkt) {
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("recipes", pkt.recipes);
        buf.writeNbt(wrapper);
    }

    static PotionStationPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag wrapper = buf.readNbt();
        if (wrapper == null) return new PotionStationPacket(new ListTag());
        return new PotionStationPacket(wrapper.getList("recipes", Tag.TAG_COMPOUND));
    }
}
