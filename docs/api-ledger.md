# Wandscape API 账本（api-ledger）

> 目的（用户三重用途）：① 逐条实现未实现的 API；② 检查模组本体相关用法能否走 API（**保证 API 鲜活性**——被本体调用才不腐烂）；③ 为未来 `docs/` 新版提供素材。
> 生成：2026-09-01。状态基于当前 `refactor` 分支 `api/` 包，`compileJava` 绿。
> 更新：2026-09-02。① NpcApi 的属性整存取/等级/训练/升级挪入 NpcAttributesApi（§2/§16 同步）；② NpcData 明确定位为 NpcApi 的读返回契约（§3），删除无实体的 `getAssignedHouseId`/`getGraveBlockEntityId`；③ WarehouseApi 声明两个仓库变更事件为公开契约（§8）；④ 新增 MageHutApi（§20）+ NpcApi 存活/复活（§2）。

## 图例

- **状态**：`✅ 已实现` = 实现类里有真身（抽象方法在 `XxxApiImpl` 实现）；`🔶 桩` = `@Unimplemented` 默认方法，调用即抛 `UnsupportedOperationException`；`⚠️ 隐式桩` = **不是** `@Unimplemented` 但实为空转/返回假值（易骗 addon，最危险，须要么实现要么改标 `@Unimplemented`）；`🔌 回调接口` = addon 实现、本体经 `instanceof` 派发（非普通 API）。
- **本体自消费（每 API 的 getter 调用数）**：`grep WandscapeApis.getXxxApi(Silently)` 在非 `api/` 包的计数。数值>0 = 本体已在走此 API；这个数越高 API 越不可能腐烂。
- **dogfood 列**：`走API?` —— `✅`=本体已走此 API；`🔧 本体绕开(内部有等价)`=本体在做同一件事但**绕过 API 直调内部类**（该内部类行号给出）→ 该迁到 API；`🔧 纯新能力`=本体无此操作（纯 addon 用的新方法）；`-`=不适用。

---

## 总览

- 接口数：**19 个**（18 个功能/扩展接口 + 1 个窄 accessor `WandscapeApis`）＋ 标注 `@Unimplemented`。
- **已实现 vs 桩**：功能接口里大部分"读/查询/平衡值"已实现；**本轮新增的能力动词全是桩**。
- **⚠️ 隐式桩（重点慎用）**：`NpcApi.assignHouse`（恒 false）、`TouristApi.spawnTourist`（空 nil）、`TavernApi.recruitMage`（只取简历不生实体）——三者**编译通过但行为是空/假**，addon 一用就踩坑，建议优先处理。
- **本体自消费合计**：约 **139 处** `WandscapeApis.getXxxApi()*`。BuildingApi(35)/ColonyApi(39) 最重，ProductionApi(0) 最轻。
- **API 鲜活性结论**：读面已自消费充分；**写面（能力动词）几乎全部绕开 API**（见各表 dogfood 列）——这正是 `ScepterApi` 只读的根源，是下一步 dogfood 改造的核心。

---

## 1. MagicApi（施法/法术域，原名 SpellcastingApi）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `List<String> getKnownSpells(UUID npcId)` | NPC 已装备魔法 id | ✅ | `NpcDataPacket` 已走 |
| `String getStrategyPreset(UUID npcId)` | 施法策略预设名 | ✅ | `NpcDataPacket` 已走 |
| `List<String> getPriority(UUID npcId)` | 已解析的施法优先级 | ✅ | `NpcDataPacket` 已走 |
| `void setEquippedAndStrategy(UUID, preset, List)` | 全量重设装备+策略 | ✅ | `ReviveHandler` 已走 |
| `int get/setCastSingleTargetMaxEnemies` | 单体施法最大敌数（平衡） | ✅ | BalanceValues |
| `int get/setCastAoeMinEnemies` | AOE 最少敌数（平衡） | ✅ | BalanceValues |
| `boolean castNpcSpell(UUID, magicId, BlockPos target)` | 命令 NPC 施放某魔法 | 🔶 桩 | 🔧 本体绕开：`MagicCaster.castNpcAt`（`content/magic/internal/MagicCaster.java:87`） |
| `boolean castForPlayer(ServerPlayer, magicId)` | 给玩家临时施放魔法 | 🔶 桩 | 🔧 本体绕开：`MagicSpellExecutors.castForPlayer`（`MagicSpellExecutors.java:444`，仅 MagicCommand:155 用） |
| `void fillMana(UUID, float)` | 直接设 NPC 当前魔力 | 🔶 桩 | 🔧 本体绕开：`npc.magic.setMana`（`MagicCommand` 调试用） |
| `void clearCooldown(UUID)` | 清 NPC 施法冷却 | 🔶 桩 | 🔧 本体绕开：冷却组件（`MagicCommand` 调试用） |
| `MagicDef getMagicDef(String)` | 按 id 取法术定义 | 🔶 桩 | 🔧 本体绕开：`SpellbookLoader` |
| `List<String> getAllSpellIds()` | 全部注册法术 id | 🔶 桩 | 🔧 本体绕开：`SpellbookLoader` |

本体自消费：`getMagicApi` 2。**注**：桩的接入点都已列在 `@Unimplemented("...")`。

