# Config 字段核验 + 死字段/硬编码审计

> 本审计为**只读调查**，未改动任何代码。目的：核对 `Config.java` 每个字段是否真正生效、找出「Config 有可编辑值但代码仍硬编码」导致改配置不生效的字段、以及可删/可新增 Config 的硬编码项。
>
> 核查方法：`Config.java` 75 个字段逐一 grep `Config.<字段>.get()` 引用（`\bConfig\.[A-Z][A-Z_]{2,}`，覆盖 `src/main` 与 `src/test`）；对无引用的字段再追实际行为的硬编码来源。`src/test` 仅引用 `BuildingConfig.*`，不引用顶层 `Config`。

## 结论摘要

| 分类 | 数量 | 说明 |
|---|---|---|
| ✅ 真正生效 | 59 | 被代码读取并消费 |
| ⛔ 死字段（无任何引用） | 9 | 改配置无任何效果 |
| ⚠️ 无效字段（被读取但无消费者） | 7 | 读到 `RoadConfig` 但无人消费，改配置无游戏效果 |
| **合计** | **75** | |

另发现：`WandscapeConstants` 有 **16 个死常量**、`CoreBootstrap.createColony` **死方法**、`RoadConfig` 3 个 getter **无调用方**。核心矛盾集中在 **scheduler/npc/stuck/wand 四组 9 个死字段**——它们对应的行为都硬编码在 `NavigationSystem`/`SchedulerSystem` 里（默认值恰好与 Config 一致），所以「改了 Config 不生效」。

---

## 一、Config 死字段（9 个）—— 无任何代码引用

全部 grep 不到 `Config.<字段>.get()`，改配置不生效。分两类：**「Config 有值但行为硬编码」**（建议接线而非删）与**「Config 描述的功能已不存在」**（建议删）。

### 1.1 行为真实存在、只是硬编码 → 应接线（4 组 8 字段）

| Config 字段（默认值） | 实际行为位置（硬编码） | 建议 |
|---|---|---|
| `scheduler.stuckCheckIntervalTicks` (60) | `NavigationSystem.java:47` `STUCK_CHECK_INTERVAL = 60` | 接线 |
| `scheduler.stuckMinMoveDistance` (2.0) | `NavigationSystem.java:49` `STUCK_MIN_PROGRESS = 2.0` | 接线 |
| `scheduler.stuckMaxRetries` (3) | `NavigationSystem.java:48` `MAX_STUCK_CHECKS = 3` | 接线 |
| `npc.walkThreshold` (64) | `NavigationSystem.java:41` `PATHFIND_MAX_RANGE = 64` | 接线 |
| `scheduler.heartbeatTicks` (40) | `SchedulerSystem.java:32` `HEARTBEAT_INTERVAL = 2` | ⚠️ 见下 |

**注意 heartbeat 语义错位**：Config 默认 40（注释「40 ticks = 2 seconds」），但真实调度器每 **2 tick** 跑一次（`SchedulerSystem` 类注释「Runs every 2 ticks」）。这不是「默认值相同」的接线，而是 Config 值与真实行为完全不符——接线前必须先定「Config 该描述什么」：若把 `HEARTBEAT_INTERVAL=2` 当真相，Config 默认应改成 2；`WandscapeConstants.SCHEDULER_HEARTBEAT_TICKS=40` 同样是错值且无引用。

### 1.2 描述的功能已不存在 / 已被数据驱动取代 → 建议删

| Config 字段（默认值） | 实际行为 | 证据 |
|---|---|---|
| `general.colonyRadius` (128) | 殖民地半径硬编码在两处：`ColonyCommand.java:157` 建 ECS 殖民地 entity 用 **64**；`ColonyApiImpl.java:23` 空间索引用 `MAX_COLONY_RANGE = 256` | 两处都非 Config |
| `scheduler.sameBuildingContinuationBonus` (50.0) | SchedulerSystem 评分**根本没实现**同建筑续做加分——只有 `proximity×0.6 + (workEff−1)×0.4`（`SchedulerSystem.java:132-134`） | 功能从未落地 |
| `wand.baseOperationRange` (16) | 魔法射程已改为**每魔法 JSON 数据驱动**：`magic_spells/*.json` 的 `range` 字段 → `MagicDef.range`（如 beam=32） | 被 JSON 取代 |
| `wand.perWandLevelRange` (8) | 同上，无法杖等级加射程的概念 | 被 JSON 取代 |

