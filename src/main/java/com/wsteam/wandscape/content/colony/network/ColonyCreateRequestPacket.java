package com.wsteam.wandscape.content.colony.network;

import com.wsteam.wandscape.content.command.ColonyCommand;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.colony.ActiveColonyTracker;
import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→Server: Create a colony when the player names a town hall that was
 * built before any colony existed.
 *
 * <p>The player right-clicks an intact town hall with no colony nearby, the
 * client shows a naming screen, and upon confirm sends this packet with the
 * town hall's anchor and the chosen name. The server routes to
 * {@link ColonyCommand#createColonyAt} — the same core logic as
 * {@code /wandscape colony create} — then links the town hall to the new
 * colony.
 */
public record ColonyCreateRequestPacket(BlockPos townHallAnchor, String name)
        implements CustomPacketPayload {

    private static final String TAG = "ColonyCreateRequestPacket";

    /** 拒止反馈的操作描述（{@link ColonyOwnership#deny} 的两次查表）。 */
    private static final String WHAT_KEY = "town_hall";
    private static final String WHAT_FALLBACK = "市政厅";

    public static final Type<ColonyCreateRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "colony_create_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyCreateRequestPacket> STREAM_CODEC =
            StreamCodec.of(ColonyCreateRequestPacket::write, ColonyCreateRequestPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(ColonyCreateRequestPacket packet, ServerPlayer player) {
        if (packet.name == null || packet.name.trim().isEmpty()) {
            Log.warn(TAG, "[Colony] Colony create request with empty name ignored");
            return;
        }
        String name = packet.name.trim().length() > 30
                ? packet.name.trim().substring(0, 30) : packet.name.trim();

        ServerLevel level = player.serverLevel();
        ColonyApi colonyApi = WandscapeApis.getColonyApi();

        // 建镇 vs 关联，只看**这座市政厅自己**有没有归属（BuildingState.colonyId）：
        // 既不问玩家已经拥有几座镇，也不用空间距离判——「最近原点 ≤256」会把紧邻新镇的市政厅
        // 串到邻居头上（getColonyId(BlockPos) 的语义，不是归属）。这与「建镇 = 对无主市政厅右键命名」
        // 的裁定一致：两个意图由世界动作区分，与「有没有自己的镇」无关。
        var buildingApi = WandscapeApis.getBuildingApi();
        BuildingState townHall = buildingApi != null ? buildingApi.getBuildingAt(packet.townHallAnchor) : null;
        if (townHall == null) {
            sendMessage(player, I18n.name("message.wandscape.colony.create_failed",
                    "[魔法小镇] 创建小镇失败。"));
            Log.warn(TAG, "[Colony] Create request for anchor with no building: {}",
                    packet.townHallAnchor);
            return;
        }

        UUID anchorColony = townHall.getColonyId();
        if (anchorColony != null) {
            // 已有归属：这里只意味着「确认/关联这座市政厅」，不是建镇。需要该镇 MANAGER+
            // ——别人镇范围内的市政厅不能凭一次右键认领。
            if (!ColonyOwnership.hasRole(player, anchorColony, ColonyRole.MANAGER)) {
                ColonyOwnership.deny(player, WHAT_KEY, WHAT_FALLBACK);
                Log.warn(TAG, "[Colony] {} tried to attach town hall {} to colony {} without MANAGER+",
                        player.getGameProfile().getName(), packet.townHallAnchor, shortId(anchorColony));
                return;
            }
            linkTownHall(packet.townHallAnchor, anchorColony);
            sendMessage(player, I18n.name("message.wandscape.colony.attached",
                    "[魔法小镇] 市政厅已关联至现有小镇。"));
            return;
        }

        // 无主市政厅 → 命名即建镇。**一人可拥有多座镇**（用户明确要求），故这里没有
        // 「已拥有小镇就不能再建」的守卫；两镇可相距任意近，归属跟放置者。
        ColonyCommand.ColonyCreateOutcome outcome =
                ColonyCommand.createColonyAt(level, packet.townHallAnchor, name, player.getUUID());
        if (outcome == null || !outcome.success()) {
            sendMessage(player, outcome != null ? outcome.message()
                    : I18n.name("message.wandscape.colony.create_failed", "[魔法小镇] 创建小镇失败。"));
            return;
        }

        // Link the town hall to the just-created colony
        UUID colonyId = colonyApi.getColonyId(packet.townHallAnchor);
        if (colonyId != null) {
            linkTownHall(packet.townHallAnchor, colonyId);
            Log.info(TAG, "[Colony] Town hall at {} linked to new colony {}",
                    packet.townHallAnchor, shortId(colonyId));
        } else {
            Log.warn(TAG, "[Colony] Created a colony at {} but could not resolve its id; "
                    + "town hall left unlinked", packet.townHallAnchor);
        }

        // 建镇成功即把这座新镇设为当前镇（用户要求；一人可多座），随后走统一入口一次推齐
        // 顶栏统计 + 建筑边界 + 教程 + 花名册 + 列表。原来那两发分散推送（BuildingAreaSyncPacket /
        // tutorial）已并入 ColonyContextSync.push —— 少了这一步，面板高亮与顶栏会停在上一座镇。
        if (colonyId != null && !ActiveColonyTracker.setActive(player, colonyId)) {
            // 建镇者恒为 OWNER，走到这里说明花名册写入异常；仍推送（回退上一座镇），不静默。
            Log.warn(TAG, "[Colony] Created colony {} but could not make it the active colony for {}",
                    shortId(colonyId), player.getGameProfile().getName());
        }
        ColonyContextSync.push(player);
    }

    private static void linkTownHall(BlockPos anchor, UUID colonyId) {
        var buildingApi = com.wsteam.wandscape.api.WandscapeApis.getBuildingApi();
        if (buildingApi == null) return;
        var building = buildingApi.getBuildingAt(anchor);
        if (building instanceof BuildingState state) {
            state.setColonyId(colonyId);
            com.wsteam.wandscape.content.colony.ColonyApiImpl.get().assignColonyIfPossible(building);
            com.wsteam.wandscape.content.colony.ColonyApiImpl.get().onBuildingIntact(building);
        }
    }

    private static void sendMessage(ServerPlayer player, Component message) {
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    private static String shortId(UUID id) {
        return id != null ? id.toString().substring(0, 8) : "none";
    }

    static void write(RegistryFriendlyByteBuf buf, ColonyCreateRequestPacket pkt) {
        buf.writeLong(pkt.townHallAnchor.asLong());
        buf.writeUtf(pkt.name != null ? pkt.name : "");
    }

    static ColonyCreateRequestPacket read(RegistryFriendlyByteBuf buf) {
        return new ColonyCreateRequestPacket(BlockPos.of(buf.readLong()), buf.readUtf());
    }
}
