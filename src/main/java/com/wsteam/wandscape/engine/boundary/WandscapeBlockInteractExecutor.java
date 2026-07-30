package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.task.scheduler.TaskExecutionSystem;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.op.executor.OpExecutor;
import com.wsteam.wandscape.op.executor.ResourceShortageException;
import com.wsteam.wandscape.road.core.TransportRoute;
import com.wsteam.wandscape.road.engine.RoadRoutingHelper;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.element.internal.ElementMappingLoader;
import com.wsteam.wandscape.engine.transport.ItemTransportManager;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.production.ProductionRecipeLoader;
import com.wsteam.wandscape.production.data.CraftWandRecipe;
import com.wsteam.wandscape.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import com.wsteam.wandscape.shared.log.Log;

/**
 * MC implementation of {@link OpExecutor} for {@link AtomicOp.BlockInteractOp}.
 *
 * <p>Sync actions (toggle/activate/open_gui) execute immediately via {@link BlockOps}.
 *
 * <p>Async actions (gather/decompose/synthesize) use a countdown + thenRun callback.
 * Mana is consumed by {@link TaskExecutionSystem}
 * BEFORE execution — this executor only handles the timing and side effects.
 *
 * <p>Registered in {@code EngineBootstrap} and ticked via {@link #tickAll()} from
 * the server tick loop.
 */
public class WandscapeBlockInteractExecutor implements OpExecutor<AtomicOp.BlockInteractOp> {

    private static final String TAG = "WandscapeBlockInteractExecutor";

    @Nullable
    private static ElementMappingLoader elementMappingLoader;

    @Nullable
    private static ProductionRecipeLoader productionRecipeLoader;

    private final ItemTransportManager transporter;

    public static void setElementMappingLoader(ElementMappingLoader loader) {
        elementMappingLoader = loader;
    }

    public static void setProductionRecipeLoader(ProductionRecipeLoader loader) {
        productionRecipeLoader = loader;
    }

