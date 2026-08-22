# architecture/ — 代码结构快照（历史）

> **⚠️ 本目录为历史快照，部分内容已过时。** 当前文档以 **`docs/`** 为准（基于真实源码重写）：
> - 架构/数据流/依赖规则 → `docs/architecture.md`
> - 各模块详解 → `docs/modules/`
> - 数据 JSON 格式 → `docs/data/`
> - 本目录与代码的差异清单 → `docs/gaps.md`

本页保留的是旧包结构描述，仅作迁移参考。源代码始终是权威。

## 包地图

```
Wandscape.java        @Mod 入口，注册物品/实体/粒子/菜单，生命周期事件
Config.java           NeoForge TOML 配置，所有可调参数
│
├── tourist/          游客实体 + 行为 AI + 道路联动（生成/移动/交互/离开/宾馆/影子模拟）
│   ├── entity/       TouristEntity (extends PathfinderMob，非 WandscapeNpc)
│   └── internal/     TouristSpawnSystem/TouristMoveGoal/TouristSimulation/TouristState/
│                     TouristSimSystem/TouristShadow/TouristSimRegistry（游客卸载 sim：区块卸载时
│                     影子数据直线移动+交互+离开，加载时刷回实体）/HotelStayHandler/NarrativeGenerator/
│                     TavernApiImpl/TouristApiImpl
│                     （注：属性在 TouristEntity 字段中，无独立 data/ 子包；TouristApi 在 shared/api）
│
├── core/             ECS 核心框架（精简），纯 Java 21，零 MC 依赖
│   ├── ecs/          World + System + ComponentStore + CoreBootstrap
│   ├── component/    9 组件: Position/EquipmentComponent/TaskExecutor/NpcTaskQueue/Inventory/
│   │                 NavigationState/ColonyMember/ColonyMetadata/SuspensionContext
│   │                 （NPC 属性收敛为 6 个：MAX_HP/MOVE_SPEED/SPELL_POWER/WORK_SPEED/
│   │                 SPELL_SPEED/ARMOR_VALUE；装备加成仅加法）
│   ├── boundary/     8 个接口：BlockOps/EntityOps/RitualOps/MovementOps/ColonyResourceAccess/EventBus/ResourceAddedListener/ResourceShortageHandler
│   ├── event/        领域事件(SimpleEventBus) + TaskCompleted/CustomEvent/NarrativeEventTriggered
│   └── types/        基础record: GridPos/BlockType/ResourceId/EffectId/InteractAction/AttributeType/EquipmentSlot/EquipmentPreset/...
│
├── op/               原子操作系统（独立，原 core/op/）
│   ├── api/          AtomicOp sealed interface + ConditionEvaluator
│   └── executor/     OpExecutor 框架 + 注册表 + DefaultOpExecutors
│
├── task/             任务系统（整合原 core/task/ + core/system/调度 + task/network/ + shared/ui/task/）
│   ├── engine/dsl/   蓝图 DSL AST（BlueprintDefinition/ExprNode/StepNode/ParamType）
│   ├── engine/pool/  任务池（GlobalTaskPool/BuildingTaskPool）
│   ├── runtime/      运行时状态（ExecutorState/NpcTaskQueue/TaskSequence）
│   ├── scheduler/    调度系统（SchedulerSystem/TaskExecutionSystem）
│   ├── source/       TaskSource 接口 + 核心实现（TaskSourcePoller/EventDrivenTaskSource/PlayerManualSource/WorkbenchSource）
│   ├── client/       任务编辑器 GUI + 状态（原 shared/ui/task/）
│   └── network/      任务编辑器网络包（原 task/network/）
│
├── road/             道路系统（整合原 core/road/ + engine/road/ + road/）
│   ├── core/         纯数据模型（RoadNetwork/RoadNode/RoadEdge...）
│   ├── engine/       MC 实现（RoadSavedData/RoadApiImpl/RoadSegmentListener...）
│   ├── client/       编辑器客户端（原）
│   ├── network/      网络包（原）
│   └── server/       编辑器服务端（原）
│
├── engine/           MC 适配层（精简，road/ 移入 road/engine/）
│   ├── bootstrap/    EngineBootstrap — 一次性装配所有边界实现+TaskSource+系统
│   ├── boundary/     WandscapeBlockOps/WandscapeMovementOps/WandscapeRitualOps/WandscapeEntityOps + AsyncTransformExecutor + ResourceRequestExecutor
│   ├── source/       BuildingTaskSource(20tick轮询→发布TaskRequest) + BlueprintConfigLoader
│   ├── system/       ECS System（注册到World.tick()）NavigationSystem + ResourceSupplySystem
│   ├── service/      非ECS服务（EventBus订阅者）ColonyMetricsService + StatsService + AchievementService + SoundService(统一音效播放/节流)
│   │                 + ChunkLoadManager/ChunkLeaseData（建造时按需强加载建筑 footprint，殖民地区块卸载时照常施工）
│   ├── sound/        WandscapeSounds — 全部自定义 SoundEvent 注册点（逻辑id→sounds.json→音频）
│   └── transport/    ItemTransportManager (单实体视觉合并表现与自定义金边暗灰底气泡悬浮数量渲染)
│
├── shared/           所有包依赖的公共层
│   ├── api/          12 个模块接口(含 ColonyMetricsApi) + registry/WandscapeApis.java(静态定位器)
│   ├── data/         21+个record/enum(含ColonyMetricsSnapshot/WorkItem/BlueprintInfo/Emotion/...)
│   ├── event/        15 个 NeoForge 事件(模块间通信 + 模拟经营事件) + ColonyLevelUpEvent(record 回调，非总线)
│   ├── log/          Log 工具类 + LogFilter 运行时白名单过滤器
│   └── ui/           共享UI组件库(MedievalScreen MINIMAL风格/Button/ScrollableList/...)
│
├── building/         建筑管理(除扫描器外无自定义方块/BE，状态存于 SavedData；现有两个自定义方块/BE：
│                     creative_building_scanner 创造扫描器 + building_scanner 生存扫描器)。
│                     category: basic/node/storage/workstation/crafting_station/
│                               magic_station/shop/service/decoration/wonder/tavern
│                     系统: 每日结算(DailySettlementSystem)
│                           + 装饰辐射(DecorationBonusSystem) + 商店库存(ShopStockManager) + 奇观效果(WonderEffectApplier)
│   ├── client/       HotelScreen/ShopScreen/TavernScreen/TownHallScreen (MedievalScreen MINIMAL)
│   └── network/      建筑相关网络包
├── wand/             法杖物品+预设+NBT+JSON配方(新attributes[]格式)
├── element/          方块→元素映射 + 元素物品（获得即存入仓库）
├── npc/              NPC实体+ECS桥接(EntityComponentBridge)+渲染
├── warehouse/        GUI+ColonyItemBank(SavedData), 双标签页(Overview+Exchange)
├── production/       工作站(GUI/配方/菜单/网络包, wand_level已删除)
├── dataconfig/       JSON数据加载框架(WandscapeDataLoader)
├── command/          调试命令(/wandscape ...)
├── stats/            统计系统(统计数据采集/日快照/30天滚动摘要/网络同步)
│   ├── data/         ColonyDailySnapshot/ColonyStatsSummary
│   ├── internal/     StatisticsCollector/StatisticsData(SavedData)
│   └── network/      StatsSyncPacket
├── projection/       建筑投影/地面放置模式+调试检查
│   ├── client/       ProjectionClientState/ProjectionFlightController/ProjectionRenderer + BuildingDebug*
│   └── network/      ProjectionEnter*/Exit/Place + BuildingAction/DebugRequest/DebugResponse
├── overview/         俯瞰（鸟瞰）视角模式，V 打开面板默认进入
│   ├── client/       OverviewClientState/OverviewFlightController/OverviewRenderer
│   └── network/      OverviewInteractPacket
├── magic/            魔法阵粒子渲染（数据契约由 Web 编辑器导出，MC 端粒子消费）
│   ├── data/         MagicCircleSpec(record 镜像 + fromJson，纯数据)
│   ├── internal/     MagicCircleLoader(dataconfig 注册 magic_circles 类目)
│   └── client/       MagicCircleEmitter/DotParticle/RuneParticle
├── guard/            守卫任务系统(建筑周边怪物检测→守卫任务→NPC原地施法)
│   ├── executor/     GuardAttackExecutor(持续异步循环，施法→等光束→重选)
│   └── GuardZone/GuardTaskSource/GuardBlueprints/GuardConstants/GuardCommand
├── raid/             袭击机制(复用原版村庄袭击：玩家带不祥之兆近建筑10格→市政厅中心触发)
│   ├── RaidTriggerScanner(触发扫描器) + ColonyRaidTracker(胜利跟踪→事件)
│   └── RaidTownHall(市政厅定位) + MixinServerLevel(isVillage 钩子)
├── equipment/        （无独立包）装备系统是 cross-cutting：core/component/EquipmentComponent
│                     + core/types/(EquipmentSlot/AttributeType/EquipmentPreset/AttributeModifier)，
│                     桥接在 npc/internal/EntityComponentBridge
```

