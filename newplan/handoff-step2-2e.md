# Handoff — Step 2：2e API 收敛（剪接口 + 瘦内部桥 + 解散 WandscapeEngine）

> 用户明确做 Step 2。Step 1（config-api）已完成，见 `handoff-config-api.md`。当前分支 `refactor`，`compileJava` 绿。
> 依据：`config-api-decisions.md` Part D（已确认的去留表）+ 本次实测的 `WandscapeEngine` 消费方分布。

## 意图（为什么做）

CLAUDE.md 目标形态点名：**解散 `WandscapeEngine` 静态定位器**（"getXxx() 搭桥本体"）——旧 shared/engine 桥层病的残留。同时把 `api/` 里"纯内部搭桥接口"清掉，只留真公开契约 + 合理层间接缝。三件事一起：**剪接口 → 瘦内部桥 → 解散定位器**。

---

## 任务一：剪接口——实用功能保留，纯桥/空壳才砍

> **判定口径（重要）**：看**能否对整合包作者产生功能价值**，而不是"消费方是不是 mod 内部"。有真实查询/招募/发布任务/事件流价值的 → 保留；纯"让 A 不跨包引用 B"的搭桥 + 空壳实现 + 内部钩子 → 瘦/删。

### ScepterApi —— 保留
`isSheltered`/`isShelteredForAny`/`forcedHostile`：纯只读查询（读 `ScepterMarksSavedData` + 客户端安全封装），判"生物是否被庇护"对 addon 实用（避免误杀）、查某殖民地强制仇恨目标。**不改**。

### TavernApi —— 保留招募流程，删空壳、瘦钩子
- **保留**：`getMageResumes`/`recruitMage`/`rejectMage`/`getRecruitCount`/`canAffordRecruit`/`chargeRecruit`（完整招募流程：查简历→能否招→扣费→招募）。
- **删**（空壳）：`getCandidates`/`refreshCandidates`/`recruitCandidate`（Generic NPC recruitment 占位实现，返回空/false，无功能）。
- **改直调**：`receiveMageResume`（满条游客离场存简历钩子，纯内部——tourist 系统直调 `TavernRecruitStorage`）。

## 任务二：瘦"内部桥方法"（保留能产生功能价值的）

| 接口 | 瘦（移出接口 → 改直调实现类） | 留 |
|---|---|---|
| `ColonyApi` | `onBuildingIntact`/`onBuildingDestroyed`/`assignColonyIfPossible`/`rebuildFromSavedData`（building→colony 事件钩子，纯内部） | 查询+创建+等级面（getColonyId/getColonyLevel/grantExperience/... 不变） |
| `BuildingApi` | task 池协作 + 队列 UI（`dequeue`/`dequeueEligible`/`setCurrentTask`/`clearCurrentTask`/`isBuildingOccupied`/`getBuildingsWithPendingWork`/`getQueue`/`removeFromQueue`/`moveUp`/`moveDown`）+ 生命周期（`registerBuilding`/`unregisterBuilding`） | **`enqueueWork`**（程序化发布任务，addon 实用）+ 查询面（getBuilding/getColonyBuildings/getColonySnapshot/getBuildingBounds...）+ demolish/cancel/place + 可调面 get/set |
| `TouristApi` | `registerArrival`/`registerDeparture` 接口方法（系统内部登记，改内部直调）——**但保留 `TouristArrivedEvent`/`TouristDepartedEvent`**（真事件流，addon 监听） | getTouristCount/getTouristsInColony/spawnTourist/getOvernightStayerCount |

**保留（不砍）**：`GuideProgressApi`（横切教程推进，10+ 跨域消费）、`ColonyStatusApi`（镇指标查询接缝）、`ScepterApi`（上面已改保留）。

**一句话**：只剪"纯内部反依赖搭桥 + 空壳 + 内部钩子"；凡是能产生 addon 功能价值（查询/创建/招募/发布任务/事件流）的都留。tourist 导航查询（`findBeds`/`sampleWalkableGround`/`getTouristInteractionTarget`/`getEntryPoint`/`getTouristInteractPoint`）查询无害，**倾向保留**（addon 自定义互动/寻路可能用）。

## 任务三：解散 `WandscapeEngine` 静态定位器（最大、最重要）

**现状（实测）**：约 40 个文件引用。`getWorld` **46 引用**（ECS `World` 是真全局单例，**唯一例外**，不能 new）；其余 getter `getColonyLevelManager`(18)/`getTransporter`(5)/`getPlayerManualSource`(5)/`getResourceRequestExec`(2)/`getBlockInteractExec`(2)/`getAsyncExecutor`·`getRitualOps`·`getMovementOps`·`getGuardExecutor`·`getSelfDefenseExecutor`·`getAltarCastExecutor`·`getTaskPoolSavedData`·`getRoadSavedData`(各 1)；对应 set 各 1。

**改法**：这些是"域服务句柄"。解散 = **消费方在装配时拿到实例（构造注入/事件回调），不再 `WandscapeEngine.getXxx()`**。
- `getWorld`：建"World 注入门面"（如 `WorldHolder` 或成员字段），`Wandscape.java` 装配时塞入，各系统经门面取——是注入，非静态 getter。
- 其余域服务：谁用谁在 `Wandscape.java` 装配处把服务注入（或经所属域中枢对象）。
- `WandscapeEngine` 本身：**删除**（消费方全改注入）。⚠️ 检查 `blueprintConfigLoader` 是否残留（Step 1 已删蓝图 DSL，若已无则整体删干净；若有，一并清）。

**验收**：`grep -rn 'WandscapeEngine\.' src/main/java` **全仓零命中**；`compileJava` 绿。

---

## 顺序与风险

1. **先低危**：任务一（剪 TavernApi 空壳/钩子，ScepterApi 不动）+ 任务二（瘦内部桥）→ 各自独立 commit，行为不漂移（只是"桥变直调"）。
2. **最后高**：任务三（解散 WandscapeEngine）——40 文件、47 处 getWorld，**必须独立 + 每步 `compileJava` 兜底 + 小步 commit**。
3. 铁律：**移动不改逻辑、改逻辑另开一步**；每步完成 `grep WandscapeEngine.` 零命中才算验收。
4. 卡住标注：某服务消费方难以注入（如 World 深透进 ECS 系统）→ 标 `?` 交人工，别硬凑临时 getter。

## 边界 / 不做

- 不新增"公开事件流"（算新功能，无 addon 需求先不加）。
- 不动 Step 1 已完成的东西（`BalanceValues` / 各领域 API 可调面 / Config 精简 / WandscapeConstants 结构性常量）。
- 本步（2e）不改行为，纯结构；验收 = 编译绿 + grep 零命中 + 行为不变。

## 关键文件

- `impl/WandscapeEngine.java`（解散本体）
- `api/`：`ScepterApi`/`TavernApi`/`ColonyApi`/`BuildingApi`/`TouristApi`（改）
- 各 API 实现（`apiImpl`/`internal`）
- `Wandscape.java`（装配改造：注入替代 getter）
