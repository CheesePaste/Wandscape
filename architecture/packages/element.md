# element/ — 元素映射系统

## 关键类

- **ElementApiImpl** (internal/) — ElementApi 实现：BlockState/ItemStack→build_cost/decompose_yield 查询
- **ElementMappingLoader** (internal/) — 从 `data/wandscape/element_mappings/*.json` 加载映射，支持方块和物品查询
- **ElementMappingConfig** (internal/) — 映射配置 record，含可选 `SynthesizeMeta`（合成解锁条件+法杖等级）
- **ElementValueGenerator** (internal/) — 元素价值自动生成器（从原版配方推导）
- **SynthesizeMeta** (ElementMappingConfig 内) — 合成元数据：unlockRequirement + wandLevel

## JSON

位置：`data/wandscape/element_mappings/*.json`。9 个映射。

已将原 `synthesize_recipes/` 合并进来：`build_cost` 同时是合成消耗，`synthesize` 块存在即表示可合成。

格式：
```json
{
  "block": "minecraft:stone_bricks",
  "build_cost": { "earth": 4 },
  "decompose_yield": {},
  "decomposable": false,
  "source": "auto_generated",
  "synthesize": {
    "unlock_requirement": { "min_magic": 0 },
    "wand_level": {}
  }
}
```

## 共享类型

`shared/data/ElementType.java` — 9 元素 3 层：EARTH/WOOD/WATER(层1) / FIRE/IRON/WIND(层2) / GOLD/DIAMOND/ENDER(层3)。MVP 只用 3 种

## 依赖

- shared/api/ElementApi, shared/data/ElementType
- shared/registry/WandscapeApis
- dataconfig/WandscapeDataLoader
- production/data/RecipeUnlockRequirement（用于 SynthesizeMeta）
