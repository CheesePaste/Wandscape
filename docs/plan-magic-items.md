# 魔法物品化改造 — 任务拆分

> **状态（2026-08-21）**：计划草案。编写者依据 `spell-casting.md`（P1-P5 已实现）、`magic/` 包源码、`potion_station` 现状撰写；**决策 1/2/3 已用户拍板**（见第三节）。标注 **【待确认】** 的为剩余设计歧义点，动手前与用户敲定；标注 **【待调研】** 为需要查源码/文档确认的实现细节。
> 执行方式：按阶段（A→B→C→D）一部分一部分做，每部分为一独立 commit。

## 执行进度

| 步骤 | 状态 | 备注 |
|---|---|---|
| A1 物品注册调研 | ✅ | 结论：`Wandscape.ITEMS`（DeferredRegister.Items）+ `SpellItem extends Item`；magicId 用 `DataComponents.CUSTOM_DATA` 存（项目已有 wand_color 先例）；玩家施法走 `MagicSpellExecutors.castForPlayer(ServerPlayer, MagicDef)`（`MagicCommand` 已复用）；类放 `magic/item/SpellItem.java`，参考 `wand/item/WandItem` / `element/item/ElementItem`；旧 potion 配方引用的 `wandscape:mana_potion` 物品**实际未注册**（影响 C5 处置） |
| A2 MagicDef + castTime | ✅ | `MagicDef` 加 `castTime` 字段（`cast_time` JSON，clamp ≥0，缺省 0）+ 单测（parsesCastTime / negatives）；各魔法 json 的 `cast_time` 值在 A4 tooltip 一起配 |
| A3 SpellItem 通用物品 | ✅ | `magic/item/SpellItem.java`：`DataComponents.CUSTOM_DATA` 存 `magic_id`（get/set 静态方法）；注册 `wandscape:spell_scroll`；创造栏补发各战斗魔法绑定卷轴（`acceptBoundSpellScrolls`，数据驱动排除 UTILITY） |
| A4 tooltip（蓝/冷/施法时间） | ✅ | `appendHoverText` 读 MagicDef：魔法名 + 耗蓝 / 冷却(s) / 施法时间(s) / 创造施法提示；8 个魔法 json 已配 `cast_time`（前摇+法阵+收尾，取 magic_circles duration）；lang 键 zh/en 各 12 条 |
| A5 创造右键施法 | ✅ | `use()` 走 `MagicSpellExecutors.castForPlayer`（服务端）；生存/未绑定/UTILITY 拒绝并提示 |
| A6 注册/贴图/lang | ✅ | model `spell_scroll.json` + 16×16 占位贴图（待美术替换）；`./gradlew test` 全绿；无单测（涉及 ItemStack/MC 运行时留待集成测试）。⚠️ 游戏内视觉/施法待用户 runClient 实测 |
| B1 装备数据模型 | ✅ | `core/component/EquippedMagicComponent`（替代 SpellbookComponent）：按分类名 4 桶 × 每桶 ≤3、桶内=槽位序；`equip/unequip/moveUp/moveDown/list/flattened/knows/isEmpty/fromFlat(纯校验)`；`DEFAULT_EQUIP=beam+heal`；NBT=`spellbookEquip`。SpellbookComponent + 其测试已删除；新组件配 14 单测 |
| B2 NPC 接入 | ✅ | `WandscapeNpc` 字段 `spellbook`→`equippedMagic`；`onAddedToLevel`：`!spellbookLoaded && isEmpty()` 时数据驱动种 beam+heal（分类取 MagicDef）；CastBrain/GuardCombat/SpellcastingApiImpl 改取 `equippedMagic.flattened()`；`SpellcastingApi` 用 `setEquippedAndStrategy` 替代 setKnownSpells/setStrategy |
| B3 策略页 UI 改装备制 | ✅ | `NpcStrategyScreen` 重构：顶部 4 预设按钮（保留）+ 中部 4 分类×3 槽位面板（点已占槽卸载）+ 右侧背包卷轴源列表（点卷轴装备到对应分类首空槽，本地预校验已装/满）；改动发完整扁平装备态 + `consumeSlot`。`NpcDataPacket` 增 `magicCatalog`（战斗魔法 id→分类）供客户端识别卷轴归属 |
| B4 服务端校验 | ✅ | `NpcStrategyPacket(entityId, preset, equipped, consumeSlot)`；`SpellcastingApiImpl.setEquippedAndStrategy` 走 `fromFlat`（未知/UTILITY 丢、每类 ≤3、去重）；handleServer 校验后仅当卷轴魔法"新增装备"才扣一张、回发 NpcDataPacket 对账 |
| B5 UTILITY 不装备 | ✅ | teleport/revive 不进装备：`fromFlat` 分类解析对 UTILITY 返 null 丢弃、`resolvePreset` 天然排除、SpellItem 不绑定；导航回退/祭坛逻辑不变 |
| B6 旧存档兼容 | ✅ | `readAdditionalSaveData` 丢弃旧 `spellbookIds`/`castStrategyPriority`；仅读 `spellbookEquip`；`spellbookLoaded` 标记区分"有意清空载荷"（不重种）与"从未拥有"（种默认）。旧存档 NPC 收敛为默认 beam+heal |
| C1 改名 magic_station | ✅ | `potionstation1.json` category=`magic_station`（id/文件保留）、显示名「魔法工坊」；`BuildingSavedData.load` category 以 BuildingConfig 为准自动迁移旧存档；BuildingSort/CreativeScannerScreen/WandscapeConstants(QUEUE_MAGIC)/JEI(STATION_MAGIC)/lang 全量改；`potion_station` 字符串全清 |
| C2 魔法合成配方 JSON | ✅ | 新 type `spell`：`CraftSpellRecipe`（output.magic_id→CUSTOM_DATA）+ `ProductionRecipeLoader.spellRecipes`；8 个 `scroll_*.json`（beam/heal lv1、fortification/enfeeble_field lv2、petrification/conversion lv3、meteor/desperation lv4），纯元素 cost；不含 teleport/revive |
| C3 magic_station GUI | ✅ | `MagicStationPacket`(S→C, 带 magic_id) + `MagicStationScreen`（卷轴列表+魔法名+元素成本+滑条+Submit+任务队列，镜像 CraftingStationScreen）+ BuildingInteractHandler `case magic_station`；Wandscape/WandscapeClient 注册；`craft_spell` 蓝图 + RequestProductionTaskPacket 动作 + `WandscapeBlockInteractExecutor.executeCraftSpell` |
| C4 合成产物去处 | ✅ | 产物进殖民地仓库（`ColonyItemBank`，与 craft_wand 一致）：scroll 绑定 magic_id 入库（CUSTOM_DATA）+ 运输动画 |
| C5 旧 potion 配方处置 | ✅ | 用户拍板：**归属 crafting_station**——mana/stamina 配方 `craft_station=crafting_station`；CraftingStationPacket 同时携带 wand+potion（RecipeEntry 加 type/extra_inputs），合成站 GUI 列药水（行内显示玻璃瓶原料）并按 type 发 craft_wand/brew_potion；**物品仍不注册**（产出入仓为数据条目、无图标，记录 gaps） |
| D1 全流程集成 | ⏳ | 游戏内端到端待用户 runClient 实测（卷轴合成→策略页装备→NPC 施法） |
| D2 单测全绿 | ✅ | `./gradlew test` 全绿（新增 CraftSpellRecipeTest、JEI spell 收集测试；BuildingSortTest/ElementRecipeCollectorTest 随 renamed 更新） |
| D3 文档同步 | ✅ | plan/architecture/README、docs/{data/craft_recipes,modules/production,building,integration,architecture,README,gaps}.md、decisions.md 同步 |
| D4 提交与版本 | ✅ | 阶段 C 独立 commit；日常改动不递增 mod_version |

