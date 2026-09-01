# 包扫描跟进清单（该动项，你自己慢慢挪/删）

> 来源：11 子代理全包扫 + 交叉实证（2026-09-01）。只列**会咬 / 高价值**的项；观感类不列。
> 分两档：【M】移动（改包名 + import）、【D】删除/清理。
> **通用做法**：在 IDEA 里对类做 `Move to Package`（自动改 package 声明 + 全仓 import 引用），比手工可靠；改完 `./gradlew compileJava` 验证。
> 测试类不用管（`src/test` 本就 red，后续单独大删）。

## 【M】域内错置对调（纯移动，低风险）

| 类 | 现位置 | 挪到 | 为何（证据） |
|---|---|---|---|
| `RoadWalkPlanner` | `npc/nav` | `road` | 是 road 路由逻辑（import `road.algorithm.RoadRouter`/`road.core`），唯一消费方是 `tourist/internal/TouristMoveGoal`，两头都不归 npc |
| `WandscapeNavigation` | `npc/nav` | `foundation/nav` | **两域共享**寻路基建（npc + 游客都用），Javadoc 自述 "Shared navigation for all Wandscape entities" |
| `WandscapeNodeEvaluator` | `npc/nav` | `foundation/nav` | 同上，共享 NodeEvaluator |
| `NpcTaskQueue` | `npc/component` | `task` | 是 task 调度机制（存 `NpcTaskPackage`/`AtomicOp`，被 `TaskExecutionSystem`/`GlobalTaskPool`/`TaskExecutor`/`SelfDefenseExecutor` 消费），非 npc 实体状态 |
| `SuspensionContext` | `npc/component` | `task` | `NpcTaskQueue` 的挂起栈元素，随迁 |
| `ColonyAmbientTracker` | `building/internal` | `colony/sound` | colony 昼夜环境音服务端门控；客户端对应物 `ColonyAmbientSystem` 已在 `colony/sound` |
| `ChunkLoadManager` | `colony/service` | `building` | 租赁的是**建筑 footprint** 区块，类语义全 building（由 `BuildingTaskSource`/`BuildingRemovedEvent`/`BuildingSavedData` 驱动） |
| `ChunkLeaseData` | `colony/service` | `building` | 同上，`ChunkLoadManager` 的租赁数据 |
| `ParamTypeInfo` | `magic/data` | `building/data` | 蓝图/任务编辑器 DTO（仅被 `building/data/BlueprintInfo` 引用），非魔法概念 |

> 注：`ParamTypeInfo` 与已迁的 building 配置同为 DTO 归属，纯移动、纯顺手；其余 8 项解的是**依赖倒置/跨域聚错**，属"会咬"。

## 【D】删空壳/空包（零风险）

| 目标 | 删什么 | 额外动作 |
|---|---|---|
| `foundation/saveddata/` | 整个空目录（仅 `.gitkeep`） | 无 |
| `foundation/ui/render/` | 整个空目录（仅 `.gitkeep`；真渲染器在 `building/render/`） | 无 |
| `WarehouseNotificationHandler.java` | 删类（22 行，`onResourceInsufficient` 空方法体死壳，`EVENT_BUS` 挂hook不做事） | 删 `Wandscape.java:516` 的 `WarehouseNotificationHandler.register()` + `:99` import |
| `SplineEditorClientState.java` | 删 `static{}` 里 `new RoadTemplate("test_road_5x1")` 种子（`:154-155`） | 无 |

## 顺手可做（不是必须，遇着就清）
- 清死 `import com.wsteam.wandscape.content.task.ecs.World`（~216 文件，仅 import 行用到）。
  **别拆 ECS**——`task/ecs/World` 是**活的** task 引擎运行时内核（6 个跨域 `EcsSystem` 在用）；删的是"重构后改直调、World 引用已废"的死 import。删掉能编译过就是死 import，编译器兜底。
