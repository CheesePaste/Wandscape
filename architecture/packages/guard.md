# guard/ — 守卫任务系统 + NPC 自防御

殖民地 NPC 的战斗能力，两条互补路径：
1. **守卫任务（建筑中心）**：检测建筑周边敌对怪物，自动发布守卫任务，空闲 NPC 施放魔法阵 + 光束消灭。
2. **NPC 自防御（NPC 中心）**：NPC 主动仇恨半径(12)内无条件攻击 + 受伤仇恨反击（玩家除外）；抢占当前任务，打完恢复。独立于守卫任务，共享战斗引擎 `GuardCombat`。

## 核心闭环

```
怪物进入建筑 AABB 水平 +10 区（Y 不扩展）
  → GuardTaskSource 扫描到最近威胁 → 发布 guard:attack（优先级 49）
  → 调度器派给空闲 NPC → GuardAttackExecutor 持续循环（每 ~10 tick）
  → 区域内找最近 Enemy → 光束重定向到最近 → LOS 通过施法 / LOS 被挡则寻路绕过墙体
  → AABB 水平 +15 区内无怪 → 任务完成，NPC 恢复空闲
```

## 滞回区间

攻击/目标区 = 建筑包围盒水平 X/Z ± `guard.range`(10)；任务完成/脱离区 = ± `guard.releaseRange`(15)。Y 均不扩展（避免索敌到地下洞穴怪物）。有怪进 +10 触发，持续到 +15 无怪才结束，防边缘徘徊反复触发。

## 关键文件

| 文件 | 职责 |
|------|------|
| `GuardZone.java` | 纯数据 record：`of(bounds, horizontalExpand)` 水平扩展、Y 不变；`contains(x,y,z)`。可单测 |
| `GuardConstants.java` | `GUARD_PRIORITY=49`（<50 避开 PENDING_APPROVAL）、`POLL_INTERVAL=20`；法阵 id/颜色复用 `MagicCaster` |
| `GuardBlueprints.java` | 注册 `guard:attack` 代码蓝图：params → `AttackMonsterOp` |
| `GuardTaskSource.java` | TaskSource：扫描建筑 +10 区 → 有威胁且无活跃守卫任务 → 发布任务；`pool.isActive` 去重 |
| `executor/GuardAttackExecutor.java` | OpExecutor<AttackMonsterOp>：持续异步循环（每~10 tick 找最近→光束重定向→LOS/寻路→施法），+15 区无怪才 complete |
| `executor/GuardCombat.java` | **共享战斗引擎**：光束重定向/LOS/隔墙寻路/施法节流。守卫与自防御复用 |
| `executor/SelfDefenseExecutor.java` | OpExecutor<SelfDefenseOp>：自防御持续循环 + 侦测抢占注入（`suspendCurrent`→`startPackage`）；`tick(World)` 由 onServerTick 驱动 |
| `SelfDefenseHandler.java` | NeoForge `LivingIncomingDamageEvent`：NPC 被非玩家 Enemy 打伤 → 记仇（`WandscapeNpc.setHatedAttacker`） |
| `GuardCommand.java` | `/wandscape guard status` 调试命令 |

## NPC 自防御（独立子系统）

- **机制**：主动仇恨半径 `guard.selfDefenseRange`(12) 内无条件攻击；被非玩家 Enemy 打伤记仇（`guard.hateRange`=32、`guard.hateDurationTicks`=600，每次被打刷新），仇恨优先于半径扫描。
- **抢占**：走 `NpcTaskQueue` 私有队列，不经过全局任务池（无审批）。`detectAndInject` 每 4 tick：已有自防御/守卫战斗包则跳过；有目标则分离 pendingFuture（防卡异步 op）→ `suspendCurrent` → `startPackage(self_defense)`；挂起栈满跳过。完成后队列自动 `resumeLatest` 恢复原包（stepIndex 不丢）。
- **进度保护**：`TaskExecutionSystem.syncStepToPool` 只在当前包为 `global:*` 时同步——否则自防御的 step 覆盖被挂起全局任务进度。
- **互相战斗**：守卫/自防御光束伤害记为 NPC 造成（`MagicBeamEntity` 用 `indirectMagic(casterNpc, beam)`），怪物 `HurtByTargetGoal` 反击 NPC → 受伤仇恨实际触发；玩家施法保持 `magic()`。
- 状态文本 `opKind` 的 `SelfDefenseOp → "combat"`，NPC 头顶显示"战斗中"。

## 依赖与边界

- 守卫依赖 `BuildingApi.getBuildingBounds(UUID)` 取建筑 AABB（跨模块不直接引用 building/internal）；自防御只依赖 `EntityComponentBridge` + 自身半径扫描。
- 伤害与视觉完全复用 `magic/`（`MagicCaster.castNpcAt` + `MagicCastManager` + `MagicBeamEntity` 每 tick 伤害）。
- 守卫任务分发走 `TaskRequest → GlobalTaskPool → SchedulerSystem`（铁律 6）；自防御不走全局池，直接注入私有队列。
- 守卫任务 `AttackMonsterOp.target() = null` → 任务本身无站位；LOS 可见时 NPC 原地施法，LOS 被挡时执行器经 `MovementOps.navigateTo` 寻路绕到能打到的位置。
- `guard/executor/` 的 `tickAll()` / `tick(world)` 由 `Wandscape.onServerTick` 驱动（经 `WandscapeEngine` 钩子）。
