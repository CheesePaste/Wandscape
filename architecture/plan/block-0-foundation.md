# Block 0 — Foundation（共享契约）

> **本块必须最先完成**，产出共享契约；Block 1-4 全部依赖它。完成后全仓库**编译通过、游戏能加载建筑、旧游客行为不变**（靠 BuildingConfig 兼容访问器）。本块只做契约层，不实现游客新逻辑。
> **一阶段不做 category 合并**：`shop`/`service` 保持独立 category（模式预设），`relax`/`atm` 为新增 category。把四者统一成 `interact` 的 `interaction` 块 → **二阶段延后**，见 [phase-2/README.md](phase-2/README.md)。

## 负责文件

| 文件 | 动作 |
|---|---|
| `shared/data/ShopConfig.java` | 保留（一阶段不动；二阶段删） |
| `shared/data/ServiceConfig.java` | 保留（同上） |
| `shared/data/RelaxConfig.java` | 新建（见契约 §1） |
| `shared/data/AtmConfig.java` | 新建（见契约 §1） |
| `building/data/BuildingConfig.java` | `tourist_interact_aabb` → `interact_spots`；加 relax/atm 字段（见契约 §3） |
| `Config.java` | 新增/删除键（见契约 §6） |
| `WandscapeConstants.java` | 如需要加条容量常量（见契约 §6） |
| `tourist/internal/TouristStateHost.java` | 只增 default 方法（见契约 §4） |
| `tourist/internal/Activity.java` | 新建枚举（见契约 §5） |
| 全部 `data/wandscape/buildings/*.json` | 迁移到新 schema（见 §7） |
| `docs/data/buildings.md` | 更新 schema 文档 |

## 契约 §1 — 模式预设块（shop/service 保留 + relax/atm 新增）

> 用户的明确意图：`interact_spots` **只负责交互位 + 游客动作**（动作优化，不影响建筑）；**各交互的效果（卖物品/产元素/回精力/取钱）由建筑 category 的「模式预设块」决定**。一阶段保持每个 category 一个块。

### 保留：`ShopConfig`（现有 `shared/data/ShopConfig.java`，不改）

```java
public record ShopConfig(
        List<ShopGoodDef> goods,
        @SerializedName("profit_rate") double profitRate,
        @SerializedName("interaction_duration_ticks") int interactionDurationTicks
)
```
模式预设：**卖物品**。游客交互 → 购物结算（钱包购货、殖民地收元素）。

### 保留：`ServiceConfig`（现有 `shared/data/ServiceConfig.java`，不改）

```java
public record ServiceConfig(
        @SerializedName("energy_per_use") int energyPerUse,
        @SerializedName("element_output") Map<String, Integer> elementOutput,
        @SerializedName("max_occupancy") int maxOccupancy,
        @SerializedName("interaction_duration_ticks") int interactionDurationTicks
)
```
模式预设：**产元素 + 消耗精力**；`maxOccupancy > 0` 时为旅店（夜晚住宿）。

### 新增：`RelaxConfig`（新 `shared/data/RelaxConfig.java`）

```java
package com.wsteam.wandscape.shared.data;

import com.google.gson.annotations.SerializedName;

/** 放松建筑：游客交互后回复精力。category = relax。 */
public record RelaxConfig(
        @SerializedName("energy_restore") int energyRestore,      // 单次回复精力（正数）；0=不回复
        @SerializedName("interaction_duration_ticks") int interactionDurationTicks   // 活动时长
) {
    public static final RelaxConfig NONE = new RelaxConfig(0, 0);

    public RelaxConfig {
        if (energyRestore < 0) energyRestore = 0;
        if (interactionDurationTicks < 0) interactionDurationTicks = 0;
    }
}
```
模式预设：**回复精力**。游客交互 → `energy += energyRestore`（clamp 到 `TOURIST_MAX_ENERGY`）。这是精力循环里「白天恢复建筑」的载体（餐厅/澡堂/歇脚处）。

### 新增：`AtmConfig`（新 `shared/data/AtmConfig.java`）

