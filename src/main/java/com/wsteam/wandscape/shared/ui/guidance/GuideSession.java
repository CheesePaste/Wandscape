package com.wsteam.wandscape.shared.ui.guidance;

import com.wsteam.wandscape.shared.network.GuideProgressUpdatePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side runtime state for the onboarding tutorial: current step, dismissal,
 * and persistence sync. Progression is advance-only — the server-confirmed step
 * never regresses even if the building cache briefly lags. The server only
 * stores progress; this class detects step transitions and pushes them.
 */
public final class GuideSession {

    private GuideSession() {}

    /** Highest step the server has confirmed for this player. */
    private static volatile int serverStep = 0;
    private static volatile boolean dismissed = false;
    private static boolean everShown = false;
    /** Last (step, dismissed) combo pushed, to avoid redundant updates. */
    private static volatile int lastPushedKey = Integer.MIN_VALUE;

    /** Effective current step: max(derived from colony state, server-confirmed). */
    public static int currentStep() {
        return Math.max(derivedStep(GuideContext.fromBuildingCache()), serverStep);
    }

    /** Number of leading steps satisfied by the given colony state. Pure — unit-testable. */
    static int derivedStep(GuideContext ctx) {
        int derived = 0;
        for (GuideStep s : GuideRegistry.STEPS) {
            if (s.done().test(ctx)) derived++;
            else break;
        }
        return derived;
    }

    public static boolean shouldShow() {
        return !dismissed && currentStep() < GuideRegistry.STEPS.size();
    }

    /** Re-evaluate after building data changes (BuildingAreaSyncPacket arrives). */
    public static void onBuildingDataChanged() {
        if (dismissed) return;
        int step = currentStep();
        if (!everShown && step < GuideRegistry.STEPS.size()) {
            everShown = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("§e[新手引导] §f请建造【市政厅】与【仓库】以开启殖民地管理！"), true);
            }
        }
        pushIfChanged(step);
    }

    /** Dismiss the guide (× button); persisted via the server. */
    public static void dismiss() {
        dismissed = true;
        pushIfChanged(currentStep());
    }

    /** Seed progress from the server (sent on panel open / colony creation). */
    public static void applySync(int step, boolean dismissedFlag) {
        serverStep = Math.max(0, step);
        dismissed = dismissedFlag;
        lastPushedKey = syncKey(serverStep, dismissed);
    }

    private static void pushIfChanged(int step) {
        int key = syncKey(step, dismissed);
        if (key == lastPushedKey) return;
        lastPushedKey = key;
        PacketDistributor.sendToServer(new GuideProgressUpdatePacket(step, dismissed));
    }

    private static int syncKey(int step, boolean dismissedFlag) {
        return step * 2 + (dismissedFlag ? 1 : 0);
    }
}
