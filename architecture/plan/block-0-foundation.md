# Block 0 — Foundation（共享契约）

> **本块必须最先完成**，产出共享契约；Block 1-4 全部依赖它。完成后全仓库**编译通过、游戏能加载建筑、旧游客行为不变**（靠 BuildingConfig 兼容访问器）。本块只做契约层，不实现游客新逻辑。

## 负责文件

| 文件 | 动作 |
|---|---|
| `shared/data/InteractionConfig.java` | 新建（见契约 §1） |
| `shared/data/TradeConfig.java` | 新建（可内嵌于 InteractionConfig） |
| `shared/data/ShopConfig.java` | 保留旧类（迁移期被兼容访问器引用，Block 5 删） |
| `shared/data/ServiceConfig.java` | 保留旧类（同上） |
| `building/data/BuildingConfig.java` | 换字段 + 兼容访问器（见契约 §3） |
| `Config.java` | 新增/删除键（见契约 §6） |
| `WandscapeConstants.java` | 如需要加条容量常量（见契约 §6） |
| `tourist/internal/TouristStateHost.java` | 只增 default 方法（见契约 §4） |
| `tourist/internal/Activity.java` | 新建枚举（见契约 §5） |
| 全部 `data/wandscape/buildings/*.json` | 迁移到新 schema（见 §7） |
| `docs/data/buildings.md` | 更新 schema 文档 |

## 契约 §1 — InteractionConfig（新 `shared/data/InteractionConfig.java`）

取代 `shop`/`service` 两个顶层块。三根正交轴（经济 × 精力 × 住宿）可自由组合。

```java
package com.wsteam.wandscape.shared.data;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import com.google.gson.annotations.SerializedName;

public record InteractionConfig(
        int energy,                       // 精力增量：负=消耗，正=恢复，缺省 0
        @Nullable TradeConfig trade,      // 卖货回款（原 shop）；null=无
        Map<String, Integer> output,      // 服务输出元素（原 service.element_output）；空=无
        int beds,                         // 旅店床位（原 service.max_occupancy）；0=非旅店
        @SerializedName("duration_ticks") int durationTicks   // 活动时长（原 interaction_duration_ticks）
) {
    public static final InteractionConfig NONE = new InteractionConfig(0, null, Map.of(), 0, 0);

    /** 该建筑是不是游客交互目标（interaction 块存在） */
    public boolean isTarget() { return this != NONE; }

    public InteractionConfig {
        if (output == null) output = Map.of();
        if (beds < 0) beds = 0;
        if (durationTicks < 0) durationTicks = 0;
    }
}

public record TradeConfig(List<ShopGoodDef> goods, double profitRate) {
    public TradeConfig {
        if (goods == null) goods = List.of();
        if (profitRate < 0) profitRate = 0;
    }
}
```

- `ShopGoodDef`（现有 `shared/data/ShopGoodDef.java`）原样复用。
- JSON 反序列化在 BuildingConfig.Deserializer 内手工做（见契约 §3），不依赖 Gson 自动映射。

## 契约 §2 — JSON schema（新格式）

```json
{
  "id": "breadshop",
  "display_name": "面包店",
  "category": "interact",
  "pattern": [...],
  "block_mapping": {...},
  "comfort": 10,
  "magic": 3,
  "wonder": 2,
  "interaction": {
    "energy": -20,
    "trade": { "goods": [...], "profit_rate": 0.3 },
    "output": { "earth": 4 },
    "beds": 8,
    "duration_ticks": 2400
  },
  "interact_spots": [
    {"pos":[1,0,1], "action":"browse"},
    {"pos":[3,0,1], "action":"eat"}
  ],
  "boundary": {...},
  "door_offset": [...],
  "maintenance_cost": {...},
  "blueprint": {...},
  ...
}
```

