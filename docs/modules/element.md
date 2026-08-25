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

字段：`blockId / itemId / buildCost / disabled`，JSON 键：`block / item / build_cost / disabled`。block 或 item 二选一作为定位键。合成配方从 mapping 推导（`build_cost` 非空即可合成），id 匹配忽略 `minecraft:` 前缀。

## ElementApiImpl

`fromId / hasElementMapping / isDisabled / getBuildCost`（BlockState 与 ItemStack 两组重载）/ `elementItemId`（元素→元素物品 registry id，供游客泡泡/JEI 展示）。

## ElementValueGenerator

- 固定点求解器：从种子值出发，按配方反推所有元素价值；种子权威不可覆盖；每配方每槽取最便宜选项；净成本 = 原料 − 剩余物品（如奶桶 − 桶）；按 efficiency/outputCount 缩放，下限 1；覆盖 7 种配方类型；人工映射默认跳过（`--force` 覆盖）；`traceRootCauses` 输出 `missing_seeds.txt`。

## ElementAuditor

遍历 `BuiltInRegistries.ITEM`，统计缺种子/缺映射项，分 block/item。GameTest 入口：`gametest/ElementAuditRunner`（监听 ServerStartedEvent，仅当系统属性 `wandscape.runAudit=true`），审计报告写 `build/reports/element_audit.txt`，随后 `server.halt(false)` 退出。

## 元素物品（item/ElementItem）

7 种元素各注册一个物品 `element_<id>`（`Wandscape.java` 按 `ElementType` 循环，`ELEMENT_ITEMS` map，进创造模式标签页），显示名复用 `element.wandscape.<id>`。供 JEI/配方展示。

图标 = 白色通道的 `textures/gui/icons/element_<id>.png` 按 `WandscapeTheme.elementColor(id)` 预染色后缩到 16×16 存为 `textures/item/element_<id>.png`（与 V 键面板顶栏同色），模型 `layer0` 指向它——64×64 原图直接当物品贴图会因 item/generated 的 16×16 UV 假设渲染错乱。

获得即转化：`inventoryTick`（仅玩家自身背包触发）检测到元素物品时，若玩家在殖民地范围内（`ColonyApi.getColonyId`），按数量存入所在殖民地仓库（`WarehouseApi.addElement`）、移除物品并播 `WAREHOUSE` 音效；不在范围内保留物品等待进入殖民地。

## JSON 结构

见 [data/element_mappings.md](../data/element_mappings.md)。

## 与其他模块关系

- **建造消耗**：建筑施工算料用 `getBuildCost`（EnqueueHelper）。
- **分解**：Workstation decompose → WandscapeBlockInteractExecutor.executeDecompose。仓库存货中有元素价值的物品均可分解，产出 = `getItemElementValue`（= build_cost，与商店售卖同源）的 **1/divisor（向下取整，除数默认 5，Config `element.decomposeDivisor`）**，写入 colonyResources（ResourceId 元素）——防物品复制；count×总价值 < 除数时提前拒绝（不扣物品）。
- **合成**：SynthesizeRecipe 从 mapping 推导，`executeSynthesize` 从 ColonyItemBank 扣元素产物品。
- 映射 JSON 生成：`/wandscape generate_element_mappings`。
