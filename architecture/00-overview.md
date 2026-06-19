# 项目总览

## 技术栈

| 层 | 技术 | 版本 |
|----|------|------|
| MC 平台 | NeoForge | 1.21.1 |
| Java | JDK | 21 |
| 构建 | Gradle + moddev | 2.0.141 |
| 序列化 | MC 原生 NBT（CompoundTag） | — |
| 配置 | JSON (data/wandscape/) + TOML (ModConfigSpec) | — |
| 注册 | DeferredRegister | — |
| 通信 | NeoForge EventBus | — |

## 包结构

### 核心引擎（纯 Java，零 MC 依赖）

```
org.magiccolony.core/
├── Engine.java                       # 引擎引导：bootstrap() → World
├── EngineConfig.java                 # 引导配置：边界实现 + 蓝图注册表
├── Log.java                          # 核心层日志
├── TemplateResolver.java             # 模板变量解析器
│
├── boundary/                         # 适配层边界接口（5 个）
│   ├── BlockOps.java                 #   方块放置/破坏/查询
│   ├── EntityOps.java                #   实体效果/位置查询
│   ├── RitualOps.java                #   仪式引导/轮询
│   ├── ColonyResourceAccess.java     #   殖民地仓库资源 CRUD
│   └── EventBus.java                 #   事件总线接口
│
├── component/                        # ECS 组件（7 种）
│   ├── Position.java                 #   位置
│   ├── ManaPool.java                 #   魔力池
│   ├── WandCarrier.java              #   法杖能力并集
│   ├── TaskExecutor.java             #   NPC 任务执行状态
│   ├── Inventory.java                #   NPC 背包
│   ├── ColonyMember.java             #   殖民地成员
│   └── ColonyMetadata.java           #   殖民地元数据
│
├── ecs/                              # ECS 框架
│   ├── World.java                    #   世界容器 + tick()
│   ├── System.java                   #   系统接口
│   ├── ComponentStore.java           #   组件存储接口
│   └── HashMapComponentStore.java    #   组件存储实现
│
├── event/                            # 引擎事件 + SimpleEventBus
│   ├── SimpleEventBus.java           #   内存事件总线（延迟取消订阅）
│   ├── CustomEvent.java              #   自定义事件
│   ├── ResourceLow.java              #   资源不足
│   ├── ResourceFulfilled.java        #   资源恢复
│   ├── TaskCompleted.java            #   任务完成
│   ├── TaskAwaitingResources.java    #   任务等待资源
│   └── MobNearby.java                #   怪物出现
│
├── op/                               # 原子操作 + 执行器
│   ├── AtomicOp.java                 #   sealed 接口 + 7 种变体
│   ├── OpExecutor.java               #   执行器接口
│   ├── OpExecutorRegistry.java       #   执行器注册表
│   ├── OpResult.java                 #   执行结果枚举
│   ├── DefaultOpExecutors.java       #   默认执行器实现
│   └── ConditionEvaluator.java       #   条件求值器接口
│
├── system/                           # 系统（6 个，按注册顺序执行）
│   ├── ManaRegenSystem.java          #   魔力恢复
│   ├── SystemBlueprintSystem.java    #   系统蓝图驱动
│   ├── TaskSourcePoller.java         #   任务来源轮询
│   ├── SchedulerSystem.java          #   调度器（2 tick 心跳）
│   ├── TaskExecutionSystem.java      #   任务执行（逐 tick）
│   ├── EventDrivenTaskSource.java    #   事件驱动的任务来源
│   ├── TaskSource.java              #   任务来源接口
│   ├── WarehouseSource.java          #   仓库自动补货来源
│   ├── WorkbenchSource.java          #   工作站任务来源
│   ├── PlayerManualSource.java       #   玩家手动来源
│   └── SystemBlueprintRegistry.java  #   系统蓝图注册表
│
├── task/                             # 任务系统
│   ├── TaskRequest.java              #   任务请求
│   ├── TaskState.java                #   任务生命周期枚举
│   ├── TaskSequence.java             #   任务步骤序列
│   ├── GlobalTask.java               #   全局任务记录
│   ├── GlobalTaskPool.java           #   全局任务池
│   ├── ExecutorState.java            #   NPC 执行器状态
│   ├── InterruptRecord.java          #   中断记录
│   ├── ApprovalInfo.java             #   审批信息
│   ├── Blueprint.java                #   蓝图定义
│   ├── BlueprintSteps.java           #   蓝图步骤生成器
│   ├── BlueprintRegistry.java        #   蓝图注册表
│   ├── CompiledBlueprint.java        #   编译后的蓝图
│   ├── TriggerDeclaration.java       #   事件触发声明
│   └── TaskCompiler.java             #   任务编译器接口
│
├── types/                            # 基础值类型（10 种）
│   ├── BehaviourTag.java             #   行为标签枚举（8 值）
│   ├── BehaviourLevel.java           #   行为等级 1-5
│   ├── BlockType.java                #   方块类型
│   ├── EffectId.java                 #   效果 ID
│   ├── EntityId.java                 #   实体 ID
│   ├── GridPos.java                  #   方块坐标 (x,y,z)
│   ├── InteractAction.java           #   交互动作枚举
│   ├── ResourceId.java               #   资源标识
│   ├── ResourceStack.java            #   资源堆栈
│   └── RitualId.java                 #   仪式 ID
│
└── demo/
    └── MockBoundary.java             #   全部边界接口的 headless mock
```

### Wandscape MC 适配层

