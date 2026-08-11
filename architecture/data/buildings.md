# 建筑 JSON 格式

位置：`data/wandscape/buildings/*.json`

## 完整 schema

```json
{
  "id": "townhall1",
  "display_name": "殖民地市政厅",
  "category": "government",
  "pattern": [[-1,0,-1], [0,0,-1], [1,0,-1], ...],
  "block_mapping": {
    "0,0,0": "minecraft:stone_bricks",
    "-1,1,-1": "minecraft:oak_log"
  },
  "comfort": 5,
  "magic": 3,
  "wonder": 2,
  "queue": {
    "capacity": 5,
    "task_types": ["building"]
  },
  "unlock_requirement": {
    "min_colony_level": 1
  },
  "boundary": {
    "min": [-1, -1, -1],
    "max": [1, 1, 1]
  },
  "blueprint": {
    "id": "build:clear_and_build",
    "bind": {
      "offsets": "$pattern",
      "blocks": "$block_mapping",
      "name": "$display_name"
    }
  }
}
```

## 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| id | string | 唯一标识，snake_case |
| display_name | string | 显示名称 |
| creator | string | 可选，制作者名（商店/旅店/祭坛屏幕左下角显示） |
| category | string | basic/node/storage/workstation/crafting_station/potion_station/tavern/shop/service/decoration/wonder/custom |
| pattern | [x,y,z][] | 相对 anchor 的偏移列表。单方块建筑写 `[[0,0,0]]` |
| block_mapping | {"x,y,z":"mod:block"} | pattern 中每个偏移→原版方块 ID |
| comfort/magic/wonder | int | 建筑三值。规则因 category 而异(见下方"三值计入规则") |
| queue | {capacity, task_types} | 建筑内部队列容量和允许的任务类型 |
| boundary | {min:[x,y,z], max:[x,y,z]} | 建筑 AABB（相对 anchor）。用于重叠检测 |
| blueprint | {id, bind} | DSL 模式。id="build:xxx"，bind 的 $field 引用上方 JSON 字段。无此字段时 fallback 到 DataDrivenSteps 遗留路径 |
| unlock_requirement | {min_colony_level} | 建造此建筑需要殖民地达到的最低等级。填 1 表示无条件解锁。2026-07-29 从三值门槛改为等级门槛 |
| decoration | {radius} | **仅 category=decoration**。曼哈顿辐射半径 |
| wonder_config | {effects: [...]} | **仅 category=wonder**。全局效果列表(见下方)。JSON key 为 wonder_config 以避免与 int wonder 字段冲突 |
| shop | {goods: [...], profit_rate} | **仅 category=shop**。货物定义 + 利润率 |
| service | {energy_per_use, element_output, max_occupancy} | **仅 category=service**。交互参数 |
| node_config | {...} | **仅 category=node**。节点采集配置 |
| interaction_radius | int/{x,y,z}/{min,max} | 右键交互区扩展（默认 0）。>0 时玩家可从建筑边界外此范围内右键交互。支持三种格式：uniform int、per-axis {x,y,z}、explicit box {min,max} |
| tourist_interact_aabb | [{min:[x,y,z], max:[x,y,z]}] | **替代旧字段 interact_offset**。室内游客导航目标区域列表（相对于 anchor）。游客 AI 遍历列表，对每个 AABB 螺旋扫描可步行地面，使用第一个找到的位置。未指定时回退到建筑 boundary 包围盒内扫描 |
| entities | [{offset, type, facing, nbt}] | **扫描器导出**。装饰实体列表（物品展示框/发光框/画），NPC 建造时经 `spawn_entity` 步骤重建。offset 为实体所在方块格（相对 anchor），facing 为 Direction 字符串（如 "north"），nbt 为修剪后实体 NBT（base64，位置已重定基为相对偏移）。建筑旋转时 offset 与 facing 同步旋转 |
| deprecated | boolean | 默认 false。为 true 时配置照常加载、旧地图上已放置的建筑功能全部保留（任务/修复/拆除），但**从建筑面板（BUILD_PROJECTION 建筑栏）隐藏**，无法再新建。用于模组版本更新中"保留旧 id + 隐藏面板"的软废弃 |

## 三值计入规则

每栋建筑独立贡献三值（同类型多栋叠加），依次检查：isStructureIntact() → isShutdown() → category/shopHasStock。

| 建筑类别 | 三值计入方式 |
|----------|-------------|
| basic/node/storage/workstation/crafting_station/potion_station/tavern | 每栋正常计入。shutdown/损毁→0 |
| shop | 建筑基础值 + 所有有货 goods 的 comfort/magic/wonder 合计。**单 goods 缺货不影响其他 goods** |
| service | 每栋正常计入。shutdown→游客交互产出减半但三值仍计入（见 docs/simulation.md） |
| decoration | **不计入殖民地总数**。自身 comfort/magic/wonder 以范围辐射方式加成给曼哈顿距离内功能建筑 |
| wonder | **每栋直接计入殖民地总数**，且不受装饰加成上限限制 |
| custom | 三值恒为 0，正常计入但无贡献（见下方「自定义建筑」） |

