package com.wsteam.wandscape.content.building.network;

import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.npc.internal.ReviveHandler;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→server: town hall 「复活法师」 bootstrap-revive button pressed.
 *
 * <p>Anti-deadlock escape hatch: with every wizard dead there is nobody left to walk to the
 * altar, so the colony can never recover. The server re-validates everything (building is a
 * government building of that colony, caller owns the colony, colony is wiped out, no cooldown)
 * before {@link ReviveHandler#reviveLatestAtTownHall} revives the most recently fallen wizard
 * at the town hall door — free, but on a per-colony cooldown.
 */
public record TownHallReviveRequestPacket(BlockPos buildingPos, UUID colonyId)
        implements CustomPacketPayload {

    private static final String TAG = "TownHallReviveRequest";

    public static final Type<TownHallReviveRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "town_hall_revive_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TownHallReviveRequestPacket> STREAM_CODEC =
            StreamCodec.of(TownHallReviveRequestPacket::write, TownHallReviveRequestPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Server-side handler. */
    public static void handleServer(TownHallReviveRequestPacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;

        sp.getServer().execute(() -> {
            ServerLevel level = sp.serverLevel();
            BuildingSavedData data = BuildingSavedData.get(level);
            UUID buildingId = data.getBuildingIdAt(pkt.buildingPos());
            if (buildingId == null) {
                Log.warn(TAG, "no building at {}", pkt.buildingPos());
                return;
            }

            BuildingState state = data.getBuilding(buildingId);
            if (state == null || !"government".equals(state.getCategory())) {
                Log.warn(TAG, "building {} is not a government building", buildingId);
                return;
            }
            UUID colonyId = state.getColonyId();
            if (colonyId == null || !colonyId.equals(pkt.colonyId())) {
                Log.warn(TAG, "colony mismatch for {}", buildingId);
                return;
            }
            // 完全平行隔离：只能操作自己小镇市政厅的保底复活。
            if (!com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.isOwn(colonyId, sp)) {
                com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(sp, "复活");
                return;
            }

            ReviveHandler.TownHallReviveResult result =
                    ReviveHandler.reviveLatestAtTownHall(level, colonyId);
            sendFeedback(sp, level, colonyId, result);
        });
    }

    private static void sendFeedback(ServerPlayer sp, ServerLevel level, UUID colonyId,
                                     ReviveHandler.TownHallReviveResult result) {
        switch (result) {
            case OK -> ScreenFeedbackPacket.send(sp, I18n.name(
                    "gui.wandscape.townhall.revive_ok",
                    "已复活一名法师（虚弱状态）——其余阵亡者请由他前往祭坛复活"), false);
            case NO_DEATH_RECORD -> ScreenFeedbackPacket.send(sp, I18n.name(
                    "gui.wandscape.townhall.revive_no_record",
                    "没有待复活的法师"), true);
            case STILL_ALIVE -> ScreenFeedbackPacket.send(sp, I18n.name(
                    "gui.wandscape.townhall.revive_still_alive",
                    "尚有法师存活，保底复活只在全员阵亡时可用"), true);
            case COOLDOWN -> ScreenFeedbackPacket.send(sp, I18n.name(
                    "gui.wandscape.townhall.revive_cooldown",
                    "复活冷却中，还需 %s 秒",
                    ReviveHandler.townHallReviveCooldownSeconds(level, colonyId)), true);
            case SPAWN_FAILED -> ScreenFeedbackPacket.send(sp, I18n.name(
                    "gui.wandscape.townhall.revive_failed",
                    "复活失败，死亡记录已保留，请稍后重试"), true);
        }
        // 无论成败都回推最新状态：成功则按钮应立刻置灰，失败则刷新为服务端权威值
        TownHallReviveStatePacket.send(sp, level, colonyId);
    }

    static void write(RegistryFriendlyByteBuf buf, TownHallReviveRequestPacket pkt) {
        buf.writeBlockPos(pkt.buildingPos);
        buf.writeUUID(pkt.colonyId);
    }

    static TownHallReviveRequestPacket read(RegistryFriendlyByteBuf buf) {
        return new TownHallReviveRequestPacket(buf.readBlockPos(), buf.readUUID());
    }
}
