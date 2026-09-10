package com.wsteam.wandscape.content.npc.internal;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.colony.ColonySavedData;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.component.NpcInventory;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.foundation.service.ParticleService;
import com.wsteam.wandscape.content.npc.data.DeathRecord;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.api.MagicApi;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes;
import com.wsteam.wandscape.content.npc.data.MageHutResident;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 复活效果：祭坛施法引导完成后在指定位置（祭坛中心最上方）生成新 WandscapeNpc，
 * 恢复身份/外观/属性/装备/背包。入口两条，均经 {@link #spawnFromRecordAt}：
 * <ul>
 *   <li><b>祭坛</b>（常规）：AltarCastExecutor 在祭坛施法引导结束后调用。</li>
 *   <li><b>市政厅保底</b>（防卡死）：小镇全灭时玩家在市政厅面板按「复活法师」按钮，
 *       经 {@link #reviveLatestAtTownHall} 复活最近阵亡者——没有存活法师就没人能跑祭坛，
 *       这是唯一的自举出口。</li>
 * </ul>
 * shift+右键直接施放已移除（MagicInteractHandler 删除）。
 *
 * <p>虚弱复活：生成即 1 血 0 蓝，靠脱战回血与魔力回复缓慢恢复。
 */
public final class ReviveHandler {

    private static final String TAG = "Revive";

    /** revive 魔法 id（magic_spells/revive.json 的 key）。 */
    public static final String REVIVE_MAGIC_ID = "revive";

    /** 市政厅保底复活冷却（tick，5 分钟）——防止「全员送死 → 按钮拉人」反复刷。 */
    public static final long TOWN_HALL_REVIVE_COOLDOWN_TICKS = 5 * 60 * 20L;

    /** 市政厅保底复活的结果，供 UI 回执区分文案。 */
    public enum TownHallReviveResult {
        /** 复活成功。 */
        OK,
        /** 该小镇没有待复活的死亡记录。 */
        NO_DEATH_RECORD,
        /** 仍有存活法师——保底按钮只在全灭时可用。 */
        STILL_ALIVE,
        /** 冷却中。 */
        COOLDOWN,
        /** 定位成功但实体生成失败（记录保留，可重试）。 */
        SPAWN_FAILED
    }

    private ReviveHandler() {}

    // ── 全灭判定与保底复活（市政厅 UI）──

    /**
     * 小镇在世法师数量。**负载无关**：桥条目只在实体真正销毁（KILLED/DISCARDED，即
     * {@link net.minecraft.world.entity.Entity.RemovalReason#shouldDestroy()}）时才被
     * {@code onNpcLeaveWorld} 摘掉，区块卸载/跨维度只标记 removed、条目照留——所以按桥条目
     * 判定，不受远处法师所在区块是否加载影响。
     *
     * <p>两个必须绕开的坑：①阵亡后桥条目还会残留约 20 tick（死亡动画结束才 setRemoved），
     * 期间 {@code isAlive()} 已为 false，故不能只看条目存在；②实体引用丢失时保守算在世，
     * 宁可按钮不可按，也不能误判全灭而多生成一名法师。
     */
    public static int aliveCount(UUID colonyId) {
        if (colonyId == null) return 0;
        World world = com.wsteam.wandscape.content.task.ecs.World.getActive();
        if (world == null) return 0;
        int n = 0;
        for (var entry : EntityComponentBridge.INSTANCE.allNpcs().entrySet()) {
            ColonyMember member = world.get(entry.getKey(), ColonyMember.class);
            if (member != null && colonyId.equals(member.colonyId())
                    && isBridgeEntryAlive(entry.getValue())) {
                n++;
            }
        }
        return n;
    }

    /** 桥条目是否代表一名在世法师（见 {@link #aliveCount} 的两条注意事项）。 */
    private static boolean isBridgeEntryAlive(WandscapeNpc npc) {
        if (npc == null) return true;
        if (!npc.isRemoved()) return npc.isAlive();
        var reason = npc.getRemovalReason();
        return reason != null && !reason.shouldDestroy(); // 仅卸载/跨维度：人还在，只是没加载
    }

    /** 小镇待复活人数（死亡记录条数）。 */
    public static int deadCount(Level level, UUID colonyId) {
        if (level == null || colonyId == null) return 0;
        return ColonyDeathRegistry.get(level).countInColony(colonyId);
    }

    /** 市政厅保底复活剩余冷却（tick）；未在冷却返回 0。 */
    public static long townHallReviveCooldownRemaining(Level level, UUID colonyId) {
        if (level == null || colonyId == null) return 0;
        long until = ColonySavedData.getOrCreate(level).getReviveCooldownUntil(colonyId);
        return Math.max(0L, until - level.getGameTime());
    }

    /** 市政厅保底复活剩余冷却（秒，向上取整）——UI 倒计时与回执文案共用，避免两处各写一遍换算。 */
    public static int townHallReviveCooldownSeconds(Level level, UUID colonyId) {
        return (int) ((townHallReviveCooldownRemaining(level, colonyId) + 19L) / 20L);
    }

    /**
     * 市政厅保底复活：全灭且不在冷却时，把该小镇最近阵亡者拉回市政厅门口（虚弱状态）。
     * 免费，代价是 5 分钟冷却；只有全灭才能触发，所以复活出的这一个是玩家唯一的自举起点，
     * 其余死者仍须由他跑祭坛逐个救回。
     */
    public static TownHallReviveResult reviveLatestAtTownHall(ServerLevel level, UUID colonyId) {
        if (level == null || colonyId == null) return TownHallReviveResult.NO_DEATH_RECORD;
        DeathRecord rec = ColonyDeathRegistry.get(level).latestInColony(colonyId);
        if (rec == null) return TownHallReviveResult.NO_DEATH_RECORD;
        if (aliveCount(colonyId) > 0) return TownHallReviveResult.STILL_ALIVE;
        if (townHallReviveCooldownRemaining(level, colonyId) > 0) return TownHallReviveResult.COOLDOWN;
        // 双保险：桥未收录但实体仍活在某个已加载区块（例如桥刚被重建）时不再生成第二个实体
        if (level.getEntity(rec.npcId()) instanceof WandscapeNpc) {
            Log.warn(TAG, "市政厅保底复活中止：{} 的原始实体仍存活，跳过以免生成重复实体", rec.name());
            return TownHallReviveResult.STILL_ALIVE;
        }

        BlockPos at = resolveTownHallDoorOrAnchor(level, colonyId,
                new BlockPos(rec.x(), rec.y(), rec.z()));
        if (!spawnFromRecordAt(level, rec, at)) return TownHallReviveResult.SPAWN_FAILED;

        ColonySavedData.getOrCreate(level).setReviveCooldownUntil(colonyId,
                level.getGameTime() + TOWN_HALL_REVIVE_COOLDOWN_TICKS);
        Log.info(TAG, "市政厅保底复活：小镇 {} 全灭，已在市政厅门口 {} 唤醒 {}（冷却 {} tick）",
                colonyId.toString().substring(0, 8), at.toShortString(), rec.name(),
                TOWN_HALL_REVIVE_COOLDOWN_TICKS);
        return TownHallReviveResult.OK;
    }

    /** 定位小镇市政厅门口：category=government 建筑优先用 door_offsets 的可站入口点。包内可见（NpcApi 复活默认位复用）。 */
    static BlockPos resolveTownHallDoorOrAnchor(ServerLevel level, UUID colonyId, BlockPos fallback) {
        BuildingSavedData savedData = BuildingSavedData.get(level);
        if (savedData != null) {
            for (BuildingState b : savedData.getAllBuildings()) {
                if (!colonyId.equals(b.getColonyId())) continue;
                if (!WandscapeConstants.BUILDING_CATEGORY_GOVERNMENT.equals(b.getCategory())) continue;
                // 门口：door_offsets 外可站地面（市政厅门口）
                BlockPos door = savedData.getEntryPoint(b.getBuildingId(), level);
                if (door != null) return door;
                // 交互点（interact spot 世界坐标）
                BlockPos spot = savedData.getTouristInteractPoint(b.getBuildingId(), level);
                if (spot != null) return spot;
                return b.getAnchor();
            }
        }
        return fallback;
    }

    /** 在指定位置生成新 NPC，恢复死亡快照，删除记录。返回是否成功生成（失败保留记录，可重试）。 */
    public static boolean spawnFromRecordAt(ServerLevel level, DeathRecord rec, BlockPos desiredPos) {
        BlockPos spawnPos = resolveSpawnPos(level, desiredPos);
        WandscapeNpc npc = Wandscape.WANDSCAPE_NPC.get().spawn(level, spawnPos, MobSpawnType.COMMAND);
        if (npc == null) {
            Log.warn(TAG, "复活失败：无法在 {} 生成 NPC（记录保留，可重试）", spawnPos.toShortString());
            return false;
        }
        npc.setPersistenceRequired();
        npc.colonyId = rec.colonyId();
        npc.setCustomName(Component.literal(rec.name()));
        npc.setCustomNameVisible(true);
        npc.setSkinVariant(rec.skinVariant());
        npc.setHatColor(rec.hatColor());
        npc.setBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.MAX_HP, rec.maxHp());
        npc.setBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.MOVE_SPEED, rec.moveSpeed());
        npc.setBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.SPELL_POWER, rec.spellPower());
        npc.setBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.WORK_SPEED, rec.workSpeed());
        npc.setBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.SPELL_SPEED, rec.spellSpeed());
        npc.setBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.ARMOR_VALUE, rec.armorValue());
        npc.setBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.MAX_MANA, rec.maxMana());
        // 虚弱复活：1 血 0 蓝，靠脱战回血（interval 回 1 HP）与魔力回复（10t 回 1% 上限）缓慢恢复
        npc.setHealth(1f);
        npc.magic.setMana(0f);
        // 装备与法杖在死亡时已掉落在阵亡处，复活时给一把默认基础法杖
        npc.setHasDefaultWand(true);

        // 若该法师曾入住法师小屋：重挂到那间小屋，并用小屋持有的等级/基础重算属性
        // （小屋入住记录在死亡时不变——这里恢复其养成进度并更新入住者 uuid）。
        rebindToMageHut(level, npc, rec);

        fixEcsAfterSpawn(npc, rec);
        // 恢复已装备魔法卷轴：死亡时不掉落、记入死亡记录，这里重新挂回复活后 NPC
        //（沿用 SpellcastingApi 的服务端权威校验：未知/ALTAR/SPECIAL 丢、每类 ≤3、去重）。
        restoreEquippedMagic(npc, rec);
        ColonyDeathRegistry.get(level).remove(rec);

        spawnReviveBurst(level, spawnPos.getX() + 0.5, spawnPos.getY() + 1.0, spawnPos.getZ() + 0.5);
        Log.info(TAG, "NPC {} ({}) 已复活 at {}（恢复 {} 格背包）",
                npc.getUUID().toString().substring(0, 8), rec.name(),
                spawnPos.toShortString(), rec.inventory().size());
        return true;
    }

    /** spawn() 已用默认属性注册 ECS——这里按死亡快照重新设置小镇与背包。 */
    private static void fixEcsAfterSpawn(WandscapeNpc npc, DeathRecord rec) {
        World ecsWorld = com.wsteam.wandscape.content.task.ecs.World.getActive();
        if (ecsWorld == null) return;
        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(npc.getUUID());
        if (ecsId == null) return;

        var member = ecsWorld.get(ecsId, ColonyMember.class);
        if (member != null && !rec.colonyId().equals(member.colonyId())) {
            ecsWorld.addComponent(ecsId, new ColonyMember(rec.colonyId()));
        }

        NpcInventory inv = ecsWorld.get(ecsId, NpcInventory.class);
        if (inv != null) {
            for (ResourceStack s : rec.inventory()) {
                inv.add(s);
            }
        }
    }

    /** 把死亡快照中的已装备魔法卷轴重新挂到复活后 NPC。空负载（无卷轴 / 玩家全卸）跳过——种子默认
     *  beam+heal 保留；非空则经 MagicApi 服务端权威校验后全量替换。 */
    private static void restoreEquippedMagic(WandscapeNpc npc, DeathRecord rec) {
        if (rec.equippedMagic().isEmpty()) return;
        MagicApi casting = WandscapeApis.getMagicApiSilently();
        if (casting == null) return;
        // 预设读回自复活后 NPC（种子默认），仅重设载荷，策略预设保持不变。
        casting.setEquippedAndStrategy(npc.getUUID(), casting.getStrategyPreset(npc.getUUID()),
                rec.equippedMagic());
    }

    /** 若死亡快照的 npcId 匹配某法师小屋的入住者，则重挂并恢复养成进度。
     *  小屋入住记录在死亡时不变（resident.npcId 仍指向死者 UUID），这里用它反查小屋。 */
    private static void rebindToMageHut(ServerLevel level, WandscapeNpc npc, DeathRecord rec) {
        BuildingSavedData savedData = BuildingSavedData.get(level);
        if (savedData == null) return;
        for (BuildingState b : savedData.getAllBuildings()) {
            MageHutResident resident = savedData.getMageHutResident(b.getBuildingId());
            if (resident != null && rec.npcId().equals(resident.npcId())) {
                for (AttributeType type : NpcAttributes.ORDER) {
                    setFlat(npc, type, NpcAttributes.computeEffective(type,
                            resident.base(type), resident.level(), 0f));
                }
                npc.setLevel(resident.level());
                npc.setHomeHutId(b.getBuildingId());
                savedData.setMageHutResident(b.getBuildingId(), resident.withNpcId(npc.getUUID()));
                Log.info(TAG, "NPC {} re-bound to mage hut {} (Lv.{}) — progression preserved",
                        rec.name(), b.getBuildingId().toString().substring(0, 8), resident.level());
                return;
            }
        }
    }

    private static void setFlat(WandscapeNpc npc, AttributeType type, float value) {
        npc.setBaseAttributeValue(type, value);
    }

    /** 期望位置（祭坛中心最上方）通常已是空气且下方有实体；被占则在周围找可站位置。 */
    private static BlockPos resolveSpawnPos(ServerLevel level, BlockPos desired) {
        for (int dy = 0; dy < 4; dy++) {
            BlockPos check = desired.above(dy);
            if (level.isEmptyBlock(check) && !level.isEmptyBlock(check.below())) {
                return check;
            }
        }
        BlockPos[] candidates = {
                desired.offset(1, 0, 0), desired.offset(-1, 0, 0),
                desired.offset(0, 0, 1), desired.offset(0, 0, -1),
        };
        for (BlockPos pos : candidates) {
            if (level.isEmptyBlock(pos) && !level.isEmptyBlock(pos.below())) {
                return pos;
            }
        }
        return desired.above(2);
    }

    /** 复活爆点：ENTITY_EFFECT 原生绿色魔法粒子（药水式发光粒子，原生广播必现）+ burstColored 绿色 glow 爆花（增强）。 */
    private static void spawnReviveBurst(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.29f, 0.87f, 0.50f),
                x, y, z, 30, 0.4, 1.0, 0.4, 0.15);
        ParticleService.burstColored(level, new Vec3(x, y, z), 0.29f, 0.87f, 0.50f, 30, 0.22f, 40, false);
        for (int i = 0; i < 8; i++) {
            double a = i / 8.0 * Math.PI * 2;
            ParticleService.burstColored(level,
                    new Vec3(x + Math.cos(a) * 1.1, y + level.random.nextDouble() * 0.8, z + Math.sin(a) * 1.1),
                    0.53f, 0.94f, 0.67f, 10, 0.16f, 34, false);
        }
    }
}
