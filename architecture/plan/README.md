# 游客经济大改造 — 并行实现方案

> 本文档是本轮大改造的**唯一权威方案**。实现方按 Block 分块并行推进，Block 0 先行（产出共享契约），Block 1-4 并行，Block 5 集成清理。
> **开工前必读**：[goal.md](goal.md) —— 目标效果说明书（玩家/游客体验 + 每个机制的「为什么」+ 非协商红线）。block 文档没写清的细节以 goal.md 为准推断意图，不要自己发挥。
> 每个 block 开工前**先读 `block-0-foundation.md`** 获取共享契约。
> **一阶段不做 category 合并**：`shop`/`service` 保持独立 category（模式预设），新增 `relax`（回复精力）/`atm`（取出钱）两个 category。把四类统一成 `interact` 的 `interaction` 块 → **二阶段延后**，见 [phase-2/README.md](phase-2/README.md)。

## 问题

1. 满意度公式对低于阈值的建筑**扣分**（`sqrt(pref×(threeSum−level×3+1))`）→ 高等级殖民地「普通建筑变负资产」→ 逼玩家只建最强+堆装饰。
2. 游客一天访问量受精力(100)/冷却(2400tick)限制 → 必须次次顶格 +30 → minmax 唯一解。
3. 只有 `shop`/`service` 类别对游客有意义；node/storage/workstation 等纯摆设；观光（POI）是空壳（`setPoiList` 无人调用）。游客「逛累」无处恢复精力、花完钱无法补 → 新增 `relax`（回精力）、`atm`（取钱）两类游客目标。
4. 游客「碰一下建筑就进 CD 然后晃悠」——没有真实交互、没有排队，多建同类型无收益。
5. `tourist_interact_aabb`（多 AABB 交互区）臃肿；shop/service 两套顶层块结构重复（后者 → 二阶段统一成 `interaction` 块）。

## 目标（用户拍板）

- 满意度 = **三条进度条（Comfort/Magic/Wonder）**，按建筑三值填充、无惩罚；**直接去除单一 satisfaction 字段**；**满条才给经验**（开局建筑数量提高兜底）。
- 每条游客有**需求画像**（三值需求比例，如喜欢魔法则 magic 需求更高）→ 自组织多样性。**删除 typePreferences**。**画像总值与等级正相关**：等级越高总需求越高、越难满足（自然难度曲线）。
- **视野限制**：游客目标选择**只看视野内（`TOURIST_VISION_RADIUS`）且已加载**的建筑；视野内无合适目标 → **闲逛**直到出现合适的。
- 精力循环：精力=0 **只能**去恢复建筑（`relax` category：餐厅/澡堂/歇脚处）；**精力 0 且无恢复建筑 → 闲逛**（不离场）；旅店（`service.max_occupancy>0`）= 纯夜晚休息。
- 停留上限 1-3 晚（共 2-4 天），到点强制离开；`visitedBuildings` 停留期不重置。**满条等夜晚再离场**（白天满条先闲逛）。
- `interact_spots`（**每点带动作种类**）取代 `tourist_interact_aabb`，**寻路目标=一个点**；**spot 数量 = 同时交互人数上限**，**交互时长由建筑模式预设块的 `interaction_duration_ticks` 决定**（与 spot 无关），**同建筑不同 spot 动作可不同**；动作只决定游客活动状态/粒子，**各交互效果由 category 模式预设块决定**。
- **category 保持独立**：`shop`（卖物品）/`service`（产元素+精力消耗+床位）/`relax`（回复精力）/`atm`（取出钱）四类各带自己的块；**不合并**（合并见二阶段 phase-2/README.md）。
- 真实交互动作 + **排队机制**（多建同类型=多交互位=高吞吐=排队短）。**排队仅机制，无可见标记**（延后）。
- 扫描器大改：适应新字段（`interact_spots` + 四类模式预设块编辑）；**独立方块 `interact_spot_marker` 放置标记交互位，可右键循环设置动作种类**。
- 精力/经济数值 = 建筑级模式预设字段，扫描器可编辑，**平衡后置**。
- **不保留**旧 `tourist_interact_aabb` 顶层字段的 JSON 兼容解析；`shop`/`service` 块解析保留（二阶段才删）。

## 依赖顺序

```
Block 0 (foundation, 顺序, 必须最先) ──产出共享契约──▶
   ├── Block 1 (scanner + interact_spot_marker)   ║ 四个块文件互斥，可同时开工
   ├── Block 2 (tourist 数据: 三条/画像/停留)       ║
   ├── Block 3 (tourist AI + 交互 + 排队)          ║
   └── Block 4 (category + 满意度清扫)              ║
之后：Block 5 (集成清理, 顺序)
```

## 文件所有权表（任何文件只属一个块，禁止越块修改）

