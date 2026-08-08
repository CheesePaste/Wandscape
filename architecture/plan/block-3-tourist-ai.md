# Block 3 — 游客 AI + 交互 + 排队

> 依赖 Block 0 契约（InteractionConfig/Activity/TouristStateHost）+ Block 2 的数据（经接口调用，**不碰实体文件**）。这是最大的块。本块与 Block 1/2/4 可并行。所有游客行为逻辑：三条填充、Find-Best-Action 目标选择、spots 排队做动作、旅店、离开规则。

## 目标

1. `satisfactionGain` → `fillBars`（无惩罚填三条）；`performShopInteraction/performServiceInteraction` → 统一 `performInteraction`。
2. 目标选择 = Find-Best-Action（需求缺口 × 建筑值 + 精力 + 排队惩罚），**无 typePreferences**。
3. spots 单点寻路 + 占用/活动/释放 + 排队（`TouristSpotManager`）。
4. 旅店改 beds 判定，删 sat≥50 门槛，按 D6 离场。
5. 从 `TouristStateHost` **删除** `getSatisfaction/setSatisfaction`、`getTypePreference/adjustTypePreference`（迁移完调用点后）。

## 负责文件

| 文件 | 动作 |
|---|---|
| `tourist/internal/TouristSimulation.java` | fillBars / performInteraction / Find-Best-Action / 删 pref |
| `tourist/internal/TouristMoveGoal.java` | spot 单点导航 + 占用/活动/释放 + 排队 + 删 AABB/匹配分/pref |
| `tourist/internal/TouristSimSystem.java` | 镜像交互（共享 TouristSimulation）+ 离开规则 + registerDeparture 新签名 |
| `tourist/internal/HotelStayHandler.java` | beds 判定 + nightsStayed + 删 sat≥50 |
| `tourist/internal/TouristSpotManager.java` | 新建：spot 占用/队列/等待超时 |
| `tourist/internal/TouristStateHost.java` | 删遗留方法 |
| `tourist/internal/TouristState.java` | 不动（移动标签） |
| `building/internal/ShopStockManager.java` | 按 interaction.trade() 工作 |
| `building/internal/ShopInteractionHandler.java` | 同 |

## 关键概念

- **fillBars**：`sat_d += round(value_d × TOURIST_BAR_GAIN_COEFF)`，封顶 `need_d`。无惩罚。
- **isFullySatisfied()**（Block 2 实现）：三条 ratio 全 1。经验/法师简历仅满条离场触发。
- **Find-Best-Action**：`score(b) = Σ_d max(0,(need_d−sat_d)) × value_d × coeff + energyUrgency − queuePenalty`。
- **spot 占用**：`TouristSpotManager` 按 buildingId 记每个 spot 下标被谁占用；满了排队；等待超 `TOURIST_QUEUE_WAIT_TOLERANCE_TICKS` 放弃。

## 具体改动

### 1. TouristSimulation（当前 :70-177, :231-331）

- `effectiveValues`（:70-85）：保留（三值 + 商店货品加成）。
- **删除** `satisfactionGain`（:98-113，含阈值惩罚）→ 新增：
  ```java
  /** 填三条：sat_d += round(value_d × coeff)，封顶 need_d。返回是否发生了任何填充。 */
  public static boolean fillBars(ServerLevel level, TouristStateHost t, BuildingConfig cfg)
  ```
- **删除** `matchScore`（:92-96，pref×threeSum）→ 新增 need-gap 评分（见 D9 公式）。
- `applyPreferenceDecay`（:127-133）**删除**（无 pref）。
- `performShopInteraction`（:152-177）/`performServiceInteraction`（:181-208）→ 统一 `performInteraction(level, t, buildingId, colonyId)`：
  - 精力：`t.setEnergy(t.getEnergy() + interaction.energy())`（负数消耗、正数恢复）。
  - 填条：`fillBars(...)`。
  - 经济：`interaction.trade()!=null` → 走 ShopStockManager 购买（沿用现有 purchase/purchaseAffordable）；`!interaction.output().isEmpty()` → ColonyItemBank.addElement（沿用现有）。
  - `InteractionResult` record 保留（journey diary/气泡/叙事用）。
- `selectNextTarget`（:231-292）：改为按 interaction 判断目标建筑（`cfg.hasInteraction()`），shop 有货判断改 `trade()!=null && hasStock`，hotel 判断改 `beds()>0`，加入精力轴 + 排队惩罚（D9）。
- `weightedPick`（:294-311）：权重改为 Find-Best-Action 分数。

### 2. TouristMoveGoal（当前 :564-609 performBuildingInteraction、:1163-1330 planNextBuilding、:1337-1463 交互/评分、:125-132/437/454-465/479/517/1296-1321 AABB 逻辑）