- `interact_spots`：`[{"pos":[x,y,z],"action":"<动作>"},...]` 相对 anchor 的交互位列表，**每点带动作种类**。`tourist_interact_aabb` 删除。
- `action` 取值 = Activity 枚举的子集（`browse/eat/bathe/view/meditate/rest`），由 `interact_spot_marker` 方块设置（见 Block 1）。动作决定游客在该点的活动状态/粒子；**精力/经济仍由 building 级 `interaction` 决定**。
- 可选字段省略即默认：`energy`=0、`trade`=无、`output`=空、`beds`=0、`duration_ticks`=0。
- **不解析**旧 `shop`/`service`/`tourist_interact_aabb` 顶层字段。

## 契约 §3 — BuildingConfig（`building/data/BuildingConfig.java`）

**record 组件变更**（当前 :29-53）：
- 删除：`ShopConfig shop`、`ServiceConfig service`、`@SerializedName("tourist_interact_aabb") List<BoundaryBox> touristInteractAabb`
- 新增：`InteractionConfig interaction`、`@SerializedName("interact_spots") List<InteractSpot> interactSpots`
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

**兼容派生访问器**（保证旧消费者编译+行为不变；Block 5 删）：
```java
public ShopConfig shop() {
    var t = interaction.trade();
    return t != null ? new ShopConfig(t.goods(), t.profitRate(), interaction.durationTicks()) : ShopConfig.NONE;
}
public ServiceConfig service() {
    if (interaction == InteractionConfig.NONE) return ServiceConfig.NONE;
    return new ServiceConfig(Math.max(0, -interaction.energy()), interaction.output(),
            interaction.beds(), interaction.durationTicks());
}
public List<BoundaryBox> touristInteractAabb() {
    return interactSpots.stream().map(s -> new BoundaryBox(s.pos(), s.pos())).toList();
}
public boolean hasInteraction() { return interaction != null && interaction.isTarget(); }
```
> 注意旧逻辑：商店精力消耗硬编码 -20（TouristSimulation），不在 shop() 里；service 的 energyPerUse 正数=消耗，interaction.energy 负数=消耗，转换时取 `-energy`（负负得正）。

**Deserializer（当前 :122-303）**：
- 解析 `interaction` 块：`energy`(int)、`trade`{goods/profit_rate}、`output`(Map<String,Integer>)、`beds`(int)、`duration_ticks`(int)；缺省 `InteractionConfig.NONE`。
- 解析 `interact_spots`：`JsonArray` of `{"pos":[x,y,z],"action":"<字符串>"}` → `List<InteractSpot>`；`action` 字符串→Activity 用 `Activity.valueOf`（非法值回退 BROWSE）；缺省空。
- **删除** shop/service/tourist_interact_aabb 的解析分支。
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
```

**Block 0 期间保留**（勿删）：`getSatisfaction()/setSatisfaction()`、`getTypePreference()/adjustTypePreference()`——Block 3 迁移完调用点后删除。

## 契约 §5 — Activity 枚举（新 `shared/data/Activity.java`）

```java
package com.wsteam.wandscape.shared.data;