```java
package com.wsteam.wandscape.shared.data;

import com.google.gson.annotations.SerializedName;

/** ATM 建筑：游客交互后取出钱（从旅行总旅费 travelFund 取现补钱包）。category = atm。 */
public record AtmConfig(
        @SerializedName("withdraw_amount") int withdrawAmount,    // 单次取现上限（正数）；0=不可取
        @SerializedName("interaction_duration_ticks") int interactionDurationTicks   // 活动时长
) {
    public static final AtmConfig NONE = new AtmConfig(0, 0);

    public AtmConfig {
        if (withdrawAmount < 0) withdrawAmount = 0;
        if (interactionDurationTicks < 0) interactionDurationTicks = 0;
    }
}
```
模式预设：**取出钱**。游客交互 → `amount = min(withdrawAmount, travelFund)`；`wallet += amount`；`travelFund -= amount`。`travelFund`（总旅费）见契约 §4/§6，防无限取现。

> 二阶段把这四个块统一成 `interaction` 块（见 phase-2/README.md），一阶段**不做**。

## 契约 §2 — JSON schema（新格式）

`interact_spots` 取代 `tourist_interact_aabb`；四个旅游 category 各自带模式预设块。

```json
{
  "id": "breadshop",
  "display_name": "面包店",
  "category": "shop",
  "comfort": 10,
  "magic": 3,
  "wonder": 2,
  "shop": {
    "goods": [...],
    "profit_rate": 0.3,
    "interaction_duration_ticks": 2400
  },
  "interact_spots": [
    {"pos":[1,0,1], "action":"browse"},
    {"pos":[3,0,1], "action":"eat"}
  ],
  "boundary": {...},
  "door_offset": [...]
}
```

- **shop 建筑**：`category: "shop"` + `shop{}` 块（不变）。
- **service 建筑**：`category: "service"` + `service{}` 块（不变；`max_occupancy>0` = 旅店）。
- **relax 建筑**（新）：
  ```json
  {
    "category": "relax",
    "relax": { "energy_restore": 40, "interaction_duration_ticks": 1200 },
    "interact_spots": [ {"pos":[0,1,0], "action":"bath"} ]
  }
  ```
- **atm 建筑**（新）：
  ```json
  {
    "category": "atm",
    "atm": { "withdraw_amount": 50, "interaction_duration_ticks": 1200 },
    "interact_spots": [ {"pos":[0,0,0], "action":"withdraw"} ]
  }
  ```
- `interact_spots`：`[{"pos":[x,y,z],"action":"<动作>"},...]` 相对 anchor 的交互位列表，**每点带动作种类**。`tourist_interact_aabb` 删除。
- **spot 语义**：**spot 数量 = 该建筑同时交互的游客人数上限**（全满 → 排队，见 Block 3）；**交互时长 `duration_ticks` 由建筑模式预设块的 `interaction_duration_ticks` 决定**（与 spot 无关）；**同建筑不同 spot 动作可不同**。
- `action` 取值 = Activity 枚举的子集（`browse/eat/bathe/view/meditate/rest/withdraw`），由 `interact_spot_marker` 方块设置（见 Block 1）。**动作只决定游客在该点的活动状态/粒子；精力/经济效果由 category 模式预设块决定**。
- 可选字段省略即默认：`energy_restore`=0、`withdraw_amount`=0。
- **不解析**旧 `tourist_interact_aabb` 顶层字段；`shop`/`service` 仍解析（保留）。

## 契约 §3 — BuildingConfig（`building/data/BuildingConfig.java`）

**record 组件变更**（当前 :29-53）：
- 删除：`@SerializedName("tourist_interact_aabb") List<BoundaryBox> touristInteractAabb`
- 新增：`RelaxConfig relax`、`AtmConfig atm`、`@SerializedName("interact_spots") List<InteractSpot> interactSpots`
- **保留**：`ShopConfig shop`、`ServiceConfig service`
- 新增嵌套 record（放 BuildingConfig 内，像 BoundaryBox 一样）：
  ```java
  /** 交互位：相对 anchor 的坐标 + 动作种类。 */
  public record InteractSpot(BlockOffset pos, Activity action) {
      public InteractSpot {
          if (pos == null) throw new IllegalArgumentException("interact spot pos must not be null");
          if (action == null) action = Activity.BROWSE;
      }
  }
  ```
  > `Activity` 定义在 `shared/data/Activity.java`（见契约 §5），保证 `building/data` 可引用且不违反跨模块规则。

