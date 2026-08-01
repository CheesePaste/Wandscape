# guard/ — 守卫任务系统（Guard）总领性文档

> 文档编号：12 / 版本：0.1 / 状态：设计确认中
> 本文是**守卫任务系统**的总纲：先定可独立开发的模块和依赖关系，再按阶段一步步拆解实现。每个模块的实现细节后续各自落在 `docs/guard/<module>.md`。

## 一、目标与范围

**要解决的问题**：殖民地 NPC 目前完全没有战斗能力（没有任何 combat goal、无法对怪物造成伤害）。当有怪物靠近建筑时，殖民地应当自动反应。

**核心闭环**：

```
敌对怪物进入建筑 10 格内
  → 自动发布守卫任务（guard:attack）
  → 调度器派给空闲 NPC
  → NPC 走向怪物、站定、面向
  → 施放魔法阵（垂直于法杖/朝向）
  → 动画结束后信标光束射向怪物（可染色）
  → 命中造成伤害 → 怪物死亡 / 未死则冷却后重施
```

**明确边界（本套系统不做）**：
- 不做玩家 PvP、副本、战利品/掉落、经验。
- 不做怪物 AI 本身（原版怪物行为原样保留，兼容性优先）。
- 不做复杂的法术/技能树——守卫动作 = 一种攻击（魔法阵 + 光束），强度由数值配置。

## 二、现状与复用点（来自实际代码）

| 现状 | 位置 | 对守卫系统的意义 |
|------|------|----------------|
| 任务管线完整：`TaskSource` → `TaskRequest` → `GlobalTaskPool.addTask` → `SchedulerSystem`(2tick) → `TaskExecutionSystem` → `OpExecutor` → engine 边界 | `task/`、`op/` | 守卫任务走同一条管线，**不另起炉灶** |
| 自动发布模板：`BuildingTaskSource` 每 20 tick 轮询建筑队列，把 `WorkItem` 转 `TaskRequest` 入池 | `engine/source/BuildingTaskSource.java` | 守卫任务源的直接模板（轮询 → 发布） |
| `TaskSource` 接口：`int pollIntervalTicks()` + `void poll(GlobalTaskPool, World)`；由 `TaskSourcePoller`（ECS System）按间隔驱动 | `task/source/TaskSource.java`、`TaskSourcePoller.java` | 新守卫源实现它并注册进 `EngineBootstrap.bootstrap()` |
| 代码蓝图注册：`EventDrivenTaskSource.registerDefaultBlueprints` → `registry.register("gather:wood", ...)` | `task/source/EventDrivenTaskSource.java` | `guard:attack` 用代码蓝图注册（目标是实体 id，非方块坐标，JSON DSL 不便） |
| `AtomicOp` sealed interface，8 种变体 | `op/api/AtomicOp.java` | 新增攻击 op 是自然扩展点 |
| `EntityInteractOp(EntityId, EffectId, strength, duration)` 已存在，`EntityInteractExecutor` 已调 `world.entityOps.applyEffect(...)` | `op/api/AtomicOp.java`、`op/executor/DefaultOpExecutors.java` | 对实体施加效果的原语**已预留** |
| `EffectId.DAMAGE` 已定义 | `core/types/EffectId.java` | 伤害效果 id 现成 |
| `EntityOps.applyEffect` 是空 stub | `core/boundary/EntityOps.java`、`engine/boundary/WandscapeEntityOps.java` | 伤害落地点：实现 stub → `LivingEntity.hurt(...)` |
| NPC 有 `ATTACK_DAMAGE`(1.0)/`FOLLOW_RANGE`(48) 属性但**无任何战斗 goal**；只有 FloatGoal + RandomStrollGoal | `npc/entity/WandscapeNpc.java` `createAttributes()`/`registerGoals()` | 战斗能力从零建，属性已注册可复用 |
| `MovementOps.navigateTo` + `NavigationSystem`（`STOP_RANGE_SQ = 25`，5 格到达半径） | `engine/system/NavigationSystem.java`、`core/boundary/MovementOps.java` | NPC 走到怪物附近 5 格站定；`TaskExecutor.stance` 已有站位字段 |
| 异步引导模板：`RitualOps.beginRitual` / `AsyncTransformExecutor` / `ResourceRequestExecutor` 的 `Pending + tickAll() + CompletableFuture` 形态 | `engine/boundary/WandscapeRitualOps.java`、`AsyncTransformExecutor.java` | 攻击 op（施法引导 → 光束 → 伤害）照抄此形态 |
| 法力：`ManaPool` 组件 + `ManaRegenSystem` | `core/component/ManaPool.java` | 施法耗蓝、法力不足拦截 |
| 魔法阵视觉：`magicarchitecture/magic.md` 已定位为**战斗系统的视觉层**；spec/原则/示例齐全 | `magicarchitecture/`（`magic.md`、`magic-circles.md`、`magic-design-principles.md`） | 攻击视觉的数据契约已定，Java 端待实现 |