---

## 2. NpcApi（NPC 实体/战斗/属性/等级域）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `List<NpcData> getColonyNpcs(UUID)` | 殖民地 NPC 列表 | ✅ | 已走 |
| `List<NpcData> getIdleNpcs(UUID)` | 空闲 NPC | ✅ | 已走 |
| `int getNpcCount(UUID)` / `getIdleNpcCount(UUID)` | 便捷计数（default） | ✅ | 派生 |
| `NpcData getNpc(UUID)` | 单个 NPC 数据 | ✅ | 已走 |
| `getGuardRange/setGuardRange` …（18 个守卫战斗平衡对） | guard 战斗/风筝/自防距离值 | ✅ | BalanceValues 委托 |
| `getNpcRegenGraceTicks`…（6 个回血/魔力平衡对） | NPC 回血/魔力节奏 | ✅ | BalanceValues |
| `getReviveNearBuildingRange` / `getScepterHostileRange` / `getMageHutRestTicks`（3 对） | 复活/权杖/休息 | ✅ | BalanceValues |
| `UUID spawnNpc(UUID colonyId, BlockPos)` | 生成殖民地 NPC | 🔶 桩 | 🔧 本体绕开（3 处）：`Wandscape.WANDSCAPE_NPC.spawn` + `fixEcsAfterSpawn`（`TavernRecruitPacket.java:199`、`ColonyCommand.java:190`、`ReviveHandler.java:148`）——**分散复制，最该收敛到 API** |
| `boolean removeNpc(UUID)` | 解雇/移除 NPC | 🔶 桩 | 🔧 本体绕开：`WandscapeNpc.dismissFromColony`（`WandscapeNpc.java:993`，仅 `NpcDismissPacket`） |
| `boolean isNpcAlive(UUID)` | npcId 是否指向在世法师（实体存在+未移除+isAlive） | ✅ `NpcApiImpl`（overworld 实体查询） | 🔧 无现成直接入口（各系统直调实体判断） |
| `boolean reviveNpc(UUID)` / `reviveNpc(UUID, BlockPos)` | 复活法师：需死亡记录+当前不存活；pos 可空默认市政厅门口，免费 | ✅ `NpcApiImpl`（经 `ReviveHandler.spawnFromRecordAt` + `ColonyDeathRegistry.getByNpcId`） | 🔧 本体绕开：`ReviveHandler` 三条路径（祭坛/全灭保底/保卫复活）——API 是程序化强制入口，本体路径保留 |

本体自消费：`getNpcApi` 3。**2026-09-02：**属性整存取（`get/setNpcAttributes`）、等级自由设置（`setNpcLevel`）、训练/升级（`trainNpc`/`levelUpNpc`）已挪入 NpcAttributesApi（见 §16），本接口不再持有。

---

## 3. NpcData（NpcApi 的只读数据契约，`content/npc/data`——非独立 API）

> **定位（2026-09-02 裁定）**：NpcData 不是独立功能 API，是 `NpcApi` 读方法的返回类型（addon 经 `NpcApi.getNpc/getColonyNpcs/getIdleNpcs` 获得）——与 `BuildingState`/`ColonyStatusSnapshot`/`MageResume` 同为"读模型留域、经 api 接口暴露"的模式。**相关读功能已在 API 中，无需另加 API 方法**；NpcData 留在 `content/npc/data` 不搬 `api/`。缺口是映射实现而非接口设计。

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `getNpcId/getName/getMaxHealth/getCurrentHealth/getSpellPower/getWorkSpeed/getSpellSpeed/getArmorValue/isIdle/getCurrentTaskId/isDead` | 现有读取器 | ✅ `NpcDataImpl.from` 实现 | `NpcDataPacket` |
| `int getLevel()` | NPC 等级 | 🔶 桩 | 🔧 实现绕开：`WandscapeNpc.getLevel()`（`WandscapeNpc.java:860`）未映射 |
| `float getMana()` / `getMaxMana()` | 当前/最大魔力 | 🔶 桩 | 🔧 `npc.getCurrentMana/getMaxMana`（`WandscapeNpc.java:158/:163`）未映射 |
| `List<String> getSpells()` | 已装备魔法 | 🔶 桩 | 🔧 `npc.equippedMagic.flattenedEntries()`（`:985`）未映射 |
| `Map<AttributeType,Float> getAttributes()` | 全属性基础值快照 | 🔶 桩 | 🔧 `WandscapeNpc` base 映射未映射 —— ⚠️ **与 `NpcAttributesApi.getNpcAttributes` 是同一能力**，实现时二选一去重，避免重复定义 |

本体自消费：经 `NpcApi` 间接（`NpcDataPacket`）。**2026-09-02：**删除无实体的 `getAssignedHouseId`/`getGraveBlockEntityId`（恒 null；现无 House 只有法师小屋，坟墓 BE 更属子虚乌有），接口与 `NpcDataImpl` 同步清理。

---

