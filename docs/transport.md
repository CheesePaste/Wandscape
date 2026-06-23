# 物流运输系统 (Item Transport System)

## 概述

殖民地物品运输系统，负责在仓库 ↔ NPC 之间以**可视化飞行动画**运输物品（法杖、建材、合成材料等）。支持**道路网络**加速——物品在路上以 4倍速 移动。

## 架构图

```
┌─────────────────────────────────────────────────────┐
│ TaskExecutionSystem                                 │
│   → ResourceRequestOp                              │
│   → WandEquipOp / WandReturnOp                     │
└──────────────────────┬──────────────────────────────┘
                       │ executor.execute(op, world, npcId)
         ┌─────────────┼─────────────┐
         ▼             ▼             ▼
  WandEquipExecutor  WandReturnExecutor  ResourceRequestExecutor
  (仓库→NPC)         (NPC→仓库)         (仓库→NPC, N件串行)
         │             │             │
         └─────────────┼─────────────┘
                       │
         ┌─────────────▼─────────────┐
         │   planRoute(colony,from,to)  │  每个executor调
         │   → RoadRouter.plan()       │
         │   → RouteSegment列表        │
         └─────────────┬─────────────┘
                       │
         ┌─────────────▼─────────────┐
         │  ItemTransportManager      │
         │  .send(key, from, to, route) │
         │  .tickAll() 每帧驱动       │
         └─────────────┬─────────────┘
                       │ 每帧 lerp 位置
         ┌─────────────▼─────────────┐
         │  ItemEntity (服务端视觉实体) │
         │  noPhysics / noGravity    │
         │  弧线(野路) / 贴地(公路)   │
         └───────────────────────────┘
```

## 三层设计

| 层 | 文件 | 职责 |
|----|------|------|
| **Core 规划** | `core/road/RoadRouter.java` | 路网寻路：建图 → Dijkstra → 简化 → RouteSegment 列表 |
| **Core 数据** | `core/road/RouteSegment.java` | 单段 `(fromX,fromY,fromZ → toX,toY,toZ, onRoad)` |
| **Engine 运输** | `engine/transport/ItemTransportManager.java` | 创建视觉 ItemEntity，每帧 lerp 位置，管理 CompletableFuture 生命周期 |

## 触发点

| 操作 | 触发 | 文件 |
|------|------|------|
| 装备法杖 | SchedulerSystem 推 WandEquipOp | `WandEquipExecutor.java` |
| 归还法杖 | NPC idle 60 tick 后推 WandReturnOp | `WandReturnExecutor.java` |
| 请求资源 | TaskSequence 中 ResourceRequestOp | `ResourceRequestExecutor.java` |
| 测试命令 | `/wandscape transport <x> <y> <z> [item] [tx ty tz] [count]` | `TransportCommand.java` |

## 速度（参数可调）

| 类型 | 常量 | 值 | 实际速度 |
|------|------|-----|---------|
| 野路 (off-road) | `TICKS_PER_BLOCK_OFF_ROAD` | 10 ticks | **2 blocks/s** |
| 公路 (on-road) | `TICKS_PER_BLOCK_ON_ROAD` | 5 ticks | **4 blocks/s** |

定义位置：`core/road/RoadRouter.java:32-35`

## 路由算法

### RoadRouter.plan() 步骤

```
1. 计算直接距离 baseline
2. 路网为空 → 返回空列表（调方降级直飞）
3. 找到 start/end 最近的路网点 PathPoint → entry/exit 点
4. 绕路太远（viaRoad > direct*2） → 降级直飞
5. 建图（Graph）：
   - 遍历所有 RoadEdge
   - 每个 PathPoint(x,y,z) 是一个图节点
   - 同一边内相邻点连边，权重 = XZ manhattan 距离
   - 不同边但 (x,y,z) 完全相同的点 → 自动连通（路口/节点交接处）
6. Dijkstra 最短路径
7. 简化路径：去共线中间点（方向和 Y 都没变的删除）
8. 组装 RouteSegment：
   - off-road: start → 第一个路点
   - on-road:  路径内相邻点逐段
   - off-road: 最后一个路点 → end
```

### 特殊情况降级

| 情况 | 行为 |
|------|------|
| 无路网 (`network==null` 或空) | 直飞 |
| 入/出路点 null | 直飞 |
| 路跨度 ≤2 格 | 直飞 |
| 绕行 > 直接×2 | 直飞 |
| Dijkstra 找不到路径 | 直飞 |
| 异常（RoadApi 抛异常） | 直飞（静默） |

降级时直飞使用**野路速度 + 弧线动画**。

## 视觉动画

### 飞行路径分段

```
[A 仓库方块]..直飞弧线..→ [P0 上路点]..贴地直线..→ [P1]..贴地..→ ... → [Pn 下路点]..直飞弧线..→ [B NPC/目标]
```

每段叫一个 **Leg**，由 `tickAll()` 逐段推进。

### Y 轴动画

| 段类型 | 动画 | Y 偏移 |
|--------|------|--------|
| 野路 (off-road) | **弧线** `y + sin(t×π) × 1.5` | `+0.5` |
| 公路 (on-road) | **直线** 线性插值（贴路面） | `+1.0` (浮于路上方) |

