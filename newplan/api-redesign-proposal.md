# API 归口重设计提案（待拍板）

> 背景：现状 API 是按"子系统/口语名"命名、读写割裂、一个域碎成多个接口。本提案把探索到的新功能与现有面一起重新归口。
> 你在 Q1 已拍板 **SpellcastingApi 改名 MagicApi，程序化施法/教魔法/控魔力/法术定义查询全收进 MagicApi**。
> 请对下方 **Q2–Q5** 选一个（写上你的数字/字母即可），其余我按推荐执行。

---

## 归口规则（一句话）
> API 按**功能域**一个接口归口；接口名用**域名词**（`Magic`/`Npc`/`Colony`…），不用子系统口语名（`Spellcasting`/`ColonyStatus`…）；同一概念的**读/写/列表/平衡值收敛到同一个接口**。

## 目标接口全景（现状 → 目标）

| 目标接口 | 域 | 对现状 | 补进的功能（探索到的） |
|---|---|---|---|
| **MagicApi** | 施法/法术 | ← `SpellcastingApi` 改名扩 | castNpcSpell、castForPlayer、fillMana、clearCooldown、getMagicDef(s) |
| **NpcApi** | NPC 实体+战斗 | ← 现有（补读写） | spawnNpc、removeNpc、trainNpc、levelUpNpc、getLevel/getMana/getSpells、(视Q4)setSheltered/setForcedHostile |
| `TavernApi` | 酒馆招募 | ← 保留 + 修复 | addResume；recruitMage 内部改走 spawnNpc（治"只取简历不生成"） |
| **ColonyApi** | 殖民地 | ← 现有 + 并入 ColonyStatusApi | getColonyName/setColonyName、getMaxLevel、expToNext、getStatus(snapshot)、isActive/setActive |
| `BuildingApi` | 建筑 | ← 现有（较全） | (可选) registerBuildingConfig |
| `RoadApi` | 道路 | ← 现有 + 加写 | addEdge / createSegment |
| `WarehouseApi` | 仓库 | ← 现有 | clearAll、transferElements（原子转账） |
| `ElementApi` | 元素 | ← 现有 | getAllMappings、hasElementMapping(BlockState)、adjustCost（增量） |
| `TouristApi` | 游客 | ← 现有 | (修)spawnTourist 真生成、despawnAll、getTourist/controlTourist |
| **ProductionApi** | 配方/生产 | ← 扩（不只平衡值） | getUnlockedRecipes、getRecipeCost、enqueueSynthesize |
| **GuideApi** | 教程/指南书 | ← `GuideProgressApi` 改名扩 | setProgress、clearProgress、openGuide(player,path) |
| `WandApi` | 法杖物品 | ← 保留 | (视Q5) |

> 新建 vs 并入：**几乎不用新建接口**——每域已有接口（有的空/薄），主动作是改名+扩写+收口。事件面（NPC 死亡/复活/招募/训练）不是接口，补成 **NeoForge 事件**让 addon `@SubscribeEvent` 即可。

## MagicApi 长这样（Q1 已定）
```java
public interface MagicApi {
    // 现状：装备载荷 + 策略
    List<String> getKnownSpells(UUID npcId);
    String getStrategyPreset(UUID npcId);
    List<String> getPriority(UUID npcId);
    void setEquippedAndStrategy(UUID npcId, String preset, List<String> equipped);
    // 新补：程序化施法 / 魔力 / 冷却 / 法术定义
    boolean castNpcSpell(UUID npcId, String magicId, BlockPos target);
    boolean castForPlayer(ServerPlayer player, String magicId);
    void fillMana(UUID npcId, float amount);
    void clearCooldown(UUID npcId);
    MagicDef getMagicDef(String magicId);
    // 平衡值（现有）
    int getCastSingleTargetMaxEnemies(); void setCastSingleTargetMaxEnemies(int v);
    int getCastAoeMinEnemies();          void setCastAoeMinEnemies(int v);
}
```

---

## 待拍板（写编号）

> ✅ **已裁定（2026-09-01）**：Q1=全收MagicApi；Q2=B；Q3=A；Q4=A；Q5=A。已落地见文末【落地状态】。

### Q2. ColonyStatusApi（只读快照）怎么处理？
- **A**：并入 `ColonyApi.getStatus(colonyId)`，一个 Colony 接口读写+状态全取。
- **B（已选 ✅）**：保留独立只读快照接口（报表性质），ColonyApi 只管生命周期/命名/等级/激活。

### Q3. 权杖"庇护/强制仇恨"标记怎么归口？
- **A（已选 ✅）**：保留 `ScepterApi` 名字并补齐写方法（setSheltered/removeSheltered/setForcedHostile/clearForcedHostile）。
- **B**：并入 `NpcApi`（战斗 targeting 属 NPC 域）。

### Q4. 是否给 NPC 增删/训练/升级 = 开在 `NpcApi`？
- **A（已选 ✅）**：`NpcApi` 加 spawnNpc/removeNpc/trainNpc/levelUpNpc + 把 `NpcData` 补上 getLevel/getMana/getSpells/getAttributes。
- **B**：只加查询（getLevel/getMana/getSpells），增删/训练留给 Tavern/内部（不做程序化生造 NPC）。

