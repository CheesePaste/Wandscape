package com.wsteam.wandscape.npc.entity;

import com.wsteam.wandscape.npc.network.NpcDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 敌对测试生物「邪恶法师」：与小镇法师外观/属性/施法管线完全一致
 * （默认属性、同款皮肤纹理、同一套 MagicState/CD/NBT），但：
 * <ul>
 *   <li>敌对生物（{@link Enemy}）：索敌最近**生存玩家**（创造/旁观玩家免疫）。</li>
 *   <li>不加入 ECS / 不入小镇（{@link #isColonyNpc()} = false）——不被任务调度、
 *       死亡记录/复活、村民索敌增强当普通 NPC 处理。</li>
 *   <li>创造模式右键打开 {@code NpcScreen} 编辑（施法表/策略/法杖颜色），生存玩家不可配置。</li>
 * </ul>
 * 实战测试法术系统强度用：击杀不留死亡记录、不掉落，重生靠刷怪蛋。
 */
public class EvilMage extends WandscapeNpc implements Enemy {

    public EvilMage(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    // ============================================================
    // 与小镇 NPC 的边界
    // ============================================================

    @Override
    public boolean isColonyNpc() {
        return false;
    }

    /** 不加入 ECS → 施法姿态由 EvilMageCastGoal 驱动，不再由 ECS 任务执行器同步。 */
    @Override
    protected void tickCastingState() {
        // no-op：isCasting/setDebugTarget 由施法 goal 管理；魔力/血量回复仍在 tick() 中执行
    }

    /**
     * 光束可伤害目标：非友军（沿用放宽后的 {@link WandscapeNpc#canBeamHurt}，含村民/动物/
     * 敌对生物/异殖民地 NPC）或 生存玩家（非创造/旁观）——EvilMage 是敌对测试生物，
     * 刻意能伤生存玩家用于实战测试；自己的铁魔法召唤随从仍在友军名单内不误伤。
     */
    @Override
    public boolean canBeamHurt(LivingEntity target) {
        if (super.canBeamHurt(target)) return true;
        return target instanceof Player player
                && !player.isCreative() && !player.isSpectator();
    }

    // ============================================================
    // AI：索敌生存玩家 + 施法战斗
    // ============================================================

    @Override
    protected void registerGoals() {
        // Priority 0: don't drown
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Priority 1: 索敌最近生存玩家（mustSee=false → 隔墙也保持目标，追逐）
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, 10,
                false, false,
                e -> e instanceof Player p && p.isAlive() && !p.isCreative() && !p.isSpectator()));
        // Priority 2: 施法战斗（光束，LOS 挡时寻路靠近）
        this.goalSelector.addGoal(2, new EvilMageCastGoal(this));
        // Priority 5: 空闲游荡（非 ECS NPC，无 suppressWandering 抑制）
        this.goalSelector.addGoal(5, new RandomStrollGoal(this, 0.6));
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // 只有创造玩家可右键编辑（施法表/策略/法杖）——生存玩家是猎物，不能改配置
        if (player.isCreative()) {
            if (player instanceof ServerPlayer sp) {
                PacketDistributor.sendToPlayer(sp, NpcDataPacket.from(this));
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    // ============================================================
    // 外观与名牌
    // ============================================================

    @Override
    public void onAddedToLevel() {
        if (!level().isClientSide && !hasCustomName()) {
            setCustomName(Component.translatable("entity.wandscape.evil_mage"));
            setCustomNameVisible(true);
        }
        super.onAddedToLevel();
    }

    /** 顶栏名牌显示本地化名称（客户端渲染器调用，按语言解析）。 */
    @Override
    public String getNpcName() {
        return Component.translatable("entity.wandscape.evil_mage").getString();
    }

    /** 敌对法师不显示闲聊气泡（避免冒出游客式的友好台词）。 */
    @Override
    public boolean showsSpeechBubbles() {
        return false;
    }
}
