# production/ — 工作站与合成

玩家通过 GUI 提交生产任务，NPC 接取执行 BlockInteractOp，消耗元素/物品→仓库。零自定义方块/BE，无 ContainerMenu（直接发包+Screen）。

## 关键类

**配方数据 (data/)**
- **SynthesizeRecipe** — 合成配方 record：outputItem + cost(Map<ElementType,Long>) + requiredLevel
- **CraftWandRecipe** — 法杖制作配方 record：outputItem + outputNbt(CompoundTag) + cost + requiredLevel
- **BrewPotionRecipe** — 魔药配方 record：outputItem + cost + inputItems(List<String>) + requiredLevel
- 均含 `fromJson(String id, JsonElement json)` 静态工厂

**加载器**
- **ProductionRecipeLoader** — 注册 3 种配方类型到 WandscapeDataLoader（synthesize_recipes / craft_wand_recipes / potion_recipes）

**GUI (client/) — 仅客户端**
- **WorkstationScreen** — 双标签页（分解/合成），右侧 TaskQueuePanel，发送 RequestProductionTaskPacket
- **CraftingStationScreen** — 法杖配方列表+数量+提交按钮，右侧 TaskQueuePanel

**网络包 (network/)**
- **WorkstationDataPacket** — server→client：BlockPos + 可分解物品 + 合成配方
- **CraftingStationPacket** — server→client：BlockPos + 法杖配方
- **PotionStationPacket** — server→client：魔药配方（桩）
- **RequestProductionTaskPacket** — client→server：stationPos/action/recipeOrItemId/quantity
- **TaskQueueModifyPacket** — client→server：stationPos/action("refresh"/"delete"/"move_up"/"move_down")/index
- **TaskQueueDataPacket** — server→client：stationPos + List<QueueEntry(index/blueprintId/summary)>

## 数据流

```
玩家右键 workstation 方块
  → BuildingInteractHandler 检测 category="workstation"
  → ColonyItemBank 查可分解物品 + ProductionRecipeLoader 查合成配方
  → WorkstationDataPacket → 客户端
  → WorkstationScreen 渲染
  → 玩家选择+数量+提交 → RequestProductionTaskPacket → 服务器
  → BuildingApi.enqueueWork(buildingId, WorkItem{blueprint, params})
  → BuildingTaskSource → GlobalTaskPool
  → NPC 领取 → 执行 blueprint → block_interact("decompose"/"synthesize"/"craft_wand")
  → WandscapeBlockInteractExecutor 倒计时 → executeAsyncAction()
  → ColonyItemBank 消耗元素 / 注入物品（或反之）

任务队列 UI 流程
  → Screen 打开 → 发 TaskQueueModifyPacket("refresh") → BuildingApi.getQueue()
  → TaskQueueDataPacket → 客户端 → TaskQueuePanel.setEntries()
  → 玩家点击 [↑][↓][×] → TaskQueueModifyPacket("move_up"/"move_down"/"delete")/index
  → BuildingApi.moveUp/moveDown/removeFromQueue → BuildingSavedData.setDirty()
  → TaskQueueDataPacket 回发 → TaskQueuePanel 更新显示
```

## 执行处理（在 engine/boundary/）

**WandscapeBlockInteractExecutor** 中 4 个异步动作：
- `executeDecompose()` — 消耗物品 → 查 ElementMappingLoader 取 decompose_yield → bank.addElement() 注入元素
- `executeSynthesize()` — 查 ProductionRecipeLoader → bank.consumeElement() 扣除元素 → bank.add() 注入产物
- `executeCraftWand()` — 同 synthesize，产物带 NBT
- `executeBrewPotion()` — 同 synthesize，额外消耗 input_items

## JSON

| 目录 | 数量 | 说明 |
|------|------|------|
| `data/wandscape/synthesize_recipes/` | 1 | stone_bricks |
| `data/wandscape/craft_wand_recipes/` | 4 | builder/gatherer/crafter/ritual_wand |
| `data/wandscape/potion_recipes/` | 1 | mana_potion（桩） |

## 蓝图

`data/wandscape/blueprints/production/` — decompose/synthesize/craft_wand/brew_potion，每个一个 `block_interact` step。

## 建筑 JSON

`data/wandscape/buildings/` — workstation.json / crafting_station.json / potion_station.json

## 依赖

- shared/registry/WandscapeApis（WarehouseApi / BuildingApi）
- building/internal/BuildingSavedData + BuildingInteractHandler
- element/internal/ElementMappingLoader（decompose 查 yield）
- warehouse/ColonyItemBank + WarehouseManager
- engine/boundary/WandscapeBlockInteractExecutor
- core/task（蓝图执行）
