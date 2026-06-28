package com.wsteam.wandscape.engine.boundary;

import com.wsteam.wandscape.core.component.WandCarrier;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.OpExecutor;
import com.wsteam.wandscape.core.types.BehaviourLevel;
import com.wsteam.wandscape.core.types.BehaviourTag;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadRouter;
import com.wsteam.wandscape.core.road.RouteSegment;
import com.wsteam.wandscape.engine.road.RoadRoutingHelper;
import com.wsteam.wandscape.engine.transport.ItemTransportManager;
import com.wsteam.wandscape.warehouse.ColonyItemBank;
import com.wsteam.wandscape.wand.internal.WandPresetLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Executes {@link AtomicOp.WandEquipOp}: consumes a wand from the colony warehouse
 * and equips it on the NPC, updating the ECS {@link WandCarrier} component.
 */
public class WandEquipExecutor implements OpExecutor<AtomicOp.WandEquipOp> {

    private static final String TAG = "WandEquipExecutor";
    private static final String WAND_ITEM_ID = "wandscape:wand";

    private final WandPresetLoader presetLoader;
    private final ItemTransportManager transporter;

    public WandEquipExecutor(WandPresetLoader presetLoader, ItemTransportManager transporter) {
        this.presetLoader = presetLoader;
        this.transporter = transporter;
    }

    @Override
    public Class<AtomicOp.WandEquipOp> opType() {
        return AtomicOp.WandEquipOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.WandEquipOp op, World world, long npcId) {
       String presetId = op.wandItemId(); // e.g. "gatherer_wand" (preset ID from WandProvider)
        Log.info(TAG, "[WandEquip] ▶ execute called: preset={} npcId={}", presetId, npcId); // diag

        // 1. Find the preset for this wand
        var preset = presetLoader.getPreset(presetId);
        if (preset == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("[WandEquip] unknown wand preset: " + presetId));
        }

