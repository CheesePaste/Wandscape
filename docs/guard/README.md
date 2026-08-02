# guard/ — 守卫任务系统（Guard）+ NPC 自防御 总领性文档

> 文档编号：12 / 版本：0.3 / 状态：实现中
> 本文是**守卫任务系统** + **NPC 自防御**的总纲。守卫任务是建筑中心的全局任务；自防御是独立于守卫任务的 NPC 中心机制（主动仇恨半径 + 受伤反击），共享同一套战斗引擎（`GuardCombat`）。

## 一、目标与范围

**要解决的问题**：殖民地 NPC 目前完全没有战斗能力（没有任何 combat goal、无法对怪物造成伤害）。当有怪物靠近建筑时，殖民地应当自动反应——由空闲 NPC 施放魔法阵 + 光束将其消灭。

**核心闭环**：

```
怪物进入建筑 AABB 水平 +10 格区域（Y 不扩展）
  → 守卫任务源扫描到最近威胁（区域内最近敌对生物）
  → 发布 guard:attack 任务（优先级 49，同一时间仅一个活跃守卫任务）
  → 调度器派给空闲 NPC
  → 执行器每 10 tick 循环：
       ▶ LOS 可见 → 光束重定向到最近怪物（主动切换目标）、无光束则施法
       ▶ LOS 被方块挡 → 寻路到能打到怪物的位置（绕过墙体，LOS 一清就停手施法）
  → 动画结束后信标光束射向目标（每 tick 伤害束内敌对生物，光束随最近目标重定向）
  → 直到 AABB 水平 +15 格区域内无怪物 → 守卫任务完成，NPC 恢复空闲
```

**滞回区间（hysteresis）**：攻击/目标区为 AABB 水平 X/Z ± `guard.range`(10)；任务完成/脱离区为 ± `guard.releaseRange`(15)。有怪进入 +10 触发守卫，守卫持续到 +15 内无怪才结束——避免怪物恰好卡在 10 格边缘时 NPC 一遍遍进/出、反复触发。

**Y 不上下扩展**：+10/+15 只做水平（X/Z）扩展，Y 沿用建筑自身包围盒高度。否则会索敌到地下洞穴的怪物，光束打不到。

**明确边界（本套系统不做）**：
- 不做玩家 PvP、副本、战利品/掉落、经验。
- 不做怪物 AI 本身（原版怪物行为原样保留，兼容性优先）。
- 不做复杂的法术/技能树——守卫动作 = 一种攻击（魔法阵 + 光束），强度由数值配置。

## 二、现状与复用点（来自实际代码）

| 现状 | 位置 | 对守卫系统的意义 |
|------|------|----------------|
| 任务管线完整：`TaskSource` → `TaskRequest` → `GlobalTaskPool.addTask` → `SchedulerSystem`(2tick) → `TaskExecutionSystem` → `OpExecutor` | `task/`、`op/` | 守卫任务走同一条管线，**不另起炉灶** |
| 自动发布模板：`BuildingTaskSource` 每 20 tick 轮询建筑队列，把 `WorkItem` 转 `TaskRequest` 入池 | `engine/source/BuildingTaskSource.java` | 守卫任务源的直接模板（轮询 → 发布） |
| `TaskSource` 接口：`int pollIntervalTicks()` + `void poll(GlobalTaskPool, World)`；由 `TaskSourcePoller`（ECS System）按间隔驱动 | `task/source/TaskSource.java`、`TaskSourcePoller.java` | 新守卫源实现它并注册进 `EngineBootstrap.bootstrap()` |
| 代码蓝图注册：`EventDrivenTaskSource.registerDefaultBlueprints` → `registry.register("gather:wood", ...)` | `task/source/EventDrivenTaskSource.java` | `guard:attack` 用代码蓝图注册（目标为区域半径等纯参数，非方块坐标） |
| `AtomicOp` sealed interface，8 种变体 | `op/api/AtomicOp.java` | 新增 `AttackMonsterOp` 是自然扩展点（第 9 变体） |
| 异步 op 模板：`AsyncTransformExecutor` 的 `Pending + tickAll() + CompletableFuture` 形态，`tickAll()` 由 `Wandscape.onServerTick` 驱动 | `engine/boundary/AsyncTransformExecutor.java`、`Wandscape.java` | 守卫执行器照抄此形态做**持续循环**（施法 → 等光束 → 重选 → 再施法） |
| 施法入口：`MagicCaster.castNpc` / 新增 `castNpcAt(target)` | `magic/internal/MagicCaster.java` | 守卫执行器调用 `castNpcAt(指定目标)` |
| 光束调度：`MagicCastManager.schedule` + `tick()`（法阵动画后生成光束，同施法者去重） | `magic/internal/MagicCastManager.java` | 复用，防光束重叠 |
| 光束伤害：`MagicBeamEntity.damageTargets()` 每 tick 对束内 `Enemy` 造成 `DamageSources.magic()` 伤害，命中重置无敌帧 | `magic/entity/MagicBeamEntity.java` | **伤害已实现**，M4 无需另写 |
| 建筑包围盒：`BuildingState.getBounds()`（MC `BoundingBox`） | `building/internal/BuildingState.java` | 经 `BuildingApi.getBuildingBounds(UUID)` 跨模块取 AABB |
| `Config.AUTO_APPROVE_TASKS` 默认 `false` → 优先级 ≥50 的任务进 PENDING_APPROVAL | `Config.java` | 守卫优先级取 49（避开审批门槛，且高于普通建造任务 ~40） |
| NPC 属性/姿势：`ATTACK_DAMAGE`/`FOLLOW_RANGE` 已注册；`faceTarget`/`getStaffPosition`/`isCasting` | `npc/entity/WandscapeNpc.java` | 原地转向、持杖手几何、施法姿态复用 |

