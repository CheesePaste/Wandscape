# 制作站/魔法工坊配方管线统一 — 消除「加物品改一堆文件」

> **状态（2026-08-28）**：方案草案，**在 4 权杖（smallitems 2-5 项）功能完成并提交后单独执行**（`refactor:` 独立 commit，可回滚）。不在功能进行中掺结构重构。
> 背景：本次 4 权杖把 craft_wand/brew_potion/misc 已并成一个 `production:craft` + `CraftRecipeView`，消费端 switch 大幅收敛；本文档再把「制作站/魔法工坊可合成物」收敛为**一条通用配方**，让消费端零类型分支。

## 一、问题：类型扇出

一个可合成物要经历：配方 JSON 加载 → 建筑 GUI 列表 → 客户端提交 → 服务端解锁校验 → 队列图标/类别 → NPC 执行扣料 → 计费发布扫描。过去每新增一种配方 `type`（wand/spell/potion/misc），就要在 `ProductionRecipeLoader`、`CraftingStationPacket`、`RequestProductionTaskPacket`、`WandscapeBlockInteractExecutor`、`ProductionEligibility`、`TaskQueueModifyPacket`、`TaskPanelSyncTracker`、`TaskQueuePanel` 各加一个 case——**数据已 JSON 驱动，管线却硬编码按类型复制**。这是结构缺陷，不是「加物品」的固有成本。

要点：**法杖 `attributes[]`/`wand_color` 是 NPC 法杖属性预设（`WandPresetLoader` 消费），与「工艺配方」（产出物品入仓库）是两拨人**，不必耦合。

## 二、目标

「加一个制作站/魔法工坊可合成物」= **配方 JSON + 物品注册 + lang 键**，零管线改动。消费端只认识「一条 craft 配方」，类型差异全部落进配方的可选字段。

## 三、方案

### 1. 一条通用配方 record `CraftRecipe`

替换 `CraftWandRecipe`/`CraftSpellRecipe`/`BrewPotionRecipe`/`MiscRecipe` 四份 record（含各 fromJson 与测试）为一份：

```json
{
  "type": "craft",                 // 纯提示注解；消费端不再分发
  "craft_station": "crafting_station" | "magic_station",
  "id": "peace_wand",
  "display_name": "和平权杖",
  "output": {
    "item": "wandscape:peace_wand",
    "nbt": { "magic_id": "heal" }   // 可选：法杖 preset_id+wand_color / 卷轴 magic_id / 药水 output.nbt 统一塞这
  },
  "cost": { "earth": 1100, "water": 1300 },
  "input_items": [ "minecraft:glass_bottle" ],   // 可选：药水等额外物品原料
  "unlock_requirement": { "min_colony_level": 1 }
}
```

record 字段：`id / craftStation / displayName / outputItem / outputNbt(CompoundTag,可 null) / inputItems(List<String>) / cost(Map<ElementType,Long>) / unlockRequirement`。`fromJson` 读 `output.nbt` 任意 NBT（复用 `BrewPotionRecipe.parseNbt` 递归逻辑，抽公共）。

**产出 NBT 决定物品身份**：
- 法杖：`output.nbt = {preset_id, wand_color}`（原来是 `CraftWandRecipe.outputNbt` 组件）。
- 卷轴：`output.nbt = {magic_id}`（原来在 executor 里拼，改到配方 JSON 显式写，或 `CraftRecipe.resolve` 读 `type` 补）。

### 2. 一份注册表 + 一个解析入口

- `ProductionRecipeLoader`：**只注册一个** `craftRecipes` registry（`dataLoader.register("craft_recipes", (id,json) -> "craft".equals(type) ? CraftRecipe.fromJson(...) : null)`）。删 `craftWandRecipes/potionRecipes/spellRecipes/miscRecipes` 四个 registry。
- `CraftRecipeView`（现用作站内统一解析）升级为 **全管线唯一解析**：`CraftRecipe.resolve(loader, recipeId)`，没有类型 if/else——直接查唯一 registry。
- 删除 `CraftRecipeView`，`RequestProductionTaskPacket`/`WandscapeBlockInteractExecutor`/`ProductionEligibility` 统一用 `CraftRecipe.resolve`。

### 3. 法杖预设与工艺管线解耦

`WandPresetLoader` 仍需 `attributes[]`/`wand_color`。方式二选一（实施时定）：
- (a) 法杖配方 `type` 写 `"wand"`（不进 craft registry），`WandPresetLoader` 照旧解析；工艺管线用 `output.item` 判定是法杖还是其它。**推荐**——两拨人彻底分开，零耦合。
- (b) 法杖配方仍是 `type:"craft"`，`attributes`/`wand_color` 作为可选字段，`WandPresetLoader` 在读到的有 `output.item == wandscape:wand` 且带 attributes 时构建预设。缺点：Loader 与工艺配方耦合。

推荐 (a)：`type` 只决定「是否法杖预设」，工艺管线按 `craft_station` 过滤、不按 type 分发。

### 4. 建筑 GUI 过滤继续走 `craft_station`

`CraftingStationPacket.from` / `MagicStationPacket.from` 按 `craft_station` 字段过滤出各自建筑展示的配方（crafting_station 显示法杖/权杖/药水，magic_station 显示卷轴）——客户端按 `craft_station` 分组，去掉对 `type` 的依赖。`CraftingStationScreen.onSubmit` 恒发 `craft`；`MagicStationScreen.onSubmit` 恒发 `craft`（或保留独立 action，二选一，倾向也归 `craft`）。

### 5. 队列类别统一

`TaskQueueModifyPacket.categorize`/`TaskPanelSyncTracker.categorizeWorkItem`/`TaskQueuePanel.categorizeByBlueprint` 统一归 `production:craft` → `"craft"` 类别；`extractItemId` 白名单只认 `production:craft`。

## 四、改动清单（执行时）

| # | 动作 |
|---|---|
| 1 | 新建 `production/data/CraftRecipe.java`（含 fromJson + parseNbt）；`MiscRecipeTest` → `CraftRecipeTest` |
| 2 | `ProductionRecipeLoader`：删 4 registry，改 1 个 |
| 3 | `CraftRecipeView` → 删；`RequestProductionTaskPacket`/`WandscapeBlockInteractExecutor`/`ProductionEligibility` 改 `CraftRecipe.resolve` |
| 4 | `WandPresetLoader`：保留 `type=="wand"` 解析（方案 a） |
| 5 | 蓝图：`production:craft`（制作站）+ `production:craft_spell`（魔法工坊，或并成 `production:craft`） |
| 6 | 队列三处 + lang 蓝图键 |
| 7 | 相关测试更新（CraftWandRecipeTest/CraftSpellRecipeTest/BrewPotionRecipeNbtTest → 收敛） |
| 8 | `./gradlew test` 全绿；旧存档 pending `production:craft_wand`/`craft_spell` 兼容（核对任务回退） |

## 五、验收

- 新增一个制作站可合成物（如新的法杖预设 / 权杖）：只改 `craft_recipes/*.json` + 物品注册 + lang，**零 Java 管线改动**。
- `craft`/`craft_spell` 配方在合成站/魔法工坊正确展示、提交、扣料、队列图标/类别正确。
- 法杖预设（attributes/wand_color）仍在 NPC 装备生效；卷轴 `magic_id` 正确写入。
