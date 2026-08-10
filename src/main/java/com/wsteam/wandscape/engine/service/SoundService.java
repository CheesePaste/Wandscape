package com.wsteam.wandscape.engine.service;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * 全模组统一音效播放门面（docs/sounds.md 第 3 节）。
 * 服务端广播用 {@link #playAt}，实体音用 {@link #playEntity}，客户端 UI 音用 {@link #playUI}。
 * 高频 tick 结算点必须走 {@link #playAtThrottled} 防刷屏。
 * 声音是纯瞬时事件，不持久化、不进 ECS / SavedData。
 */
public final class SoundService {

    /** 按音效 id 记录上次播放的服务器 tick，供节流使用。 */
    private static final Map<ResourceLocation, Long> LAST_PLAYED_TICK = new HashMap<>();

    private SoundService() {}

    /** 服务端在坐标处向附近玩家广播。 */
    public static void playAt(ServerLevel level, double x, double y, double z,
                              DeferredHolder<SoundEvent, SoundEvent> sound,
                              SoundSource category, float volume, float pitch) {
        if (level == null || sound == null || !sound.isBound()) return;
        level.playSound(null, x, y, z, sound.get(), category, volume, pitch);
    }

    /** 服务端在方块中心向附近玩家广播。 */
    public static void playAt(ServerLevel level, BlockPos pos,
                              DeferredHolder<SoundEvent, SoundEvent> sound,
                              SoundSource category, float volume, float pitch) {
        playAt(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                sound, category, volume, pitch);
    }

    /** 实体音效（NPC/游客等）：服务端播给附近玩家，客户端本地播。 */
    public static void playEntity(Entity entity, DeferredHolder<SoundEvent, SoundEvent> sound,
                                  float volume, float pitch) {
        if (entity == null || entity.isRemoved() || sound == null || !sound.isBound()) return;
        entity.playSound(sound.get(), volume, pitch);
    }

    /** 客户端 UI 音效（走主音量通道，无空间衰减）。只应在客户端调用。 */
    @OnlyIn(Dist.CLIENT)
    public static void playUI(DeferredHolder<SoundEvent, SoundEvent> sound, float pitch) {
        if (sound == null || !sound.isBound()) return;
        net.minecraft.client.Minecraft.getInstance().getSoundManager()
                .play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound.get(), pitch));
    }

    /** 带节流的播放：同一音效在 minIntervalTicks 内只播一次，防止高频 tick 刷屏。 */
    public static void playAtThrottled(ServerLevel level, double x, double y, double z,
                                       DeferredHolder<SoundEvent, SoundEvent> sound,
                                       SoundSource category, float volume, float pitch,
                                       int minIntervalTicks) {
        if (level == null || sound == null || !sound.isBound()) return;
        ResourceLocation id = sound.getId();
        long now = level.getGameTime();
        Long last = LAST_PLAYED_TICK.get(id);
        if (last != null && now - last < minIntervalTicks) return;
        LAST_PLAYED_TICK.put(id, now);
        level.playSound(null, x, y, z, sound.get(), category, volume, pitch);
    }

    /** 带节流的播放（原版 SoundEvent 版）：同一音效在 minIntervalTicks 内只播一次。 */
    public static void playAtThrottled(ServerLevel level, double x, double y, double z,
                                       SoundEvent sound, SoundSource category, float volume, float pitch,
                                       int minIntervalTicks) {
        if (level == null || sound == null) return;
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getKey(sound);
        if (id == null) return;
        long now = level.getGameTime();
        Long last = LAST_PLAYED_TICK.get(id);
        if (last != null && now - last < minIntervalTicks) return;
        LAST_PLAYED_TICK.put(id, now);
        level.playSound(null, x, y, z, sound, category, volume, pitch);
    }
}