```
com.wsteam.wandscape/
├── Config.java                      # TOML 配置定义 (ModConfigSpec)
├── Wandscape.java                   # 主类 (@Mod) + 临时示例注册
├── WandscapeClient.java             # 客户端初始化
│
├── shared/                          # [01] shared-api — 接口/事件/枚举
│   ├── api/                         #   所有模块 API 接口定义
│   ├── event/                       #   NeoForge 事件类
│   ├── data/                        #   枚举 + record 数据类型
│   ├── registry/                    #   WandscapeApis + DataRegistry 接口
│   └── bridge/                      #   核心引擎 ↔ MC 类型映射桥
│
├── wand/                            # [02] wand-system — 法杖
│   ├── item/                        #   法杖物品注册
│   └── internal/                    #   NBT 读写、能力并集实现
│
├── element/                         # [03] element-system — 元素
│   └── internal/                    #   元素定义、映射加载、检索实现
│
├── warehouse/                       # [04] warehouse-system — 仓库
│   ├── block/                       #   仓库方块注册
│   ├── be/                          #   仓库 BE (存储 + 队列)
│   ├── screen/                      #   仓库 GUI Screen
│   └── internal/                    #   仓库 API 实现
│
├── operation/                       # [05] atomic-operations — 原子操作
│   └── internal/                    #   A/B/C/D 执行器实现
│
├── task/                            # [06] task-system — 任务调度
│   └── internal/                    #   任务池、调度器、生命周期实现
│
├── npc/                             # [07] npc-system — NPC
│   ├── entity/                      #   NPC 实体注册 + 属性
│   └── internal/                    #   NPC API 实现
│
├── building/                        # [08] building-core — 建筑核心
│   ├── block/                       #   建筑方块注册
│   ├── be/                          #   AbstractWandscapeBE + 各建筑 BE
│   ├── screen/                      #   建筑 GUI 基类
│   ├── data/                        #   BuildingConfig JSON 解析
│   └── internal/                    #   建筑 API 实现
│
├── node/                            # [09] node-building — 节点建筑
│   └── internal/                    #   节点采集任务生成
│
├── station/                         # [10] production-stations — 生产站
│   ├── block/                       #   工作站/制作站/魔药站方块
│   ├── screen/                      #   工作站/制作站/魔药站 GUI
│   └── internal/                    #   配方匹配、制作执行
│
├── housing/                         # [11] housing-mana-pool — 房屋+魔力池
│   ├── block/                       #   房屋方块 + 魔力池方块
│   ├── be/                          #   房屋 BE + 魔力池 BE
│   └── internal/                    #   HouseApi + ManaPoolApi 实现
│
├── tavern/                          # [12] tavern-recruitment — 酒馆
│   ├── block/                       #   酒馆方块
│   ├── screen/                      #   招募 GUI
│   └── internal/                    #   候选人生成、TavernApi 实现
│
├── ritual/                          # [13] ritual-altar — 仪式祭坛
│   ├── block/                       #   祭坛方块注册
│   ├── be/                          #   祭坛 BE + 多方块检测
│   └── internal/                    #   仪式执行逻辑
│
├── panel/                           # [14] management-panel — 管理面板
│   ├── screen/                      #   管理面板 GUI + 小地图渲染
│   └── internal/                    #   远程建造/管理逻辑
│
├── colony/                          # [15] colony-lifecycle — 殖民地
│   └── internal/                    #   殖民地创建/删除、ColonyApi 实现
│
└── dataconfig/                      # [16] data-driven-config — JSON 配置加载
    └── internal/                    #   JsonReloadListener、数据校验
```

> **维护规则**：新增模块时在此文件中添加对应的包路径注释（`# [NN] module-name — 说明`）。删除模块时移除对应行。包路径不写具体类名。

## 核心概念速查

| 概念 | 含义 | 定义位置 |
|------|------|---------|
| 法杖 (Wand) | NPC/玩家携带物品，NBT 定义行为标签和等级，永不损坏 | docs/02-wand-system.md |
| 行为标签 (Behavior Tag) | 法杖 NBT 键值对（如 `building:3`），声明能力领域+等级 | docs/02-wand-system.md |
| 元素 (Element) | 抽象资源，3 层 9 种，替代实体建材。存储于殖民地仓库 | docs/03-element-system.md |
| 原子操作 (Atomic Op) | NPC 工作最小单元：A 方块转化、B 建筑交互、C 实体交互、D 仪式 | docs/05-atomic-operations.md |
| 全局任务池 | 殖民地级别任务队列，调度器每 2s 分配一轮 | docs/06-task-system.md |
| 私有池 | 每个 NPC 自己的任务队列，优先级高于全局任务 | docs/06-task-system.md |
| 建筑队列 | 每个建筑内部 FIFO 队列，逐个发布到全局池 | docs/08-building-core.md |
| 三数值 | 舒适值/魔法值/奇观值，由建筑首次建造提供，驱动发展 | docs/08-building-core.md |
| 维护成本 | 建筑定期消耗木元素，扣到负数自动关停 | docs/08-building-core.md |
| WandscapeApis | 静态 API 注册表，各模块初始化时注册自己的 API 实现 | docs/01-shared-api.md |

## 阶段状态

| 阶段 | 状态 | 完成内容 |
|------|------|---------|
| 0 — 地基 | ✅ 完成 | 01-shared-api 全部类型系统 + 16 data-driven-config 框架 |
| core-engine | ✅ 已合并 | ECS + Blueprint + AtomicOp + Scheduler + 63 测试通过 |

| 1 - 02/03 | ✅ 完成 | 02 wand-system (WandItem, WandApiImpl, WandPresetLoader) + 03 element-system (ElementApiImpl, ElementMappingLoader, 5 JSON 映射) |

**当前**：阶段 1 继续 — 08 building-core (建筑核心)
