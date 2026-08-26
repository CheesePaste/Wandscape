# gaps — 旧文档与代码差异 / 未接线点 / 已知问题

本页记录：**旧 `architecture/` 与真实代码不一致之处**、**已存在但未被代码消费的数据/配置**、**已知的 stub / TODO / 潜在不一致**。以真实代码为准。

## 一、architecture/README.md 已过时的说法

| # | 旧文档说法 | 真实代码 |
|---|---|---|
| 1 | `tourist/data/TouristAttributes(level/energy/satisfaction/preferences/appearance)` | `tourist/data/` 不存在；属性在 `TouristEntity` 字段（钱包/能量/满意度/偏好/法师属性）。`TouristState` 在 `tourist/internal/` |
| 2 | `equipment/` 包 | 不存在。装备是 cross-cutting：`core/component/EquipmentComponent` + `core/types/`（EquipmentSlot/EquipmentPreset/AttributeModifier），桥接在 `npc/internal/` |
| 3 | `ColonyLevelUpEvent` 是事件 | 是 **record** 回调（`ColonyLevelManager.levelUpCallback`），不在 NeoForge 总线 |
| 4 | building "零自定义方块/BE，全部SavedData" | 现有自定义方块/BE：`creative_building_scanner` 与 `building_scanner`（含 BE） |
| 5 | 新手引导数据源是 `assets/wandscape/guide/*.md` | 引导步骤**硬编码**在 `shared/ui/guidance/GuideRegistry`（10 步）；md 只服务 Markdown 文档阅读器（GuideTestScreen） |
| 6 | `ResourceRequestExecutor` "1 item/tick" | 实际 `STAGGER_TICKS=5`（每 5 tick 发一件） |
| 7 | 建筑扫描器改名 | 原 `building_scanner` 更名 `creative_building_scanner`；`building_scanner` 现为生存扫描器 |
| 9 | `BuildingDebugController` "G key" 开关 | G 键的 overview 切换已移除；BuildingDebug 激活由 V 面板驱动 |

## 二、已存在但当前未被代码消费（dead data / 未接线）

- **法杖 attributes[] 曾只解析不应用（2026-08-26 已修复）**：旧 `WandPresetLoader` 把 `attributes[]` 解析进 `WandPreset.attributes` 并写入物品 NBT，但 `EquipmentComponent` 的 WAND 槽永远只有 `equipDefaultWand()` 的 +0 修饰符——玩家装高阶法杖只变外观、属性从不生效。现 `WandscapeNpc.syncWandAttributes()` 按手部物品 preset_id 查预设写入 WAND 槽（`NpcMenu.clicked`/`onAddedToLevel`/`EntityComponentBridge` 三处同步），`WandItem` tooltip 显示加成，创造栏按预设补发全部变体。12 个法杖预设已落地（见 `docs/data/craft_recipes.md`）。旧 basic/adept/master 已删，`basic_wand` 保留为中性默认（无 JSON）。

