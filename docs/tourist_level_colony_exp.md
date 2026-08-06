# 殖民地等级与游客等级重设计

## 一、设计目标

1. **殖民地有等级**，初始 1 级，经验条满了升级
2. **殖民地等级驱动游客数量与等级**：等级越高 → 每日游客越多，游客等级分布随殖民地等级偏移
3. **游客等级驱动招募质量**：等级越高的法师满意度 100% 后招募数值越高
4. **游客满意度驱动殖民地经验**：仅满意度达到 100% 的游客才提供殖民地经验
5. **昼夜三段式游客生成/离开节奏**
6. **UI 展示等级**：V 面板顶栏显示殖民地等级（金色），市政厅右键打开等级/经验面板

## 二、殖民地等级系统（新）

### 2.1 数据结构

新增 `ColonyLevelData`（SavedData），每个殖民地一条：

```
- colonyId: UUID
- level: int         // 当前等级，初始 1
- experience: int    // 当前经验值
```

### 2.2 经验需求公式

| 等级提升 | 需求经验 |
|---------|---------|
| 1 → 2   | 2000    |
| 2 → 3   | 3000    |
| 3 → 4   | 4000    |
| N → N+1 | (N+1) × 1000 |

公式：`expToNext(currentLevel) = (currentLevel + 1) × 1000`

> 已确认 ✓

### 2.3 经验来源

**仅**游客满意度 **达到 100%** 时才判定经验（满意度不到 100% 不做任何惩罚）：

| 游客等级 vs 殖民地等级 | 提供的经验 |
|----------------------|-----------|
| 游客等级 < 殖民地等级 | 0         |
| 游客等级 = 殖民地等级 | 100       |
| 游客等级 > 殖民地等级 | 500       |

> 已确认 ✓

### 2.4 预留接口

`ColonyLevelManager.addExperience(colonyId, amount, reason)` 的 `reason` 参数预留后续奇观/魔法等其他经验来源扩展。

## 三、游客等级生成机制

### 3.1 等级范围

```
可能等级 = [colonyLevel - 1, colonyLevel, colonyLevel + 1]
最低保底 1 级
```

### 3.2 等级分布比率（固定权重）

| 游客等级 | 权重 |
|---------|------|
| colonyLevel - 1 | 40% |
| colonyLevel     | 40% |
| colonyLevel + 1 | 20% |

例如殖民地 5 级 → 40% 4 级 / 40% 5 级 / 20% 6 级。

### 3.3 等级影响满意度门槛（已实现，无需修改）

`threshold = tourist.level × TOURIST_LEVEL_SATISFACTION_THRESHOLD`

已在 `TouristMoveGoal.computeSatisfactionGain()` 中实现。

### 3.4 等级影响法师招募数值（已实现）

**当前状态**：`TouristEntity.onAddedToLevel()` 中法师 6 属性（maxHp/moveSpeed/spellPower/workSpeed/spellSpeed/armorValue）按等级缩放 roll。

**修改方案**（2026-08-06 属性重构后）：法师属性生成时乘以缩放因子：

```
scaleFactor = 0.8 + level × 0.2
```

例如：
- `spellPower = random(1, 4) × scaleFactor`
- `workSpeed = random(1, 2) × scaleFactor`
- `spellSpeed = random(1, 2) × scaleFactor`
- `maxHp = random(40, 60) × scaleFactor`
- `armorValue = random(0, 10) × scaleFactor`
- `moveSpeed = 0.25~0.40`

| 等级 | scaleFactor | spellPower 范围 |
|------|------------|-----------------|
| 1    | 1.0        | 1 - 4           |
| 2    | 1.2        | 1.2 - 4.8       |
| 3    | 1.4        | 1.4 - 5.6       |
| 5    | 1.8        | 144 - 360   |
| 10   | 2.8        | 224 - 560   |

> 已确认 ✓

## 四、每日游客数量公式

### 4.1 目标游客数

```
base = Config.TOURIST_BASE_SPAWN_COUNT (6)
levelBonus = colonyLevel × levelSpawnBonus (3)
targetCount = base + levelBonus
```

每日在 `targetCount × [0.8, 1.2]` 范围内随机浮动（实数四舍五入取整）。

**不保留三值加成**，但通过 `addExperience()` 的 `reason` 参数预留后续奇观/魔法增加游客数的扩展接口。

> 已确认 ✓

### 4.2 上限

- `Config.TOURIST_MAX_PER_COLONY`（20）
- 同时活跃游客数不超过殖民地等级相关的上限（后续可调）

## 五、三段式游客生成/离开节奏

### 5.1 时段定义（MC 游戏时间 dayTime % 24000）

| 时段名称 | 游戏时间范围 | 描述 |
|---------|------------|------|
| 清晨     | 0 - 1000   | 每日重置窗口，酒店强制退房 |
| 上午~下午 | 1000 - 13000 | 游客分散生成 + 正常建筑交互 |
| 傍晚     | 13000 - 18000 | 无新游客生成，已有游客可继续交互 |
| 夜间离开 | 18000 - 24000 | 游客离开判定窗口 |

### 5.2 生成逻辑（1000~13000 段）

1. 当日首次进入 1000~13000 时段时，计算 `targetCount`
2. 为每个游客均匀分配一个生成时间：`spawnTime = 1000 + random(0, 12000)`
3. 每 tick 检查：当前时间 >= 某游客的 spawnTime → 生成该游客
4. 进入 13000 后不再生成新游客（即使 targetCount 未满）

