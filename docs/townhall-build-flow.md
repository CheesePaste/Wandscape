# townhall1 建造流程分析

## 完整调用链

```
V 键 → PanelStateTogglePacket → 获取 colonyId → 同步 ColonyStatsSyncPacket、BuildingAreaSyncPacket
  ↓
G 键 → ProjectionEnterPacket → 服务器记录投影模式
  ↓
建筑栏选择 townhall1 → 双击进入 PLACING 阶段 → 客户端显示 ghost
  ↓
点击放置 → ProjectionPlacePacket -> handleServer()
  │
  ├─ 1. BuildingConfigLoader.get(buildingTypeId)             校验建筑类型存在
  │
  ├─ 2. api.getBuildingAt(anchorPos)                         校验锚点未被占用
  │    └─ BuildingSavedData.getBuildingAt(BlockPos)
  │       ├─ posIndex.get(pos)                               快速路径：posIndex（重启后为空！）
  │       └─ getBuildingIdAt(pos) → chunkIndex + bounds      慢速路径：体积碰撞检测
  │
  ├─ 3. EnqueueHelper.registerIfAbsent()
  │    ├─ api.getBuildingAt(pos)                             重复 step 2（无额外作用）
  │    ├─ 创建 BuildingState（UUID、typeId、边界盒等）
  │    ├─ api.registerBuilding(state)  →  BuildingSavedData.register(state, config)
  │    │    ├─ 从 config.pattern() + anchor 计算 patternPositions（世界坐标）
  │    │    ├─ overlapsPattern(newPattern, existing)          遍历 ALL 已有建筑做碰撞检测
  │    │    │    └─ Set<BlockPos>.contains 交集检测           pattern 越大越慢
  │    │    ├─ buildings.put(stateId, state)                  ★ 插入后才抛异常不会回滚
  │    │    ├─ posIndex.put(worldPos, stateId)                ★ 已经写入了索引
  │    │    └─ chunkIndex 构建
  │    ├─ 旋转 pattern（rotationSteps != 0）
  │    ├─ ColonyApiImpl.assignColonyIfPossible()              分配殖民地
  │    └─ 首次 seed warehouse（初始物品为空，7 元素各 ×2000）
  │
  ├─ 4. 判断 firstFree（首次建造不消耗材料）
  │    ├─ config.firstFree() && !sd.isFirstFreeClaimed()
  │    └─ sd.claimFirstFree(colonyId, buildingTypeId)
  │
  ├─ 5. EnqueueHelper.buildWorkItem(config, pos, ..., skipMaterials=firstFree)
  │    ├─ anchor → JSON
  │    ├─ blueprint bind: offsets=pattern, blocks=block_mapping, name=display_name
  │    ├─ clear_offsets → boundaryBox.allPositions()         边界盒内所有位置
  │    │    └─ townhall1 边界: (0,-1,0)~(36,14,17) = 37×16×18 = 10,656 个
  │    ├─ material_list/material_counts → computeMaterialData()
  │    │    └─ skipMaterials=true → 空 JsonArray/JsonObject
  │    ├─ 旋转处理（offsets、blocks、clear_offsets 做 Y 轴旋转）
  │    └→ new WorkItem("build:clear_and_build", params, priority)
  │
  └─ 6. api.enqueueWork(buildingId, workItem)
       └─ BuildingState.taskQueue.addLast(workItem)

  ※ 至此服务器端注册完成，返回成功消息给客户端

  ────── 以下是引擎调度 NPC 实际建造 ──────

7. BuildingTaskSource.poll()  ← 每 20 tick（1 秒）
  ├─ api.getBuildingsWithPendingWork(null)
  │    └─ 遍历 all buildings，找 taskQueue 非空 + 无活跃任务的
  ├─ api.dequeueWork(buildingId)
  │    └─ BuildingState.taskQueue.pollFirst()                 从队首取出 WorkItem
  ├─ btp.enqueue(buildingId, item, pool)
  │    └─ BlueprintInterpreter.expandSteps()                  展开 blueprint 为 AtomicOp 列表
  │         │
  │         ├─ log 步骤                    → LogOp（无状态）
  │         ├─ for_each(clear_offsets)     → 每个 offset 一个 TransformOp(place air)
  │         │    └─ townhall1: 10,656 个 TransformOp
  │         ├─ call build:place_structure
  │         │    ├─ request_resource       → ResourceRequestOp(items)
  │         │    │    └─ items 为 dynamicItems：map_to_items(material_list, material_counts)
  │         │    │       material_list=[]  → items=[]  → 抛 IllegalArgumentException ★ 旧 bug
  │         │    ├─ for_each(offsets)      → 每个 pattern 偏移一个 TransformOp(place block, consumable)
  │         │    └─ emit_event             → EmitEventOp(build_complete)
  │         └→ AtomicOp[] → 加入 GlobalTaskPool
  │
  ├─ 成功: api.setCurrentTask(buildingId, taskId)
  └─ 失败: 日志 "FAILED: blueprint=... error=..."  WorkItem 已从 queue 取出 → 永久丢失

8. NPC 执行（TaskExecutionSystem）
  ├─ ResourceRequestOp → ResourceRequestExecutor
  │    └─ 向 ColonyItemBank 请求物品              仓库不够 → ResourceShortageException
  ├─ TransformOp(place) → AsyncTransformExecutor
  │    └─ 放置方块
  └─ EmitEventOp → 触发 build_complete 事件
```

