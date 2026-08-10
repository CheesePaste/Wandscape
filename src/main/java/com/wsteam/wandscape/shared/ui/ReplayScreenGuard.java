package com.wsteam.wandscape.shared.ui;

import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Prevents Wandscape UI screens from opening while a replay mod
 * (ReplayMod / ReforgedPlay) is playing back a recording.
 *
 * <p>Replays replay the recorded interactions, which re-fires the building
 * right-click packets and would otherwise pop the building UI over the replay
 * camera — blocking the screen and breaking the replay mod's own key handling.
 *
 * <p>Detection is reflective against {@code com.replaymod.replay.ReplayModReplay}
 * (the shared API of ReplayMod and its NeoForge port ReforgedPlay), so this mod
 * needs no compile/runtime dependency on either.
 */
public final class ReplayScreenGuard {

    private static final String TAG = "ReplayScreenGuard";
    private static final String REPLAY_MODULE_CLASS = "com.replaymod.replay.ReplayModReplay";

    private static boolean registered = false;

    private ReplayScreenGuard() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class, ReplayScreenGuard::onScreenOpening);
    }

    /**
     * @return {@code true} while a ReplayMod/ReforgedPlay replay is being played back.
     */
    public static boolean isReplayPlaying() {
        try {
            Class<?> clazz = Class.forName(REPLAY_MODULE_CLASS);
            Object module = clazz.getField("instance").get(null);
            if (module == null) return false;
            return clazz.getMethod("getReplayHandler").invoke(module) != null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (isReplayPlaying() && event.getScreen() instanceof MedievalScreen) {
            event.setCanceled(true);
        }
    }
}
