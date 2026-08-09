# Block 2 — 游客数据（三条/画像/停留/活动 + travelFund，去 satisfaction/typePreferences）

> 依赖 Block 0 契约（已落地：`TouristStateHost` default 方法 + `Activity` + 四类模式预设 `BuildingConfig.interactSpots`）。**只做数据面**：存储字段 + NBT + 生成/离开 + 数据包 + 面板。**不碰** `TouristSimulation`/`TouristMoveGoal`/`TouristSimSystem`/`HotelStayHandler`（Block 3）。本块与 Block 1/3/4 可并行。
>
> **用户拍板（相对 goal.md 的对齐决定）**：
> 1. **头顶 HUD 不做**：goal.md「头顶/面板」按「只做面板（TouristScreen）」实现，不渲染头顶三条 bar。
> 2. **行程「满意 ±X」→ 一条聚合增量**：`VisitMemory` 的 `satisfactionBefore/satisfactionDelta` 由 **Block 4 收口**为单个 `barDelta`（三条 ratio 增量之和，见 block-4「本块契约」C4）；面板行程显示聚合增量，不逐维。
> 3. **生成移除初始目标指派**：出生不再 `setTargetBuildingId`/`setCommuteTarget`，游客出生即闲逛，目标完全由 Block 3 的视野内 Find-Best-Action 决定（贴合非协商项 #3）。
> 4. **JSON 迁移不归本块**（用户明确「json 迁移不用管」）：`data/wandscape/buildings/*.json` 仍为旧 `tourist_interact_aabb` 属 Block 0/1 遗留，block-2 不处理。

## 目标

1. `TouristEntity`/`TouristShadow` 实现 `TouristStateHost` 新 default 方法（三条 sat/need、Activity、停留、travelFund 真实字段 + NBT）。
2. **删除 `satisfaction`、`typePreferences` 字段与 NBT key**（接口方法保留 stub，Block 3 删接口）。
3. `TouristSpawnSystem`：生成时 roll 画像 + 按等级缩放三条 need + 设停留截止 + 设 travelFund（ATM 取现池）；**移除初始目标指派**；离开判定重写为「满条夜晚 / 到点 / 夜晚无床位 / idle」四条（D6）。
4. `TouristDataPacket` + `TouristScreen`：三条 bar + 画像标签 + 活动 + 已住晚数/停留天数 + 钱包/旅费；行程聚合增量（barDelta）；删满意度单条。
5. 红线 #8：`visitedBuildings` 停留期不重置——生成/离开/入住/退房全程**不** clear。

## 负责文件

