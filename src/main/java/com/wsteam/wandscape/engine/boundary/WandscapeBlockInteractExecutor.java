package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.task.scheduler.TaskExecutionSystem;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.op.executor.OpExecutor;
import com.wsteam.wandscape.op.executor.ResourceShortageException;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.element.internal.ElementMappingLoader;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.transport.ItemTransportManager;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.magic.item.SpellItem;
import com.wsteam.wandscape.production.ProductionRecipeLoader;
import com.wsteam.wandscape.production.data.CraftSpellRecipe;
import com.wsteam.wandscape.production.data.CraftWandRecipe;
import com.wsteam.wandscape.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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

    static final class Pending {
        final CompletableFuture<Void> future;
        final AtomicOp.BlockInteractOp op;
        final World world;
        final long npcId;
        /** Owning global task id, or -1 when the channel isn't bound to a task. */
        final long taskId;
        /** {@link GlobalTask#channelEpoch} at channel start — stale channels are cancelled. */
        final int epoch;
        int remainingTicks;
        boolean cancelled;

        Pending(CompletableFuture<Void> future, AtomicOp.BlockInteractOp op, World world,
                long npcId, long taskId, int epoch, int remainingTicks) {
            this.future = future;
            this.op = op;
            this.world = world;
            this.npcId = npcId;
            this.taskId = taskId;
            this.epoch = epoch;
            this.remainingTicks = remainingTicks;
        }
    }

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

        // ── Preconditions check for async production actions ──
        try {
            checkPreconditions(op, world, npcId);
        } catch (ResourceShortageException e) {
            return CompletableFuture.failedFuture(e);
        }

        // ── Async actions ──
        if (op.channelTicks() <= 0) {
            // Zero ticks → execute immediately
            try {
                executeAsyncAction(op, world, npcId);
                return CompletableFuture.completedFuture(null);
            } catch (ResourceShortageException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        CompletableFuture<Void> future = world.startAsyncOp(
                "block_interact_" + action + "_" + op.target());

        // Channel duration: resume from the task's persisted checkpoint when a previous
        // channel was interrupted (task released / world reloaded), else run the full channel.
        // The checkpoint is NOT cleared here — the first tickAll of this channel overwrites
        // it, and completing the channel resets it to -1. Clearing it eagerly would lose
        // progress if this channel is interrupted again before it has ticked once.
        long taskId = -1;
        int epoch = 0;
        int total = effectiveChannel(world, npcId, op.channelTicks());
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec != null && exec.globalTaskId != null && world.taskPool != null) {
            GlobalTask task = world.taskPool.get(exec.globalTaskId);
            if (task != null) {
                taskId = task.id;
                if (task.channelRemainingTicks > 0) {
                    total = Math.min(task.channelRemainingTicks, total);
                }
                epoch = ++task.channelEpoch; // invalidate any stale channel from an earlier execution
            }
        }
        Pending p = new Pending(future, op, world, npcId, taskId, epoch, total);
        pending.add(p);

        future.thenRun(() -> {
            if (p.cancelled) return; // orphaned channel from a released task — don't produce output
            try {
                executeAsyncAction(op, world, npcId);
            } catch (ResourceShortageException e) {
                var e2 = world.get(npcId, TaskExecutor.class);
                if (e2 != null && e2.globalTaskId != null) {
                    world.taskPool.markAwaitingResources(
                            e2.globalTaskId, npcId, e.requestedItems(), world);
                    e2.releaseGlobalTask();
                    // 清掉队列里失败的全局任务包，否则下一 tick 会重复引导同一 op
                    // （第二次短路时 globalTaskId 已为 null，catch 空操作，白白多引导一趟）。
                    e2.npcQueue.clearCurrentWithoutResume();
                }
            } catch (Throwable t) {
                Log.warn(TAG, "executeAsyncAction threw unexpected error: {}", t.getMessage());
            }
        });

        return future;
    }

    /** Effective channel duration for this NPC: base channelTicks divided by WORK_SPEED. */
    private static int effectiveChannel(World world, long npcId, int baseTicks) {
        float work = (world.entityOps != null) ? world.entityOps.getWorkSpeed(npcId) : 1f;
        if (work <= 1f) return baseTicks;
        return Math.max(1, (int) Math.ceil(baseTicks / work));
    }

    /** Called every MC tick. Decrements countdowns, checkpoints progress, completes futures. */
    public void tickAll() {
        if (pending.isEmpty()) return;

        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (p.cancelled) {
                it.remove();
                continue;
            }

            // Validity: the owning task must still be bound to this NPC and still in
            // progress under the same epoch. If the task was released / re-assigned /
            // parked, the channel is orphaned — cancel it so the stale countdown doesn't
            // complete and produce output a second time after a resume.
            GlobalTask task = p.taskId >= 0 ? owningTask(p) : null;
            boolean valid = p.taskId < 0 || (task != null && task.state == TaskState.IN_PROGRESS
                    && task.assignedNpcId != null && p.npcId == task.assignedNpcId
                    && task.channelEpoch == p.epoch);
            if (!valid) {
                p.cancelled = true;
                it.remove();
                p.future.complete(null); // resolves startAsyncOp bookkeeping; thenRun no-ops
                continue;
            }

            int remaining = p.remainingTicks - 1;
            if (remaining <= 0) {
                task.channelRemainingTicks = -1; // completed — no checkpoint to resume
                it.remove();
                p.future.complete(null); // → thenRun → executeAsyncAction
            } else {
                p.remainingTicks = remaining;
                if (task.channelRemainingTicks != remaining) {
                    task.channelRemainingTicks = remaining;
                    markTaskPoolDirty();
                }
            }
        }
    }

    /** The global task this channel belongs to, or null if the NPC no longer holds it. */
    @Nullable
    private GlobalTask owningTask(Pending p) {
        TaskExecutor exec = p.world.get(p.npcId, TaskExecutor.class);
        if (exec == null || exec.globalTaskId == null || exec.globalTaskId != p.taskId) return null;
        if (p.world.taskPool == null) return null;
        return p.world.taskPool.get(p.taskId);
    }

    /** Mark the persisted task pool dirty so the latest channel checkpoint is saved. */
    private static void markTaskPoolDirty() {
        var sd = WandscapeEngine.getTaskPoolSavedData();
        if (sd != null) sd.markChanged();
    }

    public boolean hasPendingOps() {
        return !pending.isEmpty();
    }

    /**
     * Channel progress for the async op targeting {@code target}.
     * @return {remainingTicks, totalTicks}, or {-1, -1} if no channel is running there.
     */
    public int[] getChannelProgress(GridPos target) {
        for (Pending p : pending) {
            if (p.cancelled) continue;
            GridPos t = p.op.target();
            if (t != null && t.equals(target)) {
                return new int[]{p.remainingTicks, p.op.channelTicks()};
            }
        }
        return new int[]{-1, -1};
    }

    // ── Precondition check implementations ──

    private void checkPreconditions(AtomicOp.BlockInteractOp op, World world, long npcId) {
        String action = op.action().id();
        Map<String, String> params = op.params();
        switch (action) {
            case "synthesize" -> checkSynthesizePreconditions(params, npcId);
            case "decompose" -> checkDecomposePreconditions(params, npcId);
            case "craft_wand" -> checkCraftWandPreconditions(params, npcId);
            case "craft_spell" -> checkCraftSpellPreconditions(params, npcId);
            case "brew_potion" -> checkBrewPotionPreconditions(params, npcId);
            default -> {}
        }
    }

    private void checkSynthesizePreconditions(Map<String, String> params, long npcId) {
        checkElements("production:synthesize", params, npcId);
    }

    private void checkDecomposePreconditions(Map<String, String> params, long npcId) {
        // Decompose recycles surplus warehouse items into elements.
        // It must NEVER throw ResourceShortageException, which would cause the engine
        // to auto-synthesize materials just to decompose them in an infinite loop.
    }

    private void checkCraftWandPreconditions(Map<String, String> params, long npcId) {
        checkElements("production:craft_wand", params, npcId);
    }

    private void checkCraftSpellPreconditions(Map<String, String> params, long npcId) {
        checkElements("production:craft_spell", params, npcId);
    }

    /** Element shortage check shared by every element-costing production blueprint. */
    private void checkElements(String blueprintId, Map<String, String> params, long npcId) {
        Map<ElementType, Long> required = ProductionEligibility.requiredElementsFromStrings(blueprintId, params);
        if (required.isEmpty()) return;
        Level level = getNpcLevel(npcId);
        if (level == null) return;
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;
        UUID colonyId = findStorageColonyId();
        for (var e : required.entrySet()) {
            if (bank.countElement(colonyId, e.getKey()) < e.getValue()) {
                String elementId = e.getKey().name().toLowerCase();
                Log.warn(TAG, "{}: insufficient {} (need={})", blueprintId, e.getKey(), e.getValue());
                throw new ResourceShortageException(
                        List.of(new ResourceStack(new ResourceId(elementId), e.getValue().intValue())));
            }
        }
    }

    private void checkBrewPotionPreconditions(Map<String, String> params, long npcId) {
        checkElements("production:brew_potion", params, npcId);
        String recipeId = params.get("recipe_id");
        int count = parseCount(params);
        if (recipeId == null || count <= 0 || productionRecipeLoader == null) return;
        var recipe = productionRecipeLoader.getPotionRecipes().get(recipeId);
        if (recipe == null) return;
        Level level = getNpcLevel(npcId);
        if (level == null) return;
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;
        UUID colonyId = findStorageColonyId();
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
    }

    // ── Action implementations ──

    private void executeAsyncAction(AtomicOp.BlockInteractOp op, World world, long npcId) {
        String action = op.action().id();
        Map<String, String> params = op.params();

        switch (action) {
            case "gather" -> executeGather(op.target(), params, world, npcId);
            case "decompose" -> executeDecompose(params, world, npcId);
            case "synthesize" -> executeSynthesize(params, world, npcId);
            case "craft_wand" -> executeCraftWand(params, world, npcId);
            case "craft_spell" -> executeCraftSpell(params, world, npcId);
            case "brew_potion" -> executeBrewPotion(params, world, npcId);
            default -> Log.warn(TAG, "Unknown async block_interact action: {}", action);
        }
    }

    private void executeGather(GridPos anchor, Map<String, String> params, World world, long npcId) {
        String element = params.getOrDefault("element", "wood");
        int amount = parseAmount(params);

        ElementType elem;
        try {
            elem = ElementType.valueOf(element.toUpperCase());
        } catch (IllegalArgumentException e) {
            Log.warn(TAG, "block_interact gather: unknown element {}", element);
            return;
        }

        Level level = getNpcLevel(npcId);
        if (level == null) return;

        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) {
            Log.warn(TAG, "block_interact gather: ColonyItemBank not available, cannot inject {}", element);
            return;
        }

        // Resolve colony from the anchor position (the node building), not findStorageColony()
        BlockPos pos = new BlockPos(anchor.x(), anchor.y(), anchor.z());
        BuildingApi buildingApi = WandscapeApis.getBuildingApi();
        BuildingData bd = buildingApi.getBuildingAt(pos);
        UUID colonyId = (bd != null && bd.getColonyId() != null) ? bd.getColonyId() : new UUID(0, 0);

        bank.addElement(colonyId, elem, amount);

        // Notify listener to wake any AWAITING_RESOURCES tasks
        world.taskPool.onResourceAdded(new ResourceId(element), amount);

        // ── Transport visualization: elements fly NPC → warehouse ──
        launchElementTransport(element, amount, world, npcId);

        spawnCompletionParticles(npcId);

        Log.info(TAG, "block_interact gather complete: {} x{} → colony {} warehouse ({} total)",
                element, amount, colonyId.toString().substring(0, 8), bank.countElement(colonyId, elem));
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
        if (available <= 0) {
            Log.info(TAG, "decompose: no items available in warehouse for item={}", itemId);
            return;
        }

        int actualCount = (int) Math.min(count, available);

        // Decompose returns 1/N of the item's element value (anti item-duplication):
        // source is build_cost — same lookup as shop sale profit.
        Map<ElementType, Long> yield = mappings.getItemElementValue(itemId);

        if (yield.isEmpty()) {
            Log.warn(TAG, "decompose: no element value for {}", itemId);
            return;
        }

        long totalValue = 0;
        for (var v : yield.values()) totalValue += v;

        double divisor = Config.ELEMENT_DECOMPOSE_DIVISOR.get();

        // Refuse up front when actualCount × total value < divisor: the batch would
        // burn items and yield 0 elements (floor division truncates to zero).
        if (actualCount * totalValue < divisor) {
            Log.warn(TAG, "decompose: refuse {} x{} — total value {} < {}", itemId, actualCount,
                    actualCount * totalValue, divisor);
            return;
        }

        bank.consume(colonyId, key, actualCount);

        ColonyResourceAccess resources = world.colonyResources;
        if (resources == null) {
            Log.warn(TAG, "decompose: colonyResources is null");
            bank.add(colonyId, key, actualCount);
            return;
        }

        for (var entry : yield.entrySet()) {
            long total = (long) ((entry.getValue() * actualCount) / divisor);
            if (total <= 0) continue;
            resources.addResource(new ResourceId(entry.getKey().name().toLowerCase()), (int) total);
            Log.info(TAG, "decompose: {} x{} → {} x{} (1/{} of value)", itemId, actualCount,
                    entry.getKey().name().toLowerCase(), total, divisor);
        }

        spawnCompletionParticles(npcId);
    }

    /** Element cost to craft/synthesize: base × config multiplier, rounded up so we never underpay. */
    private static long scaledCraftCost(long base) {
        double multiplier = Config.ELEMENT_CRAFT_COST_MULTIPLIER.get();
        return (long) Math.ceil(base * multiplier);
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
            long needed = scaledCraftCost(entry.getValue() * count);
            if (bank.countElement(colonyId, entry.getKey()) < needed) {
                String elementId = entry.getKey().name().toLowerCase();
                Log.warn(TAG, "synthesize: insufficient {} (need={})", entry.getKey(), needed);
                throw new ResourceShortageException(
                        List.of(new ResourceStack(new ResourceId(elementId), (int) needed)));
            }
        }

        for (var entry : recipe.cost().entrySet()) {
            bank.consumeElement(colonyId, entry.getKey(), scaledCraftCost(entry.getValue() * count));
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
            long needed = scaledCraftCost(entry.getValue() * count);
            if (bank.countElement(colonyId, entry.getKey()) < needed) {
                String elementId = entry.getKey().name().toLowerCase();
                Log.warn(TAG, "craft_wand: insufficient {} (need={})", entry.getKey(), needed);
                throw new ResourceShortageException(
                        List.of(new ResourceStack(new ResourceId(elementId), (int) needed)));
            }
        }

        for (var entry : recipe.cost().entrySet()) {
            bank.consumeElement(colonyId, entry.getKey(), scaledCraftCost(entry.getValue() * count));
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

    /** 魔法工坊合成卷轴：产出 spell_scroll 并绑定 magic_id 入殖民地仓库（C4）。 */
    private void executeCraftSpell(Map<String, String> params, World world, long npcId) {
        String recipeId = params.get("recipe_id");
        int count = parseCount(params);
        if (recipeId == null || count <= 0) {
            Log.warn(TAG, "craft_spell: invalid params recipe_id={} count={}", recipeId, count);
            return;
        }

        ProductionRecipeLoader recipes = productionRecipeLoader;
        if (recipes == null) {
            Log.warn(TAG, "craft_spell: ProductionRecipeLoader not set");
            return;
        }

        CraftSpellRecipe recipe = recipes.getSpellRecipes().get(recipeId);
        if (recipe == null) {
            Log.warn(TAG, "craft_spell: recipe not found: {}", recipeId);
            return;
        }

        Level level = getNpcLevel(npcId);
        if (level == null) return;

        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        UUID colonyId = findStorageColonyId();

        for (var entry : recipe.cost().entrySet()) {
            long needed = scaledCraftCost(entry.getValue() * count);
            if (bank.countElement(colonyId, entry.getKey()) < needed) {
                String elementId = entry.getKey().name().toLowerCase();
                Log.warn(TAG, "craft_spell: insufficient {} (need={})", entry.getKey(), needed);
                throw new ResourceShortageException(
                        List.of(new ResourceStack(new ResourceId(elementId), (int) needed)));
            }
        }

        for (var entry : recipe.cost().entrySet()) {
            bank.consumeElement(colonyId, entry.getKey(), scaledCraftCost(entry.getValue() * count));
        }

        var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(recipe.outputItem()));
        if (item == null) {
            Log.warn(TAG, "craft_spell: output item not found: {}", recipe.outputItem());
            return;
        }

        CompoundTag nbt = new CompoundTag();
        nbt.putString(SpellItem.MAGIC_ID_KEY, recipe.magicId());

        ItemStack stack = new ItemStack(item, count);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt.copy()));

        bank.add(colonyId, ItemKey.of(recipe.outputItem(), nbt.copy()), count);

        // ── Transport visualization: scroll flies NPC → warehouse ──
        launchItemTransport(ItemKey.of(recipe.outputItem(), nbt.copy()), count, world, npcId);

        Log.info(TAG, "craft_spell: {} x{} → warehouse (magic_id={})", recipe.outputItem(), count, recipe.magicId());
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
            long needed = scaledCraftCost(entry.getValue() * count);
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
            bank.consumeElement(colonyId, entry.getKey(), scaledCraftCost(entry.getValue() * count));
        }

        for (String inputItemId : recipe.inputItems()) {
            bank.consume(colonyId, ItemKey.of(inputItemId, null), count);
        }

        CompoundTag outputNbt = recipe.outputNbt() != null ? recipe.outputNbt().copy() : null;
        if (outputNbt != null) {
            substitutePlaceholders(outputNbt, colonyId);
        }

        ItemKey outputKey = ItemKey.of(recipe.outputItem(), outputNbt);
        bank.add(colonyId, outputKey, count);

        // ── Transport visualization: potion flies NPC → warehouse ──
        launchItemTransport(outputKey, count, world, npcId);

        Log.info(TAG, "brew_potion: {} x{} → warehouse", recipe.outputItem(), count);
        spawnCompletionParticles(npcId);
    }

    private static void substitutePlaceholders(CompoundTag tag, UUID colonyId) {
        if (tag == null || colonyId == null) return;
        String colonyIdStr = colonyId.toString();
        for (String key : tag.getAllKeys()) {
            byte type = tag.getTagType(key);
            if (type == Tag.TAG_STRING) {
                String val = tag.getString(key);
                if (val.contains("$colony_id")) {
                    tag.putString(key, val.replace("$colony_id", colonyIdStr));
                }
            } else if (type == Tag.TAG_COMPOUND) {
                substitutePlaceholders(tag.getCompound(key), colonyId);
            }
        }
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
            return;
        }

        UUID colonyId = resolveColonyId(npc, world);
        BlockPos storagePos = findNearestStorage(colonyId, npc.blockPosition());
        BlockPos to = storagePos != null ? storagePos : npc.blockPosition().offset(0, 2, 0);
        BlockPos from = npc.blockPosition();

        ItemKey key = ItemKey.of(itemId, null);
        transporter.send(key, amount, from, to, npc.level(), npcId);
    }

    /** Launch transport animation for produced items (synthesize/craft_wand/brew_potion). */
    private void launchItemTransport(ItemKey outputKey, int count, World world, long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isRemoved()) return;

        UUID colonyId = resolveColonyId(npc, world);
        BlockPos storagePos = findNearestStorage(colonyId, npc.blockPosition());
        BlockPos to = storagePos != null ? storagePos : npc.blockPosition().offset(0, 2, 0);
        BlockPos from = npc.blockPosition();

        transporter.send(outputKey, count, from, to, npc.level(), npcId);
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
        if (ids != null && !ids.isEmpty()) {
            BlockPos nearest = null;
            double best = Double.MAX_VALUE;
            for (UUID id : ids) {
                BuildingData bd = api.getBuilding(id);
                if (bd == null || bd.isShutdown()) continue;
                BlockPos p = bd.getPosition();
                double d = p.distSqr(npcPos);
                if (d < best) { best = d; nearest = p; }
            }
            if (nearest != null) return nearest;
        }
        // Fallback to town hall
        var govIds = api.getBuildingsByCategory(colonyId, "government");
        if (govIds == null || govIds.isEmpty()) return null;
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (UUID id : govIds) {
            BuildingData bd = api.getBuilding(id);
            if (bd == null || bd.isShutdown()) continue;
            BlockPos p = bd.getPosition();
            double d = p.distSqr(npcPos);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest;
    }
}