    public WandscapeBlockInteractExecutor(ItemTransportManager transporter) {
        this.transporter = transporter;
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

        future.thenRun(() -> {
            try {
                executeAsyncAction(op, world, npcId);
            } catch (ResourceShortageException e) {
                var exec = world.get(npcId, TaskExecutor.class);
                if (exec != null && exec.globalTaskId != null) {
                    world.taskPool.markAwaitingResources(
                            exec.globalTaskId, npcId, e.requestedItems(), world);
                    exec.releaseGlobalTask();
                }
            }
        });

        Log.debug(TAG, "block_interact {}: NPC {} channeling at {} ({} ticks)",
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
            Log.debug(TAG, "block_interact tickAll: {} completed, {} remaining",
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
            default -> Log.warn(TAG, "Unknown async block_interact action: {}", action);
        }
    }

    private void executeGather(Map<String, String> params, World world, long npcId) {
        String element = params.getOrDefault("element", "wood");
        int amount = parseAmount(params);

        ColonyResourceAccess resources = world.colonyResources;
        if (resources == null) {
            Log.warn(TAG, "block_interact gather: colonyResources is null, cannot inject {}", element);
            return;
        }
        resources.addResource(new ResourceId(element), amount);
        // addResource() now emits ResourceFulfilled internally via WarehouseManager

        // ── Transport visualization: elements fly NPC → warehouse ──
        launchElementTransport(element, amount, world, npcId);

        // Completion sparkle
        spawnCompletionParticles(npcId);

        Log.info(TAG, "block_interact gather complete: {} x{} → colony warehouse", element, amount);
    }

    // ── Production action implementations ──

    private void executeDecompose(Map<String, String> params, World world, long npcId) {
        String itemId = params.get("item_id");
        int count = parseCount(params);
        if (itemId == null || count <= 0) {
            Log.warn(TAG, "decompose: invalid params item_id={} count={}", itemId, count);
            return;
        }

        ElementMappingLoader mappings = elementMappingLoader;
        if (mappings == null) {
            Log.warn(TAG, "decompose: ElementMappingLoader not set");
            return;
        }

        Level level = getNpcLevel(npcId);
        if (level == null) return;

        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        UUID colonyId = findStorageColonyId();
        ItemKey key = ItemKey.of(itemId, null);

        long available = bank.count(colonyId, key);
        if (available < count) {
            Log.warn(TAG, "decompose: insufficient items. need={} have={} item={}", count, available, itemId);
            int colonIdx = itemId.lastIndexOf(':');
            String shortId = colonIdx >= 0 ? itemId.substring(colonIdx + 1) : itemId;
            throw new ResourceShortageException(
                        List.of(new ResourceStack(new ResourceId(shortId), count)));
        }

        Map<ElementType, Long> yield = mappings.getSeedValues(itemId);

        if (yield.isEmpty()) {
            Log.warn(TAG, "decompose: no seed values for {}", itemId);
            return;
        }

        bank.consume(colonyId, key, count);

        ColonyResourceAccess resources = world.colonyResources;
        if (resources == null) {
            Log.warn(TAG, "decompose: colonyResources is null");
            bank.add(colonyId, key, count);
            return;
        }

        for (var entry : yield.entrySet()) {
            long total = entry.getValue() * count;
            resources.addResource(new ResourceId(entry.getKey().name().toLowerCase()), (int) total);
            Log.info(TAG, "decompose: {} x{} → {} x{}", itemId, count,
                    entry.getKey().name().toLowerCase(), total);
        }

        spawnCompletionParticles(npcId);
    }

    private void executeSynthesize(Map<String, String> params, World world, long npcId) {
        String recipeId = params.get("recipe_id");
        int count = parseCount(params);
        if (recipeId == null || count <= 0) {
            Log.warn(TAG, "synthesize: invalid params recipe_id={} count={}", recipeId, count);
            return;
        }

        ProductionRecipeLoader recipes = productionRecipeLoader;
        if (recipes == null) {
            Log.warn(TAG, "synthesize: ProductionRecipeLoader not set");
            return;
        }

        SynthesizeRecipe recipe = recipes.getSynthesizeRecipe(recipeId);
        if (recipe == null) {
            Log.warn(TAG, "synthesize: recipe not found: {}", recipeId);
            return;
        }

        Level level = getNpcLevel(npcId);
        if (level == null) return;

        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        UUID colonyId = findStorageColonyId();

        for (var entry : recipe.cost().entrySet()) {
            long needed = entry.getValue() * count;
            if (bank.countElement(colonyId, entry.getKey()) < needed) {
                String elementId = entry.getKey().name().toLowerCase();
                Log.warn(TAG, "synthesize: insufficient {} (need={})", entry.getKey(), needed);
                throw new ResourceShortageException(
                        List.of(new ResourceStack(new ResourceId(elementId), (int) needed)));
            }
        }

        for (var entry : recipe.cost().entrySet()) {
            bank.consumeElement(colonyId, entry.getKey(), entry.getValue() * count);
        }

        ItemKey outputKey = ItemKey.of(recipe.outputItem(), null);
        bank.add(colonyId, outputKey, count);

        // ── Transport visualization: crafted item flies NPC → warehouse ──
        launchItemTransport(outputKey, count, world, npcId);

        Log.info(TAG, "synthesize: {} x{} → warehouse", recipe.outputItem(), count);
        spawnCompletionParticles(npcId);
    }

    private void executeCraftWand(Map<String, String> params, World world, long npcId) {
        String recipeId = params.get("recipe_id");
        int count = parseCount(params);
        if (recipeId == null || count <= 0) {
            Log.warn(TAG, "craft_wand: invalid params recipe_id={} count={}", recipeId, count);
            return;
        }

        ProductionRecipeLoader recipes = productionRecipeLoader;
        if (recipes == null) {
            Log.warn(TAG, "craft_wand: ProductionRecipeLoader not set");
            return;
        }

        CraftWandRecipe recipe = recipes.getCraftWandRecipes().get(recipeId);
        if (recipe == null) {
            Log.warn(TAG, "craft_wand: recipe not found: {}", recipeId);
            return;
        }

        Level level = getNpcLevel(npcId);
        if (level == null) return;

        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        UUID colonyId = findStorageColonyId();

        for (var entry : recipe.cost().entrySet()) {
            long needed = entry.getValue() * count;
            if (bank.countElement(colonyId, entry.getKey()) < needed) {
                String elementId = entry.getKey().name().toLowerCase();
                Log.warn(TAG, "craft_wand: insufficient {} (need={})", entry.getKey(), needed);
                throw new ResourceShortageException(
                        List.of(new ResourceStack(new ResourceId(elementId), (int) needed)));
            }
        }

        for (var entry : recipe.cost().entrySet()) {
            bank.consumeElement(colonyId, entry.getKey(), entry.getValue() * count);
        }

        var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(recipe.outputItem()));
        if (item == null) {
            Log.warn(TAG, "craft_wand: output item not found: {}", recipe.outputItem());
            return;
        }

        ItemStack stack = new ItemStack(item, count);
        if (recipe.outputNbt() != null && !recipe.outputNbt().isEmpty()) {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(recipe.outputNbt().copy()));
        }