### 每 tick 处理（物理压制）

`ItemEntity.tick()` 会覆盖位置/速度/碰撞/重力/age 等字段。`tickAll()` 在每 tick **之后** 执行，强行覆盖：

```java
t.entity.noPhysics = true;           // 禁止碰撞（原版 tick 会重置）
t.entity.setNoGravity(true);          // 禁止重力（原版 applyGravity 会下拉）
t.entity.setDeltaMovement(ZERO);      // 禁止原版速度位移
t.entity.setUnlimitedLifetime();      // 禁止过期消失
t.entity.setPickUpDelay(Short.MAX);   // 禁止被捡起
t.entity.hasImpulse = true;           // 强制网络同步（否则 sparse teleport）
```

### 创生时（onSend）

```java
spawnVisual():
  entity = new ItemEntity(level, x+0.5, y+0.5, z+0.5, stack)
  entity.setPickUpDelay(Short.MAX)
  entity.setUnlimitedLifetime()
  entity.setNoGravity(true)
  entity.noPhysics = true
  entity.hasImpulse = true
  level.addFreshEntity(entity)
```

## 时序 & CompletableFuture 模型

### 单物品运输

```
t=0:  executor.execute() → transporter.send() → return future (未完成)
      引擎: exec.pendingFuture = future → return (等待)
t=1+: tickAll() → lerp 位置
t=n:  tickAll() → 最后一段结束 → entity.discard() → future.complete(null)
      引擎: pendingFuture.isDone → advanceStep()
```

### ResourceRequestOp 批量串行

```
ResourceRequestOp(stone×100):
  chain = completedFuture
  for i in 0..99:
    chain = chain.thenCompose(v → transporter.send())
  chain.thenRun(→ inv.add + commit)
  return chain

→ 100 个 ItemEntity 逐个飞，全部到位后入背包
```

### 欠料处理

```
ResourceRequestExecutor:
  hasEnough? no → return failedFuture(ResourceShortageException(stone×100))
引擎 processNpc:
  future.isCompletedExceptionally → cause is ResourceShortageException
    → taskPool.markAwaitingResources → task → AWAITING_RESOURCES
    → NPC released → 回到 IDLE
→ 仓库补货 → ResourceFulfilledEvent → task → PENDING_ASSIGN → 重新分配
```

## 法杖归还延迟

NPC 完成任务后**不立即归还法杖**，保留 60 tick (3秒) 冷却：

```
TaskExecutor.wandIdleTicks 每 tick 递增
  ↓ idle=60 + equippedWandIds 非空
  → push WandReturnOp 到私有队列
  → wandIdleTicks 重置
```

下次任务如果在这 3 秒内到达，`wandIdleTicks` 被 SchedulerSystem 重置为 0，法杖直接复用。

## 孤儿回收

NPC 死亡/消失 → `WandscapeNpc.onRemovedFromLevel`：

```
KILLED/DISCARDED → ItemTransportManager.cancelForNpc(npcId, bank, colonyId)
  → 所有 in-flight 物品 → bank.add(colonyId, itemKey, 1) → 入仓库
  → entity.discard()
  → future.cancel(false)
```

## 延时注册解决

NPC 实体可能在引擎 bootstrap 完成前从 chunk 加载，此时 `WandscapeEngine.getWorld()==null`：

```
onAddedToLevel → World==null → deferJoin(this)
  每 tick → flushDeferredJoins(world) → onNpcJoinWorld()
```

## 测试命令

```
# 物品往返（到玩家再飞回）
/wandscape transport <x> <y> <z> [item]

# 单向飞行
/wandscape transport <x> <y> <z> <item> <tx> <ty> <tz>

# 批量串行
/wandscape transport <x> <y> <z> <item> <tx> <ty> <tz> <count>
```

## 规划日志（每次运输一份）

```log
[RoadRouter] ═════ Route planning ═════
  From:  (10,64,5)        To: (120,64,80)
  Direct XZ distance: 185 blocks
  Network: 4 nodes, 3 edges, 328 path points
  Road entry: (12,64,8) (off-road: 5 blocks)
  Road exit:  (118,64,78) (off-road: 4 blocks)
  Graph: 312 unique position nodes
  Dijkstra: visited 187 nodes, path found: true (165 nodes)
  Simplified: 165 → 42 points
  Segments: 44 (2 off-road, 42 on-road)
  Distance: 9 off-road + 176 on-road = 185 blocks
  Time: 970 ticks (direct would be 1850 ticks, saved 880 = 48%)
═════ Route planned ✓
```

## TODO / 后续优化

1. **客户端粒子替代** — ItemEntity 在服务端每 tick 同步位置给客户端，殖民地几十个物品同时飞时会有网络压力。改用自定义网络包 + 客户端 `ItemParticleOption` + `ParticleProvider` 来渲染。
2. **道路寻路** — 当前公路段是逐 PathPoint 直线走（L-shape），可改为直接沿图边 BFS 更优的路线。
3. **路网更新** — 公路建造/拆除后当前 in-flight 物品不重新规划（已经发送的继续飞旧路径）。
4. **断点续传** — NPC 死亡后物品回仓库，任务恢复时从头开始。可改为 stepIndex 级别的进度恢复。