## 一、需求摘要

1. **魔法物品形式**：每个魔法有物品形态。tooltip 显示：**耗蓝、冷却时间、施法时间**。创造模式**右键在当前位置施法**（测试用）。
2. **NPC 装备限制**：四种类型魔法**每种只能装备三个**。
3. **神装入口**：在**施法策略页面**给 NPC 装上物品形式的魔法（不再是"开局全会"）。
4. **potion_station 改名 magic_station**：在其中**用元素合成物品形式的魔法**。

## 二、现状关键代码（已核实）

| 需求点 | 现状 |
|---|---|
| 魔法数据 | `magic/data/MagicDef.java`（record：manaCost/baseCooldown/range/category/targetMode/effect…，数据来自 `data/wandscape/magic_spells/*.json`，`SpellbookLoader` 加载） |
| **施法时间** | MagicDef **无 castTime 字段**【待确认：是否新增；或从 circle spec durationTicks 推导】 |
| 决策层 | `magic/internal/CastBrain.java`：`select(known, castable, snapshot)` + `resolvePriority(strategy, known)`；`known` 来自 `SpellbookComponent` |
| NPC 会哪些魔法 | `core/component/SpellbookComponent.java`：`DEFAULT_SPELLS = [beam, heal, meteor, petrification, conversion, desperation, fortification, enfeeble_field]`，`WandscapeNpc.onAddedToLevel` 自动补齐 |
| 施法策略 UI | `npc/client/NpcStrategyScreen.java`：4 分类按钮（单体/群攻/防御/增益）+ 每分类魔法列表 + ↑/↓/开关，发 `NpcStrategyPacket`；策略存 `CastStrategyComponent` |
| 玩家施法 | 玩家施放入口已移除；仅剩 `/wandscape magic cast <id>`（`castForPlayer`）【待调研：`castForPlayer` 具体位置怎么调】 |
| potion_station | `potionstation1.json` 建筑存在；`BuildingInteractHandler` 对 potion_station 提示 not yet implemented、`PotionStationPacket` 空实现、**无 GUI**；`craft_recipes` 有 2 个 potion 配方（`mana_potion`/`stamina_potion`，craft_station=potion_station） |
| 元素合成机制 | `production/ProductionRecipeLoader.java` 读 `craft_recipes/*.json`（type=wand/potion），workstation 消耗 `ColonyItemBank` 元素合成；`craft_station` 指定建筑类别 |
| 物品注册 | `Wandscape.java`（DeferredRegister.Items）【待调研：现有物品（法杖/元素/药水）注册写法，对照新增魔法物品】 |
| 药水物品 | `wandscape:mana_potion`/`stamina_potion` 物品可能已注册（gaps 说"配方无 GUI 无法生产"）【待调研：物品是否已注册、效果如何】 |

