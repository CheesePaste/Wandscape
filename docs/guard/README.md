# guard/ — 守卫任务系统（Guard）总领性文档

> 文档编号：12 / 版本：0.2 / 状态：实现中
> 本文是**守卫任务系统**的总纲：先定可独立开发的模块和依赖关系，再按阶段一步步拆解实现。每个模块的实现细节后续各自落在 `docs/guard/<module>.md`。

## 一、目标与范围

**要解决的问题**：殖民地 NPC 目前完全没有战斗能力（没有任何 combat goal、无法对怪物造成伤害）。当有怪物靠近建筑时，殖民地应当自动反应——由空闲 NPC 施放魔法阵 + 光束将其消灭。

**核心闭环**：

```
怪物进入建筑 AABB 水平 +10 格区域（Y 不扩展）
  → 守卫任务源扫描到最近威胁（区域内最近敌对生物）
  → 发布 guard:attack 任务（优先级 49，同一时间仅一个活跃守卫任务）
  → 调度器派给空闲 NPC
  → NPC 原地转向、视线（LOS）确认、施放魔法阵（不需走近怪物）
  → 动画结束后信标光束射向目标（每 tick 伤害束内敌对生物）
  → 光束结束 → 执行器重选区域内最近怪物 → 再施法
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
| **M3 战斗执行** | `op/api/AtomicOp`（新变体 `AttackMonsterOp`）+ `guard/executor/GuardAttackExecutor` | 持续异步循环：区域内找最近 `Enemy`（+10）→ 视线通过 → `castNpcAt` 施法 → 等光束结束 → 重选；+15 区无怪才完成 | `AsyncTransformExecutor` 形态 + `MagicCaster.castNpcAt` + `MagicCastManager` |
| **M4 伤害边界** | `magic/entity/MagicBeamEntity` | **已实现**：光束每 tick 对束内 `Enemy` 造成 magic 伤害（不需 `EntityOps.applyEffect` stub） | `MagicBeamEntity.damageTargets` |
| **M5 魔法阵攻击视觉** | `magic/` | **已实现**：MagicCircleSpec 数据/粒子发射器 + MagicBeamEntity + MagicCircleCastPacket + 渲染 | `magic/` 现有契约 |
| **M6 NPC 战斗行为** | `guard/executor/` + `npc/entity/WandscapeNpc` | 原地转向、LOS 判定、施法后任务 hold 住 NPC（不被改派）、法力/法术强度加成留待后续 | `faceTarget`/`getStaffPosition`/ECS `pendingFuture` |

**说明**：
- **M5 独立存在**：它同时也是玩家施法（法杖右键 / 调试命令）的视觉层，不依赖守卫系统。守卫系统只是它的一个消费者。
- **M4 已内化到 M5**：光束实体直接结算伤害，`EntityOps.applyEffect` 的 stub 不走守卫路径。
- **M3 持续循环**：守卫任务不是"一次施法一个任务"，而是**一个持续任务**——执行器在 `tickAll` 里循环（施法 → 等光束 → 重选最近 → 再施法），直到 +15 区清空才 complete。任务期间 NPC 保持 ACTIVE（future 未完成），不会被调度器改派、不会中途跑去干别的。

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
  → GlobalTaskPool.addTask → SchedulerSystem(2tick) 派给空闲 NPC（op.target()=null → 无导航、不走路）
  → M3 GuardAttackExecutor 持续循环（future 未完成，NPC 保持 ACTIVE）：
        ① 重算所有非停摆建筑 GuardZone(±10 / ±15)
        ② +10 区找最近存活 Enemy；无则看 +15 区 → 有怪 STANDBY 重试、无怪 complete(任务完成)
        ③ 视线(LOS)：持杖手→目标中心 射线被方块挡 → STANDBY 重试
        ④ MagicCaster.castNpcAt(npc, target, circle, color)：
           MagicCircleCastPacket → 客户端法阵；MagicCastManager 排程光束(延迟20tick)
        ⑤ 等光束结束(20 + 法阵时长 + 20) → 回 ②（重选最近，实现"每施法换最近"）
        ⑥ +15 区无怪 → complete future → 任务完成 → NPC 恢复空闲
```

## 六、阶段划分（一步一步拆解）

每阶段**可独立编译、独立测试**，完成后 commit + 更新本表状态。

| 阶段 | 内容 | 独立测试手段 | 状态 |
|------|------|-------------|------|
| **0** | M5 魔法阵攻击视觉（spec/粒子/光束/网络包） | 命令/法杖施放，看法阵垂直法杖、光束射向准星目标 | ✅ 完成 |
| **1** | M0 配置 + M1 纯逻辑：`GuardZone`（水平扩展/Y 不变/contains）+ 单测 + `Config.guard.*` | `./gradlew test` 全绿 | ⬜ 进行中 |
| **2** | M3 战斗执行：`AttackMonsterOp` + `MagicCaster.castNpcAt` + `GuardAttackExecutor` 持续循环 + 引擎钩子 | 手动派 `guard:attack` 任务，观察 NPC 原地施法→光束→怪掉血 | ⬜ 未开始 |
| **3** | M2 守卫任务源：`GuardBlueprints` + `GuardTaskSource` + `EngineBootstrap` 注册 | 建筑旁刷怪，观察自动出任务、NPC 自动施法；区域清空后任务完成、NPC 空闲 | ⬜ 未开始 |
| **4** | 打磨 + 调试命令：`/wandscape guard status`；法力/法术强度加成（M6 后续） | 多怪压力、10~15 边缘滞回、地下怪不锁定、命令打印状态 | ⬜ 未开始 |

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
