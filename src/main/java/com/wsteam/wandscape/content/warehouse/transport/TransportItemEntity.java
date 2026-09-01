package com.wsteam.wandscape.content.warehouse.transport;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.road.algorithm.RoadRouter;
import com.wsteam.wandscape.content.road.core.CurveSample;
import com.wsteam.wandscape.content.road.core.SplineLeg;
import com.wsteam.wandscape.content.road.core.SplineVec3;
import com.wsteam.wandscape.content.road.core.TransportRoute;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A visual-only ItemEntity used by {@link ItemTransportManager} for flying item animations.
 *
 * <p>Follows a multi-leg {@link TransportRoute} calculated along the colony road network:
 * cruising flat along roads and jumping in arcs over off-road segments.
 *
 * <p>Overrides {@link #shouldBeSaved()} to return {@code false} so these items
 * are never written to disk — preventing frozen floating items after world reload.</p>
 */
public class TransportItemEntity extends ItemEntity {

    private TransportRoute route;
    private int legIndex;
    private int legElapsed;
    private int ticksOnRoad = RoadRouter.DEFAULT_TICKS_ON_ROAD;
    private int ticksOffRoad = RoadRouter.DEFAULT_TICKS_OFF_ROAD;

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
        this.legIndex = 0;
        this.legElapsed = 0;
        try {
            this.ticksOnRoad = com.wsteam.wandscape.foundation.util.BalanceValues.transportTicksPerBlockOnRoad();
            this.ticksOffRoad = com.wsteam.wandscape.foundation.util.BalanceValues.transportTicksPerBlockOffRoad();
        } catch (Exception ignored) {
            this.ticksOnRoad = RoadRouter.DEFAULT_TICKS_ON_ROAD;
            this.ticksOffRoad = RoadRouter.DEFAULT_TICKS_OFF_ROAD;
        }
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
        int rate = leg.offRoad() ? ticksOffRoad : ticksOnRoad;
        int legDuration = Math.max(1, (int) Math.round(leg.getApproxLength() * rate));

        this.legElapsed++;
        if (this.legElapsed >= legDuration) {
            this.legElapsed = 0;
            this.legIndex++;
            if (this.legIndex >= this.route.legs().size()) {
                this.discard();
                return;
            }
            leg = this.route.legs().get(this.legIndex);
            rate = leg.offRoad() ? ticksOffRoad : ticksOnRoad;
            legDuration = Math.max(1, (int) Math.round(leg.getApproxLength() * rate));
        }

        double t = (double) this.legElapsed / legDuration;
        double u = leg.uStart() + (leg.uEnd() - leg.uStart()) * t;

        CurveSample sample = leg.spline().evaluate(u);
        SplineVec3 pos = sample.position();
        double x = pos.x();
        double y = pos.y() + (leg.offRoad() ? Math.sin(t * Math.PI) * 1.5 : 0.4);
        double z = pos.z();

        double nextT = (double) (this.legElapsed + 1) / legDuration;
        double nextU = leg.uStart() + (leg.uEnd() - leg.uStart()) * nextT;
        CurveSample nextSample = leg.spline().evaluate(nextU);
        SplineVec3 nextPos = nextSample.position();
        double nx = nextPos.x();
        double ny = nextPos.y() + (leg.offRoad() ? Math.sin(nextT * Math.PI) * 1.5 : 0.4);
        double nz = nextPos.z();

        this.xo = this.getX();
        this.yo = this.getY();
        this.zo = this.getZ();
        this.xOld = this.getX();
        this.yOld = this.getY();
        this.zOld = this.getZ();

        this.setDeltaMovement(nx - x, ny - y, nz - z);
        this.hasImpulse = true;
        this.setPos(x, y, z);

        // Subtle particle trail when cruising along roads
        if (!leg.offRoad() && this.random.nextInt(3) == 0) {
            this.level().addParticle(ParticleTypes.PORTAL,
                    x + (this.random.nextDouble() - 0.5) * 0.2,
                    y,
                    z + (this.random.nextDouble() - 0.5) * 0.2,
                    (this.random.nextDouble() - 0.5) * 0.1,
                    -0.05,
                    (this.random.nextDouble() - 0.5) * 0.1);
        }
    }
}
