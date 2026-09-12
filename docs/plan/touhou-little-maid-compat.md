# 车万女仆（Touhou Little Maid）兼容 —— 可行性与分阶段方案

> 创建日期：2026-09-12 | 对应分支：`newGuide`（未开工，本文只做可行性分析）
> 性质：可行性评估 + 分阶段路线。阶段一（盟友 + 工作模式）拟实现，阶段二（法师小屋 / 法杖施法 / 策略槽）**只评估不实现**。
> 关联：[架构决策记录](../adr.md)、[逐域避坑](../domain-notes.md)
> 参考源码：`_refs/TouhouLittleMaid`（TLM 1.5.3-neoforge+mc1.21.1 完整检出）、`run/mods/[车万女仆] touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar`

---

## 一、目标与分期

| 期 | 目标 | 状态 |
|---|---|---|
| **阶段一** | ① **本小镇的女仆**默认属于盟友（殖民地 NPC 不攻击、不溅射误伤）；别人小镇的、无主的女仆不是盟友。**默认配置下已自动成立，零代码**（§3.1）。② 女仆可**进入工作模式**，工作模式下和法师一样参与城镇建设、接取任务。 | ① 已成立，待实测；② 本文评估，待实现 |
| 阶段二 | ③ 女仆可进入**法师小屋**升级训练。④ 手持法杖时能**普攻 + 释放法术**。⑤ 有和 NPC 一样的**策略槽**。 | 只评估，不实现 |

阶段一是阶段二的前置：两者共用同一个"把第三方实体接进殖民地系统"的抽象缝（§3.3）。先把缝抽对，阶段二才不至于二次返工。

---

## 二、结论速览

**可行性：高。** 两侧都留了正门：

- TLM 侧有官方扩展面（`@LittleMaidExtension` + `ILittleMaid#addMaidTask`），第三方新增"女仆任务"是其一等公民用法，**不需要 mixin、不需要改 TLM**。
- Wandscape 侧的耦合虽然散布（37 处 `instanceof WandscapeNpc`），但**任务执行链的实体访问面意外地窄**——边界执行器只用到约 **11 个**实体专有方法（§3.3 表）。抽一个 `ColonyWorker` 接口即可把工作链解锁，无需重写 ECS。

**难度：阶段一 中等偏大但不失控。** 第一步（盟友）**默认配置下零代码已成立**，只需实测确认；第二步是**行为不变的纯重构**，`./gradlew build` 即可验证；第三步才引入 TLM 依赖与女仆适配。

**阶段二难度：大。** 施法层与策略槽的实体类型写死在 4 个施法入口 + 3 个菜单/网络入口 + 1 个 UUID 解析器上，且女仆没有承载"7 项殖民地属性"的 vanilla 属性表（§4.2）。属于独立立项量级，本文只给出改造清单与风险。

---

## 三、阶段一：盟友 + 工作模式

### 3.1 盟友：默认配置下**已自动满足**，本步零代码

**结论**：在**默认配置**下，「只认自己小镇的女仆」**已经成立**，不需要写任何代码。

原因链（逐环已核实）：有主女仆落到既有的 `PET` 分支（`WandscapeNpc.java:343`），而 `Config.PVP` **默认为 `true`**（`Config.java:325`），`PET` 的判定是 `pvp ? sameColony(selfColony, otherColony) : true`（`FriendlyForce.java:65`），其中 colony 由 `pvpColony(ownerUuid)`（`WandscapeNpc.java:364-368`）在 PVP 开启时用 `ColonyApi.getColonyByFounder` 解析出**主人的殖民地**。

| 实体 | 默认配置（`npc.pvp = true`）下的判定 |
|---|---|
| 本殖民地玩家的女仆 | PET + 主人殖民地 → 同殖民地 → **友军** |
| 别人小镇玩家的女仆 | PET + 主人殖民地 → 不同殖民地 → **非友军** |
| 主人无小镇的女仆 | `pvpColony` → null → `sameColony(本镇, null)` 为假 → **非友军** |
| 无主 / 未驯服女仆 | `getOwnerUUID() == null` → 不入 PET → `OTHER` → **非友军** |

成立前提（均已核实）：

- `EntityMaid extends TamableAnimal`（`_refs/.../entity/passive/EntityMaid.java:172`），`TamableAnimal` 实现 `OwnableEntity`；
- 驯服走 `this.tame(player)`（`EntityMaid.java:688`），vanilla `TamableAnimal#tame` 会写入 owner UUID；TLM **未覆写** `getOwnerUUID()`（只覆写了 `getOwner()`，`:2719`）；
- 女仆不是 `Enemy`；`IronSpellsCompat.getSummoner`（`compat/ironspellbooks/IronSpellsCompat.java:51-54`，非 `IMagicSummon` 即 null）与 Goety 的 `getMasterOwner` 都不认领它 → `summoner == null` 成立。

**因此本步只需实测确认**（见 §五 验证项 1），不改代码。

**反例：不要注册 `registerAlly`。** `FriendlyForceApi` 登记的是 `EXTERNAL_ALLY`，`FriendlyForce.java:66` 让它恒为友军——在**默认的 PVP 开启**下反而会把**所有**女仆（含别人家的、无主的）变成你殖民地的友军，比现状更差。

#### 3.1.1 已知问题（既有，非本次引入）：`npc.pvp = false` 时玩家侧全局友军

`Config.PVP` 自述原文（`Config.java:315-318`）：「**false 时（原行为）所有玩家及玩家侧宠物/召唤物恒为友军**」。

即：只有当玩家显式把 `npc.pvp` 改成 `false`，`PLAYER` / `PLAYER_SUMMON` / `PET` 三类的殖民地语义才被整体丢弃（`isAlly` 取 `true` 分支），这时
**别人的宠物、召唤物、玩家本人**——以及**女仆**——全部成为所有殖民地的恒友军。

这是**被文档化的既有设计**（保留 PVP 系统引入前的旧行为作可回退档），**女仆不是特例**（狼/猫/马/铁魔法召唤物同理），也不是本次兼容引入的缺陷。但按「只认自己小镇的女仆」的口径它确实是错的，且影响面远大于女仆。

