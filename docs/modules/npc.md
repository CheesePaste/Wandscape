# npc/ — NPC 法师模块

`src/main/java/com/wsteam/wandscape/npc/`

## 职责

殖民地 NPC 法师实体：是任务系统的执行载体，通过 ECS `EquipmentComponent` 管理 7 属性与装备。实体 ↔ ECS 由 `EntityComponentBridge` 桥接。

## WandscapeNpc

- `extends PathfinderMob implements VillagerLike`。注册 `wandscape_npc`（CREATURE, 0.6×1.8, tracking 10）。
- **7 属性字段**：maxHp/moveSpeed/spellPower/workSpeed/spellSpeed/armorValue/maxMana，默认 `NpcAttributes.defaults()` = (40, 0.3, 1, 1, 1, 0, 200)。vanilla 基础属性：MAX_HEALTH 40、MOVEMENT_SPEED 0.3、ATTACK_DAMAGE 1.0、FOLLOW_RANGE 48。
- **魔力 + 施法门控**（`core/component/MagicState`）：管理当前魔力、每魔法独立 CD map、施法互斥锁。`npc.tryCastSpell(magicId, baseCD, manaCost, lockTicks)` 原子门控——互斥锁占用 / 该魔法 CD 未过 / 魔力不足任一即拒绝；成功则扣蓝、置该魔法 CD（基础/SPELL_SPEED 向上取整）、占用互斥锁 `lockTicks`。每 tick `tickRegen` 递减锁与各魔法 CD，并每 `Config.NPC_MANA_REGEN_TICKS`(10) 回 1 点魔力（封顶 MAX_MANA）。魔力上限 = 第 7 属性，首 tick 满蓝 seed。
- **脱战回血**：`markRecentlyDamaged()` 重置 `NPC_REGEN_GRACE_TICKS`(100) 封伤；`tickHealthRegen()` 封伤过后每 `NPC_REGEN_INTERVAL_TICKS`(80) 回 1 HP。
- **装备/属性权威**：ECS `EquipmentComponent` 是运行时权威。`getEffectiveAttribute` 有 ECS 则取 eq.getAttribute，否则回退 NBT transit 字段；`applyEffectiveAttributes()` 仅变化时 setBaseValue MAX_HEALTH/MOVEMENT_SPEED/ARMOR（3 个 applied 脏值防重复）。
- `tick()` 服务端：魔力回复（含互斥锁/各魔法 CD 递减）+ 脱战回血 + 属性推送恒执行；fast path ecsPollCooldown（施法中每 tick、空闲每 20 tick 才查 ECS）；从 TaskExecutor 读 casting/currentOpTarget/currentOpKind 同步到 SynchedEntityData（computeStatusText 映射移动/施法/仪式状态）。
- 交互 `mobInteract` 右键发 NpcDataPacket 打开界面。
- 生命周期：onAddedToLevel 设随机 skin/hat、**无 custom name 时自动命名**（`generateRandomNpcName` → 共享 `shared/data/CharacterNames` 池的 lang key，`setCustomName(translatable key)` 客户端按语言显示中/英文名；酒馆招募/复活的名保留）、发默认 wand、setPersistenceRequired，调 EntityComponentBridge.onNpcJoinWorld 或 deferJoin；onRemovedFromLevel 仅 KILLED/DISCARDED 时释放 global task/取消运输/销毁 ECS（CHANGED_DIMENSION 与 unload 保留）。
- NBT 存 SkinVariant/HatColor/EcsEntityId/7 属性/魔力状态（currentMana/manaRegenAccum/spellLockTicks/magicCooldowns/manaSeeded）/回血/hasDefaultWand/colonyId。
- 仇恨表 `setHatedAttacker/getHatedAttacker`。

## EntityComponentBridge

单例 INSTANCE；双向 map（ecsEntityId→NPC 与 UUID→ecsEntityId）。`PLACEHOLDER_COLONY`=全零 UUID。

- `onNpcJoinWorld`：同会话重连（chunk reload）只补 Position；否则自动探测 spawn-egg 殖民地，用 `CoreBootstrap.createNpc` 建 ECS 实体（Position、EquipmentComponent(seedBaseValues+equipDefaultWand)、TaskExecutor、Inventory(27)、ColonyMember）。
- `deferJoin` → `flushDeferredJoins`：引擎就绪后补注册，deferredInventory 补发库存。
- 每 tick `syncPositions` 把 MC 坐标写回 ECS Position（Wandscape.onServerTick 引擎 gate 前）。
- `onNpcLeaveWorld` 逐个 removeComponent；`clear()` 世界重置时清映射。

## NpcApiImpl

`getColonyNpcs(colonyId)` 遍历 bridge.allNpcs 按 ColonyMember 过滤；`getIdleNpcs` 再过滤 isIdle；`getNpc(uuid)` 经 getEcsId → NpcDataImpl.from；`assignHouse` 恒返回 false（Stage 4 未实现）。

## 网络

- `NpcDataPacket`（S→C）：信息屏，含 entityId/名字/血量/4 属性/wandStack/isDefaultWand；from() 从 ECS EquipmentComponent 读有效属性。
- `NpcEquipPacket`（C→S）：equip/unequip wand。handleEquip 校验 WandItem、读 wand preset 的 attributes（均为 ADDITION），换物品并同步 ECS eq.unequip/equip；handleUnequip 拒绝卸默认 wand，回默认 wand。

## 客户端

- `WandscapeNpcRenderer`：HumanoidMobRenderer，纹理自动检测 `textures/entity/wizard/*.png`；inline 绘制 SpeechBubbleRenderer、**名牌（白色名字在头顶，灰色状态在名字上方；override shouldShowName 抑制原版 nametag 防重名）**、施法中按 opKind 画仪式法阵或 cast ray。
- `WandscapeNpcModel`：casting 时 rightArm.xRot = CAST_ARM_ANGLE + getXRot()。
- `WizardHatModel/WizardHatLayer`：hat 几何，Layer 用 entityCutoutNoCull + hatColor 着色，brim edge 金色不着色。
- `CastBoltParticle`：固定亮星粒子，lifetime 10-15 tick，全亮。
- `NpcScreen`：MedievalScreen，装备格 + 属性区（生命/魔力条 + 移速/法术强度/工作速度/施法速度/护甲值）+ 4 行背包；点击 wand 槽发 UNEQUIP、点 WandItem 发 EQUIP。

## MageResume（shared/data/）

酒馆 100% 满意度法师留下的简历（touristName/level/7 属性/skinVariant/timestamp），构造器钳制非法值，toCandidate() 转 RecruitmentCandidate；存于 TavernRecruitStorage，每殖民地上限 5。
