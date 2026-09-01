package com.wsteam.wandscape.foundation.mixin;

import com.wsteam.wandscape.content.road.client.SplineEditorClientState;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overrides the camera position/rotation while the Spline Editor is in
 * top-down (bird's eye) view, mirroring {@link MixinOverviewCamera} for the
 * V-panel overview mode. Only active when the spline editor is editing AND
 * top-down view is enabled.
 */
@Mixin(Camera.class)
public abstract class MixinSplineEditorCamera {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void onSetupTail(BlockGetter level, Entity entity, boolean detached,
                             boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        if (!SplineEditorClientState.isEditing()) return;
        setPosition(
                SplineEditorClientState.getCamX(),
                SplineEditorClientState.getCamY(),
                SplineEditorClientState.getCamZ());
        setRotation(
                SplineEditorClientState.getCamYaw(),
                SplineEditorClientState.getCamPitch());
    }
}
