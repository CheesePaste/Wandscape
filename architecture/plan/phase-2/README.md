# 二阶段（延后）：把 service/shop（及 relax/atm）整合成 interact

> **本方案从一阶段 plan 中摘出，因工程量较大短期不做**。一阶段只做：`shop`/`service` 保持独立 category（模式预设：卖物品 / 产元素）+ 新增 `relax`（回复精力）/ `atm`（取出钱）两个 category，并引入 `interact_spots`（交互位 + 动作）。本二阶段方案把一阶段四个平行的「模式预设块」统一成单一 `interact` category + `interaction` 块。
> **开工前置**：一阶段全部落地、玩家验证稳定后再启动；本阶段依赖一阶段留下的 `interact_spots`/`Activity`/spot marker 机制（两阶段共用，不因本阶段改变）。

## 目标

1. 消除结构重复：`shop{}`/`service{}`/`relax{}`/`atm{}` 四个平行块 → 一个 `interaction` 块（经济 × 精力 × 住宿 × 金钱四正交轴自由组合）。
2. `category` 合并：旅游类建筑（shop/service/relax/atm）统一为 `interact`。
3. **保留** `interact_spots`（每点带动作）机制——本阶段只动「模式预设」载体，不动交互位/动作层。
4. 删除 `ShopConfig`/`ServiceConfig`/`RelaxConfig`/`AtmConfig` 与 `BuildingConfig` 兼容访问器。

## InteractionConfig（新 `shared/data/InteractionConfig.java`）

取代四个模式预设块。三根正交轴（经济 × 精力 × 住宿）外加金钱轴（withdraw）：

```java
public record InteractionConfig(
        int energy,                       // 精力增量：负=消耗，正=恢复（原 shop 硬编码 -20 / service.energy_per_use 取负 / relax.energy_restore）
        @Nullable TradeConfig trade,      // 卖货回款（原 shop.goods+profit_rate）；null=无
        Map<String, Integer> output,      // 服务输出元素（原 service.element_output）；空=无
        int beds,                         // 旅店床位（原 service.max_occupancy）；0=非旅店
        int withdraw,                     // 取现额度（原 atm.withdraw_amount）；0=无
        @SerializedName("duration_ticks") int durationTicks   // 活动时长（原各块 interaction_duration_ticks）
) {
    public static final InteractionConfig NONE = new InteractionConfig(0, null, Map.of(), 0, 0, 0);
    public boolean isTarget() { return this != NONE; }
}
public record TradeConfig(List<ShopGoodDef> goods, double profitRate) { ... }
```

> 注意：商店精力消耗硬编码 -20（一阶段沿用）不在 interaction 里定死，迁移时写入 `energy: -20`。

## JSON schema

```json
{
  "id": "breadshop",
  "category": "interact",
  "interaction": {
    "energy": -20,
    "trade": { "goods": [...], "profit_rate": 0.3 },
    "output": { "earth": 4 },
    "beds": 8,
    "withdraw": 50,
    "duration_ticks": 2400
  },
  "interact_spots": [ {"pos":[1,0,1], "action":"browse"}, ... ]
}
```

## BuildingConfig 变更

- record 组件：删 `shop/service/relax/atm` 四个 + `touristInteractAabb`；新增 `interaction` + `interact_spots`。
- 兼容派生访问器（迁移期保证旧消费者编译）：`shop()`/`service()`/`relax()`/`atm()` 由 interaction 算出；Block 收尾删除。
- `touristInteractAabb()` 由 `interactSpots` 派生（与一阶段一致）。

## 依赖的其它改动

| Block | 动作 |
|---|---|
| 1（scanner） | 四个编辑区（shop/service/relax/atm）→ 一个 `interaction` 编辑区；导出 `interaction` 块；`interact_spots`/marker 不变 |
| 3（tourist AI） | `performShop/Service/Relax/AtmInteraction` 四个方法 → 统一 `performInteraction`（按 interaction 字段分发：trade/output/energy/withdraw/beds） |
| 4（category 清扫） | category 字符串 `"shop"/"service"/"relax"/"atm"` → `"interact"` 或 interaction 字段判断 |
| 5（集成清理） | 删四个 Config 类 + 兼容访问器；grep 零残留；更新 packages/doc 文档 |

## Done 判定（二阶段）

1. 全仓库无 `shop{}`/`service{}`/`relax{}`/`atm{}` 顶层块与 `"shop"/"service"/"relax"/"atm"` category 字符串。
2. `interact_spots`/`Activity`/marker 机制原样保留（回归通过）。
3. 扫描器只编辑一个 `interaction` 块；导出新 schema。
4. 旧存档建筑（一阶段 category=shop/service/relax/atm）迁移后能加载、可交互。
