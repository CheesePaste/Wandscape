# Config + API 重构方案（面向社区的灵活性）

> 定位：把「玩家向 Config」与「addon/整合包作者向 API」这两套外部面重新设计，一次做完。
> 沿 CLAUDE.md 增量的归属原则 + plan.md 决策 #7（纯内部不包装 API）。
> 执行方式：分阶段独立 commit，每阶段 `./gradlew compileJava` 全绿。

## 一、动机与基调

现状问题（已核实）：
1. `Config.java` 是唯一 COMMON 配置，**~60 个键全塞在一个扁平文件**，其中 4 个死键、4 个客户端渲染键混进服务端配置、大量深度引擎/手感数值面向玩家。
2. `api/` 面有真扩展点（NpcInteractHook、NpcAttributesApi、注册接口），但操作类有静默失败（`addElement` void no-op）、**缺"给社区灵活性"的程序化操作**（注册元素映射、程序化升级殖民地、程序化调深度数值）。

**调性（用户拍板）**：
- **少 JSON**：不加新 JSON 驱动内容；现有 element_mappings/buildings/craft_recipes/magic_spells 保留。
- **Config = 精简，面向普通玩家**：只留玩家会懂、会想调的少数标量。
- **API = 丰富，面向附属/整合包作者**：确实有使用需求 + 好命名才加；目标是"给社区很大灵活性，发展生态"。

**归属三原则**：
| 层 | 放什么 | 面向 | 备注 |
|---|---|---|---|
| Config | 普通玩家可懂的全局标量 | 玩家 | **精简** |
| API | 程序化查询/操作/注册/覆盖 | addon + 整合包作者 | **丰富、好命名** |
| JSON | 现有结构化内容（元素/建筑/配方/魔法） | 整合包作者加内容 | **不再新增**。⚠️ `buildings/deprecated/`（14 文件）是向下兼容载荷**禁删**（CLAUDE.md 已记，见下） |
| Java | `blueprints/`（13 JSON）已另行收敛为 Java lambda 蓝图 | 内部（非 JSON 面） | 同属"少 JSON"，不在本方案范围但**本方案不再把 blueprints 当 JSON** |

---

## 二、① WarehouseApi 操作加固 + 批量

**问题**：`WarehouseManager.addElement`（`content/warehouse/WarehouseManager.java:71-73`）是 `void`，殖民地基座不存在时 `if (bank != null)` 静默 no-op——addon 加了元素却不知道失败。`insertItems`（`:131`）同理。

**改动**：
- `api/WarehouseApi.java`：
  - `boolean addElement(UUID colonyId, ElementType type, long amount)` — 返回是否成功（镇不存在 → false）。
  - 新增 `boolean addAllElements(UUID colonyId, Map<ElementType, Long> amounts)` — 批量，镇不存在 → false。
  - （可选）`boolean insertItems(UUID colonyId, List<ItemStack> stacks)` 也返回成功。
- `content/warehouse/WarehouseManager.java`：实现返回 `bank != null && bank.addElement(...)`；`consumeElement` 已是 boolean 保持一致。
- 文档注释写明契约：colony 必须存在，返回失败表示镇/仓库未就绪。

**⚠️ "镇不存在"的判定口径（待拍板）**：`ColonyItemBank.addElement`（:210）用 `computeIfAbsent` 对**任意** colonyId 自动建条目，`getColonyIds()`（:151）= storage.keySet ∪ elementStorage.keySet——**无法区分"注册的镇"与"临时加元素产生的 id"**。故"返回 false = 镇不存在"**不能靠 bank** 判定，否则任何 colonyId 都能"成功"。改法二选一：
- 改从 **colony 注册表**判定（`ColonyApi.getColonyId(origin)` / `ColonySavedData.getAllColonies()`）——语义准确，但要把 colony 注册表挂钩进 WarehouseManager（跨域依赖）；
- **或**简化语义为"返回 false = 仓库未就绪"（保留原有 `bank != null` 口径），不承诺"镇不存在"。

推荐前者（语义更符合"程序化给镇加元素"的 addon 视角），但需接受跨域依赖。