## 三、核心设计决策

> ✅ = 已用户拍板；❓ = 待确认。

1. ✅ **"四种类型"** = 施法策略页的 4 分类（SINGLE_TARGET单体 / AOE群攻 / DEFENSE防御 / SUPPORT增益），**每类最多装备 3 个**（共 ≤12）。**UTILITY（teleport / revive）不计入装备限制、不做成物品**：teleport 是导航回退硬性路径（系统固有），revive 仅祭坛可施放（`altar_only`，维持现状）。此两类不进装备 UI、不占槽位、无物品形式。
2. ✅ **魔法物品形态 = 方案 A：通用物品 `SpellItem` + DataComponent 存 magicId**。一份代码、新魔法零注册（最贴"数据驱动"哲学）；tooltip 从 MagicDef 读；合成配方 output 需扩展支持 magic 字段。仅覆盖四类战斗魔法（beam/heal/meteor/petrification/conversion/desperation/fortification/enfeeble_field 及未来战斗魔法），**不覆盖 teleport/revive**。
3. ✅ **新 NPC 默认装备 beam + heal**（占 single_target/support 各 1 槽，玩家可通过策略页换掉）；teleport 由导航系统固有持有（不占槽）。其余魔法由玩家在 magic_station 合成后到策略页装备。
4. ✅ **施法策略页面交互形态**：装备制。策略页上部 4 预设按钮（保留）+ 中部 4 分类×3 槽位面板（点已占槽卸载）+ 右侧背包卷轴源列表（点卷轴装备到对应分类首空槽）。（B 阶段落地）
5. ✅ **magic_station 与旧 potion 配方**：**旧 mana/stamina potion 配方归属 crafting_station**（`craft_station=crafting_station`，随法杖在合成站 GUI 列出、走 brew_potion 蓝图）；输出物品仍不注册（C 阶段用户拍板）。
6. ✅ **魔法合成消耗**：**仅元素**（`ColonyItemBank` 扣元素），无空卷轴原料（C 阶段用户拍板）。
7. ✅ **合成产物去处**：**殖民地仓库**（与 craft_wand 一致）（C 阶段用户拍板）。
8. ✅ **旧存档兼容**：已存档 NPC 旧 Spellbook 数据于读取时丢弃并收敛默认 beam+heal（B6）；potionstation1 建筑 category 于加载时按 BuildingConfig 迁移到 magic_station（C1）。

