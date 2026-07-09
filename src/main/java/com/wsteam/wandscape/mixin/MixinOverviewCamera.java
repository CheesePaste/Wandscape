package com.wsteam.wandscape.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class MixinOverviewCamera {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void onSetupTail(BlockGetter level, Entity entity, boolean detached,
                             boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        if (com.wsteam.wandscape.overview.client.OverviewClientState.isActive()) {
            setPosition(
                    com.wsteam.wandscape.overview.client.OverviewClientState.getCamX(),
                    com.wsteam.wandscape.overview.client.OverviewClientState.getCamY(),
                    com.wsteam.wandscape.overview.client.OverviewClientState.getCamZ());
            setRotation(
                    com.wsteam.wandscape.overview.client.OverviewClientState.getCamYaw(),
                    com.wsteam.wandscape.overview.client.OverviewClientState.getCamPitch());
        } else if (com.wsteam.wandscape.building.editor.BuildingEditorClientState.isEditing()) {
            setPosition(
                    com.wsteam.wandscape.building.editor.BuildingEditorClientState.getCamX(),
                    com.wsteam.wandscape.building.editor.BuildingEditorClientState.getCamY(),
                    com.wsteam.wandscape.building.editor.BuildingEditorClientState.getCamZ());
            setRotation(
                    com.wsteam.wandscape.building.editor.BuildingEditorClientState.getCamYaw(),
                    com.wsteam.wandscape.building.editor.BuildingEditorClientState.getCamPitch());
        } else if (com.wsteam.wandscape.road.client.SplineEditorClientState.isEditing()) {
            setPosition(
                    com.wsteam.wandscape.road.client.SplineEditorClientState.getCamX(),
                    com.wsteam.wandscape.road.client.SplineEditorClientState.getCamY(),
                    com.wsteam.wandscape.road.client.SplineEditorClientState.getCamZ());
            setRotation(
                    com.wsteam.wandscape.road.client.SplineEditorClientState.getCamYaw(),
                    com.wsteam.wandscape.road.client.SplineEditorClientState.getCamPitch());
        }
    }
}
