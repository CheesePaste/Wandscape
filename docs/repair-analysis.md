# 修复系统问题分析

## 背景

玩家通过异常系统点"修复"按钮后，建筑没有被修复（NPC 未工作、方块未被替换）。本文档追踪整个修复流水线，找出根本原因。

---

## 修复流水线总览

```
自动修复:
  BlockEvent.BreakEvent / ExplosionEvent.Detonate
    → BuildingBreakHandler.onBlockBreak / onExplosion
    → BuildCompleteListener.findDamagedBlocks()  扫描所有pattern方块
    → isBroken() 判断是否达到 1/3 损坏阈值
    → enqueueRepairForOffsets()  入队 WorkItem("build:place_structure", priority=49)
    → 队列: state.getTaskQueue().addFirst(repairWork)

手动修复（异常系统）:
  AnomalyScreen "修复" 按钮
    → BuildingActionPacket("repair")  ->  BuildingBreakHandler.triggerRepair()
    → findDamagedBlocks() → enqueueRepairForOffsets()  // 同上

任务发布:
  BuildingTaskSource.poll() (每 20 tick)
    → api.getBuildingsWithPendingWork()  筛选有工作且非shutdown的建筑
    → btp.enqueue(buildingId, item, pool)   → 发布到 GlobalTaskPool
    → api.setCurrentTask()  标记建筑当前任务

任务执行:
  SchedulerSystem (每 2 tick 评分) → NPC 认领
    → TaskExecutionSystem → AtomicOp → WandscapeBlockOps → 放方块

完成回调:
  Engine EventBus "build_complete"
    → BuildCompleteListener.onBuildComplete()
    → findDamagedBlocks() 重新验证
    → setStructureIntact(true) + addBuildingContribution()
```

---

## 根因：shutdown 阻塞整个修复流水线（关键问题）

### 问题链

**1. `BuildingState.hasWork()` 第 86 行**

```java
public boolean hasWork() { return !taskQueue.isEmpty() && !shutdown; }
```

当 `shutdown=true` 时，即使队列**有 WorkItem**，`hasWork()` 也返回 `false`。

**2. `BuildingApiImpl.getBuildingsWithPendingWork()` 第 359 行**

```java
if (!state.hasWork()) {
    Log.debug(TAG, "[BldgAPI] skip {} queue={} shutdown={} noWork=true", ...);
    continue;
}
```

shutdown 的建筑直接被跳过，它的修复任务永远不会被发布到 GlobalTaskPool。

**3. `BuildingApiImpl.dequeueWork()` 第 380 行**

```java
if (state == null || state.isShutdown()) return null;
```

即使有其他路径尝试出队，shutdown 建筑也返回 null。

**4. `BuildingTaskSource.supplyNodeBuildings()` 第 145 行**

```java
if (bd == null || bd.isShutdown() || !bd.isStructureIntact()) continue;
```

Node 建筑自动补货也跳过 shutdown + broken 建筑，导致已经半残的建筑更缺元素，进入恶性循环。

### 典型场景

```
1. 建筑因维护费不足被 DailySettlementSystem shutdown（reason="maintenance"）
2. 怪物爆炸/玩家误拆导致部分方块损坏
3. BuildingBreakHandler 触发，enqueueRepairForOffsets 入队修复任务
4. 但由于 shutdown=true:
   - hasWork() 返回 false
   - getBuildingsWithPendingWork() 跳过该建筑
   - 修复任务永远留在队列中不处理
5. 玩家打开异常系统，看到"损坏" + "关闭"两条
6. 点"修复" → triggerRepair 再次入队修复任务，但仍被 shutdown 阻塞
7. 玩家点"营业" → api.restart() 清除 shutdown
8. 但建筑仍然是 structureIntact=false，贡献没恢复
9. 下一个 BuildingTaskSource poll → 发现修复任务 → 开始修复
```

核心矛盾：**shutdown 的本意是"建筑不活跃"，但它也阻止了让建筑重新活跃所需的修复工作。**

---

## 次要问题

### 2. 异常系统的"修复"与 shutdown 互斥

- 异常系统把 "损坏" 和 "关闭" 列为两条独立记录
- 玩家不知道必须先点"营业"解除 shutdown，修复才能生效
- 点"修复"后屏幕关闭（onClose），玩家看不到任何反馈
- 即使 repair WorkItem 被入队，也必须等 shutdown 解除后才能处理