**本次处置：不改。** 理由——不在 TLM 兼容里顺手动全局友军语义，避免风险外溢到所有玩家侧实体。

**待办记录**：
- **待办 A**：`npc.pvp = false` 时玩家侧实体（玩家/宠物/召唤物/女仆）跨殖民地恒友军，与「殖民地级归属」语义冲突。修法则需重新定义 `pvp = false` 的语义（是"无 PVP 也不跨镇友军"还是维持原样），属独立决策。**已同步记入 [逐域避坑 §一.6](../domain-notes.md)**（改友军判定的第一落点），免得只留在这份 TLM 文档里被漏掉。
- **待办 B**：`EXTERNAL_ALLY` 恒友军、无殖民地维度，第三方模组无法登记「殖民地级友军」。若将来出现第二个这样的需求，再给 `FriendlyForceApi` 加殖民地级重载；现在不为单个自用场景扩公共 API（硬规则 6）。

**「非友军」不等于「会被主动攻击」**：殖民地 NPC 的索敌需要目标是 `Enemy`、被敌对权杖标记、或处于记仇状态；`EntityMaid` 都不是。所以别人家的/无主的女仆对殖民地而言是**中性**——不受友军规则保护（会吃法术溅射、会被迫还手、可被敌对权杖标记），但不会被主动追杀。这正是"不是盟友"的应有含义。

---

### 3.2 工作模式的核心矛盾

殖民地的工作流水线是 **ECS 驱动**的，不是 goal 驱动的：

```
建筑队列 → TaskRequest → GlobalTaskPool
                              ↓
        SchedulerSystem 查询 {Position, TaskExecutor, NpcInventory, ColonyMember}
                              ↓  NpcTaskPackage 塞进 TaskExecutor.npcQueue
        TaskExecutionSystem → OpExecutor.execute(op, world, ecsId)
                              ↓  MC 边界执行器
        EntityComponentBridge.getNpc(ecsId) → WandscapeNpc → 走位/挖放/动画
```

关键事实（均已核对行号）：

| 环节 | 位置 | 对实体类型的假设 |
|---|---|---|
| ECS 实体登记 | `content/npc/internal/EntityComponentBridge.java:54` | `Map<Long, WandscapeNpc>` 写死双向表 |
| 登记入口 | `WandscapeNpc.java:1747`（仅 `isColonyNpc()` 为真时）+ `:1780` 注销只看 `RemovalReason` | 女仆不走这两个回调 → 永不进 ECS |
| ECS 建工 | `impl/CoreBootstrap.java:103-114` | `Position + TaskExecutor + NpcInventory + ColonyMember`，**纯 ECS 组件，与实体类型无关** |
| 派活筛选 | `content/task/scheduler/SchedulerSystem.java:54` | 组件查询；`:61` `entityOps.isNpcAlive(ecsId)` 经桥反查 |
| 实体存活判定 | `boundary/WandscapeEntityOps.java:82-85` | 经 `getNpc(ecsId)`，女仆会被判"幽灵 NPC"跳过 |
| 移动 | `boundary/WandscapeMovementOps.java:28` + `content/npc/system/NavigationSystem.java:76/130/239` | 368 行写死 `WandscapeNpc`，驱动 `getNavigation().moveTo(...)` |
| 挖放/搬运/仪式 | `boundary/WandscapeBlockInteractExecutor`、`AsyncTransformExecutor`、`ResourceRequestExecutor`、`WandscapeRitualOps` | 均经 `getNpc(ecsId)` |

**好消息**：`OpExecutor` 接口本身就是解耦的——`OpExecutor.execute(T op, World world, long npcId)`（`content/task/op/executor/OpExecutor.java:32`）只收 `long ecsId`，**原子操作的类型系统里没有实体类型**。类型墙只在"ecsId → 实体"这一个解析点上。

**因此不需要重写调度器**：只要把解析点泛化，女仆挂上那 4 个组件就能被正常派活，且任务的资源结算、进度同步、殖民地仓库产出**全部自动复用**。

---

### 3.3 方案：抽一个 `ColonyWorker` 缝

实测边界执行器用到的实体专有方法**只有这些**（逐文件扫 `npc.xxx(` 得到）：

| 方法                                                                                                        | 用途          | 女仆可否满足                                                                            |
|-----------------------------------------------------------------------------------------------------------|-------------|-----------------------------------------------------------------------------------|
| `level()` / `blockPosition()` / `getX/Y/Z()` / `isRemoved()` / `getRandom()`                              | 通用          | 是（`Entity` 已有）                                                                    |
| `getNavigation()` / `teleportTo()` / `isInWater()` / `onGround()` / `maxUpStep()`                         | 导航          | 是（`PathfinderMob` 已有）                                                             |
| `setAiWanderingEnabled(boolean)`                                                                          | 抑制游荡        | **否** → 女仆侧转译为"工作时不随机走动"（TLM `IMaidTask#enableLookAndRandomWalk` 返回 false）        |
| `doWorkAnimation(BlockPos)`                                                                               | 挥手 + 粒子     | 否 → 适配器实现（`maid.swing(MAIN_HAND)` + 同款粒子；建议把 `WandscapeNpc.java:2086` 的粒子段抽成共用工具） |
| `isFollowMode()` / `isResting()` / `isPeaceMode()`                                                        | 调度门槛        | 否 → 女仆恒 `false`（工作模式下）                                                            |
| `getFollowerPlayer()`                                                                                     | 跟随者解析       | 否 → 女仆返回主人或 null                                                                  |
| `getCurrentMana()` / `getMaxMana()` / `getEffectiveAttribute(AttributeType)` / `getEffectiveArmorValue()` | 魔力门槛与工作速度评分 | 否 → 读女仆自有状态容器（§3.5）                                                               |

合计约 **11 个专有方法** + 一组 `Entity` 通用方法。抽成：

