# 06 — NPC 系统 (`npc/`)

MC 实体 + ECS 桥接 + 施法动画 + 粒子特效 + 多皮肤变体 + 法师帽渲染层。NPC 是 PathfinderMob 外壳，行为逻辑由引擎 System 驱动。

## 源文件 (11 文件)

| 文件 | 作用 |
|------|------|
| `entity/WandscapeNpc.java` | **NPC 实体**：继承 `PathfinderMob`。持有 ecsEntityId / colonyId / mana 属性 / 27 格背包。AI：FloatGoal(防溺水) + RandomStrollGoal(0.6 速度，受 `setAiWanderingEnabled()` 控制)。`FOLLOW_RANGE=32` 用于寻路搜索范围。**皮肤变体** `DATA_SKIN_VARIANT`（synced int，首次进入世界时随机分配 0~N，持久化到 NBT `SkinVariant`）。`SKIN_VARIANT_COUNT` 自动扫描 mod jar 中 `textures/entity/wizard/` 的 png 数量。生命周期 `onAddedToLevel`→分配皮肤+ECS 注册，`onRemovedFromLevel` 按 RemovalReason 分类：KILLED/DISCARDED→释放任务+销毁 ECS（CHANGED_DIMENSION 跳过；UNLOADED_TO_CHUNK/WITH_PLAYER 保留 ECS）。NBT 持久化从 ECS ManaPool 读。**施法状态** `DATA_CASTING`（synced boolean）驱动客户端动画+粒子：真实工作模式读 `TaskExecutor.currentOpTarget`→`setDebugTarget`+`faceTarget()`，debug 模式读 `debugDiamondTarget`。**施法时锁定移动**（`getNavigation().stop()` + `setDeltaMovement(Vec3.ZERO)`）。**Debug 模式** `debugCasting`：右键切换，跳过 ECS 轮询强制施法。`doWorkAnimation(BlockPos)`：挥臂+WITCH 粒子（每 Op 完成时调用） |
| `internal/EntityComponentBridge.java` | **核心桥接**单例：MC↔ECS 双向映射 (`npcByEcsId` / `ecsIdByUuid`)。`onNpcJoinWorld`→`CoreBootstrap.createNpc()` 注册 6 组件。`onNpcLeaveWorld`→移除组件。`syncPositions`→每 tick MC 位置→ECS Position（门控前始终执行） |
| `internal/NpcApiImpl.java` | NpcApi 实现：查询 ECS World 找殖民地 NPC/空闲 NPC。`spawnNpc` / `assignHouse` 为阶段 2 stub |
| `data/NpcDataImpl.java` | NpcData 实现 record：包装 WandscapeNpc 字段，含 `from(WandscapeNpc)` 工厂 |
| `client/WandscapeNpcRenderer.java` | 客户端渲染：HumanoidMobRenderer + WandscapeNpcModel。**多皮肤**：启动时扫描 `textures/entity/wizard/` 文件夹所有 png，按文件名排序构建 ResourceLocation 数组，`getTextureLocation()` 按 `skinVariant` 索引选取。施法时从右手位置发射彩色射线粒子（`CastBoltParticle`），射线方向对准 `DATA_DEBUG_TARGET`。注册 `WizardHatLayer` |
| `client/WandscapeNpcModel.java` | 自定义 HumanoidModel：`setupAnim` 中根据 `isCasting()` 抬高右臂（`xRot = -1.2 + pitchRad`），左手不动 |
| `client/WizardHatModel.java` | **法师帽模型**：独立 LayerDefinition（`WIZARD_HAT_LAYER`），空 head + 三个子节点：`hat_brim_edge`（11×1×11 外圈）、`hat_brim_inner`（9×1×9 内圈）、`hat_body`（7×5×7 锥体 → 4×4×4 上层 → 2×3×2 尖端，层间有倾斜角） |
| `client/WizardHatLayer.java` | **法师帽渲染层**：RenderLayer，跟随父模型 head 变换。分三部分渲染：brim edge 不着色（金色纹理原色）、brim inner + body 按 `skinVariant` 着色（7 色轮转：紫/红/蓝/绿/琥珀/宝蓝/洋红）。帽身贴图为灰度+噪点纹理，运行时 tint 着色 |
| `client/CastBoltParticle.java` | **施法粒子**：`TextureSheetParticle`，静止星星状，全亮度（`getLightColor()=15728880`，等同 end_rod），最后 20% 生命缩至消失。`PARTICLE_SHEET_TRANSLUCENT` |
| `Wandscape.java`（相关部分） | `CAST_BOLT` 粒子类型注册（`SimpleParticleType`），`debugDiamondTarget` 静态字段追踪最后放置的钻石块（`BlockEvent.EntityPlaceEvent`）。`onServerStopped` → `WandscapeEngine.reset()` 清除引擎状态。粒子 provider 在 `WandscapeClient` 注册 |
| `WandscapeClient.java`（相关部分） | 注册 `WizardHatModel.createLayer()` 到 `WIZARD_HAT_LAYER` ModelLayerLocation（`EntityRenderersEvent.RegisterLayerDefinitions`） |

