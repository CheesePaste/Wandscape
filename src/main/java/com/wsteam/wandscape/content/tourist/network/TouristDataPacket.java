package com.wsteam.wandscape.tourist.network;

import com.wsteam.wandscape.shared.data.Activity;
import com.wsteam.wandscape.tourist.entity.TouristEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet: opens / updates the tourist info screen.
 *
 * <p>Block 2：三条需求条（sat/need）+ 画像 + 活动 + 停留 + 钱包/旅费；去掉单一 satisfaction 与调试字段。
 */
public record TouristDataPacket(
        int entityId,
        String touristName,
        int energy,
        int level,
        int wallet,
        int travelFund,
        int comfortSat,
        int magicSat,
        int wonderSat,
        int comfortNeed,
        int magicNeed,
        int wonderNeed,
        @Nullable Activity currentActivity,
        int nightsStayed,
        int stayDaysTotal,
        List<VisitEntry> recentVisits
) implements CustomPacketPayload {

    public static final Type<TouristDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "tourist_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TouristDataPacket> STREAM_CODEC =
            StreamCodec.of(TouristDataPacket::write, TouristDataPacket::read);

    /**
     * Serializable visit entry — lightweight subset of {@link com.wsteam.wandscape.shared.data.VisitMemory}.
     * {@code buildingTypeId} lets the client resolve the localized name via {@code building.wandscape.<id>}.
     * Block 2：满意度 satDelta → 三维增量（comfort/magic/wonder）。
     */
    public record VisitEntry(String buildingTypeId, String buildingName, String whatHappened,
                             int comfortDelta, int magicDelta, int wonderDelta, int energyDelta) {
        static void write(net.minecraft.network.FriendlyByteBuf buf, VisitEntry entry) {
            buf.writeUtf(entry.buildingTypeId != null ? entry.buildingTypeId : "");
            buf.writeUtf(entry.buildingName != null ? entry.buildingName : "");
            buf.writeUtf(entry.whatHappened != null ? entry.whatHappened : "");
            buf.writeInt(entry.comfortDelta);
            buf.writeInt(entry.magicDelta);
            buf.writeInt(entry.wonderDelta);
            buf.writeInt(entry.energyDelta);
        }

        static VisitEntry read(net.minecraft.network.FriendlyByteBuf buf) {
            return new VisitEntry(buf.readUtf(), buf.readUtf(), buf.readUtf(),
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    private static Consumer<TouristDataPacket> clientHandler;

    public static void setClientHandler(Consumer<TouristDataPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(TouristDataPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, TouristDataPacket pkt) {
        buf.writeInt(pkt.entityId);
        buf.writeUtf(pkt.touristName);
        buf.writeInt(pkt.energy);
        buf.writeInt(pkt.level);
        buf.writeInt(pkt.wallet);
        buf.writeInt(pkt.travelFund);
        buf.writeInt(pkt.comfortSat);
        buf.writeInt(pkt.magicSat);
        buf.writeInt(pkt.wonderSat);
        buf.writeInt(pkt.comfortNeed);
        buf.writeInt(pkt.magicNeed);
        buf.writeInt(pkt.wonderNeed);
        buf.writeBoolean(pkt.currentActivity != null);
        if (pkt.currentActivity != null) {
            buf.writeEnum(pkt.currentActivity);
        }
        buf.writeInt(pkt.nightsStayed);
        buf.writeInt(pkt.stayDaysTotal);
        buf.writeCollection(pkt.recentVisits, (b, v) -> VisitEntry.write(b, v));
    }

    static TouristDataPacket read(RegistryFriendlyByteBuf buf) {
        return new TouristDataPacket(
                buf.readInt(),
                buf.readUtf(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readBoolean() ? buf.readEnum(Activity.class) : null,
                buf.readInt(),
                buf.readInt(),
                buf.readList(b -> VisitEntry.read(b))
        );
    }

    // ── Factory ──

    public static TouristDataPacket from(TouristEntity tourist) {
        List<VisitEntry> visits = tourist.getRecentVisits().stream()
                .map(v -> new VisitEntry(v.buildingTypeId(), v.buildingDisplayName(), v.whatHappened(),
                        v.comfortDelta(), v.magicDelta(), v.wonderDelta(), v.energyDelta()))
                .toList();

        long stayTicks = Math.max(1L, tourist.getDepartureDeadline() - tourist.getArrivalTime());
        int stayDaysTotal = Math.max(1, (int) (stayTicks / 24000L));

        return new TouristDataPacket(
                tourist.getId(),
                tourist.getTouristName(),
                tourist.getEnergy(),
                tourist.getLevel(),
                tourist.getWallet(),
                tourist.getTravelFund(),
                tourist.getComfortSat(),
                tourist.getMagicSat(),
                tourist.getWonderSat(),
                tourist.getComfortNeed(),
                tourist.getMagicNeed(),
                tourist.getWonderNeed(),
                tourist.getCurrentActivity(),
                tourist.getNightsStayed(),
                stayDaysTotal,
                visits
        );
    }
}
