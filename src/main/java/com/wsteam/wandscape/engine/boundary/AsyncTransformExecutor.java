package com.wsteam.wandscape.engine.boundary;

import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.Inventory;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.op.executor.OpExecutor;
import com.wsteam.wandscape.content.task.op.executor.ResourceShortageException;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Async TransformOp executor — exercises V2.5 CompletableFuture model.
 *
 * <p>Returns an incomplete future from {@link World#startAsyncOp} (Promise pattern).
 * The engine stores this future in TaskExecutor.pendingFuture and does NOT
 * re-invoke execute(). When the future completes, the engine advances stepIndex.
 *
 * <p>The actual block placement happens via the future's {@code thenRun} callback.
 *
 * <p>When the op carries a {@link AtomicOp.TransformOp#consumable()}, the item is
 * removed from NPC inventory before the delay countdown starts. On shortage, a
 * {@link ResourceShortageException} is thrown — the engine marks the task
 * AWAITING_RESOURCES and releases the NPC.
 *
 * <p>When an existing block in the world is broken, cleared, flattened, or replaced,
 * this executor intercepts the destruction, calculates dropped items, and deposits
 * the materials directly into the colony warehouse (no flying-item animation — batch
 * demolition would flood the client with transport entities).
 */
public class AsyncTransformExecutor implements OpExecutor<AtomicOp.TransformOp> {

    private static final String TAG = "AsyncTransformExecutor";

    /** NPC 施法音节流间隔（tick）：与 WandscapeBlockOps 方块放置/拆除音同频，避免每块方块都播 Evoker 施法声刷屏。 */
    private static final int NPC_CAST_THROTTLE_TICKS = 10;

    private final int delayTicks;

    record Pending(CompletableFuture<Void> future, AtomicOp.TransformOp op, World world,
                   long npcId, int remainingTicks) {}

    private final List<Pending> pending = new ArrayList<>();

    public AsyncTransformExecutor(int delayTicks) {
        this.delayTicks = delayTicks;
        Log.info(TAG, "AsyncTransformExecutor delay={} ticks", delayTicks);
    }

    @Override
    public Class<AtomicOp.TransformOp> opType() {
        return AtomicOp.TransformOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.TransformOp op, World world, long npcId) {
        // ── Consumable check: remove from NPC inventory before delay countdown ──
        if (op.consumable() != null) {
            // Strip blockstate to check element mapping. Blocks without element
            // mappings are "free" materials — skip inventory consumption and place
            // directly (they were excluded from warehouse transport by computeMaterialData).
            String pureId = op.consumable().resource().id().replaceAll("\\[.*?\\]", "").trim();
            if (WandscapeApis.getElementApi().hasElementMapping(pureId)) {
                Inventory inv = world.get(npcId, Inventory.class);
                if (inv == null || !inv.hasEnough(op.consumable().resource(),
                        op.consumable().amount())) {
                    return CompletableFuture.failedFuture(
                            new ResourceShortageException(List.of(op.consumable())));
                }
                inv.remove(op.consumable().resource(), op.consumable().amount());
            }
        }

        // ── Placement (existing delay-tick mechanism, shared by both paths) ──
        if (delayTicks <= 0) {
            performSalvage(op, world, npcId);
            BlockOps blockOps = world.blockOps;
            if (blockOps != null) {
                blockOps.setBlock(op.target(), op.to());
                blockOps.setBlockEntityData(op.target(), op.blockNbtBase64());
            }
            return CompletableFuture.completedFuture(null);
        }

        // ① Get a promise (CompletableFuture) from the world gate
        CompletableFuture<Void> future = world.startAsyncOp(
                "place_" + op.to().id() + "_" + op.target());

        // ② Schedule: after delayTicks, place block then complete the promise
        //    Engine stores this future in TaskExecutor.pendingFuture,
        //    does NOT re-invoke execute(). When complete() fires, engine
        //    advances stepIndex and calls execute() for the NEXT op.
        pending.add(new Pending(future, op, world, npcId, effectiveDelay(world, npcId)));

        // Hook: place block when delay expires
        future.thenRun(() -> {
            Pending p = findPending(future);
            if (p == null) return;
            pending.remove(p);

            // Intercept & return salvaged block items to colony warehouse with visual flight
            performSalvage(p.op(), p.world(), p.npcId());

            if (p.world().blockOps != null) {
                p.world().blockOps.setBlock(p.op().target(), p.op().to());
                p.world().blockOps.setBlockEntityData(p.op().target(), p.op().blockNbtBase64());
            }
            // Visual feedback on the NPC that performed the work
            WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(p.npcId());
            if (npc != null) {
                npc.doWorkAnimation(new BlockPos(
                        p.op().target().x(), p.op().target().y(), p.op().target().z()));
                // NPC 施法放置音（守卫/自防御不走这里，避免与 GuardCombat 开火音重叠）
                // 节流与方块放置/拆除音同频：整栋楼连续施工时不会每块都响
                if (npc.level() instanceof ServerLevel sl) {
                    SoundService.playAtThrottled(sl, p.op().target().x() + 0.5,
                            p.op().target().y() + 0.5, p.op().target().z() + 0.5,
                            WandscapeSounds.NPC_CAST, SoundSource.NEUTRAL, 0.5f, 1.0f,
                            NPC_CAST_THROTTLE_TICKS);
                }
            }
        });

        return future;
    }

    /** Effective per-block delay for this NPC: base delayTicks divided by WORK_SPEED. */
    private int effectiveDelay(World world, long npcId) {
        float work = (world.entityOps != null) ? world.entityOps.getWorkSpeed(npcId) : 1f;
        if (work <= 1f) return delayTicks;
        return Math.max(1, (int) Math.ceil(delayTicks / work));
    }

    /** Called every MC tick. Decrements countdowns and completes futures. */
    public void tickAll() {
        if (pending.isEmpty()) return;

        // Collect to-complete BEFORE calling complete() — complete() triggers
        // thenRun which modifies pending, so iterate-copy is required.
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
            f.complete(null); // → triggers thenRun → places block
        }
    }

    public boolean hasPendingOps() { return !pending.isEmpty(); }

    private Pending findPending(CompletableFuture<Void> future) {
        for (Pending p : pending) {
            if (p.future() == future) return p;
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════
    //  Dismantling / Salvage Logistics Interception
    // ════════════════════════════════════════════════════════════

    private void performSalvage(AtomicOp.TransformOp op, World world, long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        Level level = npc != null ? npc.level() : null;
        if (level == null && ServerLifecycleHooks.getCurrentServer() != null) {
            level = ServerLifecycleHooks.getCurrentServer().overworld();
        }
        if (!(level instanceof ServerLevel sl)) return;

        BlockPos bp = new BlockPos(op.target().x(), op.target().y(), op.target().z());
        BlockState oldState = sl.getBlockState(bp);
        if (!isSalvageable(oldState, sl, bp, op.to())) return;

        BlockEntity be = sl.getBlockEntity(bp);
        List<ItemStack> drops = Block.getDrops(oldState, sl, bp, be, npc, ItemStack.EMPTY);
        if (drops.isEmpty()) {
            if (!oldState.canBeReplaced()) {
                Item item = oldState.getBlock().asItem();
                if (item != Items.AIR) {
                    drops = List.of(new ItemStack(item, 1));
                }
            }
        }
        if (drops.isEmpty()) return;

        UUID colonyId = resolveColonyId(npc, world, bp);
        ColonyItemBank bank = ColonyItemBank.get(sl);
        if (bank == null || colonyId == null) return;

        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(drop.getItem()).toString();
            ItemKey key = ItemKey.of(itemId, null);
            int count = drop.getCount();
            // Direct deposit — no flying-item animation. Batch demolition would spawn
            // hundreds of transport entities on the client, so shattered blocks just
            // bank their drops instantly.
            bank.add(colonyId, key, count);
            Log.info(TAG, "[Salvage] Dismantled item returned to warehouse: {} x{} (colony={})",
                    key.itemId(), count, colonyId.toString().substring(0, 8));
        }
    }

    private boolean isSalvageable(BlockState oldState, ServerLevel sl, BlockPos bp, BlockType toType) {
        if (oldState.isAir()) return false;
        if (!oldState.getFluidState().isEmpty()) return false;
        if (oldState.getDestroySpeed(sl, bp) < 0) return false;
        if (oldState.is(net.minecraft.tags.BlockTags.FIRE)) return false;
        if (toType != null && !toType.id().isEmpty() && !"minecraft:air".equals(toType.id())) {
            String pureOld = BuiltInRegistries.BLOCK.getKey(oldState.getBlock()).toString();
            String pureTo = toType.id().replaceAll("\\[.*?\\]", "").trim();
            return !pureOld.equals(pureTo); // same block type, no replacement salvage needed
        }
        return true;
    }

    private UUID resolveColonyId(@Nullable WandscapeNpc npc, World world, BlockPos bp) {
        if (npc != null) {
            var member = world.get(npc.ecsEntityId, ColonyMember.class);
            if (member != null && member.colonyId() != null) return member.colonyId();
            if (npc.colonyId != null) return npc.colonyId;
        }
        var colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi != null) {
            UUID cid = colonyApi.getColonyId(bp);
            if (cid != null) return cid;
        }
        return new UUID(0, 0);
    }
}