## 四、任务拆分

### 阶段 A：魔法物品层（物品形式 + tooltip + 创造施法）

| # | 任务 | 要点 | 验收 |
|---|---|---|---|
| A1 | **【待调研】参照现有物品注册** | 读 `Wandscape.java` 物品注册 + 法杖/药水物品类的写法（NBT/DataComponent 用法） | 产出：魔法物品注册方案 |
| A2 | **MagicDef 增加 castTime（施法时间）** | record 加字段（默认 >0 校验），`fromJson` 读 `cast_time`；缺省回退 = 该魔法 circle spec 的 durationTicks 或常量 | 单测：castTime 解析/回退 |
| A3 | **新增 `SpellItem`（通用物品，DataComponent 存 magicId）** | 参照现有物品类写法；DataComponent 存 magicId（`wandscape:magic_id`）；`use()`=创造模式才施法（调 castForPlayer，右键当前位置施法）；校验 magicId 存在且为战斗魔法（非 teleport/revive） | 物品可放入物品栏 |
| A4 | **tooltip 展示耗蓝/冷却/施法时间** | `appendHoverText` 读 MagicDef（缺省数据可读时显示"未知"）；耗蓝=mana_cost、冷却=baseCooldown/20s、施法时间=castTime/20s；中文 lang 键 | 悬停显示三项 |
| A5 | **创造施法短路** | `use()` 限创造模式（`level.isClientSide` 分流、服务端执行）；复用/接通 `castForPlayer` 现存施法链 | 创造右键=原地施法、生存右键=无效/提示 |
| A6 | **贴图 + lang + 注册** | 物品注册进 DeferredRegister、贴图（现有 magic 贴图或占位）、`item.wandscape.*`/`magic.wandscape.*` lang 键 | `./gradlew build` 通过、游戏内出现物品 |

### 阶段 B：NPC 装备系统（每类 ≤3 + 策略页装备）

| # | 任务 | 要点 | 验收 |
|---|---|---|---|
| B1 | **装备数据模型** | 【待调研】改造或替换 `SpellbookComponent` 为"装备魔法"容器：按 Category 分桶（4 桶），每桶 ≤3；默认装备 **beam + heal**；NBT 持久；纯 Java 零 MC（放 core/component/） | 单测：键帽/桶上限/默认/持久 |
| B2 | **NPC 接入** | `WandscapeNpc` 持有新组件、`onAddedToLevel` 去默认补齐逻辑（改由组件默认 beam+heal）；`CastBrain` 的 `known` 改为**只取已装备魔法**（UTILITY 仍系统固有） | 单测：CastBrain 只从装备魔法选 |
| B3 | **策略页 UI 改装备制** | `NpcStrategyScreen`：列出四类型槽位（每类 3 格）+ 可选魔法物品源（背包/仓库/殖民地已有物品）→ 装备到槽位；清空槽位=卸载 | UI 可装/卸 |
| B4 | **服务端校验** | `NpcStrategyPacket` 服务端处理：只接受已装备范围、每类 ≤3、去重；拒绝非法就刷新回正确状态 | 单测：非法请求被拒 |
| B5 | **UTILITY 魔法不装备** | teleport/revive 不进装备 UI、不占槽位；导航回退/祭坛独立逻辑不变 | 行为不变 |
| B6 | **旧存档兼容** | 已存档 NPC 的旧 Spellbook 数据迁移/丢弃朝向【待确认：是否需要】 | 加载不崩 |

