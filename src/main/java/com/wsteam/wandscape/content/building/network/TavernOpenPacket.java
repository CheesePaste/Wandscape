package com.wsteam.wandscape.content.building.network;

import com.wsteam.wandscape.content.npc.data.MageResume;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet: opens the Tavern GUI with recruitment data.
 */
public record TavernOpenPacket(BlockPos buildingPos, UUID colonyId,
                                int recruitCount, List<MageResume> mageResumes, String creator)
        implements CustomPacketPayload {

    public static final Type<TavernOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "tavern_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TavernOpenPacket> STREAM_CODEC =
            StreamCodec.of(TavernOpenPacket::write, TavernOpenPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    private static Consumer<TavernOpenPacket> clientHandler;

    public static void setClientHandler(Consumer<TavernOpenPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(TavernOpenPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, TavernOpenPacket pkt) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", pkt.buildingPos.asLong());
        tag.putUUID("colony", pkt.colonyId);
        tag.putInt("recruitCount", pkt.recruitCount);
        ListTag resumesTag = new ListTag();
        for (MageResume r : pkt.mageResumes) {
            CompoundTag rt = new CompoundTag();
            rt.putString("name", r.touristName());
            rt.putInt("level", r.level());
            rt.putFloat("maxHp", r.maxHp());
            rt.putFloat("moveSpeed", r.moveSpeed());
            rt.putFloat("spellPower", r.spellPower());
            rt.putFloat("workSpeed", r.workSpeed());
            rt.putFloat("spellSpeed", r.spellSpeed());
            rt.putFloat("armorValue", r.armorValue());
            rt.putFloat("maxMana", r.maxMana());
            rt.putInt("skinVariant", r.skinVariant());
            rt.putLong("timestamp", r.timestamp());
            resumesTag.add(rt);
        }
        tag.put("resumes", resumesTag);
        tag.putString("creator", pkt.creator != null ? pkt.creator : "");
        buf.writeNbt(tag);
    }

    static TavernOpenPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        if (tag == null) {
            return new TavernOpenPacket(BlockPos.ZERO, new UUID(0, 0), 0, List.of(), "");
        }
        List<MageResume> resumes = new ArrayList<>();
        ListTag list = tag.getList("resumes", ListTag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag rt = list.getCompound(i);
            resumes.add(new MageResume(
                    rt.getString("name"),
                    rt.getInt("level"),
                    rt.getFloat("maxHp"),
                    rt.getFloat("moveSpeed"),
                    rt.getFloat("spellPower"),
                    rt.getFloat("workSpeed"),
                    rt.getFloat("spellSpeed"),
                    rt.getFloat("armorValue"),
                    rt.getFloat("maxMana"),
                    rt.getInt("skinVariant"),
                    rt.getLong("timestamp")));
        }
        return new TavernOpenPacket(
                BlockPos.of(tag.getLong("pos")),
                tag.getUUID("colony"),
                tag.getInt("recruitCount"),
                resumes,
                tag.getString("creator"));
    }
}
