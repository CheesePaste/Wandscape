# scepter/ — 玩家权杖模块

`src/main/java/com/wsteam/wandscape/scepter/`

## 职责

玩家手持的 4 把权杖：和平 / 跟随 / 庇护 / 敌对。合成站 1 级配方产出，3D 模型与 NPC 法杖共用模板、仅头部按主题色染色。与 NPC 法杖（`wand/`，属性法器）不同——权杖是**右键行为法器**（指挥本殖民地法师）。

## 物品

- `ScepterItem extends Item implements MageWandItem`：持 `ScepterKind`；右键法师经 `MageWandItem` 接口由 `WandscapeNpc.mobInteract` 转交（非潜行分支，`!isShiftKeyDown()`）；tooltip 显示用途。注册 `wandscape:peace_wand / follow_wand / shelter_wand / hostile_wand`，创造栏补发。
- `OmniScepterItem extends Item implements MageWandItem, NpcBindingItem`：**一杖四模式**（复用 `ScepterKind` 作模式枚举），模式存物品 `CUSTOM_DATA["mode"]`（默认 PEACE）；shift+右键循环模式，右键执行当前模式。注册 `wandscape:omni_scepter`，合成站 10 级配方。
- `ScepterKind`：PEACE/FOLLOW/SHELTER/HOSTILE + 主题色（`themeColor`，ItemColors tintindex 0 染头部）。

## 交互分派

- **法师（本殖民地）**：`WandscapeNpc.mobInteract` L1334 后加 `held instanceof MageWandItem → onInteractNpc + return CONSUME`。四把都适用。潜行+权杖仍走默认（非 NpcBindingItem → openMenu）。**万能权杖例外**：它同时实现 `NpcBindingItem`，潜行右键法师走 `onShiftClickNpc` → 循环模式（复用既有潜行 seam，不改 mobInteract）。
- **非法师/非本殖民地生物**：`ScepterInteractHandler`（`PlayerInteractEvent.EntityInteract`）——手持 SHELTER/HOSTILE 权杖（或万能权杖处于庇护/敌对模式）、目标为 `LivingEntity && !Player`、非本殖民地法师（含 EvilMage/非法师生物）时 `setCanceled(true)+setCancellationResult(SUCCESS)`，两端一致屏蔽喂牛/驯狼，服务端业务执行。万能权杖潜行（循环模式）与庇护/敌对模式均接管；和平/跟随模式（非潜行）与基础权杖一样对生物放行 vanilla。本殖民地法师与玩家一律放行（走 mobInteract / 不干涉）。**只订阅 EntityInteract，不订阅 EntityInteractAt**（后者先到且默认 PASS）。
- **空气/方块**：`OmniScepterItem.use()`——潜行则循环模式，非潜行 `PASS`（执行需目标，法师/生物走各自 seam）。

## 行为

- **和平/跟随**：`ScepterService.togglePeace/toggleFollow` 直调 `WandscapeNpc.setPeaceMode/setFollowMode+setFollowerUuid`（服务端权威，与面板 NpcTogglePacket 同语义）；开和平顺带 `clearHatedAttacker`。
- **庇护**：把生物 UUID 写入本殖民地庇护名单 → `WandscapeNpc.isFriendlyForce` 早退返回 true（守卫/光束/陨石/自防御/跟随攻击全经它过滤，法师不主动攻击/不误伤/不触发守卫发布）；再次右键同生物解除。
- **敌对**：本殖民地**单槽**强制仇恨目标（`forcedHostile`）——`SelfDefenseExecutor.resolveTarget` 与 `GuardAttackExecutor` 选目标处最高优先（`HostileMarkDecision` 判定：目标存活 + 距 ≤128），期间不被其它生物吸引；右键另一生物转移、右键当前/目标死亡（`ScepterDeathHandler` LivingDeathEvent）自动解除；与庇护同目标互斥（标记时自动撤庇护）。

## 范围归属（本殖民地）

四把都只指挥玩家自己殖民地的法师：和平/跟随要求目标法师属于自己殖民地（`ColonyApi.getColonyByFounder`）；庇护/敌对要求玩家有殖民地（标记存该殖民地名下）。无殖民地/跨殖民地拒绝并上屏反馈（`message.wandscape.scepter.*`）。

## 存储与持久化

- `ScepterMarks`（纯 Java 可单测）：`Map<colonyId, ColonyMarks>`；`ColonyMarks` = `Set<UUID> sheltered` + `UUID forcedHostile`（单槽）；`toggleShelter/isSheltered/isShelteredForAny/toggleForcedHostile/clearForcedHostileByEntity`；含 `toNbt/loadFromNbt`。
- `ScepterMarksSavedData`（overworld SavedData，仿 `OathRingSavedData`）：按殖民地名下长期持久化（退出重进依然生效）；任何变更 `setDirty()`；`Wandscape.java` ServerStarted 预载。

## 共享 API

- `shared/api/ScepterApi`：`isSheltered(colonyId, uuid, level)` / `isShelteredForAny(uuid, level)` / `forcedHostile(ServerLevel, colonyId)`；实现 `ScepterApiImpl`，仅 `Wandscape` 装配点 `WandscapeApis.setScepterApi` 一次。npc/guard 经 `WandscapeApis.getScepterApiSilently()` 读取（模块隔离）。
- `shared/api/MageWandItem`：非潜行右键法师钩子（与 `NpcBindingItem` 潜行钩子区分）。

## 配置

- `Config.scepter.hostileRange`（默认 128）：敌对权杖强制仇恨作用范围（该殖民地法师距目标 ≤ 此值集火）。

## 与其他模块关系

- 右键法师：`WandscapeNpc.mobInteract`（接口 `MageWandItem`，非潜行）。
- 庇护集成：`WandscapeNpc.isFriendlyForce`（经 `ScepterApi` 查庇护名单）。
- 敌对集成：`SelfDefenseExecutor.resolveTarget` / `GuardAttackExecutor.runCycle`（经 `ScepterApi.forcedHostile` + `HostileMarkDecision`）；`GuardTaskSource.findThreat` 过滤庇护生物（不触发守卫发布）。
- NPC 行为消费（peace/follow 字段、守卫/自防御）全部复用既有机制，无第二套。
