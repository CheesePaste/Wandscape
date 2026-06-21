# 已解决问题存档

文档编号：NEW-98
版本：1.0
状态：所有已解决的设计问题 + 刻意设计决策的永久记录

---

## 一、已解决的设计问题

| 问题 | 方案 | 解决日期 |
|------|------|---------|
| 建筑结构三种 JSON 格式不一致 | 建造→BlockOffset；多方块→layers grid；导出工具→BlockOffset | — |
| `CompoundTag.toString()` 作 ItemKey | 改用 `CompoundTag` 直接作键 | — |
| `TaskTemplate` 未在 01 定义 | 加入 01 §2.7 | — |
| 维护成本负数→未定义行为 | 负数自动关停，使用时间加倍+产出减半 | — |
| 殖民地边界未定义 | radius 字段，默认 128 | — |
| 操作射程缺失 | `16 + wand.range × 8`，超距自动传送 | — |
| 源方块不匹配处理 | 通知玩家+任务退回/取消 | — |
| 拆除重建是否刷数值 | 已解锁类型永久标记，重建不重复贡献 | — |
| NPC 步行/传送规则模糊 | ~~<64 寻路，≥64/失败/卡死→self_teleport(Operation D)~~ → 改为直接传送（`npc.setPos()`）。寻路在动态建筑工地不可靠，路径反复作废。 | — |
| `BuildingData` 缺 `category` | 增加 `getCategory()` | — |
| `HouseApi` 缺失 | 新增 `HouseApi` 接口 | — |
| `OperationD` 不含消耗 | 增加 `manaCost` + `elementCost` | — |
| `AbilitySet` 可变 | 紧凑构造器 `Map.copyOf()` | — |
| 差量保存无细节 | 脏标记+5分钟定时+区块卸载+批量合并 | — |
| `shutdown_penalty` 含义模糊 | `output_reduction: 0.5`=减半，`time_multiplier: 2.0`=加倍 | — |
| EventBus 优先级未定义 | 默认 NORMAL，需顺序用 API | — |
| `OperationB` 缺少 `params` | 补充 `Map<String, Object> params` | — |
| `craft_wand` 误用 OperationD | 改为 OperationB | — |
| Operation B 魔力消耗未区分 | 瞬发=1，引导=ceil(ticks/20) | — |
| 招募流程不完整 | 候选人三选一+刷新+扣费延迟到执行 | — |
| 导出工具字母溢出 | 坐标→BlockState 直出 | — |
| `WandscapeApis` 静态单例 | 接受权衡，测试时 set mock | — |
| `OperationB.params` 类型安全 | 接受权衡 | — |
| 引擎退出世界后重入崩溃 "World already bootstrapped" | `WandscapeEngine.reset()` 在 `ServerStoppedEvent` 时清除全部静态状态 | 2026-06-21 |
| 建筑状态耦合在自定义方块 BE 中 | 迁移到 `BuildingSavedData` (Level SavedData)。建筑使用原版方块，NPC 通过蓝图放置。自定义建筑方块/BE 全部删除 | 2026-06-21 |
| `block_id` 字段强制自定义方块 | 从 `BuildingConfig` 和所有 JSON 中移除。`block_mapping` 全部使用原版方块 ID | 2026-06-21 |
| 右键交互依赖 `WandscapeBuildingBlock.useWithoutItem()` | 改为 `BuildingInteractHandler` 订阅 `PlayerInteractEvent.RightClickBlock` + `BuildingSavedData.posIndex` O(1) 查找 | 2026-06-21 |
| `computeClearOffsets` 跳过 anchor 保护 BE | 移除跳过逻辑。anchor 也是原版方块，应该被 clear | 2026-06-21 |
| 仓库物品存储在 `WarehouseBE` 中，方块破坏导致数据丢失 | 创建 `ColonyItemBank` (Level SavedData)。`WarehouseBE` 精简为 GUI 终端。物品数据独立于方块，方块破坏不丢失物品 | 2026-06-21 |
| `WarehouseBE` 继承 `AbstractWandscapeBE` 仅复用 NBT/colonyId | `WarehouseBE` 改为直接继承 `BlockEntity`。colonyId/shutdown 从 `BuildingSavedData` 查询，物品存储迁移到 `ColonyItemBank` | 2026-06-21 |
| 建筑注册无 AABB 重叠检测 | `BuildingSavedData.register()` 使用 MC 原生 `BoundingBox.intersects()` 检测重叠，冲突时抛出 `BuildingOverlapException` | 2026-06-21 |
| `build_complete` 事件缺乏 anchor 信息 | 在 `build_place_structure.json` 的 `emit_event` data 中增加 `"anchor": "$anchor"` | 2026-06-21 |
| `BuildingApiImpl.getBuildingsWithPendingWork()` 无区块感知 | 增加 `level.isLoaded(state.anchor)` 检查，未加载区块的建筑跳过 | 2026-06-21 |
| `RitualOp.channelTicks()` 死代码，self_teleport 瞬间传送 | `WandscapeRitualOps` 改为 V2.5 异步引导：PendingRitual 队列 + tickAll 倒计时 → thenRun 执行。self_teleport 600 ticks 引导后传送 | 2026-06-21 |
| `SchedulerSystem.score()` 使用静态 `maxRange`，无视 NPC 到任务的距离 | 改为 distance-based：`proximity = 10/(10+水平距离)` ×0.5。距离 = 任务序列首个 target() 到 NPC 位置的水平距离。`extractTaskTarget()` 从序列提取目标 | 2026-06-21 |
| 魔力不足时私有队列操作导致 NPC 卡住狂挥手臂 | `TaskExecutionSystem` 魔力失败路径：`isPrivate` 时设置 `state=IDLE` + 清除视觉字段。NPC 恢复 AI 漫游，私有操作等待魔力回复后重试 | 2026-06-21 |
| 退出世界重进后 `BlueprintRegistry` 丢失 DSL 蓝图 | `WandscapeEngine.reset()` 移除 `blueprintConfigLoader = null`。该单例由 `WandscapeDataLoader` 管理，不受引擎生命周期影响 | 2026-06-21 |
| 退出世界重进后多个 NPC 同时显示施法动画（纯客户端 bug） | `EntityComponentBridge`: reconnection 检查增加 UUID 匹配前置条件 + `clear()` 方法。`Wandscape.onServerStopped()` 调用 clear。防止跨会话 ECS 实体 ID 碰撞导致多 NPC 共享 TaskExecutor | 2026-06-21 |
| `DATA_OP_KIND` / `DATA_DEBUG_TARGET` 每 tick 无条件 set 导致不必要网络包 | 添加 `lastSyncedOpKind` / `lastSyncedTarget` 脏检查字段。只有值变化时才调用 `entityData.set()` | 2026-06-21 |
| 闲置 NPC 每 tick 查询 ECS TaskExecutor（无效 HashMap 查询） | `tick()` 添加 `ecsPollCooldown`：施法时每 tick 轮询，闲置时每 20 tick 轮询一次。减少 ~95% 闲置 ECS 查询 | 2026-06-21 |
| `WandscapeNpcRenderer.render()` 粒子每渲染 pass 重复生成 | 添加 `lastParticleTick` 节流：同一 game tick 内只生成一次粒子，避免多 pass 重复 | 2026-06-21 |
| 退出世界重进后任务池清空，做到一半的任务全部丢失 | 实现 `TaskPoolSavedData` (SavedData) 跨会话持久化：保存 blueprintId + taskParams + stepIndex + state → NBT。世界重载时从蓝图重新编译、恢复进度。IN_PROGRESS → PENDING_ASSIGN 重新分配 | 2026-06-21 |