## 三、模块分解

系统拆成 **6 个可独立开发、独立测试的模块**。每个模块只做一件简单的事；复杂度来自模块间关系，不来自模块内部。

| 模块 | 建议包/文件 | 职责一句话 | 关键复用点 |
|------|------------|-----------|-----------|
| **M0 配置** | `Config.java`（现有 TOML）+ 守卫常量 | 侦测半径、伤害、冷却、法阵 id、颜色、耗蓝 | `Config.SPEC` 现有机制 |
| **M1 威胁侦测** | `guard/detection/` | 周期扫描建筑周围 10 格内敌对 `Monster`，产出 `GuardThreat`，活怪去重 | `BuildingApi`（建筑位置）、`Level.getEntitiesOfClass`、`ManaPool` 无关 |
| **M2 守卫任务源** | `guard/source/GuardTaskSource` | 威胁 → `TaskRequest("guard:attack", {monster})` → `GlobalTaskPool.addTask`；高优先级/去重/冷却 | `TaskSource` + `TaskSourcePoller` + `BuildingTaskSource` 模板 |
| **M3 战斗执行** | `op/api/AtomicOp`（新变体）+ `guard/executor/` | `AttackMonsterOp`（目标实体、伤害）+ 异步 executor：导航→施法→引导→命中→未死循环；目标死亡则任务完成 | `OpExecutor` 注册表 + `EngineBootstrap` + `RitualOps` 异步形态 |
| **M4 伤害边界** | `engine/boundary/WandscapeEntityOps` | 落地 `applyEffect(target, EffectId.DAMAGE, strength, duration)` → `LivingEntity.hurt(DamageSource, strength)`；按 id 查实体；伤害来源=NPC | `EntityOps` 接口 + `EffectId.DAMAGE` |
| **M5 魔法阵攻击视觉** | `magic/` + `MagicBeamEntity` | MagicCircleSpec 数据/加载/粒子发射器 + `MagicCircleCastPacket`（服务端→客户端）+ 信标光束实体与渲染器（`BeaconRenderer.renderBeaconBeam` 旋转朝目标 + 染色） | `magicarchitecture/` 契约；`BeaconRenderer` 原版光束 |
| **M6 NPC 战斗行为** | `npc/` + `engine/system/GuardSystem` | NPC 走到 5 格站定、面向怪物、施法冷却、耗蓝、受 spellPower/装备加成；目标死亡后恢复空闲 | `MovementOps`/`NavigationSystem`/`TaskExecutor.stance`/`ManaPool`/`EquipmentComponent` |

**说明**：
- **M5 独立存在**：它同时也是玩家施法（法杖右键 / 调试命令）的视觉层，不依赖守卫系统。守卫系统只是它的一个消费者。
- **M3 与 M4 的衔接**：最省事的路径是直接复用已存在的 `EntityInteractOp(monster, EffectId.DAMAGE, strength, duration)` 作为伤害原语；但守卫攻击需要"引导 → 视觉 → 伤害"的多 tick 流程，故 M3 单独做一个异步 executor（照抄 `RitualOps`/`AsyncTransformExecutor` 形态），内部走 M4 边界。

## 四、依赖关系

```
                    ┌─────────── M0 配置（全模块可读）───────────┐
                    ▼                                          ▼
              [M1 威胁侦测] ──> [M2 守卫任务源] ──> GlobalTaskPool ──> SchedulerSystem
                                                                    │ 把 guard:attack 派给空闲 NPC
                                                                    ▼
[M5 魔法阵视觉] <──── [M3 战斗执行] ──────────────> [M4 伤害边界] ──> LivingEntity.hurt
       ▲                   │
       │                   └──────────────> [M6 NPC 战斗行为]（站位/冷却/法力/加成）
       │
       └── 独立消费者：玩家法杖 / 调试命令也直接触发 M5
```

**依赖规则**（沿用项目约定）：
- M1 只依赖 building API + M0；不碰任务池。
- M2 依赖 M1 的输出 + 任务池；不碰战斗逻辑。
- M3 依赖 M4（伤害）、M5（视觉）、M6（行为）；通过 op/ 管线被调度器驱动。
- M5 不依赖守卫系统（可单独存在、单独测试）。
- 模块间用 `WandscapeApis` + EventBus 通信，禁止跨包直接 new 类。
- 新任务不另起炉灶分发 → 必须走 `TaskRequest → GlobalTaskPool → SchedulerSystem`（项目铁律 6）。

## 五、数据流（核心路径）

