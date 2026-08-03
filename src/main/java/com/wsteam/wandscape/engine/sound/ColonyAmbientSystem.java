package com.wsteam.wandscape.engine.sound;

import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 殖民地昼夜环境音（客户端，不依赖事件，纯时间驱动）。
 *
 * <p>白天（dayTime ∈ [1000, 18000)，游客在城期间）循环播放人群环境音；
 * 夜晚（其余时间，游客离城）低音量循环播放森林环境音。
 * 分界点对应 Config 的 tourist.spawnWindowStart=1000 / tourist.departureWindowStart=18000。
 *
 * <p>两个循环都是 2D 全局声（relative + AMBIENT 通道），由 {@link AmbientLoop}
 * 驱动淡入，相位切换时停旧启新。
 */
@OnlyIn(Dist.CLIENT)
public final class ColonyAmbientSystem {

    private static final String TAG = "ColonyAmbient";

    /** 白天开始（游客开始出现）。 */
    private static final int DAY_START_TICK = 1000;
    /** 夜晚开始（游客离场窗口）。 */
    private static final int NIGHT_START_TICK = 18000;
    /** 相位评估间隔（tick）。 */
    private static final int CHECK_INTERVAL = 20;
    /** 白天人群环境音音量。 */
    private static final float DAY_VOLUME = 0.5f;
    /** 夜晚森林环境音音量（低）。 */
    private static final float NIGHT_VOLUME = 0.22f;

    private static int checkCounter;
    private static boolean dayPhase;
    private static AmbientLoop activeLoop;

    private ColonyAmbientSystem() {}

    /** 客户端每 tick 调用（挂 ClientTickEvent.Post）。 */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            stopLoop();
            return;
        }
        if (++checkCounter < CHECK_INTERVAL) return;
        checkCounter = 0;

        long dayTime = mc.level.getDayTime() % 24000L;
        boolean day = dayTime >= DAY_START_TICK && dayTime < NIGHT_START_TICK;
        if (day == dayPhase && activeLoop != null) return;

        stopLoop();
        dayPhase = day;
        if (day) {
            startLoop(WandscapeSounds.COLONY_AMBIENT_DAY.get(), DAY_VOLUME);
        } else {
            startLoop(WandscapeSounds.COLONY_AMBIENT_NIGHT.get(), NIGHT_VOLUME);
        }
        Log.debug(TAG, "phase switch -> {}", day ? "DAY" : "NIGHT");
    }

    private static void startLoop(SoundEvent sound, float volume) {
        if (sound == null) {
            Log.warn(TAG, "ambient sound event not bound — skipping");
            return;
        }
        AmbientLoop loop = new AmbientLoop(sound, volume);
        activeLoop = loop;
        Minecraft.getInstance().getSoundManager().play(loop);
    }

    private static void stopLoop() {
        if (activeLoop != null) {
            Minecraft.getInstance().getSoundManager().stop(activeLoop);
            activeLoop = null;
        }
    }

    /** 可循环的 2D 环境音实例，启动时淡入目标音量。 */
    private static final class AmbientLoop extends AbstractTickableSoundInstance {
        private final float targetVolume;

        AmbientLoop(SoundEvent sound, float volume) {
            super(sound, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.relative = true;
            this.targetVolume = volume;
            this.volume = 0.0F;
        }

        @Override
        public void tick() {
            if (this.volume < this.targetVolume) {
                this.volume = Math.min(this.targetVolume, this.volume + 0.02F);
            }
        }
    }
}
