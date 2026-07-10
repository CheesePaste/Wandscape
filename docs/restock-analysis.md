# 商店补货系统分析文档

> 编写日期：2026-07-10
> 目的：分析当前补货逻辑的问题，设计更符合模拟经营游戏的补货方案

---

## 一、当前补货逻辑问题分析

### 1.1 面包 43/64 为什么不补货？

**触发条件**：`purchase()` 方法中的动态补货阈值：

```java
int maxStock = getMaxStock(buildingId, itemId);  // = 64
if (maxStock > 0 && newStock < maxStock / 3) {   // 64/3 = 21
    restock(...);
}
```

库存 43 时卖出一个变 42，`42 >= 21`，**不触发补货**。

**根本原因**：没有定期补货机制。当前系统是纯事件驱动的（购买触发、首次打开GUI、拖动滑块），没有任何每日或定时补货。面包需要降到 21 以下才会补货。

### 1.2 蛋糕 0/64 为什么永远空？

**三重复合故障**：

| 问题 | 说明 | 代码位置 |
|------|------|---------|
| ① 建筑级初始化检查 | `ensureStockInitialized` 用 `hasShopStock(buildingId)` 检查——面包有库存(43)，返回 true，整个建筑的初始化被跳过，蛋糕从未获得首次补货 | `ShopStockManager.java:89` |
| ② 补货成本不可负担 | 蛋糕每单位补货需 576 金属（colony 没有），`canAfford = 0`，被跳过 | `ShopStockManager.java:337-340` |
| ③ 0 库存无法触发购买 | `purchase()` 在 `current <= 0` 时直接 return false，蛋糕永远进不了购买→补货回路 | `ShopStockManager.java:241-242` |

**死锁回路**：买不了 → 没触发 → 补不了 → 买不了。即使面包触发补货时连带检查蛋糕，也会因 576 金属不够再次跳过。

### 1.3 DEFAULT_MAX_STOCK = 0

`ShopGoodDef.DEFAULT_MAX_STOCK = 0`，玩家必须手动通过 GUI 滑块设置最大库存，否则所有商品 `needed = 0 - 0 = 0`，永不补货。

---

## 二、时间系统现状调查

### 2.1 结论：没有统一时间管理

模组**没有**统一的时间管理服务。各系统各自独立追踪时间：

| 系统 | 触发方式 | 周期 | 文件 |
|------|---------|------|------|
| DailySettlementSystem | ServerTickEvent.Post + dayTime%24000≈0 | 每天一次 | `building/internal/DailySettlementSystem.java` |
| TouristSpawnSystem | ServerTickEvent.Post + dayTime 阶段判断 | 每天三阶段 | `tourist/internal/TouristSpawnSystem.java` |
| HotelStayHandler | ServerTickEvent.Post + dayTime<200 | 每天清晨 | `tourist/internal/HotelStayHandler.java` |
| MaintenanceForecastSystem | ServerTickEvent.Post + 自计数 | 每 6000 tick | `building/internal/MaintenanceForecastSystem.java` |
| DecorationBonusSystem | ServerTickEvent.Post + 自计数 | 每 200 tick | `building/internal/DecorationBonusSystem.java` |
| Wandscape (主类) | ServerTickEvent.Post + 自计数 | 每 tick + 每 100 tick | `Wandscape.java` |
| SchedulerSystem (ECS) | world.tick() 内部 | 每 2 tick | `task/scheduler/SchedulerSystem.java` |
| **ShopStockManager** | **纯事件驱动，无 tick** | **不主动** | `building/internal/ShopStockManager.java` |

### 2.2 可用的 hook 点

`DailySettlementSystem` 已经在 `level.getDayTime() % 24000 ≈ 0` 时（每天日出时刻）执行结算，并在完成后通过 `NeoForge.EVENT_BUS` 投递 `DailySettlementEvent`。

**提议的方案**：不另起炉灶造时间系统，而是让 `ShopStockManager` 监听 `DailySettlementEvent`，在每日结算完成后执行商店补货。这样：
- 复用现有的"每天日出"触发点
- 遵循"维护费先扣，补货在后"的合理经济顺序
- 不需要新增 tick 监听器

---

## 三、仓库物品系统调查

### 3.1 ColonyItemBank 存储结构

