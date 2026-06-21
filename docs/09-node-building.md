# 节点建筑

文档编号：NEW-09
版本：2.1
状态：已实现，待调试。节点建筑 = 可发布采集任务产出元素的建筑
依赖：01-shared-api, 08-building-core

---

## 零、设计决策记录（grill-me 结果）

| # | 决策 | 结论 | 理由 |
|---|------|------|------|
| 1 | 自动入队逻辑位置 | **扩展 BuildingTaskSource**，不新建 NodeTaskSource | 统一任务源，避免重复的清理/占用逻辑 |
| 2 | 采集任务的原子操作 | **RitualOp + channelTicks**，利用现有异步 future 机制 | RitualOp 已有异步模型，自然适合"引导 N tick" |
| 3 | element/amount 传参 | **RitualOp 加 `Map<String, String> params`** | A1 最通用，后续其他 ritual 也可用 |
| 4 | 蓝图 vs 硬编码 | **统一 `node:gather` DSL 蓝图 JSON**，各节点引用同一蓝图，参数差异化 | 数据驱动，不硬编码 |
| 5 | NodeConfig 存哪里 | **BuildingConfig 加 `node_config` 字段** | 节点类型参数属于建筑配置 |
| 6 | 元素注入仓库 | **ColonyResourceAccess 加 `addResource(ResourceId, int)`** | 仓库注入是 resource access 的职责 |
| 7 | 异步引导实现 | **WandscapeRitualOps 仿 AsyncTransformExecutor**：incomplete future + tickAll countdown + thenRun addResource | 复用已验证的异步模式 |
| 8 | colonyId 来源 | **从 NPC 实体取**：`EntityComponentBridge.getNpc(casterId).getColonyId()` | 不污染 core 层接口 |

---

## 一、架构概览

```
BuildingTaskSource.poll() 每 20 tick:
  ├─ Phase 1: 清理已完成任务（已有）
  ├─ Phase 2: 节点自动补给
  │    对 category=node 的建筑：
  │      operational?  queueEmpty?  !isOccupied?
  │      → 读 BuildingConfig.nodeConfig
  │      → 生成 WorkItem("node:gather", {anchor, element, amount, channel_ticks})
  │      → BuildingApi.enqueueWork(buildingId, workItem)
  └─ Phase 3: 出队发布（已有）
        → GlobalTaskPool.addTask(request)
        → 匹配空闲 NPC → 执行
```

**蓝图执行链：**

```
NPC 领取任务 → TaskExecutionSystem 执行：
  1. NavigationSystem: NPC 走到节点建筑旁
  2. RitualOp(node_gathering, target, channel_ticks, {element, amount})
     → WandscapeRitualOps.beginRitual() 返回 incomplete Future
     → NPC 进入引导状态（暂停，等待 future）
  3. tickAll 倒计时 → channel_ticks 到零 → future.complete()
     → thenRun: colonyResources.addResource(element, amount)
     → 元素注入殖民地仓库
  4. TaskExecutionSystem 检测 future 完成 → advanceStep → 任务完成
```

---

## 二、节点类型

### 2.1 第一层（开局可用）

| 节点 | 建筑 ID | 产出元素 | 每次产出量 | 引导时间 |
|------|---------|---------|-----------|---------|
| 大地节点 | earth_node | earth | 8 | 200 tick |
| 森林节点 | forest_node | wood | 10 | 200 tick |
| 水域节点 | water_node | water | 6 | 200 tick |

### 2.2 第二层（奇观值解锁）

| 节点 | 建筑 ID | 产出元素 | 每次产出量 | 引导时间 |
|------|---------|---------|-----------|---------|
| 地心节点 | fire_node | fire | 4 | 400 tick |
| 深层大地节点 | deep_earth_node | iron | 4 | 400 tick |
| 高山节点 | wind_node | wind | 4 | 400 tick |

### 2.3 第三层（高奇观值解锁）

| 节点 | 建筑 ID | 产出元素 | 每次产出量 | 引导时间 |
|------|---------|---------|-----------|---------|
| 金矿节点 | gold_node | gold | 2 | 600 tick |
| 钻石矿节点 | diamond_node | diamond | 1 | 600 tick |
| 虚空节点 | void_node | ender | 2 | 600 tick |

---

## 三、JSON 配置

### 3.1 建筑 JSON（forest_node.json）

```json
{
  "id": "forest_node",
  "display_name": "森林节点",
  "category": "node",
  "block_id": "wandscape:forest_node",
  "pattern": [[0, 0, 0]],
  "block_mapping": {
    "0,0,0": "wandscape:forest_node"
  },
  "comfort": 1,
  "magic": 2,
  "wonder": 0,
  "maintenance_cost": 2,
  "shutdown_penalty": {
    "output_reduction": 0.5,
    "time_multiplier": 2.0
  },
  "queue": {
    "capacity": 10,
    "task_types": ["gathering"]
  },
  "unlock_requirement": {
    "min_wonder": 0
  },
  "node_config": {
    "blueprint": "node:gather",
    "element": "wood",
    "amount_per_harvest": 10,
    "channel_ticks": 200
  },
  "boundary": {
    "min": [-1, -1, -1],
    "max": [1, 2, 1]
  },
  "blueprint": {
    "id": "build:clear_and_build",
    "bind": {
      "offsets": "$pattern",
      "blocks": "$block_mapping",
      "name": "$display_name"
    }
  }
}
```

