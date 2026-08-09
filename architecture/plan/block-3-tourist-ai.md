# Block 3 — 游客 AI + 四类交互 + 排队

> 依赖 Block 0 契约（四类模式预设块 / Activity / TouristStateHost）+ Block 2 的数据（经接口调用，**不碰实体文件**）。这是最大的块。本块与 Block 1/2/4 可并行。所有游客行为逻辑：三条填充、Find-Best-Action 目标选择、spots 排队做动作、四类交互（购物/产元素/回精力/取钱）、旅店、离开规则。
> **一阶段不统一四类交互**：`performShopInteraction`/`performServiceInteraction` 保留，新增 `performRelaxInteraction`（回精力）/`performAtmInteraction`（取钱）。统一成 `performInteraction` 属二阶段（phase-2/README.md）。

## 目标

1. `satisfactionGain` → `fillBars`（无惩罚填三条）；四类交互各自结算（shop 购物 / service 产元素+耗精力 / relax 回精力 / atm 取钱）。
2. 目标选择 = Find-Best-Action（需求缺口 × 建筑值 + 精力 + 排队惩罚），**无 typePreferences**，**只看视野内**。
3. spots 单点寻路 + 占用/活动/释放 + 排队（`TouristSpotManager`）。
4. 旅店改 `service.maxOccupancy>0` 判定，删 sat≥50 门槛，按 D6 离场。
5. 从 `TouristStateHost` **删除** `getSatisfaction/setSatisfaction`、`getTypePreference/adjustTypePreference`（迁移完调用点后）。

## 负责文件

| 文件 | 动作 |
|---|---|
| `tourist/internal/TouristSimulation.java` | fillBars / 四类交互 / Find-Best-Action / 删 pref |
| `tourist/internal/TouristMoveGoal.java` | spot 单点导航 + 占用/活动/释放 + 排队 + 删 AABB/匹配分/pref |
| `tourist/internal/TouristSimSystem.java` | 镜像交互（共享 TouristSimulation）+ 离开规则 + registerDeparture 新签名 |
| `tourist/internal/HotelStayHandler.java` | `service.maxOccupancy>0` 判定 + nightsStayed + 删 sat≥50 |
| `tourist/internal/TouristSpotManager.java` | 新建：spot 占用/队列/等待超时 |
| `tourist/internal/TouristStateHost.java` | 删遗留方法 |
| `tourist/internal/TouristState.java` | 不动（移动标签） |
| `tourist/client/ActivityVisuals.java` | 新建：Activity → (Pose/骨骼目标角度/粒子) 注册表，未知动作兜底 browse |
| `tourist/client/TouristHumanoidModel.java` | 新建：HumanoidModel 子类，setupAnim 按当前 Activity 插值姿态 |
| `tourist/client/TouristRenderer.java` | 改用 TouristHumanoidModel + 按 activity 发射粒子 |
| `building/internal/ShopStockManager.java` | 按 `cfg.shop()` 工作 |
| `building/internal/ShopInteractionHandler.java` | 同 |

## 关键概念

- **fillBars**：`sat_d += round(value_d × TOURIST_BAR_GAIN_COEFF)`，封顶 `need_d`。无惩罚。
- **isFullySatisfied()**（Block 2 实现）：三条 ratio 全 1。经验/法师简历仅满条**夜晚**离场触发。
- **Find-Best-Action**：`score(b) = Σ_d max(0,(need_d−sat_d)) × value_d × coeff + energyUrgency − queuePenalty`。
- **视野（vision）**：目标选择**只看 `TOURIST_VISION_RADIUS` 内且已加载**的可交互建筑。视野内无合适目标 → **闲逛**，直到视野出现合适的。
- **四类交互（category 模式预设）**：
  - `shop`：购物（钱包买货，殖民地收元素）；精力 -20（沿用旧硬编码）。
  - `service`：产元素 + 消耗精力（`energyPerUse`）；`maxOccupancy>0` 为旅店（夜晚）。
  - `relax`：回复精力（`energyRestore`，clamp 到 `TOURIST_MAX_ENERGY`）——精力循环的「恢复建筑」。
  - `atm`：取钱（`amount = min(withdrawAmount, travelFund)`，`wallet += amount`、`travelFund -= amount`）。