**验证**：`./gradlew compileJava` 绿；生产消费方（`addElement` 旧调用方）返回值用法不引发行为变化（返回值可忽略）。

---

## 三、② ElementApi.registerMapping（程序化注册元素映射，减 JSON）

**目的**：附属作者给自家方块/物品加元素值、或改 build_cost，无需写 `data/<ns>/element_mappings/*.json`，代码注册即可。这是"少 JSON"的第一个直接落点。

**改动**：
- `content/element/internal/ElementMappingLoader.java`：
  - 加一个**运行时覆盖层**（与 NpcAttributes 同款模式）：`private final Map<String, ElementMappingConfig> runtimeOverrides = new ConcurrentHashMap<>();`
  - `findConfig*/hasMapping/getBuildCost*/isDisabled/getItemElementValue` 等查询方法**先查 runtimeOverrides，再回落到 registry**。
  - 加 `void register(String blockOrItemId, ElementType type, Map<ElementType, Long> buildCost)` 与 `void unregister(String blockOrItemId)`（写覆盖层）。
- `api/ElementApi.java`：
  - `void registerMapping(String blockOrItemId, ElementType type, Map<ElementType, Long> buildCost)`（buildCost 可空 → 单类型无成本）。
  - `void unregisterMapping(String blockOrItemId)`。
- `content/element/internal/ElementApiImpl.java`：委托 `mappingLoader.register(...)`。
- 注册时机：mod 初始化（`Wandscape.java` 装配处），或 addon 在自身 init 时经 `WandscapeApis.getElementApi()` 调用。

**注意**：本次**事件影响面**——`ElementBalanceChangedEvent`（`element/event`）在映射变化时应触发；登记后 `hasElementMapping`/`getBuildCost` 立即生效。

**验证**：加一个纯逻辑单测（注册后 `getBuildCost` 返回覆盖值、unregister 后回落默认）；`compileJava` + 该测试绿。

---

## 四、③ ColonyApi.grantExperience + getColonyLevel（程序化升级）

**目的**：addon 做事件/奖励/任务时想程序化给小镇升级（如"完成里程碑 +X 经验"）。当前 ColonyApi 只读查询，无 exp/level 写面。

**改动**：
- `api/ColonyApi.java`：
  - `int getColonyLevel(UUID colonyId)` — 查询（0 表示无此殖民地）。
  - `long getColonyExp(UUID colonyId)` — 查询。
  - `void grantExperience(UUID colonyId, long amount)` — 加经验，遵守 `Config.COLONY_MAX_LEVEL` 上限；可触发升级（内部走 `ColonyLevelManager` 现有升级逻辑）。
- `content/colony/ColonyLevelManager.java`：已有 `setLevel`（`:66`）、升级判定（`:99-117`）。加public `grantExp(colonyId, amount)` 委托现有 internal 递进；`getLevel/getExp` 读 `ColonyLevelData`。
- impl 处装配：`impl/` 或 `colony/` 已有 `ColonyApiImpl`，在这里暴露。

**验证**：编译绿；行为复用现有 `ColonyLevelManager`（不另起升级逻辑）。

---

## 五、④ Config 瘦身：删死键 + 拆 ClientConfig + 深度值回硬编码

**目标**：`Config.java` 只留玩家向标量；删死键；客户端键独立成 `ClientConfig`；深度引擎/手感值移回常量（**不新增 JSON**）。

### 4a. 删 4 个死键（`Config.java`）
| 键 | 真相 |
|---|---|
| `COLONY_RADIUS` | 0 读，纯死（colony 半径在别处硬编码；若要真配置需另行接线到 colony 创建，本方案不出、直接删） |
| `SAME_BUILDING_CONTINUATION_BONUS` | Config 死，活源 `WandscapeConstants.java:21` |
| `BASE_OPERATION_RANGE` | Config 死，活源 `WandscapeConstants.java:43` |
| `PER_WAND_LEVEL_RANGE` | Config 死，活源 `WandscapeConstants.java:44` |

