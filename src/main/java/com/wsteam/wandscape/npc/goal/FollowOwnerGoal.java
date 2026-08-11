package com.wsteam.wandscape.npc.goal;

import java.util.EnumSet;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

/**
 * NPC 跟随玩家 AI Goal：开启跟随模式后，NPC 保持跟随拥有者移动，超距自动平滑传送。
 */
public class FollowOwnerGoal extends Goal {

    private final WandscapeNpc npc;
    private Player owner;
    private final double speedModifier;
    private final float startDistance;
    private final float stopDistance;
    private int timeToRecalcPath;

    public FollowOwnerGoal(WandscapeNpc npc, double speedModifier, float startDistance, float stopDistance) {
        this.npc = npc;
        this.speedModifier = speedModifier;
        this.startDistance = startDistance;
        this.stopDistance = stopDistance;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!npc.isFollowing()) return false;
        if (npc.getFollowOwnerUuid() == null) return false;
        if (!(npc.level() instanceof ServerLevel level)) return false;

        Player player = level.getPlayerByUUID(npc.getFollowOwnerUuid());
        if (player == null || player.isSpectator() || player.isDeadOrDying()) return false;
        if (npc.distanceToSqr(player) < (double) (startDistance * startDistance)) return false;

        this.owner = player;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!npc.isFollowing()) return false;
        if (this.owner == null || !this.owner.isAlive() || this.owner.isSpectator()) return false;
        return npc.distanceToSqr(this.owner) > (double) (stopDistance * stopDistance);
    }

    @Override
    public void start() {
        this.timeToRecalcPath = 0;
    }

    @Override
    public void stop() {
        this.owner = null;
        this.npc.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.owner == null) return;

        this.npc.getLookControl().setLookAt(this.owner, 10.0F, (float) this.npc.getMaxHeadXRot());
        if (--this.timeToRecalcPath <= 0) {
            this.timeToRecalcPath = 10;
            double distSqr = this.npc.distanceToSqr(this.owner);
            // 超出 32 格自动平滑传送至玩家附近
            if (distSqr > 32.0 * 32.0) {
                this.teleportToOwner();
            } else {
                this.npc.getNavigation().moveTo(this.owner, this.speedModifier);
            }
        }
    }

    private void teleportToOwner() {
        if (this.owner == null) return;
        BlockPos ownerPos = this.owner.blockPosition();
        for (int i = 0; i < 10; i++) {
            int dx = this.npc.getRandom().nextInt(5) - 2;
            int dz = this.npc.getRandom().nextInt(5) - 2;
            BlockPos targetPos = ownerPos.offset(dx, 0, dz);
            if (this.npc.level().isEmptyBlock(targetPos) && !this.npc.level().isEmptyBlock(targetPos.below())) {
                this.npc.moveTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, this.npc.getYRot(), this.npc.getXRot());
                this.npc.getNavigation().stop();
                return;
            }
        }
    }
}
