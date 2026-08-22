# Wandscape 模组文档

本目录基于**真实源码**编写（`src/main/java/com/wsteam/wandscape/`），以代码为最终事实来源。注释仅作参考；发现注释/旧设计文档与实现不符之处，已在 [gaps.md](gaps.md) 中列出。

> 旧 `docs/` 已全部删除重建。`architecture/` 目录保留为结构快照，但其中过时内容以本目录为准。

## 模组概览

Wandscape 是一个 Minecraft NeoForge 1.21.1 模组，包含两大系统：

1. **殖民地自动化** — 玩家放置建筑，NPC 法师通过法杖执行原子操作（建造/采集/合成/守卫），殖民地自治运转。
2. **模拟经营（游客经济）** — 游客沿道路来访，在商店/服务建筑消费，满意度影响殖民地等级与经验，法师游客可被酒馆招募为 NPC。

技术栈：NeoForge 1.21.1、Java 21、纯 Java ECS 内核、JSON 数据驱动（建筑/蓝图/元素/配方/魔法阵/叙事）。

## 文档地图

| 想看什么 | 打开 |
|---|---|
| 分层架构、数据流、依赖规则 | [architecture.md](architecture.md) |
| ECS 核心（World/System/组件/边界接口/类型） | [modules/core.md](modules/core.md) |
| 原子操作系统（10 种 AtomicOp + 执行框架） | [modules/op.md](modules/op.md) |
| 任务系统（蓝图 DSL/任务池/调度/任务源） | [modules/task.md](modules/task.md) |
| 引擎适配层（bootstrap/边界实现/寻路/服务/音效/运输） | [modules/engine.md](modules/engine.md) |
| 建筑模块（生命周期/每日结算/装饰/商店/奇观/扫描器） | [modules/building.md](modules/building.md) |
| 游客模块（生成/移动/影子模拟/满意度/酒店/酒馆/叙事） | [modules/tourist.md](modules/tourist.md) |
| 游客偏好系统（三值需求/画像/精力/钱包/spot 排队/目标选择评分） | [simulation.md](simulation.md) |
| NPC 模块（实体/ECS 桥接/7 属性/装备/渲染） | [modules/npc.md](modules/npc.md) |
| 道路模块（路网/算法/铺路/样条编辑器/物品运输） | [modules/road.md](modules/road.md) |
| 法杖模块（物品/NBT/预设/施法） | [modules/wand.md](modules/wand.md) |
| 元素模块（方块→元素映射/种子值/分解） | [modules/element.md](modules/element.md) |
| 生产模块（工作站/合成站/魔法工坊/配方） | [modules/production.md](modules/production.md) |
| 仓库模块（元素银行/双标签 GUI/运输） | [modules/warehouse.md](modules/warehouse.md) |
| 魔法模块（魔法阵粒子/光束实体） | [modules/magic.md](modules/magic.md) |
| **NPC 施法决策层（多魔法规划，未实现）** | [spell-casting.md](spell-casting.md) |
| 守卫模块（守卫任务/主动索敌/自卫反击） | [modules/guard.md](modules/guard.md) |
| 袭击模块（复用原版袭击/市政厅为中心） | [modules/raid.md](modules/raid.md) |
| 灵魂投影建造模式 | [modules/projection.md](modules/projection.md) |
| 俯瞰视角模式 | [modules/overview.md](modules/overview.md) |
| 统计模块（日快照/30 天滚动摘要） | [modules/stats.md](modules/stats.md) |
| 共享层（12 接口/数据类/事件/UI 组件/新手引导/Markdown 阅读器/网络包） | [modules/shared.md](modules/shared.md) |
| JSON 数据加载框架（WandscapeDataLoader） | [modules/dataconfig.md](modules/dataconfig.md) |
| 调试命令（`/wandscape`） | [modules/command.md](modules/command.md) |
| 建筑 JSON 格式 | [data/buildings.md](data/buildings.md) |
| 蓝图 DSL JSON 格式 | [data/blueprints.md](data/blueprints.md) |
| 元素映射/种子 JSON 格式 | [data/element_mappings.md](data/element_mappings.md) |
| 合成配方 JSON 格式 | [data/craft_recipes.md](data/craft_recipes.md) |
| 魔法阵 JSON 格式 | [data/magic_circles.md](data/magic_circles.md) |
| 道路 JSON（模板/规则/等级） | [data/road.md](data/road.md) |
| 叙事 JSON 格式 | [data/narratives.md](data/narratives.md) |
| architecture 与代码差异 / 未接线点 / 已知问题 | [gaps.md](gaps.md) |
| 游戏内指南书文体规范（写作标尺） | [guide_style.md](guide_style.md) |

## 代码发现路径

- 包结构：`src/main/java/com/wsteam/wandscape/<模块>/`
- 注册入口：`Wandscape.java`（物品/实体/粒子/方块/音效/网络包/命令/API 装配）
- 引擎装配：`engine/bootstrap/EngineBootstrap.java` → `core/CoreBootstrap.java`
- 服务端 tick 驱动：`Wandscape.onServerTick`
- API 定位器：`shared/registry/WandscapeApis.java`
- 常量：`shared/registry/WandscapeConstants.java` + `Config.java`（TOML 可调参数）

## 分层一览

```
core/      纯 Java 21，零 MC 依赖（ECS/边界接口/原子操作类型）
op/        纯 Java，原子操作定义与执行框架
task/      纯 Java，任务引擎/调度/任务源
shared/    所有包可见（API 接口 + 数据类 + 事件 + 日志 + UI 组件库）
engine/    MC 适配层，实现 core 边界接口，唯一持有 MC 引用的实现层
building/wand/element/npc/warehouse/production/tourist/road/…  通过 WandscapeApis + EventBus 通信
```

## 关键数据流

1. **建筑施工**：`BuildingConfig JSON → BuildingConfigLoader → EnqueueHelper → BuildingState.taskQueue → BuildingTaskSource(20tick) → GlobalTaskPool → SchedulerSystem(评分) → NPC → TaskExecutionSystem → AtomicOp → WandscapeBlockOps(实际方块) → CustomEvent build_complete → BuildCompleteListener`
2. **游客经济**：`TouristSpawnSystem(每日清晨) → RoadSavedData(道路) → TouristEntity → TouristMoveGoal(沿路) → Shop/Service 交互 → 满意度 → ColonyLevelManager 经验 → 法师游客(5%) → TavernRecruitStorage → 招募成 NPC`
3. **指标聚合**：`ColonyMetricsService.getSnapshot → BuildingApi + ColonyLevelManager + TouristApi + NpcApi + WarehouseApi → ColonyStatsSyncPacket → 客户端 HUD`
