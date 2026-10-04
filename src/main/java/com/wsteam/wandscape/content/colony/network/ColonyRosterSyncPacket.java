package com.wsteam.wandscape.content.colony.network;

import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client: 一座小镇的花名册快照，按**收件人视角**渲染。
 *
 * <p>{@code myRole} 是收件人在该镇的档位（非成员为 null——被邀请但还没接受的人正是这一档），
 * 因为同一份花名册对不同人显示不同的可操作按钮，服务端一次算准比客户端自己推更省事、也不会算漏。
 *
 * <p>{@code pendingInvites} 与 {@code colonyId} **无关**：它是「发给收件人的待处理邀请总表」
 * （跨所有小镇）。放在这里是因为待处理邀请的消费方就是同一个面板，再开一个包体只多一条登记行。
 *
 * <p>侧边栏打开时不需要请求包：服务端在玩家登录时、以及每次花名册变更后主动推（见
 * {@code ColonyRosterSyncService}），客户端只消费。
 */
public record ColonyRosterSyncPacket(UUID colonyId, String colonyName, @Nullable ColonyRole myRole,
                                     List<Member> members, List<Invite> pendingInvites)
        implements CustomPacketPayload {

    /** 花名册一行：玩家的 UUID、显示名与档位。 */
    public record Member(UUID id, String name, ColonyRole role) {}

    /** 发给收件人的一条待处理邀请。 */
    public record Invite(UUID colonyId, String colonyName, String inviterName, ColonyRole role) {}

    private static final String TAG = "ColonyRosterSyncPacket";

    public static final Type<ColonyRosterSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "colony_roster_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyRosterSyncPacket> STREAM_CODEC =
            StreamCodec.of(ColonyRosterSyncPacket::write, ColonyRosterSyncPacket::read);

    public ColonyRosterSyncPacket {
        colonyName = colonyName == null ? "" : colonyName;
        members = members == null ? List.of() : List.copyOf(members);
        pendingInvites = pendingInvites == null ? List.of() : List.copyOf(pendingInvites);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleClient(ColonyRosterSyncPacket packet) {
        try {
            ColonyPanelClientState.applyRoster(packet);
        } catch (Throwable t) {
            // 客户端只做展示：单个包解析失败绝不能崩游戏端，记警告后丢弃。
            Log.warn(TAG, "Failed to apply roster sync: {}", t.toString());
        }
    }

    static void write(RegistryFriendlyByteBuf buf, ColonyRosterSyncPacket pkt) {
        ColonyWireCodecs.writeNullableUuid(buf, pkt.colonyId);
        buf.writeUtf(pkt.colonyName);
        ColonyWireCodecs.writeRole(buf, pkt.myRole);
        buf.writeCollection(pkt.members, (b, member) -> writeMember(b, member));
        buf.writeCollection(pkt.pendingInvites, (b, invite) -> writeInvite(b, invite));
    }

    static ColonyRosterSyncPacket read(RegistryFriendlyByteBuf buf) {
        UUID colonyId = ColonyWireCodecs.readNullableUuid(buf);
        String colonyName = buf.readUtf();
        ColonyRole myRole = ColonyWireCodecs.readRole(buf);
        List<Member> members = buf.readList(ColonyRosterSyncPacket::readMember);
        List<Invite> pendingInvites = buf.readList(ColonyRosterSyncPacket::readInvite);
        return new ColonyRosterSyncPacket(colonyId, colonyName, myRole, members, pendingInvites);
    }

    private static void writeMember(FriendlyByteBuf buf, Member member) {
        buf.writeUUID(member.id());
        buf.writeUtf(member.name() != null ? member.name() : "");
        ColonyWireCodecs.writeRole(buf, member.role());
    }

    private static Member readMember(FriendlyByteBuf buf) {
        return new Member(buf.readUUID(), buf.readUtf(), ColonyWireCodecs.readRole(buf));
    }

    private static void writeInvite(FriendlyByteBuf buf, Invite invite) {
        ColonyWireCodecs.writeNullableUuid(buf, invite.colonyId());
        buf.writeUtf(invite.colonyName() != null ? invite.colonyName() : "");
        buf.writeUtf(invite.inviterName() != null ? invite.inviterName() : "");
        ColonyWireCodecs.writeRole(buf, invite.role());
    }

    private static Invite readInvite(FriendlyByteBuf buf) {
        return new Invite(ColonyWireCodecs.readNullableUuid(buf), buf.readUtf(), buf.readUtf(),
                ColonyWireCodecs.readRole(buf));
    }
}