## 4. ColonyApi（殖民地生命周期/命名/等级/激活域）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `createColony(BlockPos[, UUID founder])` | 建殖民地，回 UUID | ✅ | `ColonyApiImpl` |
| `getFounder(UUID)` / `getColonyByFounder(UUID)` | 创始人双向 | ✅ | — |
| `getColonyId(BlockPos)` | 位置→最近殖民地 | ✅ | `WandscapeApis.colonyAt` |
| `deleteColony(UUID)` | 删殖民地 | ✅ | `ColonyCommand` |
| `isColonyOrigin(BlockPos)` | 是否殖民地原点 | ✅ | — |
| `getAllColonyIds()` | 全部殖民地 UUID | ✅ | — |
| `get/setNamingStyle(UUID, NameStyle)` | 英文/中文/奇幻取名风格 | ✅ | `ColonySavedData` |
| `getColonyLevel(UUID)` / `getColonyExp(UUID)` | 本级/经验 | ✅ | `ColonyLevelManager` 委托 |
| `grantExperience(UUID, int)` | 加经验（正向） | ✅ | `ColonyLevelManager.addExperience` |
| `String getColonyName(UUID)` | 殖民地显示名 | 🔶 桩 | 🔧 本体绕开：`ColonyLevelManager.getColonyName`（`ColonyLevelManager.java:76`），且已被 `ColonyStatusService` 读到（`ColonyStatusSnapshot.colonyName`） |
| `void setColonyName(UUID, String)` | 设显示名 | 🔶 桩 | 🔧 本体绕开：`ColonyLevelManager.setColonyName`（`:87`，`ColonyCommand.java:163` 命名面板） |
| `int getMaxLevel()` | 等级上限 | 🔶 桩 | 🔧 本体绕开：`Config.COLONY_MAX_LEVEL` |
| `int getExpToNext(UUID)` | 升下级所需经验 | 🔶 桩 | 🔧 本体绕开：`ColonyLevelManager.expToNextLevel`（`:102`） |
| `boolean isActive(UUID)` | 殖民地是否激活（创始人在线+未冻结） | 🔶 桩 | 🔧 本体绕开：`ColonyActivation.isColonyActive`（`ColonyActivation.java:32`）——**本体 58 处在建工/游客/结算判活跃，正是该走 API 的热点** |
| `void setActive(UUID, boolean)` | 冻结/解冻（覆盖派生） | 🔶 桩 | 🔧 本体无（现无 per-colony 覆盖）——`setActive` 是纯新能力 |
| `boolean setColonyLevel(UUID, int)` | 自由设等级（**可降到 1**） | 🔶 桩 | 🔧 本体绕开：`ColonyLevelManager.setLevel`（`ColonyLevelManager.java:81`，调试用） |

本体自消费：`getColonyApi` 39（最重之一）。

---

## 5. BuildingApi（建筑域，基本全实现）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `getBuilding/getBuildingAt/getColonyBuildings` | 建筑查询 | ✅ | 已走（`ColonyStatusService` 等） |
| `getBuildingBounds(UUID)` | 世界包围盒（守卫防区） | ✅ | guard 用 |
| `demolishBuilding/isDemolishing/demolishBlockReason/cancelBuilding` | 拆除/取消 | ✅ | 命令/GUI |
| `getColonySnapshot`(record)/`getColonyComfort/Magic/Wonder` | 三评估值 | ✅ | `ColonyStatusService` |
| `enqueueWork(UUID, WorkItem)` | 发布建筑任务（**addon 实用**） | ✅ | 任务管线 |
| `getBuildingsByCategory(UUID, category)` | 按类过滤 | ✅ | — |
| `placeBuilding(BlockPos, typeId, rotation)` | 统一放置 | ✅ | 投影/命令 |
| `isFirstFreeClaimed/findBeds/sampleWalkableGround/getTouristInteractionTarget/getEntryPoint/getTouristInteractPoint` | 放置/床位/游客落点辅助 | ✅ | tourist/投影 |
| `get/setDecorationBonusCap`、`get/setConstructionPlaceTicksPerUnit` | 装饰上限/建造耗时（平衡） | ✅ | BalanceValues |

本体自消费：`getBuildingApi` 35（最重之一）。**无新桩**——这是当前最"活"的 API。

---

## 6. ScepterApi（权杖庇护/强制仇恨域）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `boolean isSheltered(UUID colonyId, UUID entityUuid, Level)` | 是否被殖民地庇护 | ✅ | `WandscapeNpc.isFriendlyForce`（:261）已走 |
| `boolean isShelteredForAny(UUID, Level)` | 是否被任意殖民地庇护 | ✅ | `GuardTaskSource`（:90）已走 |
| `LivingEntity forcedHostile(ServerLevel, UUID)` | 殖民地强制仇恨目标 | ✅ | guard 已走 |
| `void setSheltered(UUID, UUID, boolean)` | 设/撤庇护（**要补的写侧**） | 🔶 桩 | 🔧 **本体绕开**：`ScepterMarks.toggleShelter`（`ScepterMarks.java:60`，经 `ScepterService` 右键触发）——**这就是只读 bug 的根源** |
| `void setForcedHostile(UUID, UUID)` | 设强制仇恨目标 | 🔶 桩 | 🔧 本体绕开：`ScepterMarks.toggleForcedHostile`（`:94`） |
| `UUID clearForcedHostile(UUID)` | 清强制仇恨目标 | 🔶 桩 | 🔧 本体绕开：`ScepterMarks.clearForcedHostile`（`:116`） |

