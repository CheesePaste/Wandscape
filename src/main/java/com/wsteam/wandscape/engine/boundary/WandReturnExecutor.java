package com.wsteam.wandscape.engine.boundary;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.component.WandCarrier;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.OpExecutor;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.warehouse.ColonyItemBank;
import com.wsteam.wandscape.wand.internal.WandPresetLoader;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Executes {@link AtomicOp.WandReturnOp}: unequips a wand from the NPC
 * and returns it to the colony warehouse, updating the ECS
 * {@link WandCarrier} component.
 */
public class WandReturnExecutor implements OpExecutor<AtomicOp.WandReturnOp> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WAND_ITEM_ID = "wandscape:wand";

    private final WandPresetLoader presetLoader;

    public WandReturnExecutor(WandPresetLoader presetLoader) {
        this.presetLoader = presetLoader;
    }

    @Override
    public Class<AtomicOp.WandReturnOp> opType() {
        return AtomicOp.WandReturnOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.WandReturnOp op, World world, long npcId) {
        String presetId = op.wandItemId(); // e.g. "gatherer_wand" (preset ID)
        LOGGER.info("[WandReturn] ▶ execute called: preset={} npcId={}", presetId, npcId); // diag

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
            // Wand not equipped — nothing to do (may have been lost on death)
            LOGGER.debug("[WandReturn] preset {} not equipped on NPC {}, skipping", presetId, npcId);
            return CompletableFuture.completedFuture(null);
        }

        // 2. Find the preset for this wand (needed for NBT to add back to warehouse)
        var preset = presetLoader.getPreset(presetId);
        if (preset == null) {
            LOGGER.warn("[WandReturn] unknown wand preset: {}", presetId);
            // Still unequip so NPC can continue working
            current.unequip(presetId, java.util.Map.of());
            world.addComponent(npcId, current);
            return CompletableFuture.completedFuture(null);
        }

        // 3. Unequip from carrier: build knownWands map from remaining equipped IDs
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

        // 4. Add wand back to warehouse as "wandscape:wand" with preset NBT
        ItemKey key = ItemKey.of(WAND_ITEM_ID, preset.nbt().copy());
        bank.add(colonyId, key, 1);

        // 5. Restore NPC default hand item
        npc.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(Wandscape.WAND.get()));

        // 6. Visual feedback
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

        return CompletableFuture.completedFuture(null);
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

    private UUID resolveColonyId(WandscapeNpc npc, World world) {
        var member = world.get(npc.ecsEntityId,
                com.wsteam.wandscape.core.component.ColonyMember.class);
        if (member != null && member.colonyId() != null) {
            return member.colonyId();
        }
        return npc.colonyId != null ? npc.colonyId : new UUID(0, 0);
    }
}