---

## 二、Config 无效字段（7 个）—— 被 `RoadConfig` 读取但无消费者

这些字段被 `RoadConfig.java` 的 getter 读取，但 getter 无人调用 → 改配置同样无游戏效果。`docs/gaps.md` 已部分记录。

| Config 字段 | RoadConfig getter | 有无调用方 |
|---|---|---|
| `road.maxFillHeight` (6) | `getMaxFillHeight()` | ❌ 无（RoadBuilder 只用了 `getMaxCutDepth`，见 `RoadBuilder.java:133`） |
| `road.decoration.enabled` (true) | `getDecorationConfig()` / `isDecorationEnabled()` | ❌ 无 |
| `road.decoration.lampSpacing` (8) | 同上 | ❌ 无 |
| `road.decoration.benchSpacing` (24) | 同上 | ❌ 无 |
| `road.decoration.lampPost` (oak_fence) | 同上 | ❌ 无 |
| `road.decoration.lampLight` (lantern) | 同上 | ❌ 无 |
| `road.decoration.benchBlock` (oak_stairs) | 同上 | ❌ 无 |

Road 其余 getter 均有调用方：`getBuildingThreshold`（`RoadEventListener:86`、`RoadApiImpl:47`）、`getDefaultWidth`（`RoadEventListener:96,285`）、`getSegmentMaxLength`（`RoadEventListener:176,320,347`）、`getMaxCutDepth`/`getSurfacePalette`/`isPillarEnabled`/`getPillarSpacing`/`getPillarBlock`（`RoadBuilder:62,133,158,159,162`）。

**建议**：`road.maxFillHeight` 若短期不实现「填平」逻辑就删；`road.decoration.*` 是「装饰生成未接入 RoadBuilder」的遗留——若决定实现则接线，否则整组删（与 gaps.md 记录一致）。

---

## 三、Config 生效字段（59 个）—— 参照表

按子系统列出，读者可快速核对「改这里生效」：

- **日志/通用**：`general.debug`（`Wandscape.java:440`）、`general.maxConcurrentBuildings`（`BuildingTaskSource.java:79`）、`general.autoApproveTasks`（`EngineBootstrap.java:128`）
- **NPC**：`npc.regenGraceTicks` / `npc.regenIntervalTicks` / `npc.manaRegenTicks`（`WandscapeNpc.java:176,188,574`）
- **Road（生效部分）**：`road.buildingThreshold`、`road.segmentMaxLength`、`road.defaultWidth`、`road.maxCutDepth`、`road.surfacePalette`、`road.pillar.enabled`、`road.pillar.spacing`、`road.pillar.block`
- **游客-生成/离场**：`tourist.maxPerColony`、`tourist.despawnTimeoutTicks`、`tourist.baseSpawnCount`、`tourist.levelSpawnBonus`、`tourist.spawnRangeWidth`、`tourist.spawnWindowStart`、`tourist.spawnWindowEnd`、`tourist.departureWindowStart`、`tourist.departureWindowEnd`、`tourist.departureDelayMaxTicks`、`tourist.rescueRoadRadius`、`tourist.rescuePeripheryRadius`、`tourist.stayMinDays`、`tourist.stayMaxDays`（主要在 `TouristSpawnSystem.java`、`TouristTeleport.java`、`TouristEntity.java`）
- **游客-经济**：`tourist.baseWallet`、`tourist.walletPerLevel`、`tourist.atmTravelFundMultiplier`、`tourist.needBase`、`tourist.needPerLevel`（`TouristSpawnSystem.java`）
- **游客-行为**：`tourist.arrivalRadius`、`tourist.microNavSwitchDistance`、`tourist.queueWaitToleranceTicks`、`tourist.barGainCoeff`、`tourist.energyRestoreThreshold`、`tourist.queueSlotSpacing`、`tourist.visionRadius`（`TouristMoveGoal.java`、`TouristSimulation.java`）
- **殖民地**：`colony.expEqualLevel` / `colony.expAboveLevel`（`ColonyLevelManager.java:97,98`）
- **装饰**：`decoration.bonusCap`、`decoration.scanIntervalTicks`（`DecorationBonusSystem.java`、`BuildingContributionRegistry.java`）
- **维护**：`maintenance.gracePeriodTicks`、`maintenance.settlementWindowTicks`、`maintenance.autoRestart`（`DailySettlementSystem.java`）、`maintenance.reserveDays`、`maintenance.forecastIntervalTicks`（`MaintenanceForecastSystem.java`）
- **守卫**：`guard.range`、`guard.releaseRange`（`GuardTaskSource.java`、`GuardCommand.java`、`GuardBlueprints.java`）、`guard.selfDefenseRange`、`guard.hateRange`（`SelfDefenseExecutor.java`）、`guard.hateDurationTicks`（`SelfDefenseHandler.java`）
- **袭击**：`raid.triggerRange`、`raid.nearbyRadius`、`raid.checkIntervalTicks`（`RaidTriggerScanner.java`）、`raid.villageRange`（`MixinServerLevel.java:28`）
- **粒子**：`particle.level`（`ParticleService.java:38,43`）

