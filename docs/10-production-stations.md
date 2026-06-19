# 生产站点

文档编号：NEW-10
版本：1.0
状态：制作站 + 万能工作站 + 魔药站
依赖：01-shared-api, 08-building-core

---

## 一、职责边界

- 实现三种生产建筑：制作站、万能工作站、魔药站
- 提供各自的方块实体（继承 `AbstractWandscapeBE`）
- 提供各自的 GUI（玩家手动向队列添加任务）
- 对接仓库系统（消耗元素/物品、存入产物）

**不包含：**
- 建筑队列机制（08 模块提供）
- 任务调度和 NPC 执行（06 模块负责）
- 元素存储（04 模块负责）

---

## 二、万能工作站

### 2.1 功能

工作站是玩家与殖民地仓库交互的主要操作终端，提供两种功能：

| 功能 | 操作方向 | 说明 |
|------|---------|------|
| 分解 | 物品 → 元素 | 将 `#wandscape:decomposable` 标签内的方块分解为元素 |
| 合成 | 元素 → 物品 | 用元素合成方块/物品，存入仓库 |

所有操作通过生成任务、加入工作站队列、由 NPC 执行来完成。

### 2.2 分解任务

玩家操作：
1. 打开工作站 GUI，选择"分解"模式
2. 界面显示仓库中所有可分解方块及数量
3. 选择一种方块 → 输入分解数量（任意数量，如 5 个或 64 个）
4. 系统检查仓库中该方块是否充足 → 生成 1 个分解任务入队（任务携带玩家指定的数量）

任务单元：
```java
TaskTemplate decomposeTask = new TaskTemplate(
    BehaviorType.CRAFTING,
    1,
    List.of(
        new OperationB(stationId, "decompose", Map.of("item_id", itemId, "count", quantity))
    )
);
```

NPC 执行时：从仓库提取指定数量的方块 → 消耗方块 → 对应元素注入仓库。每次任务引导时间固定为 60 秒，单任务最多处理 1 组（64 个）物品。

### 2.3 合成任务

玩家操作：
1. 打开工作站 GUI，选择"合成"模式
2. 界面显示所有已解锁配方（取决于殖民地魔法值）
3. 选择目标产物 → 输入合成数量（任意数量，如 5 个或 64 个）
4. 系统计算所需元素 → 检查储量 → 生成 1 个合成任务入队（任务携带玩家指定的数量）

任务单元：
```java
TaskTemplate synthesizeTask = new TaskTemplate(
    BehaviorType.CRAFTING,
    1,
    List.of(
        new OperationB(stationId, "synthesize", Map.of("recipe_id", recipeId, "count", quantity))
    )
);
```

NPC 执行时：从仓库扣除对应数量的元素 → 产物存入仓库。每次任务引导时间固定为 60 秒，单任务最多产出 1 组（64 个）物品。

### 2.4 限制

- 分解仅限 `#wandscape:decomposable` 方块
- 合成仅限已解锁配方（魔法值控制）
- 单个任务只处理一种方块/物品，数量由玩家自定（最小 1，最大 64 = 1 组）
- 队列容量 60（60 个任务，不是 60 组物品）
- 同一时间只处理一个任务
- 每次任务引导时间固定为 60 秒（与任务内物品数量无关，1 个和 64 个都是 60 秒）

---

## 三、制作站

### 3.1 功能

- 制作法杖（指定 NBT 预设的成品法杖）
- 制作装备（法袍、饰品等）
- 可制作的等级受殖民地魔法值限制

### 3.2 制作流程

1. 玩家打开制作站 GUI → 看到所有已解锁的法杖/装备配方
2. 选择目标物品 → 输入数量
3. 系统计算所需元素和材料 → 生成制作任务入队（队列容量 60）

```java
TaskTemplate craftWandTask = new TaskTemplate(
    BehaviorType.CRAFTING,
    requiredLevel,
    List.of(
        new OperationB(stationId, "craft_wand",
            Map.of("recipe_id", recipeId, "count", quantity))
    )
);
```

### 3.3 JSON 配方

```json
// data/wandscape/recipes/builder_wand.json
{
  "id": "builder_wand",
  "type": "wandscape:crafting",
  "station": "crafting_station",
  "output": {
    "item": "wandscape:wand",
    "nbt": {
      "wand_color": "#FFD700",
      "behaviors": { "building": 1 },
      "range": 1,
      "mana_cost_multiplier": 1.0
    }
  },
  "cost": {
    "earth": 32,
    "wood": 16
  },
  "required_level": 1,
  "unlock_magic_value": 0
}
```

---

## 四、魔药站

### 4.1 功能

- 制作消耗性药剂和道具
- 示例：初级法力药剂（恢复 NPC 个人魔力值）

### 4.2 制作流程

与制作站相同，队列容量 10。

```json
// data/wandscape/recipes/mana_potion.json
{
  "id": "mana_potion",
  "type": "wandscape:potion",
  "station": "potion_station",
  "output": {
    "item": "wandscape:mana_potion",
    "count": 1
  },
  "cost": {
    "water": 16,
    "wood": 4
  },
  "required_level": 1,
  "unlock_magic_value": 0
}
```

---

## 五、方块实体

```java
public class WorkstationBE extends AbstractWandscapeBE {
    // 继承队列、关停、维护

    @Override
    public void tick() {
        super.tick(); // 处理队列发布
    }

    // 处理玩家 GUI 操作：入队分解/合成任务
    public void enqueueDecompose(String itemId, CompoundTag nbt, int batches) { /* ... */ }
    public void enqueueSynthesize(String recipeId, int batches) { /* ... */ }
}

public class CraftingStationBE extends AbstractWandscapeBE {
    public void enqueueCraft(String recipeId, int count) { /* ... */ }
}

public class PotionStationBE extends AbstractWandscapeBE {
    public void enqueueBrew(String recipeId, int count) { /* ... */ }
}
```

---

## 六、MVP 范围

| 建筑 | MVP 实现 |
|------|---------|
| 万能工作站 | 分解圆石/原木 → 土/木元素；元素合成石砖 |
| 制作站 | 制作 4 种基础法杖（建造/采集/制作/仪式） |
| 魔药站 | 制作初级法力药剂 1 种 |

---

## 七、独立测试方案

### 单元测试

1. **配方加载**：所有配方 JSON 正确解析
2. **队列容量**：工作站 ≤60，魔药 ≤10
3. **元素消耗计算**：合成 N 组产物所需元素正确

### 集成测试

1. 玩家在工作站 GUI 发布分解 64 个圆石 → NPC 执行 → 土元素 +256
2. 玩家发布合成 64 个石砖 → NPC 执行 → 土元素 -256，石砖 +64
3. 制作站制作建造法杖 → 仓库中获得带正确 NBT 的法杖
4. 魔药站制作法力药剂 → 玩家可从仓库取出并使用
