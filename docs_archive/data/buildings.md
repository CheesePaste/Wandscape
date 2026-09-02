# 数据格式 — 建筑 JSON

位置：`src/main/resources/data/wandscape/buildings/<id>.json`

解析：`building/data/BuildingConfig.java`（Gson Deserializer，缺失字段有默认值）。建筑没有自定义方块，全部状态存于 `BuildingSavedData`。

## 顶层字段树

```json
{
  "id": "townhall1",                    // string，必填
  "display_name": "Town Hall",          // string，显示名（中文直接内嵌）
  "creator": "xxx",                     // string，可选：制作者名（商店/旅店/祭坛屏幕左下角显示）
  "category": "government",             // string，默认 "basic"。实际值见下
  "first_free": true,                   // bool，可选：殖民地首个该建筑不消耗材料
  "deprecated": true,                   // bool，可选：仍加载（旧存档建筑可用）但隐藏于放置面板
  "pattern": [[x,y,z], ...],            // 3D 整数偏移数组，必填（建造方块位置，N 个）
  "palette": [                           // 必填：去重后的方块态字符串（M 种，首次出现序）
    "minecraft:water[level=1]",
    "minecraft:polished_diorite_slab[type=top]",
    "minecraft:oak_stairs[facing=east,half=top]",
    "minecraft:mud_brick_wall[north=tall,west=tall]"
  ],
  "block_indices": [0, 2, 1, 3, 0, ...], // 必填：N 个 palette 索引，block_indices[i] ↔ pattern[i]
  "block_nbt": {"x,y,z": "<base64压缩NBT>"},   // 可选：方块实体 NBT（键仍为 "x,y,z"）。
                                                   // 仅创造建筑扫描器导出时写入；生存建筑扫描器
                                                   // 导出为"纯建筑"，不带任何 NBT（防"藏物品→打印"刷物品）。
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
      "blocks": "$block_mapping",       // $block_mapping 是 EnqueueHelper 提供的派生参数
                                        // （由 palette+block_indices 重建的 offset→方块态 map），
                                        // 非原始 JSON 字段
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
  "decoration": {"radius": 8},          // 仅 category=decoration，默认 8
  "wonder_config": {"effects": [...]},  // 已解析但当前无建筑 JSON 使用（见 gaps）
  "shop": {                             // 仅 category=shop
    "goods": [
      {"item_id": "minecraft:bread", "comfort": 6, "magic": 0, "wonder": 0}
    ],
    "profit_rate": 0.3,
    "interaction_duration_ticks": 2400
  },
  "service": {                          // 仅 category=service（产元素+耗精力；max_occupancy>0=旅店）
    "energy_per_use": 10,
    "element_output": {"water": 4},
    "max_occupancy": 4,
    "interaction_duration_ticks": 600
  },
  "relax": {                            // 仅 category=relax（回复精力）
    "energy_restore": 40,
    "interaction_duration_ticks": 1200
  },
  "atm": {                              // 仅 category=atm（取现）
    "interaction_duration_ticks": 1200  // 单次取现 = 初始钱包随机 20%~50%，封顶 travelFund 池子
  },
  "door_offset": [x,y,z],               // 可选：入口位置，缺省螺旋扫描
  "interact_spots": [                   // 交互位列表（相对 anchor），见下节
    {"pos": [x,y,z], "action": "browse", "facing": "south"}
  ]
}
```

## interact_spots（交互位）与游客目标建筑

