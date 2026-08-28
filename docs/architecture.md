# 架构总览（以真实代码为准）

本页描述当前源码的分层、装配流程、数据流与依赖规则。与旧 `architecture/README.md` 的差异见 [gaps.md](gaps.md)。

## 包地图

```
com.wsteam.wandscape
├── Wandscape.java         @Mod 入口：注册物品/实体/粒子/方块/BE/音效/网络包/命令，生命周期事件，ServerTick 驱动
├── WandscapeClient.java   客户端：按键绑定/面板/渲染事件注册
├── Config.java            NeoForge TOML 配置，所有可调参数
│
├── core/                  纯 Java 21，零 MC 依赖
│   ├── CoreBootstrap.java     装配 World/组件/边界/系统
│   ├── ecs/                   World + System + ComponentStore（轻量 ECS）
│   ├── component/             9 个组件（Position/TaskExecutor/NpcTaskQueue/Inventory/
│   │                          NavigationState/ColonyMember/ColonyMetadata/EquipmentComponent/SuspensionContext）
│   ├── boundary/              8 个边界接口（BlockOps/EntityOps/RitualOps/MovementOps/
│   │                          ColonyResourceAccess/EventBus/ResourceAddedListener/ResourceShortageHandler）
│   ├── event/                 SimpleEventBus + CustomEvent/TaskCompleted/NarrativeEventTriggered
│   └── types/                 基础 record/enum（GridPos/BlockType/ResourceId/AttributeType/EquipmentSlot…）
│
├── op/                     原子操作系统（纯 Java）
│   ├── api/                 AtomicOp sealed interface（10 种）+ ConditionEvaluator
│   └── executor/            OpExecutor 框架 + 注册表 + DefaultOpExecutors + ResourceShortageException
│
├── task/                   任务系统（纯 Java）
│   ├── engine/dsl/          蓝图 DSL AST + 解释器 + 编译器
│   ├── engine/pool/         GlobalTaskPool/BuildingTaskPool/BuildingTaskQueue/TaskRequest
│   ├── runtime/             ExecutorState/TaskState/TaskSequence/NpcTaskPackage
│   ├── scheduler/           SchedulerSystem/TaskExecutionSystem/SystemBlueprintSystem
│   └── source/              TaskSource + TaskSourcePoller/EventDrivenTaskSource/PlayerManualSource/WorkbenchSource
│
├── engine/                 MC 适配层（唯一实现 core 边界接口的层）
│   ├── WandscapeEngine.java   静态单例，持有 World/各 executor/SavedData
│   ├── bootstrap/EngineBootstrap.java   一次性装配
│   ├── boundary/             WandscapeBlockOps/AsyncTransformExecutor/WandscapeMovementOps/
│   │                         WandscapeRitualOps/WandscapeEntityOps/WandscapeBlockInteractExecutor/ResourceRequestExecutor
│   ├── colony/               ColonySavedData/ColonyLevelData/ColonyLevelManager + ColonyApiImpl
│   ├── nav/                  WandscapeNavigation
│   ├── service/              ChunkLoadManager/ColonyMetricsService/StatsService/AchievementService/
│   │                         GuideProgressService/ParticleService/SoundService
│   ├── sound/                WandscapeSounds + ColonyAmbientSystem
│   ├── source/               BuildingTaskSource + blueprint/BlueprintConfigLoader
│   ├── system/               NavigationSystem/ResourceSupplySystem
│   └── transport/            ItemTransportManager/TransportItemEntity/TransportStartPacket
│
├── shared/                 所有包可见的公共层
│   ├── api/                 12 个模块接口 + registry/WandscapeApis（静态定位器）+ WandscapeConstants + WandscapeDataRegistry
│   ├── data/                数据类（BuildingData/ColonyMetricsSnapshot/MageResume/ShopConfig/…）
│   ├── event/               16 个 NeoForge 事件（模块间通信 + 模拟经营事件）
│   ├── log/                 Log + LogFilter
│   ├── network/             PanelStateTracker/ColonyStatsSync/ColonyAmbient/Guide 系列/ParticleBurst 等包
│   ├── ui/                  UI 组件库（MedievalScreen/按钮/TabBar/TaskQueuePanel）+ 面板 + 新手引导 + Markdown 阅读器
│   └── client/              气泡渲染（SpeechBubble/TransientBubbleStore/SatisfactionBar）+ 建筑虚影
│
├── building/               建筑管理（含两个自定义方块/BE：扫描器）
│   ├── data/                BuildingConfig/BlockOffset
│   ├── internal/            生命周期监听/每日结算/装饰加成/商店库存/奇观效果/贡献注册
│   ├── scanner/             创造扫描器 + 生存扫描器（方块/BE/模式/导出）
│   ├── client/              市政厅/酒店/商店/酒馆/节点屏幕
│   └── network/             建筑相关网络包
│
├── wand/                   法杖物品 + 预设 + NBT + 施法
├── element/                方块→元素映射 + 种子值 + 审计
├── npc/                    NPC 实体 + ECS 桥接（EntityComponentBridge）+ 渲染 + 装备网络
├── tourist/                游客实体 + 行为 AI + 影子模拟 + 满意度 + 酒馆/酒店
├── warehouse/              元素银行（SavedData）+ 双标签 GUI + 运输
├── production/             工作站/合成站/魔法工坊 + 配方
├── road/                   道路（核心数据/算法/MC 实现/预设/客户端编辑器/网络）
├── projection/             灵魂投影建造模式
├── overview/               俯瞰视角模式
├── magic/                  魔法阵粒子 + 光束实体
├── guard/                  守卫任务 + 主动索敌 + 自卫反击
├── raid/                   袭击触发/胜利跟踪 + MixinServerLevel
├── integration/            第三方集成兼容（可选依赖，不打包；JEI 元素配方标签页）
│   └── jei/                 @JeiPlugin + 元素合成/分解/法杖/药剂配方分类（compileOnly）
├── stats/                  统计采集/日快照/摘要/同步
├── command/                /wandscape 调试命令
└── gametest/               元素审计 GameTest 入口
```