        bank.add(colonyId, ItemKey.of(recipe.outputItem(), recipe.outputNbt().copy()), count);

        // ── Transport visualization: wand flies NPC → warehouse ──
        launchItemTransport(ItemKey.of(recipe.outputItem(), recipe.outputNbt().copy()), count, world, npcId);

        Log.info(TAG, "craft_wand: {} x{} → warehouse", recipe.outputItem(), count);
        spawnCompletionParticles(npcId);
    }

    private void executeBrewPotion(Map<String, String> params, World world, long npcId) {
        String recipeId = params.get("recipe_id");
        int count = parseCount(params);
        if (recipeId == null || count <= 0) {
            Log.warn(TAG, "brew_potion: invalid params recipe_id={} count={}", recipeId, count);
            return;
        }

        ProductionRecipeLoader recipes = productionRecipeLoader;
        if (recipes == null) {
            Log.warn(TAG, "brew_potion: ProductionRecipeLoader not set");
            return;
        }

        var recipe = recipes.getPotionRecipes().get(recipeId);
        if (recipe == null) {
            Log.warn(TAG, "brew_potion: recipe not found: {}", recipeId);
            return;
        }

        Level level = getNpcLevel(npcId);
        if (level == null) return;

        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        UUID colonyId = findStorageColonyId();

        for (var entry : recipe.cost().entrySet()) {
            long needed = entry.getValue() * count;
            if (bank.countElement(colonyId, entry.getKey()) < needed) {
                String elementId = entry.getKey().name().toLowerCase();
                Log.warn(TAG, "brew_potion: insufficient {} (need={})", entry.getKey(), needed);
                throw new ResourceShortageException(
                        List.of(new ResourceStack(new ResourceId(elementId), (int) needed)));
            }
        }

        for (String inputItemId : recipe.inputItems()) {
            ItemKey key = ItemKey.of(inputItemId, null);
            if (bank.available(colonyId, key) < count) {
                int colonIdx = inputItemId.lastIndexOf(':');
                String shortId = colonIdx >= 0 ? inputItemId.substring(colonIdx + 1) : inputItemId;
                Log.warn(TAG, "brew_potion: insufficient input item {} (need={})", inputItemId, count);
                throw new ResourceShortageException(
                        List.of(new ResourceStack(new ResourceId(shortId), count)));
            }
        }

        for (var entry : recipe.cost().entrySet()) {
            bank.consumeElement(colonyId, entry.getKey(), entry.getValue() * count);
        }

        for (String inputItemId : recipe.inputItems()) {
            bank.consume(colonyId, ItemKey.of(inputItemId, null), count);
        }

        bank.add(colonyId, ItemKey.of(recipe.outputItem(), null), count);

        // ── Transport visualization: potion flies NPC → warehouse ──
        launchItemTransport(ItemKey.of(recipe.outputItem(), null), count, world, npcId);

        Log.info(TAG, "brew_potion: {} x{} → warehouse", recipe.outputItem(), count);
        spawnCompletionParticles(npcId);
    }

    // ── Transport helpers ──────────────────────────────────────────────────

    /**
     * Launch transport animation for gathered elements.
     * Elements are abstract — map to a representative block for the visual ItemEntity.
     */
    private void launchElementTransport(String elementName, int amount, World world, long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isRemoved()) return;

        String itemId = resolveElementItem(elementName);
        if (itemId == null) {
            Log.debug(TAG, "gather transport: no visual item for element '{}', skipping", elementName);
            return;
        }

        UUID colonyId = resolveColonyId(npc, world);
        BlockPos storagePos = findNearestStorage(colonyId, npc.blockPosition());
        BlockPos to = storagePos != null ? storagePos : npc.blockPosition().offset(0, 2, 0);
        BlockPos from = npc.blockPosition();
        TransportRoute route = planRoute(colonyId, from, to, npc.level());

        ItemKey key = ItemKey.of(itemId, null);
        transporter.send(key, amount, from, to, npc.level(), npcId, route);
        Log.debug(TAG, "gather transport: {} x{}({}) NPC→warehouse",
                elementName, amount, itemId);
    }

    /** Launch transport animation for produced items (synthesize/craft_wand/brew_potion). */
    private void launchItemTransport(ItemKey outputKey, int count, World world, long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isRemoved()) return;

        UUID colonyId = resolveColonyId(npc, world);
        BlockPos storagePos = findNearestStorage(colonyId, npc.blockPosition());
        BlockPos to = storagePos != null ? storagePos : npc.blockPosition().offset(0, 2, 0);
        BlockPos from = npc.blockPosition();
        TransportRoute route = planRoute(colonyId, from, to, npc.level());

        transporter.send(outputKey, count, from, to, npc.level(), npcId, route);
        Log.debug(TAG, "production transport: {} x{} NPC→warehouse",
                outputKey.itemId(), count);
    }

    /** Map an element name to a representative MC block ID for visual transport. */
    @Nullable
    private static String resolveElementItem(String elementName) {
        ElementType type;
        try {
            type = ElementType.valueOf(elementName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
        ElementMappingLoader mappings = elementMappingLoader;
        if (mappings == null) return null;
        return mappings.getRepresentativeBlock(type);
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

    /** Brief sparkle particles on action completion. Transport handles the main visual. */
    private void spawnCompletionParticles(long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc != null && !npc.isRemoved()) {
            for (int i = 0; i < 5; i++) {
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
        var api = WandscapeApis.getBuildingApi();
        for (var bd : api.getColonyBuildings(null)) {
            if ("storage".equals(bd.getCategory()) && !bd.isShutdown()) {
                UUID cid = bd.getColonyId();
                return cid != null ? cid : new UUID(0, 0);
            }
        }
        return new UUID(0, 0);
    }

    private static UUID resolveColonyId(WandscapeNpc npc, World world) {
        var member = world.get(npc.ecsEntityId, ColonyMember.class);
        if (member != null && member.colonyId() != null) return member.colonyId();
        return npc.colonyId != null ? npc.colonyId : new UUID(0, 0);
    }

    @Nullable
    private static BlockPos findNearestStorage(UUID colonyId, BlockPos npcPos) {
        BuildingApi api = WandscapeApis.getBuildingApi();
        if (api == null) return null;
        var ids = api.getBuildingsByCategory(colonyId, "storage");
        if (ids == null || ids.isEmpty()) return null;
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (UUID id : ids) {
            BuildingData bd = api.getBuilding(id);
            if (bd == null || bd.isShutdown()) continue;
            BlockPos p = bd.getPosition();
            double d = p.distSqr(npcPos);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest;
    }

    private static TransportRoute planRoute(UUID colonyId, BlockPos from, BlockPos to,
                                                 net.minecraft.world.level.Level level) {
        return RoadRoutingHelper.planWithRoads(
                WandscapeApis.getRoadApi(), level, colonyId, from, to);
    }
}
