# tourist/ — 游客实体与行为 AI

游客是**短居访客**。与常驻市民（WandscapeNpc）不同——无职业/床位/住宅/状态机。`TouristState` 只是**移动状态标签**（禁止扩展为状态机）；活动状态走 `shared/data/Activity` 枚举。

游客在城镇里逛街：面包店排队、澡堂泡澡回精力、ATM 取钱、旅店睡觉；三条需求条全满、夜晚开心离场，给殖民地经验（法师游客留简历可被酒馆招募）。

## 为什么不复用 WandscapeNpc

WandscapeNpc 承载 ECS 桥接、法杖、魔力池、任务执行器等完整设施。游客不需要这些：独立实体类（extends PathfinderMob，非 WandscapeNpc）避免组件污染，行为逻辑更简洁。

## 数据模型（三条需求条 + 画像 + 停留）

- **三条需求条** `comfort/magic/wonder` 各带 `sat`（填充量）/`need`（需求上限）。填充无惩罚：`sat += round(建筑该维值 × TOURIST_BAR_GAIN_COEFF)`，封顶 need。**满条 = 三条 ratio 全 1**（`isFullySatisfied()`）。
- **画像**：40% 均衡 `{1,1,1}`、20% 舒适 `{1.6,0.7,0.7}`、20% 魔法 `{0.7,1.6,0.7}`、20% 奇观 `{0.7,0.7,1.6}`。三条 need = `totalNeed × 权重占比`，`totalNeed = TOURIST_NEED_BASE + (level-1)×TOURIST_NEED_PER_LEVEL` —— **等级越高总需求越高、越难满足**（自然难度曲线；1 级 totalNeed=60：均衡 20/20/20、侧重 32/14/14）。
- **精力循环**：shop/service 交互消耗精力，`relax` 建筑回精力（clamp 到 `TOURIST_MAX_ENERGY`），旅店夜晚入住也填一次三条（利好玩家的特性）。精力 0 且视野内无恢复建筑 → **闲逛**（不离场）。
- **钱包 / 总旅费**：`wallet`（随身现金）买货；`travelFund = startingWallet × TOURIST_ATM_TRAVEL_FUND_MULTIPLIER`（ATM 分批取现的池子，防无限取现）。
- **停留**：`departureDeadline = arrivalTime + rand(2~4)×24000`；`nightsStayed` 住店晚数。**`visitedBuildings` 停留期不重置**（防挂机，一栋建筑整个停留只逛一次；**ATM 例外**——`atmReusable` 判定下豁免 visited 可分批取现，靠取现冷却控节奏，visited 本身仍不重置）。

## 行为系统

- **TouristSpawnSystem** — 生成：roll 画像 → 设三条 need / 停留截止 / travelFund；**出生不指派目标**，出生即闲逛，目标完全由视野内 Find-Best-Action 决定。离开判定 D6（见下）。
- **TouristSimulation**（共享交互经济）— `fillBars` 填三条、四类交互结算（shop 购物 / service 产元素+耗精力 / relax 回精力 / atm 取钱）、**Find-Best-Action 目标选择**（视野内）、spot 认领/释放。实体路径与影子 sim 路径共用本类，一套逻辑无漂移。
- **TouristMoveGoal**（实体 AI）— MoveMode 状态机：`VISITING_BUILDING`（spot 单点导航 + 占用/活动/释放 + 排队）/ `EXPLORING_POI` / `WANDERING`。**闲逛目标 = `wandscape:custom_roads` 标签方块**（玩家自铺的路也算），锚点仅站到路上时随动，离闲逛起点 32 格强制折返（详见「与道路系统联动」）。
- **TouristSimSystem**（影子 sim）— 游客区块卸载后由 shadow 直线移动推进，镜像 `TouristSimulation` 交互与 D6 离场；玩家靠近时实体接管（shadow 胜出 → importToEntity）。
- **HotelStayHandler** — 夜晚旅店（`service.maxOccupancy>0`）：入住 → 睡床（视觉）+ 填一次三条 → 清晨退房精力回 100、`nightsStayed++`、回到入住站位。
- **TouristSpotManager** — spot 占用（buildingId → 占用下标集合）。**spot 数量 = 该建筑同时交互人数上限**；全满 → 排队（在建筑旁等，超 `TOURIST_QUEUE_WAIT_TOLERANCE_TICKS` 放弃去别处）。仅机制，无可见标记。
- **ActivityVisuals / TouristHumanoidModel**（client）— `Activity → (Pose/骨骼角度/粒子)` 注册表，`setupAnim` 缓动插值；未知动作兜底 `BROWSE`，渲染不崩。

## 目标选择（Find-Best-Action，只看视野内）

`score(b) = Σ_d min(缺口_d, round(value_d × TOURIST_BAR_GAIN_COEFF)) + 精力紧急加分(relax) + 钱包紧急加分(atm) − 排队惩罚(spot 全满)`

- 满意度偏好 = **总三值满意度增益**（潜在总三值 − 现在三值，逐维 `min(需求缺口, round(值×coeff))` 与 `fillBars` 结算一致）：避免单维数值夸张的建筑过度吸走游客（Comfort 满条仍去高 Comfort 建筑 = 浪费访问）。

