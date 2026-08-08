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

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

/**
 * 复活效果：祭坛施法引导完成后在指定位置（祭坛中心最上方）生成新 WandscapeNpc，
 * 恢复身份/外观/属性/装备/背包。入口已迁移为**祭坛唯一**（AltarCastExecutor 调用
 * {@link #spawnFromRecordAt}）；shift+右键直接施放已移除（MagicInteractHandler 删除）。
 *
 * <p>虚弱复活：生成即 1 血 0 蓝，靠脱战回血与魔力回复缓慢恢复。
 * 失败（生成位置无地可放等）保留死亡记录，玩家可重试。
 */
public final class ReviveHandler {

    private static final String TAG = "Revive";

    /** revive 魔法 id（magic_spells/revive.json 的 key）。 */
    public static final String REVIVE_MAGIC_ID = "revive";

    private static long lastPruneTick = 0;

    private ReviveHandler() {}

    /** ServerTick 驱动：每日清理过期死亡记录（引导/生成已迁至祭坛）。 */
    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now - lastPruneTick >= 24000L) {
            lastPruneTick = now;
            ColonyDeathRegistry.get(level).prune(now);
        }
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

        spawnReviveBurst(level, npc.getX(), npc.getY() + 1.0, npc.getZ());
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

    /** 复活爆点：绿色 glow 爆花（法阵主题色 #4ade80/#86efac），中心大爆花 + 环绕一圈二次爆花。 */
    private static void spawnReviveBurst(ServerLevel level, double x, double y, double z) {
        ParticleService.burstColored(level, new Vec3(x, y, z), 0.29f, 0.87f, 0.50f, 22, 0.16f, 30, false);
        for (int i = 0; i < 6; i++) {
            double a = i / 6.0 * Math.PI * 2;
            ParticleService.burstColored(level,
                    new Vec3(x + Math.cos(a) * 0.9, y + level.random.nextDouble() * 0.6, z + Math.sin(a) * 0.9),
                    0.53f, 0.94f, 0.67f, 7, 0.12f, 24, false);
        }
    }
}