## 三、模块分解

| 模块 | 建议包/文件 | 职责一句话 | 关键复用点 |
|------|------------|-----------|-----------|
| **M0 配置** | `Config.java`（现有 TOML）+ `guard/GuardConstants.java` | `guard.range`(10)/`guard.releaseRange`(15)、守卫优先级(49)、轮询间隔(20)、法阵 id、颜色 | `Config.SPEC` 现有机制 |
| **M1 威胁侦测** | `guard/GuardZone.java`（纯数据）+ `GuardTaskSource` 扫描 | 所有非停摆建筑包围盒水平 ±10 区 → 区域内最近存活 `Enemy`，产出威胁 | `BuildingApi.getBuildingBounds`、`Level.getEntities` |
| **M2 守卫任务源** | `guard/GuardTaskSource` | 有威胁且无活跃守卫任务 → `TaskRequest("guard:attack", params)` 入池；`pool.isActive` 去重 | `TaskSource` + `TaskSourcePoller` + `BuildingTaskSource` 模板 |
| **M3 战斗执行** | `op/api/AtomicOp`（新变体 `AttackMonsterOp`）+ `guard/executor/GuardAttackExecutor` | 持续异步循环（每 ~10 tick）：找最近 `Enemy`（+10）→ 光束重定向到最近（主动切换）→ LOS 被挡则寻路绕过墙体 → 可见才 `castNpcAt` 施法；+15 区无怪才完成 | `AsyncTransformExecutor` 形态 + `MagicCaster.castNpcAt` + `MagicCastManager` + `MagicBeamEntity.retarget` |
| **M4 伤害边界** | `magic/entity/MagicBeamEntity` | **已实现**：光束每 tick 对束内 `Enemy` 造成 magic 伤害（不需 `EntityOps.applyEffect` stub） | `MagicBeamEntity.damageTargets` |
| **M5 魔法阵攻击视觉** | `magic/` | **已实现**：MagicCircleSpec 数据/粒子发射器 + MagicBeamEntity + MagicCircleCastPacket + 渲染 | `magic/` 现有契约 |
| **M6 NPC 战斗行为** | `guard/executor/` + `npc/entity/WandscapeNpc` | 转向、LOS 判定、LOS 被挡时经 `MovementOps` 寻路、施法后任务 hold 住 NPC（不被改派）、法力/法术强度加成留待后续 | `faceTarget`/`getStaffPosition`/`movementOps.navigateTo`/ECS `pendingFuture` |

