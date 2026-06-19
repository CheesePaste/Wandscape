# 05 — 建筑核心 (`building/`)

建筑方块 + BE + JSON 配置 + 结构验证 + 三数值系统。所有建筑类模块的基座。

## 源文件 (11 文件)

| 文件 | 作用 |
|------|------|
| `block/WandscapeBuildingBlock.java` | 建筑方块基类：持有 `buildingTypeId` + BE 工厂。右键→入队 `build:<typeId>` 任务（JSON 驱动蓝图） |
| `be/AbstractWandscapeBE.java` | **建筑 BE 基类**：colonyId / 关停标记 / 结构完整性 / `Deque<WorkItem>` FIFO 队列 / currentTaskId。完整 NBT 持久化。`hasWork()`/`dequeueWork()` 只检查 shutdown，不检查 structureIntact（避免修复死锁） |
| `be/TownHallBE.java` | 市政厅 BE（category: basic，殖民地中心） |
| `be/ForestNodeBE.java` | 森林节点 BE（category: node，产木元素） |
| `be/EarthNodeBE.java` | 大地节点 BE（category: node，产土元素） |
| `data/BlockOffset.java` | record：[x,y,z] 相对偏移，含 `toKey()` 和 Gson Deserializer |
| `data/BuildingConfig.java` | record：id / display_name / category / block_id / pattern / block_mapping / comfort/magic/wonder / maintenance / shutdown_penalty / queue / unlock。含完整 Gson Deserializer |
| `internal/BuildingConfigLoader.java` | 单例：从 `data/wandscape/buildings/*.json` 加载 `BuildingConfig`，存 ConcurrentHashMap。注册自定义 Gson + BlockOffset/BuildingConfig Deserializer |
| `internal/BuildingDataImpl.java` | BuildingData 接口的 package-private 可变实现 |
| `internal/BuildingApiImpl.java` | BuildingApi 实现：三索引（byId / byPos / colony→activeCounts）+ 关停/重启 + 殖民地三数值计算 + 任务桥接（getBuildingsWithPendingWork / dequeueWork / setCurrentTask / clearCurrentTask） |
| `internal/BlockPlaceHandler.java` | `@EventBusSubscriber`：监听 `EntityPlaceEvent`，放置时验证 pattern + 注册建筑 + 缺失方块自动入队修复 WorkItem |

## 注册项

| 注册 ID | 类型 | DeferredRegister | BE |
|---------|------|-----------------|-----|
| `town_hall` | WandscapeBuildingBlock | BLOCKS + ITEMS (BlockItem) | TownHallBE |
| `forest_node` | WandscapeBuildingBlock | BLOCKS + ITEMS (BlockItem) | ForestNodeBE |
| `earth_node` | WandscapeBuildingBlock | BLOCKS + ITEMS (BlockItem) | EarthNodeBE |

3 方块 + 3 BlockItem + 3 BE 类型均注册在 `Wandscape.java`。

## JSON 格式 (`data/wandscape/buildings/`)

已有 3 个：`town_hall.json` / `forest_node.json` / `earth_node.json`

## 依赖

- `shared/api/BuildingApi` / `shared/data/BuildingData` / `shared/data/WorkItem`
- `shared/event/BuildingPlacedEvent` / `BuildingShutdownEvent` / `BuildingRestartedEvent`
- `shared/registry/WandscapeApis`
- `dataconfig/WandscapeDataLoader`
