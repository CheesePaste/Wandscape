# citizen/ — 市民 NPC 系统

**实现状态:** Phase 1+ (状态机 + 路网寻路 + 可视/隐藏双模 + 职业-建筑绑定)  
**设计文档:** `docs/citizen-npc-design.md`  
**原则:** 纯观赏性，与 ECS 零耦合。不继承 `WandscapeNpc`，不使用 `GlobalTaskPool`。

## 关键类

### `citizen/CitizenEntity.java`

MC 实体，继承 `Villager` 复用模型/渲染/睡眠 pose。vanilla 脑完全压制：

| 覆写 | 行为 |
|------|------|
| `brainProvider()` | 空 brain — `Brain.provider(ImmutableList.of(), ImmutableList.of())` |
| `makeBrain()` | 不调 `registerBrainGoals()`（在 Villager 中是 private） |
| `customServerAiStep()` | 空体 — 跳过 brain tick, trade timer, raid sweep |
| `shouldBeSaved()` | `false` — 永不写入 chunk 数据 |
| `registerGoals()` | `FloatGoal(0)` + `CitizenMoveGoal(1)` + `RandomLookAroundGoal(3)` |
| `mobInteract()` | 右键显示「名字 - 职业 - 情绪 (状态)」 |

字段：`citizenName`, `profession`, `mood`, `currentState`, `commuteTarget`, `commuteArrived`, `wanderAnchor`, `wanderRadius`, `poiList`。

`applyState(CitizenState)` — 切换状态时自动同步 `Pose.SLEEPING` ↔ `Pose.STANDING`。

### `citizen/Profession.java`

6 职业枚举：`FARMER/农民`, `MERCHANT/商人`, `SCHOLAR/学者`, `ARTISAN/工匠`, `GUARD/守卫`, `IDLER/无业`。

### `citizen/CitizenState.java`

5 状态枚举：`IDLE/空闲`, `COMMUTING/通勤中`, `WORKING/工作中`, `LEISURE/休闲中`, `SLEEPING/睡眠中`。

### `citizen/StoredCitizen.java`

Record — WORKING / SLEEPING 状态时市民的全部数据。反序列化（respawn）时用这些字段重建 `CitizenEntity`。

```
record StoredCitizen(
    String name,
    Profession profession,
    int mood,
    @Nullable BlockPos workplace,
    @Nullable BlockPos home,
    @Nullable BlockPos bed,
    CitizenState storedState
)
```

### `citizen/CitizenManager.java`

单例，殖民地级生命周期管理器。3 个核心 Map：

| Map | Key | 含义 |
|-----|-----|------|
| `active` | entity UUID | 当前在世界的实体（COMMUTING/LEISURE/IDLE） |
| `stored` | 原 UUID | 已 despawn 的市民数据（WORKING/SLEEPING） |
| `bedAssignments` | entity UUID | 分配的床坐标 |
| `workplaceAssignments` | entity UUID | 工作建筑坐标 |
| `homeAssignments` | entity UUID | 宿舍建筑 anchor |

#### 时刻表（MC dayTime 0 = 6:00 AM）

| 时段 | dayTime | 行为 |
|------|---------|------|
| 06:00-06:30 | 0–500 | stored SLEEPING → spawn COMMUTING(→工作建筑) |
| 06:30-17:30 | 500–11500 | 到达工作建筑 → WORKING(despawn)；IDLER 保持 IDLE 可见 |
| 17:30-18:00 | 11500–12000 | stored WORKING → spawn COMMUTING(→家) |
| 18:00-22:00 | 12000–14000 | 到家 → LEISURE 逛城(可见+路网) |
| 22:00-06:00 | 14000–24000 | SLEEPING(despawn) |

#### 建筑事件

`@SubscribeEvent onBuildingPlaced(BuildingPlacedEvent)` → `evaluateAndSpawn()`：
1. 扫所有建筑 → 按 category 分组
2. residence 建筑 → `BuildingApi.findBeds()` → 计算人口上限 = min(床数, 15)
3. 职业分配：每有一个匹配 category 的建筑 → 1 个对应职业市民
4. POI 缓存：`BuildingApi.sampleWalkableGround()` 每建筑 ~3 个可走地面采样点
5. 差额生成 → 初始状态由当前时间决定

#### 持久化防护

3 层：

- `CitizenEntity.shouldBeSaved() → false` — chunk save 不写
- `ServerStoppingEvent` → `onServerStopping()` — save 前 discard 全部 active
- `ServerStartingEvent` → `killAllStrayCitizens()` — 异常残留扫荡

### `citizen/ai/CitizenMoveGoal.java`

State-driven 的 vanilla `Goal`（优先级 1）。

#### 状态行为矩阵

| 状态 | 寻路 | 目标 | 速度 |
|------|------|------|------|
| COMMUTING | 路网优先 → fallback vanilla | `commuteTarget`（工作建筑/家） | 0.55 |
| LEISURE | 路网优先 → fallback vanilla | `poiList` 中随机远 POI | 0.35 |
| IDLE | vanilla 直线 | `wanderAnchor` 半径内 | 0.35 |

#### 路网集成

1. `RoadRouter.plan()` → waypoint 链
2. `wpIndex = 1` 跳过 waypoints[0]（起点坐标）
3. 每个 waypoint 用 `findGround()` 投影到可走 Y 后再传给 `PathNavigation.moveTo()`
4. stuck 检测：40 COMMUTING / 60 LEISURE tick `nav.isDone()` 未到达 → replan
5. 不渲染：只在状态切换或 stuck replan 时打 `[Citizen] <name> <mode> ROAD/VANILLA → <target>` 日志

#### LEISURE POI 行走

- 到达 POI → 5–15 秒停留 → 随机下一个远 POI
- POI 源：`CitizenManager.cachedPoiList`（所有建筑 boundary 内随机可走地面）
- 无 POI → fallback 走 `wanderAnchor` 周围

## 注册

- 实体：`wandscape:citizen` (MobCategory.CREATURE, 0.6×1.95)
- 刷怪蛋：`citizen_spawn_egg` (橙色#FFAA00+白色#FFFFFF)
- 渲染器：直接复用 `VillagerRenderer::new`（零自定义渲染）

## 依赖

```
citizen/ → shared/api/BuildingApi.ts       (getColonyBuildings, findBeds, sampleWalkableGround)
        → shared/api/RoadApi               (getNetwork, 路网规划)
        → shared/event/BuildingPlacedEvent (NeoForge 事件订阅)
        → shared/registry/WandscapeApis    (getBuildingApi, getRoadApi)
        → core/road/RoadRouter              (plan)
```

**不依赖** `core/ecs`, `core/task`, `core/op`, `npc/`, `engine/boundary/`。

## 测试命令

```
/wandscape citizen list                    列出 active + stored
/wandscape citizen state <name|all> <state>  强制状态切换
```

有效状态：`commuting working leisure idle sleeping`

## 已知限制 (Phase 1+)

- IDLER 职业无时刻表推动（始终 IDLE 可见）
- LEISURE POI 数量取决于建筑数（少建筑 = 逛不起来）
- 无雨天/怪物/建筑拆除事件处理
- 无 mood 影响行为
- 名字池硬编码数组（未切 JSON）