| 文件 | 动作 |
|---|---|
| `tourist/entity/TouristEntity.java` | 三条/画像/活动/停留/**travelFund** 字段 + NBT；删 satisfaction/typePreferences（保 stub） |
| `tourist/internal/TouristShadow.java` | 同上（影子副本） |
| `tourist/internal/TouristSpawnSystem.java` | 生成 roll 画像+设 need/截止/travelFund、移除初始目标；离开判定重写（D6）；target 池改四类 |
| `tourist/network/TouristDataPacket.java` | 扩展/替换字段（三条 sat/need、活动、停留、travelFund；删 satisfaction/currentState/target*）；`VisitEntry` 改 `barDelta`（形状随 Block 4） |
| `tourist/client/TouristScreen.java` | 三条 bar + 画像标签 + 活动 + 停留 + 钱包/旅费；行程聚合增量（barDelta）；删满意度单条 |
| `shared/data/VisitMemory.java` | **形状归 Block 4**（`satisfactionBefore/satisfactionDelta` → `barDelta`，见 block-4 C4）；本块只适配实体/影子 NBT 序列化与 packet/screen |

## 契约补充（跨块协调，须回写 Block 3 消费方）

- **`VisitMemory` 形状归 Block 4**（见 block-4 C4：`barDelta` 单聚合增量）：本块只负责 `TouristEntity`/`TouristShadow` 的 save/load 与 `TouristDataPacket.VisitEntry`/`TouristScreen` 适配新形状；**Block 3 构造 `VisitMemory` 时填 `barDelta`**（`TouristSimulation`/`TouristSimSystem`/`TouristMoveGoal` 的 `addVisitMemory` 调用点）。
- **实体↔影子同步新字段**：`TouristSimSystem.adoptTourist`（:106-125，走 `exportToShadow` :307-337）与 `importToEntity`（:262-304）**当前不复制** 三条 sat/need、travelFund、nightsStayed、departureDeadline。Block 2 期间为「开发期临时状态」：影子在区块卸载期间的这些字段不回填实体，属已知缺口，**Block 3 补同步行**。block-2 不碰 `TouristSimSystem`。
- **`registerDeparture` 第三参改 `BarRatio`**：`TouristApi.registerDeparture(UUID, UUID, BarRatio fill)` 签名由 **Block 4 收口**（见 block-4 C2）；Block 2 沿用 3 参调用，第三参传 `BarRatio.of(三条 sat/need)`（离场时最终三条）。

## 具体改动

### 1. TouristEntity（当前 :167-234 字段，:450-537 save，:539-652 load，:728-767 getter/pref）

**删除**：
- `satisfaction` 字段（:168）+ save key（:467 `tag.putInt("satisfaction", ...)`）+ load（:567）。
- `typePreferences: Map<String,Integer>`（:177-180）+ save 复合块（:472-477）+ load（:572-579）+ `getTypePreference/adjustTypePreference`（:750-767）。

**新增字段**（放「Tourist attributes」区，:167-173 附近）：
```java
private int comfortSat, magicSat, wonderSat;                    // 填充量，0..need
private int comfortNeed = 100, magicNeed = 100, wonderNeed = 100; // 需求上限（画像）
private Activity currentActivity;                               // null=无活动
private int activityTicks;                                       // 活动剩余/已过 tick
private int occupiedSpot = -1;                                   // 占用交互位下标，-1=未占用
private int nightsStayed;                                        // 住店晚数
private long departureDeadline = Long.MAX_VALUE;                 // 离境截止（gameTime）
private int travelFund;                                          // 总旅费（ATM 取现池）
```

**实现 `TouristStateHost` default 方法 override**（getter/setter 直读字段，`setComfortSat` 等 clamp 到 `[0, need]`；need setter clamp 到 `≥1`）：
```java
@Override public boolean isFullySatisfied() {
    return comfortSat >= comfortNeed && magicSat >= magicNeed && wonderSat >= wonderNeed;
}
```
> 三条 need 生成时必 ≥1，无需除零保护；`isFullySatisfied()` = 三条 ratio 全 1。

**NBT save/load 新 key**：`comfortSat/magicSat/wonderSat/comfortNeed/magicNeed/wonderNeed/currentActivity/activityTicks/occupiedSpot/nightsStayed/departureDeadline/travelFund`。删除旧 `satisfaction`/`typePreferences` key。
- `currentActivity` 存 `Activity.name()`（`String`），load 时 `valueOf` 失败回退 null。
- `departureDeadline` 存绝对 gameTime（`Long`）。
- 旧档无新 key → 走字段默认值（need=100×3、deadline=MAX、travelFund=0），不报错。

**保留 stub（编译桥，勿删，Block 3 删接口时一并清掉）**：
```java
/** 过渡期派生聚合值：min(sat/need)×100。Block 3 删接口后移除。 */
@Override public int getSatisfaction() {
    return (int) Math.floor(Math.min(ratio(comfortSat, comfortNeed),
            Math.min(ratio(magicSat, magicNeed), ratio(wonderSat, wonderNeed))) * 100);
}
@Override public void setSatisfaction(int s) { /* no-op：三条由 Block 3 fillBars 填 */ }
@Override public int getTypePreference(String buildingTypeId) { return 40; }
@Override public void adjustTypePreference(String buildingTypeId, int delta) { /* no-op */ }
/** Block 3 影子同步仍需此签名（TouristSimSystem :315 putAll）；返回空 map 即可。 */
@Override public Map<String, Integer> getTypePreferencesMap() { return Map.of(); }
```
> 依赖：`getSatisfaction()` stub 返回聚合值 → `onTouristKilled`（:440）沿用；`registerDeparture` 第三参改传 `BarRatio`（Block 4 收口，见 block-4 C2）；`TouristSimulation`/`TouristMoveGoal`/`TouristSimSystem`（Block 3 文件，未合并）读 `getSatisfaction()` 编译不破。

### 2. TouristShadow（当前 :38-93 字段，:252-324 save，:326-400 load）

镜像 `TouristEntity`：
- 删 `satisfaction`（:70 + save :273 + load :348）、`typePreferences`（:74 + save :278-280 + load :353-356）、`getTypePreference/adjustTypePreference`（:194-207）。
- 加三条/画像/活动/停留/**travelFund** 字段 + NBT key（同 §1；`departureDeadline` 存绝对值即可）。
- override `TouristStateHost` default 方法（含 `isFullySatisfied()`，同 §1）。
- stub：`getSatisfaction()`（派生）、`setSatisfaction()`（no-op）、`getTypePreference()`（返回 40）、`adjustTypePreference()`（no-op）。
- **`getTypePreferences()`（:192）保留并返回可变空 map**（`new HashMap<>()`）：`TouristSimSystem` :282 遍历、:314-315 `clear()+putAll()` 依赖此签名且要能 `putAll`，空 map 即可满足。

### 3. TouristSpawnSystem（生成 :103-156 forceSpawn / :292-358 flushPendingSpawns；离开 :393-447 cleanupTourists / :458-502 processNightDepartures / :546-583 onTouristDepart；target 池 :642-653）

**a) target 池改四类**：`getTouristTargets`（:642-653）过滤 `"shop"/"service"`（:646-647）→ 四类 `"shop"/"service"/"relax"/"atm"`。仅用于 createSchedule 派生 colonyId + 出生安全点（决策 #3 移除指派后，不再作为游客的导航目标）。

**b) 生成（forceSpawn 与 flushPendingSpawns 两处同步改）**：
```java
// 移除：目标指派（决策 #3）
// tourist.setTargetBuildingId(ps.buildingId());                  ← 删
// tourist.setTargetBuildingCategory(target.getCategory());       ← 删
// tourist.setCommuteTarget(interactionTarget);                  ← 删；interactionTarget 计算（:118-119/:308-309）一并删
// 保留：name/pos/level/wallet/initialWallet/colonyId/arrivalTime
rollAndSetPersona(tourist, ps.level);                            // 新增：三条 need（画像×等级缩放）
long stayTicks = (Config.TOURIST_STAY_MIN_DAYS.get()
        + random.nextInt(Config.TOURIST_STAY_MAX_DAYS.get() - Config.TOURIST_STAY_MIN_DAYS.get() + 1)) * 24000L;
