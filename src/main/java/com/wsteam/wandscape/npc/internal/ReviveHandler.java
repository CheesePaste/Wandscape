package com.wsteam.wandscape.npc.internal;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.EquipmentComponent;
import com.wsteam.wandscape.core.component.Inventory;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.service.ParticleService;
import com.wsteam.wandscape.npc.data.DeathRecord;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

/**
 * 复活效果：祭坛施法引导完成后在指定位置（祭坛中心最上方）生成新 WandscapeNpc，
 * 恢复身份/外观/属性/装备/背包。入口已迁移为**祭坛唯一**（AltarCastExecutor 调用
 * {@link #spawnFromRecordAt}）；shift+右键直接施放已移除（MagicInteractHandler 删除）。
 *
 * <p>全灭保底：当殖民地所有 NPC 均已阵亡时，全灭保底自动在市政厅门口释放复活魔法复活离世成员。
 * 虚弱复活：生成即 1 血 0 蓝，靠脱战回血与魔力回复缓慢恢复。
 */
public final class ReviveHandler {

    private static final String TAG = "Revive";

    /** revive 魔法 id（magic_spells/revive.json 的 key）。 */
    public static final String REVIVE_MAGIC_ID = "revive";

    private ReviveHandler() {}

    /**
     * 检查并执行殖民地全灭自动复活保底。
     * 当某殖民地存活 NPC 为 0、但存在死亡记录时，自动在市政厅门口释放复活魔法复活最近死亡的一名 NPC。
     */
    public static boolean checkAndAutoReviveColony(ServerLevel level, UUID colonyId) {
        if (colonyId == null) return false;
        ColonyDeathRegistry deathReg = ColonyDeathRegistry.get(level);
        DeathRecord latestRec = deathReg.latestInColony(colonyId);
        if (latestRec == null) return false;

        // 检查世界上该殖民地活着的 NPC 数量
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
        Log.info(TAG, "全灭保底触发：殖民地 {} 成员全灭，已自动在市政厅门口 ({}) 释放复活魔法唤醒 {}",
                colonyId.toString().substring(0, 8), townHallPos.toShortString(), latestRec.name());
        return true;
    }

    /** 定位殖民地市政厅门口：category=government 建筑优先用 door_offsets 的可站入口点。 */
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
        npc.maxHp = rec.maxHp();
        npc.moveSpeed = rec.moveSpeed();
        npc.spellPower = rec.spellPower();
        npc.workSpeed = rec.workSpeed();
        npc.spellSpeed = rec.spellSpeed();
        npc.armorValue = rec.armorValue();
        npc.maxMana = rec.maxMana();
        // 虚弱复活：1 血 0 蓝，靠脱战回血（interval 回 1 HP）与魔力回复（10t/1 点）缓慢恢复
        npc.setHealth(1f);
        npc.magic.setMana(0f);
        npc.magic.markManaSeeded(); // 阻止首 tick 的"满蓝种子"逻辑把蓝填满
        npc.setHasDefaultWand(rec.hasDefaultWand());

        fixEcsAfterSpawn(npc, rec);
        ColonyDeathRegistry.get(level).remove(rec);

        spawnReviveBurst(level, spawnPos.getX() + 0.5, spawnPos.getY() + 1.0, spawnPos.getZ() + 0.5);
        Log.info(TAG, "NPC {} ({}) 已复活 at {}（恢复 {} 格背包）",
                npc.getUUID().toString().substring(0, 8), rec.name(),
                spawnPos.toShortString(), rec.inventory().size());
    }

    /** spawn() 已用默认属性注册 ECS——这里按死亡快照重新 seed 属性/殖民地/装备/背包（TavernRecruit 同款修正）。 */
    private static void fixEcsAfterSpawn(WandscapeNpc npc, DeathRecord rec) {
        World ecsWorld = WandscapeEngine.getWorld();
        if (ecsWorld == null) return;
        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(npc.getUUID());
        if (ecsId == null) return;

        EquipmentComponent eq = ecsWorld.get(ecsId, EquipmentComponent.class);
        if (eq != null) {
            eq.seedBaseValues(new NpcAttributes(npc.maxHp, npc.moveSpeed, npc.spellPower,
                    npc.workSpeed, npc.spellSpeed, npc.armorValue, npc.maxMana));
            if (rec.hasDefaultWand()) {
                eq.equipDefaultWand();
            }
        }

        var member = ecsWorld.get(ecsId, ColonyMember.class);
        if (member != null && !rec.colonyId().equals(member.colonyId())) {
            ecsWorld.addComponent(ecsId, new ColonyMember(rec.colonyId()));
        }

        Inventory inv = ecsWorld.get(ecsId, Inventory.class);
        if (inv != null) {
            for (ResourceStack s : rec.inventory()) {
                inv.add(s);
            }
        }
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