**兼容派生访问器**（保证旧消费者编译+行为不变；二阶段删）：
```java
public List<BoundaryBox> touristInteractAabb() {
    return interactSpots.stream().map(s -> new BoundaryBox(s.pos(), s.pos())).toList();
}
/** 该建筑是不是游客交互目标（四类旅游 category 之一）。 */
public boolean isTouristTarget() {
    return shop() != ShopConfig.NONE || service() != ServiceConfig.NONE
            || relax() != RelaxConfig.NONE || atm() != AtmConfig.NONE;
}
```
> 注意：`shop()/service()/relax()/atm()` 仍是一阶段真实字段，不是派生访问器；只有 `touristInteractAabb()` 由 spots 派生。

**Deserializer（当前 :122-303）**：
- 解析 `interact_spots`：`JsonArray` of `{"pos":[x,y,z],"action":"<字符串>"}` → `List<InteractSpot>`；`action` 字符串→Activity 用 `Activity.valueOf`（非法值回退 BROWSE）；缺省空。
- 保留 `shop`/`service` 解析分支（:240-250）。
- 新增 `relax`/`atm` 解析分支：`RelaxConfig.NONE`/`AtmConfig.NONE` 缺省。
- **删除** `tourist_interact_aabb` 解析分支（:270-282）。
- 其余字段（pattern/block_mapping/comfort/magic/wonder/queue/boundary/blueprint/node_config/maintenance_cost/decoration/wonder_config/door_offset/first_free/deprecated）保持不变。

## 契约 §4 — TouristStateHost（`tourist/internal/TouristStateHost.java`）

**只增 default 方法**，向后兼容（不改现有抽象方法；Block 3 删遗留方法）：
```java
// 三条（填充量 sat / 需求 need）
default int getComfortSat() { return 0; }    default void setComfortSat(int v) {}
default int getMagicSat() { return 0; }      default void setMagicSat(int v) {}
default int getWonderSat() { return 0; }     default void setWonderSat(int v) {}
default int getComfortNeed() { return 100; } default void setComfortNeed(int v) {}
default int getMagicNeed() { return 100; }   default void setMagicNeed(int v) {}
default int getWonderNeed() { return 100; }  default void setWonderNeed(int v) {}
// 活动状态（占位做动作）
default Activity getCurrentActivity() { return null; } default void setCurrentActivity(Activity a) {}
default int getActivityTicks() { return 0; } default void setActivityTicks(int t) {}
default int getOccupiedSpot() { return -1; } default void setOccupiedSpot(int i) {}
// 停留
default int getNightsStayed() { return 0; } default void setNightsStayed(int n) {}
default long getDepartureDeadline() { return Long.MAX_VALUE; } default void setDepartureDeadline(long t) {}
// 满条判定
default boolean isFullySatisfied() { return false; }
// 总旅费（ATM 取现来源；初始 = startingWallet × TOURIST_ATM_TRAVEL_FUND_MULTIPLIER）
default int getTravelFund() { return 0; } default void setTravelFund(int v) {}
```
**Block 0 期间保留**（勿删）：`getSatisfaction()/setSatisfaction()`、`getTypePreference()/adjustTypePreference()`——Block 3 迁移完调用点后删除。

## 契约 §5 — Activity 枚举（新 `shared/data/Activity.java`）

```java
package com.wsteam.wandscape.shared.data;

public enum Activity {
    TRAVEL, QUEUE, BROWSE, EAT, BATHE, VIEW, MEDITATE, SLEEP, REST, WITHDRAW
}
```

- **放 `shared/data`**（不是 tourist/internal），因为 `building/data/BuildingConfig.InteractSpot` 要引用它（避免跨模块直接引用）。
- **交互位动作子集**：`BROWSE/EAT/BATHE/VIEW/MEDITATE/REST/WITHDRAW` —— `interact_spot_marker` 只在这几个里循环；游客在某 spot 做该 spot 的 action。`WITHDRAW` 供 atm 建筑交互位使用。
- `SLEEP` 归旅店（beds 建筑夜晚）；`TRAVEL/QUEUE` 是 AI 移动/排队状态，非交互位动作。
- `TouristState` 保持移动标签，禁止塞活动。

