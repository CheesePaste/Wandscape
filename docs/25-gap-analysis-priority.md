# 差距分析与优先级排序

文档编号：NEW-25
版本：1.0
状态：全项目审视 — 已实现 vs 未实现 vs 缺失设计
日期：2026-06-20

---

## 一、当前实现状态总览

### 1.1 已完成的模块

| 模块 | 状态 | 核心产出 |
|------|------|---------|
| `core/` 引擎 | ✅ 完整 | ECS World, 7 组件, 7 种 AtomicOp, GlobalTaskPool, SchedulerSystem, TaskExecutionSystem, Blueprint DSL (21 表达式 + 12 步骤类型), 领域事件总线, Trigger 事件→任务链 |
| `engine/` 桥梁 | ✅ 完整 | 5 个边界接口 MC 实现, AsyncTransformExecutor, BuildingTaskSource, BlueprintConfigLoader |
| 02 wand-system | ✅ 基础 | WandItem, 4 种法杖 preset JSON, WandApiImpl |
| 03 element-system | ✅ 基础 | 5 种元素映射 JSON, ElementApiImpl |
| 07 npc-system | ✅ 完整 | WandscapeNpc (PathfinderMob), EntityComponentBridge (MC↔ECS), NpcApiImpl, 客户端渲染+施法粒子, spawn egg |
| 08 building-core | ✅ 完整 | 4 种建筑 (town_hall/forest_node/earth_node/grand_tower), AbstractWandscapeBE (FIFO 队列+NBT 持久化), BuildingApiImpl, BuildingConfigLoader, BlockPlaceHandler, EnqueueHelper |
| 16 data-config | ✅ 基础 | WandscapeDataLoader + WandscapeDataRegistry 泛型框架 |
| Commands | ✅ 4 个 | FillBuilding, PublishBlueprint, StressTest, export-structure |

### 1.2 部分实现的模块

| 模块 | 已完成 | 缺失 |
|------|--------|------|
| 05 atomic-operations | TransformOp, BlockInteractOp, EmitEventOp, IfConditionOp, ResourceRequestOp, RitualOp(self_teleport) 均可执行 | EntityInteractOp stub, WandscapeEntityOps.getPosition/applyEffect 空操作, 传送以外的仪式 |
| 06 task-system | GlobalTaskPool + 调度器完整 | WarehouseSource/WorkbenchSource/PlayerManualSource stub, EventDrivenTaskSource 无实际事件源 |
| 15 colony-lifecycle | ColonyMember/ColonyMetadata ECS 组件存在 | 无 ColonyApi 实现, 无殖民地创建/删除流程, 无维护结算, ColonyResourceAccess stub |

### 1.3 完全未实现的模块

| 模块 | 设计文档 | 备注 |
|------|---------|------|
| 04 warehouse-system | docs/04 ✅ | ✅ **已完成** — WarehouseBE (物品存储+GUI+NBT+reserve/commit/release), WarehouseManager (implements WarehouseApi + ColonyResourceAccess), ResourceInsufficientEvent + 聊天栏通知 |
| 09 node-building | docs/09 ✅ | ForestNodeBE/EarthNodeBE 已注册但无自动采集逻辑, 无元素产出 |
| 10 production-stations | docs/10 ✅ | 工作站/制作站/魔药站 — **零实现** |
| 11 housing-mana-pool | docs/11 ✅ | 房屋绑定/魔力池 — **零实现** |
| 12 tavern-recruitment | docs/12 ✅ | 酒馆/NPC 招募 — **零实现** |
| 13 ritual-altar | docs/13 ✅ | 多方块检测, 复活仪式等 — **零实现** (仅 self_teleport) |
| 14 management-panel | docs/14 ✅ | 殖民地管理 GUI + 小地图 + 远程建造 — **零实现** |
| 18 structure-export | docs/18 ✅ | 游戏内命令导出建筑结构 — **零实现** |

### 1.4 已定义但无实现的 API 接口

```
ColonyApi     — 接口存在，WandscapeApis 中从未注册
HouseApi      — 接口存在，从未注册
ManaPoolApi   — 接口存在，从未注册
TavernApi     — 接口存在，从未注册
WarehouseApi  — ✅ 已注册 (WarehouseManager)
TaskApi       — 接口存在，从未注册
```

---

## 二、路线图之外的缺失系统

以下是 `docs/17-development-roadmap.md` 五阶段计划中**未覆盖**、但对殖民地模拟模组至关重要或极具差异化的系统。

### 2.1 P0 — 核心闭环（缺了玩不了）

