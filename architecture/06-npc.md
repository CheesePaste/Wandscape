# 06 — NPC 系统 (`npc/`)

MC 实体 + ECS 桥接。NPC 是 PathfinderMob 外壳，行为逻辑由引擎 System 驱动。

## 源文件 (5 文件)

| 文件 | 作用 |
|------|------|
| `entity/WandscapeNpc.java` | **NPC 实体**：继承 `PathfinderMob`。持有 ecsEntityId / colonyId / mana 属性 / 27 格背包。AI：FloatGoal(防溺水) + RandomStrollGoal(0.6 速度)。生命周期 `onAddedToLevel`→ECS 注册，`onRemovedFromLevel`(KILLED/DISCARDED)→ECS 移除。NBT 持久化从 ECS ManaPool 读 |
| `internal/EntityComponentBridge.java` | **核心桥接**单例：MC↔ECS 双向映射 (`npcByEcsId` / `ecsIdByUuid`)。`onNpcJoinWorld`→`CoreBootstrap.createNpc()` 注册 6 组件。`onNpcLeaveWorld`→移除组件。`syncPositions`→每 tick MC 位置→ECS Position（门控前始终执行） |
| `internal/NpcApiImpl.java` | NpcApi 实现：查询 ECS World 找殖民地 NPC/空闲 NPC。`spawnNpc` / `assignHouse` 为阶段 2 stub |
| `data/NpcDataImpl.java` | NpcData 实现 record：包装 WandscapeNpc 字段，含 `from(WandscapeNpc)` 工厂 |
| `client/WandscapeNpcRenderer.java` | 客户端渲染：HumanoidMobRenderer + 玩家模型 + `wandscape:textures/entity/wizard.png` 纹理 |

## 注册项

| 注册 ID | 类型 | 所在 |
|---------|------|------|
| `wandscape_npc` | EntityType\<WandscapeNpc\> (MobCategory.CREATURE, 0.6×1.8) | `Wandscape.ENTITIES` |
| `wandscape_npc_spawn_egg` | DeferredSpawnEggItem (暗紫底 #4B0082 + 金高亮 #FFD700) | `Wandscape.ITEMS` |

NPC 属性在 `onEntityAttributeCreation` 中注册。

## ECS 组件注册

NPC 加入世界时自动注册 6 个 ECS 组件：Position / ManaPool / TaskExecutor / WandCarrier / Inventory / ColonyMember

Position 同步流：`onServerTick` → `bridge.syncPositions(world)`（始终执行，门控前）→ 引擎 tick

## 依赖

- `shared/api/NpcApi` / `shared/data/NpcData` / `shared/data/RecruitmentCandidate`
- `engine/WandscapeEngine` — 获取 World 实例
- `core/CoreBootstrap` — createNpc 工厂
- `core/component/*` — 6 个 ECS 组件
