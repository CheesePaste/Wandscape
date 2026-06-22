# building/ — 建筑管理

零自定义方块/BE。建筑状态全部通过 `BuildingSavedData` (Level SavedData) 管理。所有建筑使用原版方块，NPC 通过蓝图放置。

## 关键类

- **BuildingConfig** (data/) — record：id/display_name/category/pattern/block_mapping/comfort/magic/wonder/maintenance/queue/blueprint+bind。block_mapping 值全为原版方块 ID
- **BlockOffset** (data/) — [x,y,z] 相对偏移，含 toKey() 和 Gson Deserializer
- **BuildingState** (internal/) — 可变建筑状态：buildingId/typeId/category/anchor/BoundingBox/colonyId/shutdown/structureIntact/taskQueue/currentTaskId/stats
- **BuildingSavedData** (internal/) — 3个索引(buildings/posIndex/chunkIndex) + NBT持久化 + AABB重叠检测。register() 检测 intersects()
- **BuildingApiImpl** (internal/) — BuildingApi 实现：全部通过 BuildingSavedData 读写
- **EnqueueHelper** (internal/) — 入队：读 BlueprintRef → resolve bind → 硬编码 anchor → 构建 WorkItem
- **BuildingInteractHandler** (internal/) — RightClickBlock → posIndex(chunkIndex fallback) O(1) → 按 category 分发：storage→仓库GUI / workstation/crafting_station/potion_station→生产站GUI / 其他→信息打印
- **BuildingBreakHandler** (internal/) — BreakEvent/ExplosionEvent → structureIntact=false
- **BuildCompleteListener** (internal/) — 订阅引擎 build_complete CustomEvent → 验证 pattern → structureIntact=true

## 数据流

```
玩家/GUI 提交建造
  → EnqueueHelper → BuildingSavedData(structureIntact=false)
  → WorkItem → GlobalTaskPool
  → NPC领取 → 执行蓝图 → 放置原版方块
  → emit_event("build_complete")
  → BuildCompleteListener → structureIntact=true
```

## JSON

位置：`data/wandscape/buildings/*.json`，8 个文件：town_hall/forest_node/earth_node/grand_tower/warehouse/workstation/crafting_station/potion_station。格式参见 [data/buildings.md](../data/buildings.md)。

## 依赖

- shared/api/BuildingApi, shared/data/BuildingData, shared/data/WorkItem
- shared/event/BuildingPlacedEvent/BuildingShutdownEvent/BuildingRestartedEvent
- shared/registry/WandscapeApis
- dataconfig/WandscapeDataLoader
- core/event/CustomEvent（BuildCompleteListener 订阅引擎事件）
