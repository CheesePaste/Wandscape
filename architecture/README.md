# architecture/ — 代码结构事实

本目录是项目代码结构的**唯一真相来源**。docs/ 只放设计推理和路线图。源代码是权威的——这里不重复代码。

## 包地图

```
Wandscape.java        @Mod 入口，注册物品/实体/粒子/菜单，生命周期事件
Config.java           NeoForge TOML 配置，所有可调参数
│
├── tourist/          游客实体 + 行为 AI + 道路联动（生成/移动/交互/离开/宾馆）
│   ├── entity/       TouristEntity (extends PathfinderMob，非 WandscapeNpc)
│   ├── data/         TouristAttributes (level/energy/satisfaction/preferences/appearance)
│   ├── api/          TouristApi
│   └── internal/     TouristSpawnSystem/TouristMoveGoal/TouristInteractGoal/...
│
├── core/             ECS 引擎，纯 Java 21，零 MC 依赖
│   ├── ecs/          World + System + ComponentStore
│   ├── component/    10 个组件：Position/ManaPool/EquipmentComponent/TaskExecutor/Inventory/...
│   ├── boundary/     8 个接口：BlockOps/EntityOps/RitualOps/MovementOps/ColonyResourceAccess/EventBus/ResourceAddedListener/ResourceShortageHandler
│   ├── op/           AtomicOp(sealed,8种) + OpExecutor + 注册表 + ConditionEvaluator
│   ├── task/         任务池 + 蓝图DSL(BlueprintDefinition/Interpreter/ExprNode/StepNode) + TaskCompiler + ApprovalInfo + CompiledBlueprint + ExecutorState
│   ├── system/       Scheduler/TaskExecution/ManaRegen/TaskSourcePoller/PlayerManualSource/WorkbenchSource/EventDrivenTaskSource
│   ├── road/         路网生成(MST+PathGenerator+DecorationPlanner+RoadRouter) — 纯逻辑无MC依赖
│   ├── event/        领域事件(SimpleEventBus) 和类型定义
│   └── types/        基础record: GridPos/BlockType/ResourceId/EffectId/InteractAction/AttributeType/EquipmentSlot/EquipmentPreset/...
│
├── engine/           MC 适配实现（注入 core 边界接口）
│   ├── bootstrap/    EngineBootstrap — 一次性装配所有边界实现+TaskSource+系统
│   ├── boundary/     WandscapeBlockOps/WandscapeMovementOps/WandscapeRitualOps/WandscapeEntityOps + AsyncTransformExecutor + ResourceRequestExecutor
│   ├── source/       BuildingTaskSource(20tick轮询→发布TaskRequest) + BlueprintConfigLoader
│   ├── road/         RoadBuilder/RoadSavedData/RoadEventListener/RoadTaskSource/RoadConfig/WandscapeTags/RoadRoutingHelper/RoadBlobExplorer
│   ├── transport/    ItemTransportManager
│   └── system/       NavigationSystem(NPC移动总控) + StatsSystem + AchievementSystem
│
├── shared/           所有包依赖的公共层
│   ├── api/          12 个模块接口(不含AtomixExecutor/HouseApi/ManaPoolApi桩) + registry/WandscapeApis.java(静态定位器)
│   ├── data/         20+个record/enum(WorkItem/MaintenanceCost/BlueprintInfo/Emotion/MageResume/VisitMemory/...)
│   ├── event/        12 个 NeoForge 事件(模块间通信 + 模拟经营事件)
│   ├── log/          Log 工具类 + LogFilter 运行时白名单过滤器
│   └── ui/           中世纪魔法主题组件库(MedievalScreen/Button/ScrollableList/...)
│
├── building/         建筑管理(零自定义方块/BE，全部SavedData)。
│                     category: basic/node/storage/workstation/crafting_station/
│                               potion_station/shop/service/decoration/wonder/tavern
│                     系统: 每日结算(DailySettlementSystem) + 维护费预测(MaintenanceForecastSystem)
│                           + 装饰辐射(DecorationBonusSystem) + 商店库存(ShopStockManager) + 奇观效果(WonderEffectApplier)
│   ├── client/       HotelScreen/ShopScreen/TavernScreen
│   ├── editor/       BuildingEditor 全套(状态/控制器/ImGui面板/输入处理)
│   └── network/      建筑相关网络包
├── wand/             法杖物品+预设+NBT+JSON配方(新attributes[]格式)
├── element/          方块→元素映射
├── npc/              NPC实体+ECS桥接(EntityComponentBridge)+渲染
├── warehouse/        GUI+ColonyItemBank(SavedData), 双标签页(Overview+Exchange)
├── production/       工作站(GUI/配方/菜单/网络包, wand_level已删除)
├── dataconfig/       JSON数据加载框架(WandscapeDataLoader)
├── command/          调试命令(/wandscape ...)
├── stats/            统计系统(统计数据采集/日快照/30天滚动摘要/网络同步)
│   ├── data/         ColonyDailySnapshot/ColonyStatsSummary
│   ├── internal/     StatisticsCollector/StatisticsData(SavedData)
│   └── network/      StatsSyncPacket
├── task/             任务编辑器网络层
│   └── network/      TaskEditorOpenPacket/TaskCreatePacket/BlueprintListResponsePacket/TaskNetworkHandler
├── projection/       建筑投影/灵魂出窍模式+调试检查
│   ├── client/       ProjectionClientState/ProjectionFlightController/ProjectionRenderer + BuildingDebug*
│   └── network/      ProjectionEnter*/Exit/Place + BuildingAction/DebugRequest/DebugResponse
├── road/             道路编辑器客户端/网络/服务端
│   ├── client/       RoadEditorClientState/RoadEditorRenderer + RoadProjection*
│   ├── network/      RoadBatchPublish/RoadEdgePlan/Remove/EditorToggle/NetworkSync
│   └── server/       RoadEditorHandler
├── imgui/            ImGui 管理器 + 渲染调度
├── standalone/       独立编辑器启动器(无需MC, 纯GLFW+ImGui)
├── equipment/        装备系统(EquipmentSlot/AttributeType/EquipmentPreset/EquipmentComponent)
└── blueprint/        蓝图节点编辑器
    └── editor/       BlueprintEditorClientState/Canvas/ImGui/Controller/Network
```

