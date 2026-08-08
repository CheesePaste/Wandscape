# 游客经济大改造 — 并行实现方案

> 本文档是本轮大改造的**唯一权威方案**。实现方按 Block 分块并行推进，Block 0 先行（产出共享契约），Block 1-4 并行，Block 5 集成清理。
> **开工前必读**：[goal.md](goal.md) —— 目标效果说明书（玩家/游客体验 + 每个机制的「为什么」+ 非协商红线）。block 文档没写清的细节以 goal.md 为准推断意图，不要自己发挥。
> 每个 block 开工前**先读 `block-0-foundation.md`** 获取共享契约。

## 问题

1. 满意度公式对低于阈值的建筑**扣分**（`sqrt(pref×(threeSum−level×3+1))`）→ 高等级殖民地「普通建筑变负资产」→ 逼玩家只建最强+堆装饰。
2. 游客一天访问量受精力(100)/冷却(2400tick)限制 → 必须次次顶格 +30 → minmax 唯一解。
3. 只有 `shop`/`service` 类别对游客有意义；node/storage/workstation 等纯摆设；观光（POI）是空壳（`setPoiList` 无人调用）。
4. 游客「碰一下建筑就进 CD 然后晃悠」——没有真实交互、没有排队，多建同类型无收益。
5. `tourist_interact_aabb`（多 AABB 交互区）+ `shop{}`/`service{}` 两套顶层块结构臃肿。

## 目标（用户拍板）

- 满意度 = **三条进度条（Comfort/Magic/Wonder）**，按建筑三值填充、无惩罚；**直接去除单一 satisfaction 字段**；**满条才给经验**（开局建筑数量提高兜底）。
- 每条游客有**需求画像**（三值需求比例，如喜欢魔法则 magic 需求更高）→ 自组织多样性。**删除 typePreferences**。**画像总值与等级正相关**：等级越高总需求越高、越难满足（自然难度曲线）。
- **视野限制**：游客目标选择**只看视野内（`TOURIST_VISION_RADIUS`）且已加载**的建筑；视野内无合适目标 → **闲逛**直到出现合适的（不跨城镇寻路到远处建筑）。
- 精力循环：精力=0 **只能**去恢复建筑（白天餐厅/澡堂恢复）；**精力 0 且无恢复建筑 → 闲逛**（不离场）；旅店=纯夜晚休息。
- 停留上限 1-3 晚（共 2-4 天），到点强制离开；`visitedBuildings` 停留期不重置。**满条等夜晚再离场**（白天满条先闲逛）。
- `interaction` 块取代 `shop`/`service`；`category` 合并为 `interact`；`interact_spots`（**每点带动作种类**）取代 `tourist_interact_aabb`，**寻路目标=一个点**。
- 真实交互动作 + **排队机制**（多建同类型=多交互位=高吞吐=排队短）。**排队仅机制，无可见标记**（延后）。
- 扫描器大改：适应新字段；**独立方块 `interact_spot_marker` 放置标记交互位，可右键循环设置动作种类**。
- 精力/经济数值 = 建筑级 `interaction` 字段，扫描器可编辑，**平衡后置**。
- **不保留**旧 shop/service 顶层字段的 JSON 兼容解析。

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
| **0** | `shared/data/InteractionConfig.java`(新)、`shared/data/ShopConfig.java`、`shared/data/ServiceConfig.java`、`building/data/BuildingConfig.java`、`Config.java`、`WandscapeConstants.java`、`tourist/internal/TouristStateHost.java`(只增 default 方法)、`shared/data/Activity.java`(新)、全部 `data/wandscape/buildings/*.json`、`docs/data/buildings.md` |
| **1** | `building/scanner/**`（BE、Screen、ExportPacket、Renderer、SurvivalScanner*、network 包）、`interact_spot_marker` 方块类+注册（`Wandscape.java`）+ 资源（blockstate/model/lang/recipe/物品模型/创造标签） |
| **2** | `tourist/entity/TouristEntity.java`、`tourist/internal/TouristShadow.java`、`tourist/internal/TouristSpawnSystem.java`、`tourist/network/TouristDataPacket.java`、`tourist/client/TouristScreen.java` |
| **3** | `tourist/internal/TouristStateHost.java`(删遗留方法)、`TouristSimulation.java`、`TouristMoveGoal.java`、`TouristSimSystem.java`、`TouristState.java`、`HotelStayHandler.java`、`TouristSpotManager.java`(新)、`building/internal/ShopStockManager.java`、`ShopInteractionHandler.java` |
| **4** | `shared/api/TouristApi.java`、`tourist/internal/TouristApiImpl.java`、`shared/data/ColonyMetricsSnapshot.java`、`stats/internal/StatisticsCollector.java`、`engine/service/ColonyMetricsService.java`、`building/internal/BuildingApiImpl.java`、`BuildingContributionRegistry.java`、`DailySettlementSystem.java`、`BuildingInteractHandler.java`、`engine/service/AchievementService.java`、`GuideProgressService.java`、`shared/ui/panel/BuildingSelectionOverlay.java`、`projection/network/ProjectionNetwork.java`、`projection/client/BuildingAreaRenderer.java`、`tourist/internal/NarrativeGenerator.java`、`NarrativeTemplates.java`、`TouristCommand.java` |
| **5** | 删除 BuildingConfig 兼容访问器、删 ShopConfig/ServiceConfig、grep 验证零残留、版本号、全量编译/测试、`architecture/packages/*.md` 更新 |