### 4b. 新建 `ClientConfig.java`（`ModConfig.Type.CLIENT`）
迁入并保持读取点：`panel.flySpeed`、`particle.level`、`preview.resolution`、`preview.fps`。
- 读点在 client 侧（`OverviewFlightController`、`BuildingPreviewGifCache`、粒子层）改为 `ClientConfig.XXX`。
- `Config.java` 从 COMMON 移除这 4 键；COMMON 与 CLIENT 分别 `registerConfig`（`Wandscape.java`）。

### 4c. 深度值从 Config 移回常量（硬编码，不动手调）
对每个被移出的键：把默认值定义到 `WandscapeConstants`（或新建 `foundation/.../Defaults`），并把读取点 `Config.X.get()` → `WandscapeConstants.X`（或 `BalanceValues.X`，见⑤）。
移出清单（**不玩家向**）：
- scheduler：`heartbeatTicks`/`stuckCheckIntervalTicks`/`stuckMinMoveDistance`/`stuckMaxRetries`/`sameBuildingContinuationBonus`
- transport：`ticksPerBlockOnRoad`/`ticksPerBlockOffRoad`
- npc：`regenGraceTicks`/`regenIntervalTicks`/`manaRegenTicks`/`manaRegenFraction`/`walkThreshold`
- guard 深度：`releaseRange`/`selfDefenseRange`/`hateRange`/`hateDurationTicks`/`followAttackDurationTicks`/`peaceFleeRange`/`kiteStartDist`/`kiteStandoff`/`swayFlipTicks`/`engageStandoff`/`fleeHpThreshold`/`fleeStartDist`/`fleeStandoff`
- tourist 精细/时间窗：`spawnRangeWidth`/`levelSpawnBonus`/`spawnWindowStart`/`spawnWindowEnd`/`departureWindowStart`/`departureWindowEnd`/`departureDelayMaxTicks`/`rescueRoadRadius`/`rescuePeripheryRadius`/`energyRestoreThreshold`/`queueWaitToleranceTicks`/`queueSlotSpacing`/`stayMinDays`/`stayMaxDays`/`visionRadius`/`atmTravelFundMultiplier`/`atmWithdrawCooldownTicks`/`needBase`/`needPerLevel`/`nightStart`/`eveningRoutingStart`/`hotelTeleportDistance`/`baseWallet`/`walletPerLevel`/`barGainCoeff`/`arrivalRadius`/`microNavSwitchDistance`
- decoration：`scanIntervalTicks`；settlement：`windowTicks`；raid：`checkIntervalTicks`/`villageRange`/`nearbyRadius`；wand：`baseOperationRange`/`perWandLevelRange`

### 4d. Config 保留（玩家向，精简 ~11 键）
- `colony.offlineIncomeMultiplier` / `colony.initialElementCount` / `colony.maxLevel`
- `tourist.maxPerColony` / `tourist.baseSpawnCount`
- `element.decomposeDivisor` / `element.craftCostMultiplier`
- `general.autoApproveTasks` / `building.noSpawnInBuildingArea`
- `guard.range` / `revive.nearBuildingRange`

**验证**：`./gradlew compileJava` 绿；`grep Config.<移出键>` 零命中；`grep Config.<保留键>` 仍命中；`build -x test` 绿。

---

## 六、⑤ BalanceApi：把有真实需求的深度值做成 well-named 程序化可调面

**目的**：④把深度值从 Config 移走后，addon/整合包作者想要细调的是**代码里**而不是改 config——故建一个专门的调优 API（**替代**"塞回 config"），只做有真实需求的一撮，好命名。

**模式**（与 NpcAttributes 覆盖层一致）：
- `foundation/balance/BalanceValues`（内部静态）：每个值 = 默认常量 + `ConcurrentHashMap<String, Double>` 覆盖层；`getXxx()` 返回覆盖或默认。
- 读取点（④ 移出的那些）改走 `BalanceValues.getXxx()` → **④与⑤绑定**：④里被 ⑤覆盖的值读 `BalanceValues`，其余读 `WandscapeConstants` 常量。
- `api/BalanceApi`：`getXxx/setXxx`（每组带 javadoc、单位、默认值）；`resetAll()`。
- `WandscapeApis`：加 `getBalanceApi()` 槽位 + `impl` 装配（委托 `BalanceValues`）。