### 5.3 夜间离开逻辑（18000~24000）

每次 cleanup 检查：

| 满意度 | 行为 |
|--------|------|
| < 50   | 0-1500 tick 随机延迟后离开 |
| = 100  | 0-1500 tick 随机延迟后离开（法师简历已存酒馆） |
| 50-99  | 引导入住酒店（有空位则入住，无空位等待下次判定） |

不再做分散离开，保持现有瞬间消失逻辑。

> 已确认 ✓

### 5.4 酒店入住逻辑调整

| 项目 | 旧值 | 新值 |
|------|------|------|
| 入住满意度阈值 | 70-99 | 50-99 |
| 退房 | 清晨 | 清晨强制退房 |
| 续住 | — | 白天结束后仍 50-99 可再次入住，无黑名单 |

> 已确认 ✓

## 六、UI 展示

### 6.1 V 面板顶栏

`WandscapePanelOverlay.renderTexts()` 修改：

**当前**：`Colony: xxxxxxxx`

**改为**：`殖民地 xxxxxx §eLv.5`（金色等级）

需要：
1. `WandscapePanelState` 新增 `colonyLevel` 字段，从服务端同步
2. `ColonyStatsSyncPacket` 或新增的同步包携带等级数据
3. 顶栏渲染时显示金色等级

### 6.2 市政厅右键面板

当前 `BuildingInteractHandler` 中 `town_hall` 走到 `default` 分支，只显示一行状态文本。

改为：
1. 新增 `engine/colony/TownHallScreen` 网络包 + 客户端 Screen
2. 右键市政厅 → `TownHallOpenPacket`（含等级、经验、下一级需求经验）
3. 客户端 `TownHallScreen` 显示：等级、经验条（当前/需求）、经验来源说明

> 已确认 ✓

## 七、新增配置项

```toml
[tourist]
# 每日基础游客生成数量
base_spawn_count = 6

# 殖民地等级相关的游客数量加成（每级增加数量）
level_spawn_bonus = 3

# 生成窗口开始（游戏时间 tick）
spawn_window_start = 1000
# 生成窗口结束（游戏时间 tick）
spawn_window_end = 13000
# 夜间离开窗口开始（游戏时间 tick）
departure_window_start = 18000
# 夜间离开窗口结束（游戏时间 tick）
departure_window_end = 24000

# 满意度低于此值 → 夜间必须离开
night_departure_satisfaction_threshold = 50
# 离开延迟的最大 ticks（0-1500 随机）
departure_delay_max_ticks = 1500

[colony]
# 同等级游客提供的经验
exp_equal_level = 100
# 高于等级游客提供的经验
exp_above_level = 500
```

## 八、实现文件清单

### 8.1 新建文件

| 文件 | 路径 | 说明 |
|------|------|------|
| ColonyLevelData.java | `engine/colony/ColonyLevelData.java` | SavedData 存储殖民地等级/经验 |
| ColonyLevelManager.java | `engine/colony/ColonyLevelManager.java` | 等级/经验逻辑（添加经验、升级检查、查询） |
| TownHallOpenPacket.java | `building/network/TownHallOpenPacket.java` | S→C 市政厅数据包 |
| TownHallScreen.java | `building/client/TownHallScreen.java` | 市政厅客户端 Screen |

### 8.2 修改文件
2
| 文件 | 修改内容                                                                                                                                                                                                     |
|------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| TouristEntity.java | `onAddedToLevel()` 法师数值乘以 `(0.8 + level × 0.2)`                                                                                                                                                          |
| TouristSpawnSystem.java | 重写生成逻辑（三段式分散生成）；重写 cleanup（夜间离开窗口cl 50 阈值 + 0-1500 tick 随机延迟）；生成时查 ColonyLevelData 获取等级分布和 targetCount                                                                                                   |
| Config.java | 新增 `base_spawn_count`、`level_spawn_bonus`、`spawn_window_start/end`、`departure_window_start/end`、`night_departure_satisfaction_threshold`、`departure_delay_max_ticks`、`exp_equal_level`、`exp_above_level` |
| Wandscape.java | 注册 TownHallOpenPacket，初始化 ColonyLevelManager                                                                                                                                                             |
| WandscapePanelOverlay.java | 顶栏显示金色等级                                                                                                                                                                                                 |
| WandscapePanelState.java | 新增 `colonyLevel` 和 `colonyExperience` 字段                                                                                                                                                                 |
| WandscapeEngine.java | 新增 `ColonyLevelManager` 静态持有                                                                                                                                                                             |
| BuildingInteractHandler.java | `town_hall` 分支改为发送 TownHallOpenPacket                                                                                                                                                                    |
| ColonyStatsSyncPacket / PanelStateTracker | 携带殖民地等级数据                                                                                                                                                                                                |
| WandscapeClient.java | 注册 TownHallOpenPacket 客户端 handler                                                                                                                                                                        |
| docs/gaps.md | 记录新系统状态                                                                                                                                                                                                  |

## 九、不做的事

- 不以分散离开（瞬间消失保持现状）
- 不修改满意度计算公式（已正确关联等级）
- 不修改偏好系统
- 不修改道路相关
- 不修改建筑交互逻辑