## 商店建筑 (category: shop)

```json
{
  "id": "bakery",
  "display_name": "面包店",
  "category": "shop",
  "comfort": 2,
  "magic": 0,
  "wonder": 1,
  "shop": {
    "goods": [
      { "item_id": "minecraft:bread", "comfort": 1, "magic": 0, "wonder": 0 },
      { "item_id": "minecraft:cake", "restock_cost": { "earth": 3, "fire": 1 }, "comfort": 2, "magic": 0, "wonder": 1 }
    ],
    "profit_rate": 0.2
  }
}
```

- `goods`: 货物种类由 JSON 固定。`maxStock` **不在 JSON 中定义**，玩家通过商店 GUI 的滑动条为每种货物单独调整（0–64，默认 0，需玩家主动拉滑动条才会补货）
- `restock_cost`: **可选**。每种货物补货 1 件消耗的元素种类和数量。未指定时自动从 `element_mappings` 反查该物品的 `decompose_yield` 作为补货成本。显式指定则覆盖自动推断值
- `comfort`/`magic`/`wonder` (goods 内): 可选，默认 0。有货时该 goods 的三值叠加到商店总三值。货物越丰富商店三值越高
- `profit_rate`: 利润率。游客购物时殖民地获得 `元素成本 × (1 + profit_rate)` 的元素收入
- 每日清晨从仓库扣元素补货到 maxStock。未售出货品次日清除
- 商店建筑交互区半径由 `interaction_radius` 控制（建议 4-6），玩家/游客在范围内即可交互
- `interaction_duration_ticks`: 可选，默认 0。游客在该店交互后的**冷却时长**（tick）。交互效果（满意度/精力/行程记录）在游客到达交互点立即落地，之后游客在该时长内自由闲逛/逛景点、不与其他建筑交互。0=无冷却
- 商店 GUI：每种货物一行，含物品 ID、当前库存 (×cur/max)、拖动滑动条（0–64）、`[−]` `[+]` 按钮

## 服务建筑 (category: service)

```json
{
  "id": "library",
  "display_name": "图书馆",
  "category": "service",
  "comfort": 3,
  "magic": 5,
  "wonder": 2,
  "service": {
    "energy_per_use": 20,
    "element_output": { "earth": 1, "wood": 2 },
    "max_occupancy": 0
  }
}
```

- `max_occupancy`: 最大同时入住数（仅宾馆需要，0=不限）。宾馆通过此字段判断空位
- `interaction_duration_ticks`: 可选，默认 0。游客在该服务建筑交互后的**冷却时长**（tick），语义同商店的 `interaction_duration_ticks`

## 装饰建筑 (category: decoration)

```json
{
  "id": "flower_garden",
  "display_name": "花园",
  "category": "decoration",
  "comfort": 3,
  "magic": 1,
  "wonder": 2,
  "decoration": {
    "radius": 16
  }
}
```

- 自身 `comfort/magic/wonder` 不直接计入殖民地总数
- 以范围辐射方式全额加成给曼哈顿距离 ≤ radius 的功能建筑
- 一个功能建筑可从多个装饰建筑获得加成，总计不超过自身基础值的 100%(可配置)
- shutdown 时辐射加成归零

## 奇观建筑 (category: wonder)

```json
{
  "id": "floating_crystal",
  "display_name": "浮空水晶",
  "category": "wonder",
  "comfort": 0,
  "magic": 15,
  "wonder": 20,
  "wonder_config": {
    "effects": [
      { "type": "stat_mod", "target": "all_npc_spell_power", "value": 1 },
      { "type": "price_mod", "target": "all_shops", "percentage": 10.0 },
      { "type": "rule_unlock", "rule_id": "cross_colony_transport" }
    ]
  }
}
```

### WonderEffect 类型

| type | 额外字段 | 说明 |
|------|---------|------|
| stat_mod | target, value | 修改属性。target: all_npc_spell_power / all_npc_max_mana / all_npc_max_health / all_building_magic |
| price_mod | target, percentage | 修改商店售价。target: all_shops。percentage: 正数为涨价 |
| rule_unlock | rule_id | 解锁规则级能力。rule_id: cross_colony_transport / ... |

- 奇观 intact + 非 shutdown → 全局效果生效
- 奇观损坏或 shutdown → 全局效果暂停
- 三值直接计入殖民地总数，不受装饰加成上限限制
- 不参与游客交互系统

## 自定义建筑 (category: custom)

```json
{
  "id": "player_castle",
  "display_name": "玩家城堡",
  "category": "custom",
  "pattern": [[0,0,0], [0,1,0], ...],
  "block_mapping": { "0,0,0": "minecraft:stone_bricks" },
  "comfort": 0,
  "magic": 0,
  "wonder": 0,
  "boundary": { "min": [-5, 0, -5], "max": [5, 10, 5] },
  "blueprint": { "id": "build:clear_and_build", "bind": { "offsets": "$pattern", "blocks": "$block_mapping", "name": "$display_name" } }
}
```

