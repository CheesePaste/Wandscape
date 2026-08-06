# 数据格式 — 元素映射 / 种子

## element_mappings/

位置：`src/main/resources/data/wandscape/element_mappings/minecraft_<id>.json`

解析：`element/internal/ElementMappingLoader`（Gson → `ElementMappingConfig`）。由 `/wandscape generate_element_mappings` 自动生成。**block 或 item 二选一作为定位键**。

```json
{
  "block": "minecraft:acacia_log",   // 或 "item": "minecraft:diamond"
  "build_cost": {"wood": 8},          // 建造该方块/物品的元素成本
  "decompose_yield": {},              // 分解产出（非空即 decomposable）
  "decomposable": false,
  "synthesize": {},                   // SynthesizeMeta{unlock_requirement}，从 mapping 推导
  "source": "auto_generated"
}
```

示例：
- `minecraft_diamond.json`：item diamond，build_cost `{metal:1024}`。
- `wandscape_building_scanner.json`：block wandscape:building_scanner，build_cost `{earth:128, wood:8, metal:1024, wind:128}`。

## element_seeds.json

顶层：`description` + `seeds[]`（约 370 条）。种子值是元素价值的**权威来源**（不可覆盖）。

```json
{
  "description": "…Earth:1 → Metal:32(铁) → Dark:256+(末影珍珠)…",
  "seeds": [
    {"item": "minecraft:dirt", "values": {"earth": 1}, "name_cn": "泥土"},
    {"item": "minecraft:iron_ingot", "values": {"metal": 64}, "name_cn": "铁锭"},
    {"item": "minecraft:ender_pearl", "values": {"dark": 64}, "name_cn": "末影珍珠"},
    {"item": "minecraft:ancient_debris", "values": {"metal": 8192, "fire": 4096}, "name_cn": "远古残骸"},
    {"item": "minecraft:water_bucket", "values": {"water": 8, "metal": 192}, "name_cn": "水桶"}
  ]
}
```

价值比例参考：Earth:1 → Metal:32(铁) → Dark:256+(末影珍珠)。

## 7 元素

`ElementType`：EARTH/WOOD/WATER/FIRE/METAL/WIND/DARK（JSON 中用小写 id：earth/wood/water/fire/metal/wind/dark）。

## 使用路径

- 建造算料：`ElementApi.getBuildCost`（EnqueueHelper 施工用料）。
- 分解：`getItemElementValue`（decompose_yield → build_cost 回退）× 1/5 向下取整（Workstation decompose → colonyResources）。
- 合成：`SynthesizeRecipe.fromElementMapping`（cost = buildCost）。
- 审计：`ElementAuditor` + `gametest/ElementAuditRunner`（`wandscape.runAudit=true`）。
