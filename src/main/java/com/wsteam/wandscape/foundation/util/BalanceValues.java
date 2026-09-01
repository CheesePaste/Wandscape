package com.wsteam.wandscape.foundation.util;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 各领域"可调战斗/经济/节奏数值"的单一内部存储（非 API、不暴露给 addon）。
 *
 * <p>每个值 = 默认常量 + {@link ConcurrentHashMap} 覆盖层；{@code getXxx()} 返回覆盖或默认。
 * 逻辑读取走这里；各领域对外 API（{@code NpcApi}/{@code WarehouseApi}/{@code BuildingApi}）的
 * {@code get/set} 委托这里的 getter/setter，保证 addon 覆盖与逻辑读到的是同一处。
 *
 * <p>覆盖为运行时生效（下次读到新值），不追溯已生成实体/进行中任务。想改默认值：要么改本类常量，
 * 要么经对应领域 API {@code setXxx}（整合包/mixin）。想用 mixin 覆盖：mixin 本类或对应 API。
 *
 * <p>纯 Java，零 MC 运行时依赖。
 */
public final class BalanceValues {
    private BalanceValues() {}

    private static final Map<String, Double> OVERRIDES = new ConcurrentHashMap<>();

    /** 可被持久化 JSON（{@code data/wandscape/wandscape_balance.json}）覆盖的全部键。
     *  新增 balance 值须同步补进本 Set，否则该键无法被文件覆盖。 */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "npcRegenGraceTicks", "npcRegenIntervalTicks", "npcManaRegenTicks", "npcManaRegenFraction",
            "guardRange", "guardReleaseRange", "guardSelfDefenseRange", "guardHateRange",
            "guardHateDurationTicks", "guardFollowAttackDurationTicks", "guardKiteStartDist",
            "guardKiteStandoff", "guardEngageStandoff", "guardFleeHpThreshold", "guardFleeStartDist",
            "guardFleeStandoff",
            "reviveNearBuildingRange", "scepterHostileRange",
            "transportTicksPerBlockOnRoad", "transportTicksPerBlockOffRoad", "decorationBonusCap",
            "workstationCraftTicksPerUnit", "craftingStationCraftTicksPerUnit",
            "constructionPlaceTicksPerUnit", "castSingleTargetMaxEnemies", "castAoeMinEnemies",
            "mageHutRestTicks");

    // ============================================================
    // npc 回血回蓝
    // ============================================================
    private static final int DEFAULT_NPC_REGEN_GRACE_TICKS = 100;
    private static final int DEFAULT_NPC_REGEN_INTERVAL_TICKS = 80;
    private static final int DEFAULT_NPC_MANA_REGEN_TICKS = 10;
    private static final double DEFAULT_NPC_MANA_REGEN_FRACTION = 0.01;

    public static int npcRegenGraceTicks() { return (int)(double) OVERRIDES.getOrDefault("npcRegenGraceTicks", (double) DEFAULT_NPC_REGEN_GRACE_TICKS); }
    public static void setNpcRegenGraceTicks(int v) { OVERRIDES.put("npcRegenGraceTicks", (double) v); }
    public static int npcRegenIntervalTicks() { return (int)(double) OVERRIDES.getOrDefault("npcRegenIntervalTicks", (double) DEFAULT_NPC_REGEN_INTERVAL_TICKS); }
    public static void setNpcRegenIntervalTicks(int v) { OVERRIDES.put("npcRegenIntervalTicks", (double) v); }
    public static int npcManaRegenTicks() { return (int)(double) OVERRIDES.getOrDefault("npcManaRegenTicks", (double) DEFAULT_NPC_MANA_REGEN_TICKS); }
    public static void setNpcManaRegenTicks(int v) { OVERRIDES.put("npcManaRegenTicks", (double) v); }
    public static double npcManaRegenFraction() { return OVERRIDES.getOrDefault("npcManaRegenFraction", DEFAULT_NPC_MANA_REGEN_FRACTION); }
    public static void setNpcManaRegenFraction(double v) { OVERRIDES.put("npcManaRegenFraction", v); }

    // ============================================================
    // guard 战斗
    // ============================================================
    private static final int DEFAULT_GUARD_RANGE = 10;
    private static final int DEFAULT_GUARD_RELEASE_RANGE = 15;
    private static final int DEFAULT_GUARD_SELF_DEFENSE_RANGE = 16;
    private static final int DEFAULT_GUARD_HATE_RANGE = 48;
    private static final int DEFAULT_GUARD_HATE_DURATION_TICKS = 600;
    private static final int DEFAULT_GUARD_FOLLOW_ATTACK_DURATION_TICKS = 300;
    private static final double DEFAULT_GUARD_KITE_START_DIST = 9.0;
    private static final double DEFAULT_GUARD_KITE_STANDOFF = 13.0;
    private static final double DEFAULT_GUARD_ENGAGE_STANDOFF = 9.0;
    private static final double DEFAULT_GUARD_FLEE_HP_THRESHOLD = 0.30;
    private static final double DEFAULT_GUARD_FLEE_START_DIST = 12.0;
    private static final double DEFAULT_GUARD_FLEE_STANDOFF = 18.0;

    public static int guardRange() { return (int)(double) OVERRIDES.getOrDefault("guardRange", (double) DEFAULT_GUARD_RANGE); }
    public static void setGuardRange(int v) { OVERRIDES.put("guardRange", (double) v); }
    public static int guardReleaseRange() { return (int)(double) OVERRIDES.getOrDefault("guardReleaseRange", (double) DEFAULT_GUARD_RELEASE_RANGE); }
    public static void setGuardReleaseRange(int v) { OVERRIDES.put("guardReleaseRange", (double) v); }
    public static int guardSelfDefenseRange() { return (int)(double) OVERRIDES.getOrDefault("guardSelfDefenseRange", (double) DEFAULT_GUARD_SELF_DEFENSE_RANGE); }
    public static void setGuardSelfDefenseRange(int v) { OVERRIDES.put("guardSelfDefenseRange", (double) v); }
    public static int guardHateRange() { return (int)(double) OVERRIDES.getOrDefault("guardHateRange", (double) DEFAULT_GUARD_HATE_RANGE); }
    public static void setGuardHateRange(int v) { OVERRIDES.put("guardHateRange", (double) v); }
    public static int guardHateDurationTicks() { return (int)(double) OVERRIDES.getOrDefault("guardHateDurationTicks", (double) DEFAULT_GUARD_HATE_DURATION_TICKS); }
    public static void setGuardHateDurationTicks(int v) { OVERRIDES.put("guardHateDurationTicks", (double) v); }
    public static int guardFollowAttackDurationTicks() { return (int)(double) OVERRIDES.getOrDefault("guardFollowAttackDurationTicks", (double) DEFAULT_GUARD_FOLLOW_ATTACK_DURATION_TICKS); }
    public static void setGuardFollowAttackDurationTicks(int v) { OVERRIDES.put("guardFollowAttackDurationTicks", (double) v); }
    public static double guardKiteStartDist() { return OVERRIDES.getOrDefault("guardKiteStartDist", DEFAULT_GUARD_KITE_START_DIST); }
    public static void setGuardKiteStartDist(double v) { OVERRIDES.put("guardKiteStartDist", v); }
    public static double guardKiteStandoff() { return OVERRIDES.getOrDefault("guardKiteStandoff", DEFAULT_GUARD_KITE_STANDOFF); }
    public static void setGuardKiteStandoff(double v) { OVERRIDES.put("guardKiteStandoff", v); }
    public static double guardEngageStandoff() { return OVERRIDES.getOrDefault("guardEngageStandoff", DEFAULT_GUARD_ENGAGE_STANDOFF); }
    public static void setGuardEngageStandoff(double v) { OVERRIDES.put("guardEngageStandoff", v); }
    public static double guardFleeHpThreshold() { return OVERRIDES.getOrDefault("guardFleeHpThreshold", DEFAULT_GUARD_FLEE_HP_THRESHOLD); }
    public static void setGuardFleeHpThreshold(double v) { OVERRIDES.put("guardFleeHpThreshold", v); }
    public static double guardFleeStartDist() { return OVERRIDES.getOrDefault("guardFleeStartDist", DEFAULT_GUARD_FLEE_START_DIST); }
    public static void setGuardFleeStartDist(double v) { OVERRIDES.put("guardFleeStartDist", v); }
    public static double guardFleeStandoff() { return OVERRIDES.getOrDefault("guardFleeStandoff", DEFAULT_GUARD_FLEE_STANDOFF); }
    public static void setGuardFleeStandoff(double v) { OVERRIDES.put("guardFleeStandoff", v); }

    // ============================================================
    // revive / scepter
    // ============================================================
    private static final int DEFAULT_REVIVE_NEAR_BUILDING_RANGE = 20;
    private static final double DEFAULT_SCEPTER_HOSTILE_RANGE = 128.0;

    public static int reviveNearBuildingRange() { return (int)(double) OVERRIDES.getOrDefault("reviveNearBuildingRange", (double) DEFAULT_REVIVE_NEAR_BUILDING_RANGE); }
    public static void setReviveNearBuildingRange(int v) { OVERRIDES.put("reviveNearBuildingRange", (double) v); }
    public static double scepterHostileRange() { return OVERRIDES.getOrDefault("scepterHostileRange", DEFAULT_SCEPTER_HOSTILE_RANGE); }
    public static void setScepterHostileRange(double v) { OVERRIDES.put("scepterHostileRange", v); }

    // ============================================================
    // transport / decoration
    // ============================================================
    private static final int DEFAULT_TRANSPORT_TICKS_PER_BLOCK_ON_ROAD = 2;
    private static final int DEFAULT_TRANSPORT_TICKS_PER_BLOCK_OFF_ROAD = 4;
    private static final double DEFAULT_DECORATION_BONUS_CAP = 1.0;

    public static int transportTicksPerBlockOnRoad() { return (int)(double) OVERRIDES.getOrDefault("transportTicksPerBlockOnRoad", (double) DEFAULT_TRANSPORT_TICKS_PER_BLOCK_ON_ROAD); }
    public static void setTransportTicksPerBlockOnRoad(int v) { OVERRIDES.put("transportTicksPerBlockOnRoad", (double) v); }
    public static int transportTicksPerBlockOffRoad() { return (int)(double) OVERRIDES.getOrDefault("transportTicksPerBlockOffRoad", (double) DEFAULT_TRANSPORT_TICKS_PER_BLOCK_OFF_ROAD); }
    public static void setTransportTicksPerBlockOffRoad(int v) { OVERRIDES.put("transportTicksPerBlockOffRoad", (double) v); }
    public static double decorationBonusCap() { return OVERRIDES.getOrDefault("decorationBonusCap", DEFAULT_DECORATION_BONUS_CAP); }
    public static void setDecorationBonusCap(double v) { OVERRIDES.put("decorationBonusCap", v); }

    // ============================================================
    // craft / construction / cast / mage
    // ============================================================
    private static final int DEFAULT_WORKSTATION_CRAFT_TICKS_PER_UNIT = 5;
    private static final int DEFAULT_CRAFTING_STATION_CRAFT_TICKS_PER_UNIT = 1200;
    private static final int DEFAULT_CONSTRUCTION_PLACE_TICKS_PER_UNIT = 1;
    private static final int DEFAULT_CAST_SINGLE_TARGET_MAX_ENEMIES = 3;
    private static final int DEFAULT_CAST_AOE_MIN_ENEMIES = 3;
    private static final int DEFAULT_MAGE_HUT_REST_TICKS = 2400;

    public static int workstationCraftTicksPerUnit() { return (int)(double) OVERRIDES.getOrDefault("workstationCraftTicksPerUnit", (double) DEFAULT_WORKSTATION_CRAFT_TICKS_PER_UNIT); }
    public static void setWorkstationCraftTicksPerUnit(int v) { OVERRIDES.put("workstationCraftTicksPerUnit", (double) v); }
    public static int craftingStationCraftTicksPerUnit() { return (int)(double) OVERRIDES.getOrDefault("craftingStationCraftTicksPerUnit", (double) DEFAULT_CRAFTING_STATION_CRAFT_TICKS_PER_UNIT); }
    public static void setCraftingStationCraftTicksPerUnit(int v) { OVERRIDES.put("craftingStationCraftTicksPerUnit", (double) v); }
    public static int constructionPlaceTicksPerUnit() { return (int)(double) OVERRIDES.getOrDefault("constructionPlaceTicksPerUnit", (double) DEFAULT_CONSTRUCTION_PLACE_TICKS_PER_UNIT); }
    public static void setConstructionPlaceTicksPerUnit(int v) { OVERRIDES.put("constructionPlaceTicksPerUnit", (double) v); }
    public static int castSingleTargetMaxEnemies() { return (int)(double) OVERRIDES.getOrDefault("castSingleTargetMaxEnemies", (double) DEFAULT_CAST_SINGLE_TARGET_MAX_ENEMIES); }
    public static void setCastSingleTargetMaxEnemies(int v) { OVERRIDES.put("castSingleTargetMaxEnemies", (double) v); }
    public static int castAoeMinEnemies() { return (int)(double) OVERRIDES.getOrDefault("castAoeMinEnemies", (double) DEFAULT_CAST_AOE_MIN_ENEMIES); }
    public static void setCastAoeMinEnemies(int v) { OVERRIDES.put("castAoeMinEnemies", (double) v); }
    public static int mageHutRestTicks() { return (int)(double) OVERRIDES.getOrDefault("mageHutRestTicks", (double) DEFAULT_MAGE_HUT_REST_TICKS); }
    public static void setMageHutRestTicks(int v) { OVERRIDES.put("mageHutRestTicks", (double) v); }

    // ============================================================
    // 持久化 JSON 覆盖（data/wandscape/wandscape_balance.json）驱动
    // ============================================================

    /** 按键写覆盖层：仅接受 {@link #KNOWN_KEYS} 中的键，未知返回 false（调用方记 warn）。
     *  供 datapack loader 使用；纯 Java，零 MC 依赖。 */
    public static boolean apply(String key, double value) {
        if (!KNOWN_KEYS.contains(key)) return false;
        OVERRIDES.put(key, value);
        return true;
    }

    /** 清空覆盖层、回到全部默认常量。reload 时让 JSON 文件成为唯一持久源（确定性）。 */
    public static void reset() {
        OVERRIDES.clear();
    }
}
