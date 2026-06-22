# production/ — 工作站与合成

玩家通过 GUI 提交生产任务，NPC 接取执行 BlockInteractOp，消耗/产出物品→仓库。零自定义方块/BE。

## 关键类

**配方数据 (data/)**
- **SynthesizeRecipe** — 合成配方 record：outputItem + cost(Map<ElementType,Long>) + requiredLevel
- **CraftWandRecipe** — 法杖制作配方 record：outputItem + outputNbt(CompoundTag) + cost + requiredLevel
- **BrewPotionRecipe** — 魔药配方 record：outputItem + cost + inputItems(List<String>) + requiredLevel
- 均含 `fromJson(String id, JsonElement json)` 静态工厂

**加载器**
- **ProductionRecipeLoader** — 注册 3 种配方类型到 WandscapeDataLoader（synthesize_recipes / craft_wand_recipes / potion_recipes）

**GUI (menu/)**
- **WorkstationMenu** — 万能工作站 ContainerMenu（分解+合成双模式），createMenuProvider(pos, items, recipes) 延迟 1 tick 发包
- **CraftingStationMenu** — 法杖制作站 ContainerMenu，createMenuProvider(pos, recipes)

**GUI (client/) — 仅客户端**
- **WorkstationScreen** — 双标签页（分解/合成），列表+数量+提交按钮，发送 RequestProductionTaskPacket
- **CraftingStationScreen** — 法杖配方列表+数量+提交按钮

**网络包 (network/) — import 链安全，不引用 Screen 类**
- **WorkstationDataPacket** — server→client：BlockPos + 可分解物品 + 合成配方
- **CraftingStationPacket** — server→client：BlockPos + 法杖配方
- **PotionStationPacket** — server→client：魔药配方（桩）
- **RequestProductionTaskPacket** — client→server：stationPos/action/recipeOrItemId/quantity
- 所有 playToClient 包使用 `Consumer<T>` 注入替代直接 import Screen — 服务端安全

## 数据流

```
玩家右键 workstation 方块
  → BuildingInteractHandler 检测 category="workstation"
  → ColonyItemBank 查可分解物品 + ProductionRecipeLoader 查合成配方
  → WorkstationMenu.createMenuProvider(pos, items, recipes)
  → 1 tick 后 WorkstationDataPacket → 客户端
  → WorkstationScreen 渲染
  → 玩家选择+数量+提交 → RequestProductionTaskPacket → 服务器
  → BuildingApi.enqueueWork(buildingId, WorkItem{blueprint, params})
  → BuildingTaskSource → GlobalTaskPool
  → NPC 领取 → 执行 blueprint → block_interact("decompose"/"synthesize"/"craft_wand")
  → WandscapeBlockInteractExecutor 倒计时 → executeAsyncAction()
  → ColonyItemBank 消耗/注入
```

## 执行处理（在 engine/boundary/）

**WandscapeBlockInteractExecutor** 中新增 4 个异步动作：
- `executeDecompose()` — 读 item_id+count → 查 ElementMappingLoader 取 decompose_yield → ColonyItemBank 消耗物品 → ColonyResourceAccess 注入元素
- `executeSynthesize()` — 读 recipe_id+count → 查 ProductionRecipeLoader → 扣除元素 → 注入产物
- `executeCraftWand()` — 同 synthesize，产物带 NBT（DataComponents.CUSTOM_DATA）
- `executeBrewPotion()` — 同 synthesize，额外消耗 input_items

静态引用通过 `setElementMappingLoader()` / `setProductionRecipeLoader()` 在 ServerStarting 时注入。

## JSON

| 目录 | 数量 | 说明 |
|------|------|------|
| `data/wandscape/synthesize_recipes/` | 1 | stone_bricks |
| `data/wandscape/craft_wand_recipes/` | 4 | builder/gatherer/crafter/ritual_wand |
| `data/wandscape/potion_recipes/` | 1 | mana_potion（桩） |

## 蓝图

`data/wandscape/blueprints/production/` — decompose/synthesize/craft_wand/brew_potion，每个一个 `block_interact` step，channel_ticks/mana_cost 可配置。

## 建筑 JSON

`data/wandscape/buildings/` — workstation.json / crafting_station.json / potion_station.json（5 方块十字形，category 对应交互处理器）

## 依赖

- shared/registry/WandscapeApis（WarehouseApi / BuildingApi）
- building/internal/BuildingSavedData + BuildingInteractHandler
- element/internal/ElementMappingLoader（decompose 查 yield）
- warehouse/ColonyItemBank + WarehouseManager
- engine/boundary/WandscapeBlockInteractExecutor
- core/task（蓝图执行）