- **建筑间道路自动生成（MST）已删除（2026-08）**：废弃功能整体移除——`RoadEventListener`/`RoadPlanner`(MST)/`RoadBuilder`/`RoadTaskSource`/`RoadConfig` 及其 `road_templates`/`road_tiers.json`/`road_rules` 数据全部删除。保留手动铺路（`SplineBuildPacket`/`RoadPlacePacket` 等，经 `PlayerManualSource`）与段完成记账 `RoadSegmentListener`。相关架构文档已同步。
- **`wonder_config`**：`BuildingConfig` 有该字段（`WonderConfig` → `WonderEffect` StatMod/PriceMod/RuleUnlock），但 `buildings/*.json` **无任何文件定义它**。`WonderEffectApplier` 的查询接口在，但当前没有 wonder 类建筑数据。
- **`potion_station` → `magic_station`（2026-08 P 阶段 C 改造）**：类别更名 `magic_station`（存档 category 于加载时按 BuildingConfig 迁移）并落地魔法合成 GUI（`MagicStationScreen` + `craft_spell` 产物流，卷轴入殖民地仓库）。**旧 2 个药水配方（mana/stamina）归属合成站**（`craft_station=crafting_station`，随法杖配方在合成站 GUI 列出、走 brew_potion 蓝图），但输出物品（`wandscape:mana_potion`/`stamina_potion`）**仍未注册**——产出入仓为数据条目、无图标，属已知残留。
- **`HouseApi` / `NpcApi.assignHouse`**：恒返回 false（住宅分配 Stage 4 未实现）。
- **施法决策已集中（P1-P3 落地）**：守卫/自防御经 `CastBrain` 选魔法，CD/蓝/射程/视觉数据驱动；条件决策（`SpellConditions`/`WorldSnapshot`）与玩家策略（`SpellbookComponent`/`CastStrategyComponent`/`NpcStrategyScreen`，经 `SpellcastingApi`）已落地，已知列表来自 NPC spellbook + 玩家策略；自动施法永不选 `altarOnly` 魔法（祭坛专属）。**战斗魔法已多面化**：默认法术书 `[beam, heal, meteor, petrification]` 已按 id 在 `MagicSpellExecutors` switch 分发（单体/AOE/治疗/防御）；`MagicOp` 效果分发仍延后（switch 够用，建单实现 sealed 层级是死代码）。完整方案见 [spell-casting.md](spell-casting.md)。
  > **B 阶段（2026-08）已更新**：`SpellbookComponent` 移除，改 `EquippedMagicComponent`（按分类 4 桶 × 每桶 ≤3，默认 beam+heal，`NBT`=`spellbookEquip`，旧 `spellbookIds` 不再读取）。策略页改装备制。见 [plan-magic-items.md](plan-magic-items.md)。
- **复活不恢复魔法装备（B 阶段暴露的既有缺口）**：`DeathRecord` 未存 `equippedMagic`/`castStrategy`，复活生成新 NPC 后经 `onAddedToLevel` 种默认 beam+heal+balanced——玩家自定义的装备载荷在战死复活后丢失。旧 SpellbookComponent 时代默认"全会 8 个"，复活后也等于全会所以无感；B 阶段装备稀缺化后此丢失变得明显。待修：`DeathRecord`/`ColonyDeathRegistry` 增存载荷与策略，`ReviveHandler.spawnFromRecordAt` 恢复。
- **祭坛施法（P5 已实现）**：`altar1` 建筑类别 + `AltarScreen`（V 面板右键祭坛）+ NPC 走到祭坛旁执行 `AltarCastOp`（扣接取任务 NPC 的蓝，`SchedulerSystem` 按 `mana_cost` 门槛分派）；每祭坛每魔法 CD 存 `AltarCastState`（SavedData，祭坛间不共享）；`altarOnly` 魔法（revive）禁止 NPC 直接施放——shift+右键复活已移除，复活 = 最近死去（`ColonyDeathRegistry.latest`）在祭坛中心最上方重生。见 [spell-casting.md](spell-casting.md) 第十章。
- **`TavernApi.getCandidates/refreshCandidates/recruitCandidate`**：占位（返回空/false）；实际招募走 `TavernRecruitPacket.handleRecruitMage` + `receiveMageResume/recruitMage`。
- **`WorkbenchSource`**：V1 stub（POLL_INTERVAL=30，poll 空）。
- **`ElementAuditor`/`ElementAuditRunner`**：需系统属性 `wandscape.runAudit=true` 才跑（GameTest 用途）。

## 三、已知 stub / TODO

- **`WandscapeEntityOps`**：stub（applyEffect/getPosition 空实现）。
- **`WandscapeRitualOps`**：仅实现 `self_teleport`（安全落点搜索 + teleportTo + PORTAL 粒子），其余 RitualId no-op；channelTicks 硬编码且 self_teleport/item_teleport/player_summon=1（代码注释标 TODO 600）。
- **`StatsService`**：订阅 NarrativeEventTriggered，onEvent 为空实现（TODO）。
- **`RitualOp.channelTicks()`**（op/）：self_teleport/item_teleport/player_summon=600、warding=200、group_vigor=400、rain_call/clear_weather=1200、portal_gate=1800——与 `WandscapeRitualOps` 的 1 不一致，需对齐。
- **`ColonyMetricsService`/`StatsSyncPacket`**：统计面板已同步，但 `WandscapePanelState.StatsSummary` 消费端展示待确认。

