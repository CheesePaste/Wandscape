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
- **ProductionRecipeLoader** — 从 ElementMappingLoader 派生合成配方（筛选有 synthesize 块的条目），加载法杖和药水配方

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
  → ColonyItemBank 查可分解物品 + ProductionRecipeLoader 从 element_mappings 派生合成配方
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
- `executeSynthesize()` — 查 ProductionRecipeLoader（从 element_mappings 派生）→ bank.consumeElement() 扣除元素 → bank.add() 注入产物
- `executeCraftWand()` — 同 synthesize，产物带 NBT
- `executeBrewPotion()` — 同 synthesize，额外消耗 input_items

## JSON

| 目录 | 数量 | 说明 |
|------|------|------|
| `data/wandscape/element_mappings/` | 9 | 合并了原 synthesize_recipes，synthesize 块存在即表示可合成 |
| `data/wandscape/craft_recipes/` | 9 | 法杖×7 + 魔药×2，type 字段区分，craft_station 指定工作站 |

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

位置：`data/wandscape/craft_recipes/*.json`

```json
{
  "type": "wand",
  "craft_station": "crafting_station",
  "id": "builder_wand",
  "display_name": "Builder's Wand",
  "wand_color": "#FFD700",
  "behaviors": { "building": 1 },
  "range": 1,
  "mana_cost_multiplier": 1.0,
  "output": { "item": "wandscape:wand" },
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
| type | string | 配方类型：`"wand"` / `"potion"`，未来可扩展 `"alchemy"` 等 |
| craft_station | string | 制作工作站：`"crafting_station"` / `"potion_station"` |
| id | string | 配方唯一标识 |
| display_name | string | 法杖显示名称（WandPreset 使用） |
| wand_color | string | 法杖颜色（hex），同时作为 output NBT 和预设颜色 |
| behaviors | {"tag": level} | 法杖能力映射。level 0 表示基础即可使用 |
| range | int | 法杖范围 |
| mana_cost_multiplier | float | 法力消耗倍率 |
| output.item | string | 产出物品 ID（全部法杖共用 "wandscape:wand"） |
| cost | {element: amount} | 制作消耗的元素量 |
| unlock_requirement | {min_comfort/min_magic/min_wonder} | 配方可见性门槛，三维满足才显示 |
| wand_level | {"building"/"crafting"/…: N}（可选） | 覆盖默认 wand 需求。缺省或全为 0 → 任何 NPC 可制作 |
| input_items | [string]（仅 type=potion） | 魔药额外消耗物品列表 |

**注意**：法杖的 NBT 属性（`wand_color`/`behaviors`/`range`/`mana_cost_multiplier`）定义在 JSON 顶层，`CraftWandRecipe` 和 `WandPreset` 均从同一字段读取，消除重复。`output.nbt` 不再嵌套——由 `CraftWandRecipe.fromJson` 自动构建。

**注意**：`behaviors` 中的 level 为 0 时，任何 BUILDING 能力 ≥ 0 的 NPC 均可制作。法杖实际能力等级由 output.nbt 决定，制作完成后可通过 `ColonyItemBank` 装备给 NPC。

## Synthesize 配方（已合并到 element_mappings）

位置：`data/wandscape/element_mappings/*.json`（与方块元素映射同一文件）

```json
{
  "block": "minecraft:stone_bricks",
  "build_cost": { "earth": 4 },
  "decompose_yield": {},
  "decomposable": false,
  "synthesize": {
    "unlock_requirement": { "min_magic": 0 },
    "wand_level": {}
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| build_cost | {element: amount} | 合成消耗（合并了原 synthesize_recipes 的 cost） |
| synthesize | object | 存在即表示可合成，空对象 `{}` 表示禁用 |
| synthesize.unlock_requirement | {min_comfort/min_magic/min_wonder} | 配方可见性门槛 |
| synthesize.wand_level | {"crafting": N}（可选） | 覆盖默认 wand 需求 |

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