## 关键约束

1. **契约一次定死**：Block 0 定义的 TouristStateHost default 方法与 JSON schema 中间**不改**。Block 2/3 靠它并行。
2. **satisfaction/typePreferences 删除顺序**：Block 2 删字段 → Block 3 删接口与调用 → Block 4 删共享消费 → Block 5 验证。Block 0 **保留遗留签名**（`getSatisfaction/setSatisfaction`、`getTypePreference/adjustTypePreference`）做编译桥，勿提前删。
3. **兼容访问器**：BuildingConfig 的 `shop()/service()/touristInteractAabb()` 是派生视图（由 interaction/interact_spots 算出），保证 Block 0 完成后旧行为不变、全仓库编译。Block 5 删除。
4. **Block 2/3 合并前属「开发期临时状态」**：游客填条无实际存储（default 方法返回 0），不要求可玩，只要求各自编译通过。
5. `TouristState` 是**移动状态标签**，禁止扩展为状态机；活动状态走新的 `Activity` 枚举。

## 验证（全改造完成后，Block 5 执行）

- 编译：`./gradlew build`
- 单测：`./gradlew test`（InteractionConfig 序列化、bar 填充公式、need-gap 评分应有 JUnit）
- 手测（runClient）：
  1. interact 建筑（interaction+interact_spots）→ 游客导航到 spot、占位、做该 spot 动作、释放
  2. 2 栋同类型 → 排队变短（多建收益）
  3. 精力 0 → 只能去 energy>0 建筑；无恢复建筑时闲逛（不离场）
  4. 视野外建筑不被选为目标；视野内无目标 → 闲逛
  5. 夜晚 → 非满条游客入住 beds 建筑；满条游客等夜晚再离场给经验
  6. 停留 2-4 天到点离场；低级小镇满不了条 → 0 经验；高等级游客需求更高更难满
  7. 扫描器：放置 interact_spot_marker、右键循环动作、潜行移除、导出新 schema、即时可建
- 回归：旧存档建筑（category=interact 迁移后）能加载、可交互。

## 分块文档

| 文档 | 内容 |
|---|---|
| [goal.md](goal.md) | **目标效果说明书**（北极星）：玩家/游客体验 + 每个机制的为什么 + 非协商红线 |
| [block-0-foundation.md](block-0-foundation.md) | 共享契约完整定义（InteractionConfig / Activity / TouristStateHost / BuildingConfig / JSON schema / Config 键） |
| [block-1-scanner.md](block-1-scanner.md) | 扫描器大改 + interact_spot_marker 方块 |
| [block-2-tourist-data.md](block-2-tourist-data.md) | 游客数据：三条/画像/停留/活动 + 去 satisfaction/typePreferences |
| [block-3-tourist-ai.md](block-3-tourist-ai.md) | 游客 AI + 交互 + 排队 |
| [block-4-category-sweep.md](block-4-category-sweep.md) | category + 满意度清扫 |
| [block-5-integration.md](block-5-integration.md) | 集成清理、验证、版本号 |

> 每块文档**自包含**：含改动目标、文件清单（带当前代码事实）、消费契约引用、Done 判定、手测步骤。可单独交给一个独立 AI 开工。
