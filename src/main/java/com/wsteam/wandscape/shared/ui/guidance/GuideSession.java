package com.wsteam.wandscape.shared.ui.guidance;

import com.wsteam.wandscape.shared.network.GuideProgressUpdatePacket;
import com.wsteam.wandscape.shared.ui.I18n;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side rendering state for the onboarding tutorial. The step index is
 * server-authoritative (pushed via {@code GuideProgressSyncPacket}); this class
 * only stores it, decides visibility, and forwards dismissal to the server.
 */
public final class GuideSession {

    private GuideSession() {}

    private static volatile int serverStep = 0;
    private static volatile boolean dismissed = false;
    private static boolean toastShown = false;

    public static int currentStep() {
        return serverStep;
    }

    public static boolean shouldShow() {
        return !dismissed && serverStep < GuideRegistry.STEPS.size();
    }

    /** Seed progress from the server (panel open / placement / colony creation). */
    public static void applySync(int step, boolean dismissedFlag) {
        serverStep = Math.max(0, step);
        dismissed = dismissedFlag;
        if (!toastShown && !dismissed && serverStep < GuideRegistry.STEPS.size()) {
            toastShown = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        I18n.name("message.wandscape.guide.toast", "§e[新手引导] §f跟随引导，逐步建设你的魔法小镇！"), true);
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
        PacketDistributor.sendToServer(new GuideProgressUpdatePacket(true));
    }
}
