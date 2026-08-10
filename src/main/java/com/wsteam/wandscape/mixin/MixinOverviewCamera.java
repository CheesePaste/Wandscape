package com.wsteam.wandscape.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.wsteam.wandscape.overview.client.OverviewClientState;

@Mixin(Camera.class)
public abstract class MixinOverviewCamera {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void onSetupTail(BlockGetter level, Entity entity, boolean detached,
                             boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        if (OverviewClientState.isActive() && !com.wsteam.wandscape.road.client.SplineEditorClientState.isEditing()) {
            setPosition(
                    OverviewClientState.getCamX(),
                    OverviewClientState.getCamY(),
                    OverviewClientState.getCamZ());
            setRotation(
                    OverviewClientState.getCamYaw(),
                    OverviewClientState.getCamPitch());
        }
    }
}
