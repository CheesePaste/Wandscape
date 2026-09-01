package com.wsteam.wandscape.content.building.network;

import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildCompleteListener;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.building.internal.EnqueueHelper;
import com.wsteam.wandscape.content.warehouse.system.ResourceSupplySystem;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→client packet for the construction-site panel of a not-yet-completed building.
 *
 * <p>Carries the full blueprint material demand (only warehouse-supplied blocks), the
 * warehouse stock per block, the per-block supply status (ready / being synthesized /
 * pending), and the estimated start &amp; completion ticks. Assembly happens on the
 * server in {@link #from(Level, BuildingState)}; the client just renders. {@code
 * buildingTypeId} lets the client resolve the localized building name via
 * {@code building.wandscape.<id>}; {@code buildingName} is the raw display-name fallback.
 */
public record ConstructionSiteDataPacket(
        UUID buildingId,
        BlockPos pos,
        String buildingTypeId,
        String buildingName,
        List<MaterialEntry> materials,
        int estStartTicks,
        int estCompleteTicks,
        boolean canEstimate,
        boolean completed,
        String creator,
        int kind
) implements CustomPacketPayload {

    /** Panel target is an under-construction building. */
    public static final int KIND_BUILDING = 0;
    /** Panel target is an under-construction road edge. */
    public static final int KIND_ROAD = 1;

    public static final int STATUS_READY = 0;     // 已备齐：仓库储量充足
    public static final int STATUS_CRAFTING = 1;  // 制作中：不足，但工作站正在合成
    public static final int STATUS_PENDING = 2;   // 待制作：不足，且没有工作站在做

    private static final String TAG = "ConstructionSiteDataPacket";

    public static final Type<ConstructionSiteDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "construction_site_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConstructionSiteDataPacket> STREAM_CODEC =
            StreamCodec.of(ConstructionSiteDataPacket::write, ConstructionSiteDataPacket::read);

    /** A single block type's demand + supply status in the construction-site list. */
    public record MaterialEntry(String blockId, int required, long stock, int status) {}

    /** Pure time estimate — {@code startTicks} is when all materials are assembled and
     *  placement can begin; {@code completeTicks} adds per-block construction time for the
     *  {@code remainingBlocks} still to place (remaining time, not total build duration). */
    public record Estimate(int startTicks, int completeTicks, boolean canEstimate) {
        public static Estimate of(int sumMissing, int remainingBlocks, int workingCount,
                                  int craftCD, int placeCD) {
            boolean can = true;
            int start;
            if (sumMissing <= 0) {
                start = 0; // 材料已备齐，随时可开工
            } else if (workingCount <= 0) {
                can = false; // 有缺口但无工作站在做 → 无法估算
                start = 0;
            } else {
                start = (int) Math.ceil((double) sumMissing * craftCD / workingCount);
            }
            return new Estimate(start, start + placeCD * remainingBlocks, can);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server-side assembly ──

    /**
     * 5 秒快照 CD：同一建筑在窗口期内重复打开面板直接返回上次快照（时间不变）。
     * 面板只在打开时算一次，而 {@code findDamagedBlocks} 对超大建筑（10 万方块）单次
     * 扫描约 5-10ms，若不缓存，玩家反复右键会持续触发世界扫描拖垮服务端。
     */
    private static final Map<UUID, CacheEntry> SNAPSHOT_CACHE = new HashMap<>();
    private static final long SNAPSHOT_CACHE_TTL_MS = 5_000;

    private record CacheEntry(ConstructionSiteDataPacket packet, long timestampMs) {}

    private static void pruneStaleCache() {
        long now = System.currentTimeMillis();
        SNAPSHOT_CACHE.entrySet().removeIf(e -> now - e.getValue().timestampMs() >= SNAPSHOT_CACHE_TTL_MS);
    }

    /**
     * Assemble the construction-site snapshot for a building from its blueprint demand,
     * the colony warehouse stock, and in-flight workstation synthesis.
     */
    public static ConstructionSiteDataPacket from(Level level, BuildingState state) {
        UUID buildingId = state.getBuildingId();
        pruneStaleCache();
        CacheEntry cached = SNAPSHOT_CACHE.get(buildingId);
        if (cached != null) {
            return cached.packet(); // 5s CD 内，返回同一快照，时间不变
        }

        UUID colonyId = state.getColonyId() != null ? state.getColonyId() : new UUID(0, 0);
        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        String name = config != null ? config.displayName() : state.getBuildingTypeId();

        ColonyItemBank bank = ColonyItemBank.get(level);
        List<MaterialEntry> materials = new ArrayList<>();
        int sumMissing = 0;

        // 剩余未建方块：仅面板打开时算一次（findDamagedBlocks 对超大建筑有成本，
        // 故不做实时刷新）。
        int remainingBlocks = 0;
        if (config != null) {
            remainingBlocks = BuildCompleteListener.findDamagedBlocks(
                    level, state.getAnchor(), config, state.getRotationSteps()).size();
            for (var e : EnqueueHelper.computeMaterialCounts(config).entrySet()) {
                String pureId = e.getKey();
                int required = e.getValue();
                long stock = bank != null ? bank.count(colonyId, ItemKey.of(pureId, null)) : 0;
                int inFlight = ResourceSupplySystem.countSynthesizeInFlight(
                        pureId, colonyId, com.wsteam.wandscape.content.task.ecs.World.getActive());
                int status;
                if (stock >= required) {
                    status = STATUS_READY;
                } else if (inFlight > 0) {
                    status = STATUS_CRAFTING;
                } else {
                    status = STATUS_PENDING;
                }
                long missing = Math.max(0L, required - stock);
                sumMissing += (int) Math.min(Integer.MAX_VALUE, missing);
                materials.add(new MaterialEntry(pureId, required, stock, status));
            }
        }

        int workingCount = ResourceSupplySystem.countSynthesizingWorkstations(
                colonyId, com.wsteam.wandscape.content.task.ecs.World.getActive());
        Estimate est = Estimate.of(sumMissing, remainingBlocks, workingCount,
                com.wsteam.wandscape.foundation.util.BalanceValues.workstationCraftTicksPerUnit(),
                com.wsteam.wandscape.foundation.util.BalanceValues.constructionPlaceTicksPerUnit());

        ConstructionSiteDataPacket packet = new ConstructionSiteDataPacket(
                buildingId, state.getAnchor(), state.getBuildingTypeId(), name,
                materials, est.startTicks(), est.completeTicks(), est.canEstimate(),
                state.hasEverCompleted(), config != null ? config.creator() : "",
                KIND_BUILDING);
        SNAPSHOT_CACHE.put(buildingId, new CacheEntry(packet, System.currentTimeMillis()));
        return packet;
    }

    // ── Client handler ──

    private static Consumer<ConstructionSiteDataPacket> clientHandler;

    public static void setClientHandler(Consumer<ConstructionSiteDataPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(ConstructionSiteDataPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        } else {
            Log.warn(TAG, "ConstructionSiteDataPacket: no client handler registered");
        }
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, ConstructionSiteDataPacket pkt) {
        buf.writeUUID(pkt.buildingId);
        buf.writeBlockPos(pkt.pos);
        buf.writeUtf(pkt.buildingTypeId);
        buf.writeUtf(pkt.buildingName);
        buf.writeVarInt(pkt.materials.size());
        for (MaterialEntry e : pkt.materials) {
            buf.writeUtf(e.blockId);
            buf.writeVarInt(e.required);
            buf.writeVarLong(e.stock);
            buf.writeVarInt(e.status);
        }
        buf.writeVarInt(pkt.estStartTicks);
        buf.writeVarInt(pkt.estCompleteTicks);
        buf.writeBoolean(pkt.canEstimate);
        buf.writeBoolean(pkt.completed);
        buf.writeUtf(pkt.creator != null ? pkt.creator : "");
        buf.writeByte(pkt.kind);
    }

    static ConstructionSiteDataPacket read(RegistryFriendlyByteBuf buf) {
        UUID buildingId = buf.readUUID();
        BlockPos pos = buf.readBlockPos();
        String typeId = buf.readUtf();
        String name = buf.readUtf();
        int n = buf.readVarInt();
        List<MaterialEntry> materials = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            materials.add(new MaterialEntry(
                    buf.readUtf(), buf.readVarInt(), buf.readVarLong(), buf.readVarInt()));
        }
        int start = buf.readVarInt();
        int complete = buf.readVarInt();
        boolean can = buf.readBoolean();
        boolean done = buf.readBoolean();
        String creator = buf.readUtf();
        int kind = buf.readByte();
        return new ConstructionSiteDataPacket(buildingId, pos, typeId, name, materials, start, complete, can, done, creator, kind);
    }
}