```java
// content/npc/worker/ColonyWorker.java（新）
public interface ColonyWorker {
    UUID workerId(); Level level(); BlockPos blockPosition();
    double getX(); double getY(); double getZ(); boolean isRemoved();
    boolean isFollowMode(); boolean isResting(); boolean isPeaceMode();
    @Nullable UUID followerUuid();
    // 导航：两种机制
    void applyWalkTarget(GridPos target);  boolean isNavigationDone();  void stopNavigation();
    boolean isInWater(); boolean onGround(); float maxUpStep();
    boolean teleportTo(double x, double y, double z);
    void doWorkAnimation(BlockPos target);
    // 资源/属性（女仆读自有容器）
    float getCurrentMana(); float getMaxMana();
    float getEffectiveAttribute(NpcAttributes.AttributeType type);
    float getEffectiveArmorValue();
    boolean tryEscapeTeleport(/* 脱困自传送 */);
}
```

- `WandscapeNpc implements ColonyWorker`：全部委托现有实现，**行为零变化**。
- `EntityComponentBridge`：字段泛化为 `Map<Long, ColonyWorker>`；新增 `getWorker(long)` 与 `onWorkerJoinWorld/onWorkerLeaveWorld`。**保留 `getNpc(long)` 现状语义**（只返 `WandscapeNpc`，女仆返回 null），这样 37 处 `instanceof WandscapeNpc` 的交互/网络/UI 路径**一行不动**；只有工作链（5 个 boundary 执行器 + `NavigationSystem` + `WandscapeEntityOps`）切到 `getWorker()`。

**导航的两种机制（关键设计点）**：`WandscapeNpc` 由 `NavigationSystem` 直接 `getNavigation().moveTo(...)`；但 TLM 女仆的移动由 **Brain** 驱动（`WalkTarget` 记忆 + 行为）。若对女仆也用同一套 `moveTo`，会与 TLM WORK activity 里硬编码的 `MaidStealEdibleMoveBlockTask`(优先级 8) 等行为**争抢导航**。推荐：

- 女仆侧 `applyWalkTarget` **直接写 `WALK_TARGET` 记忆**（`BehaviorUtils.setWalkAndLookTargetMemories`，与 TLM 自己的移动任务同一通道），由 CORE 活动的 `MoveToTargetSink` 驱动导航。**不要直接 `getNavigation().moveTo()`**——理由见 §五 验证 3/4：写 `WALK_TARGET` 会让 TLM 那一族移动任务（声明 `WALK_TARGET ABSENT` 为前提）无法启动，互斥免费拿到；直接驱动则留着记忆缺席，TLM 的任务照跑照抢。
- `NavigationSystem` 的卡死检测 / 净逼近判据 / 脱困自传送（`NavigationSystem.java:130-360`）**逻辑保留**，只把"施加移动"与"读位置"两处下沉到 `ColonyWorker`。女仆的 `tryEscapeTeleport` 可返回 false（不做自传送）或映射到 TLM 的传送。

**这一步是纯重构、行为不变**，`./gradlew build` + 现有 NPC 玩法实测即可验证，不引入任何 TLM 依赖。这是本方案最重要的风险隔离。

**对外 API（已实现）**：缝抽好后，顺手把"让别的生物当工人"开成公开契约——新增 `api/ColonyWorkerApi`，**只暴露 5 个方法**（`enlist` / `dismiss` / `isEnlisted` / `getWorkerColony` / `getWorkerEcsId`），内部 `ColonyWorker` **不公开**，通用适配器 `MobColonyWorker` 也留在 `content/npc/worker/`。别家模组一行 `enlist(colonyId, myCreature)` 即可让自己的生物干活，走的是与法师完全相同的链路。选"薄登记面"而非"把 17 个方法的适配器接口搬进 api/"的理由：公开面小、以后好改，且唯一已知的非平凡适配器（车万女仆）本来就在我们自己的 `compat/` 里，能直接实现内部接口。

`MobColonyWorker` 的明确语义与限制（已写进 `ColonyWorkerApi` 的 javadoc，登记方需自行确认可接受）：走位走原版寻路；属性取中性常量（工作速度 1、魔力 0、护甲取原版有效值）；**登记期间关掉该生物 `goalSelector` 的 MOVE 控制位**（否则它自己的游荡/逃跑会与工作走位打架，代价是它不再自主追击或逃跑，`dismiss` 时恢复）；不支持脱困自传送。

---

### 3.4 女仆侧：进入 / 退出工作模式

**入口用 TLM 官方任务机制**，而不是我们自己造开关——玩家在女仆 GUI 里选任务就是"进入工作模式"，语义与 TLM 一致：

| 项 | 做法 | 依据 |
|---|---|---|
| 扩展注册 | `@LittleMaidExtension public class TlmExtension implements ILittleMaid`，覆写 `addMaidTask(TaskManager manager)` | `api/ILittleMaid.java:51`；ASM 扫描 + 无参构造（`util/AnnotatedInstanceUtil.java:23-38`） |
| 注册时机 | TLM 在 `FMLCommonSetupEvent` 调 `TaskManager.init()`，之后任务表 `ImmutableMap.copyOf` **冻结** | `entity/task/TaskManager.java:59-64` |
| 任务本体 | `implements IMaidTask`：`getUid()` / `getIcon()` / `getAmbientSound()` / `createBrainTasks()` 四个必需方法 | `api/task/IMaidTask.java:36/43/52/60` |
| 关随机走动 | `enableLookAndRandomWalk(maid)` 返回 false（工作时） | `IMaidTask.java:101`；消费点 `MaidBrain.java:144` |
| 注册/注销 ECS | **不用 task 生命周期钩子**（TLM 的 task 无 onStart/onStop）→ 用对账式 sweep：每 N tick 扫注册表，任务已非我们的女仆注销、任务为我们且**主人殖民地可解析**的注册（§3.6） | TLM 提供 `MaidTickEvent`（`api/event/MaidTickEvent.java:7`，每 tick 可取消） |
| 工作配置界面 | `getTaskConfigGuiProvider(maid)` 返回自定义 `MenuProvider`（TLM 内置 `TaskFeedAnimal` 就是这么做的） | `IMaidTask.java:196`；先例 `entity/task/TaskFeedAnimal.java:111` |

