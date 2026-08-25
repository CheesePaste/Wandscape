package com.wsteam.wandscape.npc.entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.component.CastStrategyComponent;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.EquipmentComponent;
import com.wsteam.wandscape.core.component.EquippedMagicComponent;
import com.wsteam.wandscape.core.component.MagicState;
import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.AttributeModifier;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.EquipmentSlot;
import com.wsteam.wandscape.core.types.ModifierOperation;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.npc.NpcMenu;
import com.wsteam.wandscape.npc.network.NpcDataPacket;
import com.wsteam.wandscape.task.runtime.ExecutorState;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.nav.WandscapeNavigation;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.internal.MagicCaster;
import com.wsteam.wandscape.magic.internal.MagicSpellExecutors;
import com.wsteam.wandscape.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.runtime.NpcTaskPackage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wsteam.wandscape.shared.entity.PlayerLike;
import com.wsteam.wandscape.shared.log.Log;

/**
 * A colony NPC — the MC-layer shell for an ECS-driven task executor.
 *
 * <p>Architecture: the NPC entity provides appearance, pathfinding, and NBT
 * persistence. All logic (mana, scheduling, task execution) is driven by the
 * core engine via ECS components. An {@link EntityComponentBridge} maintains
 * the bidirectional mapping between this entity and its ECS counterpart.
 *
 * <p>Stage 2 (V1 minimal): basic idle AI, no task-driven movement.
 * Subsequent stages add stuck detection, death/grave, house binding, etc.
 */
public class WandscapeNpc extends PathfinderMob implements PlayerLike {

    private static final String TAG = "WandscapeNpc";

    // ============================================================
    // Engine bridge (public for same-module cross-package access)
    // ============================================================

    /** ECS World entity ID — assigned by EntityComponentBridge on join. */
    public long ecsEntityId = -1;

    /** Colony membership. Stage 2: placeholder UUID. Stage 4: real colony. */
    public UUID colonyId = EntityComponentBridge.PLACEHOLDER_COLONY;

    // ============================================================
    // Attributes (ECS EquipmentComponent is authoritative at runtime:
    // base = these fields + equipment modifiers; these fields are NBT transit)
    // ============================================================

    public float maxHp = NpcAttributes.defaults().maxHp();
    public float moveSpeed = NpcAttributes.defaults().moveSpeed();
    public float spellPower = NpcAttributes.defaults().spellPower();
    public float workSpeed = NpcAttributes.defaults().workSpeed();
    public float spellSpeed = NpcAttributes.defaults().spellSpeed();
    public float armorValue = NpcAttributes.defaults().armorValue();
    public float maxMana = NpcAttributes.defaults().maxMana();

    // ============================================================
    // 魔力值 + 每魔法独立 CD + 施法互斥锁（纯逻辑在 core/component/MagicState）
    // 魔力上限 = 第 7 属性 MAX_MANA（ECS EquipmentComponent 权威，getEffectiveAttribute 读取）
    // ============================================================

    public final MagicState magic = new MagicState();

    /** 已装备魔法容器（按分类 4 桶、每桶 ≤3，桶内=类内优先级）。决策层已知表来源。 */
    public final EquippedMagicComponent equippedMagic = new EquippedMagicComponent();

    /** 载荷是否从存档读出（区分「从未拥有载荷→种默认」与「有意清空载荷→保持空」）。 */
    private boolean spellbookLoaded;

    /** 施法策略（玩家可控：预设 + 自定义优先级）。GuardCombat 经 CastBrain.resolvePriority 消费。 */
    public final CastStrategyComponent castStrategy = new CastStrategyComponent();

    /** 当前魔力。 */
    public float getCurrentMana() {
        return magic.getMana();
    }

    /** 魔力上限（第 7 属性有效值）。 */
    public float getMaxMana() {
        return getEffectiveAttribute(AttributeType.MAX_MANA);
    }

    /**
     * 原子施放门控：互斥锁 + 该魔法独立 CD + 固定魔力消耗，全满足才成功。
     * 成功后占用 {@code lockDurationTicks} 的施法互斥锁；CD 在锁占用期间冻结、锁释放后
     * 才开始倒计时（施法时间不计入 CD），CD 基础值按 SPELL_SPEED 缩短（向上取整）。
     */
    public boolean tryCastSpell(String magicId, int baseCooldown, int manaCost, int lockDurationTicks) {
        return magic.tryCast(magicId, baseCooldown, manaCost, lockDurationTicks,
                getEffectiveAttribute(AttributeType.SPELL_SPEED));
    }

    /**
     * 祭坛施法门控：扣蓝 + 占互斥锁，不设置本 NPC 的每魔法 CD
     * （祭坛 CD 按建筑独立存放，见 {@code MagicState#tryAltarCast}）。
     */
    public boolean tryAltarCast(int manaCost, int lockDurationTicks) {
        return magic.tryAltarCast(manaCost, lockDurationTicks);
    }

    /**
     * 该生物当前是否应视为可**索敌**的敌对目标（NPC 目标选择唯一入口，仅用于锁定目标）：
     * 普通敌对生物（{@link Enemy} 且非中立）恒是；中立生物（{@link NeutralMob}——末影人/僵尸猪人
     * 等既属 Enemy 又属 NeutralMob）仅在**发怒且怒火指向玩家或小镇成员**（NPC/村民）时才算——
     * 平时和平状态绝不主动索敌；对其它怪物发怒（如被骷髅打伤）也不算小镇威胁。
     * 注意：这只是「索敌」判定。光束/法术伤害仍按 {@link Enemy} 结算（{@link #canBeamHurt}），
     * 战斗中可能误伤到正好在束内/溅射范围内的和平中立生物——这是有意的。
     */
    public static boolean isHostileTarget(LivingEntity target, ServerLevel level) {
        if (!(target instanceof Enemy)) return false;
        if (!(target instanceof NeutralMob neutral) || !neutral.isAngry()) return true;
        if (neutral.isAngryAtAllPlayers(level)) return true;
        UUID angerAt = neutral.getPersistentAngerTarget();
        if (angerAt == null) return false;
        Entity e = level.getEntity(angerAt);
        return e instanceof Player || e instanceof WandscapeNpc || e instanceof Villager;
    }

    /**
     * 该法师的魔法光束能伤害的目标判定钩子。默认只伤敌对生物（{@link Enemy}）——
     * 与 {@link #isHostileTarget}（索敌判定）分开：战斗中对束内 Enemy 一律结算伤害，
     * 可能误伤正好在束内/溅射范围内的和平中立生物；但 NPC 不会主动索敌锁定它们。
     * 受击反击例外：当前仇恨目标（非 Enemy 的攻击者，如北极熊/铁傀儡/狼）也放行，
     * 使 NPC 被中立生物攻击时能还手——但仍排除玩家与同殖民地 NPC
     * （见 {@link #isRetaliationTarget}）。敌对法师等子类覆盖为「Enemy 或 生存玩家」，
     * 用于实战测试。光束伤害（{@code MagicBeamEntity}）、SPELL_POWER 倍率
     * （{@code NpcSpellPowerHandler}）与战斗快照敌数（{@code GuardCombat}）
     * 三处统一走此钩子，保证「NPC 伤不了玩家、邪恶法师能伤生存玩家」的边界唯一且一致。
     */
    public boolean canBeamHurt(LivingEntity target) {
        if (target instanceof Enemy) return true;
        return isRetaliationTarget(target) && isCurrentHatedAttacker(target);
    }

