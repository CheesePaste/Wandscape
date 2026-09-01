package com.wsteam.wandscape.foundation.ui.tutorial;
import com.wsteam.wandscape.content.tutorial.network.TutorialProgressSyncPacket;

import com.wsteam.wandscape.content.tutorial.network.TutorialProgressUpdatePacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side rendering state for the onboarding tutorial. The step index is
 * server-authoritative (pushed via {@code TutorialProgressSyncPacket}); this class
 * only stores it, decides visibility, and forwards dismissal to the server.
 */
public final class TutorialSession {

    private TutorialSession() {}

    private static volatile int serverStep = 0;
    private static volatile boolean dismissed = false;
    private static boolean toastShown = false;

    public static int currentStep() {
        return serverStep;
    }

    public static boolean shouldShow() {
        return !dismissed && serverStep < TutorialRegistry.STEPS.size();
    }

    /** Seed progress from the server (panel open / placement / colony creation). */
    public static void applySync(int step, boolean dismissedFlag) {
        serverStep = Math.max(0, step);
        dismissed = dismissedFlag;
        if (!toastShown && !dismissed && serverStep < TutorialRegistry.STEPS.size()) {
            toastShown = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        I18n.name("message.wandscape.tutorial.toast", "§e[新手引导] §f跟随引导，逐步建设你的魔法小镇！"), true);
            }
        }
    }

    private static volatile boolean collapsed = false;

    public static boolean isCollapsed() {
        return collapsed;
    }

    public static void toggleCollapsed() {
        collapsed = !collapsed;
    }

    /** Dismiss the guide (× button); persisted via the server. */
    public static void dismiss() {
        dismissed = true;
        PacketDistributor.sendToServer(new TutorialProgressUpdatePacket(true));
    }
}