```java
// 物品存储（纯计数，无 ItemStack 实体）
Map<UUID, Map<ItemKey, Long>> storage;       // colonyId → (itemId+nbt → count)
// 元素存储
Map<UUID, Map<ElementType, Long>> elementStorage;  // colonyId → (elementType → count)
```

`ItemKey` 是一个 record：`(String itemId, @Nullable CompoundTag nbt)`

### 3.2 关键方法

| 方法 | 用途 |
|------|------|
| `count(colonyId, ItemKey)` | 查询某种物品的库存 |
| `available(colonyId, ItemKey)` | 可用数量（扣除预留） |
| `consume(colonyId, ItemKey, count)` | 消耗物品，返回 boolean |
| `add(colonyId, ItemKey, count)` | 增加物品 |

### 3.3 当前补货流程 vs 目标流程

**当前**（消耗元素）：
```
ColonyItemBank 扣元素 → ItemTransportManager 动画 → ShopStockManager 加库存 → 游客购买 → ColonyItemBank 加元素(利润)
```

**目标**（消耗物品）：
```
ColonyItemBank 扣物品(ItemKey) → ItemTransportManager 动画 → ShopStockManager 加库存 → 游客购买 → ColonyItemBank 加元素(利润)
```

ColonyItemBank 已经支持物品的 `count()`/`consume()`，改造成本低。关键是 `restock()` 中将元素查询/扣除替换为物品查询/扣除。

---

## 四、元素映射生成系统分析

### 4.1 生成流程

`/generate_element_mappings` 命令 → `ElementValueGenerator` 执行：

```
element_seeds.json → 种子加载
RecipeManager 扫六大配方类型 → 收集所有配方
50 轮迭代求解 → 从种子的已知值传播推导未知物品
computeFromNode() → 成分成本求和 ÷ outputCount × 效率系数
输出到 element_mappings/*.json
```

### 4.2 对三个关键问题的回答

#### 问题 A：是否考虑了合成配方的数量（count per slot）？

**是**。`outputCount = result.getCount()`，成本除以 outputCount。例如饼干配方产 8 个，总成本 16 木 → 每个饼干 2 木。

但使用 `(long)` 整数除法会截断。例如 14 成本 ÷ 8 输出 = 1（而非 1.75）。有下限保护 `if (scaled <= 0 && original > 0) scaled = 1`。

#### 问题 B：是否考虑了产出数量？

**同 A**。outputCount 就是产出数量，工作正常。

#### 问题 C：是否正确处理了副产物和可返还容器（如蛋糕的奶桶→桶）？

**没有。这是问题的根源。**

`computeFromNode()` 只处理 `recipe.getIngredients()`（成分列表），完全没有调用 `CraftingRecipe.getRemainingItems()` 或检查 `hasCraftingRemainingItem()`。

**蛋糕的 576 金属追踪**：

```
蛋糕配方：3 牛奶桶 + 3 小麦 + 2 糖 + 1 鸡蛋
  ├── 牛奶桶种子值: water=4, metal=192 ← 桶的铁
  ├── 小麦种子值: wood=12
  ├── 糖: 来自小麦→合成(木), 成本 wood=2×12×1÷2 = 12 木?
  └── 鸡蛋: ...

成分求和: water=12, metal=576, wood=20+(?), wind=1, earth=1

实际 Minecraft 行为：3 牛奶桶 → 3 空桶返还，蛋糕不应含 metal
当前生成器：3 牛奶桶完全消耗 → metal=576 错误传导到蛋糕
```

**根本原因**：`ElementValueGenerator.RecipeNode` 不包含 `remainingItems` 信息，`computeFromNode()` 只对 ingredient 求和，没有减去返还物品的元素值。

### 4.3 影响范围

所有使用**含容器物品**（桶、碗、锅等）的配方都会受此影响：

| 配方 | 含容器成分 | 问题 |
|------|-----------|------|
| 蛋糕 | 3× 牛奶桶 | 多了 576 金属 |
| 蘑菇汤 | 碗 | 多了碗的成本 |
| 兔肉汤 | 碗 | 多了碗的成本 |
| 甜菜汤 | 碗 | 多了碗的成本 |
| 迷之炖菜 | 碗 | 多了碗的成本 |
| 下界合金升级 | 钻石装备 | 不返还但计算与返还的逻辑分离 |
| 烟花 | 火药/纸 | 多个输出但非返还问题 |

