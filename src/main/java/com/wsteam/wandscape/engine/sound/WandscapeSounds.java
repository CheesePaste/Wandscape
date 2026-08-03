package com.wsteam.wandscape.engine.sound;

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
 * 播放统一走 {@link com.wsteam.wandscape.engine.service.SoundService}。
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
    /** NPC 蓝图施工整栋建成。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> BUILDING_PLACED = register("building_placed");
    /** NPC 拆除建筑。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> BUILDING_DEMOLISHED = register("building_demolished");
    /** 游客到达。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> TOURIST_ARRIVE = register("tourist_arrive");
    /** 游客离开。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> TOURIST_DEPART = register("tourist_depart");
    /** 商店补货。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> SHOP_RESTOCK = register("shop_restock");

    // ---- P2 模拟经营 / 全局 ----
    /** 建筑因维护费不足关停。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> BUILDING_SHUTDOWN = register("building_shutdown");
    /** 建筑恢复运行。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> BUILDING_RESTART = register("building_restart");
    /** 殖民地升级。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> COLONY_LEVEL_UP = register("colony_level_up");
    /** 奇观效果应用/移除。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> WONDER_EFFECT = register("wonder_effect");
    /** 公路铺路。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> ROAD_PLACE = register("road_place");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, name)));
    }

    private WandscapeSounds() {}
}