        // 2. Find NPC
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isRemoved()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("[WandEquip] NPC " + npcId + " not found in entity bridge"));
        }

        String wandColor = preset.nbt().getString("wand_color");
        CompoundTag presetBehaviors = preset.nbt().getCompound("behaviors");

        // ── 2a. Shortfill: check NPC's own inventory first ──
        //      Same pattern as ResourceRequestExecutor — only go to warehouse
        //      for what the NPC doesn't already carry. This also bypasses the
        //      storage building requirement for cold-start wands.
        var wandItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.tryParse(WAND_ITEM_ID));
        if (wandItem != null) {
            for (int slot = 0; slot < npc.inventory.getContainerSize(); slot++) {
                ItemStack stack = npc.inventory.getItem(slot);
                if (stack.isEmpty() || stack.getItem() != wandItem) continue;
                CompoundTag stackNbt = getCustomData(stack);
                if (stackNbt == null) continue;
                if (wandColor.equals(stackNbt.getString("wand_color"))
                        && behaviorsEqual(presetBehaviors, stackNbt.getCompound("behaviors"))) {
                    // Found in NPC inventory — consume and equip directly
                    stack.shrink(1);
                    equipWandDirectly(world, npcId, presetId, preset, npc);
                    Log.info(TAG, "[WandEquip] 🪄 NPC #{} equipped '{}' from own inventory (shortfill)",
                            npcId, presetId);
                    return CompletableFuture.completedFuture(null);
                }
            }
        }

        // ── 2b. Not in NPC inventory — fall through to warehouse + storage ──
        UUID colonyId = resolveColonyId(npc, world);
        BlockPos storagePos = findNearestStorage(colonyId, npc.blockPosition());
        if (storagePos == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("[WandEquip] no storage building for colony " + colonyId));
        }

        // 3. Find the actual wand item in warehouse (wandscape:wand with matching NBT)
        ColonyItemBank bank = ColonyItemBank.get(npc.level());
        if (bank == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("[WandEquip] ColonyItemBank not available"));
        }

        ItemKey foundKey = null;
        for (var entry : bank.getSnapshot(colonyId).entrySet()) {
            ItemKey entryKey = entry.getKey();
            if (!WAND_ITEM_ID.equals(entryKey.itemId())) continue;
            if (entry.getValue() <= 0) continue;
            if (entryKey.nbt() == null) continue;
            if (wandColor.equals(entryKey.nbt().getString("wand_color"))
                    && behaviorsEqual(presetBehaviors, entryKey.nbt().getCompound("behaviors"))) {
                foundKey = entryKey;
                break;
            }
        }

        if (foundKey == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("[WandEquip] wand preset=" + presetId + " not in warehouse (colony=" + colonyId + ")"));
        }

        // 4. Consume from warehouse (sync — logical ownership transfer)
        bank.consume(colonyId, foundKey, 1);
        final ItemKey consumedKey = foundKey; // effectively final for lambda

        // 5. Start visual transport: wand flies from warehouse to NPC
        //    Use road network if available for faster on-road segments.
        BlockPos npcPos = npc.blockPosition();
        List<RouteSegment> route = planRoute(colonyId, storagePos, npcPos, npc.level());
        CompletableFuture<Void> transportFuture = transporter.send(
                consumedKey, storagePos, npcPos, npc.level(), npcId, route,
                true /* ownsItem: wand was consumed from bank, must return on cancel */);

        // 6. On arrival: equip wand capabilities into WandCarrier
        transportFuture.thenRun(() -> equipWandDirectly(world, npcId, presetId, preset, npc));

        return transportFuture;
    }

    /** Map NBT behavior tags (lowercase strings) to core BehaviourTag enum values. */
    private Map<BehaviourTag, BehaviourLevel> parseCapabilities(CompoundTag behaviors) {
        if (behaviors.isEmpty()) return Map.of();

        Map<BehaviourTag, BehaviourLevel> caps = new HashMap<>();
        for (String key : behaviors.getAllKeys()) {
            BehaviourTag tag = mapFromNbtKey(key);
            if (tag != null) {
                int level = behaviors.getInt(key);
                caps.put(tag, BehaviourLevel.of(level));
            }
        }
        return caps;
    }

    private static BehaviourTag mapFromNbtKey(String key) {
        return switch (key) {
            case "building"           -> BehaviourTag.BUILDING;
            case "farming"            -> BehaviourTag.FARMING;
            case "mining"             -> BehaviourTag.MINING;
            case "logging"            -> BehaviourTag.LOGGING;
            case "crafting"           -> BehaviourTag.CRAFTING;
            case "gathering"          -> BehaviourTag.GATHERING;
            case "ritual"             -> BehaviourTag.RITUAL;
            case "entity_interaction" -> BehaviourTag.ENTITY_INTERACTION;
            default                   -> null;
        };
    }

    /** Equip a wand on an NPC directly (no transport, no warehouse). Used for shortfill path. */
    private void equipWandDirectly(World world, long npcId, String presetId,
                                   WandPresetLoader.WandPreset preset, WandscapeNpc npc) {
        CompoundTag behaviors = preset.nbt().getCompound("behaviors");
        Map<BehaviourTag, BehaviourLevel> wandCaps = parseCapabilities(behaviors);
        float manaEff = preset.nbt().contains("mana_cost_multiplier")
                ? preset.nbt().getFloat("mana_cost_multiplier") : 1.0f;
        int range = preset.nbt().contains("range")
                ? preset.nbt().getInt("range") : 1;

        WandCarrier current = world.get(npcId, WandCarrier.class);
        if (current == null) {
            Log.warn(TAG, "[WandEquip] NPC {} lost WandCarrier, cannot equip", npcId);
            return;
        }
        WandCarrier updated = new WandCarrier(current);
        updated.equip(presetId, wandCaps, manaEff, range);
        world.addComponent(npcId, updated);

        // Update NPC hand item visual
        var wi = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.tryParse(WAND_ITEM_ID));
        if (wi != null) {
            ItemStack wandStack = new ItemStack(wi);
            wandStack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(preset.nbt().copy()));
            npc.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, wandStack);
        }

        // Visual feedback
        if (!npc.level().isClientSide) {
            for (int i = 0; i < 10; i++) {
                npc.level().addParticle(ParticleTypes.ENCHANT,
                        npc.getX() + (npc.getRandom().nextDouble() - 0.5) * 1.0,
                        npc.getY() + npc.getRandom().nextDouble() * 2.0,
                        npc.getZ() + (npc.getRandom().nextDouble() - 0.5) * 1.0,
                        0, 0, 0);
            }
        }

        Log.info(TAG, "[WandEquip] 🪄 NPC #{} equipped '{}' → caps: {} eff: {} range: {}",
                npcId, presetId, wandCaps.keySet(), manaEff, range);

        // Notify WandLifecycle: wand is now equipped on this NPC
        var lifecycle = world.wandLifecycle;
        if (lifecycle != null) {
            var cm = world.get(npcId, com.wsteam.wandscape.core.component.ColonyMember.class);
            UUID colonyId = cm != null && cm.colonyId() != null ? cm.colonyId() : npc.colonyId;
            if (colonyId != null) {
                lifecycle.confirmEquip(colonyId, presetId);
            }
        }
    }

    /** Extract CUSTOM_DATA NBT from an ItemStack, or null if absent. */
    @javax.annotation.Nullable
    private static CompoundTag getCustomData(ItemStack stack) {
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data != null ? data.copyTag() : null;
    }

    // ── Warehouse helpers ──

    private UUID resolveColonyId(WandscapeNpc npc, World world) {
        var member = world.get(npc.ecsEntityId, com.wsteam.wandscape.core.component.ColonyMember.class);
        if (member != null && member.colonyId() != null) {
            return member.colonyId();
        }
        return npc.colonyId != null ? npc.colonyId : new UUID(0, 0);
    }

    private static boolean behaviorsEqual(CompoundTag a, CompoundTag b) {
        if (a.size() != b.size()) return false;
        for (String key : a.getAllKeys()) {
            if (!b.contains(key)) return false;
            if (a.getInt(key) != b.getInt(key)) return false;
        }
        return true;
    }

    /** Find the nearest storage building to the NPC's current position. */
    private static BlockPos findNearestStorage(UUID colonyId, BlockPos npcPos) {
        BuildingApi buildingApi = WandscapeApis.getBuildingApi();
        if (buildingApi == null) return null;

       List<UUID> storageIds = buildingApi.getBuildingsByCategory(colonyId, "storage");
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
    private static List<RouteSegment> planRoute(UUID colonyId, BlockPos from, BlockPos to,
                                                 net.minecraft.world.level.Level level) {
        return RoadRoutingHelper.planWithRoads(
                WandscapeApis.getRoadApi(), level, colonyId, from, to);
    }
}
