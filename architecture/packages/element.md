# element/ — 元素映射系统

7 种元素类型：EARTH/WOOD/WATER（廉价）/ FIRE/METAL/WIND（中等）/ DARK（最贵）。JSON 数据驱动（966 个自动生成映射）。

## JSON

位置：`data/wandscape/element_mappings/*.json`。`build_cost` 同时是合成消耗，`synthesize` 块存在即表示可合成。原 synthesize_recipes/ 已合并进来。格式参见 [data/buildings.md](../data/buildings.md)。

## 依赖

- shared/api/ElementApi, shared/data/ElementType
- shared/registry/WandscapeApis
- dataconfig/WandscapeDataLoader
- production/data/RecipeUnlockRequirement（用于 SynthesizeMeta）
