package com.wsteam.wandscape.content.colony.exploration;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.colony.ColonyLevelManager;
import com.wsteam.wandscape.content.colony.exploration.event.ExplorationChestRewardEvent;
import com.wsteam.wandscape.content.colony.exploration.network.ExplorationRewardPacket;
import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.service.ParticleService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side service managing exploration chest expectation precomputation,
 * cache lookup, and reward processing.
 *
 * <p>What a chest is worth comes entirely from data: the matched region's {@code reward}
 * block decides between sampling the loot table, using a declared value, or both. This
 * class only resolves that rule, rolls the result, gives listeners a last chance to change
 * it, and credits it.
 */
public class ExplorationRewardService {
    private static final String TAG = "ExplorationRewardService";

    private static volatile ExplorationRewardService activeInstance;

    public static ExplorationRewardService get() {
        if (activeInstance == null) {
            synchronized (ExplorationRewardService.class) {
                if (activeInstance == null) {
                    activeInstance = new ExplorationRewardService();
                }
            }
        }
        return activeInstance;
    }

    private final Map<ResourceKey<LootTable>, ExplorationRewardRange> rewardCache = new ConcurrentHashMap<>();
    private final Set<BlockPos> processedPositions = ConcurrentHashMap.newKeySet();
    private final Random random = new Random();

    public ExplorationRewardService() {}

    /** Clear precomputed caches (e.g. on datapack reload or server shutdown). */
    public void clearCache() {
        rewardCache.clear();
        processedPositions.clear();
        Log.info(TAG, "Exploration reward cache cleared");
    }

    /**
     * Get or compute the exploration reward range for a given loot table key.
     *
     * <p>Cached because pricing a range reads the whole loot table structure;
     * a region declaring {@code reward.mode = fixed} skips that entirely.
     */
    public ExplorationRewardRange getOrCreateRewardRange(ServerLevel level, ResourceKey<LootTable> lootKey) {
        return rewardCache.computeIfAbsent(lootKey, k -> computeRewardRange(level, k));
    }

    private ExplorationRewardRange computeRewardRange(ServerLevel level, ResourceKey<LootTable> lootKey) {
        String lootTableId = lootKey.location().toString();
        ExplorationRegionLoader loader = Wandscape.EXPLORATION_REGION_LOADER;
        ExplorationRegionConfig config = loader != null ? loader.findMatchingRegion(lootTableId) : null;

        String regionName = config != null ? config.name() : ExplorationRegionConfig.deriveDisplayName(lootTableId);
        ExplorationRewardSpec spec = config != null ? config.reward() : ExplorationRewardSpec.DEFAULT;

        Map<ElementType, Long> value;
        if (spec.skipsSampling()) {
            value = spec.value();
        } else {
            value = ExplorationLootEstimator.estimate(level, lootKey);
            if (spec.addsToDerived()) {
                value = ExplorationExpectationCalculator.add(value, spec.value());
            }
        }

        ExplorationRewardRange range = ExplorationExpectationCalculator.fromValue(regionName, value, spec);
        Log.info(TAG, "Resolved reward for {} [{} / {}]: EXP [{}, {}], {} element types{}",
                lootTableId, regionName, spec.mode(), range.minExp(), range.maxExp(),
                range.maxElements().size(), range.degenerate() ? " (degenerate fallback)" : "");
        return range;
    }

    /**
     * Process an exploration chest discovery by a player.
     *
     * @param player player who opened or broke the chest
     * @param level server level
     * @param pos container block position
     * @param lootKey loot table key
     */
    public void processReward(ServerPlayer player, ServerLevel level, BlockPos pos, ResourceKey<LootTable> lootKey) {
        if (player == null || level == null || pos == null || lootKey == null) return;

        BlockPos immutablePos = pos.immutable();
        if (!processedPositions.add(immutablePos)) {
            // Already processed this position recently
            return;
        }

        UUID colonyId = ColonyOwnership.ownColony(player);
        if (colonyId == null) {
            Component tip = Component.literal("§e[魔法小镇] 你在野外发现了宝箱，但尚未建立小镇，探索经验与元素已消散。使用小镇权杖即可建立属于你的小镇！");
            player.displayClientMessage(tip, true);
            ScreenFeedbackPacket.send(player, tip, false);
            try {
                player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8f, 1.0f);
            } catch (Throwable ignored) {}
            return;
        }

        ExplorationRewardRange range = getOrCreateRewardRange(level, lootKey);
        double expMult = Config.SPEC.isLoaded() ? Config.EXPLORATION_CHEST_EXP_MULTIPLIER.get() : 1.0;
        double elemMult = Config.SPEC.isLoaded() ? Config.EXPLORATION_CHEST_ELEMENT_MULTIPLIER.get() : 1.0;

        int exp = (int) Math.round(range.rollExp(random) * expMult);
        Map<ElementType, Long> elements = range.rollElements(random);
        if (elemMult != 1.0) {
            Map<ElementType, Long> scaled = new LinkedHashMap<>();
            for (Map.Entry<ElementType, Long> entry : elements.entrySet()) {
                long val = Math.round(entry.getValue() * elemMult);
                if (val > 0) scaled.put(entry.getKey(), val);
            }
            elements = Collections.unmodifiableMap(scaled);
        }

        // Last chance to change or suppress the payout before it is credited and shown.
        ExplorationChestRewardEvent event = new ExplorationChestRewardEvent(
                player, colonyId, lootKey, immutablePos, range.regionName(), range.degenerate(), exp, elements);
        if (NeoForge.EVENT_BUS.post(event).isCanceled()) {
            Log.info(TAG, "Reward for chest at {} suppressed by a listener", immutablePos);
            return;
        }
        exp = event.exp();
        elements = event.elements();

        if (exp <= 0 && elements.isEmpty()) {
            // A chest nothing could be priced for pays nothing on purpose. Showing "0 exp, 0
            // elements" with particles and a chime would read as a bug, and a small consolation
            // payout would read as a cheap chest — neither is true, so stay quiet.
            Log.info(TAG, "Chest at {} [{}] paid nothing{}", immutablePos, range.regionName(),
                    range.degenerate() ? " (no item in its loot table could be priced)" : "");
            return;
        }

        // Deposit colony exp
        ColonyLevelManager levelMgr = ColonyLevelManager.get();
        if (levelMgr != null) {
            levelMgr.addExperience(colonyId, exp);
        }

        // Deposit elements to colony warehouse bank
        ColonyItemBank itemBank = ColonyItemBank.get(level);
        if (itemBank != null) {
            for (Map.Entry<ElementType, Long> entry : elements.entrySet()) {
                itemBank.addElement(colonyId, entry.getKey(), entry.getValue());
            }
        }

        // Broadcast particles & sound at chest position
        Vec3 center = Vec3.atCenterOf(immutablePos).add(0, 0.3, 0);
        ParticleService.burstAt(level, ParticleTypes.ENCHANT, center, 22, 0.35, 0.15);
        ParticleService.burstAt(level, ParticleTypes.HAPPY_VILLAGER, center, 10, 0.25, 0.1);
        level.playSound(null, immutablePos.getX() + 0.5, immutablePos.getY() + 0.5, immutablePos.getZ() + 0.5,
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0f, 1.0f);

        // Send HUD notification to player
        ExplorationRewardPacket.send(player, range.regionName(), exp, elements);

        Log.info(TAG, "Player {} discovered chest at {} [{}] -> Colony {} (+{} exp, {} element types)",
                player.getGameProfile().getName(), immutablePos, range.regionName(), colonyId, exp, elements.size());
    }
}
