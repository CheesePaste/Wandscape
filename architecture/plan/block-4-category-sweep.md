# Block 4 — category + 满意度清扫

> 依赖 Block 0 契约。处理所有**非 scanner、非 tourist-AI** 文件里 `"shop"/"service"` category 字符串与 satisfaction 聚合。本块与 Block 1/2/3 可并行。**不碰** `building/scanner/**`、tourist AI 文件（TouristSimulation/MoveGoal/SimSystem/HotelStayHandler）。

## 目标

1. 满意度聚合迁移：`TouristApi.registerDeparture` 去掉 satisfaction int → 三条聚合；`getAverageSatisfaction` → 三条填充率；统计/指标 HUD 改三条。
2. category 清扫：剩余文件 `"shop"/"service"` → `"interact"` 或 interaction 字段判断。
3. Narrative/TouristCommand 的 category→模板键、调试输出改三条。

## 负责文件

| 文件 | 动作 |
|---|---|
| `shared/api/TouristApi.java` | `registerDeparture` 签名去掉 satisfaction int；`getAverageSatisfaction` 改三条聚合 |
| `tourist/internal/TouristApiImpl.java` | 实现对应改动（注册表存条形聚合） |
| `shared/data/ColonyMetricsSnapshot.java` | 「游客满意度」字段 → 三条聚合（或 min-ratio） |
| `stats/internal/StatisticsCollector.java` | 采集聚合值 |
| `engine/service/ColonyMetricsService.java` | 指标拼装 |
| `building/internal/BuildingApiImpl.java` | shutdown 惩罚 switch 里 `"shop"`/`"service"`（:256-267） |
| `building/internal/BuildingContributionRegistry.java` | 商店有货才贡献三值（:209/:241）→ `trade()!=null && hasStock` |
| `building/internal/DailySettlementSystem.java` | 维护优先级 `shop`=NORMAL（:51-53）→ interact |
| `building/internal/BuildingInteractHandler.java` | UI 分发 `service+maxOccupancy`（:112）/`shop`（:137）→ 按 interaction 字段 |
| `engine/service/AchievementService.java` | `getBuildingsByCategory("service")`（:182）/`"shop"`（:202） |
| `engine/service/GuideProgressService.java` | `hasShopPurchased`→hasCategory("shop")（:98）/`hasServiceInn`（:111） |
| `shared/ui/panel/BuildingSelectionOverlay.java` | 过滤标签 `"service","shop"`（:155） |
| `projection/network/ProjectionNetwork.java` | categoryPriority service=2/shop=3（:82-83） |
| `projection/client/BuildingAreaRenderer.java` | 渲染相关 category 判断 |
| `tourist/internal/NarrativeGenerator.java` | category→事件类型 `"shop"`（:41）/`"service"`（:96/:115）；satisfaction 参数 |
| `tourist/internal/NarrativeTemplates.java` | category→模板键 `"shop"`/`"service"`（:60/:117） |
| `tourist/TouristCommand.java` | 调试命令 service case（:163）/suggest（:213）；satisfaction 输出 |

## 具体改动

### 1. 满意度聚合（去 satisfaction）

- `TouristApi.registerDeparture(UUID touristId, UUID colonyId, int satisfaction)` → 新签名。建议：`registerDeparture(UUID touristId, UUID colonyId, int barRatioPct)`（`barRatioPct = floor(min(ratio_d) × 100)`，调用方=Block 2/3 在离场时算好传入）。或去掉该 int，`getAverageSatisfaction` 改由三条数据聚合。
- `TouristApi.getAverageSatisfaction(colonyId)` → 改为返回三条填充率的聚合（如平均 min-ratio），语义改「三值填充率」。
- `ColonyMetricsSnapshot` 的游客满意度字段 → 同名但语义改为三条聚合（或保留 int 由 `barRatioPct` 填充）。
- `StatisticsCollector/ColonyMetricsService` 跟随。

> 调用方（Block 2 TouristSpawnSystem / Block 3 TouristSimSystem）会传入新签名；本块**统一收口**共享 API，别让 Block 2/3 各自改签名。

### 2. category 清扫（`"shop"`/`"service"` → `"interact"` 或字段判断）

统一原则：
- 「是否游客目标」→ `BuildingConfig.hasInteraction()`（interaction 非 NONE）。
- 「商店（有货）」→ `interaction.trade()!=null`。
- 「旅店」→ `interaction.beds()>0`。
- 纯 category 分组/优先级 → `"interact"`。

各文件落地（依上表逐一）：
- `BuildingApiImpl.shutdownPenalties`：`"shop"` 与 `"service"` 合并进同一组（如 NORMAL 组），值按 interact。
- `BuildingContributionRegistry`：shop-with-stock 判断改 `cfg.hasInteraction() && cfg.interaction().trade()!=null && stock.hasStock(id)`。
- `DailySettlementSystem`：`"shop"` → `"interact"`；service 注释同步。
- `BuildingInteractHandler`：`service && maxOccupancy>0 → 旅店屏` 改 `interaction.beds()>0`；`shop → ShopOpenPacket` 改 `interaction.trade()!=null`；其余按 interaction 分发。
- `AchievementService/GuideProgressService`：category 查询改 `"interact"` 或按 interaction 字段。
- `BuildingSelectionOverlay`：过滤标签改 `"interact"`。
- `ProjectionNetwork`：priority 合并。
- `NarrativeGenerator/NarrativeTemplates`：category→模板键 `"shop"/"service"` → `"interact"`（若模板按 category 区分，可改为按 interaction 特征或统一 interact）；satisfaction 参数改条形聚合。
- `TouristCommand`：`satisfaction` 调试输出 → 三条 sat/need；`service` case/suggest 改。

## Done 判定

1. `./gradlew build` 绿。
2. grep 验证：全仓库（除 Block 5 将删的 BuildingConfig 兼容访问器与 ShopConfig/ServiceConfig 类定义）**无 `"shop"`/`"service"` 作为 category 字符串比较**、无 `getSatisfaction()`/`getTypePreference()` 调用残留。
3. 殖民地 HUD/统计显示三条填充率（或 min-ratio），不再有单一满意度。
4. 旧行为回归：维护结算、成就、引导、建筑交互 UI 均正常。
