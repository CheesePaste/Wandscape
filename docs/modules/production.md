# production/ — 生产模块（工作站/合成站/酿造站）

`src/main/java/com/wsteam/wandscape/production/`

## 职责

建筑内的生产配方：**工作站**（分解/合成）、**合成站**（法杖）、**酿造站**（药水）。配方 JSON 数据驱动，产物可解锁（按殖民地等级）。

## 配方加载

- `ProductionRecipeLoader`：类目 `craft_recipes`。同一类目注册两个 registry：`craftWandRecipes`（JSON `type=="wand"`）与 `potionRecipes`（`type=="potion"`）；type 缺省按 "wand"。
- Synthesize 配方**不从 JSON 加载**，运行时从 ElementMappingConfig 推导（`getSynthesizeRecipe/getAllSynthesizeRecipes`）。
- `RecipeUnlockChecker.isUnlocked`：colonyId null → 锁定；NONE → 解锁；否则 levelMgr.getLevel(colonyId) >= minColonyLevel。

## 配方数据类（production/data/）

- `BrewPotionRecipe`：id/craftStation(缺省 potion_station)/outputItem(output.item)/cost(元素 map)/inputItems(字符串列表)/unlockRequirement。
- `CraftWandRecipe`：额外 displayName、outputNbt（写入 preset_id + 可选 wand_color）。
- `SynthesizeRecipe.fromElementMapping`：cost 取自 config.buildCost()。
- `RecipeUnlockRequirement`：仅 minColonyLevel，缺省 1；NONE = min 1。

## 建筑类别触发（BuildingInteractHandler）

- `storage` → WarehouseDataPacket
- `workstation` → openWorkstationGui（发 WorkstationDataPacket）
- `crafting_station` → openCraftingStationGui（发 CraftingStationPacket）
- `potion_station` → 仅聊天提示"not yet implemented"（**无 GUI**）

## client/ 屏幕

- `CraftingStationScreen`：搜索框 + 配方列表 + 数量滑条 + Submit + 右侧 TaskQueuePanel；Submit 发 RequestProductionTaskPacket("craft_wand")；每 20 tick 用 TaskQueueModifyPacket("refresh") 刷队列。
- `WorkstationScreen`：双标签 Decompose/Synthesize；Submit 分别发 "decompose"（携带 itemId）与 "synthesize"（携带 recipeId）。均按 lockedReason（"unlocked"/"colony"/"elements"）渲染锁与成本。

## network/ 包

- `CraftingStationPacket`（S→C）：maxAffordable 上限 64、locked_reason、unlock_requirement NBT。
- `PotionStationPacket`（S→C）：MVP 存根，handleClient 空实现。
- `RequestProductionTaskPacket`（C→S）：服务端映射 blueprint `production:{decompose,synthesize,craft_wand,brew_potion}`；**服务端二次校验解锁**；channel_ticks：synthesize/decompose = WORKSTATION_CRAFT_TICKS_PER_UNIT(10)×qty、craft_wand = CRAFTING_STATION_CRAFT_TICKS_PER_UNIT(1200)×qty、brew_potion=120；enqueueWork 入队。
- `WorkstationDataPacket`（S→C）：itemList + recipeList。

## 执行（WandscapeBlockInteractExecutor）

- sync 动作 toggle/activate/open_gui 立即执行；异步走 channel tick + thenRun。
- `executeDecompose`：先校验 count×总价值 ≥ divisor（默认 5，否则拒绝，不扣物品），再扣物品（bank.consume），产出 = `mappings.getItemElementValue(itemId)`（decompose_yield→build_cost 回退，与商店同源）× 1/divisor 向下取整，**加到 colonyResources.addResource(元素名 ResourceId)**（非 ColonyItemBank 元素）。
- `executeSynthesize`：校验 bank.countElement ≥ cost，consumeElement，产出 bank.add。
- `executeCraftWand`：同样扣元素，产出 ItemStack 写 CUSTOM_DATA=outputNbt，以带 NBT 的 ItemKey 入库。
- `executeBrewPotion`：校验元素 cost + input_items，同时扣元素与输入物品，产出入仓。

> **差异提示**：decompose 产物写 colonyResources（ResourceId），而 synthesize/craft_wand/brew_potion 的元素消耗走 ColonyItemBank（ElementType）——两类存储不同，见 [gaps.md](../gaps.md)。
