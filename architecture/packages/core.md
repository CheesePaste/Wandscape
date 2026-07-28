# core/ — ECS 核心框架

纯 Java 21，零 MC 依赖。ECS 世界 + 组件 + 边界接口 + 内部事件。

重构后：op/task/road/system 已独立为各自顶级包，core 只保留 ECS 框架本质。

## 入口

`CoreBootstrap.bootstrap(config)` → 返回装配好的 `World` 实例。`BootStrapConfig` record 打包所有边界接口实现 + 蓝图注册表 + TaskSource 列表。

## ECS 框架

World（中央容器：ComponentStore + System 列表 + 边界引用）、System（函数式接口）、ComponentStore、CoreBootstrap。内部事件通过 `SimpleEventBus` 在 tick 末批量派发。

## 组件

Position / ManaPool / EquipmentComponent / TaskExecutor / NpcTaskQueue / Inventory / NavigationState / ColonyMember / ColonyMetadata / SuspensionContext。配套 System：ManaRegenSystem（每 tick 恢复 ManaPool，虽实现 ECS System 但因紧耦合 ManaPool 归入 component/ 而非 ecs/ 框架包）。

## 边界接口 (boundary/)

8 个纯 Java 接口定义 ECS 与 MC 世界的边界：BlockOps / EntityOps / RitualOps / MovementOps / ColonyResourceAccess / EventBus / ResourceAddedListener / ResourceShortageHandler。引擎层实现在 `engine/boundary/`。

## 内部事件

通过 SimpleEventBus 在 tick 末批量派发，与 shared/event/ 的 NeoForge 事件分离：TaskCompleted / CustomEvent（蓝图 emit 用）/ NarrativeEventTriggered。1:N 用事件，1:1 用边界接口注入。

## 测试覆盖

26+ 个测试文件，在 `src/test/java/` 对应路径下。