- **spot 占用**：`TouristSpotManager` 按 buildingId 记每个 spot 下标被谁占用；**spot 总数 = 该建筑同时交互人数上限**；全满 → 排队；等待超 `TOURIST_QUEUE_WAIT_TOLERANCE_TICKS` 放弃。**排队仅机制，无可见标记**（用户明确延后）。
- **duration 由建筑定**：游客站 spot 的时长 = 该建筑模式预设块的 `interaction_duration_ticks`（不是每个 spot 单独设）。
- **动作在 spot 上**：同建筑不同 spot 可设不同 action（如面包店 browse/eat 两个 spot），游客做所在 spot 的动作；`setActivityTicks(duration)` 用建筑级 duration。

## 具体改动

### 1. TouristSimulation（当前 :70-177, :231-331）

- `effectiveValues`（:70-85）：保留（三值 + shop 货品加成）。
- **删除** `satisfactionGain`（:98-113，含阈值惩罚）→ 新增：
  ```java
  /** 填三条：sat_d += round(value_d × coeff)，封顶 need_d。返回是否发生了任何填充。 */
  public static boolean fillBars(ServerLevel level, TouristStateHost t, BuildingConfig cfg)
  ```
- **删除** `matchScore`（:92-96，pref×threeSum）→ 新增 need-gap 评分（见 D9 公式）。
- `applyPreferenceDecay`（:127-133）**删除**（无 pref）。
- `interactionDuration`（:115-125）：按四类块取 `interactionDurationTicks`：
  ```java
  if (cfg.shop()!=null && cfg.shop()!=ShopConfig.NONE) return cfg.shop().interactionDurationTicks();
  if (cfg.service()!=null && cfg.service()!=ServiceConfig.NONE) return cfg.service().interactionDurationTicks();
  if (cfg.relax()!=null && cfg.relax()!=RelaxConfig.NONE) return cfg.relax().interactionDurationTicks();
  if (cfg.atm()!=null && cfg.atm()!=AtmConfig.NONE) return cfg.atm().interactionDurationTicks();
  ```
- **保留** `performShopInteraction`（:152-177）与 `performServiceInteraction`（:181-208），但 `satisfactionGain` 调用改为 `fillBars`。
- **新增**：
  ```java
  /** Relax 建筑：回复精力 + 填条 + 冷却。 */
  public static InteractionResult performRelaxInteraction(ServerLevel level,
          TouristStateHost t, UUID buildingId, UUID colonyId) {
      BuildingConfig cfg = getConfig(level, buildingId);
      if (cfg == null || cfg.relax() == null) return null;
      var r = cfg.relax();
      int energyBefore = t.getEnergy();
      t.setEnergy(t.getEnergy() + r.energyRestore());          // clamp 在 setEnergy 内
      int gain = fillBars(level, t, cfg) ? computedGain : 0;   // 填条，见 fillBars 返回
      applyInteractionCooldown(level, t, buildingId);
      return new InteractionResult(null, satBefore, gain, r.energyRestore(), "歇脚恢复精力");
  }
  /** ATM 建筑：从 travelFund 取现补钱包 + 填条 + 冷却。 */
  public static InteractionResult performAtmInteraction(ServerLevel level,
          TouristStateHost t, UUID buildingId, UUID colonyId) {
      BuildingConfig cfg = getConfig(level, buildingId);
      if (cfg == null || cfg.atm() == null) return null;
      var a = cfg.atm();
      int amount = Math.min(a.withdrawAmount(), t.getTravelFund());
      t.setWallet(t.getWallet() + amount);
      t.setTravelFund(t.getTravelFund() - amount);
      int gain = fillBars(level, t, cfg) ? computedGain : 0;
      applyInteractionCooldown(level, t, buildingId);
      return new InteractionResult(null, satBefore, gain, 0, "取钱 " + amount);
  }
  ```