`createBrainTasks`：**不需要移动行为**——工作移动由 `NavigationSystem` 经 `applyWalkTarget` 直接写 `WALK_TARGET` 记忆（§3.3），CORE 活动的 `MoveToTargetSink` 负责落地。返回列表可以极简（甚至空列表，只靠 `enableLookAndRandomWalk` 等开关控制工作态）。

**不要**复用 TLM 的 `MaidShootTargetTask` 之类战斗行为——工作模式下女仆不参与战斗（战斗是阶段二的事）。

**对账式 sweep 而非精确生命周期**的理由：TLM 的 `EntityMaid#setTask`（`EntityMaid.java:2344`）会 `refreshBrain` 重建整个 WORK activity，但没有回调解绑；女仆又可能被魂符收走、被其它模组杀死、区块卸载。定期对账（比较"应当注册"与"实际注册"）比追每一条消失路径可靠得多，也符合仓库"所有可能失败路径必有兜底"的硬规则。

---

### 3.5 女仆的殖民地属性与状态载体

**问题**：`WandscapeNpc.getEffectiveAttribute(type)` 读的是 **vanilla `AttributeMap`**（`WandscapeNpc.java:141` → `getAttribute(attr).getValue()`），而 7 项属性里 6 项是本模组注册的自定义属性（`content/npc/WandscapeAttributes.java:26-42`）。`EntityMaid` 的属性表由 TLM 提供，**不含我们的自定义属性**，`getAttribute` 返回 null。

**结论：不要试图把属性写进女仆的 vanilla 属性表。** NeoForge 的 `EntityAttributeCreationEvent` 只在注册期生效，无法事后替换一个已注册 EntityType 的属性供给器；用反射改 `AttributeSupplier` 又撞上仓库 2026-08-29 的"彻底移除反射镜像"过审决策（`docs/adr.md:50`）。

**方案**：女仆的 7 项殖民地属性 + 魔力 + 冷却**全部存进我们自己的状态容器** `MaidColonyState`，由 `MaidColonyWorker.getEffectiveAttribute()` 读取。共用 `NpcAttributes.computeEffective(type, base, level, equipBonus)` 这套纯函数（`content/npc/attributes/NpcAttributes.java:263`），所以升级/训练/装备加成的数学与 NPC 完全一致。

**持久化载体（已验证，采用方案 A）**：

| 方案 | 结论 |
|---|---|
| **A. NeoForge `AttachmentType`（Data Attachment）** | **采用。** 已核实附件在 `Entity` 层序列化（`Entity.java:131/1795/1883`，见 §五 验证 2），`EntityMaid` 同样生效；随实体 NBT 持久化，无需自管清理 |
| B. 自建 `SavedData`：`Map<UUID, MaidColonyState>` | 备选（若将来发现附件与 TLM 的实体复制/魂符路径冲突）。缺省不用——需自管生命周期（女仆被移除/魂符收走/换维度要清理） |

无论走哪条，**按硬规则 7 存 `version` 顶层走显式迁移**（附件内的 NBT 同样适用）。

---

### 3.6 归属殖民地：直接进主人的殖民地

**裁定（D2）**：女仆进**主人的殖民地**——`ColonyApi.getColonyByFounder(maid.getOwnerUUID())`，与 §3.1 的盟友判定**同一个解析、同一个口径**。

相比"按位置解析最近殖民地"，这条更好：

- **稳定**：不随女仆走动漂移（位置方案会在她跑出 256 格时静默换镇、跑太远时静默掉出）。
- **自洽**：一个女仆**是她盟友的那个殖民地**的工作者——盟友判定与工作归属不会打架。这是选它的主要理由。
- **主人离线仍可解析**：`getOwnerUUID()` 是持久化字段（§3.1 已核实）。
- 与 `pvpColony` 口径一致，代码上就是复用它已经用过的那次 `getColonyByFounder` 查询。

边界情况：

| 情况 | 结果 |
|---|---|
| 主人是本殖民地创始人 | 进该殖民地，成为工人 |
| 主人不是任何殖民地创始人（殖民地↔创始人 1:1） | 解析为 `null` → **不注册为工人**（建镇前女仆不参与劳作） |
| 一只主人有多只女仆 | 全部进同一殖民地，等价于多招了几个工人 |
| 无主 / 未驯服女仆 | `getOwnerUUID() == null` → **不归属、不劳作**，与"无主的不算盟友"一致 |

**不注册 `PLACEHOLDER_COLONY` 兜底**：解析不到殖民地时就干脆不注册，不要造出"占位殖民地工人"这种必须靠 `SchedulerSystem.java:86` 拦下的中间状态。

**工作范围**：不新造"工作站"概念。

- 殖民地侧：`SchedulerSystem` 的 `proximity = 10/(10+dist)` 评分天然把远处的任务分给更近的法师，女仆不会被派到天边去。
- 女仆侧：TLM 自带的站位/家概念就是她的工作范围（`restrictTo(pos, distance)` / `hasRestriction()` / `getRestrictRadius()`，`EntityMaid.java:2109/2130/2120`）。玩家想让女仆常驻工地，用 TLM 原生站位功能即可。

**可选（后续，本次不做）**：给权杖加"收编/指派"动作。`ScepterKind` 四动作里 `SHELTER`/`HOSTILE` 已对任意 `LivingEntity` 生效（`ScepterService.java:73-120`），而 `PEACE`/`FOLLOW` 硬依赖 `WandscapeNpc`（`:29` 第一行 `instanceof`）。

#### 3.6.1 任务类型准入：不是所有活女仆都能接（`caster_only`）

**问题（已实测确认，非推测）**：女仆登记后就是合法的 ECS 工作者，而**施法类任务的执行器只认本模组法师**，拿不到法师时会**把任务立刻判为完成**：

| 执行器 | 位置 | 拿不到法师时 |
|---|---|---|
| `GuardAttackExecutor` | `content/npc/guard/executor/:66,111` | `getNpc` → null → 立即 `completedFuture`，任务被判完成 |
| `AltarCastExecutor` | `content/building/executor/:61` | 同上 |
| `SelfDefenseExecutor` | `content/npc/guard/executor/:74,251` | 同上（但**不是全局任务**，由 `WandscapeNpc` 内部直接调 `:1102/:1113`，女仆碰不到，无需处理） |

