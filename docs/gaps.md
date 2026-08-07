# gaps — 旧文档与代码差异 / 未接线点 / 已知问题

本页记录：**旧 `architecture/` 与真实代码不一致之处**、**已存在但未被代码消费的数据/配置**、**已知的 stub / TODO / 潜在不一致**。以真实代码为准。

## 一、architecture/README.md 已过时的说法

| # | 旧文档说法 | 真实代码 |
|---|---|---|
| 1 | `tourist/data/TouristAttributes(level/energy/satisfaction/preferences/appearance)` | `tourist/data/` 不存在；属性在 `TouristEntity` 字段（钱包/能量/满意度/偏好/法师属性）。`TouristState` 在 `tourist/internal/` |
| 2 | `equipment/` 包 | 不存在。装备是 cross-cutting：`core/component/EquipmentComponent` + `core/types/`（EquipmentSlot/EquipmentPreset/AttributeModifier），桥接在 `npc/internal/` |
| 3 | `ColonyLevelUpEvent` 是事件 | 是 **record** 回调（`ColonyLevelManager.levelUpCallback`），不在 NeoForge 总线 |
| 4 | building "零自定义方块/BE，全部SavedData" | 现有自定义方块/BE：`creative_building_scanner` 与 `building_scanner`（含 BE） |
| 5 | 新手引导数据源是 `assets/wandscape/guide/*.md` | 引导步骤**硬编码**在 `shared/ui/guidance/GuideRegistry`（9 步）；md 只服务 Markdown 文档阅读器（GuideTestScreen） |
| 6 | `ResourceRequestExecutor` "1 item/tick" | 实际 `STAGGER_TICKS=5`（每 5 tick 发一件） |
| 7 | 建筑扫描器改名 | 原 `building_scanner` 更名 `creative_building_scanner`；`building_scanner` 现为生存扫描器 |
| 8 | `road_templates/road_tiers/road_rules` 生效 | 当前无代码读取（见下文） |
| 9 | `BuildingDebugController` "G key" 开关 | G 键现为 overview 切换；BuildingDebug 激活由 V 面板驱动 |

## 二、已存在但当前未被代码消费（dead data / 未接线）

- **`wonder_config`**：`BuildingConfig` 有该字段（`WonderConfig` → `WonderEffect` StatMod/PriceMod/RuleUnlock），但 `buildings/*.json` **无任何文件定义它**。`WonderEffectApplier` 的查询接口在，但当前没有 wonder 类建筑数据。
- **`road_templates/`、`road_tiers.json`、`road_rules/`**：三个数据文件无代码读取。`RoadTemplate` 由代码构建；`RoadPresetLoader` 注册 `road_presets` 类别，但**没有 road_presets JSON 文件**（预设全部硬编码 DEFAULT_PRESETS）。
- **道路装饰**：`RoadConfig.getDecorationConfig` 无调用方——Config 里有 `road.decoration.*`（路灯/长椅）设置，但 `RoadBuilder` **未接入**装饰生成。
- **`potion_station`**：`BuildingInteractHandler` 对 potion_station 只提示"not yet implemented"；`PotionStationPacket.handleClient` 空实现，**无 GUI**。配方 JSON 有 2 个药水配方（mana/stamina）但无法在游戏中生产。
- **`HouseApi` / `NpcApi.assignHouse`**：恒返回 false（住宅分配 Stage 4 未实现）。
- **`TavernApi.getCandidates/refreshCandidates/recruitCandidate`**：占位（返回空/false）；实际招募走 `TavernRecruitPacket.handleRecruitMage` + `receiveMageResume/recruitMage`。
- **`WorkbenchSource`**：V1 stub（POLL_INTERVAL=30，poll 空）。
- **`ElementAuditor`/`ElementAuditRunner`**：需系统属性 `wandscape.runAudit=true` 才跑（GameTest 用途）。

## 三、已知 stub / TODO

- **`WandscapeEntityOps`**：stub（applyEffect/getPosition 空实现）。
- **`WandscapeRitualOps`**：仅实现 `self_teleport`（teleportTo + PORTAL 粒子），其余 RitualId no-op；channelTicks 硬编码且 self_teleport/item_teleport/player_summon=1（代码注释标 TODO 600）。
- **`StatsService`**：订阅 NarrativeEventTriggered，onEvent 为空实现（TODO）。
- **`RitualOp.channelTicks()`**（op/）：self_teleport/item_teleport/player_summon=600、warding=200、group_vigor=400、rain_call/clear_weather=1200、portal_gate=1800——与 `WandscapeRitualOps` 的 1 不一致，需对齐。
- **`ColonyMetricsService`/`StatsSyncPacket`**：统计面板已同步，但 `WandscapePanelState.StatsSummary` 消费端展示待确认。

## 四、潜在不一致 / 需要注意

1. **两类元素存储分叉**：
   - **decompose（工作站分解）**产物写 `colonyResources.addResource(元素名 ResourceId)`（`core/types/ResourceId` 的无冒号元素名，属于 `ColonyResourceAccess` 通道）。
   - **synthesize / craft_wand / brew_potion** 元素消耗走 `ColonyItemBank`（`ElementType`）。
   - 两者数据结构不同（ResourceId vs ElementType），`ColonyItemBank.consume` 对元素"reserve 仅检查"（无保留语义）。重构时应统一。
2. **`ColonyItemBank` 跨殖民地共享**：每世界存档一个，非每殖民地一个。`seededColonies` 按 colony 记录是否已种籽（首个建筑给每元素 2000）。
3. **`ResourceShortageHandler` 兜底链路**：`taskPool.setResourceShortageHandler → ResourceSupplySystem.enqueueSynthesize`（缺元素时合成），但合成又需要元素成本——可能死循环，靠 in-flight 去重缓解。
4. **`SplineBuildPacket` 端点吸附**：3 格内吸附节点，否则建 ORPHAN 节点；tier 硬编码 "dirt"。
5. **`nodedark.json` 的 `node_config` 对象有非法尾逗号**（`"channel_ticks": 1200,` 后跟 `}`）。Gson 内部 lenient 模式可解析，故能正常加载；但严格 JSON 校验会失败，建议清理。
6. **元素价值口径**：建造用 `build_cost`（EnqueueHelper 算料）、分解用 `getItemElementValue`（decompose_yield → build_cost 回退，×1/5，且 count×总价值<5 时拒绝）、商店售卖利润同用 `getItemElementValue`、合成用 `buildCost`——来源尽量统一，`GenerateElementMappingsCommand` 负责保证一致性。

## 五、版本相关（历史提交提示）

- `refactor: 删魔力系统`（1bd748d）：NPC 属性收敛为 6 属性 + 脱战回血 + 统一魔法冷却 + 装备仅加法。**旧文档若仍描述"魔力/mana_cost"概念，一律作废**（`mana_cost`/`baseManaCost`/`spawnNpc` 死代码已删）。
- `refactor: SPELL_POWER 改伤害核算入口统一倍率`（93cc7a3）：伤害在 `MagicBeamEntity`/`NpcSpellPowerHandler` 按 SPELL_POWER 统一放大；传送 CD 300。
