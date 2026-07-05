# 建筑元素性质增强系统（Elemental Affinity System）

文档编号：10
版本：0.2
状态：设计确认中

## 一、设计目标

1. **给建筑赋予元素身份**：每栋建筑有 1-3 种代表元素，**所有建筑类别**（基础/装饰/仓库/商店/服务/奇观等）全部拥有元素属性，没有例外。维护费与服务产出围绕这些元素设计。
2. **布局策略深度**：建筑之间的元素相互作用（五行相克）让玩家在殖民地规划时考虑元素搭配——相邻建筑的元素关系影响双方的三值和产出。
3. **游客引导**：风元素建筑天然吸引游客，玩家可用风元素建筑沿道路铺设游客行进路线。
4. **暗元素作为惩罚维度**：暗元素建筑对周围所有建筑产生负面作用，带来策略取舍。
5. **游客元素偏好**：游客随机获得元素偏好，与建筑类型偏好构成二维选择空间，驱动游客流向不同区域。

## 二、元素总览

当前已定义 7 种元素。所有元素稀有度无区别（稀有度取决于具体方块/物品在 element_mappings 中的绑定，而非元素类型本身）。

| 元素 | 标识 | 参与五行 | 特殊效果 |
|------|------|---------|---------|
| 木 | WOOD | 是 | — |
| 火 | FIRE | 是 | — |
| 土 | EARTH | 是 | — |
| 金 | METAL | 是 | — |
| 水 | WATER | 是 | — |
| 风 | WIND | 否 | 吸引游客 |
| 暗 | DARK | 否 | 负面光环 |

## 三、五行相克（Wu Xing Overcoming Cycle）

五种基本元素遵循五行相克关系：

```
木 ──克──→ 土 ──克──→ 水 ──克──→ 火 ──克──→ 金 ──克──→ 木
```

即：
- 木 克 土（Wood overcomes Earth）
- 土 克 水（Earth overcomes Water）
- 水 克 火（Water overcomes Fire）
- 火 克 金（Fire overcomes Metal）
- 金 克 木（Metal overcomes Wood）

### 3.1 相克效果

当建筑 A 与建筑 B 在彼此的元素影响范围内（见第六章），且建筑 A 的元素 α 克建筑 B 的元素 β：

- **建筑 B（被克方）**：三值（comfort / magic / wonder）下降，产出（service.element_output）下降
- **建筑 A（克方）**：三值上升，产出上升

> 即：被克方削弱，克方增强。一个建筑附近有"被自己所克"的元素建筑越多，自己越强。

### 3.2 同元素建筑

两个同元素建筑在范围内：无额外效果。既不互克也不互生。

### 3.3 不相关元素

两个建筑的元素不在五行链中（如木和水，木和金），互不影响。

### 3.4 多元素建筑

一栋建筑可能同时拥有多种元素（如 {water, earth}），此时它**同时是水元素建筑和土元素建筑**，没有主次之分。五行相克对每种元素**分别独立计算**，然后**代数求和**。

举例：
- 建筑 A：{fire}，建筑 B：{water, earth}
- 检查 fire（A）与 water（B）：水克火 → A 被克（削弱），B 克方（增强）
- 检查 fire（A）与 earth（B）：火与土不在直接相克链中 → 互不影响
- 如果 A 和 B 在范围内，最终 A 获得 -X% 调整（来自 water 的克制），B 获得 +X%（来自克 fire） + 0%（来自 earth 无影响）= +X%

> 关键：每种元素都独立"代表"该建筑参与相克检查。多元素意味着建筑在五行棋盘上占有多个位置。

### 3.5 效果叠加

- 一个建筑可同时受到范围内多个建筑的多个元素影响。
- 每种独立计算，最终取代数和。
- 上限：总调整幅度不超过 ±50%（Config 可配）。

## 四、他山之石 — 同类游戏参考

### 4.1 《了不起的修仙模拟器》（ACS）— 五行风水系统

与本文设计最接近的系统。建筑坐落在不同元素强度的地块上，根据地块元素强度获得增益或减益：

| 元素强度 | 修炼速度倍率 | 突破概率修正 |
|---------|------------|------------|
| ≥ 1.85（极佳） | ×1.5（+50%） | +10% |
| 1.0 ~ 1.85（佳） | ×1.2（+20%） | +5% |
| 0 ~ 1.0（中性） | ×1.0 | +2% |
| -1 ~ 0（凶） | ×0.8（-20%） | -2% |
| -1.9 ~ -1（大凶） | ×0.5（-50%） | -5% |
| < -1.9（极凶） | ×0.1（-90%） | -10% |