## 注册项

| 注册 ID | 类型 | 所在 |
|---------|------|------|
| `wandscape_npc` | EntityType\<WandscapeNpc\> (MobCategory.CREATURE, 0.6×1.8) | `Wandscape.ENTITIES` |
| `wandscape_npc_spawn_egg` | DeferredSpawnEggItem (暗紫底 #4B0082 + 金高亮 #FFD700) | `Wandscape.ITEMS` |
| `cast_bolt` | SimpleParticleType | `Wandscape.PARTICLE_TYPES` → `CAST_BOLT` |
| `wandscape:wandscape_npc#wizard_hat` | ModelLayerLocation | `WandscapeNpcRenderer.WIZARD_HAT_LAYER` |

NPC 属性在 `onEntityAttributeCreation` 中注册。粒子 texture 为 `textures/particle/cast_bolt.png`（8×8 十字星），JSON 为 `particles/cast_bolt.json`。

## 资源

| 文件 | 作用 |
|------|------|
| `textures/entity/wizard/wizard01~NN.png` | NPC 皮肤贴图（自动检测数量，按文件名排序） |
| `textures/entity/wizard_hat.png` | 法师帽贴图（64×64）：帽檐区域为亮金色，帽身区域为灰度+噪点（运行时着色） |
| `textures/particle/cast_bolt.png` | 施法粒子贴图（8×8 十字星） |
| `particles/cast_bolt.json` | 粒子纹理引用 |
| `models/entity/wandscape_npc.json` | NPC 实体模型（复用 Player 模型） |

## NPC 外观系统

### 皮肤变体

每个 NPC 首次进入世界时服务端分配随机 `skinVariant`（0 到 `SKIN_VARIANT_COUNT-1`），通过 `SynchedEntityData` 同步到客户端，持久化在 NBT `SkinVariant` 字段。`SKIN_VARIANT_COUNT` 在类加载时通过 `ModList.getModFileById().findResource()` 扫描 `textures/entity/wizard/` 目录自动检测。

新增皮肤只需在 `wizard/` 文件夹放入 png 文件，无需修改代码。

### 法师帽

帽子通过 `WizardHatLayer` 渲染在 NPC 头顶，跟随头部旋转。结构：
- **帽檐外圈** (11×1×11)：纯金色，不着色
- **帽檐内圈** (9×1×9)：按 skinVariant 着色为帽身颜色
- **帽身** (7×5×7 → 4×4×4 → 2×3×2)：三层递减锥体，层间有微小倾斜角，按 skinVariant 着色

帽身颜色 7 色循环：`#5030A0` 紫 / `#C02030` 红 / `#2060C0` 蓝 / `#30A040` 绿 / `#906020` 琥珀 / `#2040A0` 宝蓝 / `#A03080` 洋红。

## ECS 组件注册

NPC 加入世界时自动注册 6 个 ECS 组件：Position / ManaPool / TaskExecutor / WandCarrier / Inventory / ColonyMember。首次移动请求时动态添加 NavigationState 组件

Position 同步流：`onServerTick` → `bridge.syncPositions(world)`（始终执行，门控前）→ 引擎 tick

## 依赖

- `shared/api/NpcApi` / `shared/data/NpcData` / `shared/data/RecruitmentCandidate`
- `engine/WandscapeEngine` — 获取 World 实例
- `core/CoreBootstrap` — createNpc 工厂
- `core/component/*` — 6 个 ECS 组件
- `wand/item/WandItem` — Debug 模式装备法杖
- `net.neoforged.fml.ModList` — 运行时扫描 mod jar 中的皮肤文件
