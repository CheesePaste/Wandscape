package com.wsteam.wandscape.mixin;

import com.wsteam.wandscape.content.colony.overview.client.OverviewClientState;
import com.wsteam.wandscape.content.colony.overview.client.OverviewFlightController;
import com.wsteam.wandscape.content.road.client.SplineEditorClientState;
import com.wsteam.wandscape.content.road.client.SplineEditorController;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelController;
import com.wsteam.wandscape.foundation.ui.util.CursorGrab;
import net.minecraft.client.Minecraft;
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

    private static final String TAG = "MixinMouseHandler";

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

    // ══════════════════════════════════════════════════════════════════════════
    // 光标归属兜底：取消「外来」的抓取 / 释放
    // ══════════════════════════════════════════════════════════════════════════
    // 原版 MouseHandler.grabMouse() 和 releaseMouse() **都会** 把 OS 光标 glfwSetCursorPos 到窗口正中
    // 并翻转 GLFW_CURSOR（DISABLED / NORMAL）。任何一方每 tick 抢一次，和 V 面板的「持久自由光标」
    // 就会对撞成「光标锁在屏幕正中闪烁」——huhutalk 的 PhoneOverlay.updateCursorMode() 就是
    // `else if (mc.screen == null) mc.mouseHandler.grabMouse();`，每客户端 tick 一次。
    //
    // 注入点为什么选外层方法的 HEAD，而不是两者共同调用的 InputConstants.grabOrReleaseMouse：
    // 调用者是**先**改自己的 mouseGrabbed / xpos,ypos，**再**调那个 helper，helper 之后还有
    // setScreen(null) / missTime=10000 / ignoreFirstMove=true 三个副作用。在 helper 上取消，
    // 会留下「标志位与真实游标模式不一致」+「逻辑坐标已被丢到正中（面板命中判定读的就是它）」，
    // 而后面三个副作用照样跑（missTime 每 tick 重新武装 = 左键攻击被永久拒绝）。
    // 在 HEAD 取消整个方法才是原子的 no-op。

    private static int wandscape$grabVetoes;
    private static int wandscape$releaseVetoes;
    private static long wandscape$lastVetoLogMs;

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void wandscape$vetoForeignGrab(CallbackInfo ci) {
        if (CursorGrab.isSelfCall()) return;                     // 本仓自己发的抓取 → 放行
        if (!WandscapePanelController.vetoForeignGrab()) return; // 只在本仓合法要求自由光标时介入
        wandscape$grabVetoes++;
        wandscape$logVeto("grabMouse()");
        ci.cancel();
    }

    @Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
    private void wandscape$vetoForeignRelease(CallbackInfo ci) {
        if (CursorGrab.isSelfCall()) return;                     // 本仓自己发的释放 → 放行
        if (!WandscapePanelController.vetoForeignRelease()) return;
        wandscape$releaseVetoes++;
        wandscape$logVeto("releaseMouse()");
        ci.cancel();
    }

    /** 限流 5 秒一条：既要留下「谁在抢」的证据，也不能在风暴里刷屏。 */
    private static void wandscape$logVeto(String what) {
        long now = System.currentTimeMillis();
        if (wandscape$lastVetoLogMs != 0L && now - wandscape$lastVetoLogMs < 5000L) return;
        wandscape$lastVetoLogMs = now;
        Minecraft mc = Minecraft.getInstance();
        Log.warn(TAG, "[Panel][Cursor] suppressed foreign {} — 本仓持有光标（grab否决={} release否决={} screen={} grabbed={}）",
                what, wandscape$grabVetoes, wandscape$releaseVetoes,
                mc == null || mc.screen == null ? "null" : mc.screen.getClass().getSimpleName(),
                mc != null && mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed());
    }
}
