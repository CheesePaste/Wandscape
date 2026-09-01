package com.wsteam.wandscape.content.npc.internal;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.component.NpcInventory;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.foundation.service.ParticleService;
import com.wsteam.wandscape.content.npc.data.DeathRecord;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.api.SpellcastingApi;
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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 复活效果：祭坛施法引导完成后在指定位置（祭坛中心最上方）生成新 WandscapeNpc，
 * 恢复身份/外观/属性/装备/背包。入口已迁移为**祭坛唯一**（AltarCastExecutor 调用
 * {@link #spawnFromRecordAt}）；shift+右键直接施放已移除（MagicInteractHandler 删除）。
 *
 * <p>全灭保底：当小镇所有 NPC 均已阵亡时，全灭保底自动在市政厅门口释放复活魔法复活离世成员。
 * 保卫殖民地复活：法师战死若距本殖民地建筑 ≤ {@link Config#REVIVE_NEAR_BUILDING_RANGE} 格，同样直接在市政厅门口复活。
 * 虚弱复活：生成即 1 血 0 蓝，靠脱战回血与魔力回复缓慢恢复。
 */
public final class ReviveHandler {

    private static final String TAG = "Revive";

    /** revive 魔法 id（magic_spells/revive.json 的 key）。 */
    public static final String REVIVE_MAGIC_ID = "revive";

    private ReviveHandler() {}

    /**
     * 检查并执行小镇全灭自动复活保底。
     * 当某小镇存活 NPC 为 0、但存在死亡记录时，自动在市政厅门口释放复活魔法复活最近死亡的一名 NPC。
     */
    public static boolean checkAndAutoReviveColony(ServerLevel level, UUID colonyId) {
        if (colonyId == null) return false;
        ColonyDeathRegistry deathReg = ColonyDeathRegistry.get(level);
        DeathRecord latestRec = deathReg.latestInColony(colonyId);
        if (latestRec == null) return false;

        // 检查世界上该小镇活着的 NPC 数量
        World world = WandscapeEngine.getWorld();
        if (world != null) {
            for (var entry : EntityComponentBridge.INSTANCE.allNpcs().entrySet()) {
                WandscapeNpc npc = entry.getValue();
                if (npc != null && !npc.isRemoved() && npc.isAlive()) {
                    ColonyMember member = world.get(entry.getKey(), ColonyMember.class);
                    if (member != null && colonyId.equals(member.colonyId())) {
                        return false; // 尚有幸存者，不触发保底
                    }
                }
            }
        }

        // 确认全灭：定位市政厅门口/入口
        BlockPos townHallPos = resolveTownHallDoorOrAnchor(level, colonyId, new BlockPos(latestRec.x(), latestRec.y(), latestRec.z()));
        spawnFromRecordAt(level, latestRec, townHallPos);
        Log.info(TAG, "全灭保底触发：小镇 {} 成员全灭，已自动在市政厅门口 ({}) 释放复活魔法唤醒 {}",
                colonyId.toString().substring(0, 8), townHallPos.toShortString(), latestRec.name());
        return true;
    }

    /**
     * 保卫殖民地复活：法师战死时若距本殖民地任一建筑 AABB ≤ {@link Config#REVIVE_NEAR_BUILDING_RANGE} 格，
     * 立即在市政厅门口复活（复用全灭保底的市政厅门口定位 + 虚弱复活 {@link #spawnFromRecordAt}），无需祭坛仪式。
     * 阵亡点距建筑 ≤ range 时尝试复活并返回 true；生成失败时记录保留，可由祭坛/全灭保底重试。
     */
    public static boolean checkAndReviveNearColonyBuilding(ServerLevel level, DeathRecord rec) {
        int range = com.wsteam.wandscape.foundation.util.BalanceValues.reviveNearBuildingRange();
        if (!isWithinRangeOfColonyBuilding(level, rec.colonyId(), rec.x(), rec.y(), rec.z(), range)) {
            return false;
        }
        BlockPos deathPos = new BlockPos(rec.x(), rec.y(), rec.z());
        BlockPos townHallPos = resolveTownHallDoorOrAnchor(level, rec.colonyId(), deathPos);
        spawnFromRecordAt(level, rec, townHallPos);
        Log.info(TAG, "保卫殖民地复活：法师 {} 阵亡于距建筑 ≤{} 格处，已在市政厅门口 ({}) 复活",
                rec.name(), range, townHallPos.toShortString());
        return true;
    }

    /** 阵亡位置距本殖民地任一建筑 AABB 的 3D 距离是否 ≤ range（点在盒内视为 0）。 */
    private static boolean isWithinRangeOfColonyBuilding(ServerLevel level, UUID colonyId,
                                                         int x, int y, int z, int range) {
        BuildingSavedData savedData = BuildingSavedData.get(level);
        if (savedData == null) return false;
        int rangeSq = range * range;
        for (BuildingState b : savedData.getAllBuildings()) {
            if (!colonyId.equals(b.getColonyId())) continue;
            BoundingBox box = b.getBounds();
            if (distSqToAabb(x, y, z,
                    box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()) <= rangeSq) {
                return true;
            }
        }
        return false;
    }

    /** 点到轴对齐盒的 3D 距离平方（点在盒内为 0）。纯逻辑，可单测。 */
    static long distSqToAabb(int x, int y, int z,
                             int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        long dx = Math.max(minX - x, Math.max(0, x - maxX));
        long dy = Math.max(minY - y, Math.max(0, y - maxY));
        long dz = Math.max(minZ - z, Math.max(0, z - maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    /** 定位小镇市政厅门口：category=government 建筑优先用 door_offsets 的可站入口点。 */
    private static BlockPos resolveTownHallDoorOrAnchor(ServerLevel level, UUID colonyId, BlockPos fallback) {
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

    /** 在指定位置生成新 NPC，恢复死亡快照，删除记录。 */
    public static void spawnFromRecordAt(ServerLevel level, DeathRecord rec, BlockPos desiredPos) {
        BlockPos spawnPos = resolveSpawnPos(level, desiredPos);
        WandscapeNpc npc = Wandscape.WANDSCAPE_NPC.get().spawn(level, spawnPos, MobSpawnType.COMMAND);
        if (npc == null) {
            Log.warn(TAG, "复活失败：无法在 {} 生成 NPC（记录保留，可重试）", spawnPos.toShortString());
            return;
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
    }

    /** spawn() 已用默认属性注册 ECS——这里按死亡快照重新设置小镇与背包。 */
    private static void fixEcsAfterSpawn(WandscapeNpc npc, DeathRecord rec) {
        World ecsWorld = WandscapeEngine.getWorld();
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
     *  beam+heal 保留；非空则经 SpellcastingApi 服务端权威校验后全量替换。 */
    private static void restoreEquippedMagic(WandscapeNpc npc, DeathRecord rec) {
        if (rec.equippedMagic().isEmpty()) return;
        SpellcastingApi casting = WandscapeApis.getSpellcastingApiSilently();
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
