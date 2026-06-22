# element/ — 元素映射系统

## 关键类

- **ElementApiImpl** (internal/) — ElementApi 实现：BlockState→build_cost/decompose_yield 查询
- **ElementMappingLoader** (internal/) — 从 `data/wandscape/element_mappings/*.json` 加载映射
- **ElementMappingConfig** (internal/) — 映射配置 record

## JSON

位置：`data/wandscape/element_mappings/*.json`。5 个映射：cobblestone/dirt/oak_log/stone/stone_bricks

标签过滤：`data/wandscape/tags/block/decomposable.json` 定义可分解方块

## 共享类型

`shared/data/ElementType.java` — 9 元素 3 层：EARTH/WOOD/WATER(层1) / FIRE/IRON/WIND(层2) / GOLD/DIAMOND/ENDER(层3)。MVP 只用 3 种

## 依赖

- shared/api/ElementApi, shared/data/ElementType
- shared/registry/WandscapeApis
- dataconfig/WandscapeDataLoader
