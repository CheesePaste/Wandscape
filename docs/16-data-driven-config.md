# 数据驱动配置

文档编号：NEW-16
版本：1.0
状态：全部 JSON 配置格式集中定义
依赖：01-shared-api

---

## 一、职责边界

本文档定义 Wandscape 所有 JSON 配置的格式规范。各模块引用本文档的格式定义加载自己的数据。

**配置目录结构：**

```
data/wandscape/
├── wands/                    # 法杖预设
│   ├── builder_wand.json
│   ├── gatherer_wand.json
│   ├── crafter_wand.json
│   └── ritual_wand.json
├── buildings/                # 建筑定义
│   ├── town_hall.json
│   ├── forest_node.json
│   ├── earth_node.json
│   ├── workstation.json
│   ├── crafting_station.json
│   ├── potion_station.json
│   ├── mage_house.json
│   ├── mana_pool.json
│   ├── tavern.json
│   └── ritual_altar.json
├── recipes/                  # 生产配方
│   ├── crafting/
│   │   └── builder_wand.json
│   ├── workstation/
│   │   ├── decompose_cobblestone.json
│   │   └── synthesize_stone_bricks.json
│   └── potion/
│       └── mana_potion.json
├── element_mappings/         # 方块 ↔ 元素映射
│   ├── cobblestone.json
│   ├── oak_log.json
│   └── stone_bricks.json
├── rituals/                  # 仪式定义
│   ├── item_transport.json
│   └── resurrection.json
├── multiblocks/              # 多方块结构
│   └── ritual_altar.json
└── tags/                     # 可分解方块标签
    └── decomposable.json
```

---

## 二、法杖预设格式

```json
// data/wandscape/wands/<id>.json
{
  "id": "builder_wand",
  "display_name": "建筑法杖",
  "default_color": "#FFD700",
  "behaviors": {
    "building": 1
  },
  "default_range": 1,
  "default_mana_cost_multiplier": 1.0,
  "unlock_magic_value": 0
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | string | 是 | 唯一标识 |
| display_name | string | 是 | 管理面板/GUI 中的显示名 |
| default_color | hex string | 是 | `#[0-9A-Fa-f]{6}` |
| behaviors | 键值对 | 是 | 行为类型 → 等级 |
| default_range | int | 否 | 默认 1，范围 1/2/3/5 |
| default_mana_cost_multiplier | float | 否 | 默认 1.0，范围 0.3~1.0，值越低越省魔 |
| unlock_magic_value | int | 否 | 需要殖民地魔法值 ≥ 此值 |

---

## 三、建筑定义格式

```json
// data/wandscape/buildings/<id>.json
{
  "id": "forest_node",
  "display_name": "森林节点",
  "category": "node",
  "block_id": "wandscape:forest_node",
  "pattern": [
    [0, 0, -1], [0, 0, 0], [0, 0, 1],
    [1, 0, -1], [1, 0, 0], [1, 0, 1]
  ],
  "block_mapping": {
    "0,0,0": "minecraft:oak_log",
    "0,0,-1": "minecraft:oak_log",
    "0,0,1": "minecraft:oak_log",
    "1,0,-1": "wandscape:forest_node",
    "1,0,0": "wandscape:forest_node",
    "1,0,1": "wandscape:forest_node"
  },
  "comfort": 1,
  "magic": 0,
  "wonder": 1,
  "maintenance_cost": 2,
  "shutdown_penalty": {
    "output_reduction": 0.5,
    "time_multiplier": 2.0
  },
  "queue": {
    "capacity": 10,
    "task_types": ["gathering"]
  },
  "unlock_requirement": {
    "min_wonder": 0
  },
  "node_config": {
    "element": "wood",
    "amount_per_harvest": 10,
    "channel_ticks": 200,
    "required_behavior": "gathering",
    "required_level": 1
  }
}
```

### 通用字段（所有建筑共有）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | string | 是 | 唯一标识 |
| display_name | string | 是 | 显示名 |
| category | enum | 是 | basic / node / functional / wonder |
| block_id | string | 是 | 对应方块注册 ID |
| pattern | array[BlockOffset] | 是 | 建筑结构方块相对坐标列表。单方块建筑填 `[[0,0,0]]` |
| block_mapping | object | 是 | `"偏移量→方块ID"` 映射。单方块建筑填 `{"0,0,0": "wandscape:xxx"}` |
| comfort | int | 是 | 舒适值贡献 |
| magic | int | 是 | 魔法值贡献 |
| wonder | int | 是 | 奇观值贡献 |
| maintenance_cost | int | 是 | 每周期消耗木元素 |
| shutdown_penalty | object | 否 | `output_reduction` 产出减半（0.5=50%），`time_multiplier` 使用时间加倍（2.0=200%） |
| queue.capacity | int | 是 | 队列容量 |
| queue.task_types | list[string] | 是 | 可发布的任务类型 |
| unlock_requirement | object | 否 | 解锁条件 |

> 所有建筑都有 `pattern` + `block_mapping`，即便是单方块建筑也写 `"pattern": [[0,0,0]]` + `"block_mapping": {"0,0,0": "wandscape:xxx"}`。建造、修复、结构验证全都走同一代码路径，不分支。

### 特殊配置块（按建筑类型）

不同 category 的建筑可以有额外配置块：
- `node_config` — 节点建筑专属
- `tavern_config` — 酒馆专属
- `mana_pool_config` — 魔力池专属
- 等等

---

## 四、配方格式

