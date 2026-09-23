package com.wsteam.wandscape.foundation.networking;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 网络发送门面——全仓库唯一直接调用 {@link PacketDistributor} 的地方。
 *
 * <p>存在的理由：发送点有 185 处，散布在 80 个文件里，但用到的平台方法只有 5 个。
 * 把这 5 个方法收在这里，发送拓扑（谁给谁发什么）从 80 个文件散落变成一处可读，
 * 且平台符号（{@code PacketDistributor}）的实现类依赖只剩本文件一处。
 *
 * <p>与 {@link PayloadRegistry} 的分工：本类管「发」，注册表管「收 + 类型契约」。
 * 两者是网络层仅有的两个接触平台 API 的文件。
 *
 * <p>取值约定（与 {@code PacketDistributor} 一致，不做改写）：
 * <ul>
 *   <li>{@link #toServer} 只能在客户端调，服务端调会抛异常。</li>
 *   <li>其余方法只能在服务端调，客户端调会抛异常。</li>
 *   <li>{@link #toTracking} 含实体自身（跟踪该实体的玩家 + 自己若是玩家）。
 *       殖民地法师与游客都是 Mob，自身分支实际不触发；玩家施法时自身生效是有意的。</li>
 * </ul>
 */
public final class Net {

    private Net() {}

    /** 客户端 → 服务端。 */
    public static void toServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** 服务端 → 指定玩家。 */
    public static void toPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** 服务端 → 全服所有玩家。 */
    public static void toAll(CustomPacketPayload payload) {
        PacketDistributor.sendToAllPlayers(payload);
    }

    /** 服务端 → 正在跟踪该实体的所有玩家（含实体自身若为玩家）。 */
    public static void toTracking(Entity entity, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, payload);
    }

    /** 服务端 → 正在跟踪该区块的所有玩家。 */
    public static void toTrackingChunk(ServerLevel level, ChunkPos pos, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersTrackingChunk(level, pos, payload);
    }
}
