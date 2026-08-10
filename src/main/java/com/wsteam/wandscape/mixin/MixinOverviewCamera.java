package com.wsteam.wandscape.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.wsteam.wandscape.overview.client.OverviewClientState;

@Mixin(Camera.class)
public abstract class MixinOverviewCamera {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    // Overview 激活时强制 detached=true，让原版 LevelRenderer 按第三人称渲染本地玩家模型，
    // 无需切换 CameraType（思路来自 RawNuke 的 PR #11）。按名定位 detached——setup 只有两个
    // boolean 参数(detached/thirdPersonReverse)，按类型 ordinal 只到 1，原 ordinal=2 匹配不到。
    @ModifyVariable(method = "setup", at = @At("HEAD"), argsOnly = true, name = "detached")
    private boolean forceDetachedFlagWhenOverview(boolean detached) {
        if (OverviewClientState.isActive()) {
            return true;
        }
        return detached;
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void onSetupTail(BlockGetter level, Entity entity, boolean detached,
                             boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        if (OverviewClientState.isActive()) {
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