---

## 四、`WandscapeConstants` 死常量（16 个）—— 无任何引用，可整组删

```java
SCHEDULER_HEARTBEAT_TICKS          // 40，与 Config 同错值，无引用
SAME_BUILDING_CONTINUATION_BONUS   // 50.0，功能未实现
BASE_OPERATION_RANGE               // 16，被 JSON 射程取代
PER_WAND_LEVEL_RANGE               // 8，同上
DEFAULT_COLONY_RADIUS              // 128，无引用
NPC_WALK_THRESHOLD                 // 64，无引用
STUCK_CHECK_INTERVAL_TICKS         // 60，无引用
STUCK_MIN_MOVE_DISTANCE            // 2.0，无引用
STUCK_MAX_RETRIES                  // 3，无引用
QUEUE_TOWNHALL / QUEUE_WORKSTATION / QUEUE_CRAFTING / QUEUE_POTION
QUEUE_NODE / QUEUE_HOUSE / QUEUE_TAVERN   // 队列容量已改为 JSON 的 `queue` 块（BuildingConfig.QueueDef）
```

注意两个「仍在引用但需留意」的：
- `QUEUE_RITUAL_ALTAR`（=10）仍被 `AltarCastHandler.java:129` 使用，但它是 **`TaskRequest` 的 priority 第三参**（`TaskRequest.java:16`），不是队列容量——**命名误导**，建议改名 `PRIORITY_ALTAR_CAST` 或就地删。
- `WORKSTATION_CRAFT_TICKS_PER_UNIT`(10) / `CRAFTING_STATION_CRAFT_TICKS_PER_UNIT`(1200) 仍用（`RequestProductionTaskPacket:133,135`、`ResourceSupplySystem:131`），属于硬编码调参项，见第六节。

---

## 五、死代码（可整段删）

| 位置 | 说明 |
|---|---|
| `CoreBootstrap.createColony(World, x,y,z, radius)` `CoreBootstrap.java:116-121` | **无任何调用方**；建殖民地 entity 实际走 `ColonyCommand.createColonyAt:153-160`（硬编码 radius 64） |
| `RoadConfig.getMaxFillHeight()` `RoadConfig.java:44-45` | 无调用方（见第二节） |
| `RoadConfig.getDecorationConfig()` / `isDecorationEnabled()` `RoadConfig.java:79-92` | 无调用方（见第二节） |

---

## 六、可新增 Config 的硬编码调参项（候选）

以下为玩家可感知的调参项，目前硬编码。是否新增 Config 属产品决策，按「玩家会想调」优先级排列：

