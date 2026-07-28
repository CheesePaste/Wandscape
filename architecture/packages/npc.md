# npc/ — NPC 实体与 ECS 桥接

## 核心设计

EntityComponentBridge（单例）：双向映射 ecsEntityId ↔ MC 实体。三个生命周期回调：
- `onNpcJoinWorld` — NPC 加入世界 → ECS 创建组件
- `onNpcLeaveWorld` — KILLED/DISCARDED → 移除 ECS 组件（chunk 卸载保留）
- `syncPositions` — 每 tick MC 位置 → ECS Position

PLACEHOLDER_COLONY = UUID(全0)，允许殖民地系统完成前引擎调度正常工作。

## 注册

- 实体：`wandscape:wandscape_npc` (MobCategory.CREATURE)
- 粒子：`wandscape:cast_bolt`
- 刷怪蛋：`wandscape_npc_spawn_egg` (深紫#4B0082+金色#FFD700)

## 依赖

- shared/api/NpcApi, shared/data/NpcData
- shared/event/NpcDiedEvent
- core/component/*（通过 EntityComponentBridge 创建）
