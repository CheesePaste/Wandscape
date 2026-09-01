package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.magic.data.MagicDef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 魔法域公开契约（P3）：读写 NPC 已装备魔法载荷与施法策略，并逐步开放程序化施法、
 * 魔力/冷却控制、法术定义查询。
 *
 * <p>载荷 = 已装备魔法（按分类 4 桶、每桶 ≤3，桶内 = 类内优先级），幂等全量重算由服务端
 * {@code EquippedMagicComponent} 校验（未知/ALTAR/SPECIAL 丢、超限去重）。生效的魔法级顺序由
 * {@code CastBrain.resolvePriority} 按预设分类排序解析。数据契约见 {@code docs/spell-casting.md} 5.4。
 *
 * <p>⚠️ 重设计阶段：本接口由 {@code SpellcastingApi} 改名而来并扩入施法/魔力/法术定义能力。
 * 原有载荷/策略/平衡值方法已实现；新增的 {@link #castNpcSpell} {@link #castForPlayer}
 * {@link #fillMana} {@link #clearCooldown} {@link #getMagicDef} {@link #getAllSpellIds}
 * 仍为 {@literal default} 桩（见 {@link Unimplemented}），实现落地后补真。
 */
public interface MagicApi {

    // ── 已实现：载荷 + 策略 ──

    /** NPC 已装备魔法（magicId 顺序 = 分类固定序 × 桶内槽位序）。 */
    List<String> getKnownSpells(UUID npcId);

    /** 当前策略预设名（{@code CastStrategyComponent.Preset} 的大写名）。 */
    String getStrategyPreset(UUID npcId);

    /**
     * 生效的施法优先级（magicId 顺序）——已按玩家策略解析，供 UI 展示。
     * NPC 不存在或组件缺失时返回空列表。
     */
    List<String> getPriority(UUID npcId);

    /**
     * 全量重设已装备魔法载荷 + 策略预设。{@code equipped} 为扁平 magicId 列表（分类固定序 ×
     * 类内槽位序）；服务端按每个魔法真实分类装桶校验（未知丢、ALTAR/SPECIAL 丢、每类 ≤3、去重），
     * 客户端立场不获信任。预设决定跨类施法先后。
     */
    void setEquippedAndStrategy(UUID npcId, String preset, List<String> equipped);

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /**
     * 命令一名殖民地 NPC 对世界坐标施放指定魔法（走服务端完整施法链路：法阵/光束/治疗）。
     *
     * @param npcId    目标 NPC（殖民地法师）
     * @param magicId  法术 id（{@code magic_spells/*.json}）
     * @param target   施法目标世界坐标
     * @return 是否成功发起；NPC 不存在/魔法未知/魔力或冷却不足返回 false
     */
    @Unimplemented("重设计阶段——待接入 MagicCaster.castNpcAt")
    default boolean castNpcSpell(UUID npcId, String magicId, BlockPos target) {
        throw new UnsupportedOperationException("MagicApi.castNpcSpell not yet implemented");
    }

    /**
     * 给一名玩家临时施放指定魔法效果（不消耗魔力、不绑定 NPC）。
     *
     * @return 魔法 id 合法且玩家存在返回 true
     */
    @Unimplemented("重设计阶段——待接入 MagicSpellExecutors.castForPlayer")
    default boolean castForPlayer(ServerPlayer player, String magicId) {
        throw new UnsupportedOperationException("MagicApi.castForPlayer not yet implemented");
    }

    /** 直接设置一名 NPC 的当前魔力（0..maxMana）。 */
    @Unimplemented("重设计阶段——待接入 npc.magic.setMana")
    default void fillMana(UUID npcId, float amount) {
        throw new UnsupportedOperationException("MagicApi.fillMana not yet implemented");
    }

    /** 清除一名 NPC 的施法冷却（若在冷却中）。 */
    @Unimplemented("重设计阶段——待接入冷却组件重置")
    default void clearCooldown(UUID npcId) {
        throw new UnsupportedOperationException("MagicApi.clearCooldown not yet implemented");
    }

    /** 按 id 取法术定义（数据 record）；未知 id 返回 null。 */
    @Unimplemented("重设计阶段——待接入 SpellbookLoader")
    default MagicDef getMagicDef(String magicId) {
        throw new UnsupportedOperationException("MagicApi.getMagicDef not yet implemented");
    }

    /** 全部已注册法术 id。 */
    @Unimplemented("重设计阶段——待接入 SpellbookLoader")
    default List<String> getAllSpellIds() {
        throw new UnsupportedOperationException("MagicApi.getAllSpellIds not yet implemented");
    }

    // ── 可调平衡值（已实现，委托 BalanceValues；运行时生效，不追溯已生成实体）──

    int getCastSingleTargetMaxEnemies();
    void setCastSingleTargetMaxEnemies(int v);
    int getCastAoeMinEnemies();
    void setCastAoeMinEnemies(int v);
}