**参考要点**：
- 增益/减益不是对称的——减益幅度可以比增益更剧烈（-90% vs +50%），鼓励玩家避免差布局。
- 多档位分级而非连续曲线，玩家可清晰感知"大吉/大凶"。
- △ 但 ACS 是单机修仙游戏，风水只影响单个门派内的修炼效率。玩家可以接受大面积重铺来追求极致风水。在 Minecraft 殖民地中，建筑是玩家一步步建造的，过于严厉的惩罚会打击建造自由度和创造性。

### 4.2 Frostpunk（冰汽时代）— 建筑相邻加成

工作场所毗邻同类建筑时获得效率加成。一般效率加成在 **+10% ~ +30%** 范围内。相邻惩罚极少使用。

### 4.3 其他模拟经营游戏共性

综合 Cities: Skylines、SimCity、Tropico、Banished 等游戏的建筑收益/惩罚机制：

| 游戏 | 相邻加成幅度 | 相邻减益幅度 | 作用半径 |
|------|------------|------------|---------|
| 了不起的修仙模拟器 | +20% ~ +50% | -20% ~ -90% | 地块级 |
| Frostpunk | +10% ~ +30% | 极少使用 | 相邻格 |
| 多数城市建造游戏 | +5% ~ +25% | -5% ~ -25% | 5-20 格 |

### 4.4 关键启示

**1. 减益幅度大多保守**：除 ACS（修仙游戏，接受强惩罚）外，多数游戏的相邻减益控制在 -5% ~ -25%，且 Frostpunk/Foundation 这类鼓励创意的游戏极少使用惩罚。**本系统取对称 ±10% 是安全的**。

**2. "就近"本身就是策略**：Timberborn 和 Songs of Syx 没有显式的"相邻→数值加成"公式，而是通过工人步行时间、服务可达性等**间接机制**驱动布局决策。这意味着**不需要大量数值微调也能创造策略深度**。

**3. RimWorld 的"堆叠自由度"**：一个大房间同时拿到宽敞(+5)+奢华餐厅(+6)+豪华娱乐室(+5) ≈ +16 心情加成。**多层独立系统的叠加 > 单层大数值**。本系统的装饰辐射 + 元素调整 + 奇观效果就是这种多层叠加设计。

**4. 空间约束创造乐趣**：几乎所有优秀 colony sim 都通过限制空间（岛屿/城墙/地块面积/预算）来迫使玩家做取舍。目前 Wandscape 没有空间约束——不过元素系统本身就提供了布局策略，暂时不需要新增空间约束机制。

### 4.5 本系统推荐值

考虑到 Minecraft 模组的定位（轻度不硬核、鼓励创造力、不引入生存惩罚），推荐取较温和的数值：

| 参数 | 建议值 | 理由 |
|------|-------|------|
| 被克方减益 | **-10%** | 低于 ACS，避免"错误布局毁掉殖民地" |
| 克方增益 | **+10%** | 对称，简单直观。鼓励元素搭配布局 |
| 暗元素减益 | **-10%** | 与五行减益一致 |
| 总调整上限 | **±50%** | 5 个同元素建筑即为上限，留给玩家足够的堆叠空间 |
| 作用半径 | **12 格**（曼哈顿距离） | 与装饰建筑辐射半径同一量级 |

> 所有数值均可通过 Config TOML 全局调整。玩家可在开游戏前或服务器管理员根据需求调整。

## 五、建筑元素亲和（Building Element Affinity）

### 5.1 字段设计

每栋建筑在 JSON 中新增 `elements` 数组，列出建筑的代表元素：

```json
{
  "id": "library",
  "display_name": "图书馆",
  "category": "service",
  "elements": ["water", "earth"],
  "comfort": 3,
  "magic": 5,
  "wonder": 2,
  "maintenance_cost": {
    "interval_ticks": 12000,
    "costs": { "water": 2, "earth": 1 }
  },
  "service": {
    "energy_per_use": 20,
    "element_output": { "water": 1, "earth": 2 },
    "max_occupancy": 0
  }
}
```

- `elements` 是字符串数组 `["water", "earth"]`。每个元素在 JSON 中用其小写 ID。
- 数组长度 1-3，大多数建筑 1-2 种，极少数特殊建筑 3 种。
- 该建筑**同时是** water 和 earth 建筑，没有主次。每种元素独立参与五行相克。