### 4.4 修复方向

修复 `ElementValueGenerator` 的 `RecipeNode` 和 `computeFromNode()`：

1. 在 `RecipeNode` 中增加 `remainingItems` 字段（记录每个成分槽的返还物品）
2. 在 `computeFromNode()` 中，对返还物品的元素值做减算
3. 或者更简单：对 `hasCraftingRemainingItem()` 为 true 的成分，选择子节点中**最小**的元素值作为返还值（对应桶=铁的成本，要扣除）

以蛋糕为例的修复计算：
```
成分: 3× 牛奶桶{water:4, metal:192} = {water:12, metal:576}
返还: 3× 桶{metal:192} = {metal:576}
实际净成本 = {water:12, metal:576} - {metal:576} = {water:12}
```

---

## 五、新补货逻辑设计方案

### 5.1 总览

| 改动 | 说明 | 优先级 |
|------|------|--------|
| ① 每日自动补货 | 在 DailySettlementEvent 后补货所有商店 | P0 |
| ② 补货改为消耗仓库物品 | 从 ColonyItemBank 取物品而非元素 | P0 |
| ③ 1/3 阈值保留 | 购买后 < max/3 时触发即时补货（已有逻辑） | P1（已有） |
| ④ 每商品独立初始化 | ensureStockInitialized 改为按商品检查 | P1 |
| ⑤ bakery.json 明确 restock_cost | 写入合理补货成本，不依赖推断 | P0（配套修复） |

### 5.2 每日自动补货（P0）

**触发时机**：`ShopStockManager` 订阅 `DailySettlementEvent`，在每日结算完成后执行。

```java
// ShopStockManager 新增
@SubscribeEvent
public void onDailySettlement(DailySettlementEvent event) {
    UUID colonyId = event.getReport().colonyId();
    // 遍历该 colony 的所有正常 shop 建筑，执行 restock()
    BuildingSavedData savedData = getSavedData();
    for (BuildingState state : savedData.getBuildingsByColony(colonyId)) {
        if (!"shop".equals(state.getCategory())) continue;
        if (state.isShutdown() || !state.isStructureIntact()) continue;
        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null || config.shop() == null) continue;
        ColonyItemBank bank = ColonyItemBank.get(getServerLevel());
        restock(state.getBuildingId(), config.shop(), colonyId, bank);
    }
}
```

**要点**：
- DailySettlementSystem 已经在 `timeOfDay ≈ 0` 时触发，且先扣维护费后发事件
- 补货在维护费之后执行，顺序正确（先付租金，再进货）
- 每日补货自动重试所有商品，蛋糕即使某天缺金属，第二天还会再试

### 5.3 从仓库取物品补货（P0）

修改 `restock()` 中的成本扣除逻辑：

**改动前**（按元素）：
```java
for (var entry : costPerItem.entrySet()) {
    long available = bank.countElement(colonyId, entry.getKey());
    int perItem = entry.getValue();
    if (perItem > 0) {
        canAfford = (int) Math.min(canAfford, available / perItem);
    }
}
// ...
bank.consumeElement(colonyId, elem, entry.getValue() * canAfford);
```

**改动后**（按物品）：

```java
// 不再查元素，改为查仓库有没有对应物品
ItemKey itemKey = ItemKey.of(good.itemId(), null);
long availableInWarehouse = bank.available(colonyId, itemKey); // 扣除预留
int canAfford = (int) Math.min(needed, availableInWarehouse);

// 消耗物品
bank.consume(colonyId, itemKey, canAfford);
```

**元素系统角色变化**：补货不再消耗元素，元素只从游客购买获得（利润收入）。

### 5.4 经济循环重构

```
旧循环:
  仓库(元素) → 补货消耗元素 → 商店库存 → 游客购买 → 仓库(元素+利润)

新循环:
  仓库(物品) → 补货消耗物品 → 商店库存 → 游客购买 → 仓库(元素+利润)
                   ↑                                      ↓
              生产站/workstation                  元素用于维护费/合成
              制作物品放入仓库
```

