package com.wsteam.wandscape.building.scanner.network;

import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Carries the full BuildingScanner state as NBT.
 * Server deserializes into the target BE and syncs to all clients.
 */
public record ScannerSyncPacket(BlockPos pos, CompoundTag data) implements CustomPacketPayload {

    private static final String TAG = "ScannerSyncPacket";

    public static final Type<ScannerSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "scanner_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScannerSyncPacket> STREAM_CODEC =
            StreamCodec.of(ScannerSyncPacket::write, ScannerSyncPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(ScannerSyncPacket packet, ServerPlayer player) {
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        BlockEntity be = level.getBlockEntity(packet.pos);
        if (!(be instanceof CreativeScannerBlockEntity scanner)) {
            Log.warn(TAG, "No scanner BE at {}", packet.pos);
            return;
        }
        scanner.loadAdditional(packet.data, level.registryAccess());
        scanner.detectBoundaryFromCorners(level);
        scanner.setChanged();
        // Sync to all watching clients (including the sender)
        level.sendBlockUpdated(packet.pos, scanner.getBlockState(), scanner.getBlockState(), 3);
    }

    private static void write(RegistryFriendlyByteBuf buf, ScannerSyncPacket pkt) {
        buf.writeBlockPos(pkt.pos);
        buf.writeNbt(pkt.data);
    }

    private static ScannerSyncPacket read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        CompoundTag data = buf.readNbt();
        if (data == null) data = new CompoundTag();
        return new ScannerSyncPacket(pos, data);
    }
}
