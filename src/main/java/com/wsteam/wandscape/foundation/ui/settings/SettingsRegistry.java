package com.wsteam.wandscape.foundation.ui.settings;

import com.wsteam.wandscape.ClientConfig;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.colony.settings.ColonySettings;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.foundation.util.NameStyle;

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
 * <p>标题与单位串走 {@code gui.wandscape.settings.<key>} / {@code gui.wandscape.settings.unit.*}
 * 两组 lang 键，中文原文留作缺键时的兜底（{@link I18n#string}）。本类可能在服务端被
 * {@code ConfigUpdatePacket} 触发初始化，那里取不到语言表，兜底分支保证不会炸。
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

    /** 设置项标题：lang 键由设置项 key 派生，缺键回退中文原文。 */
    private static String title(String key, String fallback) {
        return I18n.string("gui.wandscape.settings." + key, fallback);
    }

    /**
     * 带数字的单位串。lang 值里写 {@code %s 格/秒} 这种（MC 的可翻译串只认 {@code %s}），
     * 数字先在外面格式化好再传进来，兜底分支的 printf 原文也用 {@code %s}，两种路径才一致。
     */
    private static String unit(String suffix, String fallback, String number) {
        return I18n.string("gui.wandscape.settings." + suffix, fallback, number);
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        for (SettingTab tab : SettingTab.values()) {
            ITEMS_BY_TAB.put(tab, new ArrayList<>());
        }

        // ═══════════════════════════════════════════════════════════════
        // Tab 0: 本镇 (SETTLEMENT) —— 随殖民地存档走，人人可改，但服务端只认他自己的小镇
        // ═══════════════════════════════════════════════════════════════

        register(SettingItem.OptionsSetting.colony(
                ColonySettings.KEY_NAMING_STYLE,
                title(ColonySettings.KEY_NAMING_STYLE, "命名风格"),
                SettingTab.SETTLEMENT,
                () -> String.valueOf(WandscapePanelState.getNamingStyle()),
                raw -> WandscapePanelState.setNamingStyle(parseNamingStyle(raw)),
                ColonySettings.defaultRaw(ColonySettings.KEY_NAMING_STYLE),
                namingStyleValues(),
                namingStyleLabels()
        ));

        register(SettingItem.BooleanSetting.colony(
                ColonySettings.KEY_TOURIST_SPAWN,
                title(ColonySettings.KEY_TOURIST_SPAWN, "生成游客"),
                SettingTab.SETTLEMENT,
                WandscapePanelState::isTouristSpawning,
                WandscapePanelState::setTouristSpawning,
                ColonySettings.DEFAULT_TOURIST_SPAWN
        ));

        // ═══════════════════════════════════════════════════════════════
        // Tab 1: 视效控制 (VISUAL)
        // ═══════════════════════════════════════════════════════════════

        register(new SettingItem.DoubleSetting(
                "panel.flySpeed",
                title("panel.flySpeed", "相机飞行速度"),
                SettingTab.VISUAL,
                true, true,
                ClientConfig.FLY_SPEED,
                1.0, 5.0,
                val -> unit("unit.blocks_per_second", "%s 格/秒", String.format("%.1f", val))
        ));

        register(new SettingItem.BooleanSetting(
                "road.showTerrainGrid",
                title("road.showTerrainGrid", "道路网格辅助线"),
                SettingTab.VISUAL,
                true, true,
                ClientConfig.ROAD_GRID
        ));

        register(new SettingItem.OptionsSetting(
                "particle.level",
                title("particle.level", "粒子效果等级"),
                SettingTab.VISUAL,
                false, true,
                Config.PARTICLE_LEVEL,
                List.of("OFF", "LOW", "NORMAL", "HIGH"),
                List.of(
                        I18n.string("gui.wandscape.settings.option.particle_off", "关闭 (OFF)"),
                        I18n.string("gui.wandscape.settings.option.particle_low", "精简 (LOW)"),
                        I18n.string("gui.wandscape.settings.option.particle_normal", "标准 (NORMAL)"),
                        I18n.string("gui.wandscape.settings.option.particle_high", "极致 (HIGH)"))
        ));

        register(new SettingItem.BooleanSetting(
                "ui.speechBubbles",
                title("ui.speechBubbles", "闲聊气泡"),
                SettingTab.VISUAL,
                true, true,
                ClientConfig.SHOW_SPEECH_BUBBLES
        ));

        register(new SettingItem.IntSetting(
                "preview.resolution",
                title("preview.resolution", "建筑预览清晰度"),
                SettingTab.VISUAL,
                true, false,
                ClientConfig.PREVIEW_RESOLUTION,
                16, 32,
                val -> val + " px"
        ));

        register(new SettingItem.IntSetting(
                "preview.fps",
                title("preview.fps", "建筑预览帧率"),
                SettingTab.VISUAL,
                true, false,
                ClientConfig.PREVIEW_FPS,
                2, 4,
                val -> val + " FPS"
        ));

        register(new SettingItem.BooleanSetting(
                "general.debug",
                title("general.debug", "详细调试日志"),
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
        // Tab 2: 城镇经营 (COLONY)
        // ═══════════════════════════════════════════════════════════════

        register(new SettingItem.DoubleSetting(
                "colony.offlineIncomeMultiplier",
                title("colony.offlineIncomeMultiplier", "创始人离线收益系数"),
                SettingTab.COLONY,
                false, true,
                Config.COLONY_OFFLINE_INCOME_MULTIPLIER,
                0.05, 0.20,
                val -> Math.round(val * 100) + "%"
        ));

        register(new SettingItem.IntSetting(
                "warehouse.itemCapacity",
                title("warehouse.itemCapacity", "单座仓库基础容量"),
                SettingTab.COLONY,
                false, true,
                Config.WAREHOUSE_ITEM_CAPACITY,
                5000, 25000,
                val -> unit("unit.items", "%s 件", String.format("%,d", val))
        ));

        register(new SettingItem.BooleanSetting(
                "element.autoGatherOnShortage",
                title("element.autoGatherOnShortage", "元素短缺自动采集"),
                SettingTab.COLONY,
                false, true,
                Config.AUTO_GATHER_ON_ELEMENT_SHORTAGE
        ));

        register(new SettingItem.DoubleSetting(
                "element.decomposeDivisor",
                title("element.decomposeDivisor", "物品分解产出除数"),
                SettingTab.COLONY,
                false, true,
                Config.ELEMENT_DECOMPOSE_DIVISOR,
                0.5, 2.0,
                val -> String.format("1/%.1f", val)
        ));

        register(new SettingItem.DoubleSetting(
                "element.craftCostMultiplier",
                title("element.craftCostMultiplier", "合成制作元素倍率"),
                SettingTab.COLONY,
                false, true,
                Config.ELEMENT_CRAFT_COST_MULTIPLIER,
                0.1, 0.5,
                val -> String.format("%.1f×", val)
        ));

        register(new SettingItem.IntSetting(
                "tavern.recruitCostPerElement",
                title("tavern.recruitCostPerElement", "酒馆法师招募单价"),
                SettingTab.COLONY,
                false, true,
                Config.TAVERN_RECRUIT_COST_PER_ELEMENT,
                1000, 5000,
                val -> unit("unit.elements", "%s 元素", String.format("%,d", val))
        ));

        // 探索宝箱的最终产出倍率。乘在结算的最后一步，所以它同时管「读权重算出来的」和
        // 数据包手写的 reward.value——是管理员侧的全局旋钮，不是区域设定。
        register(new SettingItem.DoubleSetting(
                "exploration.chestExpMultiplier",
                title("exploration.chestExpMultiplier", "探索宝箱经验倍率"),
                SettingTab.COLONY,
                false, true,
                Config.EXPLORATION_CHEST_EXP_MULTIPLIER,
                0.1, 0.5,
                val -> String.format("%.1f×", val)
        ));

        register(new SettingItem.DoubleSetting(
                "exploration.chestElementMultiplier",
                title("exploration.chestElementMultiplier", "探索宝箱元素倍率"),
                SettingTab.COLONY,
                false, true,
                Config.EXPLORATION_CHEST_ELEMENT_MULTIPLIER,
                0.1, 0.5,
                val -> String.format("%.1f×", val)
        ));

        // 游客经济的两个产出阀门。乘在结算最后一步（离线折减之后），与探索宝箱的
        // elementMultiplier 同一口径——建筑 JSON 里的 profit_rate / element_output
        // 仍逐个配，这两项只做全局缩放。
        register(new SettingItem.DoubleSetting(
                "shop.elementMultiplier",
                title("shop.elementMultiplier", "商店元素产出倍率"),
                SettingTab.COLONY,
                false, true,
                Config.SHOP_ELEMENT_MULTIPLIER,
                0.1, 0.5,
                val -> String.format("%.1f×", val)
        ));

        register(new SettingItem.DoubleSetting(
                "service.elementMultiplier",
                title("service.elementMultiplier", "服务设施元素产出倍率"),
                SettingTab.COLONY,
                false, true,
                Config.SERVICE_ELEMENT_MULTIPLIER,
                0.1, 0.5,
                val -> String.format("%.1f×", val)
        ));

        // ═══════════════════════════════════════════════════════════════
        // Tab 3: 游客生态 (TOURIST)
        // ═══════════════════════════════════════════════════════════════

        register(new SettingItem.BooleanSetting(
                "tourist.spawnEnabled",
                title("tourist.spawnEnabled", "全局游客生成开关"),
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_SPAWN_ENABLED
        ));

        register(new SettingItem.IntSetting(
                "tourist.maxPerColony",
                title("tourist.maxPerColony", "单镇游客同时上限"),
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_MAX_PER_COLONY,
                10, 50,
                val -> unit("unit.people", "%s 人", String.valueOf(val))
        ));

        register(new SettingItem.IntSetting(
                "tourist.baseSpawnCount",
                title("tourist.baseSpawnCount", "每日基础新增游客"),
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_BASE_SPAWN_COUNT,
                1, 5,
                val -> unit("unit.people_per_day", "%s 人/日", String.valueOf(val))
        ));

        register(new SettingItem.IntSetting(
                "tourist.stayMinDays",
                title("tourist.stayMinDays", "游客最少停留天数"),
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_STAY_MIN_DAYS,
                1, 2,
                val -> unit("unit.days", "%s 天", String.valueOf(val))
        ));

        register(new SettingItem.IntSetting(
                "tourist.stayMaxDays",
                title("tourist.stayMaxDays", "游客最多停留天数"),
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_STAY_MAX_DAYS,
                1, 2,
                val -> unit("unit.days", "%s 天", String.valueOf(val))
        ));

        register(new SettingItem.IntSetting(
                "tourist.baseWallet",
                title("tourist.baseWallet", "游客初始钱包基数"),
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_BASE_WALLET,
                50, 200,
                val -> unit("unit.elements", "%s 元素", String.format("%,d", val))
        ));

        register(new SettingItem.IntSetting(
                "tourist.maxEnergy",
                title("tourist.maxEnergy", "游客每日精力上限"),
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_MAX_ENERGY,
                10, 50,
                val -> unit("unit.points", "%s 点", String.valueOf(val))
        ));

        // ═══════════════════════════════════════════════════════════════
        // Tab 4: 规则防护 (RULES)
        // ═══════════════════════════════════════════════════════════════

        register(new SettingItem.BooleanSetting(
                "building.noSpawnInBuildingArea",
                title("building.noSpawnInBuildingArea", "建筑区域防刷怪"),
                SettingTab.RULES,
                false, true,
                Config.BUILDING_NO_SPAWN_IN_AREA
        ));

        register(new SettingItem.BooleanSetting(
                "npc.friendlyFireProtection",
                title("npc.friendlyFireProtection", "NPC 友军误伤保护"),
                SettingTab.RULES,
                false, true,
                Config.NPC_FRIENDLY_FIRE_PROTECTION
        ));

        register(new SettingItem.BooleanSetting(
                "npc.deathMessageGlobal",
                title("npc.deathMessageGlobal", "法师阵亡全服广播"),
                SettingTab.RULES,
                false, true,
                Config.NPC_DEATH_MESSAGE_GLOBAL
        ));

        register(new SettingItem.BooleanSetting(
                "npc.pvp",
                title("npc.pvp", "PVP 殖民地阵营识别"),
                SettingTab.RULES,
                false, true,
                Config.PVP
        ));
    }

    /** 起名风格的候选值：枚举 ordinal 的字符串形式，顺序与 {@link NameStyle} 一致。 */
    private static List<String> namingStyleValues() {
        List<String> values = new ArrayList<>();
        for (NameStyle style : NameStyle.values()) {
            values.add(String.valueOf(style.ordinal()));
        }
        return values;
    }

    private static List<String> namingStyleLabels() {
        List<String> labels = new ArrayList<>();
        for (NameStyle style : NameStyle.values()) {
            labels.add(namingStyleLabel(style));
        }
        return labels;
    }

    private static String namingStyleLabel(NameStyle style) {
        return switch (style) {
            case FANTASY -> I18n.string("gui.wandscape.settings.option.naming_fantasy", "西幻");
            case CHINESE -> I18n.string("gui.wandscape.settings.option.naming_chinese", "中文");
            case ENGLISH -> I18n.string("gui.wandscape.settings.option.naming_english", "英文");
        };
    }

    /** 面板缓存里的 raw → ordinal；非法值退回默认，免得手改缓存把枚举越界。 */
    private static int parseNamingStyle(String raw) {
        try {
            int ordinal = Integer.parseInt(raw);
            return (ordinal >= 0 && ordinal < NameStyle.values().length)
                    ? ordinal
                    : ColonySettings.DEFAULT_NAMING_STYLE.ordinal();
        } catch (NumberFormatException e) {
            return ColonySettings.DEFAULT_NAMING_STYLE.ordinal();
        }
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
        // 按「本页能不能恢复默认」判，而不是管理员门控：本镇页非 OP 也要能恢复默认。
        if (!SettingsOverlay.canResetTab(tab)) {
            return;
        }
        if (tab == SettingTab.PACKAGES) {
            Config.setDisabledPackages(List.of());
            if (Config.SPEC.isLoaded()) {
                Config.SPEC.save();
            }
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null && mc.getConnection() != null) {
                    Net.toServer(
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
                    ? I18n.datapackName(rawName, rawName, pkg.names()).getString()
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
