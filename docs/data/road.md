# 数据格式 — 道路 JSON

位置：`src/main/resources/data/wandscape/road_templates/`、`road_rules/`、`road_tiers.json`、`tags/block/custom_roads.json`

> **重要**：`road_templates/`、`road_tiers.json`、`road_rules/` 三个数据文件当前**无代码读取**（`RoadTemplate` 由代码构建；`RoadPresetLoader` 只读 `road_presets` 类别，且当前无对应 JSON 文件）。此处仅记录现有文件的实际字段结构，供将来接线参考（见 gaps.md）。

## road_templates/（样条沿线阵列模板）

`straight.json`：

```json
{
  "id": "straight",
  "template": "wandscape:road/straight",
  "width": 3,
  "size_x": 16,
  "size_z": 16,
  "budget_cost": 16,
  "weight": 4,
  "entries": [{"dx": 7, "dz": 0, "facing": "south"}],
  "exits":  [{"dx": 7, "dz": 15, "facing": "north"}]
}
```

`corner.json`：无 size 字段，weight 2，entries 南(7,0)，exits 东(15,7)。
`crossroad.json`：weight 1，4 entries + 4 exits（N/S/W/E）。

## road_rules/（地面规则）

`dirt.json`：

```json
{
  "default_block": "minecraft:dirt_path",
  "rules": [
    {"ground": "minecraft:grass_block", "output": "minecraft:dirt_path", "chance": 0.85},
    {"ground": "minecraft:grass_block", "output": "minecraft:grass_block", "chance": 1.0},
    {"ground": "minecraft:sand", "output": "minecraft:dirt_path"},
    {"ground": "minecraft:stone", "output": "minecraft:cobblestone"},
    {"ground": "minecraft:dirt", "output": "minecraft:dirt_path"},
    {"ground": "*", "water": true, "output": "minecraft:oak_planks"}
  ]
}
```

## road_tiers.json

```json
{
  "tiers": {
    "dirt": {"default_block": "minecraft:dirt_path", "rules": "wandscape:road_rules/dirt"}
  }
}
```

## tags/block/custom_roads.json

```json
{"replace": false, "values": ["minecraft:purpur_block", "minecraft:nether_bricks", "minecraft:dark_prismarine"]}
```

`wandscape:custom_roads` 标签标记玩家自建道路方块，用于 `RoadBlobCache` 连通块识别与 `RoadRouter` 虫洞。**当前**值在 `WandscapeTags.Blocks.CUSTOM_ROADS` 读取。

## 道路默认调色板（Config TOML）

- `road.surfacePalette` 默认 `minecraft:stone_bricks=50,minecraft:andesite=25,minecraft:stone=25`（权重式）。
- `road.pillar.block` 默认 `minecraft:stone_bricks`；`road.decoration.lampPost`/`lampLight`/`benchBlock` 默认 oak_fence/lantern/oak_stairs。

## 道路预设（RoadPreset，代码 DEFAULT_PRESETS）

`road_presets` 类目（当前无 JSON 文件，预设硬编码）：dirt_path、road(stone5,gravel3,stone_bricks2)、grass、water、cobblestone、gravel、oak_planks。JSON 格式（若将来提供）：`{id, display_name, blocks:[{blockId, weight}]}`。