守卫任务尤其危险：它的 `TaskRequest` **刻意 `colonyId = null`**（`GuardTaskSource.java:77`，守卫区由全殖民地建筑包围盒并集生成、可能横跨多镇），`SchedulerSystem` 视为"不限小镇" → 女仆成为候选 → 接上后原地不动、威胁没处理、源下一轮再发布 → **反复空转**（与 `GuardTaskSource.java:96` 注释描述的空转模式同类）。祭坛任务虽已有 `mana_cost` 门槛能挡住魔力为 0 的第三方工作者，但不该依赖"它们的魔力恰好是 0"。

**修法（通用能力位，不做任务枚举）**：

1. `ColonyWorker.canCastColonyMagic()`——法师恒 `true`，无殖民地法术体系的工作者（阶段一女仆、通用外部工作者）`false`。
2. `EntityOps.canCastColonyMagic(long)` + `WandscapeEntityOps` 实现，保持核心/ MC 分层的既有约定。
3. 任务源在 `params` 里声明 `caster_only: true`（`GuardTaskSource`、`AltarCastHandler`），`SchedulerSystem` 在候选筛选里据此跳过——紧挨既有的 `mana_cost` 门槛（数据驱动，符合硬规则 3；`params` 本就被持久化，无需改数据格式）。
4. **阶段二女仆接上魔法后，只要把这个位翻成 `true` 就自动能接守卫任务，不用再动调度器**——这是选能力位而非"调度器里写死 `guard:attack`"的理由。

**遗留待确认**：`TaskPoolSavedData` 从旧档恢复的任务是否带 `caster_only`——旧档任务没有这个 key，会按"不限"处理（`taskIsCasterOnly` 对缺失 key 返回 false），即旧档里的守卫任务仍可能派给女仆。开发期不承诺存档兼容（硬规则 7），且守卫任务短命（脱战即完成），**接受**；若要收口，可在任务恢复时按 `blueprintId` 补该标记。

---

### 3.7 任务面板并排显示（D5）

**裁定（D5）**：女仆在任务与法师管理面板里**与 NPC 并列显示**——否则玩家看不出女仆在干活。

**改动面很小，且是第 2 步泛化的自然产物。** `TaskPanelSyncTracker`（`content/task/network/TaskPanelSyncTracker.java`，551 行）构建每一行用到的字段，**几乎就是 `ColonyWorker` 的接口面**：

- `getName` / `getHealth` / `getMaxHealth`（`Entity`/`LivingEntity` 已有）
- `getCurrentMana` / `getMaxMana` / `getEffectiveAttribute`×3 / `getEffectiveArmorValue`（§3.3 已定为接口方法）
- `isFollowMode` / `isResting` / `isPeaceMode`（§3.3 已定为接口方法）
- `getX/Y/Z` / `getUUID` / `getId`（`Entity` 已有）

三处遍历 `EntityComponentBridge.allNpcs()` / `getNpc()`（`:215`、`:345`、`:156`/`:423`）在切到 worker 版后自动涵盖女仆。

**唯一需要新增的**：`MageSummaryDto`（`content/task/network/MageSummaryDto.java:11`）加一个 `kind` 字段（NPC / 女仆），供客户端区分图标与标签；连带 `TaskManagementSyncPacket` 的 codec 与客户端渲染分支。字段名**不要用 `isMaid`**——那会把 TLM 概念焊进通用 DTO；用 `kind`，与同 record 里既有的 `state`（String）风格一致。

---

### 3.8 落地步骤与难度

| 步 | 内容 | 触及 | 难度 | 状态 |
|---|---|---|---|---|
| **1** | 盟友：**零代码**。默认配置（`npc.pvp = true`）下女仆已落 `PET` 分支并按主人殖民地判定（§3.1）。只做实测确认 + 记录 `npc.pvp = false` 的既有问题 | 0 行 | 极低 | 代码路径已静态核实；游戏内实测待做 |
| **2** | 抽 `ColonyWorker` + 泛化 `EntityComponentBridge`/`NavigationSystem`/6 个 boundary 执行器 + `TaskPanelSyncTracker` 三处遍历 | 改 11 文件、新增 2 | 中 | **已完成**（`081af89f`），`build` 通过，行为不变 |
| **2b** | 顺带开放对外 API：`api/ColonyWorkerApi`（薄登记面）+ `MobColonyWorker` 通用适配器 + `WorkerFx` 共用表现 + 施法者能力位（§3.6.1） | 新增 3 文件、改 6 文件 | 中 | **已完成**（`c25964cf`），`build` 通过 |
| **3** | TLM 女仆接入：`@LittleMaidExtension` + 工作模式 task + `MaidColonyWorker` 适配器（走 `WALK_TARGET`）+ `MaidColonyState`（按主人殖民地归属）+ 对账 sweep + `MageSummaryDto.kind` 与面板图标 | 新增 `compat/tlm/**` | 中高 | 未开始；风险见 §五 R1/R2/R6 |

第 2 / 2b 步是"让第 3 步可行"的投资，本身不改玩法。第 2b 步的 API 让**任何**模组都能登记工作者（不限于女仆），所以它同时也把"第三方实体接入殖民地工作链"这件事从一次性兼容变成了可复用能力。

---

## 四、阶段二：只评估不做

### 4.1 法师小屋升级训练

**准入写死**：`MageHutApiImpl.forceBind` 要求 `level.getEntity(npcId) instanceof WandscapeNpc && npc.isColonyNpc() && 同殖民地`（`content/building/internal/MageHutApiImpl.java:56-58`），`MageHutServerHandler.onAssign` 同（`:110-116`）；候选列表 `collectCandidates` 只收 `getColonyNpcs`（`:369-381`）。

**改造面**：`MageHutResident.npcId` 的语义从"NPC UUID"泛化为"worker UUID"，准入改判 `ColonyWorker`；`applyResidentAttributes`（`MageHutServerHandler.java:307-313`）里的 `setBaseAttributeValue` 下沉到 `ColonyWorker`（NPC 写 vanilla 属性，女仆写 `MaidColonyState`）。