**说明**：
- **M5 独立存在**：它同时也是玩家施法（法杖右键 / 调试命令）的视觉层，不依赖守卫系统。守卫系统只是它的一个消费者。
- **M4 已内化到 M5**：光束实体直接结算伤害，`EntityOps.applyEffect` 的 stub 不走守卫路径。
- **M3 持续循环**：守卫任务不是"一次施法一个任务"，而是**一个持续任务**——执行器在 `tickAll` 里每 ~10 tick 循环（找最近 → 光束重定向 → LOS/寻路 → 施法），直到 +15 区清空才 complete。任务期间 NPC 保持 ACTIVE（future 未完成），不会被调度器改派、不会中途跑去干别的。光束在持续期间主动切换最近目标；隔墙时经寻路绕到能打到的位置。

## 四、依赖关系

```
                    ┌─────────── M0 配置（全模块可读）───────────┐
                    ▼                                          ▼
              [M1 威胁侦测] ──> [M2 守卫任务源] ──> GlobalTaskPool ──> SchedulerSystem
                                                                    │ 把 guard:attack 派给空闲 NPC
                                                                    ▼
[M5 魔法阵视觉] <──── [M3 战斗执行(持续循环)] ──────────> [M4 伤害边界] ──> MagicBeamEntity 每tick魔法伤害
       ▲                   │  castNpcAt / MagicCastManager
       │                   └──────────────> [M6 NPC 行为]（原地转向/LOS/施法 hold）
       │
       └── 独立消费者：玩家法杖 / 调试命令也直接触发 M5
```

**依赖规则**（沿用项目约定）：
- M1 只依赖 BuildingApi（`getBuildingBounds`）+ M0；不碰任务池。
- M2 依赖 M1 的输出 + 任务池；不碰战斗逻辑。
- M3 依赖 M4（光束伤害）、M5（视觉）、M6（行为）；通过 op/ 管线被调度器驱动。
- M5 不依赖守卫系统（可单独存在、单独测试）。
- 模块间用 `WandscapeApis` + EventBus 通信，禁止跨包直接 new 类。
- 新任务不另起炉灶分发 → 必须走 `TaskRequest → GlobalTaskPool → SchedulerSystem`（项目铁律 6）。

## 五、数据流（核心路径）

```
Monster 进入某建筑 AABB 水平 +10 区
  → M1 扫描：所有非停摆建筑包围盒 → GuardZone(±10, Y 不变) → getEntities(Enemy) → 区域内最近存活
  → M2 GuardTaskSource.poll(20tick)：
        有威胁 且 无活跃守卫任务(pool.isActive) → TaskRequest("guard:attack",
            {attackRange:10, releaseRange:15, circle, color}, priority=49)
  → GlobalTaskPool.addTask → SchedulerSystem(2tick) 派给空闲 NPC（op.target()=null → 任务本身无站位）
  → M3 GuardAttackExecutor 持续循环（future 未完成，NPC 保持 ACTIVE，每 ~10 tick 一轮）：
        ① 重算所有非停摆建筑 GuardZone(±10 / ±15)
        ② +10 区找最近存活 Enemy（距 NPC）；无则看 +15 区 → 有怪待命重试、无怪 complete(任务完成)
        ③ 有目标 → 当前光束重定向到最近怪物（beam.retarget，主动切换目标）
        ④ LOS：持杖手→目标中心 射线被方块挡 → 寻路到怪物位置（寻路绕过墙体），LOS 一清就停手施法
        ⑤ 施法 = MagicCaster.castNpcAt(npc, target, circle, color)：
           MagicCircleCastPacket → 客户端法阵；MagicCastManager 排程光束(延迟20tick)
        ⑥ 光束每 tick 伤害束内 Enemy；光束随最近目标重定向，直到光束自然消失再补一发
        ⑦ 脱离区无怪 → complete future → 任务完成 → NPC 恢复空闲（停寻路、光束淡出）
```

## 六、阶段划分（一步一步拆解）

每阶段**可独立编译、独立测试**，完成后 commit + 更新本表状态。