**影响**：
- 生产建筑（工作站/合成站）制作的物品现在有了实际用途——商店消耗它们
- 元素不再是万能的"造物资源"，而是"商业利润"和"维护费"货币
- 即使元素为 0，只要仓库有实物库存，商店照样能补货营业

### 5.5 蛋糕的明确 restock_cost

在 bakery.json 中为面包和蛋糕添加明确的 `restock_cost`，不再依赖元素映射推断：

```json
{
  "shop": {
    "goods": [
      {
        "item_id": "minecraft:bread",
        "restock_cost": { "wood": 4 },
        "comfort": 1, "magic": 0, "wonder": 0
      },
      {
        "item_id": "minecraft:cake",
        "restock_cost": { "wood": 8, "water": 6, "earth": 2 },
        "comfort": 2, "magic": 0, "wonder": 1
      }
    ],
    "profit_rate": 0.2
  }
}
```

**为什么不继续用元素映射推断**：
1. 元素映射的 build_cost 是"建造方块"的成本，不是"生产商品"的成本
2. 元素映射不处理返还物品（蛋糕的桶），数值不准确
3. 经济维度不同：build_cost 是高额的一次性建造费用，restock_cost 是日常经营成本，应该低很多

### 5.6 修改文件清单

| 文件 | 改动 |
|------|------|
| `ShopStockManager.java` | 添加 `@SubscribeEvent onDailySettlement()`；`restock()` 中元素查询/扣除改为物品查询/扣除；`ensureStockInitialized` 改为按商品检查 |
| `bakery.json` | 添加面包和蛋糕的 `restock_cost` |
| `ElementValueGenerator.java` | （如果修）RecipeNode 增加 remainingItems，computeFromNode 减去返还物 |

### 5.7 后续可能的问题

- **物品运输动画**：当前 `launchRestockTransport()` 和 `addStockOnTransportArrival()` 是每单位逐个到达时扣除成本。改为物品消耗后，需要在运输出发时（或到达时）扣物品？建议在**运输出发时扣物品**（和元素扣减时点一致），到达时只加库存。如果运输途中建筑被毁，物品已扣不可退——但比元素扣了不退更合理（物理物品已经发出）。
- **空仓库场景**：如果仓库物品为 0，补货静默失败（`canAfford = 0`，跳过），不做特殊处理。每日补货会反复尝试。
- **与现有 reserve/commit 系统的冲突**：ColonyResourceAccess 的预留机制只用于 NPC 任务系统，商店补货不需要预留，直接 `consume()` 即可。

---

## 六、已实施的改动

> 实施日期：2026-07-10

### 6.1 生成器修复

| 文件 | 改动 |
|------|------|
| `ElementValueGenerator.java` | 新增 `IngredientSlot` record，`RecipeNode.ingredientOptions` → `slots`；`collectRecipes()` 中捕获 `getCraftingRemainingItem()`；`computeFromNode()` 按净值（减去返还物）选最优方案；新增 `subtractFrom()` |

**效果**：跑 `/generate_element_mappings --force` 后，蛋糕等含容器配方的 `build_cost` 不再包含容器（桶/碗）的材料成本。

**输出路径**：命令改输出到 `src/main/resources/data/wandscape/element_mappings/`，不再需要手动从 `run/wandscape_generated/` 复制。

### 6.2 补货改为消耗仓库物品

| 文件 | 改动 |
|------|------|
| `ShopStockManager.java` | `restock()` 用 `bank.available(ItemKey)` + `bank.consume(ItemKey)` 替代元素查询/扣除；`launchRestockTransport()` 去掉 `costPerItem` 参数；`addStockOnTransportArrival()` 去掉元素扣除逻辑；`inferRestockCostFromMappings()` 重命名为 `getItemElementValue()`，用于利润计算 |

### 6.3 每日自动补货

| 文件 | 改动 |
|------|------|
| `ShopStockManager.java` | `register()` 注册到 `NeoForge.EVENT_BUS`；新增 `@SubscribeEvent onDailySettlement()` 每日结算后补货所有商店 |

### 6.4 购买利润改为基于元素映射

`purchase()` 中的利润计算从 `good.restockCost()` 改为 `getItemElementValue()`（元素映射的 `decompose_yield` / `build_cost`）。
