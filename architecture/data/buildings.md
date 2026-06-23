# 建筑 JSON 格式

位置：`data/wandscape/buildings/*.json`

## 完整 schema

```json
{
  "id": "town_hall",
  "display_name": "殖民地市政厅",
  "category": "basic",
  "pattern": [[-1,0,-1], [0,0,-1], [1,0,-1], ...],
  "block_mapping": {
    "0,0,0": "minecraft:stone_bricks",
    "-1,1,-1": "minecraft:oak_log"
  },
  "comfort": 5,
  "magic": 3,
  "wonder": 2,
  "maintenance_cost": 4,
  "shutdown_penalty": {
    "output_reduction": 0.5,
    "time_multiplier": 2.0
  },
  "queue": {
    "capacity": 5,
    "task_types": ["building"]
  },
  "unlock_requirement": {
    "min_wonder": 0
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
| category | string | basic/node/storage/workstation/crafting_station/potion_station/special |
| pattern | [x,y,z][] | 相对 anchor 的偏移列表。单方块建筑写 `[[0,0,0]]` |
| block_mapping | {"x,y,z":"mod:block"} | pattern 中每个偏移→原版方块 ID |
| comfort/magic/wonder | int | 首次建造贡献的三数值。每种建筑类型在殖民地中**仅计一次**：同类型第二栋及以后的建筑不叠加贡献。值在建筑首次修复完成（structureIntact=true）时计入，建筑损毁（last intact building destroyed/damaged）时扣除，修复完成后重新计入。修复期间（structureIntact=false）该类型对三值的贡献归零 |
| maintenance_cost | int | 每维护周期消耗的木元素量 |
| shutdown_penalty | {output_reduction, time_multiplier} | 关停惩罚：产出减半+耗时加倍 |
| queue.capacity | int | 建筑内部队列容量 |
| boundary | {min:[x,y,z], max:[x,y,z]} | 建筑 AABB（相对 anchor）。用于重叠检测 |
| blueprint | {id, bind} | **新 DSL** 模式。id="build:xxx"，bind 的 $field 引用上方 JSON 字段 |
| blueprint (可选) | — | 无此字段时 fallback 到 DataDrivenSteps 遗留路径 |
| unlock_requirement | {min_comfort, min_magic, min_wonder} | 建造此建筑需要殖民地三值达到的门槛。全部填 0 表示无条件解锁。三维门槛同时满足才允许建造，任一维度填 0 表示该维度不限制。建筑被破坏/拆除期间该建筑类型的三值贡献归零，可能重新触发锁状态 |

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

## 现有建筑

town_hall / forest_node / earth_node / grand_tower / warehouse（5 个）+ workstation / crafting_station / potion_station（3 个生产站）= 8 个