| 阶段 | 内容 | 独立测试手段 | 状态 |
|------|------|-------------|------|
| **0** | M5 魔法阵攻击视觉（spec/粒子/光束/网络包） | 命令/法杖施放，看法阵垂直法杖、光束射向准星目标 | ✅ 完成 |
| **1** | M0 配置 + M1 纯逻辑：`GuardZone`（水平扩展/Y 不变/contains）+ 单测 + `Config.guard.*` | `./gradlew test` 全绿 | ✅ 完成 |
| **2** | M3 战斗执行：`AttackMonsterOp` + `MagicCaster.castNpcAt` + `GuardAttackExecutor` 持续循环 + 引擎钩子 | 派 `guard:attack` 任务，观察 NPC 施法→光束→怪掉血 | ✅ 完成 |
| **3** | M2 守卫任务源：`GuardBlueprints` + `GuardTaskSource` + `EngineBootstrap` 注册 | 建筑旁刷怪，观察自动出任务、NPC 自动施法；区域清空后任务完成、NPC 空闲 | ✅ 完成 |
| **4** | 打磨：主动切换最近目标 + 隔墙智能寻路 + `/wandscape guard status` | 多怪切换最近、隔墙绕行施法、10~15 边缘滞回、地下怪不锁定 | ✅ 完成（M6 法力/法术强度加成后续） |

## 七、注册点（汇总）

| 注册点 | 位置 |
|--------|------|
| `GuardTaskSource` 注册进 `TaskSource` 列表 | `engine/bootstrap/EngineBootstrap.java` `bootstrap()` |
| `GuardAttackExecutor` 的 OpExecutor 注册 + `WandscapeEngine.setGuardExecutor` | `EngineBootstrap` + `engine/WandscapeEngine.java` |
| `guard:attack` 代码蓝图 | `guard/GuardBlueprints.registerDefault(BlueprintRegistry)`（`EngineBootstrap` 调用） |
| `MagicCircleCastPacket` playToClient / `MagicBeamEntity` 实体注册 | `Wandscape.java`（已有） |
| `GuardAttackExecutor.tickAll()` 驱动 | `Wandscape.java` `onServerTick` |
| `/wandscape guard status` 调试命令 | `command/` 包，挂在 `onRegisterCommands` 的 `wandscape` 根下 |

## 八、参考

- 任务系统契约（**写码前必读**）：`docs/event-task.md`
- 任务包结构：`architecture/packages/task.md`；引擎边界：`architecture/packages/engine.md`；原子操作：`architecture/packages/op.md`
- 魔法阵视觉层设计：`magicarchitecture/magic.md`、`magic-circles.md`、`magic-design-principles.md`
- 现成魔法阵示例 spec：`magicarchitecture/example-specs/arcane_hexagram.json`
- 代码结构总纲：`architecture/README.md`

---

# NPC 自防御（独立子系统，v1.7.0）

## 一、目标与范围

**要解决的问题**：建筑守卫只覆盖"空闲 NPC 守建筑"。NPC 若在城镇外、或正在执行其它任务，被怪打不反击、也不会主动攻击身边的怪。自防御补齐：**每个 NPC 独立**拥有
- **主动仇恨半径**：`guard.selfDefenseRange`(12) 内的敌对生物**无条件攻击**。
- **受伤仇恨**：被非玩家攻击者打伤后记仇（`guard.hateRange`=32 内、`guard.hateDurationTicks`=600 过期，每次被打刷新），优先反击攻击者。

**优先级最高**：有目标时**抢占**当前任务（暂停），击杀/目标消失后**恢复**原任务。

**独立于守卫任务**：守卫任务走全局任务池（`TaskRequest → GlobalTaskPool → SchedulerSystem` 派空闲 NPC）；自防御走 **NPC 私有任务队列**（`NpcTaskQueue`），不经过全局池（无审批、可直接抢占）。两者复用同一套**战斗引擎** `GuardCombat`（光束重定向 / LOS / 隔墙寻路 / 施法节流）。

**互相战斗**：守卫与自防御的光束伤害都记为 NPC 造成（`DamageSources.indirectMagic(casterNpc, beam)`），怪物 `HurtByTargetGoal` 会反击 NPC → 受伤仇恨实际触发。玩家施法保持 `magic()` 不变（无施法者、不记仇恨）。

**边界**：
- 只对 `Enemy`（敌对生物）记仇/攻击——光束也只伤 `Enemy`，对非 Enemy 记仇会空转。
- 玩家、其它 NPC 的伤害不记仇（友伤排除）。
- 不扩展怪物 AI、不做追逐（自防御原地/寻路到能打到的位置施法，不追杀脱离目标）。

