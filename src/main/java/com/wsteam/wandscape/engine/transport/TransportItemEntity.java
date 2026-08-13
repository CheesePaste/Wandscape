package com.wsteam.wandscape.engine.transport;

import com.wsteam.wandscape.Wandscape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A visual-only ItemEntity used by {@link ItemTransportManager} for flying item animations.
 *
 * <p>Flies a straight line from {@code from} to {@code to} over the server-computed
 * duration; off-road flights get a jump arc, on-road flights stay flat.
 *
 * <p>Overrides {@link #shouldBeSaved()} to return {@code false} so these items
 * are never written to disk — preventing frozen floating items after world reload.</p>
 */
public class TransportItemEntity extends ItemEntity {
    private BlockPos from;
    private BlockPos to;
    private int duration;
    private int elapsed;
    private boolean onRoad;

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

    public void setFlight(BlockPos from, BlockPos to, int duration, boolean onRoad) {
        this.from = from;
        this.to = to;
        this.duration = Math.max(1, duration);
        this.onRoad = onRoad;
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide() && this.from != null && this.to != null && this.duration > 0) {
            tickClientAnimation();
        }
    }

    private void tickClientAnimation() {
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setPickUpDelay(Short.MAX_VALUE);

        this.elapsed++;
        if (this.elapsed >= this.duration) {
            this.discard();
            return;
        }

        double t = (double) this.elapsed / this.duration;
        double x = from.getX() + 0.5 + (to.getX() - from.getX()) * t;
        double z = from.getZ() + 0.5 + (to.getZ() - from.getZ()) * t;
        double y = from.getY() + 0.5 + (to.getY() - from.getY()) * t;
        if (!this.onRoad) {
            y += Math.sin(t * Math.PI) * 1.5;
        }

        double nextT = (double) (this.elapsed + 1) / this.duration;
        double nx = from.getX() + 0.5 + (to.getX() - from.getX()) * nextT;
        double nz = from.getZ() + 0.5 + (to.getZ() - from.getZ()) * nextT;
        double ny = from.getY() + 0.5 + (to.getY() - from.getY()) * nextT;
        if (!this.onRoad) {
            ny += Math.sin(nextT * Math.PI) * 1.5;
        }

        this.xo = this.getX();
        this.yo = this.getY();
        this.zo = this.getZ();
        this.xOld = this.getX();
        this.yOld = this.getY();
        this.zOld = this.getZ();

        this.setDeltaMovement(nx - x, ny - y, nz - z);
        this.hasImpulse = true;
        this.setPos(x, y, z);
    }
}
