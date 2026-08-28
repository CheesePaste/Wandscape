# guard/ — 守卫模块

`src/main/java/com/wsteam/wandscape/guard/`

## 职责

殖民地防线：**守卫任务**（建筑周边怪物检测 → 守卫任务 → NPC 原地施法）与 **NPC 主动防御**（主动索敌 + 自卫反击）。守卫蓝图由代码注册（非 JSON）。

## 配置（Config.java）

- `guard.range=10`：守卫攻击区（建筑 AABB 的 X/Z 水平外扩，Y 不变）。
- `guard.releaseRange=15`：释放区（无怪才完成，滞回带）。
- `guard.selfDefenseRange=16`：NPC 主动索敌半径（球面 + LOS）。
- `guard.hateRange=48`、`guard.hateDurationTicks=600`：NPC 仇恨记忆。
- `guard.peaceFleeRange=8`：和平模式 NPC 逃跑半径（可见怪进入即临时离任务后撤，怪远离后恢复）。
- `guard.kiteStartDist=9`、`guard.kiteStandoff=13`：战斗风筝触发/后撤距离（目标进入 9 格即后撤到威胁点 13 格外，最低间距超出苦力怕爆炸致死半径 ~4）。
- `guard.engageStandoff=9`：隔墙逼近交战落点（目标周围 9 格「有视线 + 可站立」，≥kiteStartDist 避免落点即折返）。
- `guard.fleeHpThreshold=0.30`：低血逃跑阈值（血量比例低于此值进入逃跑态）。
- `guard.fleeStartDist=12`、`guard.fleeStandoff=18`：逃跑态触发/后撤距离（更大，连充能苦力怕致死半径 ~8 都安全）。

## 守卫任务

- `GuardZone`：record 6 int；of() 仅 X/Z 水平外扩（刻意防锁洞穴怪）；contains 世界坐标含边界。
- `GuardScanner`：zones(level, range) 取全部非拆除中建筑 AABB 水平外扩；nearestInZones/hasMonsterInZones/unionAabb。
- `GuardTaskSource`：pollInterval=20；overworld 内找威胁：攻击区有存活 Enemy 且无活跃任务 → 发布 `guard:attack` 任务（params: attackRange/releaseRange/circle/color，优先级 `GUARD_PRIORITY=49`）。
- `GuardBlueprints`：代码注册 `guard:attack` → `[AtomicOp.AttackMonsterOp(attackRange, releaseRange, circle, color)]`。
- `GuardAttackExecutor`：持续异步循环（由 Wandscape.onServerTick 驱动）；RECHECK_TICKS=10；runCycle 重算攻击区→最近 Enemy；无目标→看脱离区无怪→光束淡出+cancelNavigation+完成；有怪→待命重试。future 未完成前 NPC 保持 ACTIVE 不被改派。
- `GuardCombat.engage`：有活跃光束→beam.retarget 主动切换；LOS 被挡→光束快淡出 + navigateToward（落点为怪物周围 `guard.engageStandoff`(9) 格「有视线 + 可站立」的安全交战点，杜绝传送兜底落到怪脸上被苦力怕炸）；LOS 通且安全距离→站定施法（CD/蓝/锁在 MagicCaster 内部门控：光束 50 蓝、基础 CD 400/SPELL_SPEED（锁结束后起算）、施法互斥锁全程），成功后杖尖 burstColored + GUARD_FIRE 音。**走位**：附近可见敌数 ≥3（CROWD_THRESHOLD）→ 群殴规避，往敌方质心反方向走位；LOS 通但目标进入威胁距离 `guard.kiteStartDist`=9 → 战斗风筝，后撤到威胁点 `guard.kiteStandoff`=13 格外「有视线 + 可站立」的落点。**低血逃跑态**：血量比例 < `guard.fleeHpThreshold`(0.30) → 触发/后撤改用 `guard.fleeStartDist`=12 / `guard.fleeStandoff`=18，且 LOS 被墙挡不走近交战、继续后撤（保命优先；L0 紧急奶已拦可奶的低血）。走位由 ECS 导航驱动，**施法不锁移动**（`tickCastingState` 已无「施法停移动」硬钉，`isCasting` 期间也能走位），光束独立跟随，真正「边走边打」；落点不可站立则静默站定继续打，不寻路进墙。**战斗态**：engage 每轮 `markInCombat` 保持 suppressWandering=true（禁 wandering，防战斗期间闲逛），战斗结束由执行器 `markCombatEnd` 恢复；空闲乱走由 `RandomStrollGoal` 让路（尊重 `isEngineIdle`/`isCasting`，殖民地任务施法期仍不乱走）。后撤落点 `findRetreatPos` 额外要求「NPC→落点 无墙」（走过去可达），源头减少寻路失败；正常走位不失败，self_teleport 传送回退留给狭小地带真正走投无路时逃生。**投掷物躲避走位**：`navigateDodge`/`findDodgePos` 复用同一套走位形式（ECS 导航 + 可站立/LOS 落点），沿弹道垂直方向走开 2.5 格（DODGE_DIST）让出弹道，无落点静默站定，由 ProjectileDodge 调用。
- `GuardCommand`：`/wandscape guard status`（perm 2）打印 zones/threat/releaseClear/activeGuards。

