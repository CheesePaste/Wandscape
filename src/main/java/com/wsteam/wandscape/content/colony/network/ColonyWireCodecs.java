package com.wsteam.wandscape.content.colony.network;

import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 花名册网络包的线格式基元：可空 UUID / 可空档位 / 可空动作。
 *
 * <p>存在的理由：三个包（成员动作、花名册同步、小镇列表）都要读写同一批可空字段，
 * 各写一份就会各自漂移（一个用 ordinal、一个用名称，版本一升就两端对不上）。可空一律写成
 * 「boolean 存在位 + 值」，与既有包（{@code ColonyStatsSyncPacket} 的 colonyId）一致。
 *
 * <p>枚举走**名称**而不是 {@code writeEnum} 的 ordinal：档位/动作枚举将来插值或重排时，
 * ordinal 会让旧客户端把 MANAGER 读成 MEMBER（静默越权）；名称不匹配只解析成 null，
 * 调用方按「非成员 / 无效请求」拒绝，失败方向是安全的。
 */
final class ColonyWireCodecs {

    private static final String TAG = "ColonyWireCodecs";

    private ColonyWireCodecs() {}

    // ── UUID ──

    static void writeNullableUuid(FriendlyByteBuf buf, @Nullable UUID id) {
        buf.writeBoolean(id != null);
        if (id != null) buf.writeUUID(id);
    }

    @Nullable
    static UUID readNullableUuid(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUUID() : null;
    }

    // ── 档位 ──

    static void writeRole(FriendlyByteBuf buf, @Nullable ColonyRole role) {
        buf.writeBoolean(role != null);
        if (role != null) buf.writeUtf(role.name());
    }

    @Nullable
    static ColonyRole readRole(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) return null;
        String name = buf.readUtf();
        ColonyRole role = ColonyRole.byName(name);
        if (role == null) Log.warn(TAG, "Unknown colony role on the wire: {}", name);
        return role;
    }

    // ── 成员动作 ──

    static void writeAction(FriendlyByteBuf buf, @Nullable ColonyMemberActionPacket.Action action) {
        buf.writeBoolean(action != null);
        if (action != null) buf.writeUtf(action.name());
    }

    @Nullable
    static ColonyMemberActionPacket.Action readAction(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) return null;
        String name = buf.readUtf();
        for (ColonyMemberActionPacket.Action action : ColonyMemberActionPacket.Action.values()) {
            if (action.name().equals(name)) return action;
        }
        Log.warn(TAG, "Unknown colony member action on the wire: {}", name);
        return null;
    }
}