```
Monster 进入建筑 10 格
  → M1 周期扫描：Level.getEntitiesOfClass(Monster) ∩ 建筑周围圆 → GuardThreat（去重：活怪只报一次）
  → M2 GuardTaskSource.poll → TaskRequest("guard:attack", {monster: <entityId>}, priority=高)
  → GlobalTaskPool.addTask → SchedulerSystem(2tick) 按 靠近×0.5 + 空闲 派给 NPC
  → M6 NPC 经 MovementOps.navigateTo 走到怪物 5 格内（NavigationSystem STOP_RANGE_SQ=25），stance 站定、面向
  → M3 执行 AttackMonsterOp：
       ① 扣 ManaPool（法力不足 → 任务失败/等待）
       ② M5 触发施法：MagicCircleCastPacket（服务端→客户端）→ 客户端粒子渲染魔法阵（垂直于朝向）
       ③ 引导计时（Pending + tickAll，异步）
       ④ 动画结束 → 服务端生成 MagicBeamEntity（信标光束，起点=法阵中心，终点=怪物，颜色可配）
       ⑤ 光束命中 → M4 EntityOps.applyEffect(DAMAGE, strength) → LivingEntity.hurt(...)
       ⑥ 怪物存活 → 冷却后重施；死亡/失效 → 任务完成，NPC 恢复空闲
```

## 六、阶段划分（一步一步拆解）

每阶段**可独立编译、独立测试**，完成后 commit + 更新本表状态。阶段顺序 = 从"看得见的视觉"向外扩展到"自动化的守卫闭环"，避免一上来就碰跨模块调度。

| 阶段 | 内容 | 独立测试手段 | 状态 |
|------|------|-------------|------|
| **0** | M5 魔法阵攻击视觉：`magic/` 包（spec 数据/加载/粒子发射器）+ `MagicBeamEntity`（原版信标光束 + 染色）+ `MagicCircleCastPacket`；触发：法杖右键 + `/wandscape magic <circle> [color]` | 进游戏用命令/法杖施放，看魔法阵垂直于法杖、动画结束后光束射向准星目标、颜色可调 | ✅ 完成 |
| **1** | M4 伤害边界：实现 `EntityOps.applyEffect(DAMAGE)` → `LivingEntity.hurt`，按 id 查实体、伤害来源 NPC；加调试命令"打伤指定怪物" | `/wandscape` 调试命令对目标怪物造成伤害，血量下降 | ⬜ 未开始 |
| **2** | M3 战斗执行：`AttackMonsterOp` sealed 变体 + 异步 executor + `guard:attack` 代码蓝图 + `EngineBootstrap` 注册；NPC 可执行"击杀指定怪物"的手动/命令任务 | 给 NPC 派一条手动击杀任务，观察走到射程、施法、光束、掉血、死亡 | ⬜ 未开始 |
| **3** | M1 + M2 威胁侦测 + 守卫任务源：建筑 10 格内扫怪 → 自动发布守卫任务，高优先级、去重、冷却 | 建筑旁刷怪，观察自动出任务、NPC 自动去击杀；多怪不重复发 | ⬜ 未开始 |
| **4** | M6 NPC 战斗行为打磨：站距/面向/施法频率/冷却/耗蓝/spellPower 加成；目标死亡恢复空闲；全部进配置 | 多怪压力、连续击杀后 NPC 恢复、配置项生效 | ⬜ 未开始 |

## 七、注册点（汇总）

| 注册点 | 位置 |
|--------|------|
| `GuardTaskSource` 注册进 `TaskSource` 列表 | `engine/bootstrap/EngineBootstrap.java` `bootstrap()` |
| `AttackMonsterOp` 的 OpExecutor 注册 | `OpExecutorRegistry`（`EngineBootstrap` 覆盖异步版） |
| `guard:attack` 代码蓝图 | `EventDrivenTaskSource.registerDefaultBlueprints(BlueprintRegistry)` |
| `MagicCircleCastPacket` playToClient | `Wandscape.java` `onRegisterPayloads` |
| `MAGIC_GLOW` 粒子 / `MAGIC_BEAM` 实体 | `Wandscape.java`（PARTICLE_TYPES / ENTITIES DeferredRegister） |
| 粒子 Provider / 光束渲染器 / 魔法阵 emitter tick | `WandscapeClient.java` `onRegisterParticleProviders` / `onEntityRenderers` / 构造器 |
| `/wandscape magic` 调试命令 | `command/` 包，挂在 `onRegisterCommands` 的 `wandscape` 根下 |

## 八、参考

- 任务系统契约（**写码前必读**）：`docs/event-task.md`
- 任务包结构：`architecture/packages/task.md`；引擎边界：`architecture/packages/engine.md`；原子操作：`architecture/packages/op.md`
- 魔法阵视觉层设计：`magicarchitecture/magic.md`、`magic-circles.md`、`magic-design-principles.md`
- 现成魔法阵示例 spec：`magicarchitecture/example-specs/arcane_hexagram.json`
- 代码结构总纲：`architecture/README.md`