## 四、潜在不一致 / 需要注意

1. **两类元素存储分叉**：
   - **decompose（工作站分解）**产物写 `colonyResources.addResource(元素名 ResourceId)`（`core/types/ResourceId` 的无冒号元素名，属于 `ColonyResourceAccess` 通道）。
   - **synthesize / craft_wand / brew_potion** 元素消耗走 `ColonyItemBank`（`ElementType`）。
   - 两者数据结构不同（ResourceId vs ElementType），`ColonyItemBank.consume` 对元素"reserve 仅检查"（无保留语义）。重构时应统一。
2. **`ColonyItemBank` 跨殖民地共享**：每世界存档一个，非每殖民地一个。`seededColonies` 按 colony 记录是否已种籽（首个建筑给每元素 6000，配置 `colony.initialElementCount` 可调）。
3. **`ResourceShortageHandler` 兜底链路**：`taskPool.setResourceShortageHandler → ResourceSupplySystem.enqueueSynthesize`（缺元素时合成），但合成又需要元素成本——可能死循环，靠 in-flight 去重缓解。
4. **`SplineBuildPacket` 端点吸附**：3 格内吸附节点，否则建 ORPHAN 节点；tier 硬编码 "dirt"。
5. **`nodedark.json` 的 `node_config` 对象有非法尾逗号**（`"channel_ticks": 1200,` 后跟 `}`）。Gson 内部 lenient 模式可解析，故能正常加载；但严格 JSON 校验会失败，建议清理。
6. **元素价值口径**：建造用 `build_cost`（EnqueueHelper 算料）、分解用 `getItemElementValue`（= build_cost，×1/divisor 默认 1/5，且 count×总价值<divisor 时拒绝）、商店售卖利润同用 `getItemElementValue`、合成用 `buildCost`——来源尽量统一，`GenerateElementMappingsCommand` 负责保证一致性。
7. **合成 channel 续跑的两个残余边界（2026-08）**：channel 检查点已持久化（`GlobalTask.channelRemainingTicks`），但仍有：
   - **完成边界竞态**：channel 跑完产出已入仓、而任务 step 尚未 advance 的那一两个 tick 内若存档重载，任务以 step 0 + 无检查点恢复，会重跑一次 channel → 重复产出。窗口极小（`Wandscape.onServerTick` 先 `blockInteractExec.tickAll()` 产出、同 tick 内 `world.tick()` advance step），未修。
   - **攻速换算精度**：检查点按"有效 channel tick"（已除原 NPC WORK_SPEED）存，续跑的新 NPC 直接用该 tick 数（不再按新 NPC 攻速换算）。双方攻速不同时，续跑时长略有偏差（更快/更慢），不造成丢产或重复产出。

## 五、版本相关（历史提交提示）

- `refactor: 删魔力系统`（1bd748d）：NPC 属性收敛为 6 属性 + 脱战回血 + 统一魔法冷却 + 装备仅加法。**旧文档若仍描述"魔力/mana_cost"概念，一律作废**（`mana_cost`/`baseManaCost`/`spawnNpc` 死代码已删）。
- `feat: 魔力回归`（1.5.0）：魔力为第 7 属性（MAX_MANA，默认 200），每魔法独立 CD + 施法互斥锁（`MagicState`），光束 50 蓝/传送 30 蓝，回复 10t/1 点；统一 `spellCooldown` 已删，`canCastSpell/startSpellCooldown` 不再存在。
- `refactor: SPELL_POWER 改伤害核算入口统一倍率`（93cc7a3）：伤害在 `MagicBeamEntity`/`NpcSpellPowerHandler` 按 SPELL_POWER 统一放大；传送 CD 300。

## 六、指南文本与真实机制不一致（2026-08 调查发现）

调查游戏内指南书文体时发现以下文本与真实机制不符。本次仅修文体、未改事实（用户决定事实修正另开任务），此处记录待处理：