本体自消费：`getScepterApiSilently` 4。**最高优先 dogfood 项**：把 `ScepterService` 的右键写路径改走 `ScepterApi.setSheltered/setForcedHostile`，addon 立刻获得完整写能力。

---

## 7. RoadApi（道路域，无建边）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `getNetwork/getEdges(UUID)` | 路网/边查询 | ✅ | 游客/运输 |
| `removeEdge(UUID, UUID)` | 拆路边+拆方块 | ✅ | 道路编辑器 |
| `cancelEdge(UUID, UUID)` | 撤销在建路（退料，幂等） | ✅ | 道路编辑器 |
| `boolean addEdge(UUID, RoadEdge)` | **程序化建路** | 🔶 桩 | 🔧 本体绕开：`RoadNetwork.addEdge`（`RoadNetwork.java:37`）+ `RoadPlacePacket`/`SplineBuildPacket` 服务端 handler（整段 200+ 行手工逻辑）——addon 建路唯一缺口 |

本体自消费：`getRoadApi` 5。

---

## 8. WarehouseApi（仓库/元素物品银行）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `getElement/getAllElements/consumeElement/addElement/addAllElements` | 元素读写（现含 boolean 返回） | ✅ | 经济循环 |
| `getItemCount/getItemSnapshot/extractItem/insertItems` | 物品读写 | ✅ | 经济循环 |
| `get/setTransportTicksPerBlockOnRoad/OffRoad` | 运输耗时（平衡） | ✅ | BalanceValues |
| `void clearAll(UUID)` | 清空仓库 | 🔶 桩 | 🔧 本体绕开：`ColonyItemBank.consume` 全量（`ConsumeWarehouseCommand.java:78-91`） |
| `boolean transferElements(fromUUID, toUUID, Map)` | **跨殖民地原子转账** | 🔶 桩 | 🔧 本体无（现只能两次调用拼接，非原子，B 未就绪会蒸发达部分） |

**公开事件（2026-09-02 契约声明，`WarehouseApi` javadoc 列出；域事件留域 `content/warehouse/event`，广播于 `NeoForge.EVENT_BUS`，附属直接订阅做增量同步）**：
- `WarehouseItemChangedEvent`（物品入库/出仓/消耗，带 `colonyId/itemKey/newCount/delta`）
- `WarehouseElementChangedEvent`（元素充入/消耗，带 `colonyId/elementType/newAmount/delta`）

本体自消费：`getWarehouseApi` 12。

---

## 9. ElementApi（元素映射/值数据层）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `fromId(String)` | id→ElementType | ✅ | — |
| `hasElementMapping(String)` / `isDisabled(String)` | 是否有映射/被禁用 | ✅ | [注意 disabled 时前者 false，需配合后者区分] |
| `getBuildCost(BlockState/ItemStack)` | 方块/物品元素成本 | ✅ | 建筑成本 |
| `elementItemId(ElementType)` | 元素 token 物品 id | ✅ | JEI/游客气泡 |
| `registerMapping(String, Map)` / `unregisterMapping(String)` | 程序化覆盖映射 | ✅ | 运行时覆盖层 |
| `Map<String,Map<ElementType,Long>> getAllMappings()` | 枚举全部映射 | 🔶 桩 | 🔧 本体绕开：`ElementMappingLoader.getAllConfigs`（`ElementMappingLoader.java:162`，`AuditElementsCommand` 用） |
| `boolean hasElementMapping(BlockState)` | 用方块状态查询 | 🔶 桩 | 🔧 本体绕开：转 id 字符串再查 |
| `void adjustCost(String, ElementType, long)` | 单元素成本增量调整 | 🔶 桩 | 🔧 本体绕开：`registerMapping` 整表覆盖（无法增量） |

本体自消费：`getElementApi` 11。

---

## 10. ProductionApi（配方/生产域，现仅平衡值）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `get/setWorkstationCraftTicksPerUnit`、`get/setCraftingStationCraftTicksPerUnit` | 工作站/合成台耗时（平衡） | ✅ | BalanceValues |
| `List<String> getUnlockedRecipes(UUID)` | 殖民地已解锁配方 | 🔶 桩 | 🔧 本体绕开：`RecipeUnlockChecker.isUnlocked`（internal）+ `ColonyLevelManager` |
| `Map<ElementType,Long> getRecipeCost(String)` | 配方元素成本 | 🔶 桩 | 🔧 本体绕开：`CraftRecipeView.resolve`（`CraftRecipeView.java:30`） |
| `boolean enqueueSynthesize(UUID buildingId, recipeId, int)` | 程序化发起一次合成 | 🔶 桩 | 🔧 本体绕开：`ResourceSupplySystem.enqueueSynthesize`（`:179`） |

本体自消费：`getProductionApi` **0**（当前唯一零接入的功能 API——新增时一并考虑接入）。

---

