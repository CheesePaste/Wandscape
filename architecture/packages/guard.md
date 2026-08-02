# guard/ — 守卫任务系统

殖民地 NPC 的战斗能力：检测建筑周边的敌对怪物，自动发布守卫任务，空闲 NPC 原地施放魔法阵 + 光束将其消灭。

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
| `GuardCommand.java` | `/wandscape guard status` 调试命令 |

## 依赖与边界

- 依赖 `BuildingApi.getBuildingBounds(UUID)` 取建筑 AABB（跨模块不直接引用 building/internal）。
- 伤害与视觉完全复用 `magic/`（`MagicCaster.castNpcAt` + `MagicCastManager` + `MagicBeamEntity` 每 tick magic 伤害）。
- 任务分发走 `TaskRequest → GlobalTaskPool → SchedulerSystem`（铁律 6）。
- 守卫任务 `AttackMonsterOp.target() = null` → 任务本身无站位；LOS 可见时 NPC 原地施法，LOS 被挡时执行器经 `MovementOps.navigateTo` 寻路绕到能打到的位置。
- `guard/executor/` 的 `tickAll()` 由 `Wandscape.onServerTick` 驱动（经 `WandscapeEngine` 钩子）。