---

## 二、已决策（待实现时处理）

| 问题 | 决策 |
|------|------|
| 魔力恢复速率过高 | 后续优化，MVP 保持现状 |
| 节点建筑队列容量 10 但只用 1 个槽 | 容量保留 10——玩家可手动向队列添加采集任务，容量为玩家批量操作预留 |
| `mana_efficiency` 命名反向 | 改为 `mana_cost_multiplier`（1.0=标准消耗，0.3=30%消耗） |
| 缺乏模组配置文件 | 添加 `config/wandscape-common.toml`（NeoForge `ModConfigSpec`） |
| `getColonyId` O(n) | 维护 `Map<ChunkPos, Set<UUID>>` 缓存 |
| 元素解锁"特殊事件"未设计 | P0 简化为奇观值达阈值自动解锁节点建造权限，事件系统后续考虑 |
| 水元素无 MVP 产出 | MVP 添加水域节点 |
| NPC 死亡无坟墓 | MVP 不实现坟墓（减少工作量），物品以掉落物形式散落 |
| 复活仪式 ritual:3 | 降低到 ritual:1——所有 NPC 默认拥有，死亡后可被任意 NPC 复活 |
| 远程建造半透明投影 | MVP 仅渲染矩形边界线框（告知玩家位置），不实现半透明方块填充 |
| 小地图地形色块 | 降级为仅显示建筑/NPC 图标，不渲染地形 |
| 配方 cost 定义 | cost 为一个物品的消耗；工作站单任务处理 N 个物品时总消耗 = cost × N |

---

## 三、刻意设计（非缺陷，不要改动）

| 设计 | 理由 |
|------|------|
| ElementType 含 9 种但 MVP 用 3 种 | 架构预留 |
| BehaviorType 含未用枚举值 | 架构预留 |
| `OperationB.params` 用 `Map<String, Object>` | MC 惯例 |
| 节点不设独立冷却 | 节奏由执行时间自然控制 |
| 建筑结构不主动轮询 | 事件触发 |
| 元素不可互相转化 | 唯一路径：方块→工作站→元素→工作站→方块 |
| 法杖永不损坏 | 核心物品不消耗 |

---

## 四、维护规则

- 当 `docs/99-open-questions.md` 中未解决问题被解决 → 将记录移至本文档 §一
- 当某项决策被确定为"刻意设计" → 移至本文档 §三
- 本文档是**只增不减**的存档，历史记录永久保留
- 如果后续版本推翻某个设计 → 在对应行末尾追加说明，不删除原记录