## 数据流（核心路径）

```
BuildingConfig JSON → BuildingConfigLoader
  → EnqueueHelper → WorkItem 入 BuildingState.taskQueue
  → BuildingTaskSource.poll(每20tick)
  → TaskRequest → GlobalTaskPool.addTask()
  → SchedulerSystem(每2tick评分: proximity×0.5 + efficiency×0.3 + attributes×0.2)
  → NPC领取 → TaskExecutionSystem
  → AtomicOp → OpExecutor → WandscapeBlockOps(MC世界实际方块操作)
  → emit_event → BuildCompleteListener → BuildingSavedData.structureIntact=true
```

### 模拟经营数据流

```
游客生成 (每日清晨):
  TouristSpawnSystem → RoadSavedData(道路位置) → TouristEntity spawn
  → TouristMoveGoal(沿道路移动) → TouristInteractGoal(与建筑交互)
  → ShopInteractionHandler / ServiceInteractionHandler
  → 满意度变化 → TavernRecruitStorage(法师满满意→酒馆)

商店运作 (每日清晨):
  ShopStockManager.restock() → ColonyItemBank扣元素 → 填充库存
  → 游客购物 → 消耗货品 → ColonyItemBank入元素(1.2X)

装饰辐射 (每200tick):
  DecorationBonusSystem → 遍历功能建筑 → 曼哈顿距离内装饰累加
  → cap(建筑自身×100%) → BuildingContributionRegistry.getSnapshot()计入

维护费:
  DailySettlementSystem → 每日0:00按优先级结算 → ColonyItemBank扣元素
  MaintenanceForecastSystem → 元素低于阈值 → 节点建筑高优采集(WorkItem)
  → 不足 → shutdown(分级效果) → BuildingShutdownEvent

奇观效果:
  WonderEffectApplier → 建筑intact+非shutdown → 应用modifier
  → StatModifier/PriceModifier/RuleUnlock → 全局效果
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
| 建筑管理/SavedData/维护费/商店/装饰/奇观 | [packages/building.md](packages/building.md) |
| 游客实体/生成/移动/交互/离开 | [packages/tourist.md](packages/tourist.md) |
| NPC实体/ECS桥接/渲染 | [packages/npc.md](packages/npc.md) |
| 法杖物品/NBT | [packages/wand.md](packages/wand.md) |
| 元素映射 | [packages/element.md](packages/element.md) |
| 仓库GUI/ItemBank | [packages/warehouse.md](packages/warehouse.md) |
| 工作站/合成 | [packages/production.md](packages/production.md) |
| JSON加载框架 | [packages/dataconfig.md](packages/dataconfig.md) |
| 蓝图编辑器 | [packages/blueprint_editor.md](packages/blueprint_editor.md) |
| 建筑预览/投影系统 | [packages/projection.md](packages/projection.md) |
| 道路编辑器/路网 | [packages/road.md](packages/road.md) |
| 统计系统 | [packages/stats.md](packages/stats.md) |
| 任务编辑器网络层 | [packages/task.md](packages/task.md) |
| ImGui管理器 | [packages/imgui.md](packages/imgui.md) |
| 独立编辑器启动器 | [packages/standalone.md](packages/standalone.md) |
| 装备系统 | [packages/equipment.md](packages/equipment.md) |
| 建筑JSON格式 | [data/buildings.md](data/buildings.md) |
| 蓝图DSL格式 | [data/blueprints.md](data/blueprints.md) |
| 模拟经营设计推理 | [../docs/simulation.md](../docs/simulation.md) |
| 编码规范和反模式 | [conventions.md](conventions.md) |
