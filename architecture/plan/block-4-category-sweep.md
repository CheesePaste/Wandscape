# Block 4 — category + 满意度清扫

> 依赖 Block 0 契约。处理所有**非 scanner、非 tourist-AI** 文件里 category 相关 switch 与 satisfaction 聚合。本块与 Block 1/2/3 可并行。**不碰** `building/scanner/**`、tourist AI 文件（TouristSimulation/MoveGoal/SimSystem/HotelStayHandler）。
> **一阶段不合并 category**：`shop`/`service` 保留为独立 category，新增 `relax`/`atm`；本块只把四类旅游 category 补进所有 switch/优先级/叙事，并去 satisfaction。

## 目标

1. 满意度聚合迁移：`TouristApi.registerDeparture` 去掉 satisfaction int → 三条聚合；`getAverageSatisfaction` → 三条填充率；统计/指标 HUD 改三条。
2. category 清扫：所有按 category 的 switch/过滤/优先级补上 `"relax"`、`"atm"`（`"shop"`/`"service"` 保留）；「是否游客目标」用 `BuildingConfig.isTouristTarget()` 或四类字段判断。
3. Narrative/TouristCommand 的 category→模板键、调试输出改三条 + 四类。

## 负责文件

| 文件 | 动作 |
|---|---|
| `shared/api/TouristApi.java` | `registerDeparture` 签名去掉 satisfaction int；`getAverageSatisfaction` 改三条聚合 |
| `tourist/internal/TouristApiImpl.java` | 实现对应改动（注册表存条形聚合） |
| `shared/data/ColonyMetricsSnapshot.java` | 「游客满意度」字段 → 三条聚合（或 min-ratio） |
| `stats/internal/StatisticsCollector.java` | 采集聚合值 |
| `engine/service/ColonyMetricsService.java` | 指标拼装 |
| `building/internal/BuildingApiImpl.java` | shutdown 惩罚 switch（:256-267）补 `"relax"`/`"atm"` |
| `building/internal/BuildingContributionRegistry.java` | shop 有货才贡献三值（:209/:241）→ `cfg.shop()!=NONE && hasStock`；relax/atm 按基础三值 |
| `building/internal/DailySettlementSystem.java` | 维护优先级（:51-53）补 relax/atm |
| `building/internal/BuildingInteractHandler.java` | UI 分发 `service+maxOccupancy`（:112）/`shop`（:137）→ shop/service 保留，relax/atm 不弹特殊 UI（游客专用） |
| `engine/service/AchievementService.java` | `getBuildingsByCategory("service")`（:182）/`"shop"`（:202）→ 补 relax/atm（或改四类判断） |
| `engine/service/GuideProgressService.java` | `hasShopPurchased`→hasCategory("shop")（:98）/`hasServiceInn`（:111）→ 补 relax/atm |
| `shared/ui/panel/BuildingSelectionOverlay.java` | 过滤标签（:155）加 `"relax","atm"` |
| `projection/network/ProjectionNetwork.java` | categoryPriority（:82-83）补 relax/atm |
| `projection/client/BuildingAreaRenderer.java` | 渲染相关 category 判断补 relax/atm |
| `tourist/internal/NarrativeGenerator.java` | category→事件类型（:41/:96/:115）补 relax/atm；satisfaction 参数 |
| `tourist/internal/NarrativeTemplates.java` | category→模板键（:60/:117）补 relax/atm |
| `tourist/TouristCommand.java` | 调试命令（:163/:213）补 relax/atm；satisfaction 输出改三条 |

## 具体改动

### 1. 满意度聚合（去 satisfaction）

- `TouristApi.registerDeparture(UUID touristId, UUID colonyId, int satisfaction)` → 新签名。建议：`registerDeparture(UUID touristId, UUID colonyId, int barRatioPct)`（`barRatioPct = floor(min(ratio_d) × 100)`，调用方=Block 2/3 在离场时算好传入）。或去掉该 int，`getAverageSatisfaction` 改由三条数据聚合。
- `TouristApi.getAverageSatisfaction(colonyId)` → 改为返回三条填充率的聚合（如平均 min-ratio），语义改「三值填充率」。
- `ColonyMetricsSnapshot` 的游客满意度字段 → 同名但语义改为三条聚合（或保留 int 由 `barRatioPct` 填充）。
- `StatisticsCollector/ColonyMetricsService` 跟随。

> 调用方（Block 2 TouristSpawnSystem / Block 3 TouristSimSystem）会传入新签名；本块**统一收口**共享 API，别让 Block 2/3 各自改签名。

### 2. category 清扫（保留 shop/service，新增 relax/atm 到各 switch）

统一原则：
- 「是否游客目标」→ `BuildingConfig.isTouristTarget()`（四类任一非 NONE）。
- 「商店（有货）」→ `cfg.shop()!=ShopConfig.NONE && hasStock`。
- 「旅店」→ `cfg.service().maxOccupancy()>0`。
- 「恢复建筑」→ `cfg.relax()!=RelaxConfig.NONE && energyRestore>0`。
- 「取钱建筑」→ `cfg.atm()!=AtmConfig.NONE && withdrawAmount>0`。
- 纯 category 分组/优先级 → 四类并列（shop/service/relax/atm）。

各文件落地（依上表逐一）：
- `BuildingApiImpl.shutdownPenalties`：`"shop"`/`"service"` 分支保留，新增 `"relax"`、`"atm"` 分支（同一组，如 NORMAL）。
- `BuildingContributionRegistry`：shop-with-stock 判断改 `cfg.shop()!=NONE && stock.hasStock(id)`；relax/atm 建筑按基础三值贡献（无货品加成）。
- `DailySettlementSystem`：维护优先级 `shop`=NORMAL（:51-53）→ `"shop"/"service"/"relax"/"atm"` 并列 NORMAL；service 注释同步。
- `BuildingInteractHandler`：`service && maxOccupancy>0 → 旅店屏` 保留；`shop → ShopOpenPacket` 保留；`relax`/`atm` → 不弹特殊 UI（游客专用，玩家右键给提示或无动作）。
- `AchievementService/GuideProgressService`：category 查询补 `"relax"`/`"atm"`（`hasShopPurchased`/`hasServiceInn` 之外按需加 relax/atm 条件）。
- `BuildingSelectionOverlay`：过滤标签补 `"relax"`/`"atm"`。
- `ProjectionNetwork`：categoryPriority 补 relax/atm（与 shop/service 同优先级）。
- `NarrativeGenerator/NarrativeTemplates`：category→模板键补 `"relax"`/`"atm"`（relax=歇脚/放松、atm=取钱/理财）；satisfaction 参数改条形聚合。
- `TouristCommand`：`satisfaction` 调试输出 → 三条 sat/need；service case/suggest 补 relax/atm。

## Done 判定

1. `./gradlew build` 绿。
2. grep 验证：全仓库（除二阶段将删的 BuildingConfig 兼容访问器）**无 `getSatisfaction()`/`getTypePreference()` 调用残留**；`"shop"`/`"service"` category 字符串**保留且合理**，`"relax"`/`"atm"` 已补进所有需要四类的 switch。
3. 殖民地 HUD/统计显示三条填充率（或 min-ratio），不再有单一满意度。
4. 旧行为回归：维护结算、成就、引导、建筑交互 UI 均正常；relax/atm 建筑可被游客选择为交互目标。