**好消息**：升级/训练**只改 level 与 7 项 base 属性，不碰法术、不碰装备**（`docs` 与代码均已确认），所以阶段二里这一块是本方案中最容易的——属性数学（`NpcAttributes.computeEffective/canLevelUp/upgradeCostPerElement/trainCostPerElement`）是纯函数，直接复用。

**难度**：中。定位在阶段一的 `ColonyWorker` 缝之上。

### 4.2 手持法杖施法（含普攻）

**核心阻力：施法层实体类型写死 4 处**：

| 入口 | 签名 | 位置 |
|---|---|---|
| 总分发 | `dispatch(ServerLevel, WandscapeNpc, LivingEntity, MagicDef, String, int)` | `content/magic/internal/MagicSpellExecutors.java:63` |
| 光束 | `castNpcAt(ServerLevel, WandscapeNpc, LivingEntity, String, Integer)` | `content/magic/internal/MagicCaster.java:87` |
| 铁魔法 | `cast(ServerLevel, WandscapeNpc, LivingEntity, String, int)` | `compat/ironspellbooks/IronSpellsCaster.java:73` |
| 诡厄 | `cast(ServerLevel, WandscapeNpc, LivingEntity, String, String)` | `compat/goety/GoetyCaster.java:165` |

`dispatch` 对施法者真正**不可替代的要求只有四组**（逐行核对得出）：`equippedMagic`（读等级/customData）、`tryCastSpell`（资源门控，`WandscapeNpc.java:192` → `MagicState.tryCast`）、`getStaffPosition`/`faceTarget`（施法几何）、`isFriendlyForce`/`canBeamHurt`（敌我边界）。其余（`position/getUUID/addEffect/distanceToSqr/getArmorValue`）`LivingEntity` 全有。

**决策内核零耦合，可直接复用**：`CastBrain.select` / `resolvePriority` / `knownSpells`（`content/magic/internal/CastBrain.java:44/162/213`）是纯函数；`IronSpellsHelper.equippedSpellbookSlots(LivingEntity)`（`:190`）与 `GoetyCompat.isHoldingGoetyWand(LivingEntity)`（`:59`）**已经是 `LivingEntity` 签名**；第三方魔法用 `getSyntheticDef` 伪造成 `MagicDef` 喂给 `CastBrain` 的 adapter 模式现成（`IronSpellsHelper.java:97`、`GoetyHelper`）。`MagicApi` 本身也已是 UUID 抽象（`api/MagicApi.java:28-44`）。

**TLM 侧施法入口是正门**：`EntityMaid#performRangedAttack`（`EntityMaid.java:1209`）会把调用转发给当前 task——只要 task `instanceof IRangedAttackTask`（`:1211/1217`），无需改 TLM。**但**索敌行为里**不能用** `MaidShootTargetTask`：它硬性要求主手是原版 `ProjectileWeaponItem`（`entity/ai/brain/task/MaidShootTargetTask.java:49`）。正确选择是 `MaidShootTargetAnyItemTask(int cooldown, int chargeTicks, Predicate<ItemStack> weaponTest)`（`.../MaidShootTargetAnyItemTask.java:20`），`weaponTest` 判我们的 `WandItem`。TLM 自带的 KubeJS 层 `RangedAttackTaskJS` 就是这套的现成样例。普攻（L2 兜底）可复用 `GuardCombat.normalAttack` 的语义。

**魔力/冷却/已学法术**：同 §3.5，挂在 `MaidColonyState` 上。

**施法动画**：TLM 提供 `IMagicCastingAnimationProvider`（`api/animation/IMagicCastingAnimationProvider.java:24`），经 `ILittleMaid#registerMagicCastingAnimation`（`ILittleMaid.java:218`，**仅客户端**）注册。TLM 内部**零实现**，纯留给附属；状态（咏唱 tick、相位）**必须自管并自行同步**——TLM 的 javadoc 明说"本模组不记录或缓存任何施法数据"。若要出动画，需额外加一条服务端→客户端的状态同步包。

**难度**：大。改造集中在"把 4 个施法入口的形参从 `WandscapeNpc` 收到 `SpellCaster` 接口"，但连带 `GuardCombat`（NPC 专有的战斗编排：站位/让位/打断）需要女仆侧另写一套。

### 4.3 策略槽

**结构**：`NpcStrategyMenu extends AbstractContainerMenu`，12 槽 = 4 策略组 × 3（`content/npc/component/EquippedMagicComponent.java:77-80`），行=策略组、列=类内优先级。

**写死点清单**：

| # | 位置 | 内容 |
|---|---|---|
| 1 | `content/npc/NpcStrategyMenu.java:48` | 字段 `@Nullable WandscapeNpc npc`；构造 `:64`、`stillValid` `:127`、`syncEquipped` `:300`（读 `npc.getUUID()`/`npc.castStrategy`） |
| 2 | `NpcStrategyMenu.java:167` `capForNpc(WandscapeNpc, int)` | 参数类型写死，但内部两个调用点已是 `LivingEntity` 签名 → **易改** |
| 3 | `network/NpcOpenStrategyPacket.java:51`、`network/NpcStrategyPacket.java:76` | `instanceof WandscapeNpc` 硬校验 |
| 4 | `network/NpcDataPacket.java:177` `from(WandscapeNpc)` | 27 字段里含 `getEffectiveAttribute/getEffectiveArmorValue/getSkinVariant/getHatColor/getItemBySlot(ARMOR_VANILLA_SLOTS)/getItemInHand(MAIN_HAND)/hasDefaultWand` |
| 5 | `content/magic/internal/SpellcastingApiImpl.java:95` `resolve(UUID)` | 依赖 `EntityComponentBridge`/`WandscapeNpc`，需加"非 NPC caster"解析 |
| 6 | `WandscapeClient.java:294` | handler 只认 `NpcStrategyScreen`/`NpcScreen` 两类屏 |

