# architecture/ — 代码结构事实

本目录是项目代码结构的**唯一真相来源**。docs/ 只放设计推理和路线图。源代码是权威的——这里不重复代码。

## 包地图

```
Wandscape.java        @Mod 入口，注册物品/实体/粒子/菜单，生命周期事件
Config.java           NeoForge TOML 配置，所有可调参数
│
├── core/             ECS 引擎，纯 Java 21，零 MC 依赖
│   ├── ecs/          World + System + ComponentStore
│   ├── component/    8 个组件：Position/ManaPool/WandCarrier/TaskExecutor/Inventory/...
│   ├── boundary/     6 个接口：BlockOps/EntityOps/RitualOps/MovementOps/ColonyResourceAccess/EventBus
│   ├── op/           AtomicOp(sealed,7种) + OpExecutor + 注册表
│   ├── task/         任务池 + 蓝图DSL(BlueprintDefinition/Interpreter/ExprNode/StepNode)
│   ├── system/       Scheduler/TaskExecution/ManaRegen/TaskSourcePoller/...
│   ├── road/         路网生成(MST+PathGenerator+DecorationPlanner) — 纯逻辑无MC依赖
│   ├── event/        领域事件(SimpleEventBus) 和类型定义
│   └── types/        基础record: GridPos/BlockType/ResourceId/BehaviourTag/...
│
├── engine/           MC 适配实现（注入 core 边界接口）
│   ├── bootstrap/    EngineBootstrap — 一次性装配所有边界实现+TaskSource+系统
│   ├── boundary/     WandscapeBlockOps/MovementOps/RitualOps + AsyncExecutor
│   ├── source/       BuildingTaskSource(20tick轮询→发布TaskRequest) + BlueprintConfigLoader
│   ├── road/         RoadBuilder/RoadSavedData/RoadEventListener/RoadTaskSource
│   └── system/       NavigationSystem(NPC移动总控)
│
├── shared/           所有包依赖的公共层
│   ├── api/          12个模块接口 + registry/WandscapeApis.java(静态定位器)
│   ├── data/         17个record/enum(BehaviorType/AbilitySet/WorkItem/...)
│   ├── event/        16个NeoForge事件(模块间通信)
│   └── ui/           中世纪魔法主题组件库(MedievalScreen/Button/ScrollableList/...)
│
├── building/         建筑管理(零自定义方块/BE，全部SavedData)
├── wand/             法杖物品+NBT+预设
├── element/          方块→元素映射
├── npc/              NPC实体+ECS桥接(EntityComponentBridge)+渲染
├── warehouse/        GUI+ColonyItemBank(SavedData)
├── production/       工作站(GUI/配方/菜单/网络包)
├── dataconfig/       JSON数据加载框架(WandscapeDataLoader)
└── command/          调试命令(/wandscape ...)
```

## 数据流（核心路径）

```
BuildingConfig JSON → BuildingConfigLoader
  → EnqueueHelper → WorkItem 入 BuildingState.taskQueue
  → BuildingTaskSource.poll(每20tick)
  → TaskRequest → GlobalTaskPool.addTask()
  → SchedulerSystem(每2tick评分: proximity×0.5 + efficiency×0.3 + behaviourLevel×0.2)
  → NPC领取 → TaskExecutionSystem
  → AtomicOp → OpExecutor → WandscapeBlockOps(MC世界实际方块操作)
  → emit_event → BuildCompleteListener → BuildingSavedData.structureIntact=true
```

## 依赖规则

```
shared/          ← 所有包可见（API+事件+数据类）
core/            ← 所有包可见（纯Java，零MC依赖）
engine/          ← 实现core边界接口，持有MC引用
building/wand/...  ← 通过WandscapeApis + NeoForge EventBus通信，不可跨包直接引用
```

- core/ 禁止 import MC 类，禁止持有运行时状态
- 模块间通过 `WandscapeApis.getXxxApi()` + NeoForge EventBus 通信
- 跨模块 new 类是反模式
- BE 不能直接调引擎 → BuildingTaskSource 是唯一入口

## 各包入口

| 想看什么 | 打开 |
|---------|------|
| ECS引擎/任务池/蓝图DSL/调度器 | [packages/core.md](packages/core.md) |
| MC桥接/异步执行/方块操作/NPC移动 | [packages/engine.md](packages/engine.md) |
| API接口/事件/数据类型/UI组件 | [packages/shared.md](packages/shared.md) |
| 建筑管理/SavedData/入队 | [packages/building.md](packages/building.md) |
| NPC实体/ECS桥接/渲染 | [packages/npc.md](packages/npc.md) |
| 法杖物品/NBT | [packages/wand.md](packages/wand.md) |
| 元素映射 | [packages/element.md](packages/element.md) |
| 仓库GUI/ItemBank | [packages/warehouse.md](packages/warehouse.md) |
| 工作站/合成 | [packages/production.md](packages/production.md) |
| JSON加载框架 | [packages/dataconfig.md](packages/dataconfig.md) |
| 建筑JSON格式 | [data/buildings.md](data/buildings.md) |
| 蓝图DSL格式 | [data/blueprints.md](data/blueprints.md) |
| 编码规范和反模式 | [conventions.md](conventions.md) |
