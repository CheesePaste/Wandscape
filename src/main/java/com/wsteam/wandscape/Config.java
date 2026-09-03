package com.wsteam.wandscape;
import com.wsteam.wandscape.foundation.log.Log;

import net.neoforged.neoforge.common.ModConfigSpec;
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEBUG = BUILDER
            .comment("详细日志：输出 INFO/DEBUG 日志消息。设为 false（默认）时只记录 WARN/ERROR。")
            .define("general.debug", false);
    // ---- 殖民地自治 ----

    public static final ModConfigSpec.BooleanValue AUTO_APPROVE_TASKS = BUILDER
            .comment("设为 true 时，所有殖民地任务跳过玩家审批闸门，自动派发。")
            .comment("设为 false 可让大型建造/重建任务在 NPC 动工前先经玩家审阅。")
            .define("general.autoApproveTasks", false);

    public static final ModConfigSpec.DoubleValue COLONY_OFFLINE_INCOME_MULTIPLIER = BUILDER
            .comment("创始人不在线时小镇的离线收益系数（0~1）：商店利润、服务设施元素产出、殖民地经验获取 × 该系数。"
                    + "默认 0.2 = 离线收益降为 20%（1.0 = 离线与在线同收益）。"
                    + "只打折收入侧：物品售价不变（商店按成本+折减利润入账，永不亏损），"
                    + "NPC 建造/商店补货的元素消耗照常 100%。"
                    + "设为 0 = 玩家不在线时其小镇整体冻结（NPC 建造/生产、游客经济、每日结算暂停，上线恢复）。"
                    + "无创始人的小镇视为始终满收益。")
            .defineInRange("colony.offlineIncomeMultiplier", 0.2, 0.0, 1.0);

    public static final ModConfigSpec.IntValue INITIAL_ELEMENT_COUNT = BUILDER
            .comment("每种元素在小镇仓库首次建立时的初始数量（每小镇一次，只种一次）。")
            .defineInRange("colony.initialElementCount", 6000, 0, 2147483647);

    // ---- 元素系统 ----

    public static final ModConfigSpec.DoubleValue ELEMENT_DECOMPOSE_DIVISOR = BUILDER
            .comment("Workstation 分解产出除数：分解物品返回其映射元素值的 1/N。"
                    + "默认 5 = 1/5（原为硬编码 1/10，回收率偏低）。")
            .defineInRange("element.decomposeDivisor", 5.0, 1.0, 1000000.0);

    public static final ModConfigSpec.DoubleValue ELEMENT_CRAFT_COST_MULTIPLIER = BUILDER
            .comment("合成/制作消耗倍率：Workstation 合成、法杖制作、酿造消耗的元素 × 该系数。"
                    + "默认 1.0；设为 2.0 则消耗翻倍（消耗向上取整，不会少扣）。"
                    + "警告：修改会导致利润率低于该数值的商店不盈利。")
            .defineInRange("element.craftCostMultiplier", 1.0, 1.0, 1000000.0);

    // ---- 仓库容量 ----

    public static final ModConfigSpec.IntValue WAREHOUSE_ITEM_CAPACITY = BUILDER
            .comment("殖民地仓库的物品容量上限：物品账本总量（每种物品的计件数之和，"
                    + "不可堆叠物品每件也计 1）不得超过该值。满仓后玩家无法再存入、"
                    + "NPC 合成/制作类生产任务显示\"仓库容量不足\"并等待（商店补货驱动的自动合成为豁免，"
                    + "避免殖民地经济瘫痪）。元素独立存储，不计入容量。"
                    + "设为 0 = 不设上限（容量机制关闭）。")
            .defineInRange("warehouse.itemCapacity", 50000, 0, Integer.MAX_VALUE);

    // ---- 游客系统 ----

    public static final ModConfigSpec.IntValue TOURIST_MAX_PER_COLONY = BUILDER
            .comment("每个殖民地同时存在的游客上限")
            .defineInRange("tourist.maxPerColony", 150, 1, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_BASE_SPAWN_COUNT = BUILDER
            .comment("每日新增游客数的下界（殖民地 1 级时）。"
                    + "每日新增数 = 均匀区间 [base+(lv-1)×levelSpawnBonus, base+(lv-1)×levelSpawnBonus+spawnRangeWidth-1]")
            .defineInRange("tourist.baseSpawnCount", 5, 1, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_LEVEL_SPAWN_BONUS = BUILDER
            .comment("殖民地每升 1 级额外新增的游客数（上下界各 +1）")
            .defineInRange("tourist.levelSpawnBonus", 1, 0, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_SPAWN_RANGE_WIDTH = BUILDER
            .comment("每日新增数的波动宽度：取值 ∈ [下界, 下界+宽度-1]。"
                    + "默认 3 = 1 级 5~7、2 级 6~8、3 级 7~9")
            .defineInRange("tourist.spawnRangeWidth", 3, 0, 1000000);

    public static final ModConfigSpec.IntValue COLONY_EXP_EQUAL_LEVEL = BUILDER
            .comment("游客等级等于殖民地等级时获得的经验（满条离场时）。"
                    + "700/1400（上调自 250/500）+ 低于小镇等级给一半 + expToNext 二次曲线，"
                    + "标定：5级≈5天、10级≈12天、15级≈22天、20级≈34天、30级满≈68天（sim 保守口径）。")
            .defineInRange("colony.expEqualLevel", 700, 0, 1000000);

    public static final ModConfigSpec.IntValue COLONY_EXP_ABOVE_LEVEL = BUILDER
            .comment("游客等级高于殖民地等级时获得的经验值")
            .defineInRange("colony.expAboveLevel", 1400, 0, 1000000);

    public static final ModConfigSpec.IntValue COLONY_MAX_LEVEL = BUILDER
            .comment("城镇等级上限：达到后不再累积经验、不再升级")
            .defineInRange("colony.maxLevel", 30, 1, 1000000);

    // ---- 服务系统 ----

    public static final ModConfigSpec.IntValue TOURIST_BASE_WALLET = BUILDER
            .comment("1 级游客的初始通元素钱包。"
                    + "钱包 = baseWallet + 等级 × walletPerLevel；旅行基金 = 3×钱包（总消费上限 ≈ 4×钱包）。"
                    + "参考：1 级游客总消费 ≈ 3000，20 级 ≈ 22000（外部 sim 标定，防元素产出泛滥）。"
                    + "参考物价：面包 ~16、蛋糕 ~750、金苹果 ~2684。")
            .defineInRange("tourist.baseWallet", 500, 0, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_WALLET_PER_LEVEL = BUILDER
            .comment("游客每升 1 级额外增加的通元素钱包。"
                    + "钱包 = baseWallet + 等级 × walletPerLevel。")
            .defineInRange("tourist.walletPerLevel", 200, 0, 1000000);

    // ── 游客经济改造：三条需求条 / 精力循环 / 停留 / 视野 / ATM（Block 0 新增）──


    public static final ModConfigSpec.IntValue TOURIST_STAY_MIN_DAYS = BUILDER
            .comment("游客最少停留天数（离境截止下限）。")
            .defineInRange("tourist.stayMinDays", 2, 1, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_STAY_MAX_DAYS = BUILDER
            .comment("游客最多停留天数（离境截止上限）。")
            .defineInRange("tourist.stayMaxDays", 4, 1, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_NEED_BASE = BUILDER
            .comment("游客总需求基数：totalNeed = BASE + (level-1)×PER_LEVEL，等级越高越难满足。默认 60 = 1 级均衡 20/20/20。")
            .defineInRange("tourist.needBase", 60, 50, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_NEED_PER_LEVEL = BUILDER
            .comment("游客每级需求增量。默认 10（原 20）——需求增长放缓使高级游客可被喂满，"
                    + "配合经验上调达到 5级≈5天、10级≈10天、20级≈30-40天、30级满≈60-80天。")
            .defineInRange("tourist.needPerLevel", 10, 0, 1000000);

    public static final ModConfigSpec.ConfigValue<String> PARTICLE_LEVEL = BUILDER
            .comment("粒子效果等级：OFF 关闭模组全部粒子，LOW 数量减半，NORMAL（默认），HIGH 数量翻倍。")
            .define("particle.level", "NORMAL");

    // ---- 建筑防刷怪区 ----

    public static final ModConfigSpec.BooleanValue BUILDING_NO_SPAWN_IN_AREA = BUILDER
            .comment("完整的建筑边界盒内不会自然生成敌对生物。")
            .define("building.noSpawnInBuildingArea", true);

    public static final ModConfigSpec.IntValue TAVERN_RECRUIT_COST_PER_ELEMENT = BUILDER
            .comment("酒馆「招募 NPC」自第二次起每种元素的价格。")
            .defineInRange("tavern.recruitCostPerElement", 10000, 0, 2147483647);

    public static final ModConfigSpec.IntValue TOURIST_MAX_ENERGY = BUILDER
            .comment("游客精力上限：初始 100、清晨晨起回满 100，耗尽(=0)只能去 relax 恢复建筑。")
            .defineInRange("tourist.maxEnergy", 100, 1, 1000000);

    // ---- 铁魔法兼容 ----

    public static final ModConfigSpec.DoubleValue IRON_MANA_COST_MULTIPLIER = BUILDER
            .comment("铁魔法 (Iron's Spells) 魔力消耗倍率：NPC 施铁魔法时扣减的魔力 × 该系数。默认 1.0")
            .defineInRange("iron.manaCostMultiplier", 1.0, 0.0, 1000000.0);

    public static final ModConfigSpec.DoubleValue IRON_COOLDOWN_MULTIPLIER = BUILDER
            .comment("铁魔法 (Iron's Spells) 冷却倍率：基础冷却 tick × 该系数（SPELL_SPEED 缩短前）。默认 1.0")
            .defineInRange("iron.cooldownMultiplier", 1.0, 0.1, 1000000.0);

    // ---- 诡厄巫法兼容 ----

    public static final ModConfigSpec.DoubleValue GOETY_SOUL_TO_MANA_MULTIPLIER = BUILDER
            .comment("诡厄巫法 (Goety) 灵魂消耗转 NPC 魔力消耗系数。默认 1.0 (1 灵魂 = 1 魔力)")
            .defineInRange("goety.soulToManaMultiplier", 1.0, 0.0, 1000000.0);

    public static final ModConfigSpec.DoubleValue GOETY_COOLDOWN_MULTIPLIER = BUILDER
            .comment("诡厄巫法 (Goety) 聚晶法术基础冷却换算系数。默认 1.0 (按原版 tick 换算)")
            .defineInRange("goety.cooldownMultiplier", 1.0, 0.1, 1000000.0);

    // ---- NPC 死亡消息 ----

    public static final ModConfigSpec.BooleanValue NPC_DEATH_MESSAGE_GLOBAL = BUILDER
            .comment("法师阵亡消息：设为 true（默认）时，法师阵亡会像玩家死亡那样把死亡消息"
                    + "广播给全服在线玩家；设为 false 时只发送给其所属小镇的创建者玩家"
                    + "（类似驯养宠物死亡只通知主人）。两种模式均受 showDeathMessages 游戏规则门控："
                    + "该规则关闭时一律不显示。")
            .define("npc.deathMessageGlobal", true);

    static final ModConfigSpec SPEC = BUILDER.build();
}
