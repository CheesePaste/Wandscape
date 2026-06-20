# 06 — NPC 系统 (`npc/`)

MC 实体 + ECS 桥接 + 施法动画 + 粒子特效。NPC 是 PathfinderMob 外壳，行为逻辑由引擎 System 驱动。

## 源文件 (8 文件)

| 文件 | 作用 |
|------|------|
| `entity/WandscapeNpc.java` | **NPC 实体**：继承 `PathfinderMob`。持有 ecsEntityId / colonyId / mana 属性 / 27 格背包。AI：FloatGoal(防溺水) + RandomStrollGoal(0.6 速度)。生命周期 `onAddedToLevel`→ECS 注册，`onRemovedFromLevel`(KILLED/DISCARDED)→ECS 移除。NBT 持久化从 ECS ManaPool 读。**施法状态** `DATA_CASTING`（synced boolean）驱动客户端动画+粒子。**施法时锁定移动**（`getNavigation().stop()` + `setDeltaMovement(Vec3.ZERO)`）。**Debug 模式** `debugCasting`：右键切换，跳过 ECS 轮询强制施法，`DATA_DEBUG_TARGET` 同步目标坐标 |
| `internal/EntityComponentBridge.java` | **核心桥接**单例：MC↔ECS 双向映射 (`npcByEcsId` / `ecsIdByUuid`)。`onNpcJoinWorld`→`CoreBootstrap.createNpc()` 注册 6 组件。`onNpcLeaveWorld`→移除组件。`syncPositions`→每 tick MC 位置→ECS Position（门控前始终执行） |
| `internal/NpcApiImpl.java` | NpcApi 实现：查询 ECS World 找殖民地 NPC/空闲 NPC。`spawnNpc` / `assignHouse` 为阶段 2 stub |
| `data/NpcDataImpl.java` | NpcData 实现 record：包装 WandscapeNpc 字段，含 `from(WandscapeNpc)` 工厂 |
| `client/WandscapeNpcRenderer.java` | 客户端渲染：HumanoidMobRenderer + WandscapeNpcModel。施法时从右手位置发射彩色射线粒子（`CastBoltParticle`），射线方向对准 `DATA_DEBUG_TARGET`（或默认朝向）。**手臂角度自适应**：根据 NPC pitch 动态计算手部 3D 位置 |
| `client/WandscapeNpcModel.java` | 自定义 HumanoidModel：`setupAnim` 中根据 `isCasting()` 抬高右臂（`xRot = -1.2 + pitchRad`），左手不动 |
| `client/CastBoltParticle.java` | **施法粒子**：`TextureSheetParticle`，静止星星状，全亮度（`getLightColor()=15728880`，等同 end_rod），最后 20% 生命缩至消失。`PARTICLE_SHEET_TRANSLUCENT` |
| `Wandscape.java`（相关部分） | `CAST_BOLT` 粒子类型注册（`SimpleParticleType`），`debugDiamondTarget` 静态字段追踪最后放置的钻石块（`BlockEvent.EntityPlaceEvent`）。粒子 provider 在 `WandscapeClient` 注册 |

## 注册项

| 注册 ID | 类型 | 所在 |
|---------|------|------|
| `wandscape_npc` | EntityType\<WandscapeNpc\> (MobCategory.CREATURE, 0.6×1.8) | `Wandscape.ENTITIES` |
| `wandscape_npc_spawn_egg` | DeferredSpawnEggItem (暗紫底 #4B0082 + 金高亮 #FFD700) | `Wandscape.ITEMS` |
| `cast_bolt` | SimpleParticleType | `Wandscape.PARTICLE_TYPES` → `CAST_BOLT` |

NPC 属性在 `onEntityAttributeCreation` 中注册。粒子 texture 为 `textures/particle/cast_bolt.png`（8×8 十字星），JSON 为 `particles/cast_bolt.json`。

## 资源

| 文件 | 作用 |
|------|------|
| `textures/entity/wizard.png` | NPC 皮肤贴图 |
| `textures/particle/cast_bolt.png` | 施法粒子贴图（8×8 十字星） |
| `particles/cast_bolt.json` | 粒子纹理引用 |
| `models/entity/wandscape_npc.json` | NPC 实体模型（复用 Player 模型） |

## ECS 组件注册

NPC 加入世界时自动注册 6 个 ECS 组件：Position / ManaPool / TaskExecutor / WandCarrier / Inventory / ColonyMember

Position 同步流：`onServerTick` → `bridge.syncPositions(world)`（始终执行，门控前）→ 引擎 tick

## 依赖

- `shared/api/NpcApi` / `shared/data/NpcData` / `shared/data/RecruitmentCandidate`
- `engine/WandscapeEngine` — 获取 World 实例
- `core/CoreBootstrap` — createNpc 工厂
- `core/component/*` — 6 个 ECS 组件
- `wand/item/WandItem` — Debug 模式装备法杖