- **`interact_spots`**：`[{"pos":[x,y,z], "action":"<动作>", "facing":"<朝向>"}, ...]`，坐标**相对 anchor**。**取代**旧 `tourist_interact_aabb`（不再解析，旧字段被忽略）。
- **action 取值** = `Activity` 枚举名小写（子集）：`browse`/`eat`/`bathe`/`view`/`pay`/`read`/`take`/`rest`/`withdraw`。缺省/非法值回退 `browse`。动作只决定游客在该点的活动状态/粒子；**精力/经济效果由建筑 category 的模式预设块决定**。
- **facing 取值** = 水平方向 `north`/`east`/`south`/`west`（缺省 `south`；Y 轴/非法值回退 `south`）：游客在该位做动作时**面朝的方向**。建筑旋转时随建筑一起旋转。扫描器 marker 放置时取玩家面朝方向，潜行右键循环朝向。
- **spot 语义**：**spot 数量 = 该建筑同时交互的游客人数上限**（全满 → 排队）；**交互时长由模式预设块的 `interaction_duration_ticks` 决定**（与 spot 无关）；**同建筑不同 spot 动作可不同**。
- **排队站位**：spot 全满时游客排队，**每个 spot 各排一队**——新游客均匀分散到队最短的 spot 后（并列取最小下标），沿该 spot 的 `facing` **反方向**一个贴一个向后排（间距 `tourist.queueSlotSpacing`，默认 1.0 格），队首紧贴正在交互的游客；**游客朝向 = spot 朝向**（和交互游客同向）。**严格 FIFO**：只有队首可认领该 spot 空位，超 `tourist.queueWaitToleranceTicks` 放弃去别处。
- **必须 ≥1 个 spot，无兜底**：游客目标建筑（category ∈ {shop,service,relax,atm} 且带对应模式预设块）必须给出非空 `interact_spots`，否则游客不选该建筑（旧的 spiral-scan 兜底随 `tourist_interact_aabb` 一并删除）。
- 扫描器用 `interact_spot_marker` 方块标记交互位（放置=加 spot、右键循环动作、潜行右键循环朝向、敲掉=移除），导出时扫进 `interact_spots`。每个 marker 自动生成**预览假人**（站桩循环播放该 spot 动作，含姿态/粒子/朝向），方便创作者查看效果。

## 四类游客 category（模式预设块）

| category | 块 | 交互效果 | 关键字段 |
|---|---|---|---|
| `shop` | `shop{}` | 卖物品（钱包购货、殖民地收元素） | `goods`、`profit_rate`、`interaction_duration_ticks` |
| `service` | `service{}` | 产元素 + 消耗精力；`max_occupancy>0`=旅店（夜晚住宿） | `energy_per_use`、`element_output`、`max_occupancy`、`interaction_duration_ticks` |
| `relax` | `relax{}` | 回复精力（白天恢复建筑） | `energy_restore`、`interaction_duration_ticks` |
| `atm` | `atm{}` | 从 travelFund 取现补钱包（单次=初始钱包随机 20%~50%，封顶池子） | `interaction_duration_ticks` |

> 一阶段四个 category 保持独立，**不合并**；统一成 `interact` 的 `interaction` 块是二阶段（延后）。

## category 实际值（当前数据文件）

`government`（townhall1）、`storage`（warehouse）、`node`（nodedark/nodeearth/nodefire/nodemetal/nodewater/nodewind/nodewood）、`shop`（bakery/book_shop/flower_shop/potion_store/sea_store/ancient_store/creature_store）、`service`（inn1/service_hall/deprecated-library）、`tavern`（tavern）、`mage_hut`（mage_hut1，法师住宅，非游客目标）、`crafting_station`（craftstation1）、`magic_station`（potionstation1，原 `potion_station` 于 2026-08 P 阶段 C 更名）、`workstation`（workstation1）；`relax`/`atm` 为**新增** category（示例 JSON 待补，可用扫描器导出）。

> 各建筑 JSON 在 `buildings/deprecated/` 下的仍会加载（旧存档兼容）但不出现在放置面板。
> `tavern`/`altar1` 保持原 category（招募/祭坛功能按 category 字符串判定），即使有 `interact_spots` 也非游客目标（无四类模式预设块）。

## 三值示例

- warehouse（storage）：comfort2/magic1/wonder1，queue 容量 0。
- nodedark（node）：magic5，node_config 用 node:gather/dark/10/1200。
- townhall1（government）：三值全 10，door_offset [18,1,4]。
- bakery（shop）：shop.goods 5 种食品各带三值，profit_rate 0.3，duration 1200。
- tavern：有 door_offset 与 `interact_spots`（保持 tavern，非游客目标）。
