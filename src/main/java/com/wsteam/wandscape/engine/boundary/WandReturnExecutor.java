package com.wsteam.wandscape.engine.boundary;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.WandCarrier;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.OpExecutor;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadRouter;
import com.wsteam.wandscape.core.road.RouteSegment;
import com.wsteam.wandscape.engine.transport.ItemTransportManager;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.warehouse.ColonyItemBank;
import com.wsteam.wandscape.wand.internal.WandPresetLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Executes {@link AtomicOp.WandReturnOp}: unequips a wand from the NPC
 * and returns it to the colony warehouse via visual transport animation,
 * updating the ECS {@link WandCarrier} component.
 */
public class WandReturnExecutor implements OpExecutor<AtomicOp.WandReturnOp> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WAND_ITEM_ID = "wandscape:wand";

    private final WandPresetLoader presetLoader;
    private final ItemTransportManager transporter;

    public WandReturnExecutor(WandPresetLoader presetLoader, ItemTransportManager transporter) {
        this.presetLoader = presetLoader;
        this.transporter = transporter;
    }

    @Override
    public Class<AtomicOp.WandReturnOp> opType() {
        return AtomicOp.WandReturnOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.WandReturnOp op, World world, long npcId) {
        String presetId = op.wandItemId();
        LOGGER.info("[WandReturn] ▶ execute called: preset={} npcId={}", presetId, npcId);

        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isRemoved()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("[WandReturn] NPC " + npcId + " not found in entity bridge"));
        }

        ColonyItemBank bank = ColonyItemBank.get(npc.level());
        if (bank == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("[WandReturn] ColonyItemBank not available"));
        }

        UUID colonyId = resolveColonyId(npc, world);

        // 1. Read current WandCarrier
        WandCarrier current = world.get(npcId, WandCarrier.class);
        if (current == null || !current.equippedWandIds().contains(presetId)) {
            LOGGER.debug("[WandReturn] preset {} not equipped on NPC {}, skipping", presetId, npcId);
            return CompletableFuture.completedFuture(null);
        }

        // 2. Find the preset for this wand (needed for NBT and visual)
        var preset = presetLoader.getPreset(presetId);
        if (preset == null) {
            LOGGER.warn("[WandReturn] unknown wand preset: {}, unequipping without visual", presetId);
            current.unequip(presetId, java.util.Map.of());
            world.addComponent(npcId, current);
            return CompletableFuture.completedFuture(null);
        }

        // 3. Notify WandLifecycle: wand is in transit back to warehouse
        var lifecycle = world.wandLifecycle;
        if (lifecycle != null) {
            lifecycle.startReturn(colonyId, presetId);
        }

        // 4. Unequip from carrier immediately — wand is now "in transit"
        java.util.Map<String, WandCarrier.WandCapProvider> knownWands = new java.util.HashMap<>();
        for (String id : current.equippedWandIds()) {
            if (id.equals(presetId)) continue;
            var p = presetLoader.getPreset(id);
            if (p != null) {
                knownWands.put(id, parseProvider(p.nbt()));
            }
        }
        current.unequip(presetId, knownWands);
        world.addComponent(npcId, current);

        // 4. Restore NPC default hand item
        npc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Wandscape.WAND.get()));

        // 5. Find destination: nearest storage building
        BlockPos npcPos = npc.blockPosition();
        BlockPos storagePos = findNearestStorage(colonyId, npcPos);
        ItemKey key = ItemKey.of(WAND_ITEM_ID, preset.nbt().copy());

        // 6. Start visual transport: wand flies from NPC back to warehouse
        //    Use road network if available.
        BlockPos destPos = storagePos != null ? storagePos : npcPos;
        List<RouteSegment> route = planRoute(colonyId, npcPos, destPos);
        CompletableFuture<Void> transportFuture = transporter.send(
                key, npcPos, destPos, npc.level(), npcId, route,
                true /* ownsItem: wand was unequipped, must return on cancel */);

        // 7. On arrival: add to warehouse + visual feedback
        transportFuture.thenRun(() -> {
            bank.add(colonyId, key, 1);

            if (!npc.level().isClientSide) {
                for (int i = 0; i < 8; i++) {
                    npc.level().addParticle(ParticleTypes.POOF,
                            npc.getX() + (npc.getRandom().nextDouble() - 0.5) * 1.0,
                            npc.getY() + npc.getRandom().nextDouble() * 2.0,
                            npc.getZ() + (npc.getRandom().nextDouble() - 0.5) * 1.0,
                            0, 0.05, 0);
                }
            }

            LOGGER.info("[WandReturn] 📦 NPC #{} 归还 '{}' → 仓库 剩余能力: {}",
                    npcId, presetId, current.capabilities().keySet());

            // Notify WandLifecycle: wand has arrived at warehouse
            if (lifecycle != null) {
                lifecycle.confirmReturn(colonyId, presetId);
            }
        });

        return transportFuture;
    }

    /** Parse a WandCapProvider from preset NBT. */
    private static WandCarrier.WandCapProvider parseProvider(net.minecraft.nbt.CompoundTag nbt) {
        var behaviors = nbt.getCompound("behaviors");
        java.util.Map<com.wsteam.wandscape.core.types.BehaviourTag,
                com.wsteam.wandscape.core.types.BehaviourLevel> caps = new java.util.HashMap<>();
        for (String key : behaviors.getAllKeys()) {
            var tag = mapFromNbtKey(key);
            if (tag != null) {
                caps.put(tag, com.wsteam.wandscape.core.types.BehaviourLevel.of(
                        behaviors.getInt(key)));
            }
        }
        float eff = nbt.contains("mana_cost_multiplier")
                ? nbt.getFloat("mana_cost_multiplier") : 1.0f;
        int range = nbt.contains("range") ? nbt.getInt("range") : 1;
        return new WandCarrier.WandCapProvider(caps, eff, range);
    }

    private static com.wsteam.wandscape.core.types.BehaviourTag mapFromNbtKey(String key) {
        return switch (key) {
            case "building"           -> com.wsteam.wandscape.core.types.BehaviourTag.BUILDING;
            case "farming"            -> com.wsteam.wandscape.core.types.BehaviourTag.FARMING;
            case "mining"             -> com.wsteam.wandscape.core.types.BehaviourTag.MINING;
            case "logging"            -> com.wsteam.wandscape.core.types.BehaviourTag.LOGGING;
            case "crafting"           -> com.wsteam.wandscape.core.types.BehaviourTag.CRAFTING;
            case "gathering"          -> com.wsteam.wandscape.core.types.BehaviourTag.GATHERING;
            case "ritual"             -> com.wsteam.wandscape.core.types.BehaviourTag.RITUAL;
            case "entity_interaction" -> com.wsteam.wandscape.core.types.BehaviourTag.ENTITY_INTERACTION;
            default                   -> null;
        };
    }

    private static UUID resolveColonyId(WandscapeNpc npc, World world) {
        var member = world.get(npc.ecsEntityId, ColonyMember.class);
        if (member != null && member.colonyId() != null) {
            return member.colonyId();
        }
        return npc.colonyId != null ? npc.colonyId : new UUID(0, 0);
    }

    private static BlockPos findNearestStorage(UUID colonyId, BlockPos npcPos) {
        BuildingApi buildingApi = WandscapeApis.getBuildingApi();
        if (buildingApi == null) return null;

        var storageIds = buildingApi.getBuildingsByCategory(colonyId, "storage");
        if (storageIds == null || storageIds.isEmpty()) return null;

        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (UUID id : storageIds) {
            BuildingData bd = buildingApi.getBuilding(id);
            if (bd == null || bd.isShutdown()) continue;
            BlockPos pos = bd.getPosition();
            double dist = pos.distSqr(npcPos);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = pos;
            }
        }
        return nearest;
    }

    /** Plan a road-assisted route between two positions. Returns empty if no road. */
    private static List<RouteSegment> planRoute(UUID colonyId, BlockPos from, BlockPos to) {
        try {
            var roadApi = WandscapeApis.getRoadApi();
            if (roadApi == null) return List.of();
            var network = roadApi.getNetwork(colonyId);
            return RoadRouter.plan(network,
                    new PathPoint(from.getX(), from.getY(), from.getZ()),
                    new PathPoint(to.getX(), to.getY(), to.getZ()));
        } catch (Exception e) {
            return List.of();
        }
    }
}