## 11. TouristApi（游客域）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `getTouristCount(UUID)` | 游客数（读 shadow 注册表） | ✅ | 已走 |
| `List<UUID> getTouristsInColony(UUID)` | 游客 UUID（读内存 map） | ✅ | [⚠️ 重启后与 count 不一致，见下] |
| `void spawnTourist(UUID, BlockPos)` | 生成游客 | ⚠️ **隐式桩（空 nil）** | 🔧 本体绕开：`TouristSpawnSystem.forceSpawn`（`TouristSpawnSystem.java:115`）——真正生成在每日计划 |
| `int getOvernightStayerCount(UUID)` | 过夜游客数 | ✅ | 已走 |
| `void despawnAll(UUID)` | 清空全镇游客 | 🔶 桩 | 🔧 本体绕开：遍历实体 `onTouristDepart` 流程（`TouristSpawnSystem.java:635`） |

本体自消费：`getTouristApi` 6。**⚠️ spawnTourist 隐式桩**；`getTouristsInColony` 与 `getTouristCount` 数据源不一致（内存 map vs 持久化 shadow），重启后读取会错。

---

## 12. TavernApi（酒馆招募域）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `getMageResumes(UUID)` | 简历列表（新→旧） | ✅ | 酒馆 GUI |
| `MageResume recruitMage(UUID tavernId, UUID, index)` | 取走一份简历 | ⚠️ **只取简历，不生成实体** | 🔧 本体绕开：真 `spawn` 在 `TavernRecruitPacket.handleRecruitMage:199`（GUI 包路径），API 不产法师 |
| `rejectMage(UUID, index)` | 拒绝简历 | ✅ | 酒馆 GUI |
| `getRecruitCount(UUID)` | 招募次数 | ✅ | — |
| `canAffordRecruit(UUID)` / `chargeRecruit(UUID)` | 能否/扣费招募 | ✅ | — |
| `void addResume(UUID colonyId, MageResume)` | 向简历池注入 | 🔶 桩 | 🔧 本体绕开：`TavernRecruitStorage.addResume`（`TavernCommand.java:78-82` 调试命令） |

本体自消费：`getTavernApi` 7。**⚠️ recruitMage 不产实体**。

---

## 13. GuideProgressApi（教程/指南书推进域）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `sendToPlayer(ServerPlayer, UUID)` | 重算并推送教程进度 | ✅ | 各请求点 |
| `void setProgress(ServerPlayer, int)` | 强制跳步 | 🔶 桩 | 🔧 本体绕开：`GuideProgressSavedData.set`（`GuideProgressSavedData.java:49`） |
| `void clearProgress(ServerPlayer)` | 清教程进度 | 🔶 桩 | 🔧 本体绕开：同上 |
| `void openGuide(ServerPlayer, String docPath)` | 服务端开指南书到页 | 🔶 桩 | 🔧 本体绕开：`GuideDocOpenPacket`（`GuideCommand` 用） |

本体自消费：`getGuideProgressApiSilently` 8。

---

## 14. ColonyStatusApi（只读殖民地状态快照域，Q2 裁定保留独立）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `ColonyStatusSnapshot getSnapshot(UUID)` | 聚合快照（舒适/魔奇/元素/人口/建造中） | ✅ | `ColonyStatusService` |
| `ColonyStatusSnapshot getSnapshotSafe(UUID)` | 安全版（异常回 EMPTY） | ✅ | 面板包 |

本体自消费：`getColonyStatusApiSilently` 4（`ProjectionEnterPacket`/`ColonyNameUpdatePacket`/`PanelStateTracker` 等）。

---

## 15. WandApi（法杖预设域，Q5 保留单列）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `String getWandColor(ItemStack)` | 法杖颜色 | ✅ | `WandItem` tooltip |
| `String getWandPresetId(ItemStack)` | 绑定 preset id | ✅ | `WandscapeNpc.syncWandAttributes` |
| `List<NpcAttributeModifier> getWandModifiers(String)` | preset→属性修正 | ✅ | 同上 |

本体自消费：`getWandApi` 2。**只读，无 setter**（addon 给队友造属性杖只能手工写 `CUSTOM_DATA`；且属性只对 NPC 生效，玩家手持无加成）。

---

## 16. NpcAttributesApi（NPC 属性规则覆盖 + 实例级属性/等级/训练域，零 MC）

> **2026-09-02：**从 NpcApi 挪入实例级操作（规则段本是本接口，挪入前属性整存取/等级/训练/升级在 NpcApi）。现在分两段：上段规则（mod 初始化时覆盖），下段实例级操作（运行时，均为桩）。

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `isVisible/visible` | 可见性/可见列表 | ✅ | 面板 |
| `lower/upper/perLevel/trainStep/defaultFor/effective` | 规则读取 | ✅ | 招募/训练 |
| `overrideSpec/resetSpec/setDefault/resetDefault/overrideCosts/resetCosts` | **mod 初始化时覆盖规则** | ✅ | 整合包用 |
| `Map<AttributeType,Float> getNpcAttributes(UUID)` | 整取基础属性（全量） | 🔶 桩 | 🔧 本体绕开：`WandscapeNpc.getEffectiveAttribute`（读生效值）/base 映射 —— ⚠️ 与 `NpcData.getAttributes` 同能力，二选一 |
| `boolean setNpcAttributes(UUID, Map)` | 整设基础属性 | 🔶 桩 | 🔧 本体绕开：`WandscapeNpc.setBaseAttributeValue` 逐个写（`TavernRecruitPacket.java:213` 手动拼） |
| `void setNpcLevel(UUID, int)` | 自由设等级（可降级） | 🔶 桩 | 🔧 本体绕开：`WandscapeNpc.setLevel`（`TavernRecruitPacket.java:221`） |
| `boolean trainNpc(UUID, AttributeType, steps)` | 训练属性（耗元素） | 🔶 桩 | 🔧 本体绕开：`MageHutServerHandler.onTrain`（`MageHutServerHandler.java:174`） |
| `boolean levelUpNpc(UUID)` | 升级 | 🔶 桩 | 🔧 本体绕开：`MageHutServerHandler.onUpgrade`（`:146`） |