## 二、核心闭环

```
[侦测] NPC 周围 guard.selfDefenseRange(12) 内有 Enemy  /  NPC 被非玩家攻击者打伤（记仇）
  → SelfDefenseExecutor.detectAndInject（每4tick）：
      已有自防御/守卫战斗包 → 跳过
      有目标 → 分离 pendingFuture（若正卡异步op）→ queue.suspendCurrent → startPackage(self_defense)
  → 任务执行系统执行 SelfDefenseOp → SelfDefenseExecutor 持续循环（每10tick）：
      目标 = 仇恨目标(存活/非玩家/hateRange内) 优先 → 否则半径内最近 Enemy
      无目标 → complete future → 队列自动 resumeLatest 恢复挂起任务
      有目标 → GuardCombat.engage：光束重定向→LOS→隔墙寻路→施法
  → 光束每 tick 伤害束内 Enemy（记为 NPC 造成）→ 怪物反击 NPC → 受伤记仇 → 循环
```

## 三、模块/文件

| 模块 | 位置 | 职责 |
|------|------|------|
| `SelfDefenseOp(radius, circleId, color)` | `op/api/AtomicOp.java` | sealed 新变体（第10个），target()=null 不走路、耗蓝 0 |
| `SelfDefenseExecutor` | `guard/executor/SelfDefenseExecutor.java` | 持续循环 + 侦测抢占注入；`tick(World)` 由 `onServerTick` 驱动 |
| `SelfDefenseHandler` | `guard/SelfDefenseHandler.java` | NeoForge `LivingIncomingDamageEvent` → 记仇（非玩家非NPC的 Enemy） |
| `GuardCombat` | `guard/executor/GuardCombat.java` | 共享战斗引擎（守卫 + 自防御复用） |
| 仇恨状态 | `npc/entity/WandscapeNpc.java` | `hatedAttackerUuid`/`hateExpiryTick` + `getHatedAttacker`/`clearHatedAttackerIfExpired`；状态"战斗中" |
| 配置 | `Config.java` | `guard.selfDefenseRange`(12) / `guard.hateRange`(32) / `guard.hateDurationTicks`(600) |

## 四、抢占与恢复（关键机制）

- 复用 `NpcTaskQueue.suspendCurrent/resumeLatest`（已有基础设施，自防御是首个真实消费者）。
- **边界处理**：挂起时若 NPC 正卡异步 op（`pendingFuture` 未完成），先分离该 future（底层执行器独立推进、完成后 `startAsyncOp` 自动清理；导航 future 则取消导航），否则任务执行系统会一直等旧 future、不执行自防御包。
- **挂起栈满（深度3）**：跳过本次抢占，不覆盖当前包。
- **进度保护**：`TaskExecutionSystem.syncStepToPool` 现在只在当前包为 `global:*` 时同步 stepIndex 到全局任务池——否则自防御的 step 会覆盖被挂起全局任务的进度。
- 完成后队列 `finishCurrentPackage → startNextPending → resumeLatest` 恢复原包（含 stepIndex），NPC 按包 stance 寻路回去继续。

## 五、注册点

| 注册点 | 位置 |
|--------|------|
| `SelfDefenseExecutor` OpExecutor 注册 + `WandscapeEngine.setSelfDefenseExecutor` | `engine/bootstrap/EngineBootstrap.java` + `engine/WandscapeEngine.java` |
| `SelfDefenseHandler` NeoForge 事件订阅 | `Wandscape.java` 构造器 `EVENT_BUS.register` |
| `SelfDefenseExecutor.tick(world)` 驱动 | `Wandscape.java` `onServerTick`（守卫 ①f 之后 ①g） |
| `LivingIncomingDamageEvent`（NeoForge 1.21.1，`LivingHurtEvent` 已改名） | `guard/SelfDefenseHandler.java` |

## 六、单测

- `NpcTaskQueuePreemptionTest`：suspend→注入→finish 恢复挂起包且 stepIndex 不丢；挂起栈满防覆盖；空闲 NPC 抢占后回空闲。