**`node_config` 字段说明**（仅 category=node 的建筑有此字段）：

| 字段 | 类型 | 说明 |
|------|------|------|
| blueprint | string | 引用的采集蓝图 ID，统一为 `"node:gather"` |
| element | string | 产出的元素类型 ID |
| amount_per_harvest | int | 每次采集产出量 |
| channel_ticks | int | NPC 引导时长（tick） |

### 3.2 蓝图 JSON（node_gather.json）

```json
{
  "id": "node:gather",
  "display_name": "节点采集",
  "description": "NPC 站在节点建筑旁引导 channel_ticks，完成后元素注入殖民地仓库",
  "params": {
    "anchor": "pos",
    "element": "string",
    "amount": "int",
    "channel_ticks": "int"
  },
  "steps": [
    {
      "type": "log",
      "level": "info",
      "text": {"format": ["[NodeGather] NPC gathering {} x{} at {} ({} ticks)", "$element", "$amount", "$anchor", "$channel_ticks"]}
    },
    {
      "type": "ritual",
      "ritual": "node_gathering",
      "at": "$anchor",
      "channel_ticks": "$channel_ticks",
      "params": {
        "element": "$element",
        "amount": "$amount"
      }
    },
    {
      "type": "log",
      "level": "info",
      "text": {"format": ["[NodeGather] Complete: {} x{} → warehouse", "$element", "$amount"]}
    }
  ]
}
```

---

## 四、代码变更清单

### 4.0 依赖顺序（按此顺序实施）

```
① ColonyResourceAccess.addResource          (core — 无下游依赖)
② AtomicOp.RitualOp + params                (core)
③ StepNode.RitualStep + params              (core)
④ BlueprintInterpreter — RitualStep         (core, 依赖 ②③)
⑤ BlueprintConfigLoader — parseRitual       (engine, 依赖 ③)
⑥ WarehouseManager.addResource impl         (warehouse, 依赖 ①)
⑦ WandscapeRitualOps — channeled ritual     (engine, 依赖 ②⑥)
⑧ WandscapeEngine — ritualOps singleton     (engine, 依赖 ⑦)
⑨ BuildingConfig + NodeConfig               (shared)
⑩ BuildingApi — enqueueWork + getBuildingsByCategory (shared)
⑪ BuildingApiImpl — 实现新方法               (building, 依赖 ⑩)
⑫ BuildingTaskSource — Phase 2 supplyNodes  (engine, 依赖 ⑨⑩⑪ 以及 EngineBootstrap 注入)
⑬ Wandscape.java — tickAll 调用             (main, 依赖 ⑦⑧)
⑭ JSON 数据文件                              (依赖 ④⑤⑨)
⑮ EngineBootstrap — 注入 ritualOps          (engine, 依赖 ⑦⑧)
```

### 4.1 `core/` 层（零 MC 依赖）

| 文件 | 具体变更 |
|------|---------|
| `ColonyResourceAccess.java` | 加 `void addResource(ResourceId resource, int amount)` |
| `AtomicOp.java` — `RitualOp` | record 加 `Map<String, String> params` 字段，默认 `Collections.emptyMap()`。向后兼容，现有 `new RitualOp(id, target, ticks)` 不受影响 |
| `StepNode.java` — `RitualStep` | 加 `Map<String, ExprNode> params` 字段（仿 `EmitEventStep` / `IfStep` 的 params 模式） |
| `BlueprintInterpreter.java` — `expandStep` | `case StepNode.RitualStep s`：eval params map 中每个 value 调 `evalString`，构造 `new RitualOp(ritual, at, ticks, evaluatedParams)` |

### 4.2 `engine/` 层

