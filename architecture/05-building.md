# 05 — 建筑核心 (`building/`)

建筑方块 + BE + JSON 配置 + 结构验证 + 三数值系统。所有建筑类模块的基座。

## 源文件 (13 文件)

| 文件 | 作用 |
|------|------|
| `block/WandscapeBuildingBlock.java` | 建筑方块基类：持有 `buildingTypeId` + BE 工厂。右键→`buildEnqueueWorkItem()` → 通过 `EnqueueHelper` 解析 BlueprintRef bind + 构造 anchor，`registerIfAbsent` 支持命令放置的建筑 |
| `be/AbstractWandscapeBE.java` | **建筑 BE 基类**：colonyId / 关停标记 / 结构完整性 / `Deque<WorkItem>` FIFO 队列 / currentTaskId / `onActivate(Player)` 钩子（仓库等覆写做无队列交互）。完整 NBT 持久化（params 按 JSON 字符串存储）。`hasWork()`/`dequeueWork()` 只检查 shutdown，不检查 structureIntact（避免修复死锁） |
| `be/TownHallBE.java` | 市政厅 BE（category: basic，殖民地中心） |
| `be/ForestNodeBE.java` | 森林节点 BE（category: node，产木元素） |
| `be/EarthNodeBE.java` | 大地节点 BE（category: node，产土元素） |
| `be/GrandTowerBE.java` | 大塔 BE（category: landmark，7×7×7 大型建筑测试） |
| `data/BlockOffset.java` | record：[x,y,z] 相对偏移，含 `toKey()` 和 Gson Deserializer |
| `data/BuildingConfig.java` | record：id / display_name / category / block_id / pattern / block_mapping / comfort/magic/wonder / maintenance / shutdown_penalty / queue / unlock + **BlueprintRef**（可选蓝图引用，含 id + bind） |
| `internal/BuildingConfigLoader.java` | 单例：从 `data/wandscape/buildings/*.json` 加载 `BuildingConfig`，存 ConcurrentHashMap。注册自定义 Gson + BlockOffset/BuildingConfig Deserializer |
| `internal/BuildingDataImpl.java` | BuildingData 接口的 package-private 可变实现 |
| `internal/BuildingApiImpl.java` | BuildingApi 实现：三索引（byId / byPos / colony→activeCounts）+ 关停/重启 + 殖民地三数值计算 + 任务桥接（getBuildingsWithPendingWork / dequeueWork / setCurrentTask / clearCurrentTask） |
| `internal/BlockPlaceHandler.java` | `@EventBusSubscriber`：监听 `EntityPlaceEvent`，放置时验证 pattern + 注册建筑 + 缺失方块通过 `EnqueueHelper.buildWorkItem()` 自动入队修复 |
| `internal/EnqueueHelper.java` | **入队重命名模式**：读 BlueprintRef → resolve `$field_name` 绑定 → 硬编码 anchor = [x,y,z] → 构建 `WorkItem`（`Map<String, JsonElement>` params） |

## 注册项

| 注册 ID | 类型 | DeferredRegister | BE |
|---------|------|-----------------|-----|
| `town_hall` | WandscapeBuildingBlock | BLOCKS + ITEMS (BlockItem) | TownHallBE |
| `forest_node` | WandscapeBuildingBlock | BLOCKS + ITEMS (BlockItem) | ForestNodeBE |
| `earth_node` | WandscapeBuildingBlock | BLOCKS + ITEMS (BlockItem) | EarthNodeBE |
| `grand_tower` | WandscapeBuildingBlock | BLOCKS + ITEMS (BlockItem) | GrandTowerBE |
| `warehouse` | WandscapeBuildingBlock | BLOCKS + ITEMS (BlockItem) | WarehouseBE |

5 方块 + 5 BlockItem + 5 BE 类型均注册在 `Wandscape.java`。
其中 warehouse 通过 `AbstractWandscapeBE.onActivate()` 钩子打开 GUI，不使用任务队列。

## JSON 格式 (`data/wandscape/buildings/`)

已有 5 个：`town_hall.json` / `forest_node.json` / `earth_node.json` / `grand_tower.json`（206 pattern blocks，7×7×7 boundary，549 总 ops）/ `warehouse.json`（3×3×3 敞开正面，category=storage）

完整字段规范见 [`spec/building-json.md`](../spec/building-json.md) — 含 schema、默认值、字段实现状态、数据流

蓝图 DSL 设计见 [`spec/blueprint-dsl.md`](../spec/blueprint-dsl.md) — Building JSON 引用 Blueprint 的逻辑容器分离方案

## 依赖

- `shared/api/BuildingApi` / `shared/data/BuildingData` / `shared/data/WorkItem`
- `shared/event/BuildingPlacedEvent` / `BuildingShutdownEvent` / `BuildingRestartedEvent`
- `shared/registry/WandscapeApis`
- `dataconfig/WandscapeDataLoader`
