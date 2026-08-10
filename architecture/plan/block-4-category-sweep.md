# Block 4 — category + 满意度清扫（三条表示 / 情绪 / stats / 文案）

> 依赖 Block 0 契约。处理所有**非 scanner、非 tourist-AI（B2 实体数据 / B3 AI）** 文件里的 category switch 与 satisfaction/typePreferences 的**共享消费端**，并把「游客三条需求条」贯穿到 API / 离场事件 / 统计 / HUD / 情绪 / 叙事 / 文案。本块与 Block 1/2/3 可并行。
> **一阶段不合并 category**：`shop`/`service` 保留为独立 category（模式预设），新增 `relax`/`atm`；本块只把四类旅游 category 补进所有 switch/优先级/叙事，并去 satisfaction。合并成 `interact` 见 [phase-2/README.md](phase-2/README.md)。
> **本块定义的收口签名/形状 = B2/B3 的契约**：B2/B3 开工前**必须先读**本文件「本块契约（B2/B3 必须先读）」小节——`registerDeparture`/`TouristDepartedEvent`/`VisitMemory`/`Emotion`/`TransientBubbleStore` 的形状由本块定死，B2/B3 据此写调用，否则并行合并会破。

## 目标

1. **三条表示贯穿**：新建 `shared/data/BarRatio`（三条填充率 0-100），`registerDeparture`/`TouristDepartedEvent`/stats 管道/HUD stats tab 全走三条 ratio。
2. **删单 satisfaction 的共享消费**：`getAverageSatisfaction`（死代码）、`ColonyMetricsSnapshot.averageSatisfaction`、HUD「Sat %s%%」、瞬时头顶条（SatisfactionBarRenderer）。
3. **category 清扫**：所有按 category 的 switch/过滤/优先级补 `"relax"`/`"atm"`（`"shop"`/`"service"` 保留）；「是否游客目标」用 `BuildingConfig.isTouristTarget()` 或四类字段判断。
4. **情绪/叙事对接三条**：Emotion / VisitMemory / AmbientTextPools / NarrativeGenerator / NarrativeEventType 改走三条增量与 min-ratio。
5. **文案/注释清扫**：lang、narratives JSON、guide md、Tavern/ColonyLevel/StatsService 注释。

## 本块契约（B2/B3 必须先读；本块收口，别让 B2/B3 各自改签名）

- **C1 · `shared/data/BarRatio`（本块新建）**：三条填充率 record，`of(comfortSat, comfortNeed, magicSat, magicNeed, wonderSat, wonderNeed)` 用 `floor(sat×100/need)`（need=0 兜底 0），compact 构造 clamp 0-100，`minPct()` = 三值最小值（min-ratio×100，`100 ⟺ 满条`）。纯 record，零 MC / 零 tourist 依赖。B2/B3 在离场时用它构造离场填充率。
- **C2 · `registerDeparture(UUID touristId, UUID colonyId, BarRatio fill)`**（签名形状不变，int→BarRatio）。B2（`TouristSpawnSystem:569`、`TouristEntity:440`）与 B3（`TouristSimSystem:585`）离场时传 `BarRatio.of(最终三条 sat/need)`。
- **C3 · `TouristDepartedEvent(UUID touristId, UUID colonyId, BarRatio fill)`**，`getFill()`；删 `getSatisfaction()`。
- **C4 · `VisitMemory`**：删 `satisfactionBefore`/`satisfactionDelta` → 单个 `barDelta`（三条 ratio 增量之和，0..300），`Emotion.fromDelta(barDelta)` 不变。B3 的 `TouristSimulation.addVisitMemory`（:211）签名去掉 `satBefore`、`satDelta` 改 `barDelta`；B3 在 fillBars 内可得三条 ratio 增量。
- **C5 · `Emotion`**：`fromDelta(int)` 语义改为「三条 ratio 增量之和」（阈值不变）；`fromSatisfaction(int)` **改名 `fromBarRatio(int minPct)`**（阈值不变，离场语调/闲逛气泡用）。B2 的 `NarrativeGenerator.generateDeparture` 实参由 `t.getSatisfaction()` 改为 `BarRatio.of(...).minPct()`。
- **C6 · 瞬时头顶条移除**：`TransientBubbleStore.Event` 删 `satBefore/satAfter`，`trigger()` 同步去参；删 `SAT_ANIM_TICKS`/`satFill`；删 `SatisfactionBarRenderer.java`。B3 的 `sendBubble` 不再传 sat 参数、`TouristRenderer:66` 不再调用 renderBar。
- **C7 · 删 `getAverageSatisfaction`**：`TouristApi` + `TouristApiImpl` + `ColonyMetricsService` + `ColonyMetricsSnapshot.averageSatisfaction` 全删。**理由**：`updateSatisfaction`（impl :85）全仓库无调用方，live 平均恒 0；快照字段无任何消费方（`ColonyStatsSyncPacket` 本来就不带它）。游客三条的统计由离场聚合（stats tab）承载。若日后需要 live「当前游客填充率」，可再加 `getAverageBarRatio` + B3 push（本块不做）。