| 文件 | 具体变更 |
|------|---------|
| `BlueprintConfigLoader.java` — `parseRitual` | 解析可选 `"params"` JSON 字段 → `Map<String, ExprNode>`（仿 `parseEmitEvent` 模式）。不传则空 map |
| `WandscapeRitualOps.java` | **内部 pending 机制**：
| | - `PendingRitual` record: `future, element, amount, remainingTicks, npcId`
| | - `beginRitual("node_gathering", target, world, casterId)`：`world.startAsyncOp()` → incomplete future → `pending.add(...)` → `future.thenRun(() -> colonyResources.addResource(element, amount))` → async future
| | - `tickAll()`: 倒计时递减 → 到零 `future.complete(null)`（触 `thenRun`）
| | - `hasPendingOps()`: 供 world gate
| | - 其他 ritual（包括 `self_teleport`）保持同步返回 `completedFuture`
| `WandscapeEngine.java` | 加 `static WandscapeRitualOps ritualOps` + getter/setter（与 `asyncExec` / `movementOps` 同模式） |
| `BuildingTaskSource.java` | **加 `supplyNodeBuildings()` 方法**（Phase 2）：
| | - `BuildingApi.getBuildingsByCategory(null, "node")` → 遍历
| | - 过滤：`!isShutdown && taskQueue.isEmpty() && !isBuildingOccupied`
| | - 读 `BuildingConfig.nodeConfig` → 取 `blueprint / element / amountPerHarvest / channelTicks`
| | - `BuildingApi.enqueueWork(buildingId, WorkItem{blueprint, {anchor, element, amount, channel_ticks}})`
| | - 在 `poll()` 中 Phase 1（清理）后、Phase 3（出队发布）前调用
| `EngineBootstrap.java` | 创建 `new WandscapeRitualOps()` → 注入 world → `WandscapeEngine.setRitualOps(ritualOps)` |

### 4.3 `shared/` 层

| 文件 | 具体变更 |
|------|---------|
| `BuildingApi.java` | 加 `void enqueueWork(UUID buildingId, WorkItem work)`
| | 加 `List<UUID> getBuildingsByCategory(@Nullable UUID colonyId, String category)` |
| `BuildingConfig.java` | 新建 `NodeConfig` record：`String blueprint, String element, int amountPerHarvest, int channelTicks`
| | `BuildingConfig` record 加 `@Nullable NodeConfig nodeConfig()` 字段
| | `Deserializer.deserialize`：解析 JSON `"node_config"` 可选字段（category != "node" 时不解析） |

### 4.4 `building/` 层

| 文件 | 具体变更 |
|------|---------|
| `BuildingApiImpl.java` | `enqueueWork`: getBeAt → `be.enqueueWork(work)` |
| | `getBuildingsByCategory`: 遍历 `byId` → 过滤 `BuildingData.category` |
| `BuildingConfigLoader.java` | 无需额外改动——Gson Deserializer 已注册，自动解析 `node_config` |

### 4.5 `warehouse/` 层

| 文件 | 具体变更 |
|------|---------|
| `WarehouseManager.java` | 实现 `ColonyResourceAccess.addResource(ResourceId, int)`：`ResourceId` → `ItemKey`（复用现有 MAPPING：先试 `ElementType.valueOf`，不行 `"minecraft:" + id`） → `be.add(key, amount)` |

### 4.6 数据文件

| 文件 | 变更 |
|------|------|
| `blueprints/node_gather.json` | **新建**：DSL 蓝图，ritual step 含 `params: {element: "$element", amount: "$amount"}` |
| `buildings/forest_node.json` | 加 `"node_config": {"blueprint": "node:gather", "element": "wood", "amount_per_harvest": 10, "channel_ticks": 200}` |
| `buildings/earth_node.json` | 加同上，`element: "earth"`, `amount_per_harvest: 8` |

### 4.7 `Wandscape.java`

| 位置 | 变更 |
|------|------|
| `onServerTick` | `asyncExec.tickAll()` 之后加 `WandscapeEngine.getRitualOps()?.tickAll()` |

---

## 五、关键规则

- **不重复发布**：节点有进行中任务时（`isBuildingOccupied`）不生成新 WorkItem
- **关停不产出**：`isShutdown` → `isOperational()=false` → 跳过
- **自然节奏**：采集频率 = channelTicks + NPC 调度延迟，不设独立冷却
- **元素直入仓库**：不经 NPC 背包，`thenRun` 回调中直接调用 `addResource`
- **蓝图复用**：所有节点共用 `node:gather` 蓝图，差异在 `node_config` 参数

---

## 六、已知问题

### BUG-01: 仓库 GUI 不显示采集注入的元素

**症状**：
- 日志确认 `node_gathering complete: wood x10 → colony warehouse`
- 但打开仓库 GUI 看不到新增的物品

**已排查**：
- `supplyNodeBuildings` 正常工作（有 `node supply` 日志）
- SchedulerSystem 正常分配任务给 NPC（有 `assigned` 日志）
- `WandscapeRitualOps` 正常完成引导（有 `node_gathering complete` 日志）
- `addResource` 最终调用 `WarehouseBE.add(key, amount)`

**待排查方向**：
1. `addResource` 把 `ResourceId("wood")` 映射为 `ItemKey("minecraft:oak_log", null)`，但 `WarehouseBE.add()` 是否正确写入 NBT 并 markDirty？
2. `findAnyWarehouse()` 调 `findWarehouse(null)` — 因为 `colonyId` 当前为 null，所以走 `null` 路径。仓库的 `BuildingData.colonyId` 也是 null — 匹配上了吗？
3. 仓库 GUI 的物品同步 — `WarehouseMenu` 是否在打开时从 BE 重新读取了物品列表？