## 装配流程

1. **`Wandscape` 构造**：注册 DeferredRegisters（ITEM/ENTITY/PARTICLE/BLOCK/BE/SOUND/创意标签）→ 注册 NeoForge 事件订阅者 → 注册 API 实现（Building/Npc/Warehouse/Colony/Tourist/Tavern）→ 数据加载器注册（建筑/蓝图/道路预设/元素映射/魔法阵/配方）。
2. **`onRegisterPayloads`**：注册全部网络包（约 40 个 payload，playToClient/playToServer）。
3. **`onServerStarting`**：
   - `EngineBootstrap.bootstrap()` 装配 ECS World 与系统；
   - 注册 `ColonyMetricsService`、`GuideProgressService`、`BuildCompleteListener`、`DemolishCompleteListener`、`RoadSegmentListener`；
   - 装载 SavedData：`TaskPoolSavedData`、`RoadSavedData`、`TavernRecruitStorage`、`ColonyLevelData`；
   - 初始化 `ChunkLoadManager`、`TouristSimSystem`、`PlayerManualSource`；
   - 加载 `element_seeds.json` 种子值。
4. **`onServerTick`**：依次驱动 async 方块操作、异步交互、仪式冷却、物品运输、资源请求节流、守卫战斗、自卫、袭击扫描，然后 `EntityComponentBridge.syncPositions` → `world.tick(1.0f)`（引擎逻辑 + 事件派发）。

## 依赖规则

```
shared/ ← 所有包可见（API + 事件 + 数据类）
core/   ← 所有包可见（纯 Java，零 MC 依赖）
op/     ← 所有包可见（纯 Java）
task/   ← 所有包可见（纯 Java）
engine/ ← 实现 core 边界接口，持有 MC 引用
building/wand/element/npc/… ← 通过 WandscapeApis + NeoForge EventBus 通信
```