## townhall1 关键数据

| 属性 | 值 |
|------|-----|
| pattern 偏移数 | 从 JSON 扫描结果（37×16×18 范围内有效方块） |
| block_mapping 条目数 | 与 pattern 相同 |
| boundary | min(0,-1,0) ~ max(36,14,17) = **10,656 个位置** |
| clear_offsets | boundary.allPositions() = **10,656 个** |
| first_free | true |

## 各步骤已出过的错误

### 错误 1：MaterialList 为空 → ResourceRequestOp 校验失败（已修复）

```
BlueprintInterpreter: request_resource 的 map_to_items(material_list=[]) → items=[]
AtomicOp.ResourceRequestOp: if (items.isEmpty()) throw
BuildingTaskSource: FAILED blueprint=build:clear_and_build error=ResourceRequestOp items must not be empty
```

**原因**：`firstFree` 时 `skipMaterials=true`，`buildWorkItem` 跳过 `material_list`/`material_counts`。即使改成空数组，`ResourceRequestOp` 紧凑构造函数硬校验空列表。

**修复**：`BlueprintInterpreter` 中解析 `request_resource` 后若 `stacks` 为空，跳过整个 op。

### 错误 2：重叠检测阻止第二次放置 townhall1（当前问题）

```
BuildingApiImpl: Building townhall1 at (49,-60,-113) overlaps with townhall1 at (53,-60,-104)
ProjectionPlacePacket: Failed to register building at 49,-60,-113
```

**原因**：`BuildingSavedData.register()` 的 `overlapsPattern()` 用 pattern 位置集合做交集检测。townhall1 从扫描器导入，pattern 覆盖 37×16×18 范围（含内部许多空气位）。第一个 townhall1 占据 (53,-60,-104) 到 (89,-46,-87)，任何在附近新建的 townhall1 必然与之重叠。

**关键细节**：`register()` 中即使抛 `BuildingOverlapException`，前面已有 `buildings.put()` 副作用。但由于异常传播到 `registerIfAbsent` → `catch (BuildingOverlapException e) { return false; }`，调用方认为注册失败，**但** data map 中已写入记录。

**影响**：玩家无法在同一区域放下第二个同类大型建筑。

### 错误 3：ColonyStatsSyncPacket NPE（已修复）

```
ColonyStatsSyncPacket.write(): buf.writeUUID(null) → NPE
```

**原因**：`BuildingApiImpl.getColonySnapshot()` 中 `sd.getContributionRegistry()` 返回 null（新世界无存档时未初始化），`colonyId` 虽非 null 但 NPE 被 `getSnapshotSafe()` 捕获返回 EMPTY → `colonyId=null` → 发包时 `buf.writeUUID(null)`。

**修复**：`getContributionRegistry()` 懒初始化 + `getColonySnapshot()` null 保护 + 序列化 null-safety。

## 架构问题汇总

### 1. 注册不是事务性的

`BuildingSavedData.register()` 在 `overlapsPattern` 前已经把 state 写入了 `buildings` map。如果抛异常，调用方无法回滚这个写入。

### 2. 两层重叠检测不一致

- `ProjectionPlacePacket` 第一层：`api.getBuildingAt(anchorPos)` — 只查锚点位置（单个方块）
- `BuildingSavedData.register()` 第二层：`overlapsPattern` — 查 pattern 所有位置（数千方块）

两层检查标准不同。ghost 显示时只根据视觉反馈（锚点位置），实际注册时却要全 pattern 检测。

### 3. Boundary 与 Pattern 脱节

`clear_offsets` 基于 boundary box 计算（10,656 个位置），而 pattern 只包含实际有方块的位置。clear 阶段清除了大量不需要清除的空气位。对大型扫描建筑尤为明显。

### 4. WorkItem 一旦 dequeue 不可回退

`BuildingTaskSource.dequeueWork()` 用 `pollFirst()` 移出队列，随后 blueprint 展开失败时 WorkItem 已丢失，不会回到队列中。

### 5. 碰撞检测 O(n²)

每次注册新建筑需遍历 ALL 已有建筑做 `overlapsPattern`，其中每个 `contains()` 是 O(patternSize)。大量建筑或大型 pattern 时性能劣化。

---

## 仅 townhall 相关文件

- `src/main/java/.../projection/network/ProjectionPlacePacket.java`
- `src/main/java/.../building/internal/EnqueueHelper.java`
- `src/main/java/.../building/internal/BuildingApiImpl.java` (registerBuilding)
- `src/main/java/.../building/internal/BuildingSavedData.java` (register, overlapsPattern)
- `src/main/java/.../building/internal/BuildingConfigLoader.java`
- `src/main/java/.../building/data/BuildingConfig.java`
- `src/main/resources/data/wandscape/buildings/townhall1.json`
- `src/main/resources/data/wandscape/blueprints/build_clear_and_build.json`
- `src/main/resources/data/wandscape/blueprints/build_place_structure.json`
- `src/main/java/.../task/engine/dsl/BlueprintInterpreter.java`
- `src/main/java/.../engine/source/BuildingTaskSource.java`
- `src/main/java/.../engine/boundary/ResourceRequestExecutor.java`
