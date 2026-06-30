# 模拟经营系统设计

基于 `jingying.md` 的游客经济与商业系统，为殖民地添加经营维度：游客消费、商店利润、装饰布局、奇观规则、维护成本形成完整闭环。

## 设计目标

- **过剩资源→发展动力**：商店售卖物品产生元素利润，服务建筑从游客交互中获得元素收入。
- **装饰建筑获得策略价值**：范围辐射加成将布局变为有深度的经营决策。
- **奇观提供全局级影响**：不仅是数值加成，更是改变殖民地规则的核心建筑。
- **游客行为自然有节奏**：精力值、兴趣判断、昼夜循环共同驱动访客流动。
- **法师招募有保底机制**：满意法师的信息留存酒馆，玩家不会错过。
- **道路获得经济意义**：游客沿道路入城和移动，道路布局直接影响游客流量和建筑可达性。

## 游客实体

### 为什么不复用 WandscapeNpc

WandscapeNpc 承载 ECS 桥接、法杖、魔力池、任务执行器等完整殖民地 NPC 设施。游客是纯访客——不施法、不接任务、不参与生产。独立实体类避免组件污染，行为逻辑更简洁。

### 属性

| 属性 | 说明 |
|------|------|
| 等级 | 随殖民地三值和上升而提升。等级越高 → 精力值越高 → 满意度提升阈值越高。法师游客满意度100%后招募时的数值越高 |
| 精力值 | 每次交互消耗，耗尽后离开 |
| 满意度 | 0-100%，由交互结果累积 |
| 偏好 | 对建筑类型（buildingTypeId）的偏好权重（5..100，默认 40）。驱动加权建筑选择 + 满意度计算，每次交互后 -15 衰减 |
| 样貌 | 普通市民（95%）或法师（5%）。渲染复用 tourist/ 皮肤 |

### 离开条件（三区段）

| 满意度 | 行为 |
|--------|------|
| ≥ 100 | 法师简历即时存入酒馆。不主动离开，不住宿。精力耗尽/夜间空闲/空闲超时 → 离开 |
| 70–99 | 精力耗尽或夜间 → 寻找宾馆，无空位则离开 |
| < 70 | 精力耗尽 / 夜间空闲 / 空闲超时 → 离开 |

### 满意度100%时的处理

- 游客满意度达到100%后不主动离开，仍需满足离开条件（精力耗尽/夜间/超时）
- 若该游客是法师样貌，满意度首次达到100%时招募信息即时存入酒馆招募列表（最近5位）
- 100%满意度游客不会入住宾馆
- 信息永久保留在酒馆，玩家可随时招募

### 宾馆入住

- 触发条件：满意度在70%-99%，且触发了离开条件
- 行为：不离开，转而寻找殖民地宾馆
- 宾馆有空位 → 入住，第二天精力值回满，满意度保持
- 宾馆无空位 → 离开
- 宾馆本身是服务建筑，入住行为产生满意度和元素收入

## 游客与道路联动

### 为什么游客要走道路

道路此前是纯装饰（与寻路解耦）。引入游客后道路获得经济意义：

- **生成点**：游客在殖民地边界处的道路上生成，而非随机位置
- **移动路径**：游客沿道路移动到目标建筑，不横穿殖民地
- **玩家规划**：道路布局直接影响游客流量和建筑可达性——主干道建筑曝光率高，偏僻建筑游客少

### 实现方式

- TouristSpawnSystem 查询 `RoadSavedData` 获取殖民地边界道路位置
- TouristMoveGoal 使用道路网络进行寻路，优先沿道路移动

## 商店建筑 (category: shop)

### 货物定义

货物种类由建筑 JSON 固定定义，玩家不可增减种类：

```json
{
  "category": "shop",
  "shop": {
    "goods": [
      { "item_id": "minecraft:bread", "max_stock": 32 },
      { "item_id": "minecraft:apple", "max_stock": 16 }
    ],
    "profit_rate": 0.2
  }
}
```

