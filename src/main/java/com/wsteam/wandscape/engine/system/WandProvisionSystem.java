package com.wsteam.wandscape.engine.system;

import com.wsteam.wandscape.core.system.WandProvider;
import com.wsteam.wandscape.core.types.BehaviourLevel;
import com.wsteam.wandscape.core.types.BehaviourTag;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.warehouse.ColonyItemBank;
import com.wsteam.wandscape.wand.internal.WandPresetLoader;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * Engine-layer {@link WandProvider} that queries the colony warehouse
 * for wands matching required {@link BehaviourTag} capabilities.
 *
 * <p>All wands use the same item ID {@code "wandscape:wand"} — differentiation
 * is via NBT. This provider scans the warehouse for wand items whose NBT
 * contains the required behaviour levels.
 *
 * <p>Returns the wand's NBT-hash string as the wand ID for equipping
 * (the executor uses this to find the exact ItemKey in the bank).
 */
public class WandProvisionSystem implements WandProvider {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WAND_ITEM_ID = "wandscape:wand";

    private final WandPresetLoader presetLoader;

    public WandProvisionSystem(WandPresetLoader presetLoader) {
        this.presetLoader = presetLoader;
    }

    /**
     * Find a wand in the colony warehouse that satisfies all given requirements.
     * Returns the wand's preset ID with the lowest total behavior level sum
     * among all matching candidates, to avoid over-provisioning higher-tier wands.
     */
    @Override
    @Nullable
    public String findWand(Map<BehaviourTag, BehaviourLevel> reqs, UUID colonyId) {
        if (reqs.isEmpty()) return null;

        ServerLevel level = getServerLevel();
        if (level == null) return null;
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return null;

        // Scan warehouse for the lowest-level wand that satisfies requirements
        String bestPreset = null;
        int bestSum = Integer.MAX_VALUE;

        Map<ItemKey, Long> snapshot = bank.getSnapshot(colonyId);
        for (var entry : snapshot.entrySet()) {
            ItemKey key = entry.getKey();
            if (!WAND_ITEM_ID.equals(key.itemId())) continue;
            if (entry.getValue() <= 0) continue;
            if (key.nbt() == null || key.nbt().isEmpty()) continue;

            // Check if this wand's NBT satisfies requirements
            if (!wandNbtSatisfies(key.nbt(), reqs)) continue;

            // Match the preset by comparing behaviors tag
            String presetId = matchPreset(key.nbt());
            if (presetId == null) continue;

            int sum = behaviorsSum(key.nbt().getCompound("behaviors"));
            if (sum < bestSum) {
                bestSum = sum;
                bestPreset = presetId;
            }
        }

        if (bestPreset != null) {
            LOGGER.info("[WandProvision] selected {} (total_level={}) for reqs={}",
                    bestPreset, bestSum, reqs);
        } else {
            LOGGER.debug("[WandProvision] no wand in warehouse for reqs={}", reqs);
        }
        return bestPreset;
    }

    /** Sum all behavior level values in a wand's behaviors tag. */
    private static int behaviorsSum(CompoundTag behaviors) {
        int sum = 0;
        for (String key : behaviors.getAllKeys()) {
            sum += behaviors.getInt(key);
        }
        return sum;
    }

    /**
     * Check whether a wand item's NBT satisfies all requirements.
     * The NBT key "behaviors" maps behavior IDs (lowercase) to levels.
     */
    private boolean wandNbtSatisfies(CompoundTag nbt,
                                     Map<BehaviourTag, BehaviourLevel> reqs) {
        CompoundTag behaviors = nbt.getCompound("behaviors");
        if (behaviors.isEmpty()) return false;

        for (var entry : reqs.entrySet()) {
            String key = mapToNbtKey(entry.getKey());
            int required = entry.getValue().value();
            if (!behaviors.contains(key)) return false;
            if (behaviors.getInt(key) < required) return false;
        }
        return true;
    }

    /**
     * Match a wand's NBT back to a known preset by comparing behaviors.
     * Returns the preset ID (e.g. "gatherer_wand") or null.
     */
    @Nullable
    private String matchPreset(CompoundTag wandNbt) {
        CompoundTag wandBehaviors = wandNbt.getCompound("behaviors");
        String wandColor = wandNbt.getString("wand_color");

        for (var entry : presetLoader.getAllPresets().entrySet()) {
            CompoundTag presetNbt = entry.getValue().nbt();
            if (wandColor.equals(presetNbt.getString("wand_color"))
                    && behaviorsEqual(wandBehaviors, presetNbt.getCompound("behaviors"))) {
                return entry.getKey();
            }
        }
        return null;
    }

    private boolean behaviorsEqual(CompoundTag a, CompoundTag b) {
        if (a.size() != b.size()) return false;
        for (String key : a.getAllKeys()) {
            if (!b.contains(key)) return false;
            if (a.getInt(key) != b.getInt(key)) return false;
        }
        return true;
    }

    /** Map core BehaviourTag to the lowercase NBT key used in wand JSONs. */
    private static String mapToNbtKey(BehaviourTag tag) {
        return switch (tag) {
            case BUILDING           -> "building";
            case FARMING            -> "farming";
            case MINING             -> "mining";
            case LOGGING            -> "logging";
            case CRAFTING           -> "crafting";
            case GATHERING          -> "gathering";
            case RITUAL             -> "ritual";
            case ENTITY_INTERACTION -> "entity_interaction";
        };
    }

    @Nullable
    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
