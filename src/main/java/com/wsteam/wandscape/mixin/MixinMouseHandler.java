package com.wsteam.wandscape.mixin;

import com.wsteam.wandscape.content.colony.overview.client.OverviewClientState;
import com.wsteam.wandscape.content.colony.overview.client.OverviewFlightController;
import com.wsteam.wandscape.content.road.client.SplineEditorClientState;
import com.wsteam.wandscape.content.road.client.SplineEditorController;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts player turning to redirect mouse movement deltas to Wandscape's custom camera systems
 * (Overview mode and Spline Road Editor) and suppresses vanilla player rotation.
 * Compatible with raw input and multithreaded event dispatchers such as Ixeris.
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandler {

    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    @Shadow
    private boolean isLeftPressed;

    @Shadow
    private boolean isRightPressed;

    @Shadow
    private boolean isMiddlePressed;

    @Inject(method = "onPress", at = @At("HEAD"))
    private void wandscape$onPress(long windowPointer, int button, int action, int modifiers, CallbackInfo ci) {
        if (button == 0) this.isLeftPressed = (action != 0);
        if (button == 1) this.isRightPressed = (action != 0);
        if (button == 2) this.isMiddlePressed = (action != 0);
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void wandscape$onTurnPlayer(double movementTime, CallbackInfo ci) {
        // 1. Overview flight camera (V-panel overview, including Build projection while in overview)
        if (OverviewClientState.isActive() && !SplineEditorClientState.isEditing()) {
            OverviewFlightController.onMouseTurn(this.accumulatedDX, this.accumulatedDY);
            ci.cancel();
            return;
        }

        // 2. Spline road editor 3D freecam / top-down camera rotation while holding RMB
        if (SplineEditorClientState.isEditing() && SplineEditorController.isCameraActive()) {
            SplineEditorController.onMouseTurn(this.accumulatedDX, this.accumulatedDY);
            ci.cancel();
            return;
        }
    }
}
