package com.wsteam.wandscape.content.colony.exploration;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.colony.ColonyLevelManager;
import com.wsteam.wandscape.content.colony.exploration.network.ExplorationRewardPacket;
import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.service.ParticleService;
import com.wsteam.wandscape.foundation.sound.SoundService;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side service managing exploration chest expectation precomputation,
 * cache lookup, and reward processing.
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
     */
    public ExplorationRewardRange getOrCreateRewardRange(ServerLevel level, ResourceKey<LootTable> lootKey) {
        return rewardCache.computeIfAbsent(lootKey, k -> computeRewardRange(level, k));
    }

    private ExplorationRewardRange computeRewardRange(ServerLevel level, ResourceKey<LootTable> lootKey) {
        String lootTableId = lootKey.location().toString();
        ExplorationRegionLoader loader = Wandscape.EXPLORATION_REGION_LOADER;
        ExplorationRegionConfig config = loader != null ? loader.findMatchingRegion(lootTableId) : null;

        String regionName = config != null ? config.name() : ExplorationRegionConfig.deriveDisplayName(lootTableId);
        double danger = config != null ? config.dangerMultiplier() : 1.0;
        double variance = config != null ? config.variance() : 0.25;
        double ratio = config != null ? config.elementToExpRatio() : 15.0;

        MinecraftServer server = level.getServer();
        LootTable lootTable = server.reloadableRegistries().getLootTable(lootKey);
        if (lootTable == null || lootTable == LootTable.EMPTY) {
            return ExplorationExpectationCalculator.calculate(regionName, List.of(), danger, variance, ratio);
        }

        int sampleCount = Config.SPEC.isLoaded() ? Config.EXPLORATION_CHEST_SAMPLE_COUNT.get() : 50;
        List<Map<ElementType, Long>> sampleDraws = new ArrayList<>(sampleCount);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .create(LootContextParamSets.CHEST);

        for (int i = 0; i < sampleCount; i++) {
            ObjectArrayList<ItemStack> items = lootTable.getRandomItems(params);
            Map<ElementType, Long> drawElements = new LinkedHashMap<>();
            for (ItemStack stack : items) {
                if (stack == null || stack.isEmpty()) continue;
                Map<ElementType, Long> cost = Wandscape.ELEMENT_API.getBuildCost(stack);
                if (cost != null && !cost.isEmpty()) {
                    int count = stack.getCount();
                    for (Map.Entry<ElementType, Long> ce : cost.entrySet()) {
                        drawElements.merge(ce.getKey(), ce.getValue() * count, Long::sum);
                    }
                }
            }
            sampleDraws.add(drawElements);
        }

        ExplorationRewardRange range = ExplorationExpectationCalculator.calculate(
                regionName, sampleDraws, danger, variance, ratio
        );
        Log.info(TAG, "Computed reward range for loot table {}: EXP [{}, {}], elements {} types",
                lootTableId, range.minExp(), range.maxExp(), range.maxElements().size());
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