### 3. triggerRepair 不检查是否已有修复任务

每次点"修复"都调用 `triggerRepair()` → `findDamagedBlocks()` → `enqueueRepairForOffsets()`。如果：
- 自动修复已经入队了一个 WorkItem，手动点"修复"会再入队一个重复的
- 多个重复 WorkItem 入队后，修复时会做重复工作

### 4. findDamagedBlocks 的假阴性风险

`BuildCompleteListener.blockMatchesSpec()` 比较世界中的 BlockState 与配置中的 `blockMapping`。如果：

- 建筑配置里的 blockMapping key 是 `"0,0,0"` → `"minecraft:oak_stairs[facing=north]"`
- NPC 放置时方向被旋转（因为 NPC 朝向不同），实际放置的是 `minecraft:oak_stairs[facing=south]`
- blockMatchesSpec 返回 false → 被误判为损坏

这个风险在**初始构建**时也存在，但构建后 BuildCompleteListener 会验证一次。如果初始构建是通过 `BuildCompleteListener.onBuildComplete` 验证通过的，说明初始构建时所有 blockState 匹配。那么后续损坏检测也应该准确——**除非建筑配置和实际放置之间有持续的不一致**（如旋转导致）。

### 5. BuildingBreakHandler 的事件注册

需要确认 `BuildingBreakHandler` 是否被正确注册到了 NeoForge.EVENT_BUS。查询代码：

Grep `BuildingBreakHandler.register` 或 `NeoForge.EVENT_BUS.register(BuildingBreakHandler.class)`

如果未注册，自动修复系统就不会触发。

### 6. 手动修复屏幕数据是快照

`AnomalyScreen.init()` 从 `WandscapePanelState` 快照数据。如果在屏幕打开期间服务端同步了新的状态（例如，建筑被自动修复了），屏幕不刷新。用户关闭再打开才能看到更新。

---

## 修复建议（待评估）

以下仅为选项分析，不构成实施计划：

### 方案 A：解除 shutdown 对 repair 的阻塞

最直接：在 `getBuildingsWithPendingWork()` 或 `dequeueWork()` 中，允许 repair WorkItem（`blueprintId.startsWith("build:")`）绕过 shutdown 检查。

```java
// 在 dequeueWork 中:
if (state == null) return null;
if (state.isShutdown()) {
    // 只允许 repair 任务在 shutdown 时出队
    WorkItem item = state.getTaskQueue().peekFirst();
    if (item != null && item.blueprintId().equals("build:place_structure")) {
        return state.getTaskQueue().pollFirst();
    }
    return null;
}
```

风险：NPC 会在 shutdown 建筑上执行修复，破坏了 shutdown 的"完全停用"语义。需要在 `supplyNodeBuildings()` 中确认修复期间不会触发 node 补货。

### 方案 B："修复"按钮同时触发 restart

当玩家点"修复"时，如果建筑也是 shutdown，自动先执行 restart：

```java
// BuildingActionPacket "repair" case:
if (state.isShutdown()) {
    api.restart(packet.buildingId());
}
BuildingBreakHandler.triggerRepair(player.level(), packet.buildingId());
```

风险：restart 会恢复该建筑的贡献和活跃计数。如果 shutdown 是维护费问题，restart 后可能又立刻被 DailySettlementSystem 关停。

### 方案 C：异常系统按钮联动

- 当一个异常被处理后，自动处理另一个（如果有关联）
- 点"营业"后如果 repair 已在队列中，不额外操作
- 点"修复"时如果建筑 shutdown，提示用户先点"营业"
- 或者在 UI 上将"损坏"和"关闭"合并为一条，显示两个按钮

### 方案 D：异常不在同一建筑出现两个条目

如果一个建筑同时 shutdown 且 broken，在异常列表中合并为一行：

```
Town Hall   损坏 + 关闭    [修复] [营业]
```

让玩家清楚这两个问题是同一个建筑的，需要依次解决。

---

## 总结

| 优先级 | 问题 | 影响 |
|--------|------|------|
| **P0** | shutdown 阻塞 repair 任务处理 | 修复完全无法进行 |
| P1 | 多次点"修复"导致重复 WorkItem | 资源浪费 |
| P2 | 屏幕数据不刷新 | 用户体验差 |
| P3 | blockMatchesSpec 状态匹配精度 | 偶尔误判 |
