# 05 — 建筑核心 (`building/`)

建筑状态通过 `BuildingSavedData` (Level SavedData) 管理，零自定义方块。所有建筑使用原版方块，NPC 通过蓝图放置。

## 源文件 (12 文件)

| 文件 | 作用 |
|------|------|
| `data/BlockOffset.java` | record：[x,y,z] 相对偏移，含 `toKey()` 和 Gson Deserializer |
| `data/BuildingConfig.java` | record：id / display_name / category / pattern / block_mapping / comfort/magic/wonder / maintenance / shutdown_penalty / queue / unlock + BlueprintRef + BoundaryBox。不再包含 block_id 字段 |
| `internal/BuildingConfigLoader.java` | 单例：从 `data/wandscape/buildings/*.json` 加载 `BuildingConfig`，存 ConcurrentHashMap |
| `internal/BuildingState.java` | 可变建筑状态，实现 `BuildingData`：buildingId / typeId / category / anchor / BoundingBox / colonyId / shutdown / structureIntact / Deque\<WorkItem\> / currentTaskId / stats |
| `internal/BuildingSavedData.java` | `extends SavedData` — 3 个索引（buildings / posIndex / chunkIndex）+ NBT 持久化 + AABB 重叠检测。`register()` 时检查 `BoundingBox.intersects()` |
| `internal/BuildingOverlapException.java` | 注册重叠建筑时抛出 |
| `internal/BuildingDataImpl.java` | `BuildingData` 接口的轻量实现（保留用于未来的简单场景） |
| `internal/BuildingApiImpl.java` | `BuildingApi` 实现：三索引 + 关停/重启 + 殖民地三数值计算 + 任务桥接。全部通过 `BuildingSavedData` 读写，不再调用 `getBeAt()` |
| `internal/EnqueueHelper.java` | 入队重命名模式：读 BlueprintRef → resolve `$field_name` 绑定 → 硬编码 anchor → 构建 `WorkItem`。`registerIfAbsent()` 创建 `BuildingState` 写入 `BuildingSavedData` |
| `internal/BuildingInteractHandler.java` | `RightClickBlock` 事件 → `posIndex` O(1) 查找 → 仓库开 GUI / 其他打印信息 |
| `internal/BuildingBreakHandler.java` | `BreakEvent` / `ExplosionEvent` → `posIndex` 查找 → `structureIntact = false` |
| `internal/BuildCompleteListener.java` | 订阅引擎内部 `EventBus` 的 `build_complete` CustomEvent → 验证 pattern 完整性 → `structureIntact = true` |

## 注册项

| 注册 ID | 类型 | 说明 |
|---------|------|------|
| `warehouse_menu` | `WarehouseMenu` | `MenuType` — 仓库 GUI ContainerMenu |

**零自定义方块/BE**。warehouse / town_hall / forest_node / earth_node / grand_tower 全部通过 `BuildingSavedData` 管理，方块完全由 NPC 用原版方块放置。仓库右键通过 `BuildingInteractHandler` 拦截（category=storage）。

## JSON 格式 (`data/wandscape/buildings/`)

5 个：`town_hall.json` / `forest_node.json` / `earth_node.json` / `grand_tower.json` / `warehouse.json`

不再包含 `block_id` 字段。所有 `block_mapping` 值使用原版方块 ID。example:

```json
{
  "id": "town_hall",
  "display_name": "殖民地市政厅",
  "category": "basic",
  "pattern": [[-1,0,-1], [0,0,-1], [1,0,-1], ...],
  "block_mapping": {
    "0,0,0": "minecraft:stone_bricks",
    ...
  },
  ...
}
```

## 数据流

```
玩家/GUI 提交建造任务
  → EnqueueHelper.registerIfAbsent() → BuildingSavedData (structureIntact=false)
  → WorkItem → GlobalTaskPool
  → NPC 领取 → 执行蓝图 → 放置原版方块
  → emit_event("build_complete")
  → BuildCompleteListener → 验证 pattern → structureIntact=true
```

## 交互流

```
玩家右键原版方块
  → BuildingInteractHandler.RightClickBlock
  → BuildingSavedData.posIndex.get(pos) O(1)
  → 仓库 (category=storage): ColonyItemBank snapshot → WarehouseMenu GUI
  → 其他: 打印建筑状态信息
```

## 依赖

- `shared/api/BuildingApi` / `shared/data/BuildingData` / `shared/data/WorkItem`
- `shared/event/BuildingPlacedEvent` / `BuildingShutdownEvent` / `BuildingRestartedEvent`
- `shared/registry/WandscapeApis`
- `dataconfig/WandscapeDataLoader`
- `core/event/CustomEvent` — BuildCompleteListener 订阅引擎事件
