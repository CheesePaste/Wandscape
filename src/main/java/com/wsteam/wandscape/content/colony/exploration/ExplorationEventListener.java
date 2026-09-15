package com.wsteam.wandscape.content.colony.exploration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import net.neoforged.fml.common.EventBusSubscriber;
import com.wsteam.wandscape.Wandscape;

/**
 * Event listener intercepting player exploration chest interactions and destructions.
 * Triggers reward processing only on naturally generated containers with un-unpacked loot tables.
 */
@EventBusSubscriber(modid = Wandscape.MODID)
public final class ExplorationEventListener {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getSide().isClient()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (event.getEntity().isSpectator()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;

        BlockPos pos = event.getPos();
        BlockEntity be = serverLevel.getBlockEntity(pos);
        if (!(be instanceof RandomizableContainer rc)) return;

        ResourceKey<LootTable> lootKey = rc.getLootTable();
        if (lootKey == null && be instanceof ChestBlockEntity) {
            // Check connected half for double chests
            BlockState state = serverLevel.getBlockState(pos);
            if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                Direction dir = ChestBlock.getConnectedDirection(state);
                BlockEntity other = serverLevel.getBlockEntity(pos.relative(dir));
                if (other instanceof RandomizableContainer otherRc) {
                    lootKey = otherRc.getLootTable();
                    if (lootKey != null) {
                        rc = otherRc;
                        pos = other.getBlockPos();
                    }
                }
            }
        }

        if (lootKey != null) {
            ExplorationRewardService.get().processReward(player, serverLevel, pos, lootKey);
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.isCanceled()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        BlockPos pos = event.getPos();
        BlockEntity be = serverLevel.getBlockEntity(pos);
        if (!(be instanceof RandomizableContainer rc)) return;

        ResourceKey<LootTable> lootKey = rc.getLootTable();
        if (lootKey != null) {
            ExplorationRewardService.get().processReward(player, serverLevel, pos, lootKey);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getSide().isClient()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (event.getEntity().isSpectator()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        if (event.getTarget() instanceof RandomizableContainer rc) {
            ResourceKey<LootTable> lootKey = rc.getLootTable();
            if (lootKey != null) {
                ExplorationRewardService.get().processReward(player, serverLevel, event.getTarget().blockPosition(), lootKey);
            }
        }
    }
}