- `core/` 禁止 import MC 类、禁止持有运行时状态。
- 跨模块通信只走 `WandscapeApis.getXxxApi()` 与 NeoForge EventBus；`Shared` 事件仅通知，需要顺序时用 API。
- 方块操作唯一入口：`BuildingTaskSource` → `TaskRequest → GlobalTaskPool`。
- 任务分发只走 `TaskRequest → GlobalTaskPool → SchedulerSystem`。
- 游客是短居访客，无职业/床位/住宅/状态机；`TouristState` 是移动状态标记，不是状态机。

## 核心数据流

### 建筑施工流

```
BuildingConfig JSON → BuildingConfigLoader
  → placeBuilding → EnqueueHelper.buildWorkItem（旋转/算料/映射 blueprint $bind）
  → BuildingState.taskQueue（WorkItem）
  → BuildingTaskSource.poll（每 20 tick）
       ├─ ChunkLoadManager.leaseBuilding（强加载 footprint，预算内）
       └─ TaskRequest → GlobalTaskPool.addTask()
  → SchedulerSystem（每 2 tick 评分）→ NPC 领取
  → TaskExecutionSystem → AtomicOp → OpExecutor → WandscapeBlockOps（MC 世界方块）
  → emit CustomEvent build_complete → BuildCompleteListener（比对 palette 派生 map → 完成/修复）
  → head 完成且队列排空 → ChunkLoadManager.releaseBuilding
```

### 模拟经营流

```
游客生成（每日清晨窗口）:
  TouristSpawnSystem → RoadSavedData 道路端点 → TouristEntity spawn
  → TouristMoveGoal（沿路移动，宏观导航）→ 建筑锚点（微观导航）
  → TouristSimulation.performShopInteraction / service 交互
  → 满意度（三值 vs 阈值）→ 夜晚离开 / 入住酒店 / 满满意法师留简历

商店运作（每日清晨）:
  ShopStockManager.restock → ColonyItemBank 扣元素 → 填充库存
  → 游客购物 purchase → 扣库存 → 按 (1+profitRate) 向 ColonyItemBank 入元素
  → 缺货 → ResourceSupplySystem.enqueueSynthesize 补货

每日结算:
  DailySettlementSystem → 每游戏日发 DailySettlementEvent（SettlementReport(colonyId, day)）
  → ShopStockManager 商店补货 + StatisticsCollector 统计快照 订阅

装饰加成（每 200 tick）:
  DecorationBonusSystem → 曼哈顿距离内装饰累加三值
  → 封顶 min(累计, 目标基础×bonusCap) → BuildingContributionRegistry 计入

奇观效果:
  WonderEffectApplier → 完整的 wonder 建筑 → StatMod/PriceMod/RuleUnlock 全局效果
```

### 指标聚合流

```
ColonyMetricsService.getSnapshot(colonyId)
  → BuildingApi.getColonySnapshot（三值，单次遍历）
  → ColonyLevelManager（等级/经验/名称）
  → TouristApi（游客数/过夜/满意度）
  → BuildingApi.getColonyBuildings（建造中计数）
  → NpcApi（NPC 总数/空闲数）
  → WarehouseApi.getAllElements（7 元素储量）
  → ColonyMetricsSnapshot
消费者：PanelStateTracker → ColonyStatsSyncPacket → 客户端 HUD；AchievementService → 原版进度
```

## 与旧 architecture/README.md 的主要差异

详细清单见 [gaps.md](gaps.md)。要点：

1. `tourist/data/TouristAttributes` 已不存在，游客属性在 `TouristEntity` 字段中；`TouristState` 移到 `tourist/internal/`。
2. `equipment/` 包不存在——装备是 cross-cutting（`core/component/EquipmentComponent` + `core/types/`，桥接在 `npc/internal/`）。
3. `ColonyLevelUpEvent` 是 record 回调（`levelUpCallback`），不是 NeoForge 总线事件。
4. 建筑系统现含自定义方块/BE（扫描器），不再是"零自定义方块"。
5. 新手引导步骤硬编码在 `GuideRegistry`（10 步），`assets/wandscape/guide/*.md` 仅服务游戏内 Markdown 文档阅读器。
6. `ResourceRequestExecutor` 实际每 5 tick 发一件（非 1/tick）。