> 这些契约件是 **B4 最早交付物**（`BarRatio` 是纯 record 无依赖）。并行开工时 B2/B3 按上面形状写调用；若 B2/B3 先合入而 `BarRatio` 未就位，先合并本块的契约件（C1）即可编译。

## 负责文件（所有权表扩展）

| 文件 | 动作 |
|---|---|
| `shared/data/BarRatio.java` | **新建**（契约 C1） |
| `shared/api/TouristApi.java` | `registerDeparture` int→BarRatio；删 `getAverageSatisfaction` |
| `tourist/internal/TouristApiImpl.java` | 同上；`colonyTourists` 值改纯 presence（删 int satisfaction）；删 `updateSatisfaction` |
| `shared/event/TouristDepartedEvent.java` | 载荷 int→BarRatio（C3） |
| `shared/data/ColonyMetricsSnapshot.java` | 删 `averageSatisfaction` 字段 + EMPTY 同步 |
| `engine/service/ColonyMetricsService.java` | 删 :47/:52 avgSatisfaction 聚合 |
| `stats/internal/StatisticsCollector.java` | `totalSatisfaction` map → 三条 total；`onTouristDeparted` 按 `fill` 三值累加 |
| `stats/data/ColonyDailySnapshot.java` | `totalSatisfaction`(int+NBT) → 三条 total + 三条 NBT key |
| `stats/internal/StatisticsData.java` | 三值累加 → 三值平均（÷departed） |
| `stats/data/ColonyStatsSummary.java` | `avgSatisfaction` → `avgComfortRatio/avgMagicRatio/avgWonderRatio` |
| `stats/network/StatsSyncPacket.java` | writeVarInt×3 / read×3 |
| `shared/ui/panel/WandscapePanelState.java` | `StatsSummary.avgSatisfaction` → 三条；EMPTY 同步 |
| `shared/ui/panel/WandscapePanelOverlay.java` | :416「Sat %s%%」→ 三条填充率显示 |
| `shared/data/VisitMemory.java` | 字段改 `barDelta`（C4） |
| `shared/data/Emotion.java` | `fromSatisfaction`→`fromBarRatio`；`fromDelta` 语义改（C5） |
| `shared/client/bubble/AmbientTextPools.java` | :60 情绪来源改 min-ratio |
| `shared/client/bubble/SatisfactionBarRenderer.java` | **删除**（C6） |
| `shared/client/bubble/TransientBubbleStore.java` | Event 去 sat 字段；删 satFill/SAT_ANIM_TICKS |
| `shared/client/bubble/SpeechBubbleRenderer.java` | :127 注释更新（不再引用 SatisfactionBarRenderer） |
| `shared/data/NarrativeEventType.java` | 加 `VISIT_RELAX`/`VISIT_ATM`；删 `PREFERENCE_SHIFT` |
| `tourist/internal/NarrativeGenerator.java` | 四类事件类型映射；离场/milestone 改 min-ratio |
| `tourist/internal/NarrativeTemplates.java` | 加载数据驱动（:114-124 无需改代码；JSON 补键即可） |
| `command/TouristCommand.java` | :67 满意%→三条；:163/:213 case/suggest 补 relax/atm |
| `building/internal/BuildingApiImpl.java` | :255-280 停用惩罚 switch 补 `"relax"`/`"atm"` |
| `building/internal/BuildingContributionRegistry.java` | :209/:241 shop 有货判断改 `cfg.shop()!=ShopConfig.NONE && hasStock` |
| `building/internal/DailySettlementSystem.java` | :43-54 `CATEGORY_PRIORITY` 加 `"relax"`/`"atm"`→NORMAL |
| `building/internal/BuildingInteractHandler.java` | :112/:137 shop/service 保留；relax/atm 走默认分支（无玩家 UI） |
| `engine/service/AchievementService.java` | :182/:202 保持 shop/service 特定成就（不扩四类，决策） |
| `engine/service/GuideProgressService.java` | :98/:111/:123 保持具体 category 判断（不扩四类，决策） |
| `shared/ui/panel/BuildingSelectionOverlay.java` | :155 过滤标签加 `"relax"`,`"atm"` |
| `projection/network/ProjectionNetwork.java` | :77-89 `categoryPriority` 加 `case "relax" -> 3; case "atm" -> 3` |
| `building/client/BuildingAreaRenderer.java` | **README 表路径写错为 `projection/client/`，实际在 `building/client/`**；:101 `touristInteractAabb()`（B0 派生访问器，B5 删）→ 遍历 `interactSpots()` 画点标记 |
| `shared/api/TavernApi.java` / `tourist/internal/TavernRecruitStorage.java` / `building/client/TavernScreen.java` / `shared/data/MageResume.java` | 注释「100% satisfaction」→「三条全满」（逻辑不动） |
| `engine/colony/ColonyLevelManager.java` / `engine/colony/ColonyLevelData.java` / `engine/service/StatsService.java` | 注释同步（:89/:19/:10） |
| 资源 | `assets/wandscape/lang/zh_cn.json`+`en_us.json`、`data/wandscape/narratives/zh_cn.json`、`assets/wandscape/guide/{zh_cn,en}/{tourist_guide,townhall_guide,tavern_guide}.md` |