| 硬编码（位置） | 现值 | 建议 |
|---|---|---|
| `WORKSTATION_CRAFT_TICKS_PER_UNIT`（`WandscapeConstants.java:21`） | 10 | 工作站合成耗时，配方 JSON 无 duration 字段 → 候选 Config `production.workstationCraftTicksPerUnit` |
| `CRAFTING_STATION_CRAFT_TICKS_PER_UNIT`（`WandscapeConstants.java:22`） | 1200 | 同上，`production.craftingStationCraftTicksPerUnit` |
| `TAVERN_RECRUIT_COST_PER_ELEMENT`（`WandscapeConstants.java:32`） | 10_000 | 酒馆招募成本，经济数值 → `tavern.recruitCostPerElement` |
| `TOURIST_MAX_ENERGY`（`WandscapeConstants.java:42`） | 100 | 游客精力上限（已有 `tourist.energyRestoreThreshold` 是比例，上限仍硬编码）→ `tourist.maxEnergy` |
| `DECOMPOSE_DIVISOR`（`WandscapeConstants.java:35`） | 5 | 分解产出除数（1/5），经济数值 → `production.decomposeDivisor` |
| `ColonyApiImpl.MAX_COLONY_RANGE`（`ColonyApiImpl.java:23`） | 256 | 殖民地空间索引范围——**最好直接接 `general.colonyRadius` 或作为其默认来源**（与第一节互相呼应） |
| `ColonyCommand` 建 entity 的 radius（`ColonyCommand.java:157`） | 64 | 同上，接 Config |
| `SchedulerSystem` 评分权重（`SchedulerSystem.java:132-134`） | 0.6 / 0.4 | 调度偏好，优先级低 |
| `TouristSimulation` 评分权重（`TouristSimulation.java:51-57`） | ENERGY_URGENCY_BONUS 2000 / WALLET_LOW_BONUS 2000 / WALLET_EMPTY_BONUS 4000 / QUEUE_PENALTY 3000 | 游客目标评分，优先级低 |
| `TouristMoveGoal` 卡死阈值（`TouristMoveGoal.java:93,109,111` 等） | POST_TOUR_IDLE 200 / ROOF_STUCK 80 / WANDER_STUCK 120 | 优先级低 |
| `NavigationSystem` 其余常量（`NavigationSystem.java:40-55`） | PATHFIND_TIMEOUT 200 / MAX_REPATH 5 / STOP_RANGE 5 / NAV_SPEED 1.0 | 优先级低 |
| 各系统心跳间隔（`ResourceSupplySystem.java:50` 40、`BuildingTaskSource.java:35` 10、`ColonyAmbientTracker.java:42` 120） | — | 优先级低 |

> 建议克制：不要把所有魔法数都塞进 Config（会重蹈「改了半天不生效」覆辙）。优先做第一节的「接线」与第一/四节的「删除」，第六节按玩家价值选 3-5 个即可。

---

## 七、建议处理顺序（供后续实施参考，本次未改代码）

1. **接线（让 Config 生效）**：`NavigationSystem` 4 个常量（stuck×3 + walkThreshold）接 Config；`SchedulerSystem.HEARTBEAT_INTERVAL` 与 Config 语义对齐（改 Config 默认 40→2 或删 Config 字段二选一）。
2. **删除**：`WandscapeConstants` 16 个死常量；`CoreBootstrap.createColony`；`RoadConfig` 3 个无消费 getter + 对应 7 个 Config 字段（`road.maxFillHeight` + `road.decoration.*`）；第一节 1.2 的 4 个「功能已不存在」Config 字段（`colonyRadius` 若接 1.3 则保留，`sameBuildingContinuationBonus`、`baseOperationRange`、`perWandLevelRange` 删）。
3. **殖民地半径收口**：`ColonyApiImpl.MAX_COLONY_RANGE` / `ColonyCommand` radius 64 → 统一读 `general.colonyRadius`（或删 Config 字段、两处硬编码对齐）。注意两处现值不同（256 vs 64），收口前需定语义。
4. **可选新增**：第六节候选按玩家价值挑选。
5. 每步完成后更新 `docs/gaps.md` 与 `docs/modules/road.md` 中已过时的「装饰未接入」「colonyRadius」相关表述。
