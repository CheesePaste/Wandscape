package com.wsteam.wandscape.foundation.registry;
import com.wsteam.wandscape.foundation.sound.SoundService;

import com.wsteam.wandscape.Wandscape;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 模组所有自定义 SoundEvent 的唯一注册点。
 * 逻辑 id → 音频文件 的映射在 assets/wandscape/sounds.json，
 * 换音效只改 json/换 .ogg，不改代码（见 docs/sounds.md）。
 * 播放统一走 {@link com.wsteam.wandscape.foundation.sound.SoundService}。
 */
public final class WandscapeSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, Wandscape.MODID);

    // ---- P0 玩家直接操作 ----
    /** 法杖/命令施法起手。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> MAGIC_CAST = register("magic_cast");
    /** 法阵动画结束、光束发射。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> MAGIC_BEAM = register("magic_beam");
    /** 投影模式确认放置建筑蓝图（任务已提交）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> BUILDING_PLACE = register("building_place");
    public static final DeferredHolder<SoundEvent, SoundEvent> PROJECTION_ENTER = register("projection_enter");
    public static final DeferredHolder<SoundEvent, SoundEvent> PROJECTION_EXIT = register("projection_exit");
    /** 俯瞰模式进入。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> OVERVIEW_ENTER = register("overview_enter");
    /** 仓库元素存取。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> WAREHOUSE = register("warehouse");

    // ---- P1 NPC / 自动行为 ----
    /** NPC 施法放置每块方块。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> NPC_CAST = register("npc_cast");
    /** 玩家手动创建任务（铺路/填充/投影建筑等）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> TASK_PUBLISH = register("task_publish");
    /** 守卫开火。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> GUARD_FIRE = register("guard_fire");

    // ---- P2 模拟经营 / 全局 ----
    /** 小镇升级。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> COLONY_LEVEL_UP = register("colony_level_up");
    /** 奇观效果应用/移除。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> WONDER_EFFECT = register("wonder_effect");
    /** 公路铺路。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> ROAD_PLACE = register("road_place");

    // ---- 小镇环境音（客户端循环，不依赖事件） ----
    /** 白天（游客在城）人群环境音循环。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> COLONY_AMBIENT_DAY = register("colony_ambient_day");
    /** 夜晚（游客离城）森林环境音循环，低音量。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> COLONY_AMBIENT_NIGHT = register("colony_ambient_night");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, name)));
    }

    private WandscapeSounds() {}
}