> 边界：**不碰** `building/scanner/**`（B1）、tourist 实体数据（`TouristEntity`/`TouristShadow`/`TouristSpawnSystem`/`TouristDataPacket`/`TouristScreen`，B2）、tourist AI（`TouristSimulation`/`TouristMoveGoal`/`TouristSimSystem`/`HotelStayHandler`/`ShopStockManager`/`ShopInteractionHandler`/`TouristRenderer`/`ActivityVisuals`/`TouristHumanoidModel`，B3）、`TouristStateHost` 接口方法删改（B3）。

## 具体改动

### A. BarRatio（新建 `shared/data/BarRatio.java`）

```java
package com.wsteam.wandscape.shared.data;

/** 游客三条需求条填充率（0-100，floor(sat×100/need)）。共享 API/事件/统计走它。 */
public record BarRatio(int comfort, int magic, int wonder) {
    public static final BarRatio ZERO = new BarRatio(0, 0, 0);

    public BarRatio {
        comfort = Math.clamp(comfort, 0, 100);
        magic   = Math.clamp(magic,   0, 100);
        wonder  = Math.clamp(wonder,  0, 100);
    }

    /** 由三条 sat/need 算填充率。need≤0 视为 0（防除零）。 */
    public static BarRatio of(int comfortSat, int comfortNeed, int magicSat, int magicNeed,
                              int wonderSat, int wonderNeed) {
        return new BarRatio(pct(comfortSat, comfortNeed), pct(magicSat, magicNeed), pct(wonderSat, wonderNeed));
    }

    private static int pct(int sat, int need) {
        return need <= 0 ? 0 : (int) Math.floor(sat * 100.0 / need);
    }

    /** min-ratio×100：三条最短板；100 ⟺ 满条（isFullySatisfied）。离场语调/闲逛情绪用。 */
    public int minPct() { return Math.min(Math.min(comfort, magic), wonder); }
}
```

### B. 离场事件 / API

- `TouristApi`：`registerDeparture(UUID, UUID, BarRatio fill)`；**删** `getAverageSatisfaction(UUID)`。
- `TouristApiImpl`：`colonyTourists` 值由 `Integer satisfaction` 改纯 presence（`Map<UUID, Set<UUID>>` 或保持 map 忽略值）；删 `getAverageSatisfaction`/`updateSatisfaction`；`registerDeparture` 把 `BarRatio` 传进事件。
- `TouristDepartedEvent`：字段 `int satisfaction` → `BarRatio fill`；`getSatisfaction()` → `getFill()`。
- `ColonyMetricsService`：删 :47/:52 的 avgSatisfaction 聚合与快照参数。
- `ColonyMetricsSnapshot`：删 `averageSatisfaction` 字段；`EMPTY` 同步（`.touristFill` 不留——live avg 是死代码，见 C7）。`ColonyStatsSyncPacket` 本来就不带该字段，**无需改 packet**。