## NPC 主动防御

- `NpcSpellPowerHandler`：@Subscribe LivingIncomingDamageEvent；目标 Enemy 且伤害源是 WandscapeNpc → 伤害×SPELL_POWER（>1 时），再×魔力强化（`MagicSpellExecutors.magicEnhanceMultiplier`，无 buff 时 ×1）。玩家施法不经过。
- `SelfDefenseHandler`：NPC 受任意伤 → markRecentlyDamaged()（重置脱战回血计时）；攻击者非玩家且非同殖民地 NPC（`isRetaliationTarget`，不要求 Enemy——北极熊/铁傀儡/狼等中立生物主动攻击也还手）→ setHatedAttacker(uuid, gameTime+GUARD_HATE_DURATION_TICKS)。
- `SelfDefenseExecutor`：独立于守卫任务，每个 NPC 注入 `self_defense` 包。detectAndInject（每 DETECT_INTERVAL_TICKS=4）：已有自防御包或 guard: 全局任务则跳过；resolveTarget 命中则抢占——分离 pendingFuture（导航则取消）、suspendCurrent（挂起栈满跳过）、startPackage。优先级 90。resolveTarget：仇恨目标（存活、可反击、≤hateRange、LOS 可见）优先；否则半径内最近可见 Enemy（selfDefenseRange，球面距离+LOS）。runCycle：无目标→clearHatedAttackerIfExpired+complete（队列恢复挂起包）；有目标→engage。**和平模式**：不索敌不反击，但可见怪进入 peaceFleeRange → 同样抢占注入；runCycle 走逃跑分支（navigateAway 后撤，无威胁即 complete 恢复任务）。
- `ProjectileDodge`：**投掷物躲避**（与自防御互补——自防御管近身敌人，本模块管远程投掷物）。每 DETECT_INTERVAL_TICKS=3 扫所有殖民地 NPC 周围 20 格（DETECT_RADIUS）的敌对投掷物（`owner instanceof Enemy`：骷髅箭/凋零骷髅头/火球/女巫药水等），轨迹预判命中（`willHit` 纯数学：直线飞行最近距离 <1 格且 2~16 tick 内会到，窗口/半径可单测）则 `GuardCombat.navigateDodge` 走开让出弹道；单 NPC DODGE_COOLDOWN=12 tick 防连续弹幕来回拽；传送引导中（定身）跳过。

## 与其他模块关系

- 守卫任务经任务系统执行（GuardTaskSource → GlobalTaskPool）。
- 施法表现走 magic/（castNpcAt + MagicCircleCastPacket）。
- 伤害放大走 NpcSpellPowerHandler（SPELL_POWER 属性 + 魔力强化独立乘区）。
- 任务抢占用 NpcTaskQueue.suspendCurrent 挂起栈。