## 数据流（核心路径）

```
BuildingConfig JSON → BuildingConfigLoader
  → EnqueueHelper → WorkItem 入 BuildingState.taskQueue
  → BuildingTaskSource.poll(每20tick)
     ├─ ChunkLoadManager.leaseBuilding(强加载建筑 footprint，预算内)   ← 区块卸载时也施工
     └─ TaskRequest → task/engine/pool/GlobalTaskPool.addTask()
  → task/scheduler/SchedulerSystem(每2tick评分: proximity×0.5 + efficiency×0.3 + attributes×0.2)
  → NPC领取 → task/scheduler/TaskExecutionSystem
  → AtomicOp → op/executor/OpExecutor → engine/boundary/WandscapeBlockOps(MC世界实际方块操作)
  → emit_event → BuildCompleteListener → BuildingSavedData.structureIntact=true
  → head完成且队列排空 → ChunkLoadManager.releaseBuilding(区块可卸载)
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
  → 游客购物 purchase → 消耗货品 → ColonyItemBank入元素 ceil(价值×(1+profitRate))（如 bakery 0.3→1.3X）

装饰辐射 (每200tick):
  DecorationBonusSystem → 遍历功能建筑 → 曼哈顿距离内装饰累加
  → cap(建筑自身×100%) → BuildingContributionRegistry.getSnapshot()计入

每日结算:
  DailySettlementSystem → 每游戏日发 DailySettlementEvent
  → 触发商店补货(ShopStockManager) / 统计快照(StatisticsCollector)

奇观效果:
  WonderEffectApplier → 建筑intact+非shutdown → 应用modifier
  → StatModifier/PriceModifier/RuleUnlock → 全局效果
```

