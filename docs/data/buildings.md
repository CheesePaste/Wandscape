# 数据格式 — 建筑 JSON

位置：`src/main/resources/data/wandscape/buildings/<id>.json`

解析：`building/data/BuildingConfig.java`（Gson Deserializer，缺失字段有默认值）。建筑没有自定义方块，全部状态存于 `BuildingSavedData`。

## 顶层字段树

```json
{
  "id": "townhall1",                    // string，必填
  "display_name": "Town Hall",          // string，显示名（中文直接内嵌）
  "category": "government",             // string，默认 "basic"。实际值见下
  "first_free": true,                   // bool，可选：殖民地首个该建筑不消耗材料
  "deprecated": true,                   // bool，可选：仍加载（旧存档建筑可用）但隐藏于放置面板
  "pattern": [[x,y,z], ...],            // 3D 整数偏移数组，必填（建造方块位置）
  "block_mapping": {
    "x,y,z": "minecraft:water[level=1]", // 方块态字符串（含 [prop=val] 转义）
    "x,y,z": "minecraft:polished_diorite_slab[type=top]",
    "x,y,z": "minecraft:oak_stairs[facing=east,half=top]",
    "x,y,z": "minecraft:mud_brick_wall[north=tall,west=tall]"
  },
  "block_nbt": {"x,y,z": "<base64压缩NBT>"},   // 可选：方块实体 NBT
  "comfort": 10,                        // int，默认 0（三值之一）
  "magic": 10,                          // int，默认 0
  "wonder": 10,                         // int，默认 0
  "queue": {
    "capacity": 5,                      // int，默认 5
    "task_types": ["building"]          // string[]，默认 ["building"]
  },
  "unlock_requirement": {"min_colony_level": 1},  // 默认 min=1
  "boundary": {
    "min": [x,y,z], "max": [x,y,z]      // AABB 角点（相对 anchor），建造前会清理
  },
  "blueprint": {                        // 引用蓝图 DSL
    "id": "build:clear_and_build",
    "bind": {                           // 键=蓝图参数名，值=$顶层字段名
      "offsets": "$pattern",
      "blocks": "$block_mapping",
      "blocks_nbt": "$block_nbt",
      "name": "$display_name"
    }
  },
  "node_config": {                      // 仅 category=node
    "blueprint": "node:gather",
    "element": "dark",
    "amount_per_harvest": 10,
    "channel_ticks": 1200
  },
  "maintenance_cost": {
    "costs": {"earth": 5, "metal": 5}   // 元素:数量，默认空
  },
  "decoration": {"radius": 8},          // 仅 category=decoration，默认 8
  "wonder_config": {"effects": [...]},  // 已解析但当前无建筑 JSON 使用（见 gaps）
  "shop": {                             // 仅 category=shop
    "goods": [
      {"item_id": "minecraft:bread", "comfort": 6, "magic": 0, "wonder": 0}
    ],
    "profit_rate": 0.3,
    "interaction_duration_ticks": 2400
  },
  "service": {                          // 仅 category=service
    "energy_per_use": 10,
    "element_output": {"water": 4},
    "max_occupancy": 4,
    "interaction_duration_ticks": 600
  },
  "door_offset": [x,y,z],               // 可选：入口位置，缺省螺旋扫描
  "tourist_interact_aabb": [            // 可选：游客交互区列表，缺省螺旋扫描
    {"min":[x,y,z], "max":[x,y,z]}
  ]
}
```

## category 实际值（当前数据文件）

`government`（townhall1）、`storage`（warehouse）、`node`（nodedark/nodeearth/...）、`shop`（breadshop/bookshop/flowershop/magicshop）、`service`（deprecated/library）、`tavern`（tavern）、另有 `crafting_station`（craftstation1）、`potion_station`（potionstation1）、`workstation`（workstation1）、`hotel`（inn1）。

> 各建筑 JSON 在 `buildings/deprecated/` 下的仍会加载（旧存档兼容）但不出现在放置面板。

## 三值 & 维护费示例

- warehouse（storage）：comfort2/magic1/wonder1，维护 earth2+wood2，queue 容量 0。
- nodedark（node）：magic5，维护 dark5，node_config 用 node:gather/dark/10/1200。
- townhall1（government）：三值全 10，维护 earth5+metal5+dark5，door_offset [18,1,4]。
- breadshop（shop）：shop.goods 6 种食品各带三值，profit_rate 0.3，duration 2400。
- tavern：有 door_offset 与 tourist_interact_aabb。