- `selectNextTarget`（:231-292）：改为按 category 判断目标建筑（`cfg.isTouristTarget()`），shop 有货判断改 `cfg.shop()!=NONE && hasStock`，hotel 判断改 `cfg.service().maxOccupancy()>0`，加入精力轴（精力 0 只能去 `relax.energyRestore()>0`）+ 排队惩罚。**候选只取视野 `TOURIST_VISION_RADIUS` 内且已加载的建筑**；视野内无合适目标 → 返回 null，调用方**闲逛**。
- `weightedPick`（:294-311）：权重改为 Find-Best-Action 分数。

### 2. TouristMoveGoal（当前 :564-609 performBuildingInteraction、:1163-1330 planNextBuilding、:1337-1463 交互/评分、:125-132/437/454-465/479/517/1296-1321 AABB 逻辑）

- **删 AABB 交互区逻辑**：`touristInteractZones: List<BoundingBox>`（:125-132）及生成（:1296-1321）、到达判定（:454-465）→ 改为**寻路到一个 spot 点**：`interactPoint = api.getTouristInteractPoint(buildingId)`（由 interactSpots 派生，返回一个点）；到达 = `distSqr(spot) <= ARRIVAL_RADIUS²`。
- `performBuildingInteraction`（:564-609）：按 category 分发到 `performShopInteraction/performServiceInteraction/performRelaxInteraction/performAtmInteraction`（不再有「shop/service 两类」假设）。
- `interactWithShop/interactWithService`（:1337-1393）→ 扩展为四类（relax/atm 沿用气泡/叙事格式）。
- `computeMatchScore/applyPreferenceDecay`（:1427-1442）**删除**；`weightedPick`（:1445-1463）改 need-gap 分数。
- `planNextBuilding/hasBuildingsAvailable`（:1163-1330/:1077-1097）：目标过滤改 `cfg.isTouristTarget()`；hotel 判 `service.maxOccupancy()>0`；shop 有货判 `shop()!=NONE && hasStock`；精力 0 只选 `relax.energyRestore()>0`。
- `decideNextMode`（:1042-1074）：休息冷却概念保留（活动后冷却），但不再有 sat 参与。
- **活动/占用**：到达 spot → `TouristSpotManager.claim(buildingId, spotIndex, touristId)` → `setCurrentActivity(<该 spot 的 action，来自 InteractSpot.action>)` + `setOccupiedSpot` + `setActivityTicks(duration)` → 活动期间站着做动作（粒子/姿态）→ `duration_ticks` 后 `release` + 结算。
- **排队**：spot 全满 → `setCurrentActivity(QUEUE)` 在建筑旁等；超时放弃（`release` + 去别处）。仅机制，无可见标记。

### 3. TouristSimSystem（当前 :444-511 interact、:514-524 hasHotelVacancy、:528-576 checkDeparture/routeToHotel、:578+ depart）

- `interact`（:444-511）：按 category 分发到四类交互（与实体共用 TouristSimulation）。
- 旅店入住（:458-484）：条件改 `service.maxOccupancy()>0 && !isFullySatisfied() && (夜晚) && hasVacancy`，删 `sat>=50`；入住记 nightsStayed。
- `checkDeparture`（:528-558）：改 D6 规则（isFullySatisfied&&夜晚 / deadline / 夜晚无床位 / idle；删 sat 三段与「精力 0 无恢复→离场」——精力 0 无恢复改为闲逛）。
- `routeToHotel`（:560-576）：`"service".equals(category)` → `cfg.service().maxOccupancy()>0`。
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

- 旅店判定 `config.service().maxOccupancy()`（:39/86/88/230/231）→ **保持不变**（service.maxOccupancy 仍在）。
- checkIn 条件（:84）删 `sat>=50`，改 `!isFullySatisfied() && 夜晚`。
- 退房（heartbeat 清晨 1000-1200）：`nightsStayed++`、精力回 100（沿用）。入住点 wakeUpPos 逻辑保留。

