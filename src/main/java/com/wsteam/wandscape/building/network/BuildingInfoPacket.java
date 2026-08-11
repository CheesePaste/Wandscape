package com.wsteam.wandscape.building.network;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet: opens the generic info panel for tourist-building
 * categories without a dedicated screen — service (non-hotel), relax,
 * decoration, atm. Carries the static config fields the panel displays.
 */
public record BuildingInfoPacket(BlockPos pos, String buildingTypeId, String category,
                                 Map<String, Integer> elementOutput,
                                 int energyPerUse, int energyRestore, int interactionDurationTicks,
                                 String creator)
        implements CustomPacketPayload {

    public static final Type<BuildingInfoPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "building_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingInfoPacket> STREAM_CODEC =
            StreamCodec.of(BuildingInfoPacket::write, BuildingInfoPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // Client handler
    private static Consumer<BuildingInfoPacket> clientHandler;

    public static void setClientHandler(Consumer<BuildingInfoPacket> handler) { clientHandler = handler; }

    public static void handleClient(BuildingInfoPacket packet) {
        if (clientHandler != null) clientHandler.accept(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, BuildingInfoPacket pkt) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", pkt.pos.asLong());
        tag.putString("type", pkt.buildingTypeId);
        tag.putString("category", pkt.category);
        tag.putInt("energyPerUse", pkt.energyPerUse);
        tag.putInt("energyRestore", pkt.energyRestore);
        tag.putInt("durationTicks", pkt.interactionDurationTicks);
        tag.putString("creator", pkt.creator);
        CompoundTag elem = new CompoundTag();
        for (var e : pkt.elementOutput.entrySet()) {
            elem.putInt(e.getKey(), e.getValue());
        }
        tag.put("elementOutput", elem);
        buf.writeNbt(tag);
    }

    static BuildingInfoPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        if (tag == null) {
            return new BuildingInfoPacket(BlockPos.ZERO, "", "", Map.of(), 0, 0, 0, "");
        }
        Map<String, Integer> elem = new HashMap<>();
        CompoundTag elemTag = tag.getCompound("elementOutput");
        for (String key : elemTag.getAllKeys()) {
            elem.put(key, elemTag.getInt(key));
        }
        return new BuildingInfoPacket(
                BlockPos.of(tag.getLong("pos")),
                tag.getString("type"),
                tag.getString("category"),
                elem,
                tag.getInt("energyPerUse"),
                tag.getInt("energyRestore"),
                tag.getInt("durationTicks"),
                tag.getString("creator"));
    }
}