**好消息**：`NpcStrategyScreen` 本身**不持实体引用**（只用 `entityId`，`client/NpcStrategyScreen.java:53`；另有 `NpcScreenNavigator.getLastEntityId()` 兜底 `:130`）→ **屏幕侧天然可复用，零改动**。`SpellSlot.mayPlace` 的门控逻辑也全是数据判定。

**难度**：中。以"把 `@Nullable WandscapeNpc` 字段收成接口 + 通用 entityId 解析"为主，属于机械改造。

---

## 五、风险与验证清单

| # | 风险 | 影响 | 缓解 / 验证 |
|---|---|---|---|
| **R1** | TLM WORK activity 里硬编码的行为（`MaidBegTask` 6 / `MaidWorkMealTask` 7 / `MaidStealEdible*` 8 / 随机走动 20，`MaidBrain.java:138-145`）与我们的工作移动争抢 | 女仆跑去做饭/偷吃/乞讨而不去工地 | **已基本解决**：走 `WALK_TARGET` 记忆通道后，声明 `WALK_TARGET ABSENT` 前提的 `MaidMoveToBlockTask` 一族（含偷吃）自动无法启动；随机走动由 `enableLookAndRandomWalk` 返回 false 关掉；`MaidBegTask` 注册在 IDLE，工作时不活跃。残余仅 `MaidBegTask`/`MaidWorkMealTask` 的苛刻触发条件，实测确认即可（§五 验证 3） |
| **R2** | 女仆被移除（死亡/魂符收走/换维度/被其它模组处理）时 ECS 残留 | 幽灵 worker 占任务、任务卡死 | 对账式 sweep（§3.4）而非精确生命周期；sweep 检测 `isRemoved()` 即注销并 `releaseTaskForReassign` |
| **R3** | ~~`AttachmentType` 能否随 `EntityMaid` 存档持久化~~ | — | **已验证通过**（§五 验证 2）：附件在 `Entity` 层序列化，`EntityMaid` 同样生效。§3.5 走方案 A |
| **R4** | 女仆无 vanilla 属性表承载 7 项殖民地属性 | 工作速度/魔力结算无源 | 自有 `MaidColonyState` + 复用 `NpcAttributes.computeEffective` 纯函数（§3.5） |
| **R5** | TLM 未安装时的类加载 | `NoClassDefFoundError` 崩服 | 门面+Impl 隔离，门面零外部类型引用。**本例风险最高**：`TlmCompat.getOwnerUuid` 被挂在 `classify()` 这个判定咽喉上，是全库最热路径之一，不能用 `GoetyCompat` 那种顶部直接 import 的写法（见 §3.1） |
| **R6** | TLM API 漂移 | 升级 TLM 后兼容层编译失败 | `IMaidTask`/`ILittleMaid` 是 TLM 官方 api 包（非 internal），但仍非冻结契约；`gradle.properties` 锁版本并在升级时回归 |
| **R7** | 两套库存（ECS `NpcInventory` vs 实体物品栏）不互通 | 女仆挖到的材料不进她自己的背包 | **这不是新问题**——NPC 现在同样如此（`WandscapeNpc.java:505` 的 `SimpleContainer` 与 ECS `NpcInventory` 无互转代码）。女仆与法师行为一致，属可接受 |
| **R8** | `npc.pvp = false` 时玩家侧实体（含女仆）跨殖民地恒友军 | 「只认自己小镇的女仆」在该配置下失效 | 属**既有**设计、非本次引入；不在 TLM 兼容里顺带修，记为待办 A（§3.1.1） |

**动手前的四项验证（已全部完成，2026-09-12）**：

| # | 项 | 结果 |
|---|---|---|
| 1 | 盟友行为 | **通过（静态核实）**。`EntityMaid extends TamableAnimal`（`EntityMaid.java:172`）→ 继承 `OwnableEntity`；TLM 自身在 `:1071/1082/1536/1546/2722` 都用继承来的 `getOwnerUUID()`，未覆写 owner 存储；驯服走 vanilla `this.tame(player)`（`:688`）。故有主女仆落 `classify()` 的 PET 分支（`WandscapeNpc.java:343`）→ `pvpColony(ownerUUID)`。游戏内实测仍待做，但代码路径无歧义。 |
| 2 | `AttachmentType` 持久化 | **通过**。`Entity extends AttachmentHolder`（`Entity.java:131`），`saveWithoutId` 内 `serializeAttachments(registryAccess())` 写入 `neoforge:attachments`（`:1795-1796`），`load` 内读回（`:1883`）。均在 `Entity` 层、且在 `addAdditionalSaveData` **之外**，子类无法绕过 → 女仆同样持久化。**§3.5 走方案 A。** |
| 3 | TLM WORK 行为争抢 | **通过，且找到了根治手段**（见下）。 |
| 4 | 能否驱动女仆导航 | **通过**。`Mob.serverAiStep()` 内 `this.navigation.tick()`（`Mob.java:797`）无条件执行；且女仆在 CORE 活动注册了 `MoveToTargetSink`（`MaidBrain.java:98`）。 |

**验证 3/4 的关键发现（纠正了本文初稿的一个错误假设）**：

1. **vanilla `Brain` 的 priority 不产生互斥。** `availableBehaviorsByPriority` 是 `TreeMap<Integer, Map<Activity, Set<BehaviorControl>>>`（`Brain.java:51`），`startEachNonRunningBehavior` 会把活跃活动里**所有** STOPPED 行为都启动——初稿"我们放优先级 5 就早于 TLM 的 6/7/8/20 从而获胜"的说法**是错的**。
2. **TLM 用「记忆状态」做互斥，而不是 priority。** `MaidMoveToBlockTask` 的构造把 `WALK_TARGET, VALUE_ABSENT` 声明为**启动前提**（`MaidMoveToBlockTask.java:30-36`）——只要 `WALK_TARGET` 存在，这一族移动任务（种田/偷吃/找家饭……）就**不会启动**。`MaidStealEdibleMoveBlockTask` 正是它的子类。
3. **`WALK_TARGET` 才是女仆移动的正门。** 消费者是 vanilla 的 `MoveToTargetSink`，TLM 把它注册在 **`Activity.CORE`**（`MaidBrain.java:98`，CORE 恒活跃，`MaidBrain.java:68-71`）。写 `WALK_TARGET` → `MoveToTargetSink` 驱动导航。