玩家可通过 GUI 调整每种货物的 `max_stock`（每日补货数量上限），不支持增减货物种类。

### 为什么货物种类由 JSON 固定

1. 商店类型差异化：面包店和药水店卖不同货物，由 JSON 定义建筑类型实现
2. 避免 GUI 复杂度：拖拽式进货清单需要物品浏览器 + 搜索 + NBT 匹配，远超出 MVP 范围
3. 数据驱动扩展：新增商店类型只需加 JSON 文件

### 每日进货

- 每日清晨统一进货（与游客生成同一心跳）
- 进货从殖民地仓库直接扣元素（货物合成成本），不扣物品
- 补货到 `max_stock` 上限
- 当日未售出的货品在次日进货前清除（自然损耗）

### 利润模型

- 货物在制作时消耗了元素 X
- 游客购物时消耗一份货品，殖民地获得 X × (1 + profit_rate) 的元素收入
- 默认利润率 20%，可通过 Config 全局调整

### 货品与三值联动

- 有货 → 货物对三值加成正常计入
- 缺货（仓库元素不足以进货）→ 该建筑三值加成清零

## 服务建筑 (category: service)

游客使用服务建筑时，消耗精力值，产生满意度和元素收入。服务建筑包括宾馆、图书馆、体育馆等。

```json
{
  "category": "service",
  "service": {
    "energy_per_use": 20,
    "element_output": { "earth": 2, "wood": 1 }
  }
}
```

### 宾馆 (service 子类型)

宾馆是服务建筑的一种，有额外入住逻辑：
- 游客入住时产生满意度和元素收入
- 空位判断：宾馆 `service.max_occupancy`（JSON 配置），当前入住人数由 TouristLeaveManager 跟踪
- 第二天清晨自动退房

## 装饰建筑 (category: decoration)

### 为什么不直接计入殖民地三值

装饰价值在于布局策略——辐射范围内功能建筑获得加成。直接计入会使装饰和普通建筑无区别，丧失"布局深度"的设计目标。

### 范围辐射加成

```json
{
  "category": "decoration",
  "comfort": 3,
  "magic": 1,
  "wonder": 2,
  "decoration": {
    "radius": 16
  }
}
```

- 装饰建筑的 `comfort/magic/wonder` 不直接计入殖民地总数值
- 这些值以范围辐射方式，全额加成给曼哈顿距离内的所有功能建筑（shop、service、workstation 等）
- `radius` 控制辐射半径，不同装饰建筑可不同配置
- 一个功能建筑可接收多个装饰建筑的加成，加成叠加
- 加成上限：单个功能建筑从装饰建筑获得的总加成，不超过其自身基础三值的 100%（可通过 Config 调整）

### 为什么用曼哈顿距离而非欧几里得距离

Minecraft 世界是轴对齐方块网格。曼哈顿距离与道路系统的 L 形路径一致，计算简单且确定性强。

## 奇观建筑 (category: wonder)

### 为什么奇观用独立的 modifier 系统而非硬编码

奇观效果类型开放（法术强度、售价、规则解锁...），后续扩展不可避免。sealed interface 保证类型安全 + 模式匹配，新增效果只需加 record + applier 分支，无需修改已有代码。

### Modifier 类型

```java
public sealed interface WonderEffect {
    record StatModifier(StatTarget target, int value) implements WonderEffect {}
    record PriceModifier(PriceTarget target, double percentage) implements WonderEffect {}
    record RuleUnlock(String ruleId) implements WonderEffect {}
}
```

| 类型 | 说明 | 示例 |
|------|------|------|
| StatModifier | 修改 NPC/建筑 属性 | 所有 NPC 法术强度 +1 |
| PriceModifier | 修改商店售价 | 所有商店售价提升 10% |
| RuleUnlock | 解锁规则级能力 | 跨殖民地物资传送 |