### 5.2 元素分配原则

**所有建筑**（无论类别是 basic/node/storage/workstation/shop/service/decoration/wonder/tavern 等）都必须定义 `elements`。没有按类别跳过的特例。

| 元素数 | 适用场景 | 示例 |
|-------|---------|------|
| 1 种 | 大多数建筑，主题单一 | town_hall: ["earth"], warehouse: ["earth"] |
| 2 种 | 混合主题建筑 | bakery: ["fire", "earth"], library: ["water", "earth"] |
| 3 种 | 极少数特殊建筑 | 某仪式建筑: ["fire", "water", "air"] |

- 避免为凑元素而给建筑随意添加无关元素——每个元素应有设计理由。
- 一个建筑两种元素的典型场景是混合主题（如 bakery 需要火来烤、土来承载）。

### 5.3 维护费与产出对齐原则

**原则：建筑的代表元素应体现在维护费消耗和服务产出中。**

- 建筑包含元素 α，则 `maintenance_cost.costs` 应**包含 α**。对于有 service/产出系统的建筑，`service.element_output` 也应包含 α。
- 对齐关系不是代码强制校验，而是设计评审保证——玩家看到"水建筑消耗水、产出水"才有直观的代入感。
- 如果一个建筑有 2 种元素，维护费可以消耗其中一种或全部消耗，由设计师依据经济平衡决定。
- 对于没有 service 产出的建筑类别（如 decoration），只保证维护费对齐即可。

## 六、元素交互范围

### 6.1 范围定义

| 范围类型 | 默认值（曼哈顿距离） | Config 路径 |
|---------|-------------------|-------------|
| 五行相克范围 | 12 格 | elemental_affinity.element_radius |
| 风元素吸引范围 | 28 格 | elemental_affinity.wind_attraction_radius |
| 暗元素光环范围 | 10 格 | elemental_affinity.dark_aura_radius |

### 6.2 范围检查

- 以建筑 anchor 之间的曼哈顿距离计算。
- 与现有的 DecorationBonusSystem 同一模式：心跳扫描或按需缓存。
- 来源建筑的 shutdown 状态使其元素作用暂停（已停机的建筑不应再影响邻居）。

## 七、特殊元素

### 7.1 风元素（Wind）— 游客吸引

风元素建筑不参与五行相克。它的作用是**改变游客的行走路线**。

**机制**：
1. 风元素建筑对周围 `wind_attraction_radius`（默认 28 格曼哈顿距离）内的游客产生吸引力。
2. 游客仅在 **WANDERING 状态**（TouristMoveGoal 中的 WANDERING MoveMode）受此影响。VISITING_BUILDING 和 EXPLORING_POI 状态不受干扰。
3. 游客在 WANDERING 状态下选择漫步目的地时，风元素建筑周围的"吸引点"会加入候选目的地池，优先级高于普通随机漫步目的地。
4. **消耗机制**：游客进入风元素建筑的 `wind_attraction_radius` 范围后，该风元素建筑对该游客的吸引作用**立即永久消失**。游客不一定要进入建筑或使用服务——仅仅是"路过"就消耗了吸引力。
5. 游客可能在使用风元素建筑前就被消耗了吸引力（被其他建筑吸引、精力耗尽等），这种情况下风元素建筑**不产生任何元素收入**。
6. 已消耗的风元素建筑通过 `Set<Vector3i> consumedWindAttractions` 跟踪，随游客实体 NBT 持久化。

**策略意义**：
玩家可以沿道路间隔铺设定数量的风元素建筑，相当于铺设一串"路标"。游客在道路上闲逛时会不断被下一个风元素吸引向前走，从而流经特定商业区。即使游客不一定在每个风元素建筑消费，但他们"经过"本身就增加了与沿线商店/服务建筑交互的概率。铺完一条路线的风元素建筑后，玩家可以拆除或保留——风元素的吸引是一次性消耗，但"引导游客经过某区域"的目标已经达成。

### 7.2 暗元素（Dark）— 负面光环

暗元素建筑不参与五行相克，但对周围所有建筑产生负面作用：

**机制**：
1. 暗元素建筑在 `dark_aura_radius`（默认 10 格曼哈顿距离）内产生光环。
2. 光环内的所有其他建筑（暗建筑自身不受影响）：
   - 三值下降 -10%（Config 可配）
   - 服务产出下降 -10%