**因此导航方案定为：女仆侧经 `WALK_TARGET` 记忆驱动，而不是直接 `getNavigation().moveTo()`。**

- **直接驱动**虽然技术上可行（navigation 每 tick 都被 tick），但 `WALK_TARGET` 保持缺席 → TLM 的移动任务照常启动并覆盖我们的路径，**R1 成立**。
- **记忆通道**反过来把 R1 顺手解掉：写 `WALK_TARGET` 本身就阻断了那一族任务，**互斥免费拿到**，且用的是 TLM 自己的机制（不越权、不 mixin）。

**实现落点**：`BehaviorUtils.setWalkAndLookTargetMemories(maid, pos, speed, closeEnoughDist)`（TLM 自己的移动任务用的就是这个）。注意 `MoveToTargetSink` 到达后会擦除该记忆，所以工作移动需要**每 tick 续写**（或按 `PathNavigation.isDone()` 续写）——这正好与 `NavigationSystem` 现有的「每轮重发」节奏合拍。

**R1 收窄后的残余风险**：`MaidBegTask` 声明的要求里**没有** `WALK_TARGET ABSENT`（只要求 `NEAREST_VISIBLE_LIVING_ENTITIES` 存在），因此它仍可能覆写我们的记忆。触发条件苛刻（主人在 2-6 格内、在其站位范围内、且**手持诱惑物**），实测确认即可，不必额外抑制。另注意 `MaidBegTask` 注册在 IDLE 活动，而工作时活跃活动是 {CORE, WORK} → IDLE 不活跃，**实际上它也不会跑**。

---

## 六、待决问题

| # | 问题 | 备选 | 倾向 |
|---|---|---|---|
| D1 | 盟友范围 | 全部 `EntityMaid` / 仅有主女仆 / 仅本殖民地主人的女仆 | **已裁定**：只含主人属于本殖民地的女仆；别人小镇的、无主/未驯服的都**不是**盟友。默认配置下**已自动满足、零代码**（§3.1） |
| D2 | 女仆归属殖民地的方式 | 按位置解析最近殖民地 / 主人殖民地 / 权杖指派 | **已裁定**：直接进**主人的殖民地**（`getColonyByFounder(getOwnerUUID())`），与盟友判定同源；不做位置解析、不做占位兜底（§3.6） |
| D3 | 别人家的女仆是否算友军 | 算（全局恒友军）/ 不算 | **已裁定**：不算。默认配置（`npc.pvp = true`）下天然成立 |
| D4 | `npc.pvp = false` 时玩家侧全局友军（含女仆、宠物、召唤物、玩家本人） | 顺带修 / 记录待办 | **已裁定**：不在本次顺带修（会把风险外溢到所有玩家侧实体），记为待办 A（§3.1.1） |
| D5 | 女仆工作态是否要在任务面板/概览里与 NPC 并列显示 | 显示 / 不显示 | **已裁定**：**并列显示**。改动面即第 2 步泛化的自然产物（`TaskPanelSyncTracker` 建行字段≈`ColonyWorker` 接口面），只需加 `MageSummaryDto.kind` + 客户端图标分支（§3.7） |
| D6 | 阶段一是否一并做"女仆专用工作配置界面"（`getTaskConfigGuiProvider`） | 做 / 先不做 | **已裁定**：先不做。等 R1 实测后再定界面需要暴露什么 |
| D7 | 第 2 步重构是否单独成 commit/分支 | 独立提交 / 与第 3 步合并 | **已裁定并执行**：独立提交 `081af89f`（行为不变、可单独回滚，是第 3 步的保险） |
| D8 | `ColonyWorker` 的 API 化做到哪一档 | 薄登记面 / 薄登记面+自定义适配器 / 先不做 | **已裁定并执行**：薄登记面——`api/ColonyWorkerApi` 只暴露 5 个方法，内部 `ColonyWorker` 不公开（§3.3） |

---

## 七、被否决的替代方案

| 方案 | 否决理由 |
|---|---|
| **走 `FriendlyForceApi.registerAlly` 注册全部女仆**（本文初稿方案） | 该 API 登记的是 `EXTERNAL_ALLY`——**恒**友军、不校验殖民地（`FriendlyForce.java:66`）。在默认的 PVP 开启下反而让**别人家的、无主的女仆**也免疫殖民地攻击，比现状更差（§3.1） |
| **新增 `COLONY_COMPANION` 枚举 + `classify()` 分支**（本文二稿方案） | 语义上更"正"、且与 PVP 开关解耦，但**默认配置下结果与既有 `PET` 分支完全等价**——为等价行为新增枚举项 + 热路径分支不划算。留作待办 B 的备选：将来真要支持「与 PVP 无关的殖民地级友军」时再启用 |
| **把女仆"转生"成 `WandscapeNpc`**（换模型贴图冒充） | 丢失 TLM 的全部生态：模型包（geckolib/资源包）、背包、好感度、饰品、AI 任务、`tlm_custom_pack`。等于让玩家二选一，兼容性反而最差 |
| **女仆侧平行实现一套 worker/施法/策略槽** | 重复实现同一概念，违背"一个概念收敛进该域唯一命名类"的增量约束；且从此每次改动工作链都要改两处 |
| **只做盟友，不参与工作** | 不满足目标①的"参与城镇建设、接取任务" |
| **给 ECS 再开一条"女仆专用"任务通道** | 与 SchedulerSystem 并行的第二套调度会分叉任务分配与产出结算，违背"任务派发唯一通道"的既有约束 |

---

## 八、下一步

阶段一按 §3.8 推进。第 1 步（盟友）**零代码**，只剩游戏内实测确认；第 2 / 2b 步（`ColonyWorker` 缝 + 工作者 API + 施法者能力位）**已完成并 build 通过**。第 3 步（TLM 女仆接入）是接下来的主体工作。阶段二在阶段一落地并实测后再单独立项。