### JSON 格式

```json
{
  "category": "wonder",
  "comfort": 0,
  "magic": 15,
  "wonder": 20,
  "wonder": {
    "effects": [
      { "type": "stat_mod", "target": "all_npc_spell_power", "value": 1 },
      { "type": "price_mod", "target": "all_shops", "percentage": 10 }
    ]
  }
}
```

- 奇观的三值直接计入殖民地总数，不受装饰加成上限限制
- 奇观 intact + 非 shutdown → 全局效果生效
- 奇观损坏或 shutdown → 全局效果暂停（防止出 bug）
- 不参与游客交互系统

## 建筑维护费

### 为什么维护费独立于 BuildingTaskSource

BuildingTaskSource 是任务调度器（每 20 tick 轮询建筑队列），维护费是周期性资源消耗（每天/半天一次）。两者时机和职责不同。维护费走独立心跳 + NeoForge EventBus 通知。

### 维护费 JSON 格式

替代现有单一 `maintenance_cost: 4` 为 Map 格式：

```json
{
  "maintenance_cost": {
    "interval_ticks": 12000,
    "costs": { "earth": 4, "wood": 2 }
  }
}
```

### Shutdown 分级效果

| 建筑类别 | Shutdown 效果 |
|----------|--------------|
| shop | 完全关闭，游客无法交互。三值贡献归零 |
| service | 仍可使用，但游客交互产出元素减半 |
| decoration | 对周围建筑的三值辐射归零 |
| wonder | 全局效果暂停（防止连锁 bug） |
| workstation / node | 工作时间 +100%，产出 -50% |
| basic / storage | 三值贡献归零 |
| **所有建筑** | **不产生维护费**（防止死档） |

## 酒馆招募

现有酒馆建筑（category: tavern）已有 GUI 骨架。扩展内容：
- 新增 `TavernRecruitStorage`（SavedData）：存储最近 5 位满意度达到 100% 的法师游客信息
- 信息永久有效，不自动过期，直到玩家招募或手动清除
- 扩展酒馆 GUI 增加招募列表标签页

## 三值计算规则总结

| 建筑类别 | 三值计入方式 |
|----------|-------------|
| basic / node / storage / workstation 等 | 直接计入殖民地总数（per-type 首次建造，同类型不叠加） |
| shop | 有货→正常计入；缺货→归零 |
| service | 直接计入殖民地总数 |
| decoration | **不计入殖民地总数**，辐射加成给曼哈顿距离内功能建筑 |
| wonder | 直接计入殖民地总数，**不受装饰加成上限限制** |

## Config 新增配置项

```toml
[tourist]
base_spawn_count = 3          # 每日基础游客数
eval_score_divisor = 10       # 三值转换游客数除数
mage_appearance_rate = 0.05   # 法师出现概率
base_energy = 100             # 游客基础精力值
energy_per_interaction = 20   # 每次交互消耗精力

[decoration]
bonus_cap_ratio = 1.0         # 装饰加成上限 (功能建筑自身基础值的百分比)

[maintenance]
interval_ticks = 12000        # 维护周期间隔 (半天)
grace_period_ticks = 6000     # 新建筑宽限期

[shop]
default_profit_rate = 0.2     # 默认利润率
```

## MVP 阶段范围

1. 维护费系统 + shutdown 分级 ✅
2. 装饰辐射系统 + 三值计算修改 ✅
3. 商店系统（JSON 定义货物 + 每日进货 + 售卖 + 三值联动）✅
4. 奇观 modifier 框架（StatModifier + PriceModifier + RuleUnlock）✅
5. 游客实体 + 道路联动（生成 + 移动 + 交互 + 离开）✅
6. 服务建筑交互 + 宾馆入住 ✅
7. 酒馆招募存储 + GUI 扩展 ✅
8. 奇观→满意度加成（待实现）
9. 商店 max_stock 调整 GUI（待实现）