- **删 AABB 交互区逻辑**：`touristInteractZones: List<BoundingBox>`（:125-132）及生成（:1296-1321）、到达判定（:454-465）→ 改为**寻路到一个 spot 点**：`interactPoint = api.getTouristInteractPoint(buildingId)`（Block 0/4 保持该 API 返回一个点，由 spots 派生）；到达 = `distSqr(spot) <= ARRIVAL_RADIUS²`。
- `performBuildingInteraction`（:564-609）：删 shop/service category 分发 → 调 `TouristSimulation.performInteraction`。
- `interactWithShop/interactWithService`（:1337-1393）→ 合并为一个按 interaction 处理的交互（气泡/叙事沿用）。
- `computeMatchScore/applyPreferenceDecay`（:1427-1442）**删除**；`weightedPick`（:1445-1463）改 need-gap 分数。
- `planNextBuilding/hasBuildingsAvailable`（:1163-1330/:1077-1097）：目标过滤改 `cfg.hasInteraction()`；hotel 判 `beds()>0`；shop 有货判 `trade()!=null && hasStock`。
- `decideNextMode`（:1042-1074）：休息冷却概念保留（活动后冷却），但不再有 sat 参与。
- **活动/占用**：到达 spot → `TouristSpotManager.claim(buildingId, spotIndex, touristId)` → `setCurrentActivity(派生)` + `setOccupiedSpot` + `setActivityTicks(duration)` → 活动期间站着做动作（粒子/姿态，可加简单特效）→ `duration_ticks` 后 `release` + 结算。
- **排队**：spot 全满 → `setCurrentActivity(QUEUE)` 在建筑旁等；超时放弃（`release` + 去别处）。

### 3. TouristSimSystem（当前 :444-511 interact、:514-524 hasHotelVacancy、:528-576 checkDeparture/routeToHotel、:578+ depart）

- `interact`（:444-511）：删 shop/service category 分发 → 调 `TouristSimulation.performInteraction`（与实体共用）。
- 旅店入住（:458-484）：条件改 `beds()>0 && !isFullySatisfied() && (夜晚) && hasVacancy`，删 `sat>=50`；入住记 nightsStayed。
- `checkDeparture`（:528-558）：改 D6 规则（isFullySatisfied / deadline / 无恢复 / 无床位 / idle），删 sat 三段。
- `routeToHotel`（:560-576）：`"service".equals(category)` → `cfg.beds()>0`。
- `depart`/`registerDeparture`（:578+）：satisfaction 实参 → 聚合值（min-ratio×100，Block 4 收口签名）。

### 4. TouristSpotManager（新建，仿 HotelStayHandler 单例）

```java
public final class TouristSpotManager {
    // getActive() 单例；内存态，无需持久化
    // claim(buildingId, spotIndex, touristId) -> boolean
    // release(buildingId, spotIndex)
    // isSpotFree(buildingId, spotIndex)
    // freeSpotCount(buildingId)
    // totalSpots(buildingId)  <- 从 BuildingConfig.interactSpots().size()
}
```
- 并发/线程：仿现有单例（HashMap<UUID, int[]> 或 Map<UUID, Set<Integer>>），留意与影子/实体共用。

### 5. HotelStayHandler（当前 :84 checkIn、:154 settleIntoBed、:110 checkOut、:268-295 heartbeat、:228 hasVacancy）

- 旅店判定 `config.service().maxOccupancy()`（:39/86/88/230/231）→ 改 `config.interaction().beds()`。
- checkIn 条件（:84）删 `sat>=50`，改 `!isFullySatisfied() && 夜晚`。
- 退房（heartbeat 清晨 1000-1200）：`nightsStayed++`、精力回 100（沿用）。入住点 wakeUpPos 逻辑保留。

### 6. ShopStockManager / ShopInteractionHandler

- `onDailySettlement` 补货过滤 `"shop".equals(state.getCategory())`（:623）→ 改 `cfg.interaction().trade()!=null`。
- `purchaseAffordable/purchase/getGoodsBonus*`（:282/:327/:203-215）：读 `cfg.interaction().trade().goods()`（经 BuildingConfig 访问器或直接 interaction）。
- `ShopInteractionHandler`（:15-40）：纯透传，按新字段。

### 7. TouristStateHost（删遗留方法）

- 迁移完所有调用点后，**删除** `getSatisfaction()/setSatisfaction()`、`getTypePreference()/adjustTypePreference()`。
- 全仓库 grep 确认无引用（Block 4 负责非 tourist 文件；本块负责 tourist AI 文件）。

## Done 判定

1. `./gradlew build` 绿。
2. 游客：逛建筑填三条（无惩罚）；精力低/0 时优先/只能去 energy>0 建筑；在 spots 占位做动作、满了排队、超时放弃；夜间入旅店、清晨退房回精力。
3. 满条离场给经验 + mage resume；停留 2-4 天到点离场；低级小镇满不了条 → 0 经验。
4. `tourist/**`（AI 部分）无 `getSatisfaction`/`getTypePreference`/`"shop"`/`"service"` 引用。
