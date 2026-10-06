package com.wsteam.wandscape.content.building.network;

import com.wsteam.wandscape.content.building.internal.BuildingDelegation;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.building.projection.network.BuildingDebugRequestPacket;
import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server：建筑委派面板的三个动作（一个包三个动作，与 {@code TaskQueueModifyPacket} 同风格）。
 *
 * <ul>
 *   <li>{@link #ACTION_PICKER} — 打开「选择一名法师」：回一份
 *       {@link BuildingDelegateDataPacket}（当前委派 + 候选列表）。</li>
 *   <li>{@link #ACTION_SET} — 把 {@code mageUuid} 委派到 {@code buildingId}；
 *       回执数据包与建筑状态快照一并推回，面板即时反映。</li>
 *   <li>{@link #ACTION_CLEAR} — 解除该建筑的委派。</li>
 * </ul>
 *
 * <p>档位：委派是「让某法师专职某建筑」的策略决定，按 MANAGER 把关（与切换法师模式同级）。
 * 委派的**行为约束**不在这里，而在任务域的派发/执行门槛（见 {@code BuildingDelegation}）。
 */
public record BuildingDelegatePacket(
        String action,
        UUID buildingId,
        @Nullable UUID mageUuid
) implements CustomPacketPayload {

    private static final String TAG = "BuildingDelegate";

    public static final String ACTION_PICKER = "picker";
    public static final String ACTION_SET = "set";
    public static final String ACTION_CLEAR = "clear";

    public static final Type<BuildingDelegatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "building_delegate"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingDelegatePacket> STREAM_CODEC =
            StreamCodec.of(BuildingDelegatePacket::write, BuildingDelegatePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(BuildingDelegatePacket pkt, ServerPlayer player) {
        if (player == null || player.isRemoved() || pkt.buildingId() == null) return;
        player.getServer().execute(() -> handle(pkt, player));
    }

    private static void handle(BuildingDelegatePacket pkt, ServerPlayer player) {
        BuildingSavedData sd = BuildingSavedData.get(player.server.overworld());
        if (sd == null) {
            Log.warn(TAG, "no BuildingSavedData — building delegate request dropped");
            return;
        }
        BuildingState state = sd.getBuilding(pkt.buildingId());
        if (state == null) {
            reject(player, "message.wandscape.delegate.no_building", "建筑不存在");
            return;
        }
        // 档位：委派 = MANAGER（无归属的野建筑沿用其它建筑入口的放行语义）
        if (state.getColonyId() != null
                && !com.wsteam.wandscape.content.colony.ownership.ColonyOwnership
                        .hasRole(player, state.getColonyId(), ColonyRole.MANAGER)) {
            com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(player, "delegate", "委派");
            return;
        }

        switch (pkt.action() == null ? "" : pkt.action()) {
            case ACTION_PICKER -> {
                // 与 set 同口径：不支持委派的建筑连候选列表都不给（客户端正常不会点，手改包也白搭）
                if (!BuildingDelegation.supports(state)) {
                    reject(player, "message.wandscape.delegate.unsupported", "该建筑类型不支持委派");
                    return;
                }
                Net.toPlayer(player, BuildingDelegateDataPacket.of(sd, state));
            }

            case ACTION_CLEAR -> {
                BuildingDelegation.clear(pkt.buildingId());
                pushRefresh(player, sd, pkt.buildingId());
            }

            case ACTION_SET -> {
                if (pkt.mageUuid() == null) return;
                BuildingDelegation.Result result = BuildingDelegation.delegate(pkt.buildingId(), pkt.mageUuid());
                if (result != BuildingDelegation.Result.OK) {
                    switch (result) {
                        case UNSUPPORTED ->
                                reject(player, "message.wandscape.delegate.unsupported", "该建筑类型不支持委派");
                        case UNDER_CONSTRUCTION -> reject(player,
                                "message.wandscape.delegate.under_construction", "建筑尚未完工，建成后才能委派");
                        case NO_MAGE -> reject(player, "message.wandscape.delegate.no_mage", "找不到该法师");
                        case WRONG_COLONY ->
                                reject(player, "message.wandscape.delegate.wrong_colony", "该法师不属于本镇");
                        case NO_BUILDING, OK ->
                                reject(player, "message.wandscape.delegate.no_building", "建筑不存在");
                    }
                    return;
                }
                pushRefresh(player, sd, pkt.buildingId());
            }

            default -> Log.warn(TAG, "unknown delegate action: {}", pkt.action());
        }
    }

    /**
     * 委派变更后把**建筑状态快照**推回：面板按钮态与悬停文案（已委派给谁）无需重开面板即可更新。
     * 候选列表刻意不推——那个列表只在玩家点「委派」打开选人框时才有用，推了反而会把
     * 刚关闭的选人框重新弹出来。
     */
    private static void pushRefresh(ServerPlayer player, BuildingSavedData sd, UUID buildingId) {
        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return;
        Net.toPlayer(player, BuildingDebugRequestPacket.buildResponse(player.server.overworld(), state));
    }

    private static void feedback(ServerPlayer player, String key, String fallback) {
        ScreenFeedbackPacket.send(player, I18n.name(key, fallback), true);
    }

    /** 拒止回执：错误反馈一律走屏幕 toast（上屏只留错误与完成反馈）。 */
    private static void reject(ServerPlayer player, String key, String fallback) {
        feedback(player, key, "§e[委派] " + fallback);
    }

    static void write(RegistryFriendlyByteBuf buf, BuildingDelegatePacket pkt) {
        buf.writeUtf(pkt.action != null ? pkt.action : "", 32);
        buf.writeUUID(pkt.buildingId);
        if (pkt.mageUuid != null) {
            buf.writeBoolean(true);
            buf.writeUUID(pkt.mageUuid);
        } else {
            buf.writeBoolean(false);
        }
    }

    static BuildingDelegatePacket read(RegistryFriendlyByteBuf buf) {
        String action = buf.readUtf(32);
        UUID buildingId = buf.readUUID();
        UUID mageUuid = buf.readBoolean() ? buf.readUUID() : null;
        return new BuildingDelegatePacket(action, buildingId, mageUuid);
    }
}