### 6. ShopStockManager / ShopInteractionHandler

- `onDailySettlement` 补货过滤 `"shop".equals(state.getCategory())`（:623）→ 改 `cfg.shop()!=NONE`。
- `purchaseAffordable/purchase/getGoodsBonus*`（:282/:327/:203-215）：读 `cfg.shop().goods()`（不变）。
- `ShopInteractionHandler`（:15-40）：纯透传，按新字段。

### 7. TouristStateHost（删遗留方法）

- 迁移完所有调用点后，**删除** `getSatisfaction()/setSatisfaction()`、`getTypePreference()/adjustTypePreference()`。
- 全仓库 grep 确认无引用（Block 4 负责非 tourist 文件；本块负责 tourist AI 文件）。

### 8. 交互动作视觉（客户端，零依赖）

> 动作是数据（spot 上的 `Activity`），表现是客户端映射。**不用 GeckoLib / PlayerAnimator**：游客是 PathfinderMob + 玩家模型（`HumanoidModel(PLAYER)`），两库分别面向自定义 geo 模型 / 玩家实体，引入需换渲染管线且违背零依赖原则（PlayerAnimator README 明说非玩家实体请用 GeckoLib）。用原版 API：`Pose` + `setupAnim` 骨骼旋转 + 粒子，先例见 `WandscapeNpcModel` 施法摆臂。

- **`ActivityVisuals`**（新建，client）：`Activity → (Pose, 骨骼目标角度, 粒子规格)` 注册表；**未知 Activity 兜底 BROWSE**——动作是创作者可配数据（扫描器 marker 可设），渲染不能因未知值崩。
- **`TouristHumanoidModel`**（新建，client，extends HumanoidModel）：`setupAnim` 读 `getCurrentActivity()` + `activityTicks`，各骨骼用 `Mth.lerp` 缓动到目标角度（约 10 tick 过渡），进出平滑不瞬移。
- 首版动作映射：

| Activity | 表现 |
|---|---|
| BROWSE | 头微低 + 手臂轻抬 + head 小幅左右打量（默认兜底） |
| EAT | 手持食物 + 手臂周期抬放 |
| BATHE | `Pose.SWIMMING` + 蒸汽粒子 |
| VIEW | 头仰望 + 缓慢转身扫视 |
| MEDITATE | 双手合十 + 头微垂 + 魔法粒子 |
| WITHDRAW | 手臂前伸 + 金币粒子 |

- 循环动画（EAT 咀嚼等）用 vanilla `AnimationDefinition`/`AnimationState` + `AnimationUtils.animate()`（warden/allay 同款，Java 关键帧，仍零依赖）。
- **BlockBench 只做创作参考，不做运行时资产**：摆参考姿态或设计动画后经「Animation to Java Converter」插件转 Java 关键帧塞进 `AnimationDefinition`；不引入 `.geo.json`/GeckoLib 格式。
- 粒子在 `TouristRenderer` 按 activity 发射，复用现有粒子体系。

## Done 判定

1. `./gradlew build` 绿。
2. 游客：逛建筑填三条（无惩罚）；**只看视野内目标，视野内无目标则闲逛**；精力低/0 时优先/只能去 `relax.energyRestore()>0` 建筑、无恢复则闲逛；去 atm 取钱（travelFund 扣减、钱包增加）；在 spots 占位做**该 spot 指定动作**、满了排队、超时放弃；夜间入旅店、清晨退房回精力。
3. **满条游客等夜晚离场**给经验 + mage resume；停留 2-4 天到点离场；低级小镇满不了条 → 0 经验。
4. `tourist/**`（AI 部分）无 `getSatisfaction`/`getTypePreference` 引用。
5. 游客在 spot 做动作时有对应姿态/粒子（浏览/用餐/泡澡/看展/冥想/取现），切换平滑；未知 action 兜底浏览，不崩渲染。
