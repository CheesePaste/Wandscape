package com.wsteam.wandscape.building.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→client packet: opens the Mage Hut GUI with the resident mage's
 * progression (base/level/equip breakdown) and, when empty, the list of
 * assignable colony mages.
 */
public record MageHutDataPacket(BlockPos buildingPos, UUID colonyId, String creator,
                                int colonyLevel, boolean hasResident, boolean alive,
                                boolean resting, @Nullable UUID npcId, String mageName,
                                int mageLevel, int skinVariant, float[] base, float[] equipBonus,
                                List<MageCandidate> candidates) implements CustomPacketPayload {

    /** A colony mage the player can assign to the hut (render-only data). */
    public record MageCandidate(UUID npcId, String name, boolean idle) {}

    public static final Type<MageHutDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "mage_hut_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MageHutDataPacket> STREAM_CODEC =
            StreamCodec.of(MageHutDataPacket::write, MageHutDataPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    private static Consumer<MageHutDataPacket> clientHandler;

    public static void setClientHandler(Consumer<MageHutDataPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(MageHutDataPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, MageHutDataPacket pkt) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", pkt.buildingPos.asLong());
        tag.putUUID("colony", pkt.colonyId);
        tag.putString("creator", pkt.creator != null ? pkt.creator : "");
        tag.putInt("colonyLevel", pkt.colonyLevel);
        tag.putBoolean("hasResident", pkt.hasResident);
        tag.putBoolean("alive", pkt.alive);
        tag.putBoolean("resting", pkt.resting);
        if (pkt.npcId != null) {
            tag.putUUID("npcId", pkt.npcId);
        }
        tag.putString("name", pkt.mageName != null ? pkt.mageName : "");
        tag.putInt("mageLevel", pkt.mageLevel);
        tag.putInt("skinVariant", pkt.skinVariant);
        tag.putIntArray("base", floatBits(pkt.base));
        tag.putIntArray("equip", floatBits(pkt.equipBonus));
        ListTag candidatesTag = new ListTag();
        for (MageCandidate c : pkt.candidates) {
            CompoundTag ct = new CompoundTag();
            ct.putUUID("id", c.npcId());
            ct.putString("name", c.name() != null ? c.name() : "");
            ct.putBoolean("idle", c.idle());
            candidatesTag.add(ct);
        }
        tag.put("candidates", candidatesTag);
        buf.writeNbt(tag);
    }

    static MageHutDataPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        if (tag == null) {
            return empty();
        }
        List<MageCandidate> candidates = new ArrayList<>();
        ListTag cList = tag.getList("candidates", ListTag.TAG_COMPOUND);
        for (int i = 0; i < cList.size(); i++) {
            CompoundTag ct = cList.getCompound(i);
            candidates.add(new MageCandidate(ct.getUUID("id"), ct.getString("name"),
                    ct.getBoolean("idle")));
        }
        return new MageHutDataPacket(
                BlockPos.of(tag.getLong("pos")),
                tag.getUUID("colony"),
                tag.getString("creator"),
                tag.getInt("colonyLevel"),
                tag.getBoolean("hasResident"),
                tag.getBoolean("alive"),
                tag.getBoolean("resting"),
                tag.hasUUID("npcId") ? tag.getUUID("npcId") : null,
                tag.getString("name"),
                tag.getInt("mageLevel"),
                tag.getInt("skinVariant"),
                fromBits(tag.getIntArray("base")),
                fromBits(tag.getIntArray("equip")),
                candidates);
    }

    private static int[] floatBits(float[] values) {
        int[] bits = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Float.floatToIntBits(values[i]);
        }
        return bits;
    }

    private static float[] fromBits(int[] bits) {
        float[] values = new float[bits.length];
        for (int i = 0; i < bits.length; i++) {
            values[i] = Float.intBitsToFloat(bits[i]);
        }
        return values;
    }

    private static MageHutDataPacket empty() {
        return new MageHutDataPacket(BlockPos.ZERO, new UUID(0, 0), "", 1, false,
                false, false, null, "", 1, -1, new float[7], new float[7], List.of());
    }
}
