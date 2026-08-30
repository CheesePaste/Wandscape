package com.wsteam.wandscape.warehouse.network;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.warehouse.WarehouseTerminalItem;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端按下仓库终端快捷键时向服务端发送的请求包。
 * 服务端校验玩家是否穿戴/持有仓库终端，是则打开仓库菜单，否则提示未穿戴。
 */
public record WarehouseTerminalKeyPacket() implements CustomPacketPayload {

    public static final Type<WarehouseTerminalKeyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "warehouse_terminal_key"));

    public static final StreamCodec<ByteBuf, WarehouseTerminalKeyPacket> STREAM_CODEC =
            StreamCodec.unit(new WarehouseTerminalKeyPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端处理快捷键开仓请求。 */
    public static void handleServer(WarehouseTerminalKeyPacket packet, ServerPlayer player) {
        if (player == null) return;
        if (!WarehouseTerminalItem.isTerminalEquipped(player)) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.wandscape.warehouse_terminal.not_equipped"), true);
            return;
        }
        WarehouseTerminalItem.openWarehouse(player);
    }
}
