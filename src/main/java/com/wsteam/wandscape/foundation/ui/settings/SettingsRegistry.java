package com.wsteam.wandscape.foundation.ui.settings;

import com.wsteam.wandscape.ClientConfig;
import com.wsteam.wandscape.Config;

import java.util.*;

/**
 * Registry of configurable settings available in the V panel Settings Center.
 */
public final class SettingsRegistry {

    private static final List<SettingItem> ALL_ITEMS = new ArrayList<>();
    private static final Map<SettingTab, List<SettingItem>> ITEMS_BY_TAB = new EnumMap<>(SettingTab.class);
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
                "V 面板相机飞行速度（格/秒）：鸟瞰、道路与建造子模式共用。",
                SettingTab.VISUAL,
                true, true,
                ClientConfig.FLY_SPEED,
                1.0, 200.0, 1.0, 5.0,
                val -> String.format("%.1f 格/秒", val)
        ));

        register(new SettingItem.BooleanSetting(
                "road.showTerrainGrid",
                "道路网格辅助线",
                "道路放置与样条编辑模式下，相机周围地面是否显示半透明 1×1 方块网格辅助线。",
                SettingTab.VISUAL,
                true, true,
                ClientConfig.ROAD_GRID
        ));

        register(new SettingItem.OptionsSetting(
                "particle.level",
                "粒子效果等级",
                "模组法术与特效粒子浓度：OFF 关闭全部，LOW 数量减半，NORMAL 默认，HIGH 数量翻倍。",
                SettingTab.VISUAL,
                false, true,
                Config.PARTICLE_LEVEL,
                List.of("OFF", "LOW", "NORMAL", "HIGH"),
                List.of("关闭 (OFF)", "精简 (LOW)", "标准 (NORMAL)", "极致 (HIGH)")
        ));

        register(new SettingItem.BooleanSetting(
                "ui.speechBubbles",
                "闲聊气泡",
                "法师与游客头顶的随机闲聊气泡。关闭后不影响消费/服务反馈的事件气泡与头顶名牌。",
                SettingTab.VISUAL,
                true, true,
                ClientConfig.SHOW_SPEECH_BUBBLES
        ));

        register(new SettingItem.IntSetting(
                "preview.resolution",
                "建筑预览清晰度",
                "建筑预览 GIF 烘焙分辨率（像素/边）：越高越清晰但占更多显存。（重启游戏或重烘焙生效）",
                SettingTab.VISUAL,
                true, false,
                ClientConfig.PREVIEW_RESOLUTION,
                48, 256, 16, 32,
                val -> val + " px"
        ));

        register(new SettingItem.IntSetting(
                "preview.fps",
                "建筑预览帧率",
                "建筑预览 GIF 播放帧率（FPS）：越高旋转越顺滑，但烘焙占用显存更多。（重启生效）",
                SettingTab.VISUAL,
                true, false,
                ClientConfig.PREVIEW_FPS,
                4, 60, 2, 4,
                val -> val + " FPS"
        ));

        register(new SettingItem.BooleanSetting(
                "general.debug",
                "详细调试日志",
                "输出 INFO/DEBUG 日志消息。日常游玩建议保持关闭，遇到异常时开启排查。",
                SettingTab.VISUAL,
                false, true,
                Config.DEBUG
        ));

        // ═══════════════════════════════════════════════════════════════
        // Tab 1: 城镇经营 (COLONY)
        // ═══════════════════════════════════════════════════════════════

        register(new SettingItem.DoubleSetting(
                "colony.offlineIncomeMultiplier",
                "创始人离线收益系数",
                "创始人不在线时小镇收益比例：商店利润、服务产出与经验获取 × 该系数。0% 为彻底冻结。",
                SettingTab.COLONY,
                false, true,
                Config.COLONY_OFFLINE_INCOME_MULTIPLIER,
                0.0, 1.0, 0.05, 0.20,
                val -> Math.round(val * 100) + "%"
        ));

        register(new SettingItem.IntSetting(
                "warehouse.itemCapacity",
                "单座仓库基础容量",
                "每座殖民地仓库提供的物品容量上限（件）。多建仓库即可成倍扩容，设为 0 表示无上限。",
                SettingTab.COLONY,
                false, true,
                Config.WAREHOUSE_ITEM_CAPACITY,
                0, 200000, 5000, 25000,
                val -> val == 0 ? "无上限" : String.format("%,d 件", val)
        ));

        register(new SettingItem.BooleanSetting(
                "element.autoGatherOnShortage",
                "元素短缺自动采集",
                "当生产队列或等待任务因元素不足受阻时，是否自动向对应元素节点下发采集任务。",
                SettingTab.COLONY,
                false, true,
                Config.AUTO_GATHER_ON_ELEMENT_SHORTAGE
        ));

        register(new SettingItem.DoubleSetting(
                "element.decomposeDivisor",
                "物品分解产出除数",
                "Workstation 分解物品产出除数：分解物品返回其映射元素值的 1/N（默认 5.0 即返回 1/5）。",
                SettingTab.COLONY,
                false, true,
                Config.ELEMENT_DECOMPOSE_DIVISOR,
                1.0, 20.0, 0.5, 2.0,
                val -> String.format("1/%.1f", val)
        ));

        register(new SettingItem.DoubleSetting(
                "element.craftCostMultiplier",
                "合成制作元素倍率",
                "Workstation 合成、法杖制作及酿造消耗元素的倍率（默认 1.0，设为 2.0 消耗翻倍）。",
                SettingTab.COLONY,
                false, true,
                Config.ELEMENT_CRAFT_COST_MULTIPLIER,
                1.0, 10.0, 0.1, 0.5,
                val -> String.format("%.1f×", val)
        ));

        register(new SettingItem.IntSetting(
                "tavern.recruitCostPerElement",
                "酒馆法师招募单价",
                "酒馆自第二次招募起，招募 NPC 法师每种元素的花费价格（默认 10,000）。",
                SettingTab.COLONY,
                false, true,
                Config.TAVERN_RECRUIT_COST_PER_ELEMENT,
                0, 100000, 1000, 5000,
                val -> String.format("%,d 元素", val)
        ));

        // ═══════════════════════════════════════════════════════════════
        // Tab 2: 游客生态 (TOURIST)
        // ═══════════════════════════════════════════════════════════════

        register(new SettingItem.BooleanSetting(
                "tourist.spawnEnabled",
                "全局游客生成开关",
                "全局游客生成总闸：设为关闭时所有殖民地一律不生成游客。",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_SPAWN_ENABLED
        ));

        register(new SettingItem.IntSetting(
                "tourist.maxPerColony",
                "单镇游客同时上限",
                "每个殖民地城镇同时存在的游客最大数量（防止游客过多卡顿）。",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_MAX_PER_COLONY,
                10, 500, 10, 50,
                val -> val + " 人"
        ));

        register(new SettingItem.IntSetting(
                "tourist.baseSpawnCount",
                "每日基础新增游客",
                "殖民地 1 级时每日清晨新增游客数的基准下界（随城镇等级逐步递增）。",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_BASE_SPAWN_COUNT,
                1, 50, 1, 5,
                val -> val + " 人/日"
        ));

        register(new SettingItem.IntSetting(
                "tourist.stayMinDays",
                "游客最少停留天数",
                "游客入城后离境截止天数下限（默认 2 天）。",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_STAY_MIN_DAYS,
                1, 14, 1, 2,
                val -> val + " 天"
        ));

        register(new SettingItem.IntSetting(
                "tourist.stayMaxDays",
                "游客最多停留天数",
                "游客入城后离境截止天数上限（默认 4 天）。",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_STAY_MAX_DAYS,
                1, 30, 1, 2,
                val -> val + " 天"
        ));

        register(new SettingItem.IntSetting(
                "tourist.baseWallet",
                "游客初始钱包基数",
                "1 级游客随身携带的通元素钱包基础金额（用于在小镇各商店消费）。",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_BASE_WALLET,
                50, 5000, 50, 200,
                val -> String.format("%,d 元素", val)
        ));

        register(new SettingItem.IntSetting(
                "tourist.maxEnergy",
                "游客每日精力上限",
                "游客活动精力池总量（清晨回满，归零后只前往休闲建筑恢复）。",
                SettingTab.TOURIST,
                false, true,
                Config.TOURIST_MAX_ENERGY,
                20, 500, 10, 50,
                val -> val + " 点"
        ));

        // ═══════════════════════════════════════════════════════════════
        // Tab 3: 规则防护 (RULES)
        // ═══════════════════════════════════════════════════════════════

        register(new SettingItem.BooleanSetting(
                "building.noSpawnInBuildingArea",
                "建筑区域防刷怪",
                "完整的建筑边界盒内不会自然生成敌对怪物（保护小镇安全与游客）。",
                SettingTab.RULES,
                false, true,
                Config.BUILDING_NO_SPAWN_IN_AREA
        ));

        register(new SettingItem.BooleanSetting(
                "npc.friendlyFireProtection",
                "NPC 友军误伤保护",
                "玩家及其宠物、召唤物与弹射物不会误伤自己殖民地的 NPC 法师。",
                SettingTab.RULES,
                false, true,
                Config.NPC_FRIENDLY_FIRE_PROTECTION
        ));

        register(new SettingItem.BooleanSetting(
                "npc.deathMessageGlobal",
                "法师阵亡全服广播",
                "法师阵亡时是否向全服玩家广播消息。关闭后只向所属城镇的创建者发送私信。",
                SettingTab.RULES,
                false, true,
                Config.NPC_DEATH_MESSAGE_GLOBAL
        ));

        register(new SettingItem.BooleanSetting(
                "npc.pvp",
                "PVP 殖民地阵营识别",
                "开启后只有同一殖民地的玩家才算友军；其他殖民地及无镇玩家判定为非友军，法师受袭会自卫反击。",
                SettingTab.RULES,
                false, true,
                Config.PVP
        ));
    }

    private static void register(SettingItem item) {
        ALL_ITEMS.add(item);
        ITEMS_BY_TAB.get(item.tab()).add(item);
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
            String rawDesc = pkg.description();
            String desc = (rawDesc != null && !rawDesc.isEmpty())
                    ? com.wsteam.wandscape.foundation.ui.I18n.string(rawDesc, rawDesc)
                    : ("建筑包 ID: " + pkgId);
            if (pkg.author() != null && !pkg.author().isEmpty()) {
                desc += " | 作者: " + pkg.author();
            }
            if (pkg.version() != null && !pkg.version().isEmpty()) {
                desc += " | v" + pkg.version();
            }
            items.add(new SettingItem.BooleanSetting(
                    "building.package." + pkgId,
                    title,
                    desc,
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