### Q5. WandApi（法杖预设）去向？
- **A（已选 ✅）**：保留 `WandApi` 单列（法杖是物品装备，语义清晰）。
- **B**：并入 `MagicApi`（法杖=魔法装备穿在 NPC 上），少一个接口。

---

## 落地状态（2026-09-01）
**API 层重设计已完成，`./gradlew compileJava` 绿。** 规则：接口按域归口、域名词命名、读写收敛；**未实现的方法一律 `@Unimplemented <默认桩>`**，桩体抛 `UnsupportedOperationException`（诚实、不静默）。实现层滞后待后续落地。

- 新增 `api/Unimplemented.java`（@Retention(RUNTIME) @Target(METHOD,TYPE)）。
- **`SpellcastingApi` → `MagicApi`**（Q1）：改名 + 扩入 castNpcSpell/castForPlayer/fillMana/clearCooldown/getMagicDef/getAllSpellIds（均桩）。原载荷/策略/平衡值方法保持已实现；`WandscapeApis.getSpellcastingApi→getMagicApi`、实现类 `SpellcastingApiImpl implements MagicApi`、`Wandscape`/`ReviveHandler`/`NpcDataPacket` 引用已同步。
- **`ScepterApi`**（Q3）：补 setSheltered/setForcedHostile/clearForcedHostile（桩），写侧待接 `ScepterMarks`。
- **`NpcApi`**（Q4）：补 spawnNpc/removeNpc/trainNpc/levelUpNpc（桩）+ **`getNpcAttributes`/`setNpcAttributes`**（属性整体 get/set，Map 全量）+ **`setNpcLevel`**（升级/降级统一入口，桩）；**`NpcData`** 补 getLevel/getMana/getMaxMana/getSpells/**`getAttributes`**(map)（桩，NpcDataImpl 未映射）。
- **`ColonyApi`**（Q2=B，未并入 Status）：补 getColonyName/setColonyName/getMaxLevel/getExpToNext/isActive/setActive/**`setColonyLevel`**（可降到 1，桩）。**ColonyStatusApi 保留独立**。
- 其余补桩（新增方法均为 `@Unimplemented` default）：`RoadApi.addEdge`、`WarehouseApi.clearAll/transferElements`、`ElementApi.getAllMappings/hasElementMapping(BlockState)/adjustCost`、`ProductionApi.getUnlockedRecipes/getRecipeCost/enqueueSynthesize`、`TouristApi.despawnAll`、`TavernApi.addResume`、`GuideProgressApi.setProgress/clearProgress/openGuide`。
- **不动**：`WandApi`（Q5）、`ColonyStatusApi`（Q2）、`BuildingApi`/`ElementApi` 既有实现。
- **待落地实现清单**：每个 `@Unimplemented` 桩对应的真实接入点，见【附录】各接口条目定位。

---

## 附录：各接口补写的具体新方法（定案后照此落地）

- **MagicApi**：见上方 sketch。
- **NpcApi**：`spawnNpc(colonyId, BlockPos)`、`removeNpc(npcId)`、`trainNpc(npcId, AttributeType, steps)`、`levelUpNpc(npcId)`、**`getNpcAttributes(npcId)`/`setNpcAttributes(npcId, Map<AttributeType,Float>)`**（属性整体 get/set，替代逐属性 18 个）、**`setNpcLevel(npcId, level)`**（升级/降级统一入口）；`NpcData` 增 `getLevel/getMana/getMaxMana/getSpells/getAttributes(map)`。
- **TavernApi**：`addResume(colonyId, MageResume)`；`recruitMage` 改为内部调 `NpcApi.spawnNpc`。
- **ColonyApi**：`getColonyName/setColonyName`、`getMaxLevel`、`getExpToNext`、`isActive/setActive`、**`setColonyLevel(colonyId, level)`**（可降到 1；升级/降级统一）。注：`getStatus` 不并入——Q2 裁定 ColonyStatusApi 保留独立。
- **ScepterApi**：`setSheltered(colonyId, entityUuid, bool)`、`setForcedHostile(colonyId, entityUuid)`、`clearForcedHostile(colonyId)`。
- **RoadApi**：`addEdge(colonyId, RoadEdge)`（或 `createSegment(colonyId, from, to, presetId)`）。
- **WarehouseApi**：`clearAll(colonyId)`、`transferElements(fromColonyId, toColonyId, Map<ElementType,Long>)`（原子）。
- **ElementApi**：`getAllMappings()`、`hasElementMapping(BlockState)`、`adjustCost(id, ElementType, delta)`。
- **TouristApi**：`despawnAll(colonyId)`、`getTourist(touristId)`、`controlTourist(touristId, fn)`（或精确的 setBars/setWallet）。
- **ProductionApi**：`getUnlockedRecipes(colonyId)`、`getRecipeCost(recipeId)`、`enqueueSynthesize(buildingId, recipeId, count)`。
- **GuideApi**：`setProgress(player, step)`、`clearProgress(player)`、`openGuide(player, path)`。