tourist.setDepartureDeadline(level.getGameTime() + stayTicks);   // 新增：2~4 天截止
tourist.setTravelFund((int) Math.round(startingWallet(ps.level)
        * Config.TOURIST_ATM_TRAVEL_FUND_MULTIPLIER.get()));     // 新增：总旅费 = 随身现金 × 系数
tourist.applyState(TouristState.VISITING);                       // 保留；无 target 时 goal 首 tick 覆盖
```
```java
/** 画像 roll：40% 均衡 {1,1,1}；20% 舒适 {1.4,0.8,0.8}；20% 魔法 {0.8,1.4,0.8}；20% 奇观 {0.8,0.8,1.4}。 */
private static final double[][] PERSONA_WEIGHTS = {
        {1.0, 1.0, 1.0}, {1.4, 0.8, 0.8}, {0.8, 1.4, 0.8}, {0.8, 0.8, 1.4} };

/** 实例方法（forceSpawn 为 static，经 instance. 调用）；用实例 random。 */
private void rollAndSetPersona(TouristEntity t, int touristLevel) {
    double r = random.nextDouble();
    double[] w = r < 0.4 ? PERSONA_WEIGHTS[0]
              : r < 0.6 ? PERSONA_WEIGHTS[1]
              : r < 0.8 ? PERSONA_WEIGHTS[2]
              : PERSONA_WEIGHTS[3];
    int totalNeed = Config.TOURIST_NEED_BASE.get() + (touristLevel - 1) * Config.TOURIST_NEED_PER_LEVEL.get(); // 等级越高总需求越高
    double sum = w[0] + w[1] + w[2];
    t.setComfortNeed((int) Math.round(totalNeed * w[0] / sum));
    t.setMagicNeed((int) Math.round(totalNeed * w[1] / sum));
    t.setWonderNeed((int) Math.round(totalNeed * w[2] / sum));
}
```
> 精力默认满（字段初值 `TOURIST_MAX_ENERGY`=100），生成不设。`startingWallet`（:753-755）不变。

**c) 离开判定重写（D6）** —— 替换 cleanupTourists/processNightDepartures 的 sat<50 / 50-99 / 100 三段逻辑与「精力耗尽→离场」：

`cleanupTourists`（全天）：
```
for each 存活游客（未入住旅店）：
    if isFullySatisfied() && isMage() && !isMageResumeStored(): storeMageResume(t); setMageResumeStored(true)   // 满条即存简历
    if inDepartureWindow: continue    // 夜晚交给 processNightDepartures
    deadlineReached = level.getGameTime() >= t.getDepartureDeadline()
    idleTimeout     = t.getCommuteTarget() == null && t.tickCount > Config.TOURIST_DESPAWN_TIMEOUT_TICKS.get()
    if deadlineReached || idleTimeout: toRemove
    // 删除：energyDepleted→离场（goal.md：精力 0 且无恢复建筑 → 闲逛，不离场）