```json
// data/wandscape/recipes/workstation/synthesize_stone_bricks.json
{
  "id": "synthesize_stone_bricks",
  "type": "wandscape:workstation_synthesize",
  "station": "workstation",
  "output": {
    "item": "minecraft:stone_bricks"
    // count 由玩家在 GUI 中自定，不写在配方中
  },
  "cost": {
    "earth": 256
  },
  "channel_ticks": 1200,
  "required_level": 1,
  "unlock_magic_value": 0
}
```

```json
// data/wandscape/recipes/crafting/builder_wand.json
{
  "id": "craft_builder_wand",
  "type": "wandscape:crafting",
  "station": "crafting_station",
  "output": {
    "item": "wandscape:wand",
    "nbt": {
      "wand_color": "#FFD700",
      "behaviors": { "building": 1 },
      "range": 1,
      "mana_cost_multiplier": 1.0
    }
  },
  "cost": {
    "earth": 32,
    "wood": 16
  },
  "channel_ticks": 1200,
  "required_level": 1,
  "unlock_magic_value": 0
}
```

---

## 五、方块 ↔ 元素映射格式

```json
// data/wandscape/element_mappings/cobblestone.json
{
  "block": "minecraft:cobblestone",
  "build_cost": {
    "earth": 4
  },
  "decompose_yield": {
    "earth": 4
  },
  "decomposable": true
}
```

```json
// data/wandscape/element_mappings/stone_bricks.json
{
  "block": "minecraft:stone_bricks",
  "build_cost": {
    "earth": 4
  },
  "decompose_yield": {},
  "decomposable": false
}
```

`decomposable: true` 的方块自动加入 `#wandscape:decomposable` 方块标签。

---

## 六、仪式定义格式

```json
// data/wandscape/rituals/self_teleport.json
{
  "id": "self_teleport",
  "display_name": "自传送",
  "channel_ticks": 0,
  "needs_altar": false,
  "required_ritual_level": 1,
  "mana_cost": 10,
  "element_cost": {},
  "unlock_wonder_value": 0
}
```

```json
// data/wandscape/rituals/resurrection.json
{
  "id": "resurrection",
  "display_name": "复活仪式",
  "channel_ticks": 2400,
  "needs_altar": true,
  "required_ritual_level": 1,
  "mana_cost": 100,
  "element_cost": {
    "earth": 64,
    "wood": 64,
    "water": 32
  },
  "unlock_wonder_value": 3
}
```

```json
// data/wandscape/rituals/item_transport.json
{
  "id": "item_transport",
  "display_name": "物品传送",
  "channel_ticks": 0,
  "needs_altar": false,
  "required_ritual_level": 1,
  "mana_cost": 1,
  "element_cost": {},
  "unlock_wonder_value": 0
}
```

---

## 七、多方块结构格式

```json
// data/wandscape/multiblocks/ritual_altar.json
{
  "id": "ritual_altar",
  "pattern": {
    "layers": [
      {
        "y_offset": 0,
        "grid": [
          ["S", "S", "S"],
          ["S", "A", "S"],
          ["S", "S", "S"]
        ]
      },
      {
        "y_offset": 1,
        "grid": [
          ["S", "P", "S"],
          ["P", " ", "P"],
          ["S", "P", "S"]
        ]
      }
    ]
  },
  "mapping": {
    "S": "minecraft:stone_bricks",
    "A": "wandscape:altar_core",
    "P": "wandscape:rune_pillar"
  },
  "controller_pos": { "x": 1, "y": 0, "z": 1 },
  "result_block": "wandscape:ritual_altar"
}
```

---

## 八、加载机制

使用 NeoForge `JsonCodec` + `DataMapLoader` 或自定义 `JsonReloadListener`：

```java
public class WandscapeDataLoader extends SimpleJsonResourceReloadListener {
    public WandscapeDataLoader() {
        super(GSON, "wandscape");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data,
                         ResourceManager manager, ProfilerFiller profiler) {
        // 解析 JSON → 注册到对应的 WandscapeDataRegistry
    }
}
```

支持 `/reload` 热重载所有配置。

---

## 九、模组配置文件（TOML）

Wandscape 提供服务端配置文件 `config/wandscape-common.toml`，允许管理员调整核心参数：

```toml
[general]
colony_radius = 128
maintenance_interval_minutes = 20
warehouse_save_interval_minutes = 5

[scheduler]
heartbeat_ticks = 40
same_building_continuation_bonus = 50.0
task_interrupt_cooldown_ticks = 6000
stuck_check_interval_ticks = 60
stuck_min_move_distance = 2.0
stuck_max_retries = 3

[npc]
default_max_health = 40
default_max_mana = 100
default_spell_power = 1
default_mana_regen = 2
base_mana_regen_per_tick = 2
house_mana_regen_multiplier = 3.0
npc_walk_threshold = 64

[wand]
base_operation_range = 16
per_wand_level_range = 8
default_mana_cost_multiplier = 1.0
default_wand_range = 1
```

使用 NeoForge `ModConfigSpec` 实现，服务端配置同步到客户端用于管理面板显示。`WandscapeConstants` 中的默认值在配置文件缺失时作为 fallback。

---

## 十、独立测试方案

1. **JSON 校验**：所有 JSON 文件格式合法、枚举值有效
2. **重载**：`/reload` 后配置更新生效
3. **缺失处理**：JSON 文件缺失时模块加载警告但不崩溃
4. **字段默认值**：可选字段缺失时使用标注的默认值