**首批有真实需求的组**（好命名示例，后续可扩）：
| 组 | 方法 |
|---|---|
| 守卫战斗 | `getGuardKiteStartDist/getGuardKiteStandoff/getGuardFleeHpThreshold/getGuardFleeStartDist/getGuardFleeStandoff/getGuardHateRange/getGuardReleaseRange/getGuardSelfDefenseRange` + 对应 `set` |
| 游客经济 | `getTouristBarGainCoeff/getTouristNeedBase/getTouristNeedPerLevel/getTouristWalletPerLevel/getTouristStayMinDays/getTouristStayMaxDays` + `set` |
| NPC 节奏 | `getNpcRegenGraceTicks/getNpcRegenIntervalTicks/getNpcManaRegenFraction/getNpcWalkThreshold` + `set` |
| 运输吞吐 | `getTransportTicksPerBlockOnRoad/getTransportTicksPerBlockOffRoad` + `set` |

**行为契约**：getter 返回当前生效值（覆盖或默认）；setter 写覆盖层；**运行时生效**（下次读时取到新值），不要求重启。已生成实体/进行中任务不追溯。

**验证**：编译绿；`BalanceValuesTest`（纯逻辑：默认值、覆盖后 getter 返回覆盖、reset 回默认）；读取点均走 `BalanceValues`。

---

## 七、执行顺序与验收

| 阶段 | 内容 | 验收 |
|---|---|---|
| A | ① WarehouseApi 加固 + ② ElementApi.registerMapping + ③ ColonyApi.grantExperience | `compileJava` 绿；②/③ 补纯逻辑测试绿 |
| B | ④ Config 瘦身（删死键 + 拆 ClientConfig + 深度值回常量） | `compileJava` + `build -x test` 绿；`grep Config.<移出键>` 零命中 |
| C | ⑤ BalanceApi（覆盖层 + 首批组） | `BalanceValuesTest` 绿；读取点走 `BalanceValues`；`build -x test` 绿 |

每阶段独立 commit：
- A：`feat: warehouse/element/colony API 补程序化操作——addElement 返布尔+批量、ElementApi.registerMapping、ColonyApi.grantExperience`
- B：`refactor: Config 瘦身——删 4 死键、拆客户端 ClientConfig、深度值回 WandscapeConstants（面向玩家精简到 11 键）`
- C：`feat: BalanceApi 程序化调优面——guard/游客/npc/transport 深度值可代码覆盖，替代 config 细调`

## 八、风险与注意
1. **④是 B 阶段最大风险**：移出每个键要同步改读取点（`Config.X` → 常量/`BalanceValues`），逐键 grep 核对，防漏。**「编译 + grep 移出键零命中」双验证。**
2. **⑤运行时覆盖**：覆盖层值在游戏运行期可变，须文档写明"不追溯已生成实体"；覆盖层 `ConcurrentHashMap` 线程安全。
3. **JSON 不新增**：所有注册（元素映射/配方/魔法）走 API 而非新 JSON。
4. **ColonyApi/ElementApi 是公开契约**：加方法用 default 或直接加签名（开发期不承诺二进制兼容，acceptable）。
5. 完成后更新 `newplan/status.md` + 在 `CLAUDE.md` 增补 Config/API 分层条款（防再散）。
6. **与 resources/data 审计协同（防再撞）**：`data/wandscape` 有 1368 文件，其中 `buildings/deprecated/`（14 文件）为向下兼容载荷**多次被误删**（判定靠 `newplan/packages.md` + 文件夹内 `README.md` 双重确认，CLAUDE.md 已记）；`road_templates/`（2 文件）系旧 schema 孤儿死数据待删；`blueprints/`（13 JSON）已另行收敛为 Java lambda。本方案执行涉及 `buildings` 时**避开 `deprecated/`**、涉及元素/建筑/配方时**别再当 blueprints 是 JSON**。具体字段/API 决策见 `newplan/config-api-decisions.md`（每字段打算 + API 增删，先人工审再执行）。