| Block | 文件 |
|---|---|
| **0** | `shared/data/RelaxConfig.java`(新)、`shared/data/AtmConfig.java`(新)、`shared/data/ShopConfig.java`(保留)、`shared/data/ServiceConfig.java`(保留)、`shared/data/Activity.java`(新)、`building/data/BuildingConfig.java`、`Config.java`、`WandscapeConstants.java`、`tourist/internal/TouristStateHost.java`(只增 default 方法)、全部 `data/wandscape/buildings/*.json`、`docs/data/buildings.md` |
| **1** | `building/scanner/**`（BE、Screen、ExportPacket、Renderer、SurvivalScanner*、network 包）、`interact_spot_marker` 方块类+注册（`Wandscape.java`）+ 资源（blockstate/model/lang/recipe/物品模型/创造标签） |
| **2** | `tourist/entity/TouristEntity.java`、`tourist/internal/TouristShadow.java`、`tourist/internal/TouristSpawnSystem.java`、`tourist/network/TouristDataPacket.java`、`tourist/client/TouristScreen.java` |
| **3** | `tourist/internal/TouristStateHost.java`(删遗留方法)、`TouristSimulation.java`、`TouristMoveGoal.java`、`TouristSimSystem.java`、`TouristState.java`、`HotelStayHandler.java`、`TouristSpotManager.java`(新)、`building/internal/ShopStockManager.java`、`ShopInteractionHandler.java` |
| **4** | `shared/api/TouristApi.java`、`tourist/internal/TouristApiImpl.java`、`shared/data/ColonyMetricsSnapshot.java`、`stats/internal/StatisticsCollector.java`、`engine/service/ColonyMetricsService.java`、`building/internal/BuildingApiImpl.java`、`BuildingContributionRegistry.java`、`DailySettlementSystem.java`、`BuildingInteractHandler.java`、`engine/service/AchievementService.java`、`GuideProgressService.java`、`shared/ui/panel/BuildingSelectionOverlay.java`、`projection/network/ProjectionNetwork.java`、`projection/client/BuildingAreaRenderer.java`、`tourist/internal/NarrativeGenerator.java`、`NarrativeTemplates.java`、`TouristCommand.java` |
| **5** | 删除 BuildingConfig 兼容访问器、grep 验证零残留、版本号、全量编译/测试、`architecture/packages/*.md` 更新 |

> Block 5 不再删 `ShopConfig/ServiceConfig`（及二阶段前的 `RelaxConfig/AtmConfig`）——四类块与 `shop()/service()/relax()/atm()` 字段保留到二阶段才统一删除（见 phase-2/README.md）。

## 关键约束

1. **契约一次定死**：Block 0 定义的 TouristStateHost default 方法与 JSON schema 中间**不改**。Block 2/3 靠它并行。
2. **satisfaction/typePreferences 删除顺序**：Block 2 删字段 → Block 3 删接口与调用 → Block 4 删共享消费 → Block 5 验证。Block 0 **保留遗留签名**（`getSatisfaction/setSatisfaction`、`getTypePreference/adjustTypePreference`）做编译桥，勿提前删。
3. **兼容访问器**：BuildingConfig 的 `touristInteractAabb()` 是派生视图（由 interactSpots 算出），保证 Block 0 完成后旧行为不变、全仓库编译。`shop()/service()/relax()/atm()` 是一阶段真实字段，不是访问器。Block 5 只删 `touristInteractAabb()` 派生访问器残留；四类字段/Config 类二阶段删。
4. **Block 2/3 合并前属「开发期临时状态」**：游客填条无实际存储（default 方法返回 0），不要求可玩，只要求各自编译通过。
5. `TouristState` 是**移动状态标签**，禁止扩展为状态机；活动状态走新的 `Activity` 枚举。
6. **interact_spots 不负责效果**：它只标记「有几个交互位、每个什么动作」；产出多少元素/是否购物/回多少精力/取多少钱由 category 模式预设块（service/shop/relax/atm）决定。

## 验证（全改造完成后，Block 5 执行）

- 编译：`./gradlew build`
- 单测：`./gradlew test`（RelaxConfig/AtmConfig 序列化、bar 填充公式、need-gap 评分应有 JUnit）
- 手测（runClient）：
  1. 四类建筑（shop/service/relax/atm，各带模式预设块 + interact_spots）→ 游客导航到 spot、占位、做该 spot 动作、释放；relax 回精力、atm 取钱（travelFund 扣减）
  2. 2 栋同类型 → 排队变短（多建收益）
  3. 精力 0 → 只能去 relax（energy_restore>0）建筑；无恢复建筑时闲逛（不离场）
  4. 视野外建筑不被选为目标；视野内无目标 → 闲逛
  5. 夜晚 → 非满条游客入住 service.max_occupancy>0 建筑；满条游客等夜晚再离场给经验
  6. 停留 2-4 天到点离场；低级小镇满不了条 → 0 经验；高等级游客需求更高更难满
  7. 扫描器：放置 interact_spot_marker、右键循环动作（含 withdraw）、潜行移除、导出新 schema（四类块 + interact_spots）、即时可建
- 回归：旧存档建筑（tourist_interact_aabb 迁移到 interact_spots 后）能加载、可交互。

## 分块文档

| 文档 | 内容 |
|---|---|
| [goal.md](goal.md) | **目标效果说明书**（北极星）：玩家/游客体验 + 每个机制的为什么 + 非协商红线 |
| [block-0-foundation.md](block-0-foundation.md) | 共享契约完整定义（四类模式预设块 / Activity / TouristStateHost / BuildingConfig / JSON schema / Config 键） |
| [block-1-scanner.md](block-1-scanner.md) | 扫描器大改 + interact_spot_marker 方块 |
| [block-2-tourist-data.md](block-2-tourist-data.md) | 游客数据：三条/画像/停留/活动 + travelFund + 去 satisfaction/typePreferences |
| [block-3-tourist-ai.md](block-3-tourist-ai.md) | 游客 AI + 四类交互 + 排队 |
| [block-4-category-sweep.md](block-4-category-sweep.md) | category + 满意度清扫（shop/service 保留，加 relax/atm） |
| [block-5-integration.md](block-5-integration.md) | 集成清理、验证、版本号 |
| [phase-2/README.md](phase-2/README.md) | **二阶段（延后）**：把 shop/service/relax/atm 整合成 interact（interaction 块统一） |

> 每块文档**自包含**：含改动目标、文件清单（带当前代码事实）、消费契约引用、Done 判定、手测步骤。可单独交给一个独立 AI 开工。