本体自消费：`getNpcAttributesApi` 1。**覆盖层模式（默认值+覆盖），改规则只碰这一个文件**。

---

## 17. NpcInteractHook / NpcSneakInteractHook（右键回调 SPI）

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `onInteractNpc(ServerPlayer, Mob, InteractionHand)` | 非潜行右键法师 | 🔌 回调接口（addon 实现） | 本体经 `WandscapeNpc.mobInteract:1402` instanceof 派发 |
| `onShiftClickNpc(ServerPlayer, Mob, InteractionHand)` | 潜行右键法师 | 🔌 回调接口 | 同上 `:1396` |

**优点**：addon 物品实现这两个接口即自动获得右键殖民法师行为，无需注册表。**边界**：只对 `WandscapeNpc`；非殖民地生物走 `ScepterInteractHandler`（只认 scepter 物品）。

---

## 18. WandscapeApis（窄 accessor，静态定位器）

- 每个 API 一对 `getXxxApi()`（未加载抛 `IllegalStateException`）+ `getXxxApiSilently()`（null 安全）+ `setXxxApi()`（装配用）。
- 另有便捷 `UUID colonyAt(BlockPos pos)`：位置→所在殖民地 id（调用 `colonyApi.getColonyId`）。
- **定位器性质**：只暴露 18 个 API getter（**窄**），与已解散的 `WandscapeEngine`（暴露全部内部服务）不同。**本体自消费走它安全**；但不要把纯内部管道（ECS tick/SavedData 读写/队列内部）塞进来。

---

## 19. CurioApi（新增 2026-09-01：法师 Curios 饰品槽位/装备契约）

> 面向整合包/附属："给法师加/减饰品槽、查已佩戴饰品、装备/卸下"。**零 Curios import**（仅 vanilla 类型），避免未装 Curios 的 addon 加载即崩。实现方在 `compat/curios`（仅 Curios 已加载时装配）；未安装时 `getCurioApi()` 抛 / `getCurioApiSilently()` 返 null。接入点已存在于 `CuriosCommand`（`/wandscape curios list/set/add/remove`）：槽位读 `ICuriosItemHandler.getCurios()`、增删 `growSlotType/shrinkSlotType`（注意 `@SuppressWarnings("removal")`——Curios 1.22 将移除，1.21 可用）。

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `Map<String,List<ItemStack>> getCurioContents(UUID)` | 槽类型→各槽物品 | 🔶 桩 | 🔧 本体绕开：`handler.getCurios()`+`IDynamicStackHandler`（CuriosCommand/menu） |
| `Map<String,Integer> getSlotCounts(UUID)` / `int getSlotCount(UUID,String)` | 槽类型→槽数 | 🔶 桩 | 🔧 本体绕开：`getStacksHandler(...).getSlots()`（CuriosCommand list） |
| `isEquipped(UUID, Item/Predicate)` | 是否佩戴某物 | 🔶 桩 | 🔧 本体已有 `CuriosCompat.isEquipped(LivingEntity,...)`，加 UUID 重载 |
| `boolean addSlots/removeSlots/setSlots(UUID,String,int)` | 增/减/设槽（实例级持久化） | 🔶 桩 | 🔧 本体绕开：`CuriosCommand` `grow/shrinkSlotType` |
| `boolean equipCurio(UUID, ItemStack, String)` / `@Nullable ItemStack unequipCurio(UUID,String,int)` | 装备/卸下（经 Curios 校验） | 🔶 桩 | 🔧 本体绕开：`IDynamicStackHandler.setStackInSlot/extractItem`（menu） |

本体自消费：`getCurioApi` 未接（实现层未落地，接入点在 CuriosCompatImpl）。**命名注**：取单数 `CurioApi` 避开 Curios 自身 `top.theillusivec4.curios.api.CuriosApi` 撞名。

---

## 20. MageHutApi（新增 2026-09-02：法师小屋入住绑定契约）

> 面向 addon："查法师绑了哪间小屋 / 强制绑定 / 强制解绑"。**存档权威**（`BuildingSavedData.mageHutResidents`，buildingId→`MageHutResident`）：法师战死待复活时记录保留，反查同样命中，与复活 `rebindToMageHut` 同一逻辑。实现 `content/building/internal/MageHutApiImpl`（绑定域留 building）。