- **游客不可交互**：游客系统只将 `shop`/`service` 建筑作为交互目标，`custom` 类别天然被排除，不会生成游客交互区。
- **三值恒 0**：`comfort`/`magic`/`wonder` 全为 0，对殖民地三值无贡献。
- 用途：生存玩家用**建筑扫描器**（`building_scanner`）框选自己建造的建筑导出，让 NPC 用蓝图重建；创造建筑扫描器（`creative_building_scanner`）的 Type 也能切到 `custom`。
- 玩家右键建筑走 `BuildingInteractHandler` 的 default 分支（无专用 GUI，仅信息/解锁提示）。

## 废弃建筑 (deprecated)

```json
{
  "id": "old_house",
  "display_name": "旧民居",
  "category": "basic",
  "deprecated": true,
  "pattern": [[0,0,0], ...],
  "block_mapping": { "0,0,0": "minecraft:stone_bricks" },
  "blueprint": { "id": "build:clear_and_build", "bind": { "offsets": "$pattern", "blocks": "$block_mapping", "name": "$display_name" } }
}
```

- `deprecated: true` 时该建筑**从建筑面板（BUILD_PROJECTION 建筑栏）隐藏**，无法再选择新建。
- 配置仍正常加载（`BuildingConfigLoader.get(id)` 命中），**旧地图上已放置的同 id 建筑功能全部保留**：任务队列、损坏检测/修复、拆除、商店补货、游客交互均不受影响。
- 用途：模组版本更新中软废弃某建筑——保留原 id 让旧存档建筑继续运转，同时从面板隐藏避免新玩家再建造。需要换新建筑时用 `deprecated: true` 标记旧 id，另起新 id 的 JSON，不必硬删或改名。
- 文件位置无特殊要求：`deprecated/` 子文件夹只是组织习惯，加载器递归扫描且注册键用 JSON 内 `id`，隐藏语义完全由 `deprecated` 字段决定。

## 节点建筑额外字段

```json
{
  "category": "node",
  "node_config": {
    "element": "earth",
    "amount_per_harvest": 5,
    "channel_ticks": 100,
    "mana_cost": 10,
    "blueprint": "node:gather"
  }
}
```

## 交互区

### 右键交互区 (interaction_radius)

`interaction_radius` 控制玩家右键打开建筑 GUI 的范围：

| interaction_radius | 行为 | 适用建筑 |
|--------------------|------|----------|
| 0（默认） | 必须点击建筑方块或进入建筑边界内部才能交互 | 宾馆、体育场、工作站、仓库等 |
| > 0 | 从建筑包围盒向外扩展 N 格范围内均可交互 | 商店（建议 4-6）、装饰建筑、奇观 |

交互区检查在 `BuildingInteractHandler` 中：先精确匹配 posIndex，未命中时通过 chunkIndex 查找附近建筑的交互区。

### 室内导航目标 (tourist_interact_aabb)

`tourist_interact_aabb` 定义建筑内部游客应该前往的交互区域列表，替代旧字段 `interact_offset`（单个偏移坐标）：

```json
"tourist_interact_aabb": [
  { "min": [-2, 0, -2], "max": [2, 0, 2] }
]
```

- 每个 AABB 的 `min`/`max` 是相对于建筑 anchor 的偏移坐标
- 游客 AI 遍历列表，对每个 AABB 计算世界坐标包围盒，螺旋扫描可步行地面（空气在上、实心在下）
- 使用第一个找到的有效位置作为室内导航目标
- 未指定时回退到建筑 `boundary` 包围盒内螺旋扫描

## 装饰实体 (entities)

由**建筑扫描器**（`building_scanner`）导出。物品展示框/发光框/画是**实体**而非方块，扫描器按边界 AABB 查询并写入此字段，NPC 建造时在方块全部放置后经 `spawn_entity` 步骤重建：

```json
"entities": [
  { "offset": [1, 2, 0], "type": "minecraft:item_frame", "facing": "north", "nbt": "<base64>" },
  { "offset": [1, 2, 0], "type": "minecraft:painting", "facing": "south", "nbt": "<base64>" }
]
```

- `offset`：实体所在方块格（相对 anchor 的偏移）
- `type`：实体注册 ID。白名单：`minecraft:item_frame` / `minecraft:glow_item_frame` / `minecraft:painting`
- `facing`：Direction 字符串（`north/south/east/west/up/down`），独立成字段以便建筑旋转时同步旋转
- `nbt`：修剪后实体 NBT（base64 压缩），已去掉 `UUID/Pos/Motion` 并把位置重定基为相对偏移
- 同一格空气可有正反两面两个展示框，故用**数组**而非按 offset 作 key 的 map
- 实体装饰 v1 不参与材料成本计算（`computeMaterialData` 只算方块）

## 现有建筑

| 类别 | 建筑 | 数量 |
|------|------|------|
| basic | town_hall, grand_tower, residence | 3 |
| node | forest_node, earth_node | 2 |
| storage | warehouse | 1 |
| workstation | workstation | 1 |
| crafting_station | crafting_station | 1 |
| potion_station | potion_station | 1 |
| tavern | tavern | 1 |
| **shop** | **bakery** | **1** |
| **service** | **library**, **inn** | **2** |

共计 13 个建筑 JSON
