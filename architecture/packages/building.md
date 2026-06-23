# building/ — 建筑管理

零自定义方块/BE。建筑状态全部通过 `BuildingSavedData` (Level SavedData) 管理。所有建筑使用原版方块，NPC 通过蓝图放置。

## 关键类

- **BuildingConfig** (data/) — record：id/display_name/category/pattern/block_mapping/comfort/magic/wonder/maintenance/queue/blueprint+bind。block_mapping 值全为原版方块 ID
- **BlockOffset** (data/) — [x,y,z] 相对偏移，含 toKey() 和 Gson Deserializer
- **BuildingState** (internal/) — 可变建筑状态：buildingId/typeId/category/anchor/BoundingBox/colonyId/shutdown/structureIntact/taskQueue/currentTaskId/stats
- **BuildingSavedData** (internal/) — 3个索引(buildings/posIndex/chunkIndex) + NBT持久化 + AABB重叠检测。register() 检测 intersects()
- **BuildingApiImpl** (internal/) — BuildingApi 实现：全部通过 BuildingSavedData 读写
  - 新增：getQueue() / removeFromQueue() / moveUp() / moveDown() — 任务队列查询和调序
- **EnqueueHelper** (internal/) — 入队：读 BlueprintRef → resolve bind → 硬编码 anchor → 构建 WorkItem
- **BuildingInteractHandler** (internal/) — RightClickBlock → posIndex(chunkIndex fallback) O(1) → 按 category 分发：storage→仓库GUI / workstation/crafting_station/potion_station→生产站GUI / 其他→信息打印
- **BuildingBreakHandler** (internal/) — BreakEvent/ExplosionEvent → 收集受损坐标 → structureIntact=false → 调用 `BuildingSavedData.removeBuildingContribution()`（1→0 边界跨越时广播 `ColonyEvaluationChangedEvent`）→ 构造局部修复 WorkItem（offsets 仅含受损方块，addFirst 插入队首，priority=49）。ExplosionEvent 按建筑分组批量入队，避免对大建筑全量重放蓝图。提供 `enqueueRepairForPositions`（事件侧）和 `enqueueRepairForOffsets`（Listener 侧）两个静态入口
- **BuildCompleteListener** (internal/) — 订阅引擎 build_complete CustomEvent → `findDamagedBlocks` 扫描 → `structureIntact=true` → 调用 `BuildingSavedData.addBuildingContribution()`（0→1 边界跨越时广播 `ColonyEvaluationChangedEvent`）→ `/ 仍有损坏 → enqueueRepairForOffsets(局部重试)`
- **BuildingContributionRegistry** (internal/) — 殖民地区三值聚合缓存：per-colony per-type 的 intactCount。只在 0↔1 边界跨越时广播 `ColonyEvaluationChangedEvent`。`BuildingSavedData.load()` 后调用 `rebuildFrom()` 做一次性全量重建，兼容旧存档
- **BuildingUnlockChecker** (internal/) — 静态工具：传入 colonyId + BuildingConfig → 查询 BuildingApi 三值 vs unlockRequirement → 返回是否解锁 + 锁因字符串。用于建筑右键提示和 GUI 展示

## 数据流

```
玩家/GUI 提交建造
  → EnqueueHelper → BuildingSavedData(structureIntact=false)
  → WorkItem → GlobalTaskPool
  → NPC领取 → 执行蓝图 → 放置原版方块
  → emit_event("build_complete")
  → BuildCompleteListener → findDamagedBlocks 扫描
  → 全部修复 → structureIntact=true
  → BuildingSavedData.addBuildingContribution()
  → BuildingContributionRegistry: intactCount(type) 0→1
  → 广播 ColonyEvaluationChangedEvent

建筑受损（Break/Explosion）
  → BuildingBreakHandler → 收集受损坐标 → structureIntact=false
  → BuildingSavedData.removeBuildingContribution()
  → BuildingContributionRegistry: intactCount(type) 1→0
  → 广播 ColonyEvaluationChangedEvent
  → 构造局部 WorkItem（offsets=仅受损偏移）→ addFirst 队首
  → NPC修复 → BuildCompleteListener 再次扫描 → 全部修复 → structureIntact=true
  → BuildingSavedData.addBuildingContribution() → intactCount 0→1 → 广播事件
```

## JSON

位置：`data/wandscape/buildings/*.json`，8 个文件：town_hall/forest_node/earth_node/grand_tower/warehouse/workstation/crafting_station/potion_station。格式参见 [data/buildings.md](../data/buildings.md)。

## 依赖

- shared/api/BuildingApi, shared/data/BuildingData, shared/data/WorkItem
- shared/event/BuildingPlacedEvent/BuildingShutdownEvent/BuildingRestartedEvent/**ColonyEvaluationChangedEvent**
- shared/registry/WandscapeApis
- shared/ui/component/TaskQueuePanel (UI组件，通过building/network包使用)
- building/network/TaskQueueModifyPacket, TaskQueueDataPacket
- dataconfig/WandscapeDataLoader
- core/event/CustomEvent（BuildCompleteListener 订阅引擎事件）
