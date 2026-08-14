# element/ — 元素映射系统

7 种元素类型：EARTH/WOOD/WATER（廉价）/ FIRE/METAL/WIND（中等）/ DARK（最贵）。JSON 数据驱动（966 个自动生成映射）。

## 元素物品（item/ElementItem）

`element/item/ElementItem`：代表一种元素的物品形态（`element_<id>`），图标 = `textures/gui/icons/element_<id>.png`（白色通道）按 `WandscapeTheme.elementColor(id)` 预染色缩到 16×16 存于 `textures/item/`，供 JEI/配方展示。在 `Wandscape.java` 按 `ElementType` 循环注册为 `ELEMENT_ITEMS` map，进创造模式标签页。

获得即转化：物品在玩家背包内每 tick 检查（`inventoryTick`，仅玩家自身背包触发），若玩家在殖民地范围内（`ColonyApi.getColonyId`），把 `elementType` 按数量存入仓库（`WarehouseApi.addElement`）并移除物品、播 `WAREHOUSE` 音效；不在范围内则保留物品待玩家进入殖民地。显示名复用 `element.wandscape.<id>`。

## JSON

位置：`data/wandscape/element_mappings/*.json`。`build_cost` 同时是合成消耗，`synthesize` 块存在即表示可合成。原 synthesize_recipes/ 已合并进来。格式参见 [data/buildings.md](../data/buildings.md)。

**`"disabled": true`**：把该方块/物品**彻底排除出元素经济**——不可分解、不可合成、无建造成本；含禁用方块的建筑/道路在放置时被拒绝（不会当作免费材料白嫖）。整合包作者覆盖映射文件加此字段即可 ban 掉某个映射（覆盖须用 `wandscape` 命名空间 + 同文件名，见 [dataconfig.md](dataconfig.md)）。示例：`element_mappings/disabled/example_disabled.json`（该子目录仅存放示例）。

## 依赖

- shared/api/ElementApi, shared/data/ElementType
- shared/registry/WandscapeApis
- dataconfig/WandscapeDataLoader
- production/data/RecipeUnlockRequirement（用于 SynthesizeMeta）
