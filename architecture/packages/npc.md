# npc/ — NPC 实体与 ECS 桥接

## 关键类

- **WandscapeNpc** (entity/) — MC 实体，继承 PathfinderMob。属性：maxMana/manaRegenRate/spellPower/colonyId。持有 ecsEntityId 用于桥接。NPC 死亡/消失时由 EquipmentComponent 处理装备回收（`returnEquippedWands()` 已删除）
- **EntityComponentBridge** (internal/) — **核心桥接单例**。双向映射 ecsEntityId↔MC实体。三个生命周期回调：
  - `onNpcJoinWorld` — NPC加入世界→ECS创建组件（EquipmentComponent 初始无装备）
  - `onNpcLeaveWorld` — KILLED/DISCARDED→移除ECS组件（chunk卸载保留）。EquipmentComponent 提前处理装备回收
  - `syncPositions` — 每tick MC位置→ECS Position
- **NpcApiImpl** (internal/) — NpcApi 实现，通过 bridge 查询
- **NpcDataImpl** (data/) — NpcData 只读视图实现

## 客户端

- WandscapeNpcRenderer — 渲染 NPC 模型+粒子节流
- WandscapeNpcModel — 人体模型
- WizardHatLayer/WizardHatModel — 巫师帽装饰层
- CastBoltParticle — 施法粒子特效

## 注册

- 实体：`wandscape:wandscape_npc` (MobCategory.CREATURE)
- 粒子：`wandscape:cast_bolt`
- 刷怪蛋：`wandscape_npc_spawn_egg` (深紫#4B0082+金色#FFD700)

## 占位殖民地

`EntityComponentBridge.PLACEHOLDER_COLONY` = UUID(全0)。阶段2占位，允许殖民地系统完成前引擎调度正常工作。

## 依赖

- shared/api/NpcApi, shared/data/NpcData
- shared/event/NpcDiedEvent
- core/component/*（通过 EntityComponentBridge 创建）
