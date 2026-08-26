# production/ — 生产模块（工作站/合成站/魔法工坊）

`src/main/java/com/wsteam/wandscape/production/`

## 职责

建筑内的生产配方：**工作站**（分解/合成）、**合成站**（法杖+药水）、**魔法工坊**（魔法卷轴）。配方 JSON 数据驱动，产物可解锁（按殖民地等级）。

## 配方加载

- `ProductionRecipeLoader`：类目 `craft_recipes`。同一类目注册三个 registry：`craftWandRecipes`（JSON `type=="wand"`）、`potionRecipes`（`type=="potion"`）、`spellRecipes`（`type=="spell"`）；type 缺省按 "wand"。
- Synthesize 配方**不从 JSON 加载**，运行时从 ElementMappingConfig 推导（`getSynthesizeRecipe/getAllSynthesizeRecipes`）。
- `RecipeUnlockChecker.isUnlocked`：colonyId null → 锁定；NONE → 解锁；否则 levelMgr.getLevel(colonyId) >= minColonyLevel。

## 配方数据类（production/data/）

- `BrewPotionRecipe`：id/craftStation(缺省 crafting_station)/outputItem(output.item)/cost(元素 map)/inputItems(字符串列表)/unlockRequirement。
- `CraftWandRecipe`：额外 displayName、outputNbt（写入 preset_id + 可选 wand_color）。
- `CraftSpellRecipe`：额外 displayName、magicId（output.magic_id → 写入 spell_scroll CUSTOM_DATA）。
- `SynthesizeRecipe.fromElementMapping`：cost 取自 config.buildCost()。
- `RecipeUnlockRequirement`：仅 minColonyLevel，缺省 1；NONE = min 1。

## 建筑类别触发（BuildingInteractHandler）

- `storage` → WarehouseDataPacket
- `workstation` → openWorkstationGui（发 WorkstationDataPacket）
- `crafting_station` → openCraftingStationGui（发 CraftingStationPacket，含法杖+药水配方）
- `magic_station` → openMagicStationGui（发 MagicStationPacket，含卷轴配方；原 potion_station 类别更名而来，存档 category 于加载时按 BuildingConfig 迁移）

## client/ 屏幕

- `CraftingStationScreen`：搜索框 + 配方列表（法杖+药水）+ 数量滑条 + Submit + 右侧 TaskQueuePanel；Submit 按配方 type 发 RequestProductionTaskPacket("craft_wand" / "brew_potion")；药水行内显示额外原料（玻璃瓶）。**药水配方已删（2026-08-26），当前列表只有法杖**；药水路由代码保留（配方为空时优雅降级）。每 20 tick 用 TaskQueueModifyPacket("refresh") 刷队列。
- `MagicStationScreen`：镜像 CraftingStationScreen，列表显示卷轴图标 + 魔法名 + 元素成本；Submit 发 "craft_spell"。
- `WorkstationScreen`：双标签 Decompose/Synthesize；Submit 分别发 "decompose"（携带 itemId）与 "synthesize"（携带 recipeId）。均按 lockedReason（"unlocked"/"colony"/"elements"）渲染锁与成本。
- 三个界面共用同一**窗口化**数量滑条（`QuantityStepper`）：滑条只显示一页、跨度 ≤64（1–64、65–128、…），左右各带 `-64` / `+64` 按钮整页翻页，`Slider.setRange` 换页时把值夹进新窗口、翻到顶/底为安全 no-op（`QuantityWindow` 管窗口数学）。上限取真实总量：合成/法杖/法术 = `ProductionAffordability.computeMaxAffordable` 按当前元素可负担量，分解 = 物品库存数；最后一页不足一页自动收窄到总量（如总量 100 → 首页 1–64，+64 后 65–100）。

## network/ 包

- `CraftingStationPacket`（S→C）：maxAffordable=按当前元素/成本可负担量（无单次 64 硬上限，见 `ProductionAffordability`）、locked_reason、unlock_requirement NBT；配方条目带 type（wand/potion）+ extra_inputs。
- `MagicStationPacket`（S→C）：镜像 CraftingStationPacket，携带 magic_id。
- `RequestProductionTaskPacket`（C→S）：服务端映射 blueprint `production:{decompose,synthesize,craft_wand,craft_spell,brew_potion}`；**服务端二次校验解锁**；channel_ticks：synthesize/decompose = WORKSTATION_CRAFT_TICKS_PER_UNIT(10)×qty、craft_wand / craft_spell = CRAFTING_STATION_CRAFT_TICKS_PER_UNIT(1200)×qty、brew_potion=120；enqueueWork 入队。
- `WorkstationDataPacket`（S→C）：itemList + recipeList。

