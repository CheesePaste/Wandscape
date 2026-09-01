package com.wsteam.wandscape.content.building.scanner.network;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Requests the element value of the scanner boundary box.
 * The server scans all non-air / non-scanner / non-marker blocks inside the
 * boundary, sums each block's element value (= build_cost, same source as
 * {@code ElementMappingLoader.getItemElementValue}),
 * and prints the per-element totals plus grand total to the player's chat.
 */
public record ScannerValuePacket(BlockPos pos) implements CustomPacketPayload {

    private static final String TAG = "ScannerValue";

    public static final Type<ScannerValuePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "scanner_value"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScannerValuePacket> STREAM_CODEC =
            StreamCodec.of(ScannerValuePacket::write, ScannerValuePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(ScannerValuePacket packet, ServerPlayer player) {
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        BlockEntity be = level.getBlockEntity(packet.pos);
        if (!(be instanceof CreativeScannerBlockEntity scanner)) {
            Log.warn(TAG, "No scanner BE at {}", packet.pos);
            player.sendSystemMessage(I18n.name("message.wandscape.scanner.value_no_scanner", "§c未找到扫描器方块 @ %s", packet.pos.toShortString()));
            return;
        }

        // Auto-detect boundary from CORNER blocks, mirroring the export flow.
        scanner.detectBoundaryFromCorners(level);
        BlockPos wMin = scanner.getWorldMin();
        BlockPos wMax = scanner.getWorldMax();
        if (wMin == null || wMax == null) {
            player.sendSystemMessage(I18n.name("message.wandscape.scanner.value_no_boundary", "§c未定义 3D 边界"));
            return;
        }

        var elementApi = WandscapeApis.getElementApi();
        Map<ElementType, Long> totals = new EnumMap<>(ElementType.class);
        long grandTotal = 0;
        long blockCount = 0;
        BlockPos scannerPos = scanner.getBlockPos();

        for (int x = wMin.getX(); x <= wMax.getX(); x++) {
            for (int y = wMin.getY(); y <= wMax.getY(); y++) {
                for (int z = wMin.getZ(); z <= wMax.getZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    if (bp.equals(scannerPos)) continue;
                    BlockState state = level.getBlockState(bp);
                    if (state.isAir()) continue;
                    if (state.is(com.wsteam.wandscape.Wandscape.BUILDING_SCANNER.get())
                            || state.is(com.wsteam.wandscape.Wandscape.CREATIVE_BUILDING_SCANNER.get())
                            || state.is(com.wsteam.wandscape.Wandscape.INTERACT_SPOT_MARKER.get())) continue;

                    blockCount++;
                    Map<ElementType, Long> value = elementApi.getBuildCost(state);
                    for (var entry : value.entrySet()) {
                        long amount = entry.getValue();
                        totals.merge(entry.getKey(), amount, Long::sum);
                        grandTotal += amount;
                    }
                }
            }
        }

        if (blockCount == 0) {
            player.sendSystemMessage(I18n.name("message.wandscape.scanner.value_no_blocks", "§c区域内无有效方块（已排除扫描器与交互位）"));
            return;
        }

        var msg = I18n.name("message.wandscape.scanner.value_total_header", "§a区域元素价值: ");
        List<Component> parts = new ArrayList<>();
        for (var entry : totals.entrySet()) {
            parts.add(Component.literal("§7" + entry.getKey().getId() + " §f" + entry.getValue()));
        }
        if (parts.isEmpty()) {
            msg.append(I18n.name("message.wandscape.scanner.value_no_mapping", "§c区域内方块均无元素映射，价值为 0"));
        } else {
            for (Component p : parts) msg.append(p);
            msg.append(I18n.name("message.wandscape.scanner.value_grand_total", "  §6总价值 §e%s", grandTotal));
        }
        msg.append(I18n.name("message.wandscape.scanner.value_block_count", "  §8(%s 方块)", blockCount));
        player.sendSystemMessage(msg);
    }

    private static void write(RegistryFriendlyByteBuf buf, ScannerValuePacket pkt) {
        buf.writeBlockPos(pkt.pos);
    }

    private static ScannerValuePacket read(RegistryFriendlyByteBuf buf) {
        return new ScannerValuePacket(buf.readBlockPos());
    }
}
