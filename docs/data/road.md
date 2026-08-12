# 数据格式 — 道路 JSON

位置：`src/main/resources/data/wandscape/tags/block/custom_roads.json`

## tags/block/custom_roads.json

```json
{"replace": false, "values": ["minecraft:purpur_block", "minecraft:nether_bricks", "minecraft:dark_prismarine"]}
```

`wandscape:custom_roads` 标签标记玩家自建道路方块，用于 `RoadBlobCache` 连通块识别与 `RoadRouter` 虫洞。**当前**值在 `WandscapeTags.Blocks.CUSTOM_ROADS` 读取。

## 道路预设（RoadPreset，代码 DEFAULT_PRESETS）

`road_presets` 类目（当前无 JSON 文件，预设硬编码）：dirt_path、road(stone5,gravel3,stone_bricks2)、grass、water、cobblestone、gravel、oak_planks。JSON 格式（若将来提供）：`{id, display_name, blocks:[{blockId, weight}]}`。
