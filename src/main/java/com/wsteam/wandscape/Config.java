package com.wsteam.wandscape;
import com.wsteam.wandscape.foundation.log.Log;

import net.neoforged.neoforge.common.ModConfigSpec;
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEBUG = BUILDER
            .comment("详细日志：输出 INFO/DEBUG 日志消息。设为 false（默认）时只记录 WARN/ERROR。")
            .comment("Verbose logging: output INFO/DEBUG log messages. When false (default), only WARN/ERROR is logged.")
            .define("general.debug", false);
    // ---- 殖民地自治 Colony Autonomy ----

    public static final ModConfigSpec.BooleanValue AUTO_APPROVE_TASKS = BUILDER
            .comment("设为 true 时，所有殖民地任务跳过玩家审批闸门，自动派发。")
            .comment("When true, all colony tasks skip the player-approval gate and are dispatched automatically.")
            .comment("设为 false 可让大型建造/重建任务在 NPC 动工前先经玩家审阅。")
            .comment("When false, large build/rebuild tasks are reviewed by the player before NPCs start work.")
            .define("general.autoApproveTasks", false);

    public static final ModConfigSpec.DoubleValue COLONY_OFFLINE_INCOME_MULTIPLIER = BUILDER
            .comment("创始人不在线时小镇的离线收益系数（0~1）：商店利润、服务设施元素产出、殖民地经验获取 × 该系数。"
                    + "默认 0.2 = 离线收益降为 20%（1.0 = 离线与在线同收益）。"
                    + "只打折收入侧：物品售价不变（商店按成本+折减利润入账，永不亏损），"
                    + "NPC 建造/商店补货的元素消耗照常 100%。"
                    + "设为 0 = 玩家不在线时其小镇整体冻结（NPC 建造/生产、游客经济、每日结算暂停，上线恢复）。"
                    + "无创始人的小镇视为始终满收益。")
            .comment("Offline income multiplier for a town whose founder is offline (0~1): shop profit, service-element output, and colony XP gain are all multiplied by this factor. "
                    + "Default 0.2 = offline income drops to 20% (1.0 = same income online or offline). "
                    + "Only the income side is discounted: item prices are unchanged (shops book cost + reduced profit and never lose money), "
                    + "while element consumption for NPC building / shop restock stays a full 100%. "
                    + "Set 0 = the town fully freezes while its founder is offline (NPC building / production, tourist economy, and daily settlement pause; resumes on login). "
                    + "A town with no founder is treated as always at full income.")
            .defineInRange("colony.offlineIncomeMultiplier", 0.2, 0.0, 1.0);

    public static final ModConfigSpec.IntValue INITIAL_ELEMENT_COUNT = BUILDER
            .comment("每种元素在小镇仓库首次建立时的初始数量（每小镇一次，只种一次）。")
            .comment("Initial count of each element when a town's warehouse is first established (once per town, planted only once).")
            .defineInRange("colony.initialElementCount", 6000, 0, 2147483647);

    // ---- 元素系统 Element System ----

    public static final ModConfigSpec.DoubleValue ELEMENT_DECOMPOSE_DIVISOR = BUILDER
            .comment("Workstation 分解产出除数：分解物品返回其映射元素值的 1/N。"
                    + "默认 5 = 1/5（原为硬编码 1/10，回收率偏低）。")
            .comment("Workstation decompose output divisor: decomposing an item returns 1/N of its mapped element value. "
                    + "Default 5 = 1/5 (was hard-coded 1/10, which made the recovery rate too low).")
            .defineInRange("element.decomposeDivisor", 5.0, 1.0, 1000000.0);

    public static final ModConfigSpec.DoubleValue ELEMENT_CRAFT_COST_MULTIPLIER = BUILDER
            .comment("合成/制作消耗倍率：Workstation 合成、法杖制作、酿造消耗的元素 × 该系数。"
                    + "默认 1.0；设为 2.0 则消耗翻倍（消耗向上取整，不会少扣）。"
                    + "警告：修改会导致利润率低于该数值的商店不盈利。")
            .comment("Crafting / production cost multiplier: element consumption of Workstation crafting, wand crafting, and brewing × this factor. "
                    + "Default 1.0; set 2.0 to double consumption (rounded up, never under-charged). "
                    + "Warning: changing this can make shops with a profit margin below this value unprofitable.")
            .defineInRange("element.craftCostMultiplier", 1.0, 1.0, 1000000.0);

    public static final ModConfigSpec.BooleanValue AUTO_GATHER_ON_ELEMENT_SHORTAGE = BUILDER
            .comment("元素不足时自动下发采集任务：当生产队列或等待任务因元素不足受阻时，是否自动向对应元素节点下发采集任务（node:gather）。"
                    + "默认 false（关闭）。开启后，系统会自动在对应元素节点排队采集任务以补足元素缺口。")
            .comment("Auto-dispatch gather tasks on element shortage: whether to automatically dispatch gather tasks (node:gather) to element nodes when production queues or waiting tasks are blocked due to missing elements. "
                    + "Default false (disabled). When enabled, the system automatically queues gather tasks at corresponding element nodes to cover element deficits.")
            .define("element.autoGatherOnShortage", false);

    // ---- 仓库容量 Warehouse Capacity ----

    public static final ModConfigSpec.IntValue WAREHOUSE_ITEM_CAPACITY = BUILDER
            .comment("殖民地仓库容量 = 殖民地「仓库」建筑数 × 本值（每座仓库各提供一份容量）："
                    + "物品账本总量（每种物品的计件数之和，不可堆叠物品每件也计 1）不得超过该上限，"
                    + "多建仓库即扩容；没有独立仓库（市政厅临时存取期）按 1 座计。满仓后玩家无法再存入、"
                    + "NPC 合成/制作类生产任务显示\"仓库容量不足\"并等待（商店补货驱动的自动合成为豁免，"
                    + "避免殖民地经济瘫痪）。元素独立存储，不计入容量。"
                    + "设为 0 = 关闭容量机制（不设上限）。")
            .comment("Colony warehouse capacity = number of colony 'warehouse' buildings × this value (each warehouse provides one share of capacity): "
                    + "the total item ledger (sum of per-item piece counts, non-stackable items counting 1 each) may not exceed this cap; "
                    + "build more warehouses to expand it; a town with no standalone warehouse (town hall temporary-storage period) counts as 1. Once full, the player cannot deposit more "
                    + "and NPC crafting / production tasks show \"warehouse capacity is full\" and wait (the auto-crafting driven by shop restock is exempt "
                    + "to avoid paralyzing the colony economy). Elements are stored separately and do not count toward capacity. "
                    + "Set 0 = disable the capacity mechanic (no limit).")
            .defineInRange("warehouse.itemCapacity", 50000, 0, Integer.MAX_VALUE);

    // ---- 游客系统 Tourist System ----

    public static final ModConfigSpec.BooleanValue TOURIST_SPAWN_ENABLED = BUILDER
            .comment("全局游客生成开关：设为 false 时所有殖民地一律不生成游客，"
                    + "无视各市政厅「生成游客」设置（该设置只在全局开关开启时才生效）。"
                    + "默认 true。")
            .comment("Global tourist spawn toggle: when false, no colony spawns tourists at all, "
                    + "ignoring each town hall's 'spawn tourists' setting (that setting only applies while the global toggle is on). "
                    + "Default true.")
            .define("tourist.spawnEnabled", true);

    public static final ModConfigSpec.IntValue TOURIST_MAX_PER_COLONY = BUILDER
            .comment("每个殖民地同时存在的游客上限。")
            .comment("Maximum tourists present in a colony at the same time.")
            .defineInRange("tourist.maxPerColony", 150, 1, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_BASE_SPAWN_COUNT = BUILDER
            .comment("每日新增游客数的下界（殖民地 1 级时）。"
                    + "每日新增数 = 均匀区间 [base+(lv-1)×levelSpawnBonus, base+(lv-1)×levelSpawnBonus+spawnRangeWidth-1]")
            .comment("Lower bound of tourists added per day (at colony level 1). "
                    + "Daily additions = uniform range [base+(lv-1)×levelSpawnBonus, base+(lv-1)×levelSpawnBonus+spawnRangeWidth-1]")
            .defineInRange("tourist.baseSpawnCount", 5, 1, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_LEVEL_SPAWN_BONUS = BUILDER
            .comment("殖民地每升 1 级额外新增的游客数（上下界各 +1）。")
            .comment("Extra tourists added per colony level-up (+1 to both the lower and upper bounds).")
            .defineInRange("tourist.levelSpawnBonus", 1, 0, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_SPAWN_RANGE_WIDTH = BUILDER
            .comment("每日新增数的波动宽度：取值 ∈ [下界, 下界+宽度-1]。"
                    + "默认 3 = 1 级 5~7、2 级 6~8、3 级 7~9")
            .comment("Spread width of the daily additions: value ∈ [lower bound, lower bound+width-1]. "
                    + "Default 3 = at level 1: 5~7, level 2: 6~8, level 3: 7~9")
            .defineInRange("tourist.spawnRangeWidth", 3, 0, 1000000);

    public static final ModConfigSpec.IntValue COLONY_EXP_EQUAL_LEVEL = BUILDER
            .comment("游客等级等于殖民地等级时获得的经验（满条离场时）。"
                    + "700/1400（上调自 250/500）+ 低于小镇等级给一半 + expToNext 二次曲线，"
                    + "标定：5级≈5天、10级≈12天、15级≈22天、20级≈34天、30级满≈68天（sim 保守口径）。")
            .comment("XP gained when a tourist's level equals the colony level (on leaving with full bars). "
                    + "700/1400 (raised from 250/500) + half if below town level + expToNext quadratic curve; "
                    + "calibration: Lv5≈5 days, Lv10≈12, Lv15≈22, Lv20≈34, Lv30 full≈68 (conservative sim estimate).")
            .defineInRange("colony.expEqualLevel", 700, 0, 1000000);

    public static final ModConfigSpec.IntValue COLONY_EXP_ABOVE_LEVEL = BUILDER
            .comment("游客等级高于殖民地等级时获得的经验值。")
            .comment("XP gained when a tourist's level is above the colony level.")
            .defineInRange("colony.expAboveLevel", 1400, 0, 1000000);

    public static final ModConfigSpec.IntValue COLONY_MAX_LEVEL = BUILDER
            .comment("城镇等级上限：达到后不再累积经验、不再升级。")
            .comment("Town level cap: once reached, no more XP accumulates and no more level-ups occur.")
            .defineInRange("colony.maxLevel", 30, 1, 1000000);

    // ---- 服务系统 Service System ----

    public static final ModConfigSpec.IntValue TOURIST_BASE_WALLET = BUILDER
            .comment("1 级游客的初始通元素钱包。"
                    + "钱包 = baseWallet + 等级 × walletPerLevel；旅行基金 = 3×钱包（总消费上限 ≈ 4×钱包）。"
                    + "参考：1 级游客总消费 ≈ 3000，20 级 ≈ 22000（外部 sim 标定，防元素产出泛滥）。"
                    + "参考物价：面包 ~16、蛋糕 ~750、金苹果 ~2684。")
            .comment("Initial element wallet of a level-1 tourist. "
                    + "Wallet = baseWallet + level × walletPerLevel; travel fund = 3× wallet (total spending cap ≈ 4× wallet). "
                    + "Reference: a level-1 tourist spends ≈ 3000 total, level-20 ≈ 22000 (external sim calibration, prevents element flooding). "
                    + "Reference prices: bread ~16, cake ~750, golden apple ~2684.")
            .defineInRange("tourist.baseWallet", 500, 0, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_WALLET_PER_LEVEL = BUILDER
            .comment("游客每升 1 级额外增加的通元素钱包。"
                    + "钱包 = baseWallet + 等级 × walletPerLevel。")
            .comment("Extra element wallet gained per tourist level-up. "
                    + "Wallet = baseWallet + level × walletPerLevel.")
            .defineInRange("tourist.walletPerLevel", 200, 0, 1000000);

    // ── 游客经济改造：三条需求条 / 精力循环 / 停留 / 视野 / ATM（Block 0 新增）──
    // Tourist-economy rework: three need bars / energy loop / stay / vision / ATM (new in Block 0)


    public static final ModConfigSpec.IntValue TOURIST_STAY_MIN_DAYS = BUILDER
            .comment("游客最少停留天数（离境截止下限）。")
            .comment("Minimum days a tourist stays (departure deadline lower bound).")
            .defineInRange("tourist.stayMinDays", 2, 1, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_STAY_MAX_DAYS = BUILDER
            .comment("游客最多停留天数（离境截止上限）。")
            .comment("Maximum days a tourist stays (departure deadline upper bound).")
            .defineInRange("tourist.stayMaxDays", 4, 1, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_NEED_BASE = BUILDER
            .comment("游客总需求基数：totalNeed = BASE + (level-1)×PER_LEVEL，等级越高越难满足。默认 60 = 1 级均衡 20/20/20。")
            .comment("Tourist total-need base: totalNeed = BASE + (level-1)×PER_LEVEL; the higher the level, the harder to satisfy. Default 60 = level-1 balanced 20/20/20.")
            .defineInRange("tourist.needBase", 60, 50, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_NEED_PER_LEVEL = BUILDER
            .comment("游客每级需求增量。默认 10（原 20）——需求增长放缓使高级游客可被喂满，"
                    + "配合经验上调达到 5级≈5天、10级≈10天、20级≈30-40天、30级满≈60-80天。")
            .comment("Tourist need increase per level. Default 10 (was 20): slower need growth lets high-level tourists be fully fed; "
                    + "combined with the XP raise this reaches Lv5≈5 days, Lv10≈10, Lv20≈30-40, Lv30 full≈60-80.")
            .defineInRange("tourist.needPerLevel", 10, 0, 1000000);

    public static final ModConfigSpec.ConfigValue<String> PARTICLE_LEVEL = BUILDER
            .comment("粒子效果等级：OFF 关闭模组全部粒子，LOW 数量减半，NORMAL（默认），HIGH 数量翻倍。")
            .comment("Particle effect level: OFF disables all mod particles, LOW halves the count, NORMAL (default), HIGH doubles the count.")
            .define("particle.level", "NORMAL");

    // ---- 建筑防刷怪区 Building No-Spawn Area ----

    public static final ModConfigSpec.BooleanValue BUILDING_NO_SPAWN_IN_AREA = BUILDER
            .comment("完整的建筑边界盒内不会自然生成敌对生物。")
            .comment("Hostile mobs will not naturally spawn inside a building's complete bounding box.")
            .define("building.noSpawnInBuildingArea", true);

    public static final ModConfigSpec.IntValue TAVERN_RECRUIT_COST_PER_ELEMENT = BUILDER
            .comment("酒馆「招募 NPC」自第二次起每种元素的价格。")
            .comment("Price per element for the Tavern 'recruit NPC' from the second recruit onward.")
            .defineInRange("tavern.recruitCostPerElement", 10000, 0, 2147483647);

    public static final ModConfigSpec.IntValue TOURIST_MAX_ENERGY = BUILDER
            .comment("游客精力上限：初始 100、清晨晨起回满 100，耗尽(=0)只能去 relax 恢复建筑。")
            .comment("Tourist energy cap: starts at 100 and refills to 100 on waking each morning; when depleted (=0) they can only go to a relax building to recover.")
            .defineInRange("tourist.maxEnergy", 100, 1, 1000000);

    // ---- 铁魔法兼容 Iron's Spells Compat ----

    public static final ModConfigSpec.DoubleValue IRON_MANA_COST_MULTIPLIER = BUILDER
            .comment("铁魔法 (Iron's Spells) 魔力消耗倍率：NPC 施铁魔法时扣减的魔力 × 该系数。默认 1.0")
            .comment("Iron's Spells mana cost multiplier: mana deducted when an NPC casts an Iron's spell × this factor. Default 1.0")
            .defineInRange("iron.manaCostMultiplier", 1.0, 0.0, 1000000.0);

    public static final ModConfigSpec.DoubleValue IRON_COOLDOWN_MULTIPLIER = BUILDER
            .comment("铁魔法 (Iron's Spells) 冷却倍率：基础冷却 tick × 该系数（SPELL_SPEED 缩短前）。默认 1.0")
            .comment("Iron's Spells cooldown multiplier: base cooldown ticks × this factor (before SPELL_SPEED reduction). Default 1.0")
            .defineInRange("iron.cooldownMultiplier", 1.0, 0.1, 1000000.0);

    // ---- 诡厄巫法兼容 Goety Compat ----

    public static final ModConfigSpec.DoubleValue GOETY_SOUL_TO_MANA_MULTIPLIER = BUILDER
            .comment("诡厄巫法 (Goety) 灵魂消耗转 NPC 魔力消耗系数。默认 1.0 (1 灵魂 = 1 魔力)")
            .comment("Goety soul cost to NPC mana cost conversion factor. Default 1.0 (1 soul = 1 mana)")
            .defineInRange("goety.soulToManaMultiplier", 1.0, 0.0, 1000000.0);

    public static final ModConfigSpec.DoubleValue GOETY_COOLDOWN_MULTIPLIER = BUILDER
            .comment("诡厄巫法 (Goety) 聚晶法术基础冷却换算系数。默认 1.0 (按原版 tick 换算)")
            .comment("Goety crystal-spell base cooldown conversion factor. Default 1.0 (converted from vanilla ticks)")
            .defineInRange("goety.cooldownMultiplier", 1.0, 0.1, 1000000.0);

    // ---- NPC 死亡消息 NPC Death Message ----

    public static final ModConfigSpec.BooleanValue NPC_DEATH_MESSAGE_GLOBAL = BUILDER
            .comment("法师阵亡消息：设为 true（默认）时，法师阵亡会像玩家死亡那样把死亡消息"
                    + "广播给全服在线玩家；设为 false 时只发送给其所属小镇的创建者玩家"
                    + "（类似驯养宠物死亡只通知主人）。两种模式均受 showDeathMessages 游戏规则门控："
                    + "该规则关闭时一律不显示。")
            .comment("Mage death message: when true (default), a mage death broadcasts the death message "
                    + "to all online players like a player death; when false it is sent only to the founder player of its town "
                    + "(similar to a tamed pet's death notifying only its owner). Both modes are gated by the showDeathMessages game rule: "
                    + "when that rule is off, nothing is shown.")
            .define("npc.deathMessageGlobal", true);


    public static boolean autoGatherOnShortage() {
        return SPEC.isLoaded() && AUTO_GATHER_ON_ELEMENT_SHORTAGE.get();
    }
    // ---- 友军误伤 Friendly Fire ----

    public static final ModConfigSpec.BooleanValue NPC_FRIENDLY_FIRE_PROTECTION = BUILDER
            .comment("误伤保护：true（默认）时，玩家（及玩家侧宠物/召唤/弹射物）不会误伤自己殖民地的 NPC；"
                    + "其他殖民地的 NPC 仍可能被误伤。设为 false 恢复可伤自己殖民地 NPC。")
            .comment("Friendly-fire protection: when true (default), the player (and player-side pets/"
                    + "summons/projectiles) cannot friendly-fire the NPCs of their own colony; other colonies' "
                    + "NPCs can still be hit. Set false to allow damaging your own colony's NPCs.")
            .define("npc.friendlyFireProtection", false);

    // ---- PVP 阵营 PvP Faction ----

    public static final ModConfigSpec.BooleanValue PVP = BUILDER
            .comment("PVP 阵营：true（默认）时，只有属于同一殖民地的玩家（及其宠物/召唤物）才算友军；"
                    + "其他殖民地的玩家、无殖民地玩家以及它们的宠物/召唤物都判非友军——"
                    + "NPC 被打会还手、可用敌对权杖强制标记。false 时（原行为）所有玩家及玩家侧宠物/召唤物恒为友军。"
                    + "注意：殖民地与创始人是 1:1；无创始人（控制台创建）的殖民地在 PVP 下无法识别己方玩家。")
            .comment("PVP faction: when true (default), only players of the same colony (and their pets/summons) "
                    + "count as friendly; players of other colonies and colony-less players (with their pets/summons) "
                    + "are non-friendly — NPCs fight back when attacked and can be marked hostile. "
                    + "When false (original), all players and player-side pets/summons are always friendly. "
                    + "Note: colonies map 1:1 to founders; a colony with no founder (console-created) cannot "
                    + "identify its own players under PVP.")
            .define("npc.pvp", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

}