### 指标聚合数据流

```
ColonyMetricsService.getSnapshot(colonyId)   ← 统一查询入口
  → BuildingApi.getColonySnapshot(colonyId)         三值(单次遍历)
  → ColonyLevelManager                              等级/经验/名称
  → TouristApi                                      游客数/过夜/满意度
  → BuildingApi.getColonyBuildings(colonyId)        关停/损坏计数(一次遍历)
  → NpcApi.getNpcCount/getIdleNpcCount              NPC 数量
  → WarehouseApi.getAllElements(colonyId)            7 元素储量

消费者:
  PanelStateTracker          → ColonyStatsSyncPacket → 客户端 HUD
  PanelStateTogglePacket     → ColonyStatsSyncPacket → 面板首次打开
  AchievementService         → 条件达成时授予 vanilla 进度（data/wandscape/advancement/）
```

## 依赖规则

```
shared/          ← 所有包可见（API+事件+数据类）
core/            ← 所有包可见（纯Java，零MC依赖）
op/              ← 所有包可见（纯Java，零MC依赖，原子操作定义）
task/            ← 所有包可见（纯Java，零MC依赖，任务引擎/调度）
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
| ECS核心框架（组件/边界/事件/类型） | [packages/core.md](packages/core.md) |
| 原子操作（10种 AtomicOp + 执行框架） | [packages/op.md](packages/op.md) |
| 任务系统（引擎/调度/源/编辑器网络） | [packages/task.md](packages/task.md) |
| MC桥接/异步执行/方块操作/导航 | [packages/engine.md](packages/engine.md) |
| API接口/事件/数据类型/UI组件 | [packages/shared.md](packages/shared.md) |
| 建筑管理/SavedData/商店/装饰/奇观 | [packages/building.md](packages/building.md) |
| 游客实体/生成/移动/交互/离开 | [packages/tourist.md](packages/tourist.md) |
| NPC实体/ECS桥接/渲染 | [packages/npc.md](packages/npc.md) |
| 法杖物品/NBT | [packages/wand.md](packages/wand.md) |
| 元素映射 | [packages/element.md](packages/element.md) |
| 仓库GUI/ItemBank | [packages/warehouse.md](packages/warehouse.md) |
| 工作站/合成 | [packages/production.md](packages/production.md) |
| JSON加载框架 | [packages/dataconfig.md](packages/dataconfig.md) |
| 建筑预览/投影系统 | [packages/projection.md](packages/projection.md) |
| 俯瞰视角模式 | [packages/overview.md](packages/overview.md) |
| 道路系统（数据/算法/MC实现/编辑器） | [packages/road.md](packages/road.md) |
| 统计系统 | [packages/stats.md](packages/stats.md) |
| 装备系统 | [packages/equipment.md](packages/equipment.md) |
| 魔法阵模块设计 | [magic/magic.md](magic/magic.md) |
| 守卫任务系统 | [packages/guard.md](packages/guard.md) |
| 袭击机制 | [packages/raid.md](packages/raid.md) |
| 建筑JSON格式 | [data/buildings.md](data/buildings.md) |
| 魔法阵JSON格式 | [magic/magic-circles.md](magic/magic-circles.md) |
| 蓝图DSL格式 | [data/blueprints.md](data/blueprints.md) |
| 模拟经营（游客）详解 | [../docs/modules/tourist.md](../docs/modules/tourist.md) |
| 编码规范和反模式 | [conventions.md](conventions.md) |