> 事件消费方同步：`StatisticsCollector.onTouristDeparted`（见 C）改三值累加；`PanelStateTracker:64` 只用 `getColonyId()` 刷新 HUD，不受影响。

### C. stats 管道 → 三条

- `StatisticsCollector`：`Map<UUID,Integer> totalSatisfaction` → 三个 `Map<UUID,Integer> touristComfortTotal/touristMagicTotal/touristWonderTotal`（或单 record map）。`onTouristDeparted` 对 `event.getFill()` 三值各 `merge`。`onDailySettlement` 把三 total 写进 `ColonyDailySnapshot`；重置清零同步三 map。
- `ColonyDailySnapshot`（stats/data）：`int totalSatisfaction` + `TAG_TOTAL_SATISFACTION` → `int touristComfortTotal/touristMagicTotal/touristWonderTotal` + NBT key `tourist_comfort_total`/`tourist_magic_total`/`tourist_wonder_total`。**旧存档 daily snapshot 读新 key 缺省 0**（仅历史统计归零，无碍；不写兼容迁移）。
- `StatisticsData.computeSummary`（:93-127）：`totalSatisfaction += ...` → 三 total 累加；`avgSatisfaction = total/departed` → 三维各自 `total/departed`（departed=0 时 0）。
- `ColonyStatsSummary`：`int avgSatisfaction` → `int avgComfortRatio/avgMagicRatio/avgWonderRatio`。
- `StatsSyncPacket`：`writeVarInt(s.avgSatisfaction())` → 3 个 `writeVarInt`；`read` 同步 3 个。
- `WandscapePanelState.StatsSummary`：`int avgSatisfaction` → 3 int；`EMPTY` 构造同步。
- `WandscapePanelOverlay` :416：`"Sat: %s%%"` 一行 → 三条填充率（复用 `drawStatBar` 或三行 `gui.wandscape.stats.tourist_comfort/magic/wonder`）。
- lang：删 `gui.wandscape.stats.satisfaction`（zh:271 / en:283），加三条 key。

### D. 情绪 / 叙事

- `VisitMemory`：record 组件删 `satisfactionBefore`/`satisfactionDelta` → `int barDelta`；`Builder` 同步（`barDelta(v)`），`build()` 用 `Emotion.fromDelta(barDelta)`。`barDelta = Σ_d (ratio_d_after − ratio_d_before)`，B3 在 fillBars 内算。
- `Emotion`：`fromDelta(int delta)` 注释语义改「三条 ratio 增量之和」（阈值不变）；`fromSatisfaction(int)` → `fromBarRatio(int minPct)`（阈值不变，0-100）。
- `AmbientTextPools` :60：`Emotion.fromSatisfaction(tourist.getSatisfaction())` → 用 `tourist.getComfortSat()/getComfortNeed()` 等（B0 default 方法，B2 已实现）算 `minPct`，再 `Emotion.fromBarRatio(minPct)`。辅助内联（本类已 import `TouristEntity`）。
- `NarrativeEventType`：加 `VISIT_RELAX(false)`、`VISIT_ATM(false)`；**删 `PREFERENCE_SHIFT(true)`**（typePreferences 残留，全仓库无 producer）。
- `NarrativeGenerator`：
  - `generateVisit` :41-43：`"shop".equals(cat) ? VISIT_SHOP : VISIT_SERVICE` → 四类映射（shop→VISIT_SHOP、service→VISIT_SERVICE、relax→VISIT_RELAX、atm→VISIT_ATM；未知→VISIT_SERVICE 兜底）。
  - `generateDeparture` :66-81：参数 `int satisfaction` → `int minRatioPct`；`Emotion.fromSatisfaction(satisfaction)` → `Emotion.fromBarRatio(minRatioPct)`。调用方 B2（`TouristSpawnSystem:579`）传 `BarRatio.of(...).minPct()`。
  - `generateSatisfactionMilestone` :125-140：**先 grep 调用方**——当前无 producer（疑似死代码）。无调用方则删方法；有则阈值改 min-ratio（100/70/50）与 `satisfaction_milestone_*` 模板。
