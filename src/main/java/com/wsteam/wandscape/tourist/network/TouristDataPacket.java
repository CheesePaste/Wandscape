package com.wsteam.wandscape.tourist.network;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.tourist.entity.TouristEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet: opens / updates the tourist info screen.
 */
public record TouristDataPacket(
        int entityId,
        String touristName,
        int energy,
        int satisfaction,
        int level,
        int wallet,
        List<VisitEntry> recentVisits,
        String currentState,
        String targetBuildingName,
        String targetBuildingType,
        @Nullable BlockPos targetPos,
        int cooldownRemainingTicks
) implements CustomPacketPayload {

    public static final Type<TouristDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "tourist_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TouristDataPacket> STREAM_CODEC =
            StreamCodec.of(TouristDataPacket::write, TouristDataPacket::read);

    /**
     * Serializable visit entry — lightweight subset of {@link com.wsteam.wandscape.shared.data.VisitMemory}.
     * {@code buildingTypeId} lets the client resolve the localized name via {@code building.wandscape.<id>}.
     */
    public record VisitEntry(String buildingTypeId, String buildingName, String whatHappened, int satDelta, int energyDelta) {
        static void write(net.minecraft.network.FriendlyByteBuf buf, VisitEntry entry) {
            buf.writeUtf(entry.buildingTypeId != null ? entry.buildingTypeId : "");
            buf.writeUtf(entry.buildingName != null ? entry.buildingName : "");
            buf.writeUtf(entry.whatHappened != null ? entry.whatHappened : "");
            buf.writeInt(entry.satDelta);
            buf.writeInt(entry.energyDelta);
        }

        static VisitEntry read(net.minecraft.network.FriendlyByteBuf buf) {
            return new VisitEntry(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readInt());
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
        buf.writeInt(pkt.satisfaction);
        buf.writeInt(pkt.level);
        buf.writeInt(pkt.wallet);
        buf.writeCollection(pkt.recentVisits, (b, v) -> VisitEntry.write(b, v));
        buf.writeUtf(pkt.currentState);
        buf.writeUtf(pkt.targetBuildingName);
        buf.writeUtf(pkt.targetBuildingType);
        buf.writeBoolean(pkt.targetPos != null);
        if (pkt.targetPos != null) {
            buf.writeBlockPos(pkt.targetPos);
        }
        buf.writeInt(pkt.cooldownRemainingTicks);
    }

    static TouristDataPacket read(RegistryFriendlyByteBuf buf) {
        return new TouristDataPacket(
                buf.readInt(),
                buf.readUtf(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readList(b -> VisitEntry.read(b)),
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readBoolean() ? buf.readBlockPos() : null,
                buf.readInt()
        );
    }

    // ── Factory ──

    public static TouristDataPacket from(TouristEntity tourist) {
        List<VisitEntry> visits = tourist.getRecentVisits().stream()
                .map(v -> new VisitEntry(v.buildingTypeId(), v.buildingDisplayName(), v.whatHappened(),
                        v.satisfactionDelta(), v.energyDelta()))
                .toList();

        String targetName = "";
        String targetType = "";
        UUID targetId = tourist.getTargetBuildingId();
        if (targetId != null) {
            try {
                BuildingData data = WandscapeApis.getBuildingApi().getBuilding(targetId);
                if (data != null) {
                    String typeId = data.getBuildingTypeId();
                    if (typeId != null) {
                        targetType = typeId;
                        var config = BuildingConfigLoader.getInstance().get(typeId);
                        if (config != null && config.displayName() != null && !config.displayName().isEmpty()) {
                            targetName = config.displayName();
                        }
                    }
                }
            } catch (IllegalStateException e) {
                // Building module not loaded — leave target fields empty
            }
        }
        if (targetName.isEmpty()) {
            targetName = targetType;
        }

        int remaining = tourist.getServiceCooldownEndTick() - tourist.tickCount;

        return new TouristDataPacket(
                tourist.getId(),
                tourist.getTouristName(),
                tourist.getEnergy(),
                tourist.getSatisfaction(),
                tourist.getLevel(),
                tourist.getWallet(),
                visits,
                tourist.getCurrentState().name(),
                targetName,
                targetType,
                tourist.getCommuteTarget(),
                Math.max(0, remaining)
        );
    }
}