1. **「声望(reputation)」机制虚构**——指南把"声望"当真实机制讲，但代码无 reputation 概念：
   - `guide/zh_cn/townhall_guide.md` L3（"等级、经验、声望、小镇名字"）、L16（"声望：由游客的满意离场积累。声望越高，每天进城门的游客越稀有、越有钱"）
   - `guide/zh_cn/getting_started_guide.md` L60（第八步标题"升级与声望"）、L62（"游客越满意，积累的声望越高，以后进城的游客就越富有"）
   - `guide/zh_cn/test_guide.md` L12（测试页"游客声望"，已隔离不在导航）
   - 英文镜像 `guide/en/` 对应位置同病。

   真实机制：游客生成数 = 均匀整数区间 `[base(5)+(colonyLevel−1)×levelSpawnBonus(1), +spawnRangeWidth(3)]`（1 级 5~7、2 级 6~8），等级分布固定 40%(colonyLevel−1) / 40%(==) / 20%(+1)，与"声望"无关。修正方向：改为"殖民地等级影响游客生成数量与等级"。

   （调查同时核实：指南对「奇观(Wonder)」仅作为需求条/商品三值提及，属真实机制，未虚构"奇观建筑"；指南未提及「药水酿造站」。此两者非指南错误。）

## 七、指南文体重写时发现的待核实事实点（2026-08）

2026-08 全站指南文风重写（统一为平实中性教程风）过程中，按「只改文体不改事实」原则保留了以下原意表述，但发现其与真实机制可能不符，记录待核实：

1. **市政厅与法师招募的关系**——`guide/zh_cn/townhall_guide.md`（及 en 镜像）称市政厅是「吸引法师 NPC 加入的关键」。但 `getting_started_guide.md` 与 `tavern_guide.md` 指明法师通过酒馆招募。市政厅是否真为招募前置待核实；若否，应删除该表述。
2. **旅馆英文建筑名 Inn / Hotel**——`guide/en/hotel_guide.md` 原文表格用 `Inn`、H1 用 `Hotel`，重写时统一为 `Hotel`（与术语表「旅馆 Hotel」一致）。若游戏内建筑注册的英文显示名实际为 `Inn`，此处需回退；建议核对建筑 lang 键。

## 八、仓库 GUI 原版化后的已知限制（2026-08-22）

1. **光标大数量显示 "999+"**：左键点击仓库格会把整叠（可 >64）取进光标（AE2/RS 语义），但 vanilla 浮动物品数量渲染上限 999（`renderItemDecorations`），>999 显示 "999+"。功能正常，仅显示精简；批量取出请用 Shift 快速移动到背包。
2. **仓库格点击限制**：Exchange 页仓库槽不可拖拽均分（QUICK_CRAFT 被 `mayPlace=false` 拒绝，对齐 AE2 终端行为）；双击仓库格无合并语义（每条目一槽）。
3. **光标持有 >64 时点击玩家槽**：`safeInsert` 每次最多放 64（vanilla 槽位上限），剩余留在光标，需多次点击——vanilla 标准行为。
4. **Overview 页物品列表数据源**：来自 `WarehouseDataPacket` 全量快照（打开时 + 每次动作后刷新），NPC 系统实时入库时列表不实时（槽网格同数据源，但菜单打开期间服务端只读槽不参与 vanilla 同步）；刷新包机制与改造前一致。

## 九、拆除返还的潜在丢物品与已修复的双计（2026-08-25）

1. **【已修复】撤回未完成建筑的双计刷物品**——`cancelBuilding` 原为「蓝图全量退款 + 拆除 salvage 返已放实物」，净赚已放部分。已改为只退未放置部分（详见 `docs/decisions.md` 2026-08-25 条目）。
2. **【已修复】salvage 入库依赖物流动画完成**——`AsyncTransformExecutor.performSalvage` 原把 `bank.add` 写在 `transporter.send(...).thenRun(...)` 里，入库在飞行结束后才发生，动画若不完成则「飞了但没入账」。已改为破坏/拆除路径**直接 `bank.add` 入库**（去掉逐块飞行动画，兼防批量 demolish 时客户端上百 flying entity + 粒子洪泛）；飞行动画仅存于采集/合成入库、仓库→NPC 取料等单发路径。