- `NarrativeTemplates`：加载是数据驱动（:114-124 读 JSON 任意 category 键），**代码不改**。补 JSON 键（见 G）。
- `narratives/zh_cn.json`：`category_templates` 加 `"relax"`（`visit`）、`"atm"`（`visit`）模板（文案自拟：歇脚/放松、取现/理财）；`satisfaction_milestone_50/70/100` 模板文案改「三条需求」表述或随 D 的 milestone 决策一并处理。

### E. 瞬时头顶条移除

- 删 `shared/client/bubble/SatisfactionBarRenderer.java`。
- `TransientBubbleStore`：`Event` record 删 `satBefore/satAfter`；`trigger()` 删两参；删 `SAT_ANIM_TICKS`、`satFill()`；`ICON_NONE` 注释改「无图标，仅气泡」。
- `SpeechBubbleRenderer` :127 注释删除对 SatisfactionBarRenderer 的引用。
- B3 侧（本块只定义）：`sendBubble` 不再传 sat 参数；`TouristRenderer:66` 不再调 renderBar。

### F. category 清扫（保留 shop/service，新增 relax/atm）

统一原则：
- 「是否游客目标」→ `BuildingConfig.isTouristTarget()`（四类块任一非 NONE）。
- 「商店（有货）」→ `cfg.shop()!=ShopConfig.NONE && hasStock`。
- 「旅店」→ `cfg.service()!=null && cfg.service().maxOccupancy()>0`。
- 「恢复建筑」→ `cfg.relax()!=RelaxConfig.NONE && energyRestore>0`（B3 用）。
- 「取钱建筑」→ `cfg.atm()!=AtmConfig.NONE && withdrawAmount>0`（B3 用）。
- 纯 category 分组/优先级 → 四类并列。

各文件落地（依上表逐一）：
- `BuildingApiImpl.applyShutdownPenalties`（:255-280）：第一组 `"shop","basic","government","storage","tavern"` → **加 `"relax","atm"`**（停用即零贡献，与 shop 同组）。service/decoration/wonder/workstation/node 分支不动。
- `DailySettlementSystem.CATEGORY_PRIORITY`（:43-54）：加 `"relax"`→NORMAL、`"atm"`→NORMAL（与 shop/tavern 并列；注释同步）。
- `BuildingContributionRegistry`：:209/:241 `"shop".equals(cat)` → `cfg.shop()!=ShopConfig.NONE && shopHasStock`（按块判断更稳；relax/atm 走 else 基础三值，**不变**——它们天然贡献基础 comfort/magic/wonder）。
- `BuildingInteractHandler`：:112 `"service" && maxOccupancy>0 → 旅店屏`、:137 `"shop" → ShopOpenPacket`、:148 tavern、:163 altar **保留**；`"relax"/"atm"` → 落入默认分支（不弹特殊 UI，游客专用；可加提示文案「仅供游客交互」）。
- `AchievementService`（:182/:202）：**保持** shop/service 特定成就（FULL_HOUSE/全商铺货满），不扩 relax/atm——成就是对具体建筑类型的里程碑，非「游客目标」判断。
- `GuideProgressService`（:98/:111/:123）：`hasShopPurchased`/`hasServiceInn`/`hasTavernRecruited` 保持具体 category 判断（hasServiceInn 用 `service().maxOccupancy()>0`，本就正确），不扩四类。
- `BuildingSelectionOverlay`（:155）：过滤标签列表加 `"relax"`,`"atm"`（四类游客目标可筛）。
- `ProjectionNetwork.categoryPriority`（:79-88）：加 `case "relax" -> 3; case "atm" -> 3;`（与 shop 同档）。
- `BuildingAreaRenderer`（**实际路径 `building/client/`**，README 表误写 `projection/client/`）：:98-101 遍历 `config.touristInteractAabb()`（B0 派生访问器，B5 删）→ 改遍历 `config.interactSpots()` 画点标记（B0 后每 spot 是一个单点 box）。
- `BuildingDebugRequestPacket`（projection/network:55）：shop 专属调试，**无需补四类**（决策）。
- `TouristCommand`：:67 单行 `满意%d%%` → 三条 `sat/need + ratio`；:163 case / :213 suggest 补 `"relax"`/`"atm"`。

### G. 文案 / 注释