3. 多个暗建筑光环叠加（同普通五行叠加规则），总上限 ±50%。
4. 风元素建筑不受暗元素影响（风作为引导性元素应保持中立）。
5. shutdown 的暗建筑光环暂停。

**策略意义**：
暗建筑提供区域减益，但它通常拥有较高的基础三值或奇观效果作为补偿。玩家需要在"放远隔离"和"在黄金地段忍受减益换取收益"之间做取舍。

## 八、数值配置

```toml
[elemental_affinity]
element_radius = 12            # 五行相克作用半径（曼哈顿距离）
wind_attraction_radius = 28    # 风元素吸引半径
dark_aura_radius = 10          # 暗元素光环半径
overcome_debuff = 0.10         # 被克方三值/产出下降比例（10%）
overcome_buff = 0.10           # 克方三值/产出上升比例（10%）
dark_debuff = 0.10             # 暗元素负面下降比例
adjustment_cap = 0.50          # 总调整上限（±50%）
```

## 九、系统改动概览

### 9.1 纯 Java 组件（core/）

**WuXingEngine** — 五行相克纯逻辑（零 MC 依赖）：

```java
public final class WuXingEngine {
    private static final Map<ElementType, ElementType> OVERCOMING = Map.of(
        ElementType.WOOD, ElementType.EARTH,
        ElementType.EARTH, ElementType.WATER,
        ElementType.WATER, ElementType.FIRE,
        ElementType.FIRE, ElementType.METAL,
        ElementType.METAL, ElementType.WOOD
    );

    public static boolean overcomes(ElementType a, ElementType b) { ... }

    public static boolean isWuXingElement(ElementType e) { ... }
}
```

### 9.2 数据类（shared/data/）

**BuildingElementalAffinity** — record:

```java
public record BuildingElementalAffinity(
    List<ElementType> elements
) {
    public boolean isEmpty() { return elements.isEmpty(); }
}
```

### 9.3 建筑 JSON 改动

BuildingConfig 新增 `elements` 字段（`List<ElementType>`）。现有建筑无需立即更新——新字段为空时元素系统对该建筑不起作用。

### 9.4 元素交互系统（building/internal/）

**ElementalAffinitySystem** — 周期心跳（建议与 DecorationBonusSystem 同一节奏，每 200 tick）：

1. 遍历所有建筑的 `elements`。
2. 对每个建筑，扫描其 `element_radius` 内的其他建筑。
3. 对每对建筑（A, B），对 A 的每个元素 × B 的每个元素执行五行检查：
   - A.elem 克 B.elem → A 获得 buff，B 获得 debuff
   - B.elem 克 A.elem → B 获得 buff，A 获得 debuff
   - 无关 → 跳过
   - 同元素 → 跳过
   - 风或暗 → 跳过
4. 每栋建筑的最终调整值 = sum(buff) - sum(debuff)，clamp 到 ±adjustment_cap。
5. 结果缓存到 `ElementalInteractionCache`。

**ElementalInteractionCache** — 缓存每建筑当前元素调整值（倍率）：

```java
public class ElementalInteractionCache {
    // buildingId → 总调整倍率（1.0 = 无调整，1.1 = +10%，0.85 = -15%）
    private final Map<UUID, Double> buildingModifiers;
}
```

### 9.5 三值计算整合（BuildingContributionRegistry）

三值计算中乘入元素调整倍率：

```
adjusted_comfort = building.comfort × elementalModifier
adjusted_magic   = building.magic   × elementalModifier
adjusted_wonder  = building.wonder  × elementalModifier
```

其中 `elementalModifier = 1.0 + ElementalInteractionCache.getModifier(buildingId)`。

- **所有建筑**（包括装饰/仓库/奇观/工作站/基础等全部类别）都参与元素调整。没有按类别跳过的特判逻辑。
- 最终三值进入 `BuildingContributionRegistry.snapshot` 广播 `ColonyEvaluationChangedEvent`。

### 9.6 产出调整

- **service.element_output**：在游客交互时，产出量 × elementalModifier。
- **shop 利润**：不受元素影响（shop 的利润模型是固定的 1.2× 成本）。
- **shutdown 建筑**：元素作用暂停（shutdown 时 ElementalAffinitySystem 跳过该建筑作为来源）。

### 9.7 风元素与游客移动（tourist/internal/）