| 方法 | 用途 | 状态 | dogfood |
|---|---|---|---|
| `UUID getBindingHut(UUID)` | NPC→小屋反查（含待复活死者）；未绑定 null | ✅ `MageHutApiImpl` | 🔧 本体绕开：`ReviveHandler.rebindToMageHut` 全表扫（`:221`）+ 各处 `npc.getHomeHutId()` 直读 |
| `boolean forceBind(UUID buildingId, UUID npcId)` | 强绑：顶替+自动解旧+保留 colony 校验+活体 | ✅ | 🔧 本体绕开：`MageHutServerHandler.onAssign`（含全部校验，GUI 路径）——API 为其程序化版，本体保留 GUI 软绑 |
| `boolean forceUnbind(UUID buildingId)` | 解绑（hut 原语） | ✅ | 🔧 本体绕开：`NpcDismissPacket:74-76`（离职清理） |
| `boolean forceUnbindNpc(UUID)` | 解绑（npc 便捷，先反查再解） | ✅ | 🔧 同上 |

本体自消费：`getMageHutApi` 未接（新建纯 addon 能力；本体 GUI/离职路径保留原逻辑）。

---

## 二、Dogfood 改造清单（保证 API 新鲜度，按价值排序）

> 原则：让 mod 的操作本体**也走 API**，写面才不会藏私；纯内部管道（ECS/SavedData/事件总线）**不过 API**。

| # | 改造点 | 现状（绕开 API 的地方） | 改为 | 价值 |
|---|---|---|---|---|
| 1 | **权杖写侧** | `ScepterService` 右键 → `ScepterMarks.toggleShelter/toggleForcedHostile` | `ScepterApi.setSheltered/setForcedHostile/clearForcedHostile` | 根治"只读" bug，addon 立即能写 |
| 2 | **NPC 生成** | 3 处复制 `Wandscape.WANDSCAPE_NPC.spawn` + `fixEcsAfterSpawn`（Tavern/Colony/Revive） | `NpcApi.spawnNpc` | 消灭最痛的复制代码，addon 程序化生法师 |
| 3 | **殖民地名/等级** | `ColonyLevelManager.setColonyName/setLevel`（命名面板/命令） | `ColonyApi.setColonyName/setColonyLevel` | addon 改名/降级 |
| 4 | **NPC 训练/升级** | `MageHutServerHandler.onTrain/onUpgrade` | `NpcAttributesApi.trainNpc/levelUpNpc` | addon 养成联动 |
| 5 | **殖民活跃判定** | `ColonyActivation.isColonyActive`（58 处分判） | `ColonyApi.isActive` | 统一判定入口 |
| 6 | **道路建边** | `RoadNetwork.addEdge` + 网络包 handler | `RoadApi.addEdge` | addon 程序化接路 |
| 7 | **游客生成/清空** | `TouristSpawnSystem.forceSpawn` / `onTouristDepart` 遍历 | `TouristApi.spawnTourist`(真实现)/`despawnAll` | addon 控制游客 |
| 8 | **仓库清空/转账** | `ColonyItemBank.consume` / 两次调用拼接 | `WarehouseApi.clearAll/transferElements` | 原子、addon 跨镇 |
| 9 | **配方发起合成** | `ResourceSupplySystem.enqueueSynthesize` | `ProductionApi.enqueueSynthesize` | addon 程序化生产 |
| 10 | **法术定义/施法** | `SpellbookLoader`/`MagicCaster.castNpcAt` | `MagicApi.getMagicDef/getAllSpellIds/castNpcSpell` | addon 施法 |

> **注意**：本改造必须配合把 `@Unimplemented` 桩替换为真实现（调 API 即为真实现），否则"走 API"会抛 UOE。

## 三、隐式桩处理（最危险，优先）

| 位置 | 现行为 | 建议 |
|---|---|---|
| `NpcApi.assignHouse` | 恒 false | 实现（`EntityComponentBridge` 写 house 组件）或改标 `@Unimplemented` |
| `TouristApi.spawnTourist` | 空 nil | 接真生成（`TouristSpawnSystem.forceSpawn`）或改标 `@Unimplemented` |
| `TavernApi.recruitMage` | 只取简历不生实体 | 让内部走 `NpcApi.spawnNpc`，API 真产 NPC |
| `NpcData.getAssignedHouseId/getGraveBlockEntityId` | 恒 null | ✅ 已删除（2026-09-02，无 House 只有法师小屋、无坟墓概念） |
| `getTouristsInColony` vs `getTouristCount` | 数据源不一致 | 统一读持久化 shadow |

## 四、实现优先级建议（先做底部能力强、风险低）

1. `ScepterApi` 写侧（对接 `ScepterMarks`，一对一转，风险最低）→ dogfood #1
2. `NpcApi.spawnNpc`（收敛 3 处复制，需调 `fixEcsAfterSpawn`）→ dogfood #2
3. `ColonyApi.setColonyName/getColonyName/setColonyLevel`（对接 `ColonyLevelManager`）→ dogfood #3
4. `NpcAttributesApi.getNpcAttributes/setNpcAttributes`（对接 `WandscapeNpc.setBaseAttributeValue` 逐个写；实现时与 `NpcData.getAttributes` 二选一去重）
5. 其余（生产/道路/游客/仓库）价值高但涉及跨域装配，靠后逐步做。

---

> 文中所有"绕开 API 的内部类行号"来自 2026-09-01 探索；改动前请以 `packages.md` + 实际代码为准复核。

---

