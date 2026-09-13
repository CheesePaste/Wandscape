package com.wsteam.wandscape.foundation.ui.settings;

import com.wsteam.wandscape.ClientConfig;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.foundation.log.Log;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Registry of configurable settings available in the V panel Settings Center.
 *
 * <p>本类是「设置项 key → 项」的唯一来源：{@code ConfigUpdatePacket} 也按 key 来这里查，
 * 不再自备一份 switch 名单。新增一项只需在这里 register，两端自动都能改到；
 * 忘了登记只会被 {@link #findByKey} 判为未知路径并告警，不会出现「面板改了、服务端不认」。
 *
 * <p>每项只登记「标题 + tab + 归属 config + 步进」，取值区间由 {@link SettingItem} 从 config 的
 * {@code defineInRange} 自取；卡片不显示介绍文案，说明写在各自 config 项的 comment 里。
 *
 * <p>This class is the single source of truth for "setting key → item": {@code ConfigUpdatePacket}
 * resolves keys through it instead of keeping its own switch list.
 */
public final class SettingsRegistry {

    private static final String TAG = "SettingsRegistry";

    private static final List<SettingItem> ALL_ITEMS = new ArrayList<>();
    private static final Map<SettingTab, List<SettingItem>> ITEMS_BY_TAB = new EnumMap<>(SettingTab.class);
    private static final Map<String, SettingItem> BY_KEY = new HashMap<>();
    private static boolean initialized = false;

    private SettingsRegistry() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        for (SettingTab tab : SettingTab.values()) {
            ITEMS_BY_TAB.put(tab, new ArrayList<>());
        }

        // ═══════════════════════════════════════════════════════════════
        // Tab 0: 视效控制 (VISUAL)
        // ═══════════════════════════════════════════════════════════════

        register(new SettingItem.DoubleSetting(
                "panel.flySpeed",
                "相机飞行速度",
                SettingTab.VISUAL,
                true, true,
                ClientConfig.FLY_SPEED,
                1.0, 5.0,
                val -> String.format("%.1f 格/秒", val)
        ));

        register(new SettingItem.BooleanSetting(
                "road.showTerrainGrid",
                "道路网格辅助线",
                SettingTab.VISUAL,
                true, true,
                ClientConfig.ROAD_GRID
        ));

        register(new SettingItem.OptionsSetting(
                "particle.level",
                "粒子效果等级",
                SettingTab.VISUAL,
                false, true,
                Config.PARTICLE_LEVEL,
                List.of("OFF", "LOW", "NORMAL", "HIGH"),
                List.of("关闭 (OFF)", "精简 (LOW)", "标准 (NORMAL)", "极致 (HIGH)")
        ));

        register(new SettingItem.BooleanSetting(
                "ui.speechBubbles",
                "闲聊气泡",
                SettingTab.VISUAL,
                true, true,
                ClientConfig.SHOW_SPEECH_BUBBLES
        ));

        register(new SettingItem.IntSetting(
                "preview.resolution",
                "建筑预览清晰度",
                SettingTab.VISUAL,
                true, false,
                ClientConfig.PREVIEW_RESOLUTION,
                16, 32,
                val -> val + " px"
        ));

        register(new SettingItem.IntSetting(
                "preview.fps",
                "建筑预览帧率",
                SettingTab.VISUAL,
                true, false,
                ClientConfig.PREVIEW_FPS,
                2, 4,
                val -> val + " FPS"
        ));

        register(new SettingItem.BooleanSetting(
                "general.debug",
                "详细调试日志",
                SettingTab.VISUAL,
                false, true,
                Config.DEBUG
        ) {
            @Override
            public void onApplied() {
                // 本项除了写 config，还要同步日志级别；两端都要跟着变。
                com.wsteam.wandscape.foundation.log.LogConfig.setRootLevel(
                        Config.DEBUG.get()
                                ? com.wsteam.wandscape.foundation.log.LogLevel.DEBUG
                                : com.wsteam.wandscape.foundation.log.LogLevel.INFO);
            }
        });

        // ═══════════════════════════════════════════════════════════════
        // Tab 1: 城镇经营 (COLONY)
        // ═══════════════════════════════════════════════════════════════

        register(new SettingItem.DoubleSetting(
                "colony.offlineIncomeMultiplier",
                "创始人离线收益系数",
                SettingTab.COLONY,
                false, true,
                Config.COLONY_OFFLINE_INCOME_MULTIPLIER,
                0.05, 0.20,
                val -> Math.round(val * 100) + "%"
        ));

        register(new SettingItem.IntSetting(
                "warehouse.itemCapacity",
                "单座仓库基础容量",
                SettingTab.COLONY,
                false, true,
                Config.WAREHOUSE_ITEM_CAPACITY,
                5000, 25000,
                val -> String.format("%,d 件", val)
        ));

        register(new SettingItem.BooleanSetting(
                "element.autoGatherOnShortage",
                "元素短缺自动采集",
                SettingTab.COLONY,
                false, true,
                Config.AUTO_GATHER_ON_ELEMENT_SHORTAGE
        ));

        register(new SettingItem.DoubleSetting(
                "element.decomposeDivisor",
                "物品分解产出除数",
                SettingTab.COLONY,
                false, true,
                Config.ELEMENT_DECOMPOSE_DIVISOR,
                0.5, 2.0,
                val -> String.format("1/%.1f", val)
        ));

        register(new SettingItem.DoubleSetting(
                "element.craftCostMultiplier",
                "合成制作元素倍率",
                SettingTab.COLONY,
                false, true,
                Config.ELEMENT_CRAFT_COST_MULTIPLIER,
                0.1, 0.5,
                val -> String.format("%.1f×", val)
        ));

        register(new SettingItem.IntSetting(
                "tavern.recruitCostPerElement",
                "酒馆法师招募单价",
                SettingTab.COLONY,
                false, true,
                Config.TAVERN_RECRUIT_COST_PER_ELEMENT,
                1000, 5000,
                val -> String.format("%,d 元素", val)
        ));

        // ═══════════════════════════════════════════════════════════════
        // Tab 2: 游客生态 (TOURIST)
        // ═══════════════════════════════════════════════════════════════

        register(new SettingItem.BooleanSetting(
                "tourist.spawnEnabled",
                "全局游客生成开关",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_SPAWN_ENABLED
        ));

        register(new SettingItem.IntSetting(
                "tourist.maxPerColony",
                "单镇游客同时上限",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_MAX_PER_COLONY,
                10, 50,
                val -> val + " 人"
        ));

        register(new SettingItem.IntSetting(
                "tourist.baseSpawnCount",
                "每日基础新增游客",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_BASE_SPAWN_COUNT,
                1, 5,
                val -> val + " 人/日"
        ));

        register(new SettingItem.IntSetting(
                "tourist.stayMinDays",
                "游客最少停留天数",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_STAY_MIN_DAYS,
                1, 2,
                val -> val + " 天"
        ));

        register(new SettingItem.IntSetting(
                "tourist.stayMaxDays",
                "游客最多停留天数",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_STAY_MAX_DAYS,
                1, 2,
                val -> val + " 天"
        ));

        register(new SettingItem.IntSetting(
                "tourist.baseWallet",
                "游客初始钱包基数",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_BASE_WALLET,
                50, 200,
                val -> String.format("%,d 元素", val)
        ));

        register(new SettingItem.IntSetting(
                "tourist.maxEnergy",
                "游客每日精力上限",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_MAX_ENERGY,
                10, 50,
                val -> val + " 点"
        ));

        // ═══════════════════════════════════════════════════════════════
        // Tab 3: 规则防护 (RULES)
        // ═══════════════════════════════════════════════════════════════

        register(new SettingItem.BooleanSetting(
                "building.noSpawnInBuildingArea",
                "建筑区域防刷怪",
                SettingTab.RULES,
                false, true,
                Config.BUILDING_NO_SPAWN_IN_AREA
        ));

        register(new SettingItem.BooleanSetting(
                "npc.friendlyFireProtection",
                "NPC 友军误伤保护",
                SettingTab.RULES,
                false, true,
                Config.NPC_FRIENDLY_FIRE_PROTECTION
        ));

        register(new SettingItem.BooleanSetting(
                "npc.deathMessageGlobal",
                "法师阵亡全服广播",
                SettingTab.RULES,
                false, true,
                Config.NPC_DEATH_MESSAGE_GLOBAL
        ));

        register(new SettingItem.BooleanSetting(
                "npc.pvp",
                "PVP 殖民地阵营识别",
                SettingTab.RULES,
                false, true,
                Config.PVP
        ));
    }

    private static void register(SettingItem item) {
        SettingItem previous = BY_KEY.put(item.key(), item);
        if (previous != null) {
            Log.warn(TAG, "Duplicate setting key '{}' — the later registration wins", item.key());
        }
        ALL_ITEMS.add(item);
        ITEMS_BY_TAB.get(item.tab()).add(item);
    }

    /** 按 key 找已注册的设置项；建筑包那两条是动态项，不在这里（见 ConfigUpdatePacket）。 */
    @Nullable
    public static SettingItem findByKey(String key) {
        init();
        return BY_KEY.get(key);
    }

    public static List<SettingItem> getItems(SettingTab tab) {
        init();
        if (tab == SettingTab.PACKAGES) {
            return getPackageItems();
        }
        return ITEMS_BY_TAB.getOrDefault(tab, List.of());
    }

    public static List<SettingItem> getAllItems() {
        init();
        List<SettingItem> all = new ArrayList<>(ALL_ITEMS);
        all.addAll(getPackageItems());
        return Collections.unmodifiableList(all);
    }

    public static void resetTab(SettingTab tab) {
        init();
        if (tab == SettingTab.PACKAGES) {
            Config.setDisabledPackages(List.of());
            if (Config.SPEC.isLoaded()) {
                Config.SPEC.save();
            }
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null && mc.getConnection() != null) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.wsteam.wandscape.foundation.ui.settings.network.ConfigUpdatePacket("building.disabledPackages", ""));
                }
            } catch (Throwable ignored) {}
            return;
        }
        for (SettingItem item : getItems(tab)) {
            item.resetToDefault();
        }
    }

    private static List<SettingItem> getPackageItems() {
        List<com.wsteam.wandscape.content.building.data.BuildingPackage> packages =
                com.wsteam.wandscape.content.building.projection.client.ProjectionClientState.getBuildingPackages();
        List<SettingItem> items = new ArrayList<>();
        for (com.wsteam.wandscape.content.building.data.BuildingPackage pkg : packages) {
            String pkgId = pkg.id();
            String rawName = pkg.name();
            String title = (rawName != null && !rawName.isEmpty())
                    ? com.wsteam.wandscape.foundation.ui.I18n.string(rawName, rawName)
                    : pkgId;
            items.add(new SettingItem.BooleanSetting(
                    "building.package." + pkgId,
                    title,
                    SettingTab.PACKAGES,
                    false,
                    true,
                    () -> Config.isPackageEnabled(pkgId),
                    enabled -> Config.setPackageEnabled(pkgId, enabled),
                    true
            ));
        }
        return items;
    }
}
