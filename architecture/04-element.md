# 04 — 元素系统 (`element/`)

三层 9 元素 + 方块→元素映射 JSON + `#wandscape:decomposable` 标签。

## 源文件 (3 文件)

| 文件 | 作用 |
|------|------|
| `internal/ElementApiImpl.java` | ElementApi 实现：ID/name → ElementType 查找，tier 查询，委托 ElementMappingLoader 做 BlockState→cost/yield |
| `internal/ElementMappingConfig.java` | record：block ID + build_cost Map + decompose_yield Map + decomposable flag，含 Gson `fromJson` 解析 |
| `internal/ElementMappingLoader.java` | 从 `data/wandscape/element_mappings/*.json` 加载映射，提供 `getBuildCost(BlockState)` / `getDecomposeYield(BlockState)` / `isDecomposable(BlockState)` 查询 |

## 注册项

无 DeferredRegister。通过 `WandscapeApis.setElementApi()` 注册 API 实现。

## JSON 格式 (`data/wandscape/element_mappings/`)

```json
{
  "block": "minecraft:cobblestone",
  "build_cost": { "earth": 4 },
  "decompose_yield": { "earth": 4 },
  "decomposable": true
}
```

已有 5 个映射：`cobblestone` / `oak_log` / `stone_bricks` / `stone` / `dirt`

## 方块标签

`data/wandscape/tags/block/decomposable.json` — `#wandscape:decomposable` 标签，标记可分解方块。

## 依赖

- `shared/api/ElementApi` — 实现的接口
- `shared/data/ElementType` — 9 元素枚举
- `shared/registry/WandscapeApis` — 注册 API 实现
- `dataconfig/WandscapeDataLoader` — 加载 JSON 映射