# 五、部分消费审计与修正（2026-09-01）

> 目的（用户）：逐 API 核实"本体标'已走'的，是否真全走 API"。刚重构完，防"一半走 API 一半绕过直调内部"的隐形部分消费。判定口径：**能力动词走 API；纯内部管道（子系统自身实现/SaveLoad/ECS）不走 API 且合法**。

## 审计结论速览（18 接口全查）

### ✅ 确认全走 API（无绕过）
- **ScepterApi 3 读**：`isSheltered`/`isShelteredForAny`/`forcedHostile` 全部经 `WandscapeApis.getScepterApiSilently()`（`WandscapeNpc.isFriendlyForce`、`GuardTaskSource:91`、`GuardAttackExecutor:139`、`SelfDefenseExecutor:173`）。`ScepterMarks` 直读仅限 scepter 子系统自身。
- **NpcApi 查询**：`NpcDataImpl.from` 只在 `NpcApiImpl`；`EntityComponentBridge.allNpcs()` 用户全为各子系统内部活体逻辑。
- **TouristApi 读**、**TavernApi 读/招/拒**、**GuideProgressApi.sendToPlayer**、**RoadApi 增/减/撤**、**ElementApi 命名方法**、**BuildingApi 拆/撤/快照**、**ColonyApi getAllColonyIds/getColonyByFounder/setNamingStyle**。

### ⚠️ 真绕过 → 已修正
| 接口 | 绕过点（修正前） | 修正 |
|---|---|---|
| **MagicApi.setEquippedAndStrategy**（④ 校验旁路） | `NpcStrategyMenu:161`（GUI 直写 `equippedMagic`）、`NpcStrategyPacket:80`（直写 `castStrategy`）、`TavernRecruitPacket:128/208`（直清默认载荷）——全部跳过 API 服务端装桶/≤3/去重校验 | 三处改走 `setEquippedAndStrategy`（GUI 用 `flattenedQualified()` 保留槽行类别→幂等；预设包带当前装备重设；酒馆空列表=清空）。**编译绿** |
| **ColonyApi**（③ 单例接缝） | `ColonyCommand:143/159/254/288/318`、`ColonyCreateRequestPacket:54` 用 `ColonyApiImpl.get()` | 改 `WandscapeApis.getColonyApi()`。⚠️ `ColonyCreateRequestPacket:100-101` 的 `assignColonyIfPossible/onBuildingIntact` 是 impl-only（接口无），保留 `ColonyApiImpl.get()`。**编译绿** |
| **BuildingApi 读模型太薄**（②） | 接口只回只读 `BuildingData`，内部域（tourist/npc/task/chunk-load）被迫 `BuildingSavedData.get(level).getBuilding(id)` 拿活体 `BuildingState` | **接口改返活体 `BuildingState`（用户拍板"面向 addon 不加限制"）**：`getBuilding`/`getBuildingAt`→`BuildingState`、`getColonyBuildings`→`List<BuildingState>`；impl 签名同步；4 处 `List<BuildingData>` 消费方兼容修复。**编译绿** |

### 裁定不动（审计证明非缺陷）
- **FillBuildingCommand/StressTestCommand 不路由 placeBuilding**：两调试命令**故意走低层**（无视解锁/disabled/firstFree 堆建筑/灌任务）；且 `EnqueueHelper.registerIfAbsent` **本身就带重叠校验**（placeBuilding:892 同用它），核心校验未绕过。路由会改变调试用途。判定"API 不完整"不成立。
- **BuildingApiImpl 其余 impl-only 留 content/building，不搬 API**：getQueue/removeFromQueue/moveUp/moveDown/dequeueWorkEligible/setCurrentTask/clearCurrentTask/getBuildingsWithPendingWork/registerBuilding/unregisterBuilding 全是**建筑队列调度核心**（任务源轮询/队列 UI/跨域 ResourceSupplySystem），接口故意不暴露（Step 2e 裁）。跨域直调正常（CLAUDE.md 铁律）。
- **ColonyApiImpl impl-only**（onBuildingIntact/onBuildingDestroyed/assignColonyIfPossible/rebuildFromSavedData/setColonyLevelManager）= building↔colony 钩子+装配，留内部。

### 死码清理（BuildingApiImpl）
- 删 **`isBuildingOccupied`、`dequeueWork`**（全仓零调用，删后编译绿 = 真死码）。
- **`setLevel` 保留**：⚠️ grep 一度误判死码，`Wandscape.java:960/1067` 装配时调用（Tier 1"编译+grep 双关、编译为权威"活例）。
- `sharedQueueFor/rebindAnchor/unregisterState/currentTasks` 仍被 `dequeueWorkEligible` 等使用，非孤儿。

> 校正：本账本上文建表时的 dogfood 列对 `WarehouseApi`（经济循环 104 处直调 `ColonyItemBank`）与 `ColonyLevelManager`（游客/建筑/生产直读等级）的"已走"标注偏乐观——**它们仍是绕过热点**，但分两类：经济域存/取/扣元素走 `ColonyApi`/`WarehouseApi` 该收敛（未来 dogfood 项）；`ColonyLevelManager.getLevel` 的跨域直读是内部取值（自治法，暂留）。详见上文各节绕过点。
