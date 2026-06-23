# production/ — 工作站与合成

玩家通过 GUI 提交生产任务，NPC 接取执行 BlockInteractOp，消耗元素/物品→仓库。零自定义方块/BE，无 ContainerMenu（直接发包+Screen）。

## 关键类

**配方数据 (data/)**
- **RecipeUnlockRequirement** — 配方解锁门槛：minComfort / minMagic / minWonder。三者同时满足才解锁，任一维度填 0 表示无要求
- **RecipeUnlockChecker** — 静态工具：传入 colonyId + unlockRequirement → 查询 BuildingApi 三值 → 返回布尔值/锁因字符串
- **SynthesizeRecipe** — 合成配方 record：outputItem + cost(Map<ElementType,Long>) + unlockRequirement
- **CraftWandRecipe** — 法杖制作配方 record：outputItem + outputNbt(CompoundTag) + cost + unlockRequirement
- **BrewPotionRecipe** — 魔药配方 record：outputItem + cost + inputItems(List<String>) + unlockRequirement
- 均含 `fromJson(String id, JsonElement json)` 静态工厂，支持 `unlock_requirement`（新三字段格式）和 `unlock_magic_value`（遗留单值，已迁移）

**加载器**
- **ProductionRecipeLoader** — 注册 3 种配方类型到 WandscapeDataLoader（synthesize_recipes / craft_wand_recipes / potion_recipes）

**GUI (client/) — 仅客户端**
- **WorkstationScreen** — 双标签页（分解/合成），右侧 TaskQueuePanel，发送 RequestProductionTaskPacket
- **CraftingStationScreen** — 法杖配方列表+数量+提交按钮，右侧 TaskQueuePanel

**网络包 (network/)**
- **WorkstationDataPacket** — server→client：BlockPos + 可分解物品 + 合成配方（含 `locked_reason` / `wand_level` NBT）
- **CraftingStationPacket** — server→client：BlockPos + 法杖配方（含 `cost`, `locked_reason`, `unlock_requirement`, `wand_level` NBT）
- **PotionStationPacket** — server→client：魔药配方（桩）
- **RequestProductionTaskPacket** — client→server：stationPos/action/recipeOrItemId/quantity
- **TaskQueueModifyPacket** — client→server：stationPos/action("refresh"/"delete"/"move_up"/"move_down")/index
- **TaskQueueDataPacket** — server→client：stationPos + List<QueueEntry(index/blueprintId/summary)>

## 数据流

```
玩家右键 workstation 方块
  → BuildingInteractHandler 检测 category="workstation"
  → ColonyItemBank 查可分解物品 + ProductionRecipeLoader 查合成配方
  → RecipeUnlockChecker.isUnlocked(colonyId, recipe.unlockRequirement) 过滤配方
  → WorkstationDataPacket（仅含已解锁配方 + unlockRequirement NBT）→ 客户端
  → WorkstationScreen 渲染（已解锁正常显示，已过滤的配方不出现）
  → 玩家选择+数量+提交 → RequestProductionTaskPacket → 服务器
  → RecipeUnlockChecker 二次验证（防篡改）→ BuildingApi.enqueueWork(buildingId, WorkItem{blueprint, params})
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
| `data/wandscape/synthesize_recipes/` | 4 | stone_bricks / reinforced_stone(0) / tier2_bricks(1) / crystal_block |
| `data/wandscape/craft_wand_recipes/` | 7 | builder/gatherer/crafter/ritual/archmage/legendary/journeyman_builder_wand |
| `data/wandscape/potion_recipes/` | 2 | mana_potion / stamina_potion(1) |

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

## Craft Wand 配方 JSON 格式

位置：`data/wandscape/craft_wand_recipes/*.json`

```json
{
  "id": "builder_wand",
  "output": {
    "item": "wandscape:wand",
    "nbt": {
      "wand_color": "#FFD700",
      "behaviors": { "building": 0 },
      "range": 1,
      "mana_cost_multiplier": 1.0
    }
  },
  "cost": {
    "earth": 32,
    "wood": 16
  },
  "unlock_requirement": {
    "min_magic": 0
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| id | string | 配方唯一标识 |
| output.item | string | 产出物品 ID（全部法杖共用 "wandscape:wand"） |
| output.nbt | object | 产出物品 NBT（含 wand_color/behaviors/range/mana_cost_multiplier） |
| output.nbt.behaviors | {"tag": level} | 法杖能力映射（如 `{"building": 0}`）。level 0 表示基础即可使用 |
| cost | {element: amount} | 制作消耗的元素量 |
| unlock_requirement | {min_comfort/min_magic/min_wonder} | 配方可见性门槛，三维满足才显示 |
| wand_level | {"building"/"crafting"/…: N}（可选） | 覆盖默认 wand 需求。缺省或全为 0 → 任何 NPC 可制作；`{"building": 2}` → 需要 BUILDING≥2 法杖才能执行此配方 |

**注意**：`behaviors` 中的 level 为 0 时，任何 BUILDING 能力 ≥ 0 的 NPC 均可制作。法杖实际能力等级由 output.nbt 决定，制作完成后可通过 `ColonyItemBank` 装备给 NPC。

## Synthesize 配方 JSON 格式

位置：`data/wandscape/synthesize_recipes/*.json`

```json
{
  "id": "stone_bricks",
  "output": {
    "item": "minecraft:stone_bricks"
  },
  "cost": {
    "earth": 4
  },
  "unlock_requirement": {
    "min_magic": 0
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| id | string | 配方唯一标识 |
| output.item | string | 产出物品 ID |
| cost | {element: amount} | 每单位消耗的元素量 |
| unlock_requirement | {min_comfort/min_magic/min_wonder} | 配方可见性门槛 |
| wand_level | {"crafting": N}（可选） | 覆盖默认 wand 需求。缺省或 `{"crafting": 0}` → 无 CRAFTING 要求，任何 NPC 可制作；`{"crafting": 1}` → 需要 CRAFTING≥1 法杖 |

`wand_level` 在 `RequestProductionTaskPacket.handleServer()` 中提取，通过 `GlobalTaskPool.mergeOverrides()` 合并进任务 requirements（0=删除、≥1=覆盖）。默认（无此字段）等同于 `{"crafting": 0}`，与 `craft_wand` 行为一致。

### `locked_reason` 字段（数据包 NBT）

服务端根据配方状态写入 `locked_reason`，客户端据此选择渲染提示：

| `locked_reason` | 条件 | 客户端显示 |
|---|---|---|
| `"unlocked"` | 三维满足 + 元素足够 | 正常显示成本 |
| `"colony"` | 三维不满足 | 🔒 + C/M/W 门槛 |
| `"elements"` | 三维满足 + 元素不足 + 无 wand_level | 灰色 + 成本（元素不足） |
| `"wand_level"` | 三维满足 + 元素不足 + wand_level>0 | 🔒 + TAG:LEVEL（如 `CRAFTING:1`） |

`wand_level` CompoundTag 仅当 `locked_reason = "wand_level"` 时随 NBT 下发。
