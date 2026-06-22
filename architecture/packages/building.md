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
- **BuildingBreakHandler** (internal/) — BreakEvent/ExplosionEvent → 收集受损坐标 → structureIntact=false → 构造局部修复 WorkItem（offsets 仅含受损方块，addFirst 插入队首，priority=49）。ExplosionEvent 按建筑分组批量入队，避免对大建筑全量重放蓝图。提供 `enqueueRepairForPositions`（事件侧）和 `enqueueRepairForOffsets`（Listener 侧）两个静态入口
- **BuildCompleteListener** (internal/) — 订阅引擎 build_complete CustomEvent → `findDamagedBlocks` 扫描世界检测剩余损坏方块 → `structureIntact=true` / `enqueueRepairForOffsets(局部)`（修复中再次损坏则只修复新增部分）

## 数据流

```
玩家/GUI 提交建造
  → EnqueueHelper → BuildingSavedData(structureIntact=false)
  → WorkItem → GlobalTaskPool
  → NPC领取 → 执行蓝图 → 放置原版方块
  → emit_event("build_complete")
  → BuildCompleteListener → structureIntact=true

结构损坏修复
  → BreakEvent/ExplosionEvent
  → BuildingBreakHandler → 收集受损世界坐标 → 转为 pattern 偏移 → structureIntact=false
  → 构造局部 WorkItem（offsets=仅受损偏移，params=offsets+blocks+anchor）→ addFirst
  → BuildingTaskSource.poll() → TaskRequest → GlobalTaskPool
  → NPC领取 → 执行 build:place_structure 局部蓝图（仅放置受损偏移的方块）
  → BuildCompleteListener → findDamagedBlocks 扫描 → 全部修复 → structureIntact=true
  → 仍有损坏（修复中再次受损）→ enqueueRepairForOffsets(剩余部分) → 局部重试
```

## JSON

位置：`data/wandscape/buildings/*.json`，8 个文件：town_hall/forest_node/earth_node/grand_tower/warehouse/workstation/crafting_station/potion_station。格式参见 [data/buildings.md](../data/buildings.md)。

## 依赖

- shared/api/BuildingApi, shared/data/BuildingData, shared/data/WorkItem
- shared/event/BuildingPlacedEvent/BuildingShutdownEvent/BuildingRestartedEvent
- shared/registry/WandscapeApis
- shared/ui/component/TaskQueuePanel (UI组件，通过building/network包使用)
- building/network/TaskQueueModifyPacket, TaskQueueDataPacket
- dataconfig/WandscapeDataLoader
- core/event/CustomEvent（BuildCompleteListener 订阅引擎事件）