    /**
     * 受击反击目标判定：NPC 被该攻击者打伤时是否应当还手。
     * 与 {@link #isHostileTarget}（主动索敌，仅 Enemy）区分——反击不要求 Enemy：
     * 北极熊/铁傀儡/狼等中立生物主动攻击 NPC 时同样记仇还手。排除玩家与同殖民地 NPC（友伤）。
     */
    public boolean isRetaliationTarget(LivingEntity attacker) {
        if (attacker instanceof Player) return false;
        if (attacker instanceof WandscapeNpc other) return sameColonyAs(other);
        return true;
    }

    /** 与另一 NPC 是否同殖民地（colonyId 一致；null 按占位殖民地处理）。 */
    private boolean sameColonyAs(WandscapeNpc other) {
        UUID a = colonyId != null ? colonyId : EntityComponentBridge.PLACEHOLDER_COLONY;
        UUID b = other.colonyId != null ? other.colonyId : EntityComponentBridge.PLACEHOLDER_COLONY;
        return a.equals(b);
    }

    /** 目标是否为当前有效仇恨目标（未过期且 UUID 一致）——反击伤害放行的依据。 */
    private boolean isCurrentHatedAttacker(LivingEntity target) {
        if (hatedAttackerUuid == null) return false;
        if (level().getGameTime() > hateExpiryTick) return false;
        return target.getUUID().equals(hatedAttackerUuid);
    }

    /** 头顶是否显示闲聊气泡（客户端渲染器用）。敌对法师等子类覆盖为 false。 */
    public boolean showsSpeechBubbles() {
        return true;
    }

    // ============================================================
    // 脱战生命恢复：受击后封伤 grace tick，之后每 interval tick 回 1 HP。
    // 剩余值 NBT 持久（tick 数可跨存档）。
    // ============================================================

    private int regenCooldown = 0;
    private int regenAccum = 0;

    /** 受击时调用（SelfDefenseHandler）：重置脱战封伤计时。 */
    public void markRecentlyDamaged() {
        regenCooldown = Config.NPC_REGEN_GRACE_TICKS.get();
        regenAccum = 0;
    }

    /** 每 server tick：脱战封伤计时递减，封伤过后按 interval 累计回血。 */
    private void tickHealthRegen() {
        if (regenCooldown > 0) {
            regenCooldown--;
            return;
        }
        if (getHealth() < getMaxHealth()) {
            regenAccum++;
            if (regenAccum >= Config.NPC_REGEN_INTERVAL_TICKS.get()) {
                regenAccum = 0;
                heal(1f);
            }
        } else {
            regenAccum = 0;
        }
    }

    /**
     * 非战斗自奶：脱离战斗且血量未满、会治疗 → 用治疗魔法把自己补满。
     * 与战斗内 L0 紧急奶（{@code GuardCombat.l0EmergencyHeal}，血比 &lt; 0.5 才强制奶）互补——
     * 战斗打完 NPC 常停在 50%~满血之间，L0 不触发、L1 治疗只认友方最低血，于是永远不补满，
     * 只靠慢速脱战回血。这里补上：不在战斗即视为非战斗，建造/采集等任务不打断（治疗以自身为
     * 圆心，与任务互不干扰）。施放门控（CD/蓝/互斥锁）由 {@code MagicState#tryCast} 原子复验，
     * 每 tick 尝试安全，实际频率受 heal 魔法 CD（300t）限制。敌对测试法师（EvilMage）除外，
     * 其施法由自身 goal 驱动，不给它加自奶。
     */
    private void tickIdleSelfHeal() {
        if (!isColonyNpc()) return;
        if (getHealth() >= getMaxHealth() - 0.5f) return; // 已满（留 0.5 容差避免浮点/贴满重复奶）
        if (!equippedMagic.knows("heal")) return;
        if (!magic.canCast("heal")) return; // 冷却中 / 施法互斥锁占用
        if (inActiveCombat()) return;       // 战斗中交给战斗循环 L0/L1
        if (!(level() instanceof ServerLevel level)) return;
        MagicDef heal = SpellbookLoader.getSpec("heal");
        if (heal == null) return;
        if (magic.getMana() < heal.manaCost()) return;
        MagicSpellExecutors.dispatch(level, this, this, heal, MagicCaster.DEFAULT_CIRCLE, MagicCaster.DEFAULT_COLOR);
    }

    /** 是否正处战斗：当前任务包为 self_defense（自防御）或全局任务为 guard: 蓝图（建筑守卫）。
     *  战斗必然以任务形式占用 ECS 队列，故按此判定——空闲/建造/采集等非战斗任务不拦截自奶。 */
    private boolean inActiveCombat() {
        if (ecsEntityId < 0) return false;
        World world = WandscapeEngine.getWorld();
        if (world == null) return false;
        TaskExecutor exec = world.get(ecsEntityId, TaskExecutor.class);
        if (exec == null) return false;
        NpcTaskPackage current = exec.npcQueue.currentPackage();
        if (current != null && "self_defense".equals(current.source())) return true;
        if (exec.globalTaskId != null && world.taskPool != null) {
            GlobalTask t = world.taskPool.get(exec.globalTaskId);
            if (t != null && t.blueprintId != null && t.blueprintId.startsWith("guard:")) return true;
        }
        return false;
    }

    /**
     * 读取 NPC 有效属性（ECS EquipmentComponent：base + 装备加成）。
     * ECS 不可用时回退到 NBT transit 字段。
     */
    public float getEffectiveAttribute(AttributeType type) {
        World world = WandscapeEngine.getWorld();
        if (world != null && ecsEntityId > 0) {
            EquipmentComponent eq = world.get(ecsEntityId, EquipmentComponent.class);
            if (eq != null) return eq.getAttribute(type);
        }
        return switch (type) {
            case MAX_HP -> maxHp;
            case MOVE_SPEED -> moveSpeed;
            case SPELL_POWER -> spellPower;
            case WORK_SPEED -> workSpeed;
            case SPELL_SPEED -> spellSpeed;
            case ARMOR_VALUE -> armorValue;
            case MAX_MANA -> maxMana;
        };
    }

    /** 上次推送到 vanilla 的属性值（防每 tick 重复 setBaseValue）。 */
    private float appliedMaxHp = -1;
    private float appliedMoveSpeed = -1;
    private float appliedArmor = -1;

    /** 把有效属性推送到 vanilla 实体（最大生命/移速/护甲）。 */
    private void applyEffectiveAttributes() {
        World world = WandscapeEngine.getWorld();
        if (world == null || ecsEntityId <= 0) return;
        EquipmentComponent eq = world.get(ecsEntityId, EquipmentComponent.class);
        if (eq == null) return;
        float maxHp = eq.getAttribute(AttributeType.MAX_HP);
        float speed = eq.getAttribute(AttributeType.MOVE_SPEED);
        float armor = eq.getAttribute(AttributeType.ARMOR_VALUE);
        if (Math.abs(maxHp - appliedMaxHp) > 0.001f) {
            appliedMaxHp = maxHp;
            this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(maxHp);
            if (getHealth() > maxHp) setHealth(maxHp);
        }
        if (Math.abs(speed - appliedMoveSpeed) > 0.001f) {
            appliedMoveSpeed = speed;
            this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(speed);
        }
        if (Math.abs(armor - appliedArmor) > 0.001f) {
            appliedArmor = armor;
            this.getAttribute(Attributes.ARMOR).setBaseValue(armor);
        }
    }

    // ============================================================
    // Inventory
    // ============================================================

    public final SimpleContainer inventory = new SimpleContainer(27);

    // ============================================================
    // Armor slots (4). Stored separately from vanilla equipment slots so the
    // wizard robe appearance is never overridden — armor only affects stats.
    // ============================================================

    public static final int ARMOR_SLOT_COUNT = 4;