#### 2.1.1 殖民地创建与所有权

**现状**：ColonyMember/ColonyMetadata 组件定义在 core 中，但没有任何 MC 层实现。放置 town_hall 不会创建殖民地。没有 `ColonyApi` 实现，没有 colony UUID 生成，没有所有权记录（哪个玩家拥有这个殖民地）。

**影响**：整个 "殖民地管理" 概念缺失——没有殖民地就没有仓库归属、没有 NPC 归属、没有维护结算。

**建议**：这是模块 15 的核心交付，应该在仓库（04）之前完成，因为仓库需要 colonyId 做隔离。

#### 2.1.2 仓库系统

**现状**：`ColonyResourceAccess` 边界接口是 stub。没有仓库方块、没有 BE、没有 GUI、没有元素/物品实际存储。`ResourceRequestOp` 在 `TaskExecutionSystem.handleResourceRequest()` 中因 `colonyResources.hasEnough()` 永远返回 false，所以任何需要物品/元素的蓝图都会卡在 `AWAITING_RESOURCES`。

**影响**：整个经济循环不存在。NPC 只能执行不消耗资源的纯方块放置任务。

**建议**：优先实现元素存储（内存 Map + NBT 持久化），物品存储可后置。仓库 BE 继承 `AbstractWandscapeBE` 非常简单——主要工作量在 GUI 和差量持久化。

#### 2.1.3 节点自动采集

**现状**：ForestNodeBE 和 EarthNodeBE 注册了，但 `BuildingTaskSource.poll()` 找不到它们——因为 BE 的队列永远是空的。没有逻辑在闲置时自动 publish 采集任务。

**影响**：没有元素来源 → 仓库永远是空的 → 配方执行不了 → 经济循环断裂。

**建议**：在 BE 的 tick（或 TaskSource 轮询）中检测 `hasWork()==false && isShutdown()==false`，自动调用 `EnqueueHelper` 入队 node_gathering 任务。这只需要 ~20 行代码。

#### 2.1.4 维护结算

**现状**：BuildingConfig 有 `maintenance_cost` 字段，但没有任何代码定时扣除。建筑关停逻辑在 BE 中有 shutdown 标记，但触发条件未实现。

**影响**：建筑永不消耗资源 → 资源只增不减 → 殖民地没有压力。

**建议**：在 ColonyMetadata 上挂一个维护定时器（如每 20 分钟），扫描殖民地所有建筑 × maintenance_cost，调用 `ColonyResourceAccess.reserve()` 扣除，不足则 shutdown。

### 2.2 P1 — 殖民地深度（差异化竞品）

#### 2.2.1 NPC 生命循环与坟墓

**现状**：NPC 可以死亡（`RemovalReason.KILLED`），死亡时任务重新分配。但没有：死亡原因系统、坟墓/墓碑、复活机制（复活仪式在 13 模块，未实现）。

**影响**：NPC 死了就没了。玩家看不到尸体，拿不回装备，无法复活。硬核过头。

**建议**：
- 短期：NPC 死亡 → 生成墓碑实体（坐标=死亡位置）→ 背包物品保存在墓碑 NBT 中
- 中期：祭坛复活仪式读取墓碑 NBT → 重新生成 NPC → 归还背包
- 长期：不同死亡原因（摔落/岩浆/怪物）不同的复活成本和墓碑类型

#### 2.2.2 NPC 成长/技能系统

**现状**：NPC 有 `spellPower` 字段，但永不改变。没有经验、升级、技能树概念。

**影响**：NPC 是同质化的工具人。玩家没有"培养"的情感连接。模拟殖民地的核心乐趣之一是看着小法师从菜鸟成长为大师。

**建议**：
- NPC 每完成 N 个任务 → spellPower+1（上限 10）
- 不同行为标签对应不同技能：`BUILDING`→建筑专精 / `GATHERING`→采集专精 / `RITUAL`→仪式专精
- 技能影响：spellPower 加速引导时间，提高产出倍率，降低魔力消耗
- 升级时播放粒子+音效

#### 2.2.3 NPC 需求与幸福度

**现状**：NPC 没有食物、休息、社交需求。它们是永动机。

**影响**：NPC 不像 "村民"，像机器人。模拟殖民地类模组中 NPC 幸福度系统是标配。

**建议**：
- 三类需求：饱食度（定期消耗食物）、休息度（定期返回房屋）、社交度（与其他 NPC 距离近时恢复）
- 幸福度 = 加权平均 → 影响工作效率（0.5x ~ 1.5x）
- 幸福度归零 → NPC 罢工/离开殖民地
- 房屋绑定 NPC 休息恢复 +200%