- **lang**（zh_cn/en_us）：删 `gui.wandscape.stats.satisfaction`；`gui.wandscape.townhall.exp_source`「游客满意度100%」→「游客三条需求全满」；`gui.wandscape.tavern.resume_hint`「法师满意度达到100%」→「法师游客三条需求全满」。
- **guide md**（`guide/{zh_cn,en}/{tourist_guide,townhall_guide,tavern_guide}.md` 共 6 文件）：`Satisfaction Stat`（0-100%）行 → 三条需求条说明（Comfort/Magic/Wonder，满条才离场给经验/留简历）。
- **narratives JSON**：见 D。
- **注释**：`TavernScreen:25/:141`、`TavernApi:14`、`TavernRecruitStorage:22`、`MageResume:5`、`ColonyLevelManager:89`、`ColonyLevelData:19`、`StatsService:10` 的「100% satisfaction」→「三条全满 / three bars full」。**逻辑不动**（法师简历招募门槛由 B2/B3 的 `isFullySatisfied()` 控制）。

## B2/B3 协同触点（本块只定义形状，B2/B3 落实）

| 触点 | 形状（B4 定） | 落实方 |
|---|---|---|
| `registerDeparture` 实参 | `BarRatio.of(最终三条)` | B2（SpawnSystem:569、Entity:440）/ B3（SimSystem:585） |
| `NarrativeGenerator.generateDeparture` 实参 | `minRatioPct = BarRatio.minPct()` | B2（SpawnSystem:579） |
| `addVisitMemory` 签名 | 去 `satBefore`、`satDelta`→`barDelta`（三条 ratio 增量之和） | B3（TouristSimulation:211、MoveGoal:644/1348/1375、SimSystem:476/499） |
| `TransientBubbleStore.trigger` | 去 sat 两参 | B3（sendBubble 调用） |
| `SatisfactionBarRenderer` | 已删，别再调用 | B3（TouristRenderer:66 删调用） |
| 气泡/闲逛情绪 | `Emotion.fromBarRatio(minPct)` | B4（AmbientTextPools）——B2 删 `getSatisfaction` 后本类改用三条 |

> **依赖顺序**：B4 的 `BarRatio` + C2-C6 形状是最早交付件；B2/B3 在写 `registerDeparture`/`addVisitMemory`/`sendBubble` 前先按此写。B4 自身编译不依赖 B2/B3 实体实现（只依赖 B0 的 `TouristStateHost` default 方法与 `isTouristTarget()`）。

## Done 判定

1. `./gradlew build` 绿（与 B2/B3 合入后全仓绿；本块自身编译无错）。
2. grep 验证（B4 范围 = 共享消费端）：`shared/**`、`stats/**`、`engine/**`、`building/internal`（除 scanner）、`command/**` 内**无** `getSatisfaction()`/`getTypePreference()`/`adjustTypePreference()`/`averageSatisfaction`/`SatisfactionBarRenderer`/`PREFERENCE_SHIFT` 引用残留（B2/B3 实体/AI 文件由 B2/B3 保证，B5 统一验证）。
3. stats tab 显示三条填充率（Comfort/Magic/Wonder），不再有单「Sat %s%%」；`ColonyMetricsSnapshot` 无 `averageSatisfaction`。
4. 需要四类的 switch 全部补进 `"relax"`/`"atm"`（BuildingApiImpl/DailySettlement/BuildingSelectionOverlay/ProjectionNetwork/NarrativeGenerator/TouristCommand）。
5. 情绪/叙事走三条：离场语调 = min-ratio、交互情绪 = 三条增量之和；`category_templates` 有 `"relax"`/`"atm"`。
6. 瞬时头顶条已删；`TransientBubbleStore.Event` 无 sat 字段。
7. 回归：维护结算、成就、引导、建筑交互 UI、酒馆招募、经验（满条才给）均正常；relax/atm 建筑可被游客选为目标。

## 手测（runClient）

1. stats tab 显示三条填充率；游客头顶无瞬时 XP 条（气泡仍在）。
2. 商店/澡堂/ATM 三栋：游客交互后——商店气泡+叙事（VISIT_SHOP）、澡堂叙事（VISIT_RELAX）、ATM 叙事（VISIT_ATM）；闲逛气泡情绪随 min-ratio 变化。
3. 造满三条的游客夜晚离场 → 经验 + 酒馆简历；文案显示「三条全满」。
4. 维护结算（含 relax/atm 停用惩罚）、成就（FULL_HOUSE/全商铺满）、引导（旅店住宿）不回归。
5. 旧存档（B0 迁移后）stats 历史 daily snapshot 加载不崩（新 key 缺省 0）。
