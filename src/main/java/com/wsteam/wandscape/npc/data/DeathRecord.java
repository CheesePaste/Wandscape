package com.wsteam.wandscape.npc.data;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.types.ResourceStack;

/**
 * 死亡留存记录：NPC 战死瞬间的快照，供复活魔法恢复身份/外观/属性/背包/已装备魔法。
 * 纯数据 record（零 MC 依赖），NBT 序列化在 {@code ColonyDeathRegistry}。
 * 复活生成的是新实体（新 UUID），原 {@code npcId} 仅作存档标识。
 */
public record DeathRecord(
        UUID npcId,
        String name,
        String dimension,
        int x, int y, int z,
        long deathTime,
        UUID colonyId,
        int skinVariant,
        int hatColor,
        boolean hasDefaultWand,
        float maxHp, float moveSpeed, float spellPower, float workSpeed,
        float spellSpeed, float armorValue, float maxMana,
        List<ResourceStack> inventory,
        List<String> equippedMagic
) {

    public DeathRecord {
        inventory = List.copyOf(inventory);
        equippedMagic = equippedMagic == null ? List.of() : List.copyOf(equippedMagic);
    }

    /** 范围内最近的死亡记录（3D 距离，含 Y）；无则 null。纯逻辑，可单测。 */
    @Nullable
    public static DeathRecord nearest(List<DeathRecord> records, int x, int y, int z, double maxRange) {
        double bestSq = maxRange * maxRange;
        DeathRecord best = null;
        for (DeathRecord r : records) {
            double dx = r.x - x;
            double dy = r.y - y;
            double dz = r.z - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestSq) {
                bestSq = d;
                best = r;
            }
        }
        return best;
    }

    /** 某小镇最近死去的记录（deathTime 最大，不限位置）；colonyId 为 null 时不限小镇；空表返回 null。纯逻辑，可单测。 */
    @Nullable
    public static DeathRecord latestInColony(List<DeathRecord> records, @Nullable UUID colonyId) {
        DeathRecord best = null;
        for (DeathRecord r : records) {
            if (colonyId != null && !colonyId.equals(r.colonyId())) continue;
            if (best == null || r.deathTime() > best.deathTime()) {
                best = r;
            }
        }
        return best;
    }
}