#### 2.2.4 建筑升级/科技树

**现状**：BuildingConfig 有 `unlock_requirement.min_wonder`，但没有实际的解锁检查逻辑，也没有建筑升级路径。

**影响**：所有建筑在开局就是最终形态。没有 progression 感觉。

**建议**：
- 多 tier 建筑 JSON（如 `town_hall_t1` → `town_hall_t2` → `town_hall_t3`）
- 升级条件：wonder≥N + 资源充足 → NPC 执行升级任务（拆除旧 pattern + 放置新 pattern）
- 升级后：更大的 pattern、更高的三数值产出、更大的队列容量
- 科技树可对玩家可视化（管理面板的一个 tab）

### 2.3 P2 — 交互与反馈

#### 2.3.1 世界交互的 NPC AI

**现状**：NPC AI 只有 FloatGoal + RandomStrollGoal。NPC 在空闲时随机走动，工作时锁定在原地。

**缺失**：
- 无战斗 AI（遇到怪物怎么办？）
- 无社交 AI（NPC 之间互动？）
- 无环境感知（避开危险方块、绕开水/岩浆）
- 无工作动画序列（不只是举法杖，是走位→瞄准→施法→完成→下一个）

**建议**：阶段 2 留下即可，后续版本逐项添加。

#### 2.3.2 NPC 任务工作可视化

**现状**：NPC 工作时有法杖光束指向目标方块。但缺少：
- 任务进度条（头顶或粒子环显示剩余 steps）
- 不同 Op 类型的不同光束颜色/粒子
- 完成时音效
- 失败/中断时反馈

**建议**：扩展 `WandscapeNpc.doWorkAnimation()` + `DATA_CASTING` 同步数据。

#### 2.3.3 音效系统

**现状**：整个模组没有任何自定义音效。

**缺失**：法杖施法音效、方块转化音效、NPC 对话/工作/死亡音效、仪式环境音、GUI 点击音效

**建议**：在 `src/main/resources/assets/wandscape/sounds/` 注册 SoundEvent，逐步添加。

#### 2.3.4 进度/指南书

**现状**：无教程，无指南，玩家靠 JEI 和命令摸索。

**建议**：
- 短期：Patchouli 指南书（《法师殖民地手册》），章节对应模块
- 中期：游戏内任务/成就（Advancement trigger）引导玩家放市政厅→建节点→招 NPC
- 长期：自定义指南书系统（不依赖 Patchouli）

### 2.4 P3 — 宏大愿景（差异化核心）

#### 2.4.1 殖民地自动化规则系统

**现状**：TriggerDeclaration + EmitEventOp 提供了底层引擎支持——"事件 X → 创建任务 Y"。但这个规则对玩家不可见、不可配置。

**愿景**：玩家在管理面板中创建自动化规则，类似 Factorio 的 logistics network 条件：

```
IF 仓库.木 < 128 THEN 创建 ForestNode.gather(64)
IF 仓库.石 > 512 THEN 创建 工作站.decompose(石, 128)
WHEN 怪物靠近 THEN 创建 防御任务 ×3
```

每个殖民地可有自己的规则集，存储为 JSON。

**优先级**：这是让模组从 "遥控 NPC" 跃升为 "自治殖民地" 的关键差异点。

#### 2.4.2 多殖民地和殖民地间物流

**现状**：ColonyMember 有 colonyId，但一个玩家只有一个殖民地。无跨殖民地资源调配。

**愿景**：
- 玩家可创建多个殖民地（每殖民地一座市政厅）
- 殖民地间可建立 "贸易路线"（NPC 定期往返运送指定资源）
- 殖民地专精化：殖民地 A 专产石材，殖民地 B 专产木材，自动调配
- 管理面板显示所有殖民地概览

#### 2.4.3 外部威胁与防御

**现状**：没有任何敌对生物/袭击/天灾系统等外部压力。

**愿景**：
- 殖民地奇观值达到阈值 → 触发 "元素风暴" 袭击（元素生物进攻）
- 防御建筑：箭塔（自动射击）、魔法屏障（消耗魔力维持）
- NPC 战斗行为：装备战斗法杖 → 执行防御任务 → 击败敌人
- 失败惩罚：建筑损坏（structureIntact=false），需修复

#### 2.4.4 环境与生态

**现状**：节点建筑从虚空中产元素，没有环境依赖。

**愿景**：
- 森林节点在森林生物群系效率 +50%，在沙漠效率 -50%
- 大地节点 5×5 内有石头/矿石增加产出
- 水域节点必须接触水方块
- 建筑位置有实际意义（不是随便放哪都一样）