## 契约 §6 — Config / Constants

`Config.java` 游客段（当前 :141-265）：
- **新增**：
  - `TOURIST_BAR_GAIN_COEFF`（double，默认 2.0）——每条填充 = `round(value_d × coeff)`，封顶 need
  - `TOURIST_ENERGY_RESTORE_THRESHOLD`（double，默认 0.25）——精力低于此比例强烈偏向恢复（relax）建筑
  - `TOURIST_QUEUE_WAIT_TOLERANCE_TICKS`（int，默认 2400）——排队等待上限
  - `TOURIST_STAY_MIN_DAYS`（int，默认 2）、`TOURIST_STAY_MAX_DAYS`（int，默认 4）——`departureDeadline = arrivalTime + rand(2~4) × 24000`
  - `TOURIST_VISION_RADIUS`（int，默认 48）——**视野**：目标选择只看半径内（且已加载）的建筑；视野内无合适目标 → 闲逛
  - `TOURIST_ATM_TRAVEL_FUND_MULTIPLIER`（double，默认 2.0）——生成时 `travelFund = startingWallet × multiplier`（总旅费 = 随身现金的倍数，ATM 分批取现的池子）
  - 画像分布权重（可硬编码于 Block 2 生成处，或 Config：`TOURIST_PERSONA_BALANCED/COMFORT/MAGIC/WONDER`）
- **画像需求与等级正相关**（Block 2 生成时 roll）：
  ```
  weightShare_d = persona 各维权重占比（均衡 1/3；偏置如 140/80/80 归一化到 1.0）
  totalNeed      = TOURIST_NEED_BASE + (touristLevel − 1) × TOURIST_NEED_PER_LEVEL
  need_d         = round(totalNeed × weightShare_d)
  ```
  `TOURIST_NEED_BASE`（默认 300）、`TOURIST_NEED_PER_LEVEL`（默认 50）→ 等级越高总需求越高、**越难满足**。
- **删除**：`TOURIST_LEVEL_SATISFACTION_THRESHOLD`、`TOURIST_MAX_SATISFACTION_PER_VISIT`、`TOURIST_PREFERENCE_DECAY`

`WandscapeConstants.java`：确认 `TOURIST_MAX_ENERGY` 存在；如需要加 `TOURIST_BAR_BASE`(=100)。

> **精力/经济数值（energy_restore/withdraw_amount 等）是建筑级模式预设字段，扫描器可编辑，平衡后置**（本方案不预先定死数值，只定机制）。

## 契约 §7 — 迁移全部 `data/wandscape/buildings/*.json`

规则：
- `tourist_interact_aabb` → `interact_spots`（每个 AABB 取中心点或 min 点，`action` 默认 `"browse"`，成 `[{"pos":[...],"action":"browse"}]`）。**shop/service 的 `shop{}`/`service{}` 块与 category 保持不变**。
- `tavern` → **可选**迁移为 `"relax"`（挂 `relax{energy_restore}`）使其成为游客目标；或保持 `tavern`（招募用，非游客目标）。
- `altar1` → 可选迁移为 `"relax"`（挂 `relax{}`）或保持 `altar`。
- **新增示例建筑**：`bathhouse`（relax，`energy_restore`、spot action `bath`）、`atm`（atm，`withdraw_amount`、spot action `withdraw`）。
- node/storage/government/workstation/crafting_station/potion_station 不变（无交互）。

涉及文件（当前目录 `src/main/resources/data/wandscape/buildings/`）：`altar1/bookshop/breadshop/craftstation1/flowershop/inn1/magicshop/nodedark~wind/wood/potionstation1/service_hall/tavern/townhall1/warehouse/workstation1`，及 `deprecated/library.json`；新增 `bathhouse/atm`。

## Done 判定

1. `./gradlew build` 全绿。
2. 进游戏：放置建筑正常；游客仍按旧逻辑跑（兼容访问器保证行为不变，无回归）。
3. `data/wandscape/buildings/*.json` 全部新 schema（`interact_spots`，无 `tourist_interact_aabb` 顶层字段）；shop/service/relax/atm 四类可加载。
4. `docs/data/buildings.md` 已更新为新 schema。