    /** 盔甲格顺序：0=头盔 1=胸甲 2=护腿 3=靴子。 */
    public final SimpleContainer armorInventory = new SimpleContainer(ARMOR_SLOT_COUNT);

    /** 盔甲槽索引 → 原版装备槽（读物品属性/判断装备槽用）。 */
    public static final net.minecraft.world.entity.EquipmentSlot[] ARMOR_VANILLA_SLOTS = {
            net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS,
            net.minecraft.world.entity.EquipmentSlot.FEET
    };

    public ItemStack getArmorItem(int slot) {
        return armorInventory.getItem(slot);
    }

    public void setArmorItem(int slot, ItemStack stack) {
        armorInventory.setItem(slot, stack);
    }

    /** 从物品的原版 ARMOR 属性修饰符求单件盔甲的护甲值（外观不渲染，仅数值生效）。 */
    public static float armorValueOf(ItemStack stack) {
        if (stack.isEmpty()) return 0f;
        float total = 0f;
        for (var entry : stack.getAttributeModifiers().modifiers()) {
            if (entry.attribute().is(Attributes.ARMOR)) {
                total += entry.modifier().amount();
            }
        }
        return Math.max(0f, total);
    }

    /**
     * 把 4 个盔甲格的护甲值同步到 ECS EquipmentComponent（每槽一个加法修饰符），
     * 使 armorValue 同时生效于属性查询（GUI）与伤害减免（vanilla ARMOR）。
     */
    public void syncArmorAttributes() {
        World world = WandscapeEngine.getWorld();
        if (world == null || ecsEntityId <= 0) return;
        EquipmentComponent eq = world.get(ecsEntityId, EquipmentComponent.class);
        if (eq == null) return;
        for (int i = 0; i < ARMOR_SLOT_COUNT; i++) {
            EquipmentSlot coreSlot = switch (i) {
                case 0 -> EquipmentSlot.HEAD;
                case 1 -> EquipmentSlot.CHEST;
                case 2 -> EquipmentSlot.LEGS;
                default -> EquipmentSlot.FEET;
            };
            ItemStack stack = armorInventory.getItem(i);
            if (stack.isEmpty()) {
                eq.unequip(coreSlot);
            } else {
                eq.equip(coreSlot, stack.getItem().getDescriptionId(),
                        List.of(new AttributeModifier(AttributeType.ARMOR_VALUE,
                                armorValueOf(stack), ModifierOperation.ADDITION)));
            }
        }
    }

    // ============================================================
    // Casting state (synced to client for animation + particles)
    // ============================================================

    public static final int SKIN_VARIANT_COUNT = detectSkinVariants();

    private static int detectSkinVariants() {
        try {
            Path dir = ModList.get().getModFileById(Wandscape.MODID).getFile()
                    .findResource("assets", "wandscape", "textures", "entity", "wizard");
            try (Stream<Path> files = Files.list(dir)) {
                int count = (int) files
                        .filter(p -> p.toString().endsWith(".png"))
                        .count();
                if (count > 0) return count;
            }
        } catch (IOException | RuntimeException ignored) {}
        return 1;
    }

