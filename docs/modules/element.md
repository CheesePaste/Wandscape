# element/ — 元素模块

`src/main/java/com/wsteam/wandscape/element/`

## 职责

方块/物品 ↔ 元素（7 元素）映射，以及元素价值的来源（种子值 + 配方推导）。映射 JSON 由 `GenerateElementMappingsCommand` 自动生成。

## 7 元素（shared/data/ElementType.java）

EARTH / WOOD / WATER / FIRE / METAL / WIND / DARK（`@SerializedName` 小写 id）。

价值比例参考（element_seeds.json description 自述）：Earth:1 → Metal:32(铁) → Dark:256+(末影珍珠)。实例：dirt earth:1、iron_ingot metal:64、ender_pearl dark:64、ancient_debris metal:8192+fire:4096、water_bucket water:8+metal:192。

## ElementMappingLoader

- 注册 `element_mappings` 类别，parser = `ElementMappingConfig::fromJson`。
- 查找方块按 `blockId` 精确匹配，再回退 item 匹配；种子值单独由 `loadSeedValues` 解析 `element_seeds.json`（`seeds[]` 数组，约 370 条 `{item, values, name_cn}`）。
- 对 `wandscape` 方块：`building_scanner` build_cost = {earth:128, wood:8, metal:1024, wind:128}。

## ElementMappingConfig record

字段：`blockId / itemId / buildCost / decomposeYield / decomposable / synthesize(SynthesizeMeta{unlockRequirement})`，JSON 键：`block / item / build_cost / decompose_yield / decomposable / synthesize`。block 或 item 二选一作为定位键。`synthesize` 配方从 mapping 推导，id 匹配忽略 `minecraft:` 前缀。

## ElementApiImpl

`hasElementMapping / fromId / getBuildCost / getDecomposeYield / isDecomposable`（BlockState 与 ItemStack 两组重载；ItemStack 版 = decompose_yield 非空）。

## ElementValueGenerator

- 固定点求解器：从种子值出发，按配方反推所有元素价值；种子权威不可覆盖；每配方每槽取最便宜选项；净成本 = 原料 − 剩余物品（如奶桶 − 桶）；按 efficiency/outputCount 缩放，下限 1；覆盖 7 种配方类型；人工映射默认跳过（`--force` 覆盖）；`traceRootCauses` 输出 `missing_seeds.txt`。

## ElementAuditor

遍历 `BuiltInRegistries.ITEM`，统计缺种子/缺映射项，分 block/item。GameTest 入口：`gametest/ElementAuditRunner`（监听 ServerStartedEvent，仅当系统属性 `wandscape.runAudit=true`），审计报告写 `build/reports/element_audit.txt`，随后 `server.halt(false)` 退出。

## JSON 结构

见 [data/element_mappings.md](../data/element_mappings.md)。

## 与其他模块关系

- **建造消耗**：建筑施工算料用 `getBuildCost`（EnqueueHelper）。
- **分解**：Workstation decompose → WandscapeBlockInteractExecutor.executeDecompose。仓库存货中有元素价值的物品均可分解，产出 = `getItemElementValue`（decompose_yield → build_cost 回退，与商店售卖同源）的 **1/5（向下取整）**，写入 colonyResources（ResourceId 元素）——防物品复制；count×总价值 < 5 时提前拒绝（不扣物品）。
- **合成**：SynthesizeRecipe 从 mapping 推导，`executeSynthesize` 从 ColonyItemBank 扣元素产物品。
- 映射 JSON 生成：`/wandscape generate_element_mappings`。