### 阶段 C：magic_station（改名 + 元素合成魔法）

| # | 任务 | 要点 | 验收 |
|---|---|---|---|
| C1 | **potion_station → magic_station 重命名** | 建筑 json（`potionstation1.json`→`magicstation1.json` 或保留 id 改显示名）、类别名、商品展示、`craft_station` 引用、lang 键全量改；BE/interact 文案 | 游戏中建筑显示 magic_station |
| C2 | **魔法合成配方 JSON** | `craft_recipes/*_spell.json`：新 type（`magic`/`spell`）+ output 指向魔法物品（带 magicId）+ cost 元素；`ProductionRecipeLoader` 解析新 type；**只覆盖四类战斗魔法（不含 teleport/revive）** | 配方加载进注册表 |
| C3 | **magic_station GUI（合成界面）** | 参照 CraftingStationScreen/WorkstationScreen：选魔法 → 显示元素成本/输入 → 消耗 `ColonyItemBank` 合成出物品进背包/仓库【待确认产物去处】 | 可合成出魔法物品 |
| C4 | **魔法合成产物处置** | 合成产出：进玩家背包 or 殖民地仓库 or 临时交互栏【待确认】 | 产物可用 |
| C5 | **旧 potion 配方处置** | mana/stamina potion 保留（craft_station 改 magic_station）或移除【同决策点 5】 | 行为符合确认结果 |

### 阶段 D：集成、测试、文档

| # | 任务 | 要点 | 验收 |
|---|---|---|---|
| D1 | **全流程集成** | 合成魔法 → 施法策略页装备到 NPC → NPC 战斗/自防御只施已装备魔法 + 创造施法测试 | 端到端可走通 |
| D2 | **单测全绿 + 补测** | 纯逻辑（A2/A3/B1/B2/B4）+ 已有 46 个 CastBrain 系测试回归 | `./gradlew test` 全绿 |
| D3 | **文档同步** | `docs/spell-casting.md`、`docs/modules/magic.md`、`docs/data/craft_recipes.md`、建筑类文档、`architecture/` 对应章节 | 文档与代码一致 |
| D4 | **提交与版本** | 按阶段分批 commit（A/B/C/D 各一块，内部 `git add` 聚合）；是否递增 `mod_version` 依改动规模定 | Git 历史清晰 |

## 五、依赖顺序

```
A1 → A2 → A3 → A4 → A5 → A6     （魔法物品形态！独立可交付）

B1 → B2 → B5 → B3 → B4 → B6     （装备系统，依赖 A 的物品形态）
                  ↘
C1 → C2 → C3 → C4 → C5           （magic_station 合成，C1 可与 A 并行）

D1 → D2 → D3 → D4                （收尾）
```

A 阶段全部完成即可独立提交（物品形态本身是可用成果）；B、C 阶段依赖 A3 的 SpellItem；C1 改名与 A 可并行。

## 六、剩余待确认问题（做到对应阶段时确认）

✅ 已确认：1) 四种类型=策略页 4 分类、每类 ≤3、UTILITY（teleport/revive）不物品化不占槽、revive 仅祭坛可用；2) 物品形态=通用 SpellItem+DataComponent 存 magicId；3) 新 NPC 默认装备 beam+heal；**4) 阶段 B 追加确认（grill-me 2026-08-21）**：装备语义=消耗式教学（装卷轴即消耗，卸载不返还，需多张卷轴教多人）；策略页 UI=背包物品选择器（背包卷轴源列表，ColonyItemBank 只存元素不存物品）；preset 保留（跨类先后）+ customPriority 保留作覆盖（装备 UI 只写预设，未配置走预设推导，覆盖机制保留在 API）；旧存档 spellbookIds 丢弃（不迁移）。

❓ 剩余（阶段 C 相关）：**全部已确认（2026-08-21 用户拍板）**——
1. ✅ 旧 mana/stamina potion 配方归属 **crafting_station**（非 magic_station）；输出物品不注册。
2. ✅ 魔法合成 **仅元素**（无空卷轴原料）。
3. ✅ 合成产物去**殖民地仓库**。