## 执行（WandscapeBlockInteractExecutor）

- sync 动作 toggle/activate/open_gui 立即执行；异步走 channel tick + thenRun。
- **channel 进度检查点（2026-08）**：异步 channel 的剩余 tick 每 tick 写回所属全局任务（`GlobalTask.channelRemainingTicks`，经 `TaskPoolSavedData` 持久化）。任务被释放（跟随/重分配）或存档重载后，新 NPC 从检查点**续跑**而非从头合成；被释放任务的孤儿 channel 在下一 tick 被取消（epoch 检测），不会重复产出。覆盖 synthesize/decompose/craft_wand/brew_potion/gather 全部 block_interact 异步动作。
- `executeDecompose`：先校验 count×总价值 ≥ divisor（默认 5，否则拒绝，不扣物品），再扣物品（bank.consume），产出 = `mappings.getItemElementValue(itemId)`（= build_cost，与商店同源）× 1/divisor 向下取整，**加到 colonyResources.addResource(元素名 ResourceId)**（非 ColonyItemBank 元素）。
- `executeSynthesize`：校验 bank.countElement ≥ cost，consumeElement，产出 bank.add。
- `executeCraftWand`：同样扣元素，产出 ItemStack 写 CUSTOM_DATA=outputNbt，以带 NBT 的 ItemKey 入库。
- `executeBrewPotion`：校验元素 cost + input_items，同时扣元素与输入物品，产出入仓。

## 合成队列模型（发布扫描，2026-08-26）

工作站/合成站/魔法工坊的队列（右侧 `TaskQueuePanel`）不再把缺元素的合成任务挂进不可见的
`AWAITING_RESOURCES`/park 状态，而是**发布时找第一个够的**：

- `BuildingTaskSource.poll`（20 tick）发布前用 `BuildingApi.dequeueWorkEligible` + `ProductionEligibility`
  从队首到队尾扫描：消耗元素的生产配方（synthesize/craft_wand/craft_spell/brew_potion）需当前元素够
  （`requiredElements` × 数量 × `ELEMENT_CRAFT_COST_MULTIPLIER`，与执行器 `checkElements` 同源）才发布；
  元素不足的留在队列原位被跳过；decompose/build/gather 恒可发布。
- 一个都不够 → 不发布任务 → 相关 NPC 自然空闲；同时**先判 eligible 再 lease 区块**，避免为跑不了的队列
  每 20 tick 强制加载/释放区块。
- **中途竞态**（发布后元素被并发任务抢走 → 执行器 `ResourceShortageException`）：`BuildingTaskSource` 清理区
  对生产任务改为**回收回队列**（用 `GlobalTask.blueprintId/taskParams/priority` 重建 WorkItem 重新入队 +
  `cancelTask`），下轮扫描按「缺元素」跳过，全程可见；`AWAITING_RESOURCES`/`parkHead` 保留给建材运输等非生产任务。
- UI：`TaskQueueDataPacket.QueueEntry` 带 `insufficient` + `missingElements`（缺失元素 id），`TaskQueuePanel`
  对不足行在类别文字后渲染暗红「缺元素」标签 + 缺失元素图标（`WandscapeTheme.elementIcon`），按钮保留。
- **自动补元素**：`ResourceSupplySystem`（40 tick 心跳）除扫 `AWAITING_RESOURCES` 外，新增扫描
  workstation/crafting_station/magic_station 队列里元素不足的条目，按 (colony,typeId) 去重后聚合元素缺口，
  走 `trySupplyResource`（先合成物品、回退节点采集，自带 in-flight 去重）。

> **差异提示**：decompose 产物写 colonyResources（ResourceId），而 synthesize/craft_wand/brew_potion 的元素消耗走 ColonyItemBank（ElementType）——两类存储不同，见 [gaps.md](../gaps.md)。
