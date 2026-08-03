package com.wsteam.wandscape.engine.sound;

import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 殖民地昼夜环境音（客户端，包驱动）。
 *
 * <p>由服务端 {@code ColonyAmbientTracker} 判断玩家是否在城镇范围内并发送
 * {@link com.wsteam.wandscape.shared.network.ColonyAmbientPacket}，本类据包启停/切相位：
 * 白天（游客在城）播放人群环境音，夜晚低音量播放森林环境音。
 *
 * <p>两个循环都是 2D 全局声（relative + AMBIENT 通道），由 {@link AmbientLoop} 淡入。
 * {@link #tick()} 仅做安全兜底：世界卸载或切换维度时停止循环。
 */
@OnlyIn(Dist.CLIENT)
public final class ColonyAmbientSystem {

    private static final String TAG = "ColonyAmbient";

    /** 白天人群环境音音量。 */
    private static final float DAY_VOLUME = 0.1f;
    /** 夜晚森林环境音音量（低）。 */
    private static final float NIGHT_VOLUME = 0.1f;

    private static boolean playing;
    private static boolean dayPhase;
    private static AmbientLoop activeLoop;
    private static ClientLevel lastLevel;

    private ColonyAmbientSystem() {}

    /** 客户端每 tick 调用（挂 ClientTickEvent.Post），仅做安全兜底。 */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            stopLoop();
            lastLevel = null;
            return;
        }
        if (level != lastLevel) {
            // 维度切换/重新进入世界 → 停止旧循环，等服务器重新下发状态
            stopLoop();
            lastLevel = level;
        }
    }

    /** 据服务端包更新环境音状态。 */
    public static void setState(boolean play, boolean day) {
        if (!play) {
            stopLoop();
            return;
        }
        if (playing && dayPhase == day && activeLoop != null) return;

        stopLoop();
        playing = true;
        dayPhase = day;
        SoundEvent ev = day
                ? WandscapeSounds.COLONY_AMBIENT_DAY.get()
                : WandscapeSounds.COLONY_AMBIENT_NIGHT.get();
        if (ev == null) {
            Log.warn(TAG, "ambient sound event not bound — skipping");
            return;
        }
        float target = day ? DAY_VOLUME : NIGHT_VOLUME;
        Log.info(TAG, "start {} (target={}, masterSlider={}, ambientSlider={})", day ? "DAY" : "NIGHT", target,
                Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER),
                Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.AMBIENT));
        AmbientLoop loop = new AmbientLoop(ev, target);
        activeLoop = loop;
        Minecraft.getInstance().getSoundManager().play(loop);
    }

    private static void stopLoop() {
        if (activeLoop != null) {
            Minecraft.getInstance().getSoundManager().stop(activeLoop);
            activeLoop = null;
        }
        playing = false;
    }

    /** 可循环的 2D 环境音实例，走 MASTER 通道（环境通道滑块可能为 0 导致听不到），直接以目标音量播放。 */
    private static final class AmbientLoop extends AbstractTickableSoundInstance {
        AmbientLoop(SoundEvent sound, float volume) {
            super(sound, SoundSource.MASTER, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.relative = true;
            this.volume = volume;
        }

        @Override
        public void tick() {
            // 音量已直接设为目标值；无需额外处理
        }
    }
}
