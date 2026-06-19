# JSON 驱动蓝图生成

文档编号：NEW-24
版本：1.1
状态：✅ 已实现。JSON BuildingConfig → Blueprint 自动注册。函数式 BlueprintSteps 保留为逃生门，DSL 未实现。
依赖：01-shared-api, 08-building-core, 19-engine-v1-baseline

---

## 一、动机

当前所有建造蓝图由 `engine/source/blueprint/BuildingBlueprints.java` 硬编码注册：

```java
registry.register("build:stone_bricks", buildPlaceSteps(BlockType.STONE_BRICKS, ...));
registry.register("build:oak_planks",  buildPlaceSteps(BlockType.OAK_PLANKS, ...));
registry.register("build:stone",       buildPlaceSteps(BlockType.STONE, ...));
registry.register("build:dirt",        buildPlaceSteps(BlockType.DIRT, ...));
registry.register("build:glass",       buildPlaceSteps(BlockType.GLASS, ...));
registry.register("build:platform",    buildPlatformSteps(BlockType.STONE_BRICKS, ...));
```

但 `BuildingConfig` 已经包含完整建造信息：`pattern` + `block_mapping` 就是该建筑需要放置的方块列表。蓝图应从 JSON 自动派生，不重复维护。

## 二、设计

### 2.1 核心思路

```
BuildingConfig.pattern + BuildingConfig.blockMapping
    → DataDrivenSteps.fromConfig(config)
    → BlueprintSteps (Function<Map<String,String>, TaskSequence>)
    → BlueprintRegistry.register("build:<buildingId>", steps)
```

### 2.2 DataDrivenSteps

`engine/source/blueprint/DataDrivenSteps.java` — 通用翻译器。

```java
public final class DataDrivenSteps {

    /** 从 BuildingConfig 生成 BlueprintSteps，负责 pattern→TransformOp 的编译期翻译。 */
    public static BlueprintSteps fromConfig(BuildingConfig config) {
        return params -> {
            GridPos anchor = parsePos(params);
            List<AtomicOp> ops = new ArrayList<>();

            for (BlockOffset offset : config.pattern()) {
                String blockId = config.blockMapping().get(offset.toKey());
                if (blockId == null) {
                    Log.warn("DataDrivenSteps",
                        "missing block_mapping for offset %s in building %s",
                        offset.toKey(), config.id());
                    continue;
                }
                GridPos pos = new GridPos(
                    anchor.x() + offset.x(),
                    anchor.y() + offset.y(),
                    anchor.z() + offset.z()
                );
                ops.add(TransformOp.place(pos, new BlockType(blockId)));
            }

            return new TaskSequence(ops,
                "Build " + config.displayName() + " at " + anchor);
        };
    }

    private static GridPos parsePos(Map<String, String> params) {
        try {
            return new GridPos(
                Integer.parseInt(params.getOrDefault("x", "0")),
                Integer.parseInt(params.getOrDefault("y", "0")),
                Integer.parseInt(params.getOrDefault("z", "0"))
            );
        } catch (NumberFormatException e) {
            return GridPos.ORIGIN;
        }
    }
}
```

三步：`parsePos(运行时坐标) → 展开 pattern → 查 block_mapping 构造 TransformOp`。

### 2.3 命名空间

统一 `"build:" + config.id()`，JSON 为唯一蓝本。

硬编码蓝图全部移除：
- 5 个单方块蓝图 (`build:stone_bricks` 等) — 无需，JSON 建筑覆盖
- `build:platform` — 调试用的 3×3 多步蓝图，移除

### 2.4 布线

`EngineBootstrap.bootstrap()` 在 `ServerStartingEvent` 触发后执行。此时 `AddReloadListenerEvent` 已触发且 `WandscapeDataLoader.apply()` 已完成——所有 JSON 已加载到 `BuildingConfigLoader`。

```
EngineBootstrap.bootstrap():
  → BlueprintRegistry blueprints = new BlueprintRegistry()
  → BuildingConfigLoader.getAll().forEach(config ->
        blueprints.register("build:" + config.id(), DataDrivenSteps.fromConfig(config)))
  → CoreBootstrapConfig 传入 blueprints
```

`BuildingConfigLoader` 持有 `BlueprintRegistry` 引用（直接传引用，不引入 listener 回调链路）。

### 2.5 模块依赖

```
engine/source/blueprint/
    ├── DataDrivenSteps.java          ← NEW
    └── BuildingBlueprints.java       ← 大幅简化或删除

DataDrivenSteps 引用:
    → building/data/BuildingConfig   (reading pattern + blockMapping)
    → core/task/BlueprintSteps       (return type)
    → core/task/TaskSequence         (return type)
    → core/task/AtomicOp             (TransformOp.place)
    → core/types/BlockType           (new BlockType(id))
    → core/types/GridPos             (anchor + offset)
```

`engine/` → `building/data/` 依赖忽略模块隔离规则（`DataDrivenSteps` 本质是引擎 JSON→任务翻译器，属 engine 内部实现细节）。

