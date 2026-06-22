package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.OpExecutor;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.element.internal.ElementMappingLoader;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.production.ProductionRecipeLoader;
import com.wsteam.wandscape.production.data.CraftWandRecipe;
import com.wsteam.wandscape.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * MC implementation of {@link OpExecutor} for {@link AtomicOp.BlockInteractOp}.
 *
 * <p>Sync actions (toggle/activate/open_gui) execute immediately via {@link BlockOps}.
 *
 * <p>Async actions (gather/decompose/synthesize) use a countdown + thenRun callback.
 * Mana is consumed by {@link com.wsteam.wandscape.core.system.TaskExecutionSystem}
 * BEFORE execution — this executor only handles the timing and side effects.
 *
 * <p>Registered in {@code EngineBootstrap} and ticked via {@link #tickAll()} from
 * the server tick loop.
 */
public class WandscapeBlockInteractExecutor implements OpExecutor<AtomicOp.BlockInteractOp> {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable
    private static ElementMappingLoader elementMappingLoader;

    @Nullable
    private static ProductionRecipeLoader productionRecipeLoader;

    public static void setElementMappingLoader(ElementMappingLoader loader) {
        elementMappingLoader = loader;
    }

    public static void setProductionRecipeLoader(ProductionRecipeLoader loader) {
        productionRecipeLoader = loader;
    }

    record Pending(CompletableFuture<Void> future, AtomicOp.BlockInteractOp op,
                   World world, long npcId, int remainingTicks) {}

    private final List<Pending> pending = new ArrayList<>();

    @Override
    public Class<AtomicOp.BlockInteractOp> opType() {
        return AtomicOp.BlockInteractOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.BlockInteractOp op, World world, long npcId) {
        String action = op.action().id();

        // ── Sync actions ──
        if ("toggle".equals(action) || "activate".equals(action) || "open_gui".equals(action)) {
            BlockOps blockOps = world.blockOps;
            if (blockOps != null) {
                switch (action) {
                    case "toggle"   -> blockOps.toggle(op.target());
                    case "activate" -> blockOps.activate(op.target());
                    case "open_gui" -> blockOps.openGui(op.target());
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        // ── Async actions ──
        if (op.channelTicks() <= 0) {
            // Zero ticks → execute immediately
            executeAsyncAction(op, world, npcId);
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = world.startAsyncOp(
                "block_interact_" + action + "_" + op.target());
        pending.add(new Pending(future, op, world, npcId, op.channelTicks()));

        future.thenRun(() -> executeAsyncAction(op, world, npcId));

        LOGGER.debug("block_interact {}: NPC {} channeling at {} ({} ticks)",
                action, npcId, op.target(), op.channelTicks());
        return future;
    }

    /** Called every MC tick. Decrements countdowns and completes futures. */
    public void tickAll() {
        if (pending.isEmpty()) return;

        List<CompletableFuture<Void>> toComplete = new ArrayList<>();

        for (int i = 0; i < pending.size(); i++) {
            Pending p = pending.get(i);
            int remaining = p.remainingTicks() - 1;
            if (remaining <= 0) {
                toComplete.add(p.future());
            } else {
                pending.set(i, new Pending(p.future(), p.op(), p.world(), p.npcId(), remaining));
            }
        }

        for (CompletableFuture<Void> f : toComplete) {
            f.complete(null); // → triggers thenRun → executeAsyncAction
        }

        pending.removeIf(p -> p.future().isDone());

        if (!toComplete.isEmpty()) {
            LOGGER.debug("block_interact tickAll: {} completed, {} remaining",
                    toComplete.size(), pending.size());
        }
    }

    public boolean hasPendingOps() {
        return !pending.isEmpty();
    }

    // ── Action implementations ──

    private void executeAsyncAction(AtomicOp.BlockInteractOp op, World world, long npcId) {
        String action = op.action().id();
        Map<String, String> params = op.params();

        switch (action) {
            case "gather" -> executeGather(params, world, npcId);
            case "decompose" -> executeDecompose(params, world, npcId);
            case "synthesize" -> executeSynthesize(params, world, npcId);
            case "craft_wand" -> executeCraftWand(params, world, npcId);
            case "brew_potion" -> executeBrewPotion(params, world, npcId);
            default -> LOGGER.warn("Unknown async block_interact action: {}", action);
        }
    }

    private void executeGather(Map<String, String> params, World world, long npcId) {
        String element = params.getOrDefault("element", "wood");
        int amount = parseAmount(params);

        ColonyResourceAccess resources = world.colonyResources;
        if (resources == null) {
            LOGGER.warn("block_interact gather: colonyResources is null, cannot inject {}", element);
            return;
        }
        resources.addResource(new ResourceId(element), amount);

        // Visual feedback on the NPC
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc != null && !npc.isRemoved()) {
            for (int i = 0; i < 15; i++) {
                double ox = (npc.getRandom().nextDouble() - 0.5) * 1.0;
                double oy = npc.getRandom().nextDouble() * 2.0;
                double oz = (npc.getRandom().nextDouble() - 0.5) * 1.0;
                npc.level().addParticle(ParticleTypes.HAPPY_VILLAGER,
                        npc.getX() + ox, npc.getY() + oy, npc.getZ() + oz,
                        0, 0, 0);
            }
        }
        LOGGER.info("block_interact gather complete: {} x{} → colony warehouse", element, amount);
    }

    // ── Production action implementations ──

    private void executeDecompose(Map<String, String> params, World world, long npcId) {
        String itemId = params.get("item_id");
        int count = parseCount(params);
        if (itemId == null || count <= 0) {
            LOGGER.warn("decompose: invalid params item_id={} count={}", itemId, count);
            return;
        }

        ElementMappingLoader mappings = elementMappingLoader;
        if (mappings == null) {
            LOGGER.warn("decompose: ElementMappingLoader not set");
            return;
        }

        Level level = getNpcLevel(npcId);
        if (level == null) return;

        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        // Find colony ID from any storage building
        UUID colonyId = findStorageColonyId();
        ItemKey key = ItemKey.of(itemId, null);

        // Check warehouse has enough items
        long available = bank.count(colonyId, key);
        if (available < count) {
            LOGGER.warn("decompose: insufficient items. need={} have={} item={}", count, available, itemId);
            return;
        }

        // Get decompose yield from element mapping
        var blockState = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(itemId));
        Map<ElementType, Long> yield = (blockState != null)
                ? mappings.getDecomposeYield(blockState.defaultBlockState())
                : Map.of();

        if (yield.isEmpty()) {
            LOGGER.warn("decompose: no decompose yield for {}", itemId);
            return;
        }

        // Consume items from warehouse
        bank.consume(colonyId, key, count);

        // Add elements to warehouse
        ColonyResourceAccess resources = world.colonyResources;
        if (resources == null) {
            LOGGER.warn("decompose: colonyResources is null");
            // Rollback: return items
            bank.add(colonyId, key, count);
            return;
        }

        for (var entry : yield.entrySet()) {
            long total = entry.getValue() * count;
            resources.addResource(new ResourceId(entry.getKey().name().toLowerCase()), (int) total);
            LOGGER.info("decompose: {} x{} → {} x{}", itemId, count,
                    entry.getKey().name().toLowerCase(), total);
        }

        spawnParticles(npcId);
    }

    private void executeSynthesize(Map<String, String> params, World world, long npcId) {
        String recipeId = params.get("recipe_id");
        int count = parseCount(params);
        if (recipeId == null || count <= 0) {
            LOGGER.warn("synthesize: invalid params recipe_id={} count={}", recipeId, count);
            return;
        }

        ProductionRecipeLoader recipes = productionRecipeLoader;
        if (recipes == null) {
            LOGGER.warn("synthesize: ProductionRecipeLoader not set");
            return;
        }

        SynthesizeRecipe recipe = recipes.getSynthesizeRecipes().get(recipeId);
        if (recipe == null) {
            LOGGER.warn("synthesize: recipe not found: {}", recipeId);
            return;
        }

        Level level = getNpcLevel(npcId);
        if (level == null) return;

        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        UUID colonyId = findStorageColonyId();

        // Check and consume elements
        for (var entry : recipe.cost().entrySet()) {
            long needed = entry.getValue() * count;
            ItemKey key = elementToItemKey(entry.getKey());
            if (key == null || bank.available(colonyId, key) < needed) {
                LOGGER.warn("synthesize: insufficient {} (need={})", entry.getKey(), needed);
                return;
            }
        }

        // Consume all elements
        for (var entry : recipe.cost().entrySet()) {
            long needed = entry.getValue() * count;
            ItemKey key = elementToItemKey(entry.getKey());
            if (key != null) bank.consume(colonyId, key, needed);
        }

        // Add output item
        ItemKey outputKey = ItemKey.of(recipe.outputItem(), null);
        bank.add(colonyId, outputKey, count);

        LOGGER.info("synthesize: {} x{} → warehouse", recipe.outputItem(), count);
        spawnParticles(npcId);
    }

    private void executeCraftWand(Map<String, String> params, World world, long npcId) {
        String recipeId = params.get("recipe_id");
        int count = parseCount(params);
        if (recipeId == null || count <= 0) {
            LOGGER.warn("craft_wand: invalid params recipe_id={} count={}", recipeId, count);
            return;
        }

        ProductionRecipeLoader recipes = productionRecipeLoader;
        if (recipes == null) {
            LOGGER.warn("craft_wand: ProductionRecipeLoader not set");
            return;
        }

        CraftWandRecipe recipe = recipes.getCraftWandRecipes().get(recipeId);
        if (recipe == null) {
            LOGGER.warn("craft_wand: recipe not found: {}", recipeId);
            return;
        }

        Level level = getNpcLevel(npcId);
        if (level == null) return;

        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        UUID colonyId = findStorageColonyId();

        // Check and consume elements
        for (var entry : recipe.cost().entrySet()) {
            long needed = entry.getValue() * count;
            ItemKey key = elementToItemKey(entry.getKey());
            if (key == null || bank.available(colonyId, key) < needed) {
                LOGGER.warn("craft_wand: insufficient {} (need={})", entry.getKey(), needed);
                return;
            }
        }

        for (var entry : recipe.cost().entrySet()) {
            long needed = entry.getValue() * count;
            ItemKey key = elementToItemKey(entry.getKey());
            if (key != null) bank.consume(colonyId, key, needed);
        }

        // Add output item with NBT
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(recipe.outputItem()));
        if (item == null) {
            LOGGER.warn("craft_wand: output item not found: {}", recipe.outputItem());
            return;
        }

        // Create ItemStack with NBT via CustomData component
        ItemStack stack = new ItemStack(item, count);
        if (recipe.outputNbt() != null && !recipe.outputNbt().isEmpty()) {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(recipe.outputNbt().copy()));
        }

        // Insert into warehouse
        bank.add(colonyId, ItemKey.of(recipe.outputItem(), recipe.outputNbt().copy()), count);

        LOGGER.info("craft_wand: {} x{} → warehouse", recipe.outputItem(), count);
        spawnParticles(npcId);
    }

    private void executeBrewPotion(Map<String, String> params, World world, long npcId) {
        String recipeId = params.get("recipe_id");
        int count = parseCount(params);
        if (recipeId == null || count <= 0) {
            LOGGER.warn("brew_potion: invalid params recipe_id={} count={}", recipeId, count);
            return;
        }

        ProductionRecipeLoader recipes = productionRecipeLoader;
        if (recipes == null) {
            LOGGER.warn("brew_potion: ProductionRecipeLoader not set");
            return;
        }

        var recipe = recipes.getPotionRecipes().get(recipeId);
        if (recipe == null) {
            LOGGER.warn("brew_potion: recipe not found: {}", recipeId);
            return;
        }

        Level level = getNpcLevel(npcId);
        if (level == null) return;

        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        UUID colonyId = findStorageColonyId();

        // Check elements
        for (var entry : recipe.cost().entrySet()) {
            long needed = entry.getValue() * count;
            ItemKey key = elementToItemKey(entry.getKey());
            if (key == null || bank.available(colonyId, key) < needed) {
                LOGGER.warn("brew_potion: insufficient {} (need={})", entry.getKey(), needed);
                return;
            }
        }

        // Check input items
        for (String inputItemId : recipe.inputItems()) {
            ItemKey key = ItemKey.of(inputItemId, null);
            if (bank.available(colonyId, key) < count) {
                LOGGER.warn("brew_potion: insufficient input item {} (need={})", inputItemId, count);
                return;
            }
        }

        // Consume elements
        for (var entry : recipe.cost().entrySet()) {
            long needed = entry.getValue() * count;
            ItemKey key = elementToItemKey(entry.getKey());
            if (key != null) bank.consume(colonyId, key, needed);
        }

        // Consume input items
        for (String inputItemId : recipe.inputItems()) {
            bank.consume(colonyId, ItemKey.of(inputItemId, null), count);
        }

        // Add output
        bank.add(colonyId, ItemKey.of(recipe.outputItem(), null), count);

        LOGGER.info("brew_potion: {} x{} → warehouse", recipe.outputItem(), count);
        spawnParticles(npcId);
    }

    // ── Helpers ──

    private static int parseCount(Map<String, String> params) {
        if (params == null) return 1;
        try {
            String raw = params.get("count");
            return raw != null ? Integer.parseInt(raw) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int parseAmount(Map<String, String> params) {
        if (params == null) return 10;
        try {
            String raw = params.get("amount");
            return raw != null ? Integer.parseInt(raw) : 10;
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    @Nullable
    private static Level getNpcLevel(long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        return npc != null ? npc.level() : null;
    }

    private void spawnParticles(long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc != null && !npc.isRemoved()) {
            for (int i = 0; i < 15; i++) {
                double ox = (npc.getRandom().nextDouble() - 0.5) * 1.0;
                double oy = npc.getRandom().nextDouble() * 2.0;
                double oz = (npc.getRandom().nextDouble() - 0.5) * 1.0;
                npc.level().addParticle(ParticleTypes.HAPPY_VILLAGER,
                        npc.getX() + ox, npc.getY() + oy, npc.getZ() + oz,
                        0, 0, 0);
            }
        }
    }

    /** Find the first storage building's colony ID, or fallback to default. */
    private static UUID findStorageColonyId() {
        var api = com.wsteam.wandscape.shared.registry.WandscapeApis.getBuildingApi();
        for (var bd : api.getColonyBuildings(null)) {
            if ("storage".equals(bd.getCategory()) && !bd.isShutdown()) {
                UUID cid = bd.getColonyId();
                return cid != null ? cid : new UUID(0, 0);
            }
        }
        return new UUID(0, 0);
    }

    /** Map element type to its representative ItemKey (matches WarehouseManager mapping). */
    @Nullable
    private static ItemKey elementToItemKey(ElementType type) {
        return switch (type) {
            case WOOD -> ItemKey.of("minecraft:oak_log", null);
            case EARTH -> ItemKey.of("minecraft:dirt", null);
            case WATER -> ItemKey.of("minecraft:water_bucket", null);
            case FIRE -> ItemKey.of("minecraft:blaze_powder", null);
            case WIND -> ItemKey.of("minecraft:feather", null);
            case IRON -> ItemKey.of("minecraft:iron_ingot", null);
            case GOLD -> ItemKey.of("minecraft:gold_ingot", null);
            case DIAMOND -> ItemKey.of("minecraft:diamond", null);
            case ENDER -> ItemKey.of("minecraft:ender_pearl", null);
        };
    }
}