**TouristMoveGoal 改动**：
- WANDERING MoveMode 中，扫描 `wind_attraction_radius` 内的风元素建筑。
- 过滤掉该游客已消耗的风元素建筑（`consumedWindAttractions` 集合）。
- 剩余的风元素建筑周围生成吸引点（建筑 anchor 附近 3-5 格随机偏移），加入候选目的地池。
- 候选池中，风吸引点的权重高于普通随机漫步点，但低于 POI 和建筑交互目标。
- 游客进入任一个风元素建筑的吸引范围 → 该建筑加入 `consumedWindAttractions`。

**持久化**：
- `consumedWindAttractions` 作为 `List<NbtPos>` 保存到游客实体的 NBT 中。
- 容量上限 64，超出时移除最早记录的条目（防止 NBT 膨胀）。

### 9.8 暗元素建筑（building/internal/）

- `ElementalAffinitySystem` 在遍历到 DARK 元素建筑时，进入暗处理分支：
  - 扫描 `dark_aura_radius` 内的其他建筑（排除风元素建筑和暗建筑自身）。
  - 对每个受影响建筑应用 `-dark_debuff` 调整（叠加到总调整值中）。
- 此调整与其他五行调整**代数叠加**。例如一个建筑既被克（-10%）又在暗光环内（-10%），总调整 -20%。

## 十、与现有系统的关系

| 系统 | 关系 |
|------|------|
| DecorationBonusSystem | 独立并行。最终三值 = 基础值 × (1 + 元素调整) + 装饰加成 |
| DailySettlementSystem | 维护费不变。但建筑的服务产出可能因元素调整而变化 |
| ShopStockManager / ShopInteractionHandler | 利润不受元素调整影响 |
| ServiceInteractionHandler | 产出量 × elementalModifier |
| TouristMoveGoal | WANDERING 时风元素介入目的地选择；元素偏好影响满意度计算 |
| BuildingContributionRegistry | 三值乘入 elementalModifier |
| BuildingConfig | 新增 `elements` 字段（List<String> 序列化为 List<ElementType>） |

> **"所有建筑都参与"的策略意义**：装饰建筑、仓库等不与游客直接交互的建筑同样有元素，同样受五行相克和暗光环影响。这意味着玩家可以把被克制元素塞给非交互建筑来"吸收"负面效果，把增益元素集中给商店/服务建筑。这避免了按类别特判的逻辑复杂性，同时也给玩家多一层布局决策——不再只有"商业区"需要考虑元素布局，整座殖民地的每个角落都有意义。

## 十一、未实现/不在当前范围

- **UI 可视化**：当前元素效果纯数值生效。后续可考虑在通用建筑面板中显示当前元素调整值。
- **风元素跨存档持久化**：`consumedWindAttractions` 随游客 NBT 保存，随游客一起持久化。
- **建筑拆除对元素影响的重算**：建筑变更时 `ElementalInteractionCache.invalidate()` 统一做，不逐建筑精细更新。

## 十二、游客元素偏好（Tourist Element Preference）

游客在生成时，除了已有的建筑类型偏好（typePreference），额外随机获得 **1 种元素偏好**。

### 12.1 基本规则

- 每种元素（EARTH/WOOD/WATER/FIRE/METAL/WIND/DARK）等概率出现。
- 风元素和暗元素同样可选——偏好风的游客更享受风元素建筑，偏好暗的游客对暗元素建筑更有好感。
- 元素偏好与建筑类型偏好（typePreference）**独立且正交**，两者不冲突。

### 12.2 满意度影响

游客满意度公式增加**元素匹配度**维度：

- 游客访问一座建筑时，检查该建筑是否包含游客偏好的元素：
  - **包含偏好元素** → 满意度额外 +5（与建筑类型偏好叠加）
  - **不包含偏好元素** → 无额外加成
  - **包含被偏好元素所克的元素**（如偏好火、建筑含金，火克金） → 满意度 -5
- 上述值与现有的满意度公式**代数相加**。

### 12.3 设计理由

- 游客不再是千人一面——有的游客偏好火区，有的偏好水区，促使玩家布局多样化。
- 与建筑类型偏好形成二维选择空间（类型 + 元素），两个偏好系统都不需要做大数值就能产生有意义的组合。
- 实现成本低：游客 NBT 新增一个 ElementType 字段，满意度调整函数加一个分支即可。

## 十三、扩展思路（不在当前范围）

以下方向在 MVP 阶段不实现，仅作为未来可能的扩展方向记录：

- ~~元素区域共振（Elemental District Resonance）~~ — 暂不实现
- ~~路网元素渗透（Road Element Contagion）~~ — 暂不实现  
- ~~元素潮汐（Elemental Tide）~~ — 暂不实现