### 2.6 BlueprintSteps 保留

`BlueprintSteps` 函数式接口保留不动。`DataDrivenSteps` 覆盖 90% 场景（pattern→TransformOp），复杂蓝图（循环、条件、混合操作类型、外部状态查询）仍通过 lambda 注册。两者不互斥。

## 三、未实现：DSL

后续阶段需要将 `DataDrivenSteps` 的能力扩展到 JSON DSL：
- 条件分支 (`mode: repair` vs `mode: build`)
- 参数驱动循环展开 (`build:wall W×H`)
- 混合操作类型（`ResourceRequestOp` → `TransformOp` × N → 事件触发）
- 动态坐标计算（朝向旋转 pattern）
- 外部状态查询（已存在方块跳过）
- GUI 可视化逻辑编排

当前 `DataDrivenSteps` 只覆盖 pattern→TransformOp 的静态翻译。DSL 编译器未来作为 `DataDrivenSteps` 的扩展或替换实现。

## 四、时序保证

源自对 MC 1.21.1 NeoForge `DedicatedServer.initServer()` 源码验证：

```
DedicatedServer.initServer():
  ① handleServerAboutToStart()    → ServerAboutToStartEvent
  ② loadLevel()
       └─ ReloadableServerResources.loadResources()
            └─ AddReloadListenerEvent  → DATA_LOADER 注册
            └─ SimpleReloadInstance.create().done()
                 └─ WandscapeDataLoader.apply()
                      └─ BuildingConfigLoader.parseConfig() × N  ← JSON 全部就位
  ③ handleServerStarting()        → ServerStartingEvent
       └─ EngineBootstrap.bootstrap()  ← 此时遍历 BuildingConfigLoader.getAll() 安全
```

引擎 boot 时 JSON 已加载完毕，无竞态。

## 五、实现记录

### 改动文件

| 文件 | 操作 |
|------|------|
| `engine/source/blueprint/DataDrivenSteps.java` | **NEW** — 核心翻译器 |
| `engine/source/blueprint/BuildingBlueprints.java` | **DELETED** — 硬编码移除 |
| `engine/bootstrap/EngineBootstrap.java` | 遍历 `BuildingConfigLoader.getAll()` 注册 `build:<id>` |
| `building/block/WandscapeBuildingBlock.java` | `"build:platform"` → `"build:" + buildingTypeId` |
| `building/be/AbstractWandscapeBE.java` | `hasWork()` + `dequeueWork()` 只检查 `isShutdown`，不检查 `isStructureIntact`（避免修复死锁） |
| `building/internal/BlockPlaceHandler.java` | repair 改为入队 `"build:" + buildingTypeId`（整建筑蓝图），不再逐块生成硬编码 ID |
| `engine/source/BuildingTaskSource.java` | 新增 `activeTasks` 跟踪 + 定时清理已完成的 currentTask |
| `core/task/GlobalTaskPool.java` | `size()` 只计活跃任务；新增 `isActive()` |
| `resources/data/wandscape/buildings/town_hall.json` | 扩展为 12 步：8 stone_bricks 地板 + 4 oak_log 角柱 |

### 首次发现并修复的 Bug

| 发现 | 修复 |
|------|------|
| `isStructureIntact=false` 时 `hasWork()` 返回 false → 修复任务永不入池 | `hasWork()`/`dequeueWork()` 不再检查 `isStructureIntact` |
| Repair 使用 `build:stone_bricks` 等已删除的硬编码蓝图 ID | 改用 `"build:" + buildingTypeId`，全建筑蓝图 re-run |
| `BuildingTaskSource` 从不调 `clearCurrentTask()` → 完成任务后建筑永久阻塞 | 新增 `activeTasks` 跟踪 + `isActive()` 检测 → 自动清理 |
| `GlobalTaskPool.size()` 计入 COMPLETED 任务 → 日志虚高 | 只计活跃任务 |

## 六、测试

`src/test/java/com/wsteam/wandscape/core/task/DataDrivenStepsTest.java`：

| 用例 | 输入 | 预期 |
|------|------|------|
| 单方块 building (earth_node) | `cfg = earth_node.json`, params `{x:10,y:64,z:5}` | 1 步 TransformOp.place，worldPos=(10,64,5)，blockType=wandscape:earth_node |
| 多方块 pattern | 手写 fixture `BuildingConfig`，3 个 offset + 3 个 mapping | 3 步，坐标正确偏移 |
| 缺失 block_mapping | pattern 含 key 不在 mapping 中 | warn + skip，总步骤数正常 |
| parsePos 缺失 key | params 无 x | 默认 0 |
| parsePos 异常 | params `{x:"abc"}` | fallback ORIGIN |
| 真实 JSON 加载 | `BuildingConfigLoader.parseConfig(earth_node.json)` → `DataDrivenSteps.fromConfig()` | 编译期无异常 |

手写 fixture `BuildingConfig` record + 真实 `earth_node.json` 双路径覆盖。