    private static final EntityDataAccessor<Integer> DATA_SKIN_VARIANT =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_HAT_COLOR =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Boolean> DATA_CASTING =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<String> DATA_OP_KIND =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.STRING);

    /** Status text shown above the NPC's head (synced to client). */
    private static final EntityDataAccessor<String> DATA_STATUS_TEXT =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.STRING);

    public int getSkinVariant() {
        return this.entityData.get(DATA_SKIN_VARIANT);
    }

    public int getHatColor() {
        return this.entityData.get(DATA_HAT_COLOR);
    }

    /** 恢复外观（复活魔法用）。 */
    public void setSkinVariant(int variant) {
        this.entityData.set(DATA_SKIN_VARIANT, variant);
    }

    public void setHatColor(int color) {
        this.entityData.set(DATA_HAT_COLOR, color);
    }

    public boolean isCasting() {
        return this.entityData.get(DATA_CASTING);
    }

    public void setCasting(boolean casting) {
        this.entityData.set(DATA_CASTING, casting);
    }

    /** Visual effect kind for the current op. Synced to client for renderer dispatch. */
    public String getOpKind() {
        return this.entityData.get(DATA_OP_KIND);
    }

    public void setOpKind(@Nullable String kind) {
        this.entityData.set(DATA_OP_KIND, kind != null ? kind : "");
    }

    /** Status text shown above head. Synced to client. */
    public String getStatusText() {
        return this.entityData.get(DATA_STATUS_TEXT);
    }

    public void setStatusText(String text) {
        this.entityData.set(DATA_STATUS_TEXT, text != null ? text : "");
    }

    /**
     * When true, the NPC is holding the default spawned wand and the wand slot
     * in the mage screen should appear empty to prevent players from taking it.
     */
    private boolean hasDefaultWand = true;

    /**
     * When true, {@link RandomStrollGoal} is suppressed so
     * {@code NavigationSystem} can control navigation without AI interference.
     */
    private boolean suppressWandering = false;

    // ── Dirty guards: only sync entity data when values actually change ──
    private String lastSyncedOpKind = "";
    private BlockPos lastSyncedTarget = null;

    // ── Fast path: skip ECS polling for idle NPCs ──
    private int ecsPollCooldown = 0;

    // ── 手动施法（祭坛施法引导窗口）：窗口内强制 isCasting=true，与 ECS 驱动的施法互不干扰 ──
    private int manualCastTicks = 0;

    /**
     * 触发一次手动施法：在 {@code ticks} 内保持举杖姿态（isCasting=true）。
     * 窗口结束由 tick() 自动恢复为 ECS 决定的状态。
     */
    public void startManualCast(int ticks) {
        manualCastTicks = Math.max(manualCastTicks, ticks);
        setCasting(true);
    }

    // ── 自防御仇恨：被非玩家攻击者打伤后记仇，直到对方死亡/超出范围/过期 ──
    private UUID hatedAttackerUuid = null;
    private long hateExpiryTick = 0;

    /** 记录仇恨目标（非玩家攻击者）与其过期 tick。 */
    public void setHatedAttacker(UUID attackerUuid, long expiryTick) {
        this.hatedAttackerUuid = attackerUuid;
        this.hateExpiryTick = expiryTick;
    }

    /**
     * 当前有效仇恨目标：未过期且在所在 Level 中存活的生物；否则 null。
     * 由 {@code SelfDefenseExecutor} 每轮目标解析调用。
     */
    @Nullable
    public LivingEntity getHatedAttacker(ServerLevel level) {
        if (hatedAttackerUuid == null || level.getGameTime() > hateExpiryTick) return null;
        Entity e = level.getEntity(hatedAttackerUuid);
        return (e instanceof LivingEntity le && le.isAlive() && !le.isRemoved()) ? le : null;
    }

    /** 仇恨已过期或目标已死/不存在时清除，避免空转。 */
    public void clearHatedAttackerIfExpired(ServerLevel level) {
        if (hatedAttackerUuid != null && getHatedAttacker(level) == null) {
            hatedAttackerUuid = null;
            hateExpiryTick = 0;
        }
    }

    /** 无条件清除仇恨（和平模式开启时调用，避免解除和平后立刻寻仇）。 */
    public void clearHatedAttacker() {
        hatedAttackerUuid = null;
        hateExpiryTick = 0;
    }

    // ── 普通攻击（L2 兜底）冷却：无有效魔法时的近战物理攻击，2s 攻速，服务端瞬时态 ──
    private long nextMeleeAttackTick = 0;

    /** 普通攻击是否就绪（距上次攻击已过攻速间隔）。 */
    public boolean canMeleeAttack(long gameTime) {
        return gameTime >= nextMeleeAttackTick;
    }

    /** 记录一次普通攻击：下次可用 = now + cooldownTicks。 */
    public void markMeleeAttack(long gameTime, int cooldownTicks) {
        this.nextMeleeAttackTick = gameTime + cooldownTicks;
    }

    // ── 传送引导（瞬态，不写 NBT）：法阵展开期间定身 + 减伤 75%，落点扫描节流 ──
    private long teleportChannelUntilTick = 0;
    private long nextEscapeScanTick = 0;

    /** 传送引导是否进行中（引导期间定身 + 减伤 75%，见 SelfDefenseHandler）。 */
    public boolean isTeleportChanneling(long gameTime) {
        return gameTime < teleportChannelUntilTick;
    }

    /** 记录一次传送引导开始：在引导时长 + 少量余量内定身 + 减伤。 */
    public void markTeleportChanneling(long gameTime, int channelTicks) {
        this.teleportChannelUntilTick = gameTime + channelTicks + 1;
    }

    /** 逃生落点扫描节流（失败重扫时防每 tick 全扫）：放行时推进 40 tick。 */
    public boolean consumeEscapeScan(long gameTime) {
        if (gameTime < nextEscapeScanTick) return false;
        this.nextEscapeScanTick = gameTime + 40;
        return true;
    }

    // ============================================================
    // 和平 / 跟随 模式（玩家在 NPC 面板右下角切换，NBT 持久化）
    // ============================================================

    /** 和平模式：不攻击任何生物（自防御/守卫/光束伤害全部关闭）。 */
    private boolean peaceMode = false;

    /** 跟随模式：目标玩家距离超过 5 格时走向玩家。 */
    private boolean followMode = false;

    /** 跟随目标玩家（跟随模式开启时记录发起玩家）。 */
    private UUID followerUuid = null;

    public boolean isPeaceMode() {
        return peaceMode;
    }

    public void setPeaceMode(boolean value) {
        this.peaceMode = value;
    }

    public boolean isFollowMode() {
        return followMode;
    }

    public void setFollowMode(boolean value) {
        this.followMode = value;
    }

    public UUID getFollowerUuid() {
        return followerUuid;
    }

    public void setFollowerUuid(UUID uuid) {
        this.followerUuid = uuid;
    }

    // ── 法师等级与小屋归属（法师小屋入住用，NBT 持久化）──
    // level 默认 1；装备加成由 ECS EquipmentComponent 计算，不改此字段。

    private int level = 1;

    /** 法师当前等级（等级加成为每级线性，见 MageHutAttributes）。 */
    public int getLevel() { return level; }

    /** 设置法师等级（最低 1）。 */
    public void setLevel(int level) { this.level = Math.max(1, level); }

    /** 法师所属法师小屋（可空；用于复活后重新挂回小屋）。 */
    @Nullable
    private UUID homeHutId = null;

    @Nullable public UUID getHomeHutId() { return homeHutId; }
    public void setHomeHutId(@Nullable UUID hutId) { this.homeHutId = hutId; }

    // ── 休息（法师小屋「休息」：回小屋休 2 分钟回满状态，期间不接任务）──
    // 瞬态：不持久化（服务器重启中断休息即恢复正常空闲，不卡死）。

    private boolean resting = false;
    private long restEndTick = 0;
    @Nullable
    private BlockPos restPos = null;

    public boolean isResting() { return resting; }

    /** 进入休息：目标点 + 结束 tick。 */
    public void setRest(BlockPos pos, long endTick) {
        this.restPos = pos;
        this.restEndTick = endTick;
        this.resting = true;
    }

    /** 结束休息（恢复空闲，由调度器重新派活；并恢复闲逛，避免休息后永久静止）。 */
    public void endRest() {
        this.resting = false;
        this.restPos = null;
        setAiWanderingEnabled(true);
    }

    @Nullable
    public BlockPos getRestPos() { return restPos; }
    public long getRestEndTick() { return restEndTick; }

    // ── Client-side: last tick particles were spawned (throttle to 1×/tick) ──
    public int lastParticleTick = -1;

    /**
     * 客户端 NPC 面板 3D 展示用的瞬态标记：渲染器据此跳过名牌与气泡。
     * 仅当该实体是 GUI 展示克隆（不在世界里）时为 true。
     */
    public boolean guiDisplayMode = false;

    /** Enable or disable idle wandering AI. Called by NavigationSystem. */
    public void setAiWanderingEnabled(boolean enabled) {
        this.suppressWandering = !enabled;
        if (!enabled) {
            getNavigation().stop();
        }
    }

    /** Debug ray target (synced to client). */
    private static final EntityDataAccessor<Optional<BlockPos>> DATA_DEBUG_TARGET =
            SynchedEntityData.defineId(WandscapeNpc.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);

    public Optional<BlockPos> getDebugTarget() {
        return this.entityData.get(DATA_DEBUG_TARGET);
    }

    public void setDebugTarget(BlockPos pos) {
        this.entityData.set(DATA_DEBUG_TARGET, Optional.ofNullable(pos));
    }

    // ============================================================
    // Construction
    // ============================================================

    public WandscapeNpc(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new WandscapeNavigation(this, level);
    }

    // ============================================================
    // Attributes (must be registered via EntityAttributeCreationEvent)
    // ============================================================

    /**
     * Creates the attribute supplier for this NPC.
     * Register via {@code EntityAttributeCreationEvent} in common setup.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    // ============================================================
    // AI goals
    // ============================================================

    @Override
    protected void registerGoals() {
        // Priority 0: don't drown
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Priority 1: 开门（WandscapeNavigation 已设 canPassDoors/canOpenDoors，
        // DoorInteractGoal.canUse 据此放行；只有水平撞上门时才触发，避免误开）
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        // Priority 2: 法师小屋休息——回到休息点停住并回满状态（可Use与跟随互斥，见各自 canUse）
        this.goalSelector.addGoal(2, new RestGoal());
        // Priority 2: 跟随模式——目标玩家距离 >5 格时走向玩家（休息/被 ECS 任务/施法接管时让路）
        this.goalSelector.addGoal(2, new FollowPlayerGoal());
        // Priority 5: wander around when idle (suppressed when MovementOps controls navigation
        // or the engine is busy / casting — casting no longer roots movement via tickCastingState)
        this.goalSelector.addGoal(5, new RandomStrollGoal(this, 0.6) {
            @Override
            public boolean canUse() {
                return !resting && !suppressWandering && !noIdleWander() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !resting && !suppressWandering && !noIdleWander() && super.canContinueToUse();
            }

            @Override
            public void stop() {
                if (!suppressWandering && !noIdleWander()) {
                    super.stop(); // only clear navigation if stopping organically
                }
                // When suppressWandering is set, MovementOps owns the navigation —
                // don't let the goal selector's cleanup kill our path.
            }
        });
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN_VARIANT, -1);
        builder.define(DATA_HAT_COLOR, 0);
        builder.define(DATA_CASTING, false);
        builder.define(DATA_DEBUG_TARGET, Optional.empty());
        builder.define(DATA_OP_KIND, "");
        builder.define(DATA_STATUS_TEXT, "");
    }

    // ============================================================
    // Lifecycle — ECS bridge
    // ============================================================

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;

        // 传送引导：定身（法阵展开期间不跑不躲，靠 SelfDefenseHandler 的减伤 75% 硬吃）
        if (isTeleportChanneling(level().getGameTime())) {
            getNavigation().stop();
            setDeltaMovement(Vec3.ZERO);
        }

        // 脱战回血 + 属性推送 + 魔力回复：idle NPC 也要执行，放在快路 return 之前
        tickHealthRegen();
        applyEffectiveAttributes();
        // 首 tick 满蓝填充（新 NPC / 旧存档迁移），此后每 10tick 回 1 点
        if (!magic.isManaSeeded()) {
            magic.setMana(getMaxMana());
            magic.markManaSeeded();
        }
        magic.tickRegen(getMaxMana(), Config.NPC_MANA_REGEN_TICKS.get());

        tickIdleSelfHeal();
        tickCastingState();
    }

    /**
     * ECS 驱动施法状态同步：casting/status/debug/op/faceTarget。
     * 子类可覆盖为完全接管（如敌对法师由自己的施法 goal 驱动 {@code isCasting}，
     * 而非 ECS 任务执行器）。
     */
    protected void tickCastingState() {
        boolean manual = manualCastTicks > 0;
        if (manual) manualCastTicks--;

        boolean casting;
        if (ecsPollCooldown > 0 && !isCasting() && !manual) {
            // Fast path: idle NPC, skip ECS query this tick
            ecsPollCooldown--;
            return;
        } else {
            World ecsWorld = WandscapeEngine.getWorld();
            if (ecsWorld != null && ecsEntityId > 0) {
                var exec = ecsWorld.get(ecsEntityId,
                        TaskExecutor.class);
                casting = exec != null
                        && exec.state == ExecutorState.ACTIVE
                        && (exec.npcQueue.hasWork() || exec.globalTaskId != null);
                if (casting && exec.currentOpTarget != null) {
                    var t = exec.currentOpTarget;
                    BlockPos target = new BlockPos(t.x(), t.y(), t.z());
                    if (!target.equals(lastSyncedTarget)) {
                        setDebugTarget(target);
                        lastSyncedTarget = target;
                    }
                    String kind = exec.currentOpKind != null ? exec.currentOpKind : "";
                    if (!kind.equals(lastSyncedOpKind)) {
                        setOpKind(exec.currentOpKind);
                        lastSyncedOpKind = kind;
                    }
                    faceTarget(target);
                } else {
                    if (lastSyncedTarget != null) {
                        setDebugTarget(null);
                        lastSyncedTarget = null;
                    }
                    if (!lastSyncedOpKind.isEmpty()) {
                        setOpKind(null);
                        lastSyncedOpKind = "";
                    }
                }
                // Compute status text from ECS state
                String status = computeStatusText(ecsWorld);
                if (!status.equals(getStatusText())) {
                    setStatusText(status);
                }
            } else {
                casting = false;
                if (!getStatusText().isEmpty()) {
                    setStatusText("");
                }
            }
            // Poll every tick while casting, every 20 ticks while idle
            ecsPollCooldown = casting ? 0 : 20;
        }

        if (manual) casting = true;
        if (casting != isCasting()) {
            setCasting(casting);
        }
        // 施法不再硬钉移动（getNavigation().stop + 清零速度）：光束等长施法会让 isCasting 连续
        // 数百 tick 为 true，硬钉会把战斗走位（风筝/群殴/投掷物躲避，由 ECS 导航驱动）整个钉死——
        // 「释放光束不走位」的根因。现在 isCasting 期间也能走位；空闲乱走由游荡 goal 自己让路
        // （RandomStrollGoal 尊重 isEngineIdle/isCasting，见 registerGoals）。
    }

    // ============================================================
    // 施法几何（与客户端渲染器/模型同一套，保证法阵/光束落在持杖手上）
    // 右臂举杖姿态基准角统一在此，避免模型/渲染/服务端三处硬编码漂移。
    // 瞄准目标时 faceTarget() 设置 getXRot（俯仰角），手臂角度随之指向目标。
    // ============================================================

    /** 举杖姿态右臂 xRot 基准角（弧度）：模型 rightArm.xRot = 此值 + 俯仰角。 */
    public static final double CAST_ARM_ANGLE = -1.2;
    /** 手臂长度（方块）。 */
    public static final double CAST_ARM_LENGTH = 0.75;

    /** 当前右臂抬起角（弧度）= 基准角 + NPC 俯仰角。getXRot 由 faceTarget() 对准目标时设置。 */
    public double getCastArmAngle() {
        return CAST_ARM_ANGLE + Math.toRadians(getXRot());
    }

    /** 持法杖的右手世界位置。 */
    public Vec3 getStaffPosition() {
        double yawRad = Math.toRadians(yBodyRot);
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double armAngle = getCastArmAngle();
        double deltaY = -CAST_ARM_LENGTH * (Math.cos(armAngle) - Math.cos(CAST_ARM_ANGLE));
        double deltaFwd = -CAST_ARM_LENGTH * (Math.sin(armAngle) - Math.sin(CAST_ARM_ANGLE));
        double fwd = 0.6 + deltaFwd;
        double oy = getY() + 1.5 + deltaY;
        double ox = getX() - 0.65 * cos - fwd * sin;
        double oz = getZ() - 0.65 * sin + fwd * cos;
        return new Vec3(ox, oy, oz);
    }

    /** 水平正前方向（基于 yBodyRot，与 spawnCastRay 无目标 fallback 一致）。 */
    public Vec3 getFacingDirection() {
        double yawRad = Math.toRadians(yBodyRot);
        return new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad)).normalize();
    }

    /** Face the NPC toward a target block (yaw from horizontal, pitch from vertical angle). */
    public void faceTarget(BlockPos target) {
        double dx = target.getX() + 0.5 - getX();
        double dz = target.getZ() + 0.5 - getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        setYRot(yaw);
        yBodyRot = yaw;
        yHeadRot = yaw;
        double dy = target.getY() + 0.5 - (getY() + 1.4);
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, hDist));
        setXRot(pitch);
    }

    // ============================================================
    // Status text (shown above NPC head)
    // ============================================================

    /**
     * Compute a short status string from ECS state for overhead display.
     */
    private String computeStatusText(World ecsWorld) {
        if (ecsWorld == null || ecsEntityId < 0) return "";

        var exec = ecsWorld.get(ecsEntityId, TaskExecutor.class);
        var nav = ecsWorld.get(ecsEntityId, NavigationState.class);

        // 1. Navigation states (visible even if idle task-wise)
        if (nav != null) {
            switch (nav.mode) {
                case TELEPORT_WAITING -> { return "waiting_magic"; }
                case TELEPORT_RITUAL   -> { return "waiting_teleport"; }
                case PATHFINDING       -> { return "moving"; }
            }
        }

        // 2. No task executor or no work → idle
        if (exec == null || !(exec.npcQueue.hasWork() || exec.globalTaskId != null) || exec.state == ExecutorState.IDLE) return "idle";

        // 3. Pending async future (navigation or channeled op)
        if (exec.pendingFuture != null && !exec.pendingFuture.isDone()) {
            if (exec.pendingFutureIsNav) return "moving";
            // Channeled op in progress
            String kind = exec.currentOpKind;
            if (kind != null) {
                if (kind.startsWith("block_interact:")) {
                    String action = kind.substring("block_interact:".length());
                    return actionKey(action);
                }
                if (kind.startsWith("ritual:")) {
                    String ritual = kind.substring("ritual:".length());
                    return ritualKey(ritual);
                }
                if (kind.equals("combat")) return "combat";
            }
            return "guiding";
        }

        // 4. Actively executing
        if (exec.state == ExecutorState.ACTIVE) {
            if (exec.currentSequence != null) {
                return "task:" + exec.currentSequence.label();
            }
            String kind = exec.currentOpKind;
            if (kind != null) {
                if (kind.startsWith("block_interact:")) {
                    return actionKey(kind.substring("block_interact:".length()));
                }
                if (kind.startsWith("ritual:")) {
                    return ritualKey(kind.substring("ritual:".length()));
                }
                if (kind.equals("transform")) return "transforming";
                if (kind.equals("combat")) return "combat";
            }
            return "executing";
        }

        if (exec.state == ExecutorState.WAITING) return "waiting";

        return "";
    }

    private static String actionKey(String action) {
        return switch (action) {
            case "gather" -> "gathering";
            case "place" -> "placing";
            case "break" -> "breaking";
            case "interact" -> "interacting";
            case "cast" -> "casting";
            default -> "op:" + action;
        };
    }

    private static String ritualKey(String ritual) {
        return switch (ritual) {
            case "self_teleport" -> "teleporting";
            case "lightning" -> "summon_lightning";
            case "portal_gate" -> "portal_gate";
            case "rain_call" -> "rain_call";
            case "clear_weather" -> "clear_weather";
            default -> "ritual:" + ritual;
        };
    }

    /**
     * Client-side fallback (zh) for a status key, shown only when the lang
     * entry is missing. Keys prefixed {@code op:}/{@code ritual:}/{@code task:}
     * carry dynamic payloads and never resolve via lang — fallback reassembles
     * the original display text.
     */
    public static String statusFallback(String statusKey) {
        return switch (statusKey) {
            case "waiting_magic" -> "等待魔力";
            case "waiting_teleport" -> "等待传送";
            case "moving" -> "移动中";
            case "idle" -> "空闲";
            case "gathering" -> "采集中";
            case "placing" -> "放置中";
            case "breaking" -> "破坏中";
            case "interacting" -> "交互中";
            case "casting" -> "施法中";
            case "combat" -> "战斗中";
            case "guiding" -> "引导中";
            case "transforming" -> "建造中";
            case "executing" -> "执行中";
            case "waiting" -> "等待中";
            case "teleporting" -> "传送中";
            case "summon_lightning" -> "召唤雷电";
            case "portal_gate" -> "开启传送门";
            case "rain_call" -> "祈雨";
            case "clear_weather" -> "驱云";
            default -> {
                if (statusKey.startsWith("op:")) yield "执行: " + statusKey.substring(3);
                if (statusKey.startsWith("ritual:")) yield "施法: " + statusKey.substring(7);
                if (statusKey.startsWith("task:")) yield statusKey.substring(5);
                yield statusKey;
            }
        };
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // 打开 NPC 装备容器菜单（4 盔甲 + 1 法杖 + 玩家背包，全部真实 vanilla 槽）
        if (player instanceof ServerPlayer sp) {
            sp.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> new NpcMenu(id, inv, this),
                    Component.literal("NPC Info")));
            // 下一 tick 补发数据（客户端屏幕就绪后刷新名字/属性等）
            sp.serverLevel().getServer().execute(() -> {
                if (!isRemoved() && sp.containerMenu instanceof NpcMenu) {
                    PacketDistributor.sendToPlayer(sp, NpcDataPacket.from(WandscapeNpc.this));
                }
            });
        }
        return InteractionResult.CONSUME;
    }

    // ── Default wand tracking ──

    /** Whether the NPC still has the default spawned wand (prevent player from taking it). */
    public boolean hasDefaultWand() {
        return hasDefaultWand;
    }

    public void setHasDefaultWand(boolean value) {
        this.hasDefaultWand = value;
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!level().isClientSide) {
            // Give the mage a name if it doesn't have one (spawn egg / colony spawns;
            // tavern-recruited and revived mages already carry a name). Assign once —
            // the custom name persists through save/load, so this is a no-op later.
            // The colony's naming rule applies when the mage spawns inside one.
            if (!hasCustomName()) {
                setCustomName(com.wsteam.wandscape.shared.data.CharacterNames.displayComponent(
                        generateRandomNpcName(detectNamingStyle())));
                setCustomNameVisible(true);
            }
            // 默认装备 beam + heal（新 NPC / 旧存档无字段），此后玩家经策略页装备卷轴改。
            // 分类由 MagicDef 数据驱动（core 组件不依赖 magic，种子在实体层做）。
            if (!spellbookLoaded && equippedMagic.isEmpty()) {
                for (String defaultSpell : EquippedMagicComponent.DEFAULT_EQUIP) {
                    MagicDef def = SpellbookLoader.getSpec(defaultSpell);
                    if (def != null && def.category() != MagicDef.Category.UTILITY) {
                        equippedMagic.equip(def.category().name().toLowerCase(Locale.ROOT), defaultSpell);
                    }
                }
            }
            if (getSkinVariant() < 0) {
                this.entityData.set(DATA_SKIN_VARIANT, random.nextInt(SKIN_VARIANT_COUNT));
            }
            if (getHatColor() == 0) {
                this.entityData.set(DATA_HAT_COLOR, generateRandomHatColor());
            }
            // Equip wand on initial spawn so casting animation shows the item
            if (getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
                setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(Wandscape.WAND.get()));
            }
            // Prevent vanilla despawn — NPC persistence is managed by the colony/engine
            this.setPersistenceRequired();
            if (isColonyNpc()) {
                World world = WandscapeEngine.getWorld();
                if (world != null) {
                    EntityComponentBridge.INSTANCE.onNpcJoinWorld(this, world);
                    syncArmorAttributes();
                } else {
                    // Engine not yet bootstrapped — entity loaded before ServerStartingEvent.
                    // Defer registration until the next tick.
                    Log.warn(TAG, "NPC {} onAddedToLevel but Engine World is null — deferring ECS registration",
                            getUUID().toString().substring(0, 8));
                    EntityComponentBridge.INSTANCE.deferJoin(this);
                }
            }
        }
    }

    /**
     * 是否作为小镇 NPC 注册进 ECS（加入任务调度/属性权威/死亡记录等）。
     * 敌对测试法师等独立实体覆盖为 false：保留外观/魔法表/法杖初始化，但不进 ECS，
     * 也因此在死亡记录与村民索敌增强中被排除（见 NpcDeathHandler / HostileTargetingHandler）。
     */
    public boolean isColonyNpc() {
        return true;
    }

    private int generateRandomHatColor() {
        float hue = random.nextFloat();
        float saturation = 0.5f + random.nextFloat() * 0.5f;
        float brightness = 0.3f + random.nextFloat() * 0.7f;
        int rgb = java.awt.Color.HSBtoRGB(hue, saturation, brightness);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    @Override
    public void onRemovedFromLevel() {
        RemovalReason reason = getRemovalReason();
        if (!level().isClientSide && reason != null) {
            World world = WandscapeEngine.getWorld();

            // CHANGED_DIMENSION: entity is transitioning to another dimension,
            // not leaving the world. Skip all cleanup — ECS components stay.
            if (reason == RemovalReason.CHANGED_DIMENSION) {
                super.onRemovedFromLevel();
                return;
            }

            // KILLED / DISCARDED: entity is destroyed (died, /kill, despawn).
            // Release global task for reassignment (preserve stepIndex),
            // then destroy ECS components. Private queue is discarded.
            if (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED) {
                if (world != null && ecsEntityId > 0) {
                    var exec = world.get(ecsEntityId,
                            TaskExecutor.class);
                    if (exec != null && exec.globalTaskId != null) {
                        world.taskPool.releaseTaskForReassign(
                                exec.globalTaskId, ecsEntityId, world);
                    }

                    // Release resource reservations from pending transports.
                    // Items were reserved but never consumed — just dropping the
                    // reservation is correct (no items need to be returned to bank).
                    var resourceReqExec = WandscapeEngine.getResourceRequestExec();
                    if (resourceReqExec != null) {
                        resourceReqExec.cancelForNpc(ecsEntityId);
                    }

                    // Orphan recovery: cancel all in-flight transports for this NPC
                    var transporter = WandscapeEngine.getTransporter();
                    if (transporter != null) {
                        var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(level());
                        if (bank != null) {
                            UUID cid = this.colonyId != null ? this.colonyId : new UUID(0, 0);
                            var member = world.get(ecsEntityId,
                                    ColonyMember.class);
                            if (member != null && member.colonyId() != null) cid = member.colonyId();
                            transporter.cancelForNpc(ecsEntityId, bank, cid);
                        }
                    }

                    EntityComponentBridge.INSTANCE.onNpcLeaveWorld(this, world);
                }
            }
            // UNLOADED_TO_CHUNK / UNLOADED_WITH_PLAYER:
            // Entity still exists, just unloaded. Keep ECS components alive
            // for reconnection when the chunk/player returns.
        }
        super.onRemovedFromLevel();
    }

    // ============================================================
    // NBT persistence
    // ============================================================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("SkinVariant", getSkinVariant());
        tag.putInt("HatColor", getHatColor());
        tag.putLong("EcsEntityId", ecsEntityId);
        tag.putFloat("maxHp", maxHp);
        tag.putFloat("moveSpeed", moveSpeed);
        tag.putFloat("spellPower", spellPower);
        tag.putFloat("workSpeed", workSpeed);
        tag.putFloat("spellSpeed", spellSpeed);
        tag.putFloat("armorValue", armorValue);
        tag.putFloat("maxMana", maxMana);
        tag.putFloat("currentMana", magic.getMana());
        tag.putInt("manaRegenAccum", magic.getManaRegenAccum());
        tag.putInt("spellLockTicks", magic.getLockTicks());
        tag.putBoolean("manaSeeded", magic.isManaSeeded());
        CompoundTag magicCds = new CompoundTag();
        for (Map.Entry<String, Integer> e : magic.getCooldowns().entrySet()) {
            magicCds.putInt(e.getKey(), e.getValue());
        }
        tag.put("magicCooldowns", magicCds);
        tag.putInt("regenCooldown", regenCooldown);
        tag.putInt("regenAccum", regenAccum);
        tag.putBoolean("hasDefaultWand", hasDefaultWand);
        tag.putBoolean("PeaceMode", peaceMode);
        tag.putBoolean("FollowMode", followMode);
        if (followerUuid != null) {
            tag.putUUID("FollowerUuid", followerUuid);
        }
        // 盔甲格（外观不渲染，仅属性生效）
        ListTag armorList = new ListTag();
        for (int i = 0; i < ARMOR_SLOT_COUNT; i++) {
            armorList.add(armorInventory.getItem(i).saveOptional(registryAccess()));
        }
        tag.put("armorInventory", armorList);
        // 施法决策：已装备魔法（按分类 4 桶、桶内槽位序）+ 策略预设 + 自定义优先级（保留作覆盖）
        CompoundTag spellbookEquip = new CompoundTag();
        for (String cat : EquippedMagicComponent.CATEGORIES) {
            List<String> slot = equippedMagic.list(cat);
            if (!slot.isEmpty()) {
                ListTag catList = new ListTag();
                for (String id : slot) {
                    catList.add(StringTag.valueOf(id));
                }
                spellbookEquip.put(cat, catList);
            }
        }
        tag.put("spellbookEquip", spellbookEquip);
        tag.putString("castStrategyPreset", castStrategy.preset().name());
        ListTag customPriority = new ListTag();
        for (String id : castStrategy.customPriority()) {
            customPriority.add(StringTag.valueOf(id));
        }
        tag.put("castStrategyPriority", customPriority);
        tag.putBoolean("castStrategyConfigured", castStrategy.configured());
        if (colonyId != null) {
            tag.putUUID("colonyId", colonyId);
        }
        tag.putInt("npcLevel", level);
        if (homeHutId != null) {
            tag.putUUID("homeHutId", homeHutId);
        }
        // Inventory save deferred to stage 3+ (wand contents)
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("SkinVariant")) {
            this.entityData.set(DATA_SKIN_VARIANT, tag.getInt("SkinVariant"));
        }
        if (tag.contains("HatColor")) {
            this.entityData.set(DATA_HAT_COLOR, tag.getInt("HatColor"));
        }
        ecsEntityId = tag.getLong("EcsEntityId");
        maxHp = tag.getFloat("maxHp");
        moveSpeed = tag.getFloat("moveSpeed");
        spellPower = tag.getFloat("spellPower");
        workSpeed = tag.getFloat("workSpeed");
        spellSpeed = tag.getFloat("spellSpeed");
        armorValue = tag.getFloat("armorValue");
        maxMana = tag.getFloat("maxMana");
        Map<String, Integer> cds = new HashMap<>();
        if (tag.contains("magicCooldowns")) {
            CompoundTag mc = tag.getCompound("magicCooldowns");
            for (String key : mc.getAllKeys()) {
                cds.put(key, mc.getInt(key));
            }
        }
        magic.load(tag.getFloat("currentMana"), tag.getInt("manaRegenAccum"),
                tag.getInt("spellLockTicks"), tag.getBoolean("manaSeeded"), cds);
        regenCooldown = tag.getInt("regenCooldown");
        regenAccum = tag.getInt("regenAccum");
        hasDefaultWand = tag.getBoolean("hasDefaultWand");
        peaceMode = tag.getBoolean("PeaceMode");
        followMode = tag.getBoolean("FollowMode");
        if (tag.hasUUID("FollowerUuid")) {
            followerUuid = tag.getUUID("FollowerUuid");
        } else {
            followerUuid = null;
        }
        // 盔甲格恢复（旧存档无字段 → 空）
        if (tag.contains("armorInventory", Tag.TAG_LIST)) {
            ListTag armorList = tag.getList("armorInventory", Tag.TAG_COMPOUND);
            for (int i = 0; i < ARMOR_SLOT_COUNT && i < armorList.size(); i++) {
                armorInventory.setItem(i,
                        ItemStack.parseOptional(registryAccess(), armorList.getCompound(i)));
            }
        }
        // 施法决策恢复：仅新存档（spellbookEquip）加载；旧存档丢弃 spellbookIds 与优先级
        // （B 阶段决策），组件留空 → onAddedToLevel 种默认 beam+heal，策略回出厂 balanced/未配置。
        if (tag.contains("spellbookEquip")) {
            spellbookLoaded = true;
            CompoundTag equipTag = tag.getCompound("spellbookEquip");
            for (String cat : EquippedMagicComponent.CATEGORIES) {
                if (equipTag.contains(cat, Tag.TAG_LIST)) {
                    ListTag slot = equipTag.getList(cat, Tag.TAG_STRING);
                    for (int i = 0; i < slot.size(); i++) {
                        equippedMagic.equip(cat, slot.getString(i));
                    }
                }
            }
            castStrategy.setPreset(tag.getString("castStrategyPreset"));
            if (tag.contains("castStrategyPriority")) {
                ListTag pl = tag.getList("castStrategyPriority", Tag.TAG_STRING);
                List<String> pri = new ArrayList<>(pl.size());
                for (int i = 0; i < pl.size(); i++) {
                    pri.add(pl.getString(i));
                }
                castStrategy.setCustomPriority(pri);
            }
            if (tag.contains("castStrategyConfigured")) {
                castStrategy.setConfigured(tag.getBoolean("castStrategyConfigured"));
            } else {
                castStrategy.setConfigured("CUSTOM".equals(tag.getString("castStrategyPreset")));
            }
        }
        if (tag.hasUUID("colonyId")) {
            colonyId = tag.getUUID("colonyId");
        }
        if (tag.contains("npcLevel")) {
            level = Math.max(1, tag.getInt("npcLevel"));
        }
        if (tag.hasUUID("homeHutId")) {
            homeHutId = tag.getUUID("homeHutId");
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Whether the NPC is idle (no ECS work). Used by NpcApiImpl. */
    public boolean isEngineIdle() {
        if (ecsEntityId < 0) return true;
        World world = WandscapeEngine.getWorld();
        if (world == null) return true;
        var exec = world.get(ecsEntityId, TaskExecutor.class);
        return exec == null || !(exec.npcQueue.hasWork() || exec.globalTaskId != null);
    }

    /** 引擎有任务 / 施法中 / 手动引导时禁止空闲游荡（RandomStrollGoal 让路用）。
     *  施法不再由 tickCastingState 硬钉停移动（战斗走位需要 isCasting 期间能移动），
     *  空闲乱走改由游荡 goal 在此自行让路——与 FollowPlayerGoal.busy() 同语义。 */
    private boolean noIdleWander() {
        return !isEngineIdle() || isCasting() || manualCastTicks > 0;
    }

    @Nullable
    public UUID getCurrentTaskId() {
        if (ecsEntityId < 0) return null;
        World world = WandscapeEngine.getWorld();
        if (world == null) return null;
        var exec = world.get(ecsEntityId, TaskExecutor.class);
        return exec != null && exec.globalTaskId != null
                ? new UUID(0, exec.globalTaskId) : null;
    }

    /** In-game display name for the NPC (resolved to the current language). */
    public String getNpcName() {
        if (!hasCustomName()) return "Mage";
        return com.wsteam.wandscape.shared.data.CharacterNames.localizedString(getCustomName().getString());
    }

    // ── Auto-generated mage names ──
    // Only used when a mage has no custom name (spawn egg / colony spawns).
    // Tavern-recruited and revived mages keep their own names. The naming rule
    // of the colony the mage spawns inside applies; outside any colony the
    // default style (FANTASY) is used.

    /** Roll a random name key for the given style. */
    public static String generateRandomNpcName(com.wsteam.wandscape.shared.data.NameStyle style) {
        return com.wsteam.wandscape.shared.data.CharacterNames.generateRandomNameKey(style);
    }

    /** The naming rule of the colony this mage is spawning inside, if any. */
    private com.wsteam.wandscape.shared.data.NameStyle detectNamingStyle() {
        var colonyApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
        if (colonyApi != null) {
            UUID detected = colonyApi.getColonyId(blockPosition());
            if (detected != null) {
                return colonyApi.getNamingStyle(detected);
            }
        }
        return com.wsteam.wandscape.shared.data.NameStyle.FANTASY;
    }

    // ============================================================
    // Work animation (called from engine boundary on op completion)
    // ============================================================

    /**
     * Visual feedback for work completion: arm swing + particles at target.
     * Called from AsyncTransformExecutor when a block op finishes.
     */
    public void doWorkAnimation(BlockPos target) {
        this.swing(InteractionHand.MAIN_HAND);
        if (level().isClientSide) return;
        // Spawn particles at the target block position (server syncs to clients)
        for (int i = 0; i < 5; i++) {
            level().addParticle(
                    ParticleTypes.WITCH,
                    target.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.5,
                    target.getY() + 0.5 + (random.nextDouble() - 0.5) * 0.5,
                    target.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.5,
                    0, 0, 0);
        }
    }

    // ============================================================
    // 跟随模式：目标玩家距离 > 5 格时走向玩家（独立于 ECS 导航，空闲时才生效）
    // ============================================================

    /** 跟随起步距离平方（5²）。 */
    private static final double FOLLOW_START_DIST_SQ = 5.0 * 5.0;
    /** 跟随停止距离平方（3²）：进入该范围后停下，避免在 5 格边界反复启停。 */
    private static final double FOLLOW_STOP_DIST_SQ = 3.0 * 3.0;
    /** 跟随移动速度系数（作用于基础移速）。 */
    private static final double FOLLOW_SPEED = 1.0;

    // ── 休息目标（法师小屋）：回到休息点停住，期间回满血/蓝，到点结束恢复空闲 ──
    // 与跟随/游荡互斥（跟随与游荡的 canUse 在休息时返回 false）。休息用 vanilla 寻路直走，
    // 独立于 ECS 导航（休息时已释放任务、引擎空闲），与 FollowPlayerGoal 同构。

    private class RestGoal extends Goal {
        /** 判定「已到休息点」的水平距离平方（2 格）。 */
        private static final double REST_ARRIVE_SQ = 4.0;

        RestGoal() {
            setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return resting;
        }

        @Override
        public boolean canContinueToUse() {
            return resting;
        }

        @Override
        public void tick() {
            if (level() == null) return;
            // 到点结束休息（恢复空闲，由调度器重新派活；已释放的任务由 TaskExecutionSystem 处理）
            if (level().getGameTime() >= restEndTick) {
                endRest();
                return;
            }
            // 回满血/蓝：休息目标。血每 tick 稳步回升，蓝直接补到上限。
            if (getHealth() < getMaxHealth()) {
                heal(1f);
            }
            magic.setMana(getMaxMana());
            // 走向休息点；已到则停住。
            if (restPos != null && distanceToSqr(Vec3.atCenterOf(restPos)) > REST_ARRIVE_SQ) {
                getNavigation().moveTo(restPos.getX(), restPos.getY(), restPos.getZ(), 0.9);
            } else if (restPos != null) {
                getNavigation().stop();
            }
        }

        @Override
        public void stop() {
            if (!resting) {
                getNavigation().stop();
            }
        }
    }

    private class FollowPlayerGoal extends Goal {
        private int repathCooldown = 0;

        FollowPlayerGoal() {
            setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Nullable
        private Player follower() {
            if (followerUuid == null) return null;
            if (!(level() instanceof ServerLevel serverLevel)) return null;
            Entity e = serverLevel.getEntity(followerUuid);
            return (e instanceof Player p && p.isAlive() && !p.isRemoved()) ? p : null;
        }

        /** ECS 任务/施法/手动引导/休息接管时让路，跟随不抢导航。
         *  isEngineIdle 直读 ECS（无轮询延迟），任务一入队立即让路。 */
        private boolean busy() {
            return !isEngineIdle() || suppressWandering || isCasting() || manualCastTicks > 0;
        }

        @Override
        public boolean canUse() {
            if (resting || !followMode || busy()) return false;
            Player p = follower();
            return p != null && distanceToSqr(p) > FOLLOW_START_DIST_SQ;
        }

        @Override
        public boolean canContinueToUse() {
            if (resting || !followMode || busy()) return false;
            Player p = follower();
            return p != null && distanceToSqr(p) > FOLLOW_STOP_DIST_SQ;
        }

        @Override
        public void tick() {
            Player p = follower();
            if (p == null) return;
            if (getNavigation().isDone()) {
                getNavigation().moveTo(p, FOLLOW_SPEED);
            } else if (--repathCooldown <= 0) {
                getNavigation().moveTo(p, FOLLOW_SPEED);
                repathCooldown = 10;
            }
        }

        @Override
        public void stop() {
            // 任务/施法接管时不清 navigation（NavigationSystem 自己会驱动/重寻路）；
            // 仅在空闲状态下取消（如玩家取消跟随）。
            if (!suppressWandering && !isCasting()) {
                getNavigation().stop();
            }
        }
    }
}