#### 2.4.5 NPC 社交与殖民地文化

**现状**：NPC 之间零互动。

**愿景**：
- NPC 之间形成 "关系图"：好友/同事/师徒
- 在酒馆/食堂社交 → 恢复幸福度
- 高级 NPC 可带学徒（新手 NPC 跟随大师执行任务→技能成长加速）
- 殖民地节日/庆典（消耗大量资源→全殖民地加成）
- 每个殖民地生成独特的 "文化特征"（影响 NPC 属性倾向）

---

## 三、当前代码中的紧急技术债务

以下问题**不阻塞功能但会随规模扩大变严重**：

| # | 问题 | 严重度 | 建议 |
|---|------|--------|------|
| 1 | GlobalTaskPool 永不清除 COMPLETED 任务 — 内存泄漏 | 中 | 保留最近 500 个，或 COMPLETED 后 5min 清除 |
| 2 | BlockBreakEvent 未监听 — 结构损坏不检测 | 中 | 补充 BlockBreakHandler（见 docs/99 1.4） |
| 3 | NPC 寻路已废弃改为传送 — 移除无用 PathfindingAi | 低 | 清理 RandomStrollGoal 或替换为殖民地范围内漫步 |
| 4 | WandscapeEntityOps stub — applyEffect/getPosition 空操作 | 低 | 阶段 4 前补全 |
| 5 | 多人游戏未测试 — 数据模型理论上隔离但未验证 | 中 | 双人测试：两个玩家各建一个殖民地，验证 NBT 不串 |
| 6 | NPC 掉落物 — 死亡时背包物品消失 | 中 | 死亡时 `spawnAtLocation()` 掉落背包内容物 + 法杖 |
| 7 | 建筑队列容量硬编码 — town_hall capacity=5 但无上限校验 | 低 | EnqueueHelper 中检查队列 size |
| 8 | AsyncTransformExecutor 30s 超时 — 可能掩盖真实卡死 | 低 | 添加超时日志 + 超时后的兜底逻辑 |

---

## 四、推荐实施顺序

按 "依赖关系 + 对可玩性的边际提升" 排序：

```
第 1 层 (立刻 — 补完 P0 闭环):
  ├── 仓库基础 (04): 元素存储 + ColonyResourceAccess MC 实现
  ├── 节点自动采集 (09): ForestNodeBE/EarthNodeBE auto-publish
  ├── 殖民地创建 (15): TownHallBE → createColony → colonyId
  └── 维护结算 (15): 定期扣 maintenance_cost

第 2 层 (然后 — 经济深度):
  ├── 仓库 GUI (04): 玩家查看/取出/存入
  ├── 工作站 (10): decompose + synthesize 配方
  ├── 制作站 (10): 法杖制作
  └── 节点 T2/T3 (09): 更多元素类型

第 3 层 (殖民地玩法):
  ├── 房屋绑定 (11): NPC↔house + 魔力恢复 ×3
  ├── 魔力池 (11): 公共魔力存储
  ├── 酒馆招募 (12): 候选人三选一 + GUI
  ├── 坟墓系统: 死亡 NPC 墓碑
  └── NPC 成长: spellPower 升级

第 4 层 (差异化):
  ├── 管理面板 (14): 殖民地 GUI + 小地图
  ├── 祭坛复活 (13): 多方块 + resurrection
  ├── 自动化规则: 玩家可配置的 IF-THEN
  └── 建筑升级: tier 1→2→3

第 5 层 (愿景):
  ├── 外部威胁: 袭击/防御
  ├── 多殖民地: 贸易路线
  ├── NPC 社交: 关系网
  ├── 环境依赖: 生物群系加成
  └── 指南书 + 进度
```

---

## 五、与现有路线图的关系

本文档**补充**而非取代 `docs/17-development-roadmap.md`。

- 17 是 **工期计划**（阶段 0-5，预计每个阶段程序 5-7 天）
- 25 是 **差距地图**（已有什么、缺什么、还该设计什么）

路线图 17 的阶段 3-5 覆盖了本文档的大部分系统，但以下系统**未出现在 17 中**：
- NPC 成长/技能、幸福度与需求
- 防御/战斗/外部威胁
- 自动化规则系统
- 建筑升级/科技树
- 环境生态依赖
- 音效系统
- NPC 坟墓（17 阶段 5 只提了"复活仪式"但未提墓碑）
- 多殖民地管理

这些需要在 17 的下一次修订中纳入。
