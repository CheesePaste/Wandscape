# element/ — 元素映射系统

## 关键类

- **ElementApiImpl** (internal/) — ElementApi 实现：BlockState/ItemStack→build_cost/decompose_yield 查询
- **ElementMappingLoader** (internal/) — 从 `data/wandscape/element_mappings/*.json` 加载映射，支持方块和物品查询
- **ElementMappingConfig** (internal/) — 映射配置 record，含可选 `SynthesizeMeta`（合成解锁条件）
- **ElementValueGenerator** (internal/) — 元素价值自动生成器（从原版配方推导）
- **SynthesizeMeta** (ElementMappingConfig 内) — 合成元数据：unlockRequirement（wandLevel 已删除）

## JSON

位置：`data/wandscape/element_mappings/*.json`。966 个自动生成映射（数据驱动，反映 `element_seeds.json` 中各元素的相对稀有度比值）。

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
    "unlock_requirement": { "min_magic": 0 }
  }
}
```

## 共享类型

`shared/data/ElementType.java` — 7 元素：EARTH/WOOD/WATER（廉价） / FIRE/METAL/WIND（中等） / DARK（最贵）。tier 字段已移除（无生产代码调用）

## 依赖

- shared/api/ElementApi, shared/data/ElementType
- shared/registry/WandscapeApis
- dataconfig/WandscapeDataLoader
- production/data/RecipeUnlockRequirement（用于 SynthesizeMeta）
