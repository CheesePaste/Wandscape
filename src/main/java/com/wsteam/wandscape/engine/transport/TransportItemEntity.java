package com.wsteam.wandscape.engine.transport;

import com.wsteam.wandscape.Wandscape;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.wsteam.wandscape.road.core.TransportRoute;
import com.wsteam.wandscape.road.core.SplineLeg;
import com.wsteam.wandscape.road.core.CurveSample;
import com.wsteam.wandscape.road.algorithm.RoadRouter;

/**
 * A visual-only ItemEntity used by {@link ItemTransportManager} for flying item animations.
 *
 * <p>Overrides {@link #shouldBeSaved()} to return {@code false} so these items
 * are never written to disk — preventing frozen floating items after world reload.</p>
 */
public class TransportItemEntity extends ItemEntity {
    private TransportRoute route;
    private int legIndex;
    private int segmentElapsed;

    public TransportItemEntity(EntityType<? extends ItemEntity> type, Level level) {
        super(type, level);
    }

    public TransportItemEntity(Level level, double x, double y, double z, ItemStack stack) {
        super(Wandscape.TRANSPORT_ITEM.get(), level);
        this.setPos(x, y, z);
        this.setYRot(this.random.nextFloat() * 360.0F);
        this.setItem(stack);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    public void setRoute(TransportRoute route) {
        this.route = route;
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide() && this.route != null && !this.route.isEmpty()) {
            tickClientAnimation();
        }
    }

    private void tickClientAnimation() {
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setPickUpDelay(Short.MAX_VALUE);

        if (this.legIndex >= this.route.legs().size()) {
            this.discard();
            return;
        }

        SplineLeg leg = this.route.legs().get(this.legIndex);
        int ticksPerBlock = leg.offRoad() ? RoadRouter.TICKS_PER_BLOCK_OFF_ROAD : RoadRouter.TICKS_PER_BLOCK_ON_ROAD;
        int legDuration = Math.max(1, (int) leg.getApproxLength() * ticksPerBlock);
        
        int segElapsed = this.segmentElapsed + 1;

        if (segElapsed >= legDuration) {
            this.segmentElapsed = 0;
            this.legIndex++;
            if (this.legIndex >= this.route.legs().size()) {
                this.discard();
                return;
            }
            SplineLeg next = this.route.legs().get(this.legIndex);
            int nextDuration = Math.max(1, (int) next.getApproxLength() * (next.offRoad() ? RoadRouter.TICKS_PER_BLOCK_OFF_ROAD : RoadRouter.TICKS_PER_BLOCK_ON_ROAD));
            tickLeg(next, 0, nextDuration);
        } else {
            this.segmentElapsed = segElapsed;
            tickLeg(leg, segElapsed, legDuration);
        }
    }

    private void tickLeg(SplineLeg leg, int elapsed, int duration) {
        double t = (double) elapsed / duration;
        double u = leg.uStart() + (leg.uEnd() - leg.uStart()) * t;
        
        CurveSample sample = leg.spline().evaluate(u);
        double x = sample.position().x();
        double z = sample.position().z();
        double y = sample.position().y();

        if (leg.offRoad()) {
            y += Math.sin(t * Math.PI) * 1.5;
        }

        this.xo = this.getX();
        this.yo = this.getY();
        this.zo = this.getZ();
        this.xOld = this.getX();
        this.yOld = this.getY();
        this.zOld = this.getZ();

        double nextT = (double) (elapsed + 1) / duration;
        double nextU = leg.uStart() + (leg.uEnd() - leg.uStart()) * nextT;
        CurveSample nextSample = leg.spline().evaluate(nextU);
        double nx = nextSample.position().x();
        double nz = nextSample.position().z();
        double ny = nextSample.position().y();
        if (leg.offRoad()) {
            ny += Math.sin(nextT * Math.PI) * 1.5;
        }
        
        this.setDeltaMovement(nx - x, ny - y, nz - z);
        this.hasImpulse = true;
        this.setPos(x, y, z);
    }
}