- 候选只取 **`TOURIST_VISION_RADIUS` 内且区块已加载**（实体寻路）的可交互建筑；0-spot 建筑不选（无兜底）。
- 视野内无合适目标 → **闲逛**，直到视野出现合适的；绝不跨城寻路。
- 精力 0 → **只能**去 `relax.energyRestore()>0` 建筑；无恢复建筑 → 闲逛（不离场）。
- 夜晚 + 未满条 → **优先**旅店（`service.maxOccupancy>0` 且有空位）；视野内无旅店 → **回退普通建筑**（尊重已逛、精力 0 只去 relax），傍晚不干晃，18000 后由离场窗口接管。

## 四类交互（category 模式预设块，不合并）

| category | 块 | 交互效果 | 关键字段 |
|---|---|---|---|
| `shop` | `shop{}` | 购物（钱包买货、殖民地收元素）；精力 -20 | `goods`、`profit_rate`、`interaction_duration_ticks` |
| `service` | `service{}` | 产元素 + 消耗精力；`max_occupancy>0`=旅店（夜晚） | `energy_per_use`、`element_output`、`max_occupancy`、`interaction_duration_ticks` |
| `relax` | `relax{}` | 回精力（白天恢复建筑） | `energy_restore`、`interaction_duration_ticks` |
| `atm` | `atm{}` | 取现补钱包（单次=初始钱包随机 20%~50%，封顶 travelFund） | `interaction_duration_ticks` |

- 交互时长 = 模式预设块 `interaction_duration_ticks`（与 spot 无关）。
- 动作（`Activity`）只决定游客在 spot 上的活动状态/粒子；**精力/经济效果由 category 模式预设块决定**。
- 每个 spot 带**朝向 `facing`**（水平方向，缺省 south，随建筑旋转）：游客在位上做动作时 `setYRot` 面向该方向（`TouristMoveGoal.faceSpot`）。

## 离场规则（D6）

1. **满条 且 夜晚** → 开心离场（经验 + 法师简历；满条白天先开心闲逛，不立刻走）
2. **停留 2-4 天到点** → 离场（**只有满条才有经验**）
3. 夜晚且没有旅店 / 旅店满 → 离场
4. 长时间没事做（idle）→ 离场

经验/简历**仅满条**触发（防刷）。`TouristDepartedEvent` 载荷为 `BarRatio`（三条填充率），stats/HUD 走三条。

## 数据流

```
每日清晨 (dayTime<1000) → 重置生成计划 + 清点住店游客
1000-8000 生成窗口 → TouristSpawnSystem:
     roll 画像（40/20/20/20）+ 三条 need（等级缩放）+ 停留截止 + travelFund
     不指派目标 → 出生即闲逛
白天 → TouristMoveGoal 周期性 planNextBuilding:
     TouristSimulation.selectNextTarget（视野内 Find-Best-Action）
     → 道路寻路到 entryPoint → 建筑内 spot 单点导航
     → TouristSpotManager.claim（全满排队）→ 做该 spot 动作（duration 倒计时）
     → 释放 spot → 四类交互结算（fillBars + 精力/经济）→ 行程记 VisitMemory
夜晚 (13000-24000) → 满条等离场；未满条 → HotelStayHandler 入住
18000-24000 离场窗口 → processNightDepartures（D6）
     → onTouristDepart: 满条 grantExperience + 法师简历 → registerDeparture(BarRatio)
区块卸载 → TouristSimSystem simStep（直线移动 + 共享 TouristSimulation + D6）
酒馆「招募 NPC」按钮 → MageAttributeRoller（random² 偏斜 + 殖民地等级加成）→ WandscapeNpc 入职
```

## 与道路系统联动

游客生成和移动都依赖道路系统：RoadSavedData 边界路面位置生成，RoadRouter 路网寻路。道路布局直接影响游客流量。

**闲逛与建路系统解耦**：`WANDERING` / POI 兜底的目标选取不依赖 `RoadNetwork` 建路系统，而是把 `wandscape:custom_roads` 标签方块直接当路——玩家用任意该标签内的方块（默认含草径/圆石/石砖/砂土等）铺路即可让游客在路上逛。闲逛规则：

- 目标 = 锚点半径（默认 12 格）内随机的 `custom_roads` 方块；该半径内无路 → 取 2 倍半径内最近的路把游客拉回路上；完全无路 → 锚点附近小范围微逛（绝不跑远）。
- **锚点只沿路漂移**：仅当游客站在路上（脚下是 `custom_roads` 方块）时闲逛区域中心才随动，野外不漂移。
- **硬上限**：离本次闲逛起点超过 32 格强制折返（回到起点附近的路）。
- 道路方块列表数据驱动（`data/wandscape/tags/block/custom_roads.json`，`replace:false` 可被数据包合并扩展）；`RoadBlobExplorer` 也把该标签方块当路由用的"自定义道路"，标签扩充对两者是良性协同。

## 依赖

- shared/data/Activity, shared/data/BarRatio, shared/data/VisitMemory, shared/data/Emotion
- shared/data/ShopConfig, ServiceConfig, RelaxConfig, AtmConfig
- shared/api/TouristApi / shared/event/TouristArrivedEvent/TouristDepartedEvent / WandscapeApis
- shared/data/MageAttributeRoller（法师 7 属性 random² 掷点，游客简历与酒馆招募共用）
- engine/road/RoadSavedData
- building/internal/ShopInteractionHandler, ShopStockManager, TouristSimulation（交互经济）
- Config（TOURIST_NEED_BASE/PER_LEVEL、TOURIST_VISION_RADIUS、TOURIST_STAY_MIN/MAX_DAYS、TOURIST_BAR_GAIN_COEFF、TOURIST_ATM_TRAVEL_FUND_MULTIPLIER 等）
- tavern/internal/TavernRecruitStorage