```

`processNightDepartures`（18000-24000）：
```
for each 存活游客（未入住旅店）：
    if isFullySatisfied() && isMage() && !isMageResumeStored(): storeMageResume(t); setMageResumeStored(true)
    if level.getGameTime() >= t.getDepartureDeadline(): toRemove; pendingDepartures.remove(uuid); continue   // 到点（满条才给经验，onTouristDepart 判定）
    if isFullySatisfied():
        // 满条 → 开心离场：沿用随机延迟错峰（pendingDepartures，0~TOURIST_DEPARTURE_DELAY_MAX_TICKS）
        分配延迟 → 到达后 toRemove
    else:
        // 非满条 → 夜晚入旅店；无旅店/满 → 离场
        pendingDepartures.remove(uuid)
        if !tryRouteToHotel(t, level): toRemove
```

`onTouristDepart`（:546-583）：
- 经验：`if (t.isFullySatisfied()) grantExperience(t);`（`:557` 改，`grantExperience` :511-523 内 `if (t.getSatisfaction() < 100) return;` → `if (!t.isFullySatisfied()) return;`）。
- 简历：`if (t.isMage() && t.isFullySatisfied() && !t.isMageResumeStored()) storeMageResume(t);`（:562 改）。
- `registerDeparture(t.getUUID(), colonyId, BarRatio.of(最终三条 sat/need))`（:569，第三参由 Block 4 收口，见 block-4 C2）。
- `NarrativeGenerator.generateDeparture(name, satisfaction, visitCount, time)`（:579-580）：satisfaction 实参传 `barRatioPct`（NarrativeGenerator 属 Block 4 文件，Block 2 不改其签名）。

> `tryRouteToHotel`/`isHotelBuilding`（:592-636）不动：旅店判定 `service.maxOccupancy()>0`（Block 0 后 `cfg.service()` 仍是真实字段）。

**d) 红线 #8（`visitedBuildings` 不重置）**：生成（新实体空集合）、离开（`discard()` 实体）、入住/退房（Block 3 职责）**全程不调用** `visitedBuildings.clear()`。`onTouristDepart` 删 shadow 但不碰 visitedBuildings。Block 2 禁止在「清晨重置/新一天」逻辑里引入清空。

### 4. TouristDataPacket（S→C）+ TouristScreen

**`TouristDataPacket` record（:24-37）改**：
```java
public record TouristDataPacket(
        int entityId,
        String touristName,
        int energy,
        int level,
        int wallet,
        int travelFund,                                    // 新增
        int comfortSat, int magicSat, int wonderSat,       // 新增
        int comfortNeed, int magicNeed, int wonderNeed,    // 新增
        @Nullable Activity currentActivity,                // 新增（替换 currentState）
        int nightsStayed,                                  // 新增
        int stayDaysTotal,                                 // 新增 = (deadline-arrival)/24000
        List<VisitEntry> recentVisits,
        int cooldownRemainingTicks
)
```
- 删除：`satisfaction`、`currentState`、`targetBuildingName`、`targetBuildingType`、`targetPos`（当前面板已不消费，死字段）。
- `VisitEntry`（:49-61）：`(buildingTypeId, buildingName, whatHappened, barDelta, energyDelta)` —— `satDelta` → `barDelta`（三条 ratio 增量之和，形状随 Block 4 C4）。
- StreamCodec write/read（:84-117）同步；`Activity` 可空：`writeBoolean(act!=null)` + `writeEnum(act)`，读回 null/枚举。
- `from(TouristEntity)`（:121-167）：填新字段；`stayDaysTotal = Math.max(1, (int)((departureDeadline - arrivalTime) / 24000L))`；删 target 解析逻辑（:127-149 的 BuildingApi 查询）。

**`TouristScreen`**（当前 :27-28 尺寸 300×230，:49-58 apply，:76-113 状态区，:93-99 满意度条，:180-190 drawStatBar）：
- 尺寸：`PH` 230 → **~300**（多 2 条 bar + 画像/活动/停留/旅费行），`PW` 300 不变。
- 布局（自上而下）：
  1. **画像标签**：need 最高维 → `I18n.get("tourist.persona.comfort/magic/wonder")`；三 need 相等 → `tourist.persona.balanced`。文本如「偏爱魔法」「均衡」。
  2. **三条 bar**：`drawStatBar`（:180-190 复用）Comfort/Magic/Wonder，label `fill/need`（如 `120/150`），三色区分（comfort=SUCCESS_GREEN、magic=ACCENT_GOLD、wonder=蓝色/自定义）。
  3. **精力 bar**（保留，:86-91）。
  4. **等级 / 钱包 / 旅费 / 停留**：文本行。钱包旁显示「旅费 travelFund」余额；「已住 N 晚 / 共 X 天」。
  5. **活动**：`currentActivity` 名称（`Activity` i18n 键或英文名），null 显示「—」。
  6. **冷却**（保留，:110-113）。
  7. **行程**（:115-147）：每行 `建筑: 事件 (需求 +X · 精力±e)`——`X = barDelta`（三条 ratio 增量之和，形状随 Block 4 C4）；`formatDelta` 复用，删「满意」字样。
- `apply`（:49-58）：删 `satisfaction`，新增字段赋值。

### 5. VisitMemory（形状归 Block 4，本块只适配序列化）

> `shared/data/VisitMemory.java` 的 record 形状由 **Block 4 收口**（见 block-4「本块契约」C4）：删 `satisfactionBefore/satisfactionDelta` → 单个 `int barDelta`（三条 ratio 增量之和，0..300），`emotion = Emotion.fromDelta(barDelta)`。本块不做形状设计，只让 B2 侧的存取跟得上新形状：
- `TouristEntity` save `recentVisits`（:522-534）与 load `new VisitMemory(...)`（:632-642）按新组件补/改 key（NBT 存 `barDelta`、删旧 `satisfaction*`）。
- `TouristShadow` save/load（:307-320/:385-394）同步。
- `TouristDataPacket.from` 从 `visit.barDelta()` 填 `VisitEntry`（:122 起）。
- **Block 3 协调项**：`addVisitMemory`（`TouristSimulation` :211 等）填 `barDelta`（fillBars 内得三条 ratio 增量求和）。

## 编译兼容（Block 3 未合入时的临时态）

1. `getSatisfaction()/setSatisfaction()/getTypePreference()/adjustTypePreference()` **在接口里仍是抽象方法**（Block 0 保留，Block 3 删）→ 实体/影子必须保留 stub 实现（§1/§2）。**Block 2 勿删接口方法**。
2. `getTypePreferencesMap()`（实体）返回 `Map.of()`、`getTypePreferences()`（影子）返回可变空 map：`TouristSimSystem` 同步（:282/:314-315）编译通过。
3. `registerDeparture` 第三参传 `BarRatio`（三条填充率，Block 4 收口签名，见 block-4 C2）；`NarrativeGenerator.generateDeparture` 保持现有 int 签名（第三参语义 = min-ratio×100）。
4. 三条 sat 本块不填（fillBars 属 Block 3）→ 游客暂不满条、经验暂不给，属「开发期临时状态」，只要求编译 + 数据正确。

## Done 判定

1. `./gradlew build` 绿（Block 3 未合入时游客行为暂不完整——本块只要求编译 + 数据正确）。
2. 游客 NBT 含三条/画像/活动/停留/travelFund key；无 `satisfaction`/`typePreferences` key。
3. 生成：画像随机（40/20/20/20）、三条 need 按等级缩放（高等级总需求更高）、deadline 在 2-4 天内、travelFund = startingWallet × multiplier；**出生无目标建筑/commuteTarget**。
4. 离开：满条夜晚离场（经验+简历）、到点离场（满条才经验）、夜晚无床位离场、idle 离场；**精力 0 不再离场**。
5. TouristScreen 显示三条 bar + 画像标签 + 活动 + 已住 N 晚/共 X 天 + 钱包/旅费；行程聚合增量（barDelta）；无单一满意度。
6. `visitedBuildings` 停留期无任何 clear。

## 手测（Block 2 阶段，临时态）

1. `/wandscape tourist spawn` 或自然生成 → 观察实体 NBT（三条 need / deadline / travelFund 已写入；无 satisfaction/typePreferences）。
2. 右键游客 → 面板：三条 bar（fill/need）+ 画像标签 + 活动「—」+ 钱包/旅费 + 停留天数；无「满意」单条。
3. 存档→读档：新 key 完整还原；旧档游客（无新 key）加载不崩，字段走默认。
4. 夜晚：满条（临时手动置三条 sat≥need）→ 离场给经验；非满条 → 入旅店；无旅店 → 离场；白天满条不立刻走。
5. 精力 0 游客不自动离场（临时验证：只减精力，不触发 cleanupTourists 移除）。
