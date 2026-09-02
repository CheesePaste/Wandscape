package com.wsteam.wandscape.content.warehouse.transport;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.road.algorithm.RoadRouter;
import com.wsteam.wandscape.content.road.core.CurveSample;
import com.wsteam.wandscape.content.road.core.SplineLeg;
import com.wsteam.wandscape.content.road.core.SplineVec3;
import com.wsteam.wandscape.content.road.core.TransportRoute;
import com.wsteam.wandscape.foundation.util.BalanceValues;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    public static final int MAX_ACTIVE_VISUALS = 48;
    private static final AtomicInteger ACTIVE_COUNT = new AtomicInteger(0);
    private static final AtomicInteger CLIENT_ENTITY_ID = new AtomicInteger(-10000);

    public static int getActiveCount() {
        return ACTIVE_COUNT.get();
    }

    public static void resetActiveCount() {
        ACTIVE_COUNT.set(0);
    }

    public static int nextClientEntityId() {
        return CLIENT_ENTITY_ID.decrementAndGet();
    }

    private final AtomicBoolean counted = new AtomicBoolean(false);
    private TransportRoute route;
    private int legIndex;
    private int legElapsed;
    private int currentLegDuration = 1;
    private int maxLifetime = 1200;
    private int ticksOnRoad = RoadRouter.DEFAULT_TICKS_ON_ROAD;
    private int ticksOffRoad = RoadRouter.DEFAULT_TICKS_OFF_ROAD;
    private boolean debugStatic = false;

    public TransportItemEntity(EntityType<? extends ItemEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setPickUpDelay(Short.MAX_VALUE);
    }

    public TransportItemEntity(Level level, double x, double y, double z, ItemStack stack) {
        super(Wandscape.TRANSPORT_ITEM.get(), level);
        this.setPos(x, y, z);
        this.setYRot(this.random.nextFloat() * 360.0F);
        this.setItem(stack);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setPickUpDelay(Short.MAX_VALUE);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    public void markCounted() {
        if (this.counted.compareAndSet(false, true)) {
            ACTIVE_COUNT.incrementAndGet();
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (this.counted.compareAndSet(true, false)) {
            ACTIVE_COUNT.decrementAndGet();
        }
    }

    @Override
    public void onClientRemoval() {
        super.onClientRemoval();
        if (this.counted.compareAndSet(true, false)) {
            ACTIVE_COUNT.decrementAndGet();
        }
    }

    public void setRoute(TransportRoute route) {
        this.route = route;
        this.legIndex = 0;
        this.legElapsed = 0;
        try {
            this.ticksOnRoad = BalanceValues.transportTicksPerBlockOnRoad();
            this.ticksOffRoad = BalanceValues.transportTicksPerBlockOffRoad();
        } catch (Exception ignored) {
            this.ticksOnRoad = RoadRouter.DEFAULT_TICKS_ON_ROAD;
            this.ticksOffRoad = RoadRouter.DEFAULT_TICKS_OFF_ROAD;
        }

        if (route == null || route.isEmpty()) {
            this.maxLifetime = 1;
            this.currentLegDuration = 1;
            this.discard();
            return;
        }

        int expected = route.totalDuration(this.ticksOnRoad, this.ticksOffRoad);
        this.maxLifetime = expected + 60; // 3s grace buffer
        this.currentLegDuration = computeLegDuration(0);
    }

    private int computeLegDuration(int index) {
        if (this.route == null || index < 0 || index >= this.route.legs().size()) {
            return 1;
        }
        SplineLeg leg = this.route.legs().get(index);
        int rate = leg.offRoad() ? this.ticksOffRoad : this.ticksOnRoad;
        return Math.max(1, (int) Math.round(leg.getApproxLength() * rate));
    }

    @Override
    public void setUnlimitedLifetime() {
        super.setUnlimitedLifetime();
        this.debugStatic = true;
    }

    @Override
    public void tick() {
        // Debug static entity support
        if (this.debugStatic && this.route == null) {
            return;
        }

        if (!this.level().isClientSide() || this.route == null || this.route.isEmpty() || this.tickCount > this.maxLifetime) {
            this.discard();
            return;
        }

        this.tickCount++;
        this.xo = this.getX();
        this.yo = this.getY();
        this.zo = this.getZ();
        this.xOld = this.getX();
        this.yOld = this.getY();
        this.zOld = this.getZ();

        tickClientAnimation();
    }

    @Override
    public float getSpin(float partialTicks) {
        return ((float) this.tickCount + partialTicks) / 20.0F + this.bobOffs;
    }

    @Override
    public int getAge() {
        return this.tickCount;
    }

    private void tickClientAnimation() {
        try {
            if (this.legIndex >= this.route.legs().size()) {
                this.discard();
                return;
            }

            SplineLeg leg = this.route.legs().get(this.legIndex);

            this.legElapsed++;
            if (this.legElapsed >= this.currentLegDuration) {
                this.legElapsed = 0;
                this.legIndex++;
                if (this.legIndex >= this.route.legs().size()) {
                    this.discard();
                    return;
                }
                leg = this.route.legs().get(this.legIndex);
                this.currentLegDuration = computeLegDuration(this.legIndex);
            }

            int legDuration = this.currentLegDuration;
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
        } catch (Exception e) {
            this.discard();
        }
    }
}
