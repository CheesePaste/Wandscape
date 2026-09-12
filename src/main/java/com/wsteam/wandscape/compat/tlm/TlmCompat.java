package com.wsteam.wandscape.compat.tlm;

import com.wsteam.wandscape.foundation.log.Log;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

/**
 * 车万女仆（Touhou Little Maid）兼容门面。
 *
 * <p>**纪律：本类不得引用任何 TLM 类型。** 它会被无条件加载的代码调用（模组装配期），
 * TLM 缺席时只要有一个 TLM 类型在这里被链接就是 `NoClassDefFoundError` 崩服。
 * 真正碰 TLM 的代码全部隔离在 {@link TlmCompatImpl} 及其同包类里，只有
 * {@link #isLoaded()} 为真时才会被链接——沿用 {@code compat/curios}、{@code compat/patchouli}
 * 的「门面 + Impl 隔离」模板。
 *
 * <p>本兼容层目前提供两件事：① 一个官方女仆任务「殖民地工作」，玩家在女仆界面选中即进入工作模式；
 * ② 该状态下把女仆登记为殖民地工作者（{@code ColonyWorkerApi} 的同一条链路）。
 * 女仆的**盟友身份不需要这里做任何事**——{@code EntityMaid extends TamableAnimal} 已实现
 * {@code OwnableEntity}，有主女仆天然落 {@code WandscapeNpc.classify()} 的 PET 分支、
 * 按主人所属殖民地判定（默认配置 {@code npc.pvp = true} 下即"只认本小镇的女仆"）。
 */
public final class TlmCompat {

    public static final String MOD_ID = "touhou_little_maid";

    private static final String TAG = "TlmCompat";

    private static boolean loaded;

    public static boolean isLoaded() {
        return loaded;
    }

    /** 在模组装配期调用一次（{@code Wandscape} 的 compat 初始化区）。 */
    public static void init(IEventBus modEventBus) {
        loaded = ModList.get().isLoaded(MOD_ID);
        if (!loaded) {
            Log.info(TAG, "Touhou Little Maid not detected, compat disabled.");
            return;
        }
        TlmCompatImpl.init(modEventBus);
        Log.info(TAG, "Touhou Little Maid detected! Initializing compat layer...");
    }

    private TlmCompat() {}
}
