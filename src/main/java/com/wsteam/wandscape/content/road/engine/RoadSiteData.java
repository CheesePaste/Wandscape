package com.wsteam.wandscape.content.road.engine;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.building.network.ConstructionSiteDataPacket;
import com.wsteam.wandscape.content.building.network.ConstructionSiteDataPacket.MaterialEntry;
import com.wsteam.wandscape.content.warehouse.system.ResourceSupplySystem;
import com.wsteam.wandscape.content.road.core.PathPoint;
import com.wsteam.wandscape.content.road.core.RoadEdge;
import com.wsteam.wandscape.content.road.data.RoadPreset;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Assembles a {@link ConstructionSiteDataPacket} for an under-construction road
 * edge so the client can render the same construction-site panel used by buildings.
 *
 * <p>Reuses the building supply-status accounting (warehouse stock + in-flight
 * synthesis) and time estimate, keyed off the road edge's persisted per-material
 * demand ({@link RoadEdge#getMaterialCounts()}).
 */
public final class RoadSiteData {

    private static final String TAG = "RoadSiteData";

    private RoadSiteData() {}

    /**
     * Build the road construction-site snapshot for a road edge. Only meaningful
     * server-side; the client just renders the returned packet.
     */
    public static ConstructionSiteDataPacket fromEdge(Level level, RoadEdge edge, UUID colonyId) {
        String tier = edge.getTier();
        RoadPreset preset = RoadPreset.parseOrGet(tier);
        String name = preset != null ? preset.displayName() : tier;
        boolean completed = edge.getStatus() == RoadEdge.EdgeStatus.COMPLETE;

        ColonyItemBank bank = ColonyItemBank.get(level);
        List<MaterialEntry> materials = new ArrayList<>();
        int sumMissing = 0;
        for (var e : edge.getMaterialCounts().entrySet()) {
            String key = e.getKey();
            int required = e.getValue();
            long stock = bank != null ? bank.count(colonyId, ItemKey.of(key, null)) : 0;
            int inFlight = ResourceSupplySystem.countSynthesizeInFlight(
                    key, colonyId, com.wsteam.wandscape.content.task.ecs.World.getActive());
            int status;
            if (stock >= required) {
                status = ConstructionSiteDataPacket.STATUS_READY;
            } else if (inFlight > 0) {
                status = ConstructionSiteDataPacket.STATUS_CRAFTING;
            } else {
                status = ConstructionSiteDataPacket.STATUS_PENDING;
            }
            long missing = Math.max(0L, required - stock);
            sumMissing += (int) Math.min(Integer.MAX_VALUE, missing);
            materials.add(new MaterialEntry(key, required, stock, status));
        }

        int workingCount = ResourceSupplySystem.countSynthesizingWorkstations(
                colonyId, com.wsteam.wandscape.content.task.ecs.World.getActive());
        // Remaining placement work is proxied by the planned footprint size.
        int remainingTiles = edge.getPlacedBlocks().size();
        ConstructionSiteDataPacket.Estimate est = ConstructionSiteDataPacket.Estimate.of(
                sumMissing, remainingTiles, workingCount,
                com.wsteam.wandscape.foundation.util.BalanceValues.workstationCraftTicksPerUnit(),
                com.wsteam.wandscape.foundation.util.BalanceValues.constructionPlaceTicksPerUnit());

        BlockPos pos = representativePos(edge);
        return new ConstructionSiteDataPacket(
                edge.getEdgeId(), pos, tier, name, materials,
                est.startTicks(), est.completeTicks(), est.canEstimate(), completed, "",
                ConstructionSiteDataPacket.KIND_ROAD);
    }

    /** A block position guaranteed inside the edge footprint (its first planned tile). */
    private static BlockPos representativePos(RoadEdge edge) {
        for (PathPoint p : edge.getPlacedBlocks()) {
            return new BlockPos(p.x(), p.y(), p.z());
        }
        Log.warn(TAG, "RoadEdge {} has no placed blocks — using origin", edge.getEdgeId());
        return new BlockPos(0, 0, 0);
    }
}
