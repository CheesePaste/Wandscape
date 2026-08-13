# production/ — 工作站与合成

玩家通过 GUI 提交生产任务，NPC 接取执行 BlockInteractOp，消耗元素/物品↔仓库。零自定义方块/BE，无 ContainerMenu（直接发包+Screen）。

## 数据流

```
玩家右键 workstation 方块
  → BuildingInteractHandler 检测 category="workstation"
  → ProductionRecipeLoader 加载配方
  → RecipeUnlockChecker.isUnlocked() 过滤
  → 数据包 → 客户端 WorkstationScreen
  → 玩家选择+提交 → RequestProductionTaskPacket
  → RecipeUnlockChecker 二次验证（防篡改）→ BuildingApi.enqueueWork()
  → BuildingTaskSource → GlobalTaskPool → NPC 执行
  → block_interact("decompose"/"synthesize"/"craft_wand")
  → WandscapeBlockInteractExecutor 倒计时 → ColonyItemBank 元素出入
```

## 执行处理

WandscapeBlockInteractExecutor 中 4 个异步动作：
- `executeDecompose()` — 查 `getItemElementValue`（decompose_yield→build_cost 回退，与商店同源）→ 产 1/divisor（向下取整，默认 5，Config `element.decomposeDivisor`）→ colonyResources；count×总价值 < divisor 提前拒绝（不扣物品）
- `executeSynthesize()` — 查 element_mappings → bank.consumeElement() → bank.add() 产物
- `executeCraftWand()` — 同 synthesize，产物带 NBT
- `executeBrewPotion()` — 同 synthesize，额外消耗 input_items

## JSON

| 目录 | 数量 | 说明 |
|------|------|------|
| `data/wandscape/element_mappings/` | 9 | 合并了原 synthesize_recipes |
| `data/wandscape/craft_recipes/` | 5 | 法杖×3 + 魔药×2 |

## 依赖

- shared/registry/WandscapeApis（WarehouseApi / BuildingApi）
- building/internal/BuildingSavedData + BuildingInteractHandler
- element/internal/ElementMappingLoader
- warehouse/ColonyItemBank + WarehouseManager
- engine/boundary/WandscapeBlockInteractExecutor
- core/task
