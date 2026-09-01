# Handoff — Step 2：2e API 收敛（剪接口 + 瘦内部桥 + 解散 WandscapeEngine）

> 用户明确做 Step 2。当前分支 `refactor`，`compileJava` 绿。
> 依据：`config-api-decisions.md` Part D（已确认的去留表）+ 务实模组架构（按域自闭环 + 消除全局上帝类，破除 MC 构造注入幻想）。

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

---

## 任务二：瘦"内部桥方法"（保留能产生功能价值的）

| 接口 | 瘦（移出接口 → 改直调实现类） | 留 |
|---|---|---|
| `ColonyApi` | `onBuildingIntact`/`onBuildingDestroyed`/`assignColonyIfPossible`/`rebuildFromSavedData`（building→colony 事件钩子，纯内部） | 查询+创建+等级面（getColonyId/getColonyLevel/grantExperience/... 不变） |
| `BuildingApi` | task 池协作 + 队列 UI（`dequeue`/`dequeueEligible`/`setCurrentTask`/`clearCurrentTask`/`isBuildingOccupied`/`getBuildingsWithPendingWork`/`getQueue`/`removeFromQueue`/`moveUp`/`moveDown`）+ 生命周期（`registerBuilding`/`unregisterBuilding`） | **`enqueueWork`**（程序化发布任务，addon 实用）+ 查询面（getBuilding/getColonyBuildings/getColonySnapshot/getBuildingBounds...）+ demolish/cancel/place + 可调面 get/set |
| `TouristApi` | `registerArrival`/`registerDeparture` 接口方法（系统内部登记，改内部直调）——**但保留 `TouristArrivedEvent`/`TouristDepartedEvent`**（真事件流，addon 监听） | getTouristCount/getTouristsInColony/spawnTourist/getOvernightStayerCount |

**保留（不砍）**：`GuideProgressApi`（横切教程推进，10+ 跨域消费）、`ColonyStatusApi`（镇指标查询接缝）、`ScepterApi`（上面已改保留）。

**一句话**：只剪"纯内部反依赖搭桥 + 空壳 + 内部钩子"；凡是能产生 addon 功能价值（查询/创建/招募/发布任务/事件流）的都留。

---

## 任务三：解散 `WandscapeEngine` 静态定位器（务实按域归位方案）

> **破除构造注入（DI）幻想**：Minecraft 的网络包（Packet）、实体（Entity）、命令（Command）和事件处理器（Event）由引擎反射或静态注册，无法进行纯 DI。解散方案采用 **“执行器运行时自闭环 + 领域服务按域归位 + 任务中枢理性收口”**。

### 1. 执行器与 Tick 自闭环 (`TaskRuntime`)
- 在 `content/task/runtime/`（或 `impl/`）建立 `TaskRuntime`，聚合 `World` 与 9 个内部执行器（`asyncExec`, `ritualOps`, `blockInteractExec`, `movementOps`, `transporter`, `resourceReqExec`, `guardExec`, `selfDefenseExec`, `altarCastExec`）。
- `EngineBootstrap.bootstrap(...)` 返回 `TaskRuntime` 实例。
- `Wandscape.java` 在 `onServerTick` 直接调用 `taskRuntime.tick(server)`，一举蒸发 `Wandscape.java` 中百行逐个 get 执行器的胶水代码与 9 对全局 Getter/Setter。

### 2. 领域服务与 SavedData 回归本域
- **`ColonyLevelManager`**：归入 `content/colony` 域，由 `ColonySavedData` 或 `ColonyManager` 统一持有，提供 `ColonyLevelManager.get(level)`，或上层直接通过 `ColonyApi` 查等级。
- **`ItemTransportManager`**：归入 `content/warehouse` 域，作为仓库物资流转管理器，提供 `ItemTransportManager.get(level)`（或单例直调）。
- **`RoadSavedData` / `TaskPoolSavedData`**：彻底删除 Engine 中的冗余静态缓存，使用 MC 原版标准的 `SavedData.get(serverLevel)`。
- **`PlayerManualSource`**：装配时直接挂载进 `TaskPool`，不暴露全局变量。

### 3. ECS `World` 核心单例收敛
- `World` 是 ECS 任务运行时的核心，提供 `TaskEngine.getActiveWorld()` / `World.getActive()` 作为任务域运行期单例。
- 任务发布方（Packet / Command / Building）若只为操作任务，收口为直接调用 `GlobalTaskPool` 或 `BuildingTaskSource`。

### 4. 彻底物理删除 `impl/WandscapeEngine.java`
- 验收标准：`grep -rn 'WandscapeEngine\.' src/main/java` **全仓零命中**；`compileJava` 绿。

---

## 顺序与执行步骤

1. **第 1 步**：任务一（剪 TavernApi 空壳/钩子）+ 任务二（瘦 ColonyApi/BuildingApi/TouristApi 内部桥）。
2. **第 2 步**：领域服务归位与 SavedData 缓存清理（`ColonyLevelManager` 归 `colony`，`ItemTransportManager` 归 `warehouse`，移除 Engine 中的 SavedData）。
3. **第 3 步**：构建 `TaskRuntime`，清理 `Wandscape.java` 中的 Tick 胶水代码，聚合内部执行器。
4. **第 4 步**：收口 `World` 访问至 `TaskEngine` / `World.getActive()`，物理删除 `impl/WandscapeEngine.java`。
5. **第 5 步**：全仓编译验证 `./gradlew compileJava`，更新 `status.md`，提交原子 commit。