public enum Activity {
    TRAVEL, QUEUE, BROWSE, EAT, BATHE, VIEW, MEDITATE, SLEEP, REST
}
```

- **放 `shared/data`**（不是 tourist/internal），因为 `building/data/BuildingConfig.InteractSpot` 要引用它（避免跨模块直接引用）。
- **交互位动作子集**：`BROWSE/EAT/BATHE/VIEW/MEDITATE/REST` —— `interact_spot_marker` 只在这几个里循环；游客在某 spot 做该 spot 的 action。
- `SLEEP` 归旅店（beds 建筑夜晚）；`TRAVEL/QUEUE` 是 AI 移动/排队状态，非交互位动作。
- `TouristState` 保持移动标签，禁止塞活动。

## 契约 §6 — Config / Constants

`Config.java` 游客段（当前 :141-265）：
- **新增**：
  - `TOURIST_BAR_GAIN_COEFF`（double，默认 2.0）——每条填充 = `round(value_d × coeff)`，封顶 need
  - `TOURIST_ENERGY_RESTORE_THRESHOLD`（double，默认 0.25）——精力低于此比例强烈偏向恢复建筑
  - `TOURIST_QUEUE_WAIT_TOLERANCE_TICKS`（int，默认 2400）——排队等待上限
  - `TOURIST_STAY_MIN_DAYS`（int，默认 2）、`TOURIST_STAY_MAX_DAYS`（int，默认 4）——`departureDeadline = arrivalTime + rand(2~4) × 24000`
  - `TOURIST_VISION_RADIUS`（int，默认 48）——**视野**：目标选择只看半径内（且已加载）的建筑；视野内无合适目标 → 闲逛，直到出现合适的
  - 画像分布权重（可硬编码于 Block 2 生成处，或 Config：`TOURIST_PERSONA_BALANCED/COMFORT/MAGIC/WONDER`）
- **画像需求与等级正相关**（Block 2 生成时 roll）：
  ```
  weightShare_d = persona 各维权重占比（均衡 1/3；偏置如 140/80/80 归一化到 1.0）
  totalNeed      = TOURIST_NEED_BASE + (touristLevel − 1) × TOURIST_NEED_PER_LEVEL
  need_d         = round(totalNeed × weightShare_d)
  ```
  `TOURIST_NEED_BASE`（默认 300）、`TOURIST_NEED_PER_LEVEL`（默认 50）→ 等级越高总需求越高、**越难满足**（自然难度曲线）。如等级1 总300、等级5 总500。
- **删除**：`TOURIST_LEVEL_SATISFACTION_THRESHOLD`、`TOURIST_MAX_SATISFACTION_PER_VISIT`、`TOURIST_PREFERENCE_DECAY`

`WandscapeConstants.java`：确认 `TOURIST_MAX_ENERGY` 存在；如需要加 `TOURIST_BAR_BASE`(=100)。

> **精力/经济数值（energy/trade profit/output 数值）是建筑级 `interaction` 字段，扫描器可编辑，平衡后置**（本方案不预先定死恢复/消耗数值，只定机制）。

## 契约 §7 — 迁移全部 `data/wandscape/buildings/*.json`

规则：
- `category: "shop"` → `"interact"`，`shop` 块 → `interaction.trade`，`interaction.energy = -20`（沿用旧硬编码），`duration_ticks` 从 shop 块搬。
- `category: "service"` → `"interact"`，`service` 块 → `interaction`（`energy = -energy_per_use`、`output = element_output`、`beds = max_occupancy`、`duration_ticks`）。
- `tourist_interact_aabb` → `interact_spots`（每个 AABB 取中心点或 min 点，`action` 默认 `"browse"`，成 `[{"pos":[...],"action":"browse"}]`）。
- `tavern` → `"interact"`（挂一个简单 interaction，如 `{"energy":10}` 或带 output），使其成为游客目标。
- `altar1` → 可选迁移为 `"interact"`（挂 interaction）或保持 `altar`。
- node/storage/government/workstation/crafting_station/potion_station 不变（无 interaction）。

涉及文件（当前目录 `src/main/resources/data/wandscape/buildings/`）：`altar1/bookshop/breadshop/craftstation1/flowershop/inn1/magicshop/nodedark~wind/wood/potionstation1/service_hall/tavern/townhall1/warehouse/workstation1`，及 `deprecated/library.json`。

## Done 判定

1. `./gradlew build` 全绿。
2. 进游戏：放置建筑正常；游客仍按旧逻辑跑（兼容访问器保证行为不变，无回归）。
3. `data/wandscape/buildings/*.json` 全部新 schema，无 `shop`/`service`/`tourist_interact_aabb` 顶层字段。
4. `docs/data/buildings.md` 已更新为新 schema。
