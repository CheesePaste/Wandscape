package com.wsteam.wandscape.engine.transport;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;

import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.road.core.TransportRoute;
import com.wsteam.wandscape.shared.log.Log;

import static com.wsteam.wandscape.Wandscape.MODID;

public record TransportStartPacket(ItemKey itemKey, int count, BlockPos from, TransportRoute route) implements CustomPacketPayload {
    private static final String TAG = "TransportStartPacket";

    public static final Type<TransportStartPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "transport_start"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TransportStartPacket> STREAM_CODEC =
            StreamCodec.of(TransportStartPacket::write, TransportStartPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(TransportStartPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(packet.itemKey().itemId()));
        if (item == null) {
            Log.warn(TAG, "[ClientTransport] unknown item: {}", packet.itemKey().itemId());
            return;
        }

        ItemStack stack = new ItemStack(item, packet.count());
        if (packet.itemKey().nbt() != null && !packet.itemKey().nbt().isEmpty()) {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(packet.itemKey().nbt().copy()));
        }

        Vec3 center = Vec3.atCenterOf(packet.from());
        TransportItemEntity entity = new TransportItemEntity(level, center.x, center.y + 0.5, center.z, stack);
        entity.setRoute(packet.route());
        
        // Generate a random client-side entity ID (negative to avoid collision)
        entity.setId(-level.random.nextInt(Integer.MAX_VALUE));
        
        level.addEntity(entity);
    }

    static void write(RegistryFriendlyByteBuf buf, TransportStartPacket pkt) {
        buf.writeUtf(pkt.itemKey().itemId());
        buf.writeBoolean(pkt.itemKey().nbt() != null);
        if (pkt.itemKey().nbt() != null) {
            buf.writeNbt(pkt.itemKey().nbt());
        }
        buf.writeInt(pkt.count());
        buf.writeBlockPos(pkt.from());
        buf.writeBoolean(!pkt.route().isEmpty());
        if (!pkt.route().isEmpty()) {
            buf.writeNbt(pkt.route().toNbt());
        }
    }

    static TransportStartPacket read(RegistryFriendlyByteBuf buf) {
        String itemId = buf.readUtf();
        CompoundTag nbt = buf.readBoolean() ? buf.readNbt() : null;
        ItemKey key = new ItemKey(itemId, nbt);
        int count = buf.readInt();
        BlockPos from = buf.readBlockPos();
        TransportRoute route = buf.readBoolean() ? TransportRoute.fromNbt(buf.readNbt()) : new TransportRoute(java.util.List.of());
        
        return new TransportStartPacket(key, count, from, route);
    }
}
