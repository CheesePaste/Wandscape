# 核心功能域避坑手册（domain-notes）

> 信息截至 2026-09-18 | Minecraft NeoForge 1.21.1

- **【何时读】**：第一次接触或修改某个具体功能域（NPC/游客/魔法/任务/建筑/仓库/道路/新手引导/第三方兼容）代码前。
- **【不包含什么】**：各域基础概念百科、原版 Minecraft 常识、无特殊约定的常规 Java 代码流程。

---

## 一、NPC 域 (`content/npc`)

1. **属性全套规则唯一事实源**：
   - 所有 9 项 NPC 属性规则收敛在 `content/npc/attributes/NpcAttributes.java`，**数据唯一源是 `BASE_SPECS`**（9 项全覆盖：7 可见 + 2 隐藏恒等曲线）。默认值取其上下界中值 `(lower+upper)/2`，招募掷点与每级加成也全部派生自它，没有第二张数值表。
   - 严禁在其他类中硬编码属性默认值或范围；修改属性规则只动 `NpcAttributes.BASE_SPECS`。
   - `MOVE_SPEED` 每级加成 0.01、`ARMOR_VALUE` 为 0（废案）；`ARMOR` 默认值为上下界中值 5.0。
2. **原版装备槽与耐久手动扣减**：
   - NPC 盔甲放原版装备槽以兼容外部属性与附魔计算。但原版 `LivingEntity` 不对非玩家生物扣除盔甲耐久，必须在 `WandscapeNpc.hurtArmor` 中手动调用 `hurtAndBreak` 结算。
3. **幽灵 NPC 守卫（区块卸载）**：
   - 区块卸载后实体处于 `isRemoved() == true` 状态。调度器必须通过 `EntityOps.isNpcAlive` 过滤，执行器遇幽灵实体立即退还任务，杜绝幽灵 NPC 接活死循环。
4. **NBT 数据安全**：
   - 对外暴露复合标签必须使用 `return tag.copy()`。
5. **NPC 击杀归属殖民地主人**：
   - 所有 NPC 来源伤害（光束/普攻/陨石/铁魔法）经 `NpcSpellPowerHandler`（NPC 伤害统一入口）把目标的 `lastHurtByPlayer` 记为殖民地创始人玩家；无殖民地记录时单在线玩家兜底，敌对法师等 `isColonyNpc()==false` 不授予。
   - 只补「最近被玩家击伤」标志，**damage source 实体恒为施法 NPC**——怪物仇恨与 SPELL_POWER 倍率判定（都读 `source.getEntity()`）不受影响；否则烈焰棒/凋灵骷髅头/亡灵装备率等 killed_by_player 掉落，装备掉落率与经验球全会因 NPC 击杀丢失。
6. **友军名单（盟友集）唯一判据收敛在 `WandscapeNpc.classify()` + `FriendlyForce`（纯判定）**：
   - 成员：所有玩家、同殖民地 NPC / 其铁魔法或诡厄随从 / 游客、玩家训养的宠物（`OwnableEntity` 有主：狼/猫/鹦鹉/马/骆驼/羊驼）、守护召唤（铁傀儡/雪傀儡）、玩家召唤的铁魔法或诡厄随从、其它模组经 `FriendlyForceApi.registerAlly` 注册的友军。
   - ⚠️ **判定顺序：召唤者解析先于宠物兜底**——诡厄等第三方 `Owned` 召唤同时实现原版 `OwnableEntity`，若主人是敌对生物（如使徒召唤的黑曜石巨柱）必须按召唤者身份判敌我，不能凭「有主」就当玩家宠物豁免；宠物兜底另排除 `Enemy`。
   - **单向**（NPC 不攻击/不记仇/不溅射友军）走 `isFriendlyForce`；**双向互不侵犯**（玩家宠物/随从不打殖民地单位、殖民地随从不打玩家）走 `FriendlyTargetingHandler` 监听 `Mob.setTarget` 的 `LivingChangeTargetEvent`。只覆盖「真实殖民地侧」，`EvilMage` 等 `isColonyNpc()==false` 的敌意行为刻意保留。
   - **PVP 分化**：`Config.PVP`（`npc.pvp`，默认 true）开启时玩家侧实体（`PLAYER`/`PLAYER_SUMMON`/`PET`）的「恒友军」收紧为「仅同殖民地」——`classify()` 需把玩家/召唤者/宠物主人的殖民地解析进 `Classified.colony`（`getColonyByFounder`，殖民地↔创始人 1:1；无殖民地→ null→判非友军），`FriendlyForce.isAlly/areMutuallyAlly` 增 `pvp` 参数做 `sameColony` 判定。改动时必须同步贯通 `isRetaliationTarget`（还手）、`canBeamHurt`（光束伤害）与 `ScepterService.toggleHostile`（可标记敌对玩家）四条边界——只改一处会阻塞。`EvilMage.canBeamHurt` 已覆盖「非友军 或 生存玩家」，不受 PVP 影响。
   - **第三方魔法模组误伤（源头修复）**：`WandscapeNpc.isAlliedTo` 覆写（vanilla 中立钩子，基于 `Entity#isAlliedTo`）让 Goety `MobUtil.areAllies` 与铁魔法 `isFriendlyFireBetween` 在源头就不索敌/不误伤本殖民地 NPC——两类模组的友伤判定最终都汇到 `isAlliedTo`，故不必逐模组打补丁。玩家分支只豁免本殖民地创始人（`colonyFounder()`，`ColonyApi.getFounder`，PvP 无关），非玩家分支沿用 PvP 感知的 `isFriendlyForce`，所以「自家殖民地受保护、其他殖民地仍可被误伤」，与 PvP 的「跨殖民地敌对」同向。配套 `Config.NPC_FRIENDLY_FIRE_PROTECTION`（`npc.friendlyFireProtection`，默认 true）经 `NpcFriendlyFireHandler`（`LivingIncomingDamageEvent`：伤害链解析回攻击者玩家，仅取消「攻击者拥有该殖民地」的伤害）兜底覆盖近战/箭矢/其它不走 `isAlliedTo` 的来源；关闭后可再伤自家殖民地 NPC。
   - ⚠️ **已知问题（待修，非缺陷）**：`npc.pvp = false` 时 `PLAYER`/`PLAYER_SUMMON`/`PET` 三类的殖民地语义被整体丢弃（`FriendlyForce.isAlly` 取 `true` 分支），**所有玩家本人 + 其宠物/召唤物跨殖民地恒为友军**——无 PVP 的世界里，玩家 B 的狼/马/召唤物都不会被玩家 A 的殖民地攻击，与「按殖民地归属判友军」的口径冲突。这是 PVP 系统引入前旧行为的保留档，改动前需先重新定义 `pvp = false` 到底该是什么语义（「无 PVP 但仍按殖民地」还是维持原样），属独立决策；**不要在第三方兼容里顺手动它**，会外溢到所有玩家侧实体。
   - ⚠️ **做其他模组兼容时，务必把该模组的召唤物/宠物经 `FriendlyForceApi.registerAlly` 加入盟友名单，避免殖民地 NPC 误伤**——这是硬性提醒，遗漏会导致兼容模组的召唤生物被己方法师当敌人打死。
     - **例外**：若该模组实体本身已实现 `OwnableEntity` 且有主（`TamableAnimal` 子类即是），它**已经**落 `PET` 兜底分支、按主人殖民地判定，**不要**再 `registerAlly`——`EXTERNAL_ALLY` 是恒友军、不校验殖民地，注册反而会把**别人家的、无主的**同类实体也变成己方友军，比不注册更差。
    - ⚠️ **已知未修风险：`FriendlyForce.sameColony` 把 null / `PLACEHOLDER_COLONY` 当具体 UUID 比对**（2026-10 全仓扫荡发现，用户拍板暂不修）。`sameColony` 把两侧的 null 都归一成 `PLACEHOLDER_COLONY` 再比相等，于是「施法者 `colonyId` 停在占位符、目标已属真实殖民地」判为**非友军**——同镇的光束 / 陨石会误伤自己人。占位符状态确实可达：`EntityComponentBridge.onNpcJoinWorld` 只在**新建登记**时按位置自动探测殖民地（重连分支刻意跳过重探测），且要求探测点附近有殖民地原点，所以镇外召唤或建镇前召唤的法师会长期停在占位符。归因困难（铁魔法等产生同一死亡文案，无法确认），真修是窄口径：只放宽 `WANDSCAPE_NPC`/`MAGIC_SUMMON`/`TOURIST` 三类，别动玩家侧。
7. **NPC 寻路水中脱困是「双层卡死判据 + 游泳免 onGround」**（`content/npc/system/NavigationSystem`）：
   - 位移卡死判据（每 60 tick 水平位移 < 2 格、连 3 次）只覆盖「没在动」；高岸水池这类「游得动但永远逼近不了目标」的困局需补**净逼近判据**：水中每区间游动够大时，记录到目标到达中心的**历史最低 3D 距离**（含垂直，兼容潜水下潜），连续 4 个区间（≈240 tick）不再创新低即切自传送脱困。渡河/水下工作全程单调逼近（每区间创新低），永不触发。
   - `switchToRitualTeleport`（及跟随兜底 `FollowPlayerGoal.tryTeleportToPlayer`）的门控是 `onGround()`，但游泳时 `onGround()` 恒 false——必须放行水中 NPC（`|| isInWater()`），否则水池困局判定卡死后每轮被门控拦下死循环，永远传送不出去（任务走 NavigationSystem、跟随走 FollowPlayerGoal 两路都会中招）；落点安全由 `findSafeLanding` 保证。
8. **殖民地数据在客户端恒不可用——客户端判定点只能用同步数据**（「客户端殖民等级陷阱」同族，2026-09-12 在第三方 GUI 上又踩一次）：
   - `ColonyApi.getColonyByFounder` / `getColonyLevel` / `getAllColonyIds` 都走 `getColonySavedData()` → `ServerLifecycleHooks.getCurrentServer()`，**专用服务器的客户端恒为 null**（单机因带有集成服务端而试不出来，属典型"单机复现不了"陷阱）；`ColonyWorkerApi.enlist` 一类引擎侧 api 在客户端也会因 `World.getActive() == null` 直接失败。
   - 典型翻车：把「主人有小镇」这类服务端事实写进**第三方 GUI 的可用性判定**里（第三方模组的任务界面常把「这个选项能不能点」放在客户端判定），结果任务在多人局里永久置灰、点不动。**做法**：客户端条件只用 `SynchedEntityData` / 同步包里的数据；服务端事实留给服务端把关，并用描述文案 + 一次性 `Log.warn` 兜底，别让它变成"选了任务却站着不动"的静默失败。
    - **`getColonyLevel()` 的表现是恒返回 0，不是抛异常**：客户端 `ColonyApiImpl` 是个**空的空间索引**（`colonyToOrigin`/`colonyOrigins` 只在服务端世界加载时经 `rebuildFromSavedData()` 与 `createColony()` 填充，客户端从不填充），于是命中「无此镇 → return 0」契约，任何镇都返回 0——客户端侧所有等级门槛判定会全部显示锁定，且只在专服/局域网远端复现（单机集成服与客户端同 JVM、共享同一单例，索引已被服务端填好，试不出来）。
    - **做法**：客户端取等级走 `WandscapePanelState.getColonyLevel()`（真实等级经 `ColonyStatsSyncPacket` 同步），服务端才走 `colonyApi.getColonyLevel(colonyId)`。判定入口 `BuildingUnlockChecker.resolveLevel()` 已按此修正（`FMLEnvironment.dist.isClient()` 先行）；`getColonyLevel` 返回 0 = 无此镇的契约保留，别改它。
    - ⚠️ **同型未修的埋雷**：`content/production/internal/RecipeUnlockChecker.isUnlocked()` 直接调 `colonyApi.getColonyLevel(colonyId)`，**没有 Dist 分支**——同一陷阱一处修了一处没修。当前尚未炸，因为全部调用点都在服务端或发包构建路径（`WorkstationDataPacket`/`CraftingStationPacket`/`MagicStationPacket`/`RequestProductionTaskPacket.handleServer`），客户端屏幕只读服务端下发的 `lockedReason`；将来任何客户端路径（新屏幕 / 预览 / JEI）复算解锁状态就会立刻复现。
9. **自定义实体禁 `bakeLayer(ModelLayers.PLAYER)`（EMF 连坐坑，游客渲染器同规则）**：
   - Detailed Animations 系列资源包经 EMF 在烘焙期替换原版玩家层几何；借该层烘焙的自定义实体（NPC/游客曾是）会被连坐——EMF 的新几何配 64×64 标准布局皮肤，头身 UV 错位分离（2026-09 用户实测，仅装 EMF+ETF 即可复现）。
   - 做法：实体渲染器各自注册自有 `ModelLayerLocation`（`wandscape:wandscape_npc/main`、`wandscape:tourist/main`，注册在 `WandscapeClient.onRegisterLayerDefinitions`），几何用 vanilla 同款工厂 `LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64)`（经典粗臂 64×64 含 overlay 第二层），与原版 PLAYER 层逐位一致——不装 EMF 时外观零变化；`EvilMage` 复用 NPC 渲染器自动覆盖。1.21.1 的 `PlayerModel` **没有** `createBodyLayer`（旧版本记忆），几何工厂是 `createMesh(CubeDeformation, boolean slim)`。
10. **玩家指挥法师的唯一道具是法杖（权杖已隐藏但代码保留）**：
   - 模式住**物品自定义数据**（`WandItem.MODE_KEY`，值 `WandMode` 名），shift+右键循环；三处入口必须同口径：本镇法师走 `mobInteract` → `NpcInteractHook`/`NpcSneakInteractHook`，非本镇生物走 `WandInteractHandler`（`PlayerInteractEvent.EntityInteract`），方块走 `WandItem.useOn`。
   - **接管与否只由 `WandMode.affectsCreatures()`/`affectsBlocks()` 决定**，客户端与服务端读同一个判据——分散判断会让两端各接一半，出现「服务端下了命令、客户端还在走原版交互」。
   - ⚠️ 两个已知边界，改交互前先看：`useOn` **只在方块自己没接住右键时被调用**，所以箱子/熔炉/门这类有界面的方块在集合、鉴定模式下照旧开自己的界面（要覆盖它们得改用 `RightClickBlock` 事件，代价是屏蔽所有方块交互）；`NpcInteractHook` 是 void、无法拒绝，因此**手持法杖右键法师永远被吞掉**，集合/鉴定模式下只回一句提示、不开法师装备菜单（权杖时代同此语义）。
   - 庇护/敌对**没有第二份实现**：`WandModeService` 直接调 `ScepterService.toggleShelter/toggleHostile`，标记仍落 `ScepterMarksSavedData`；`scepterHostileRange` 默认值已随需求改为 32 格（`BalanceValues` 与 `wandscape_balance.json` 两处）。
   - 集合是**一次性**的：直接调 ECS 的 `movementOps.navigateTo`（与守卫 AI 同一套寻路/卡住传送兜底），不存状态、不新增寻路代码；被任务打断即作废（`NavigationSystem` 只驱动 `NavigationState`，任务一入队就取消在途导航）。
   - 法杖头（模型里的 `gem`，`tintindex 0`）的显示色走 `WandItem.headColorArgb`：**存过模式**的堆叠染模式色（`WandMode.themeColor()`），没存过的仍是预设 `wand_color`。分界刻意放在「有没有存过模式」而不是「当前模式的默认值」——否则 NPC 装备界面里的法杖会显示默认模式色，而世界里 NPC 手里的法杖（`WandscapeNpcRenderer` 直接读 `colorArgb`）还是预设色，同一件物品两处不同色。只有物品 tint 走 `headColorArgb`，**施法光束（`MagicCaster`）与 NPC 手持渲染继续用 `colorArgb`**，改染色时别把三处混成一个入口。
11. **祭坛查的是建筑归属、死亡记录存的是实体字段，两处必须同源**：
   - 死亡记录的小镇归属取自实体权威字段 `WandscapeNpc.colonyId`（NBT 持久化），**不**经 `EntityComponentBridge` 静态桥往返：桥映射在区块卸载/重连/启动早期会短暂缺失，那一刻取到 null 会被静默写成占位殖民地，而祭坛按建筑的真实 `colonyId` 查——两者对不上就表现为「祭坛显示没有死亡记录」，全程零告警。查询侧 `AltarCastExecutor` 也统一用**祭坛建筑**的归属，不再用任务参数 `colony_id`（冗余副本，缺失或失配会查到别镇）。
   - `DeathRecord.latestInColony(records, null)` 返回 null，与 `getRecordsInColony(null)` 的空表口径一致；旧语义「null = 不限小镇」会让尚未归属的祭坛把**别镇**的死者拉来复活。
   - 环境伤害逃生（`NpcEscapeTeleport`）**也救工作中的法师**：唯一不放行的是正卡在异步 op 的 future 上（传送后任务会重放该 op，`ResourceRequestOp` 这类重放会二次扣资源、不保证幂等）。传送前必须做交接三步——清 `pendingFuture`、`movementOps.cancelNavigation`、把 ritual future 回填给执行器——与 `NavigationSystem.switchToRitualTeleport` 同一套；缺了这步任务执行系统会死等已失效的 future，NPC 传走了任务却卡住。
   - 法师**没有**任何伤害免疫、复活只给 1 血是**刻意设计**，别当缺陷修；要动的是「逃生可达性」，不是「死亡率」。
12. **法师的施法姿态/光束/朝向 = 每 tick 现算的 `isCasting`，不许为「空闲 NPC」节流**（2026-10-06 修，踩过）：
    - `WandscapeNpc.tickCastingState()` 从 ECS 推导 `casting`（`exec.state == ACTIVE && 有活`），它同时驱动客户端模型的施法姿态（`WandscapeNpcModel.setupAnim` 的 `isCasting()` 分支会**覆盖**原版挥手动画）、光束粒子（`WandscapeNpcRenderer`）、`faceTarget` 与头上状态串。
    - **曾经的坑**：那里有个「空闲 NPC 每 20 tick 才查一次 ECS」的快速路径，条件是「冷却未到 **且** 当前不是施法态」。多法师协同把建筑拆成几十条 1.6 秒的批次任务、批次之间恰好有 1 tick 的 `IDLE`，于是每个批次边界都会把 `casting` 打成 false 并把冷却重置成 20 tick —— 客户端整整 1 秒收不到姿态/光束/朝向更新，表现就是**方块一直在放、建筑一直在长，法师的动作却一顿一顿**（玩家口径「干一会停一会」）。已把快速路径整个删掉：`casting`/目标/朝向/状态串一律每 tick 现算（每次只是 1~2 次组件查表，可忽略），并给姿态掉线留 `CASTING_GRACE_TICKS = 5` 的余量，免得批次之间的 1 tick 空窗让手臂弹回站姿再弹回来。
    - **推论**：任何「每 N tick 省一次 ECS 查询」的节流都不能覆盖与**客户端可见状态**挂钩的字段（姿态/目标/朝向/状态串）；性能上真正贵的是拼字符串与同步实体数据，那部分靠「值没变就不 set」的脏检查就够。

---

## 二、游客经济域 (`content/tourist`)

1. **游客 ≠ 常驻市民**：
   - 游客是短居访客（停留 2~4 天后离场），无职业、无床位、无固定工作场所、无复杂状态机。
   - `TouristState` 仅为当前移动状态标记，**严禁扩展为复杂状态迁移机**；实际移动由 `TouristMoveGoal.MoveMode` 驱动。
2. **交互位（interact_spots）与排队站位**：
   - 建筑 JSON 中的 `interact_spots` 列表长度决定该建筑**同时交互的游客人数上限**。
   - 游客目标建筑（shop/service/relax/atm）必须提供非空 `interact_spots`，否则游客永远不会选中该建筑。
   - Spot 全满时进入 FIFO 队列，新游客排入最短队伍，沿 spot 的 `facing` 反方向排开，朝向与交互者一致。
3. **等比例排队降权与目标评分**：
   - Spot 全满时评分按人数等比例降权（1~3 人乘 0.75 / 0.5 / 0.25，封顶 −75%），严禁使用大额固定减分。
   - 游客 `visitedBuildings` 在整个停留期间**不重置**（防挂机刷分）；仅 ATM（缺钱）与 relax（精力低于阈值）享有重复访问豁免。
4. **结算与经验发放**：
   - 交互结算（`fillBars`）无倒扣惩罚；只有在停留期内 Comfort / Magic / Wonder 三条全部填满时，离场才发放殖民地升级经验。
5. **生成双开关（全局 Config + 殖民地级设置中心）**：
   - 全局 `Config.TOURIST_SPAWN_ENABLED`（`tourist.spawnEnabled`，默认 true）关闭时，**所有殖民地一律不生成游客**，无视各殖民地开关。
   - 殖民地级开关存于 `ColonySavedData.touristSpawnDisabled`（缺省 = 开启），由设置中心「本镇」页控制（`ColonySettingUpdatePacket`）：包体不带 colonyId，服务端按玩家的**当前镇**（`ColonyOwnership.activeColony`）解析，并要求他是该镇 OWNER（`ColonySettings.apply`）；只在全局开关开启时生效。
   - 两道闸都只在 `TouristSpawnSystem.createSchedule`（每日排期）与生成窗口内生效，只拦**新增**游客，不清理已在场的游客；开关中途关闭会清掉当日已排计划，避免重新开启时一次性倾泻。

---

## 三、魔法与施法域 (`content/magic`)

1. **施法决策三层架构**：
   - **L0 硬性覆盖（最高优先）**：自身或范围内友军血量危机（< 0.5 且会 heal）强制治疗；视线被挡转寻路；被围攻走位规避。
   - **L1 玩家策略层**：按 NPC 策略预设（balanced/offensive/support/defensive）与装备桶（每桶 ≤ 3）扫描第一个就绪法术。
   - **L2 兜底层**：全部法术不可用时回退普通物理近战攻击（GuardCombat.normalAttack，5 伤基础，受 SPELL_POWER 放大）。
2. **施法互斥锁与 CD 机制**：
   - `MagicState` 中的法术 CD 在施法互斥锁（法阵/引导阶段）占用期间**处于冻结状态**，锁释放后才开始倒计时。
3. **祭坛施法约束**：
   - 声明 `altar_only: true` 的魔法（如 revive）严禁被 NPC 直接自动决策施放，必须由玩家在祭坛 UI 发布任务后 NPC 走到祭坛中心施放。
   - 祭坛 CD 独立存储于 `AltarCastState`（按祭坛 buildingId 独立，不跨祭坛共享）。
   - **祭坛需要一名在世法师**，所以全员阵亡时祭坛跟着停摆。唯一的自举出口是市政厅面板的「复活法师」按钮（`ReviveHandler.reviveLatestAtTownHall`，全灭 + 该殖民地 5 分钟冷却），一次只救回一名，其余死者仍由他回祭坛接回。**不要再加回任何被动自动复活**：心跳轮询的全灭判定会因远处法师所在区块卸载而被误判为「全灭」。全灭判定必须走 ECS 桥（`onRemovedFromLevel` 只在 KILLED/DISCARDED 移除条目，区块卸载保留），不要用「已加载实体」枚举。
4. **第三方（Goety）聚晶施法 volley 化**（`compat/goety/`）：
   - `IChargingSpell`（EverCharge 持续 / 蓄力连发如 Steam / 呼吸）按 Goety 玩家侧语义：充能（`castUp/speed`）→ 按 `Cooldown(caster,staff,shots)` 节拍逐发 `SpellResult` → 打到 `shotsNumber()` 或单轮齐射硬顶自然收尾并上 `defaultSpellCooldown` 冷却。
   - 单轮齐射硬顶默认 100t，走 `MagicApi.get/setSustainedCastMaxTicks`（内部 BalanceValues，可被 wandscape_balance.json 覆盖），**不进 Config TOML**。
   - volley 全程**不占施法互斥锁**：各魔法 CD 照走；GuardCombat 每轮复选只让位「严格更高优先」法术，选回同 focus / 选不到都保持 volley；L0 紧急奶等其它施法经 `MagicSpellExecutors.dispatch` 打断 volley。
   - 魔力按 Goety 扣费节拍（EverCharge/呼吸每 20 发扣一次 `manaCost`、Steam 类每发扣一次），不足自动停、不罚 CD。
   - 遗留：非 `IChargingSpell` 但 `defaultCastDuration()>0` 的蓄力弹（PrismaBeam 类走 useSpell/stopSpell）仍被 instant 分支秒发，待修。
5. **魔法冷却 / 施法常量定义在各魔法自己的类里**：
   - 「每个魔法一个值」的冷却与时长常量（`GuardCombat.MELEE_COOLDOWN_TICKS`、`MagicCaster.BEAM_SPAWN_DELAY` 这类）一律放在该魔法逻辑所在类的顶部作为命名常量，**不要**上收到 `WandscapeConstants`：集中后改一个冷却要跨文件翻字段找，而放在魔法类里一眼就能看到。只有真正全局共享的跨系统常量才进 `WandscapeConstants`。
   - 读旧记录时注意常量名漂移：`GuardCombat.CAST_MIN_INTERVAL` 已重命名为 **`MELEE_COOLDOWN_TICKS`**（值仍是 40），别再按旧名找。
6. **《世界应答》（`world_response`）是两阶段施法，别当成普通法术改**：
   - **阶段一**（卷轴 / `castForPlayer`）**只打开选择**，不产生任何世界效果：`WorldResponseManager.begin` 记一条 pending（5 秒）并把允许的回应下发给客户端。**阶段二**（客户端轮盘 → `WorldResponseChoicePacket`）校验 pending 未过期 + id 合法，再交 `WorldResponseExecutors`。**执行成功才进冷却**（失败/未实现不进，便于反复调试）。
   - **pending 是防伪造闸门**：没有 pending 的选择包一律拒（客户端本地开屏 + 直接发包的方案做不到这点）。空 id = 取消（只清 pending）。状态是内存态、按世界游戏刻计时，过期项在 `begin`/`choose` 里顺手修剪；`WorldResponseManager.clear` 留给断线/换世界。传送 / 换维度 / 断线另外走 `WorldResponseManager.cancelPending`：只作废选择窗口、**不动冷却**（传送不该成为跳过冷却的手段）。
   - **四个回应不是法术**（`content/magic/worldresponse/WorldResponse` 枚举 + lang 键）：它们不进 `magic_spells/`，否则要连带处理「卷轴绑定 / NPC 装备 / JEI 图鉴 / 装备桶」四处清单。`SpellbookLoader.equippableCategoryOf` 已显式排除 `world_response`——**NPC 既不装备也不施放它**（它是玩家专属毕业魔法）。将来要扩成数据驱动（渡海/遁地/跃迁…）就把枚举提升成 JSON。
   - **选择界面是独立 `Screen`（`WorldResponseScreen`）而不是浮层**：本模组的「抬光标 + UI 点击路由」整条链挂在面板开关上（`WandscapePanelController` 先判 `isPanelOpen()`），野外施放时借不到；`Screen` 自带光标接管/释放，省掉一整套光标状态机。`isPauseScreen() = false`，世界照常跑。
   - **刻意不绑任何热键**：1-5 已被面板页签占用、1-9 是原版快捷栏。选择只走鼠标方向（离中心超过甜区即按角度归属最近扇区），左键确认、右键 / ESC 取消。**不要再给轮盘加数字键**——那正是热键冲突的来源。
   - 文案：法术名 `magic.wandscape.world_response[.desc]`、轮盘 `worldresponse.wandscape.*`、反馈 `message.wandscape.world_response.*`；改完照例跑 `gen_lang.py`。
7. **持续型世界回应（`WorldResponseEffect` / `WorldResponseEffects`）——回滚只有一条路**：
   - **开**：`WorldResponseExecutors` 调 `WorldResponseEffects.activate(player, effect)`；同 id 只允许一个（重复激活被拒且**不扣冷却**）。**收**：配套魔法《平息》（`world_response_calm` → `WorldResponseEffects.stopAll`）——企划案要的「持续时间 infinity 但必须能主动关」就落在这里。
   - **所有回滚都必须汇进 `stopAll`**：主动停止、玩家登出、**传送 / 换维度**、**关服**（`ServerStoppingEvent`）几条路都在 `WorldResponseEffects.register()` 里接好。原因很实在：效果改的是真实地形，只存内存快照的话，「效果没了地形没还」就是永久性的坑——尤其换维度，快照记的是原维度坐标，必须先还回再走。
   - **传送一律用「之前」的事件停**：`EntityTravelToDimensionEvent`（换维度之前，人还在原维度）与 `EntityTeleportEvent`（监听器挂父类，`/tp`、spreadplayers、末影珍珠、紫颂果、末影人瞬移都会进来；非玩家实体由 `instanceof ServerPlayer` 挡掉）。事后事件 `PlayerChangedDimensionEvent` 只作补网。整合包里不走这两个事件的传送（传送石碑之类）由**每 tick 位移突变**兜底：单 tick 位移超过 `worldResponseTeleportJumpDistance`（默认 16 格；玩家自由落体终速约 4 格/tick，鞘翅火箭与激流远低于它）就按被传送处理。停下会发一条 `message.wandscape.world_response.teleport_stop`，玩家重新施放即可——**宁可少维持一会儿，也不要把某台机器的核心方块留在卸载的区块里**。传送同时会作废还没选完的轮盘窗口（`WorldResponseManager.cancelPending`），免得「开着轮盘被传走、在新位置一确认就生效」。
   - **第一个落地的是「移山填海」（`TerraformEffect`，id `terraform`，即企划案的「开路」升级为持续型）**：清理玩家**脚下那层与身体那层**（`dy = 0..1`）圆形半径内的阻挡——**脚下那一格不抽**（只有它是液体时才垫，见下面「踏水」那条）、头上那格也不碰；「挡路」的判定 = 有碰撞箱 **或**是液体（水会推人）。
   - **写入标志是这套东西的关键，别只用 flag 2**：改写用 `WRITE_FLAGS` = `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS`（2|16|32 = 50，与施工侧 `WandscapeBlockOps` 同一套）。**踩过的坑**：`UPDATE_CLIENTS`（flag 2）跳过的只是**邻居方块更新**（`blockUpdated`，红石/重力那套），但 `Level.markAndNotifyBlock` 里那段 `updateNeighbourShapes`（**邻居形状更新**）要 `(flags & 16) != 0` 才跳过——只用 flag 2 时，雪层 / 火把 / 花草 / 铁轨这类**靠支撑的方块会在我们把支撑抽走的瞬间碎掉**，而它们不在我们的快照里，回滚也还不了（实测抓到的 bug）。客户端本来就是用 flag 19（含 16）应用服务端方块更新的，所以 50 不会造成客户端不同步。
   - **还原要「先整批静默写、再统一唤醒邻居」**：`BorrowedBlocks` 的批量还原先按 **Y 从小到大**逐格用 `WRITE_FLAGS` 静默写回（先还支撑、再还靠支撑的），整批写完后对每一格 `updateNeighborsAt` + `updateNeighbourShapes` 唤醒一次。为什么不能一格一格用 `UPDATE_ALL`：床、门、雪这类方块会互相"看对方在不在"，伙伴还没还回来时先还的那一半会当场碎掉（掉落物就真丢了）。唤醒那一步才让物理照常回归——该落的落、该流的流，且是在所有格子都回原位之后。
   - **五类方块/位置永远不动**：① **玩家自己列的名单**（`WorldResponseProtectionSavedData`，指令 `/wandscape response protect|unprotect|list`）；② 传送门本体（`state.is(BlockTags.PORTALS)`——原版门方块本身 `getDestroySpeed < 0` 已被不可破坏那条挡住，但整合包的自定义门不一定）；③ **与传送门相邻一圈的方块**（3×3×3 邻域里出现门方块就整块跳过：门框就是紧贴门的那一圈，且门的四个**角**框与门方块只是**斜邻**，只看上下左右会漏，而拆掉任一角框都会让 `PortalShape` 判为不完整、门在下一次方块更新里熄灭）；④ `getDestroySpeed(level,pos) < 0`（不可破坏，用原版语义而不是维护名单）与 `hasBlockEntity()`（容器/告示牌，清了会吞内容物）；⑤ `ColonyLandProtectionHandler.isProtected`（属于任何建筑的地皮——世界让路不拆别人的房子，包括施法者自己的；这条对垫脚层同样生效）。
   - **黑名单是「每个玩家一份」的偏好，不是服务器规则**：落在主世界 DataStorage（`wandscape_world_response_protection`，带 `version`，读到不认识的版本整份忽略并记 warn），因此谁都能加自己的、不用权限、不影响别人。指令挂在**面向玩家**的根节点上（`/wandscape response`），**不要**塞进 `/wandscape test`——那整棵是 op-2，而这份名单是玩家自己的偏好。`protect`/`unprotect` 省略参数 = 准星正对着的方块（`player.pick(..., includeFluids = true)`，所以能直接点水面/岩浆面）。名单是**方块粒度**（一加就是所有朝向/状态），且**覆盖不了上面②~⑤那几条硬保护**。
   - **踏水/踏岩浆：脚那格是液体就垫一层「液面替身」**（`state.getBlock() instanceof LiquidBlock` 才算「本身就是液体」——含水台阶/楼梯那种本来就站得住，不碰）。替身是模组方块 `wandscape:world_response_water` / `_lava`（`SolidFluidBlock`）：**贴图、高度、颜色都与原版液面一致，但有完整碰撞**——水贴图 + `render_type: translucent` + 生物群系水色（`WandscapeClient.onBlockColors` 接 `BiomeColors.getAverageWaterColor`，与液体渲染器同一套取色），高度照抄原版液面的 **8/9 格**（`FlowingFluid.getHeight`：上方没有同种液体时取 `getOwnHeight()`），所以替身与紧邻的真液面**齐平**、不会在水面上露出一条台阶；六面都带 `cullface` + `skipRendering` 对「同类替身 / 同种液体」跳过，免得半透明面互相重绘出深色接缝（原版 `LiquidBlock` 对同种液体就是这么做的）。它们不可破坏、不掉落、不遮光不窒息、没有脚步声、活塞推不动，**不注册物品也不进创造栏**——只由本效果临时放下。垫的是**脚那一格而不是它下面那格**：人被顶到液面上站着，于是不必在水里挖坑、水面只少掉「他正踩着的那一层」（脚那格已是空气时才往下看一格，所以在液面上走是稳定的）；走开就还回液体。整合包的自定义液体没有替身，退回 `Blocks.BARRIER`（不好看，但照样能站）。**想潜水就自己停下来**（《平息》或走开），这是设计取舍不是 bug。黑名单与建筑地皮对垫脚层同样有效——地皮内的水面不垫，你会像平常一样游过去。
   - **热伤害免疫由效果自己声明**（`WorldResponseEffect.wardsHeat()`，移山填海返回 true），管理器在 `LivingIncomingDamageEvent` 里统一判断：只挡**环境热**——`LAVA` / `HOT_FLOOR` / `IN_FIRE` / `ON_FIRE` 四种。**刻意不用 `DamageTypeTags.IS_FIRE`**：原版那个标签连 `FIREBALL`/`UNATTRIBUTED_FIREBALL` 一起收（见 `DamageTypeTagsProvider`），拿它当挡箭牌等于顺手送玩家一份火焰免疫。
   - **借出去的位置禁止第三方改动**：`WorldResponseEffect.holds(level, pos)` 让管理器知道哪些格子正被借用，`WorldResponseEffects` 在 `BlockEvent.BreakEvent` / `EntityPlaceEvent`（父类，含多格放置）/ `FluidPlaceBlockEvent`（流体想在那造石头/黑曜石）/ `PlayerInteractEvent.LeftClickBlock` 里一律取消，`PistonEvent.Pre` 则把**借用点及其外面一圈（6 邻域）**所在的推程线整条挡下（扫活塞脸前 13 格：原版最多推 12 格 + 活塞头那一格）。玩家收到一条 10 tick 节流的提示（`message.wandscape.world_response.borrowed`），非玩家实体只记日志。**为什么这么硬**：借出去的位置一旦被改写，回滚时就分不清「该还原成什么」——轻则水位永久回不去，重则把别人的建造覆盖掉；外面那一圈是照「这一带在借用期间冻结」的口径一起挡的。
   - **每一轮扫描重新压实一次**（`BorrowedBlocks.keepShaped`，快照表存的是 `Held(original, left)`）：flag 2 只保证「我们不通知邻居」，**拦不住别人引发的方块更新**——附近只要有别的方块变化（别人放东西、活塞动作、流体自己流），水就可能重新流进我们挖开的空位。所以每轮扫描把被空气/流体顶回来的格子重新压成 `left`；只有真被**实心**方块盖进来才让出这一格并记日志（别人的建造优先于我们的回滚），这条也是 `restore` 的兜底口径。
   - **「借走的位置」这套规则只有一份**（`BorrowedBlocks`，移山填海与扶摇共用）：记账（`take`/`reshape`）、出圈还原（`restoreOutOfRange` / `restoreNotIn`）、每轮压实（`keepShaped`）、未加载区块不写不丢（`restoreAll(loadChunks)`）、以及事件门要问的 `holds(level,pos)` 全在这里。**新写持续型回应就直接用它**，别再各写一套。
   - **三条不变式（回滚/接管审查时补的，改动这几个文件前先看）**：① **同一格同一时刻只归一个效果**——`BorrowedBlocks.take` 会先问 `WorldResponseEffects.isBorrowed`，而这个查询**把"已经停下、还有方块压在未加载区块里等重试"的效果也算作持有**；不这么做的新效果会把旧效果留下的方块当成"原位"记下来，最后回滚出一个谁都没见过的方块，而旧效果那次补做的回滚又只能放弃那一格。② **借用优先级：移山填海（`PRIORITY_TERRAFORM`）> 扶摇（`PRIORITY_DEFAULT`）**——优先级**严格更高**的那个可以先手，但必须走 `WorldResponseEffects.releaseFor(...)`：**先让低优先级把那一格还回原位，再接管**（直接抢会把对方留下的方块当成"原位"，见①）；同或更高优先级拿着就不动，所以扶摇铺不进移山填海借走的格子。③ **所有写入路径都不为写方块加载区块**（`take`/`reshape`/`restore`/`keepShaped` 一律先 `isLoaded`；唯一例外是关服收尾那一次的 `loadChunks = true`）。
   - **第二个落地的是「扶摇」（`LiftEffect`，id `lift`）——周围一圈铺平台、沿前进方向长楼梯，把人托上去**（与移山填海同一套口径：每轮扫描改一遍身边的区域、借用位置、出圈还回；区别是扶摇**加**东西而不是拿东西）：
     - **脚下一层是平台**：`feet.below()` 那一层、半径 `worldResponseLiftPlatformRadius`（默认 4）的圆盘里，**空气**一律变成**上半砖**（`stone_slab[type=top]`，顶面正好在玩家脚那一层）。站在平地上时这一层本来就是实地、什么都不做；一旦脚下是空气（爬楼梯 / 跳 / 掉下去）宽平台立刻出现——这就是「凭空托住」，也是**转身踩空**的兜底：脚下永远是一大片而不是一格。
     - **踩过的楼梯自动收薄**：玩家完整踩上一级时，那一级正好落在平台层里（平台层永远是脚下面那一层），于是被换成上半砖——顶面高度不变所以不会被顶，只是身后那一级薄下去。玩家还站在楼梯**前半格**时，他脚所在格就是楼梯自己那格、平台层是它下面那格，所以不会提前收。
     - **楼梯段**：沿前进方向、从脚那一层起步，铺 `worldResponseLiftStairs`（默认 6）级 × `worldResponseLiftStairWidth`（默认 3）格宽（居中于玩家），第 i 级在「前面第 i 格、抬高 i-1 格」。用真楼梯方块是因为它的碰撞天生是「前半格 0.5 + 后半格 1.0」——一级正好抬 1 格而玩家**不用跳**。扫描间隔 `worldResponseLiftScanInterval`（默认 4t，比移山填海密，要跟住走路节奏）。
     - **只有「空旷地带」铺得出来**（扶摇的用途是露天攀升，不是拆家）：① 每格必须是**空气**——**绝不替换任何已有方块**；② **人得在露天**（`canSeeSky(头那一格)`：屋顶下 / 洞里 / 水下整轮不铺，上一轮的形状按"这一轮想要什么"收回去）；③ **楼梯逐格**再要求见得到天（免得长进山体、屋顶里）；④ 不在**建筑地皮**上（`ColonyLandProtectionHandler.isProtected`）。于是玩家既不会拿它覆盖自己的红石/机器，也不会在别人的结构里凭空长出平台。**为什么露天看头那一格**：脚那格常被我们自己铺的平台/台阶占着；而逐格 `canSeeSky` 会被我们自己的楼梯挡住，所以"人在露天"只判一次、楼梯才逐格判。
     - **方向与位置**：方向用两次扫描之间的**服务端位置差**取主轴（服务端玩家的 `deltaMovement` 不可靠）；**斜着走（两轴分量差不多，比值 > 0.75）时不猜主轴，直接看视线朝向**——那才是玩家想去的地方，也免得楼梯在两条轴之间来回跳、铺成锯齿。位置永远锚在玩家身上。每轮扫描算出「这一轮想要的格子」（平台盘 + 楼梯段），不在其中又离玩家超过 `worldResponseLiftRestoreMargin` 的旧格子逐格还回。
     - **停下来之后台阶不会马上散**：方向停采样时**沿用上一次的方向**继续把那段楼梯算进「想要的格子」，直到停下超过 `worldResponseLiftHoldTicks`（默认 60t = 3 秒）才放手（实测反馈"刚走两步台阶就散"）。**转向之后会把已经铺好的楼梯就地改朝向**（`reshape` 只换 `left`、原位不动）——否则旧朝向的台阶会横在新路中间，爬不上去；玩家自己踩的那一级不在楼梯段里（i 从 1 起），所以不会改到他脚下。
   - **回滚不覆盖别人的建造**：还原一格前先看它现在是不是「还维持着我们留下的样子或本来就该有的液体」（空气 / 液体 / 我们垫的替身或屏障）；真被实心方块占了就**放弃这一格**（记一条日志）并把快照丢掉。
   - **回放的触发**：走出「半径 + 余量」（逐格还，效果跟着人走、身后不留疤）、主动停止、以及上面那几条生命周期路径。**扫描有节流**：默认 **3 tick**（0.15 秒）——早先的 10 tick（0.5 秒）实测"响应太慢、冲刺时会撞墙"（0.5 秒够冲刺跑 2.6 格，墙可能已经贴脸了）。半径/间隔/余量三个旋钮在 `BalanceValues`：`worldResponseTerraformRadius` / `worldResponseTerraformScanInterval` / `worldResponseTerraformRestoreMargin`。
   - **未加载的区块：不写、也不丢**。清理只发生在玩家身边（那时的区块必然加载着）；回放时若那一格所在区块已卸载，就**留着快照等它回来**——绝不为回放调用 `setBlock`（`Level.setBlock` 会经 `getChunkAt` **同步加载区块**：主线程卡顿，还会把玩家没去过的区块拉进内存），也绝不把还不了的记录删掉。`WorldResponseEffect.stop(player, loadChunks)` 因此返回布尔值：`false` = 还有方块压在未加载的区块里，`WorldResponseEffects` 把它挂进 `PENDING_ROLLBACK` 每 20 tick 重试一次，区块回来了就还上。
   - **关服那一次是最后机会，允许为回滚把区块读回来**（`loadChunks = true`，全仓只有这一处）。能这么做是因为 `ServerStoppingEvent` 在**存档之前**触发：`MinecraftServer.runServer` 的 `finally` 里先 `handleServerStopping`（post 事件）再 `stopServer()`，而 `saveAllChunks` 在 `stopServer()` 里面——所以这一次写回去的方块会落盘。**非正常关服（崩溃 / 断电）仍可能留下坑**：快照只在内存里，这是已知取舍（要彻底解决得上 SavedData，眼下不值）。
   - **Tick 里抛异常 = 立刻停掉该效果**（记 `Log.warn` 并走 `stopAll` 那条回滚），不允许带着半截状态继续跑。

8. **《世界应答》的测试期获得路径：`player_castable` + 两张测试卷轴配方**（正式落地「获得仪式」后要一起收掉）：
   - 卷轴（`SpellItem`）**默认只有创造模式能右键施放**（历史行为，因为它本来是给法师装备用的物品形态）；`MagicDef.playerCastable`（JSON `player_castable`，默认 false）是**测试版开关**：开了它，生存玩家也能右键施放。《世界应答》两个法术都开了——它们在 `SpellbookLoader.PLAYER_ONLY_SPELLS` 里，NPC 本来就不装备不施放，所以卷轴对它们只有「玩家自己用」这一条路径，开这个开关不会和 NPC 装备设计打架。
   - 两张测试卷轴配方：`craft_recipes/scroll_world_response.json`（成本 水/木 各 100）、`scroll_world_response_calm.json`（各 50），都在**魔法工坊**（`magic_station`）、**不设解锁门槛**（缺省即 `minColonyLevel = 1`）。卷轴**用一次不消耗**（`SpellItem.use` 不 shrink），方便反复试。
   - tooltip 会按 `playerCastable` 换一行提示（`item.wandscape.spell.player_castable_hint` / `creative_hint`）；被拒绝时的提示仍是 `creative_only`。

---

## 四、任务与 ECS 域 (`content/task`)

1. **任务发布显式绑定 colonyId**：
   - `TaskRequest` 中的 `colonyId` 是显式必须字段。建筑/生产任务必带对应殖民地 ID；无主任务（如通用守卫）只派发给真实殖民地 NPC，未注册/全零占位殖民地 NPC 绝不派活。
2. **纯逻辑零 MC 依赖**：
   - `content/task` 内的核心 ECS、任务评分、调度算法、状态计算严禁 import 任何 Minecraft / NeoForge 类，保持纯 Java 运行与快速测试能力。
3. **蓝图 Java-lambda 化**：
   - 蓝图 DSL 解释器已废除，默认蓝图全部收敛为 `content/task/engine/dsl/BlueprintDefaults.java` 中的 Java lambda 函数注册。
4. **任务链的实体解析只有一个点：`content/npc/worker/ColonyWorker`**（2026-09-12 起）：
   - 原子操作执行器只收 `ecsId`，类型墙就在这一处。7 个 MC 边界适配器（`EntityOps` / `MovementOps` / `RitualOps` / `BlockInteractExecutor` / `AsyncTransformExecutor` / `ResourceRequestExecutor` / `NavigationSystem`）一律经 `EntityComponentBridge.getWorker(ecsId)` 拿 `ColonyWorker`，**不要再写 `instanceof WandscapeNpc`**；确需法师专有能力时另取 `getNpc()`（它只认本模组法师，第三方工作者返回 null）。
   - 接口只有 `entity()` / `colonyId()` 两个抽象方法，其余全是 default——default 就是"普通 Mob 当工人"的基线（原版寻路、中性属性、无魔力无法术），`MobColonyWorker` 几乎不覆写任何方法即是证明。实现新适配器先看 `api/ColonyWorkerApi`。
   - **两个已知缺口**：`ColonyWorker` 在 `content/` 而非 `api/` 且无 `@ApiStatus.Internal`（公开面与内部包的边界是意图、没有机制）；`enlist` 写死 `MobColonyWorker`、无工厂扩展点——想接自带属性成长/魔力/法术/自定义导航的实体，只能改本仓库。动手前先确认这是不是真需求（硬规则 6）。
   - ⚠️ **殖民地归属有两处真相**：适配器上的 `colonyId()` 与 ECS 的 `ColonyMember` 组件。`AsyncTransformExecutor.resolveColonyId` 优先读组件；换镇时两者都要更新（`MobColonyWorker` 有 `setColonyId` 可原地改；接口实现若把 `colonyId` 定成不可变，换镇就得拆掉重建）。新写任何"改归属"的代码都要同时处理这两处。
5. **建筑委派（Building Delegation）：「一名法师专职一座建筑」（2026-10-06 起，改任务派发/建筑面板前必读）**：
   - **三条不变式**（规则唯一命名类 `content/building/internal/BuildingDelegation`）：① 委派法师**只接该建筑派发的任务**——别的建筑的任务、`guard:attack` 与祭坛施法（两者都没有建筑归属）一律不接；② 该建筑的任务**只派给**它的委派法师，它不在岗（在别处干活/跟随/走远了）就**等着**，绝不降级给别人；③ 一名法师同时只服务一座建筑，再委派等于把它从原岗位调走（存储层 `BuildingSavedData.setDelegatedMage` 保证这条）。
   - **白名单只有一处**：`BuildingDelegation.SUPPORTED_CATEGORIES` = 物品工坊 / 装备工坊 / 魔法工坊 / 采集节点；面板按钮与网络层校验都读 `BuildingDelegation.supports`，加类别只改这一处。刻意**不**复用 `BuildingSavedData.SHARED_QUEUE_CATEGORIES`（眼下同集）——「共享队列」与「可委派」是两套规则，谁先变都不该拖着另一个走。
   - **存储与生命周期**：`BuildingState.delegatedMage`（NPC UUID，随建筑存档）+ `BuildingSavedData.delegationByMage` 反查索引（避免调度器每拍每法师遍历全部建筑）。建筑拆除/撤销时 `unregister` 顺手摘索引，委派随建筑一起消失；法师阵亡/解散时 `EntityComponentBridge.onWorkerLeaveWorld` → `BuildingDelegation.onMageGone` 解约——否则那座建筑留着一个永远不到岗的岗位，它的任务会全部卡死。**「阵亡」与「区块卸载」必须分开**：两者 `isRemoved()` 都为真，只有 `RemovalReason.shouldDestroy()` 分得开（`BuildingDelegation.workerGone`，与 `ReviveHandler.isBridgeEntryAlive` 同一口径）；判据收在 `onMageGone` 内部，因为第三方工作者的对账钩子（`ColonyWorkerApiImpl.tick`）把区块卸载也当成"离开 ECS"，而卸载的法师还会回来。
   - **判定只有两处，别在别处再写一套**：① `SchedulerSystem` 的派发门槛（按 `task.buildingId`——**具体那座建筑**，不是类别；共享队列弹出的任务在 `BuildingTaskPool.enqueue` 时就绑到认领它的那座建筑上，所以按 id 判足够精确）；② `TaskExecutionSystem.processNpc` 步骤 1.5 的 `violatesDelegation`，错配的走既有的 `releaseForInterruption`（保留步进 + 退还已取元素 + 丢全局包 + 保留个人包），并 `SchedulerSystem.requestImmediatePass()` 催下一 tick 立刻按新归属重派。
   - **为什么执行侧还要逐拍判**：委派是玩家随时可改的配置，而任务是在改配置**之前**派出去的——调度器门口只挡得住"新派"，挡不住"已经在跑"。判据必须读 `exec.globalTaskId`（包绑定后才有；自防御抢断只是把包压进挂起栈，**不会**清这个绑定），所以判定点放在 `processNpc` 里紧跟包启动之后，**不能**提前到 `update` 的更早位置：那会顺手把挂起栈（自防御抢断的包）的恢复时序一起改掉。
   - **生效时延（改文档/面板文案时别写成"即时"）**：改委派后，错配任务在**下一拍**被释放回池（保留步进并退还已取元素），执行侧同时 `requestImmediatePass()`，所以**下一 tick** 就会按新归属重派（不必等心跳）；那件活会从第一个取料步骤重来。旧的「等满一轮心跳（20 tick）才重派」口径已作废。
   - **读侧走边界，调度器保持零 MC 依赖**：`EntityOps.delegatedBuildingOf(npcId)` / `delegatedNpcOf(buildingId)`（实现取主世界 `BuildingSavedData`）。`delegatedNpcOf` 对「查不到这名法师」返回 -1，即**按未委派处理**——存档里万一留下过期 UUID，表现是那座建筑退回常规竞派，不会把任务卡死。
   - **多法师批次同样受约束**：批次接续走的是同一条调度链路（`fairOrder` + 派发门槛，见 §五.17），没有第二条会绕过委派的自派活路径。
   - **面板入口**：委派是策略决定，档位按 MANAGER（`BuildingDelegatePacket`）；候选列表由 `BuildingDelegateDataPacket` 下发，选人框 `MageDelegateDialog` 挂在 `MedievalScreen` 基类——以后别的建筑类别要开委派，改白名单即可，UI 不用动。
   - **已知取舍**：委派不搬人——法师靠自己走到工地，所在区块卸载时该建筑的任务就一直等它（这正是委派的语义，不是 bug）。
6. **法师放置方块的速度 = 每 tick 的旁路 op 额度 = `floor(工作速度)`（至少 1 格）**（2026-10-06 起，改放置速度/工作速度曲线前必读）：
   - 一格方块就是一个 `TransformOp`，所以「一拍放几格」＝「一拍执行几个**拍内完成**的旁路 op」。额度在 `TaskExecutionSystem.instantOpBudget` 单点计算（`EntityOps#getWorkSpeed`，取的是**有效**属性：含等级加成与装备），循环里以 `sideEffectBudget` 倒扣，扣完就 `break`、剩下的留给下一 tick。原先那条「一拍只允许一个旁路 op」（外加 same-target 特例）已删除；same-target 特例的删除是安全的——连续两 op 打同一格时第二 op 会走 4a 的「已是目标方块就跳过」判定，只是从一拍变成两拍。
   - **前提是放置 op 拍内完成**：`EngineBootstrap` 把 `AsyncTransformExecutor` 的 `delayTicks` 置 0。若把它改回 `>0`，引擎会重新等每格的 future（每格占 N 拍），那条路上工作速度额度用不上——两个旋钮别同时开（类注释里写明）。
   - **两条路共用 `AsyncTransformExecutor.placeNow`**（清障回收 → 置块 + 方块实体数据 → 工作动作 + 节流施法音），所以 delay=0 也不会退化成「方块在长、法师不动」——拍内路径原先恰好缺这一段（不挥手、不出声）。
   - **只管拍内完成的 op**：拆除、铺地这类 TransformOp 同样按额度加速；采集 / 合成 / 仪式 / 整箱清空各有自己的多 tick 预算（`ClearBoxExecutor.VOXELS_PER_TICK`、`WandscapeBlockInteractExecutor` 的 channelTicks ÷ 工作速度），不吃这个额度、也不会被它加速。
   - ⚠️ **台阶很硬，别在别处偷偷加码**：工作速度 1.99 → 1 格/拍、2.0 → 2 格/拍（直接翻倍）。招募曲线 `NpcAttributes.BASE_SPECS` 是 0.5~1.5、每级 +0.05，所以低阶法师基本还是 1 格/拍，跨过 2.0 / 3.0 才质变。要平滑收益（如 1.5 → 平均 1.5 格/拍）就得把额度换成小数累积器，改一处即可。
   - **副作用**：面板的「预计完工」（`ConstructionSiteDataPacket.Estimate` 的 `placeCD × remainingBlocks`）仍按恒定每格耗时估，工作速度 >1 时会偏悲观、多法师并行也没折进去——那本是粗估，要跟新口径就得改估算公式（本次未动）。

---

## 五、建筑与扫描域 (`content/building`)

1. **建筑扫描器保真分层导出**：
   - `ScannerBlockEntity`（生存扫描器）导出时 `isSafeExport() == true`，强制跳过所有方块实体 NBT，并剥离物品展示框内的物品，彻底防止玩家通过扫描容器刷物品。
   - `CreativeScannerBlockEntity`（创造扫描器）完整保真导出 NBT。
2. **关键基础设施拆除防护**：
   - 全世界范围内仅剩最后 1 座市政厅（government）、仓库（storage）或物品工坊（workstation）时，禁止拆除或取消，防止殖民地系统运转瘫痪。
3. **向下兼容目录不可删**：
   - `src/main/resources/data/wandscape/buildings/deprecated/` 包含旧存档兼容建筑载荷，属于必须加载项，禁止删除。
4. **重叠规则——盒可叠、方块不可叠（判定唯一源 `BuildingVoxels`）**：
   - 建筑注册的唯一限制是「同一世界坐标不能被两座建筑 pattern 共用」；boundary 包围盒可任意重叠，室内/贴墙/嵌套建筑都允许。
   - 归属：建筑自注册起拥有其 pattern 所占每个格。拆除=清、修复=补只落 pattern 坐标；盒内非 pattern（空气/家具/装饰/嵌套建筑的墙）**在清盒开关关闭时**永不触碰。玩家替换进 pattern 格的方块会照拆、修复也会覆盖回原样（该格归建筑）。
   - 建造默认「清整盒」（见规则 5）：开着会把盒内非 pattern 方块清掉，室内/嵌套内容会被扫——要叠放/嵌套必须先关清盒开关。拆/修不受清盒开关影响。
   - 重叠判定是两阶段：先拿 pattern 所占 AABB 粗筛、不相交直接放行，只有相交对才精判格子。服务端 register 与客户端幽灵预览**共用** `BuildingVoxels`，改重叠逻辑只动这一处，别在两处各自写一套。
   - 旋转：pattern/精判/预览全部按 `rotationSteps` 旋转后的世界坐标算，动手前先查 `BuildingRotation` 调用链。
5. **建造清盒默认开，可关（Build 子模式右侧参数栏开关，本次会话记忆）**：
   - 默认「是」= 等价 7a254bca 之前行为（整盒清后砌墙）：`EnqueueHelper.buildWorkItem` 组 params 时把**旋转后 boundary 盒内无 pattern 映射的格**补成 `minecraft:air` 映射并前置进 offsets，`clear_and_build` 单遍放格即得「先清盒后砌墙」的最终世界态；air 免费、不耗材料，且 op 数=盒体积一遍（比旧 clear_offsets 双遍法省一半）。
   - 关掉「否」= 纯 pattern 放置（7a254bca 之后行为）：盒内残留/叠放内容不清扫，室内、紧凑、嵌套建筑可建。**叠放前务必关清盒**——开着会把盒内别座已注册建筑的方块清掉（注册只挡 pattern 同格，不挡盒）。
   - 修复（`place_structure`）、拆除（`demolish_structure`）自组 params、只落 pattern 格，与清盒开关无关，永不清整盒。
   - 无客户端开关的路径（fill 调试指令、建镇脚手架等）默认按「是」处理。
6. **锚点与查询不再依赖盒互斥**：
   - anchor 落在其它建筑盒内是合法的（室内建筑的前提）。完成/拆除事件优先按 `building_id` 定位（`EnqueueHelper`/repair 都带该参），别只靠 anchor 反查。
   - posIndex 加载时从持久化 pattern 重建，重启后点 pattern 格仍精确归属；盒兜底/交互区查询取「最内层（体积最小）」建筑。
7. **撤销建造按「已扣建材账本」退，别按图纸退**：
   - 建材在开工那一刻由 `request_resource` **一次性扣除**（`ResourceRequestExecutor.finish` 提交），扣成功后回填该建筑的账本 `BuildingState.chargedMaterials`。撤销时逐项退 `min(未建成需求, 账本余额)` 并销账——账本是退还的**唯一依据与上限**。
   - 未开工（含仓库缺料停在 `AWAITING_RESOURCES`）与首建免费（`EnqueueHelper` 的 `skipMaterials`）账本为空 → **一分不退**：材料从没被扣过，照图纸退款就是凭空造物（`isConstructionStarted` 只是「有 NPC 领过任务」，不能当扣款依据）。
   - 已建成的方块不退：拆除 `demolish_structure` 用 `UPDATE_SUPPRESS_DROPS` 放空气，不清扫掉落物，那部分建材就此消耗（旧注释里说的「salvage 掉落」不存在）。
   - 撤销会同时撤掉还在飞的 `request_resource`（`BuildingTaskSource.cancelBuildingTasks` → `ResourceRequestExecutor.cancelForNpc`，只释放仓库预占）：否则飞行途中撤销「先退（账本还空）后扣（到达才提交）」，玩家白丢一整份建材。
   - 拆除（destroy）路径**不走退款**，别给它加余额退款：玩家自己敲掉的方块已掉过掉落物，再退就是双份。
8. **工地建材自动补料「放下时补一次，之后是玩家的事」（`content/building/internal/ConstructionSupply`）**：
   - 放下建筑/道路时按「需求 − 仓库库存 − 已在制」把缺口下成物品工坊的 `production:synthesize`（自动档 40），补上了才在 `BuildingState.autoSupplyDone` / `RoadEdge.autoSupplyDone` 上记一笔。**补上了就不再自动补**：工地缺料此后只能在工地面板点「一键制作」（玩家档 80，不记账、可反复点）。
   - 补不了（殖民地还没有物品工坊、配方还没解锁）时不记账，由 `ResourceSupplySystem.scanAwaitingTasks` 的 40 tick 重扫顺延补一次——只要没补上，重试就一直有机会；补上后闸门落下。
   - 光在放下时补一次不够：`ResourceShortageHandler`（任务转入 `AWAITING_RESOURCES` 时）与 `scanAwaitingTasks`（等料任务每 40 tick 重扫）**两条路径都会自动补产**，玩家在物品工坊队列里删掉自动补的合成任务后，2 秒后就又长回来。所以两处都要拦：前者看 `ResourceShortageHandler.Context`（`GlobalTaskPool` 把任务的 blueprintId/buildingId 传进来），后者看任务自身。
   - 拦的是「工地」，不是「建造蓝图」：`build:place_structure` 也用于**修复**，但修复只发生在已建成建筑上（`BuildingData.hasEverCompleted()`），照旧自动补产。建筑实际用的蓝图是 `build:clear_and_build`，两个 id 都要认。
9. **配方解锁沿原版合成树推导（`VanillaRecipeTree`）**：
   - 解锁一件东西会**连带**解锁"每个材料槽都有已解锁选项"的下游配方并递归下去（知道橡木木板，再存入一次白色羊毛，白床跟着解锁），推导结果同样永久入档，来源标 `recipe_tree`。默认清单（`default_recipes.json`）也当已知材料参与，所以新镇开局就有 200 多条恒定已解锁——判断"这料能不能自产"时别默认"没亲手拿到就永远锁着"。
   - 因此自动补料的门（`isSynthesizeUnlocked`）比"玩家拿过什么"**宽**：工地与商店能自产的料可能比预期多。但要按**材料**判断而不是按**成品**：树只能推出材料齐全的东西，`end_rod`（烈焰棒）、`iron_ingot`（矿）、`white_wool`（线）这类上游没解锁的照样推不出来。
   - 图挂在配方管理器**实例**上，`/reload` 换实例即重建；每镇按内容指纹（`ColonyRecipeSavedData.tree_fingerprint`，v2 起）决定要不要重扫，稳态启动零开销。改配方表（数据包 / 整合包 / 版本升级）后旧档靠这次重扫自愈。规则与实测数字见 `docs/data-formats.md` §五.1。
10. **奇观建筑落成触发机制（`WonderCompletedEvent` 与 `WonderTriggerRegistry`）**：
   - **触发时机**：当任何 `category == "wonder"` 的建筑在 `BuildCompleteListener` 中建成时，自动触发。
   - **双层解耦架构**：
     - **底座事件 `WonderCompletedEvent`**（发布至 `NeoForge.EVENT_BUS`）：供外部解耦系统（如 `AchievementService` 奇观成就结算、任务系统等）监听，无需感知各个具体奇观的内部细节。
     - **专用注册中心 `WonderTriggerRegistry`**：以策略模式支持按 `buildingTypeId` 或谓词（`Predicate<WonderTriggerContext>`）注册业务处理函数。
   - **首建与复原防重入（`firstCompletion`）**：奇观若损坏后通过 V 面板「复原」虽也会触发完工，但 `state.hasEverCompleted()` 为 true。注册器通过 `firstOnly = true` 确保一次性重磅奖励（如全套配方解锁）仅在小镇历史上首次落成时触发，杜绝玩家刷取奖励。
   - **内置奇观：魔法学院（`magic_academy`）**：首建落成自动调用 `ProductionRecipeManager.unlockAllSynthesize(colonyId, "wonder:magic_academy")` 为该小镇解锁所有合成配方，并向全镇成员及附近玩家广播 ScreenFeedback 横幅与系统消息。
   - **调试与测试指令**：`/wandscape test wonder list`（查看已注册触发器）及 `/wandscape test wonder trigger <buildingType> [firstCompletion]`（手动对当前小镇触发奇观落成效果）。
11. **发布预检看的是「共享组队列 ∪ 自有队列」（`getClaimableWork` vs `getQueue`）**：
   - `getQueue` 的「共享组队列优先」是**UI 与索引操作**的语义；**发布预检**必须用 `getClaimableWork`（两边并起来看，与 `dequeueWorkEligible`「共享队列弹不出就落自有队列」对齐）。
   - 拆除 `build:demolish_structure` 与复原 `build:place_structure` 进的是建筑**自有队列**。共享队列类别（workstation / crafting_station / magic_station / node，见 `BuildingSavedData.groupKeyFor`）的建筑若预检只看 `getQueue`，会被永远判为「无活可发布」：任务静静躺在队列里 NPC 永不开工，而队列面板走同一路由也被遮蔽、显示为空。表现就是「撤销/复原点了没反应」。
   - 空组队列**不算**「有队列」：`BuildingSavedData.peekSharedQueue` 对空队列按不存在处理并顺手摘掉条目（`save` 本就不落盘空队列，空条目是纯运行时残留）。留着空条目会让运行时状态与存档态分叉，造出「本次会话卡死、重进世界自愈」的假象——排查同类问题时，「重进就好」是内存态与落盘态不一致的强信号。改队列路由前先确认这三处的分工。
12. **清场（`ClearBoxOp`）的两条不变量**：
   - **排除集不能省**：清场一旦碰 pattern 格，会把已经放好的方块回收进仓库、再由放置 op 从 NPC 背包重放一遍；仓库满时 `dropSalvageOnGround` 那一份就是一次**物品复制**。
   - 物料统计**不该向仓库要空气**：这条不靠特判，靠「空气没有元素映射」自然成立（`computeMaterialCounts` 只统计 `mapped` 的 palette 项）。
13. **工地 / 虚影渲染的已知残留（改投影渲染前先看）**：
   - `BuildingGhostRenderer.drawGhostSkipped` 每 tick 会把**所有可见段**重建一遍（样本楼 803 个非空 16³ 段，中位 468 格/段，最大 2322）。要再压一档可给重建加「每 tick 时间预算 + 轮转」，代价是遮罩最多落后几个 tick；**anchor 变化时必须整轮重建**，否则会用错位置的遮罩。
   - 同 config + 旋转的多个工地共享同一份 `BakedGhostMesh`，各工地 anchor 交替变化会让节流失效——但**不比改前差**（改前本来就每帧重建）。彻底解法是每个工地独立索引缓冲。
   - 「选中的那栋正好也在施工」会让 `ProjectionRenderer.drawGhost` 与 `ConstructionGhostRenderer.drawGhostSkipped` 在同一份网格上每帧来回约 64 MB 索引（改动前就有）。
   - **有意为之的观感差异**：改造后草/树叶/藤蔓用**目的地群系的真实染色**（改造前是固定默认草绿），只影响带 tint 的方块（样本楼 46/443,333 格）；随机模型从共享随机序列改为按位置确定，观感更稳定。
   - **观感口径（这是定档，不是待优化项）**：虚影几何**必须完整**——拒降级、拒 LOD、拒抽样；观感定档「实心壳」，不做透视。
   - **3x 填充差距的根因**：我们一律用 `RenderType.translucent()`，而投影类模组是原生分层渲染。**alpha 与 160 帧不可兼得**——别再试图两全。
   - **`ByteBufferBuilder` 必须显式 `close()`**：它既没有 `Cleaner` 也没有 finalizer；`MeshData.close()` 只把 writeOffset 归零、**不 free 指针**。漏关就是永久堆外泄漏。
     - 看到 `new ByteBufferBuilder(...)` 先问「**谁关它**」：用完即弃的必须 `try/finally` 配 `close()`，只有长期复用的静态 scratch（如 `INDEX_BBB`）才可以不关。踩过的实例是虚影 VBO 烘焙——`new ByteBufferBuilder(capacity)` 是局部变量、全项目没一处 close，每烘一次永久漏掉整个 capacity（超大建筑单次 400 MB 级），堆外吃光后 `malloc` 返回 0、构造/`resize` 抛 `OutOfMemoryError`，表现为「点提交瞬间内存耗尽崩溃」，很容易误判成「预留太多」而去调 capacity（调多少都没用）。
     - `resize()` 是 `realloc`：预留过小会走 `max(capacity + min(capacity, 2MB), size)` 的细粒度增长，几百 MB 要上百次 realloc，宁可一次估准。
14. **建材成本门控的已知缺口（`element_mappings`）**：
   - **无元素映射 = 免费，且不参与解锁门控**。当前这类方块有一批：`wall_torch`、各类 `*_wall_banner`、`*_wall_sign`、全部 `potted_*`、`water`、`lava`、`bubble_column`、`tripwire`。同一栋建筑里 `torch` 要钱而 `wall_torch` 不要钱、`oak_sign` 要钱而 `oak_wall_sign` 不要钱。
   - `bell` 有映射（metal 2048）但**原版没有配方**，首解锁只能靠村庄——「映射表里有价、游戏里没门路」的典型样本。
   - 需**精准采集**的门控仍在 L5 的 `redstone_shop` 与 L10 的 nodemetal / nodewater 上出现（`ice`、`amethyst_cluster`、深板岩矿石）。
15. **建筑显示名按产物命名，建筑三值固定叫「舒适值/魔法值/奇观值」**：
   - 生产类建筑的显示名一律写成「`<产物>`工坊」并由产物决定定语（物品工坊 / Item Workshop、装备工坊 / Equipment Workshop、魔法工坊 / Magic Workshop）。不写「工作站」「合成站」「制作」这类**可互换**的通用词——建筑名是玩家选建筑时唯一的信息来源，通用词不承载「这里产出什么」；中英名还必须语义对齐（中文叫工坊，英文就得是 Workshop，`魔法工坊 vs Magic Station` 那种错位会让两边玩家看到不同的东西）。
   - **改名只动显示名**：building id / category id 一律不动（旧档建筑按 id 解析）。改名会波及 `lang_src/`、两套手册 md、`gen_patchouli.py` 的 `TITLE_TO_DOC` 表、`insert_guidebook_images.py` 的图注、Java 兜底串、建筑 data 的 `display_name` 与存活文档，改之前先把这份清单过一遍。
   - 三值的中文定名是 **舒适值 / 魔法值 / 奇观值**（scanner 的短标签可省「值」）。**「魔力」只留给法师的 mana**（NPC 资源属性），别拿来叫建筑值——旧悬停提示里的「魔力」与手册里的「满意值」都已作废；「奇观」不要写成「奇迹」。建筑改造动作一律叫「**复原**」（英文 Restore），别写「修复/维修」，内部标识符 `btnRepair`、lang 键 `building_action.repair`、包 `BuildingRepairHandler` 保留原名、只改玩家可见文案；建筑状态徽章只有「已建成」一档表示建成，结构是否被改动只体现在「复原」按钮可不可点。
16. **别拿 `BuildingConfig` 当热路径 map 的键（record `equals` 会遍历整条 pattern）**：
   - `BuildingConfig`（`content/building/data`）是 record，组件含 `List<BlockOffset> pattern`（超大建筑 58 万条），自动生成的 `equals()` 逐组件比较、**包含整条 pattern**，一次就是 O(pattern)。它在本项目里被当过多处缓存的键（预览 GIF / LOD / 包围盒 / 缩放、缩略图 meta、动画格子、虚影 VBO），其中虚影 `getOrBake`、`animatedCells`、`pumpQueue` 是**逐帧**查的——逐帧每栋楼一次 O(pattern)，实测能吃掉 20% 以上渲染线程。
   - `HashMap.get` 本有 `==` 短路，只要**键实例稳定**就走不到 `equals`。坑在实例更替：`BuildingConfigLoader.parseAndRegister` 每次都 `withIdAndPackageId(...)` 造新实例，那条「内容相同就复用旧实例」的兜底（`previous.equals(config)`）依赖 `configs` 里还留着上一版，而 `clear()` 会先把它清空，于是兜底永不触发。
   - **进世界换实例的路径是 datapack 同步**：`DatapackDataSyncReceiver` → `WandscapeDataLoader.applyCategoryFrom` → `SimpleDataRegistry.clear()` → `onClear.run()` → `BuildingConfigLoader.clear()` → 随即按同步来的 JSON 重建全部实例。这条路径**不经过客户端的 reload listener**（`WandscapeClient#onRegisterClientReloadListeners` 里那串 `closeAll` / `clearAnimatedCache` / `clearMetaCache` / `BuildingPreviewGifCache.closeAll`），所以 **datapack reload 有清缓存、进世界同步没有**——进世界后旧实例仍留在各 map 的键里，新实例同 hash（`hashCode` 只哈希 `id`/`packageId`）不同身份，于是每帧每栋楼一次 O(pattern) `equals`。表现是「退出世界重进后缓存失效、240fps 掉到 100fps」。`hashCode` 已改成只哈希 `id`/`packageId`，所以症状会从 `HashMap.hash` 变成 `HashMap.get`。
   - **做法**：新增「以 `BuildingConfig` 为键」的缓存一律用 **`config.id()` 取键 + 值里包一层 `source` 记烘这份表时的实例**——实例换了只做**一次**内容比对，一样就认下新实例（此后走身份短路，继续复用），真变了才释放重烘。`id` 是权威唯一键（`parseAndRegister` 里 `config.id()` 就是全量 id）。虚影那两个逐帧缓存（`BuildingGhostVboCache.CACHE`、`BuildingGhostRenderer.ANIMATED_CACHE`）已按这套改完；**2026-10-06 起这套姿势抽成了 `content/building/preview/ConfigKeyedCache<V>`**（键 `config.id()`、值里记 `source`、只在换实例时比一次内容），同一包里的烘焙期缓存一律走它：`BuildingPreviewGifCache` 的 CACHE（**唯一逐帧逐格命中**的那个，`getFrameLocation` 每帧每格查一次）与 LOD_CACHE/BOUNDS_CACHE/SCALE_CACHE、`BuildingPreviewRenderer.META_CACHE` 都已改完（`META_CACHE` 顺带丢掉了 `WeakHashMap`——config 由 loader 强引用，弱键从来没真回收过什么，反而让「换实例」这条路的比较代价更高），`BuildingVoxels.ROTATED_PATTERN_CACHE` 也同时从 `System.identityHashCode` 键改过来（旧键在换实例后留下永不回收的整条旋转 pattern，大建筑每份几十 MB）。新增这类缓存**照 `ConfigKeyedCache` 来**，别再起第三套写法。也别以为「reload listener 清了缓存」能覆盖进世界同步。

17. **多法师协同建造分批与状态机（`ConstructionBatches` & `BuildingTaskPool`）**：
   - **两阶段执行契约**：单条包含大量方块的大型建筑/修复任务（`build:clear_and_build` / `build:place_structure`）超过 `Config.CONSTRUCTION_BATCH_SIZE`（默认 32 方块）且开启 `Config.CONSTRUCTION_MULTI_WORKER_ENABLED` 时，由 `ConstructionBatches.split` 拆分为批次：
     - **Phase 1 准备批次（Initial / Foundation Batch）**：承载全栋建筑的全部材料扣款（`ResourceRequestOp`）与包围盒清扫（`ClearBoxOp`，若开清盒开关），以及底层地基方块放置。若仓库材料不足，该批次自然进入 `AWAITING_RESOURCES` 挂起等待（进入 `BuildingTaskQueue.parkedTaskIds`），绝不放行后续批次，防止无料白嫖；待材料补齐唤醒后（`unparkBatch`）重回活跃状态。
     - **Phase 2 并发放置批次（Parallel Placement Batches）**：首批准备完工（建材全额记账、场地已平整）后，后续所有放置批次一次性全部释放入 `GlobalTaskPool`，多个空闲法师可同时各领一批并发施工。一批 32 格在 1 格/拍时约 1.6 秒；「一拍放几格」见 §四.6（高阶法师会成倍缩短）。
   - **状态聚合与完工事件**：子批次自身设置 `omit_complete_event = true` 不单独发广播；`BuildingTaskPool.checkBatchesProgress` 跟踪全量批次进度，只有当全部活跃与待发批次均完成时，才由 `BuildingTaskQueue` 统一聚合发射 `build_complete` 事件，触发奇观触发器、建筑完成粒子、工地面板状态更新与下一条待办任务提升。
   - **配置项**：`Config.CONSTRUCTION_MULTI_WORKER_ENABLED`（`building.multiWorkerEnabled`，默认 true，游戏内设置中心「城镇经营」可调）与 `Config.CONSTRUCTION_BATCH_SIZE`（`building.constructionBatchSize`，默认 32 方块，范围 4~1024）。
   - **批次必须像「一个法师站一处干完整栋」（2026-10-06 修，改拆批前必读）**：拆批本身没问题，问题是让每条批次「各自为政」——
     1. **整栋一个站位**：`ConstructionBatches.split` 给每条批次写 `params["task_bbox"]`（整栋 pattern ∪ 清盒范围的六元组包围盒），`TaskExecutionSystem.resolveTaskStance` 优先按它算站位；站位策略只有 `TaskExecutionSystem.standoffStance` 一处（盒西沿外两格、最底层上方一格、Z 中线），`computeTaskStance` 也走它。改前每条批次按自己那一小块现算，前后批的站位能差十几格，法师每批都横穿工地。
     2. **完工即接续（接续交给调度器，判定只有一处）**：法师一完工就 `SchedulerSystem.requestImmediatePass()`，让**下一 tick** 立刻跑一轮派活，而不是等心跳（`SCHEDULER_HEARTBEAT_TICKS = 20` tick ≈ 1 秒）——那 1 秒就是「干一会、手上停一下」。派活规则仍全在 `SchedulerSystem`，执行侧只催不派（早先那版 `TaskExecutor.continuation` 自派活已删除：它自带「同栋还有批次」等前提，前提不成立时照样回落心跳）。
     3. **公平派活序（`SchedulerSystem.fairOrder`）**：可派任务重排成「**还没有工人的任务组各一条**」+「其余按原优先序」。组 = `building_id`，没有建筑归属的任务（采集点/路段/祭坛施法）各自成组。效果：每栋楼/每个任务先分到一个人，有多余的空闲法师才去做第二、第三个工人；否则先入池的大楼（批次最多、id 最老）会一直霸占所有空出来的法师，玩家后放的建筑永远等不到人。建筑委派的候选门槛照旧在候选循环里生效，`fairOrder` 只排序不动语义。
     4. **闲逛/捡物路径在被抢活时清掉**：`WandscapeNpc` 的 `RandomStrollGoal` 与 `AutoPickupItemGoal` 的 `stop()` 现在会清掉自己的路径（`suppressWandering` 或 `engineDrivingNavigation()` 为真时除外——那是 NavigationSystem 正在驱动的工作走位）。改前这两条「闲逛目标 / 去捡掉落物」的路径会在任务到来后继续被走完，正是「跑一段再干几秒」。
     - **症状对照**：只拆批不做上面几条时，玩家实测是「法师干几秒钟就停下来来回跑、跑一段再干几秒钟」（不拆批时是站一处干完整栋）；横向乱跑来自每条批次各自算站位 + 闲逛路径，纵向「手上停一下」来自批次之间回落调度器心跳。改这几条之前别去调 `constructionBatchSize`——那是粒度旋钮，不是这个病。

18. **建造投影的放置模型（2026-10-06 起对齐 Litematica，改交互前必读）**：
   - **三阶段**：`瞄准`（虚影每 tick / 每帧跟随准心，**不按任何键**）→ `调整中`（`isPinned()`：锚点固定、不再跟随，可用 ALT+滚轮 / 6 个按钮改 xyz、左键或面板按钮旋转）→ `已定稿`（`isLocked()`：位移与旋转一律拒绝）。
   - **确认走 Enter 与面板那颗阶段按钮**：两者都是三态循环（瞄准 → 确认位置/调整中 → 定稿 → 重新瞄准），规则收敛在 `ProjectionClientState.advancePlacementStage()` 单点裁决，别在别处再写一套 if。面板同一颗按钮的文案随状态变：确认位置 / 定稿 / 重新瞄准。
   - **ALT+滚轮**沿「相机视线三分量绝对值最大的那个轴」移动 1 格（等价 Litematica 的 `getClosestLookingDirection`：抬头低头改 Y，平视朝哪看改对应 X/Z）；**普通滚轮不消费事件、不做任何事**。瞄准阶段微调会自动进入「调整中」。
     - **有最小灵敏度限制**（`ProjectionFlightController` 的 `SCROLL_STEP_MIN = 0.6` 与 `SCROLL_NUDGE_COOLDOWN_MS = 100`）：高分辨率滚轮/触控板一次物理刻度会连发多个小 delta，逐事件动一格就是「滚一下跳好几格」；现在小 delta 先累加、凑够一格才动，两次微调之间还有 100ms 冷却（冷却期内输入整段丢弃——宁可少动一格，也不连跳）。要调手感只动这两个常量。
   - **三个阶段任意时刻都能回建造栏换建筑**（数字键 1 → 建造页）：`BuildingSelectionOverlay.isActive()` **刻意不看 `isPinned()/isLocked()`**，也不再按右键按住与否隐藏（右键现在只是「打开施工屏」的一次点击，不是长按定位）。换建筑只换配置、锚点不动。历史上那里有 `!isPinned()` 门，表现为「确认位置后按 1 回建造页，栏子开了却既不显示也不吃点击」。
   - **旋转**走 `ProjectionFlightController.rotateFromInput()`（**左键**与面板「旋转」按钮共用的唯一入口；键盘侧别再另占键位——R 试过，撞 JEI/EMI 的配方键）；**已定稿后拒绝**——定稿的含义就是几何已确认，改朝向要先重新瞄准。
   - **右键 = 打开施工屏**（精确坐标 / 提交）；施工屏里改坐标会把状态退回「调整中」（否则瞄准阶段每 tick 把虚影拉回准心、定稿态又拒绝改坐标）。**提交施工**仍在面板按钮 → 施工屏里；提交成功后 pinned/locked 一起清零。
   - 面板「建筑参数」**常驻**，但**面板里不放任何键位提示**——操作说明只写在手册《建造子模式》（`guidebook/{zh_cn,en}/panel_build_guide.md`），面板只留按钮与状态（`PANEL_H = 114`，到「提交施工」下沿 +3px 为止）。别再往面板加提示行：白占一行高度，还会和手册形成第二份说法。文案键 `buildpop.*` 只改值不动键名，改完跑 `gen_lang.py`。
   - **不要再引入「跨准心三轴 gizmo 拖拽」那套**：它与左键旋转、Enter 阶段推进、ALT+滚轮三义重叠，`BuildGizmoController`/`BuildGizmoRenderer` 已删；要精调走 ALT+滚轮、6 个按钮或施工屏坐标。

---

## 六、仓库与物流域 (`content/warehouse`)

1. **仓库终端行为**：
   - 仓库终端支持 Curios 手饰槽（`hands`/`bracelet`）或快捷键开仓，开仓前必须服务端验证佩戴状态或背包持有。
2. **大数量渲染限制**：
   - 原版 Minecraft 浮动物品数量渲染上限为 999（`renderItemDecorations`），仓库中大于 999 的物品显示为 `999+` 属正常原版行为。
3. **仓库容量机制（物品账本总量）**：
   - 容量 = 每殖民地**物品账本**所有条目 count 之和；**1 物品占 1**（不可堆叠也占 1）；**元素独立账本不计入**。上限 = 殖民地「仓库(storage)」建筑数 × `Config.WAREHOUSE_ITEM_CAPACITY`（默认每座 20000，**多建一座仓库 +20000**；无独立仓库按 1 座计；0 = 关闭机制不设限）。`ColonyItemBank` 维护 `usedCounts` 缓存并在 load 重建；上限按 `capacityFor(colonyId)` 每次查询建筑数现算（放下/拆除仓库即时生效，不缓存）。
   - **拦截口径 = 净新增入仓，集中在银行 `tryAdd` 入口**：玩家全部存入路径（`insertItems`/交换页各手势，先入仓成功才清背包槽，整批放不下整体拒收 + ScreenFeedback 提示「仓库容量不足」）与 NPC 生产产物（synthesize/craft/craft_spell 前置 `checkCapacity` 守卫、产物 `tryAdd` 提交）满仓即拒；**净零归还放行**（分解回滚、拆迁/建造取消退款、任务材料退还、运输孤儿返还——只还刚取走的原物，绝不把殖民地资产卡死）。
   - **补货豁免**：商店补货驱动的自动合成（`ResourceSupplySystem.enqueueSynthesize(atFront=true)`）在 WorkItem 参数带 `supply=restock`，满仓仍可合成入仓（容量可被短暂超出），保货架不断供、防殖民地瘫痪。判定处（发布资格、队列徽标、op 守卫）统一看该参数。
   - **满仓生产任务 = 镜像「缺元素」**：合成/制作队列条目满仓不可发布、面板标「仓库容量不足」（`capacityBlocked` 走 TaskQueueDataPacket），执行期撞上满仓以伪资源 `warehouse_capacity` 抛 `ResourceShortageException` → BuildingTaskSource 回收回队列（`isCapacityShortage`），容量空出后按发布资格自动续跑；ResourceSupplySystem 对这类等待不尝试自动补产。
   - **拆迁/回收掉落满仓不丢**：`performSalvage` 产物 `tryAdd` 失败时改为在拆除点生成掉落物（等价箱满溢出），不吞物品也不阻塞平地。
4. **生产队列条目是「批次」不是「请求」（坑）**：
   - `production:decompose/synthesize/craft/craft_spell` 入队时经 `ProductionBatches.split` 按 `BalanceValues.productionBatchMax`（默认 1000，见 `wandscape_balance.json`）拆批，`channel_ticks` 按比例分摊、总时长不变；`BuildingApiImpl.mergeBandTail` 的同配方合并也用同一条上限，防止拆完又被粘回一条。
   - 于是**同一次玩家下单、同一条补料需求会在共享队列里变成多条同配方条目**（圆石 x1000 × 30）。凡是要「按配方聚合」的地方都得按 `count` 求和，不能按条目数或「第一条」：`countSynthesizeInFlight` 已经是求和口径，`ResourceSupplySystem.scanProductionQueues` 必须先汇总需求再比库存（逐条减库存会因同一份库存被减 N 次而严重低估缺口）。
   - 拆批也是发布资格（元素/容量）的判定粒度：拆细后「仓库放得下一部分」不会整条卡住。想看「一条超大任务」的旧样子，把 `productionBatchMax` 调大即可。
5. **仓库 UI 布局不变量（改 UI 前必读）**：
   - 仓库屏是**单页**：原版 6 行箱贴图（`generic_54`）+ 玩家背包，元素列在它左边、搜索框在元素列上方那一条，翻页键/页码/销毁格挤在箱子右侧那条窄留白列里，制作者署名与容量读数排在元素列下方（左下角）。没有总览/交换页签。
   - **面板高度必须贴着原版箱贴图（`PANEL_H = CHEST_TOP_OFFSET + CHEST_H + 4`）**：仓库屏是唯一装在原版 6 行箱容器里的界面，多出来的顶部高度会把箱内的玩家背包行推出矮屏（GUI scale 4 / 1080p 下 GUI 高只有 270，多 28px 就溢出）。搜索框不另起一行，元素列与搜索框共用面板左侧那一栏（`ELEM_TOP` 给搜索框让出上面一条）。**也别在箱贴图上盖横条**：贴图自带的标题条原样保留，横贯面板的深色带压在箱子上很难看。
   - **槽位原点 = 箱子贴图原点**：`WarehouseScreen.leftPos/topPos` 就是箱贴图左上角，`WarehouseMenu` 的 `GRID_X(8)`/`GRID_Y(18)` 与共享的 `VanillaPlayerInventory.INVENTORY_X(8)` 都相对它。面板只向左扩一圈 `LEFT_EXT`（元素列），**面板左上角另有 `panelX/panelY`**——画面板与工具栏一律用它，别拿 `leftPos/topPos` 当面板角。
   - 想挪箱子（改槽位坐标）就要同时改 `WarehouseMenu` 的 GRID_X/GRID_Y 与共享的 `INVENTORY_X`，代价大；改布局优先「箱子不动、只动面板装饰」。
   - 面板比 `imageWidth/imageHeight` 覆盖范围大一圈，所以**必须覆写 `hasClickedOutside` 按整面板判定**：不覆写的话在元素列/搜索行上点击会被当成「点到了 GUI 外」，手里拿着的物品直接丢进世界。
   - **建筑上下文只从仓库建筑本体入口下发**（`BuildingInteractHandler` 的 `storage` 分支带 `state.getBuildingId()`，`WarehouseDataPacket.buildingId` 非空 → 顶栏状态徽标 + 复原/拆除）。其余入口一律传 `null`，它们带的 pos 都不是仓库、挂上复原/拆除会打到别人身上：便携终端（无建筑）、市政厅代开（`TownHallWarehouseRequestPacket`，带的是市政厅）、其它建筑屏的「打开仓库」快捷入口（`OpenWarehousePacket`，法师小屋/合成站/工作台都在用，带的是那座建筑）。
   - 复原/拆除按钮是 `MedievalButton` widget，走 `super.mouseClicked` 的控件通道；确认框是自包含的 `MedievalConfirmDialog`（同 `NpcScreen` 的接法），要在 `render` 末尾补画、在 `mouseClicked`/`keyPressed` 开头拦截。

---

## 七、道路与样条域 (`content/road`)

1. **点与向量类型分工**：
   - `XZPoint`：纯 2D 整型逻辑点（用于起点/朝向判定）。
   - `GridPos`：纯 3D 整型世界网格坐标。
   - `SplineVec3`：双精度平滑样条曲线三维数学向量。
   - 各类拥有各自业务方法与 JSON 契约，禁止随意强转混用。

---

## 八、新手引导与指南书 (`content/tutorial` vs `content/items`)

1. **系统概念彻底分离**：
   - **Tutorial**（`content/tutorial`）：新手引导系统内核，包含引导步骤（`TutorialStep`）、服务端会话（`TutorialSession`）、网络同步与 HUD 引导框渲染。
   - **Guidebook**（`content/items`）：指南书物品与 Markdown 手册文档阅读器。
   - 两个系统各自自治，严禁混用 `Guide*` 泛名。

---

## 九、第三方兼容 (`compat/`)

1. **兼容插件类的静态字段里不要调 `DeferredHolder.get()`**：JEI 的 `@JeiPlugin`、Patchouli 的书等
   由第三方在**注解扫描期**加载，早于物品/方块注册事件——此时取值会抛
   `NullPointerException: Trying to access unbound value: ResourceKey[minecraft:item / wandscape:xxx]`，
   且是在类初始化里炸，堆栈看不出是哪个清单惹的。
   - 做法：静态字段只存持有者本身（`DeferredItem<Item>` 等），到 `registerRecipes` 这类回调里再 `.get()`。
   - `WandscapeJeiPlugin.INFO_ITEMS` 踩过这个坑（开局必崩）。文案清单、图标清单同理，别图省事在字段里就取成 `Item`。
2. **回放中检测（ReplayMod / ReforgedPlay）只反射公开 API，不订阅事件**：
   - ReforgedPlay 是 ReplayMod 的 NeoForge 1.21.1 移植（源码 github.com/ferriarnus/ReForgedPlay），**完整保留 `com.replaymod.*` 包结构与 API**，所以两个模组可以走同一个检测入口。判「正在回放」= 反射 `com.replaymod.replay.ReplayModReplay` 的**公开**静态字段 `instance` 非 null，且其公开方法 `getReplayHandler()` 非 null。**没有**简单的 `ACTIVE` 静态布尔。
   - 这是 ReplayMod 官方公开集成面（只有 public 成员、无 `setAccessible`）；用它的理由不是「反射更酷」而是避开更大的反射面：官方事件 `com.replaymod.replay.events.ReplayOpenedCallback`/`ReplayClosedCallback` 的事件基类 `de.johni0702.minecraft.gui.utils.Event#register` 是**包私有**，外部订阅得反射进私有方法 + 动态代理，反而更脏。
   - 做法：保持**按次反射**而不做 init 一次性缓存（任何时刻都能重新尝试，解析时机 / 版本差异不会把守卫永久禁用）；用 `Class.forName` + 捕获异常判「模组不存在 → 返回 false」，因此不需要 compileOnly 依赖。本模组实现是 `foundation/ui/ReplayScreenGuard.java`（监听 `ScreenEvent.Opening`，取消 `ReplayProtectedScreen` 的打开；旧记录里 `shared/ui/...` 的路径已作废）。PlayerAnimator 一类的录像/回放兼容也按这个 API 做。

---

## 十、殖民地与探索域 (`content/colony`)

1. **野外自然宝箱与探索奖励判定（原生状态自闭环）**：
   - 监听 `RightClickBlock` / `BreakEvent` / `EntityInteract` 触发探索奖励。
   - **防刷核心**：依靠 Minecraft 1.21.1 容器未开封状态下的 `getLootTable() != null`。开箱触发生成原版物品后，原版逻辑立即将其置 null；玩家自放箱子恒为 null。无需在磁盘维护海量坐标数据库。
   - **奖励数据显式化（region JSON 的 `reward` 块）**：顶层只说「这是哪个区域」（`name` + `loot_table_patterns`），`reward` 块说「发什么」。
     `mode` 决定元素价值向量从哪来：`derived`（默认，按战利品表算）、`fixed`（直接用 `value` 里写的，完全不碰战利品表）、`additive`（算出来的 + `value`）；
     `exp_ratio` / `variance` 不分模式始终作用在这个向量上（经验 = 向量总和 ÷ `exp_ratio`；`variance` 只决定上下浮动幅度）。**经验没有区域乘数**——危险系数 `danger_multiplier` 已删除，箱子值多少元素就换多少经验，调平衡只剩 `exp_ratio` 一个杠杆（默认 5.0）。
     本模组**不自带任何声明区域**（`data/wandscape/exploration_regions/` 已整目录删除）：原版结构与其他模组的表一视同仁，都由生成档按 `DEFAULT` 定价，改动平衡只需动 `ExplorationRewardSpec.DEFAULT` 一处。声明层留给数据包——想单独调某个箱子就写一个 region JSON 进去。写了 `value` 却没写 `mode` 时按 `derived` 处理并 `Log.warn`——"写了却不生效"是最贵的静默。
   - **价值是「读权重算期望」，不是抽样**：`ExplorationLootEstimator` 不 roll。它遍历战利品表的 pool / entry，按 `weight / 总权重 × rolls` 求每个 entry 期望命中几次，再把物品过一遍 `element_mappings` 定价求和。
     能这么算是因为原版数值提供器全是均匀分布，而 `LootContext.Builder.withOptionalRandomSource` 允许注入随机源：`ExplorationProbeRandom` 对任何区间都答中点，于是**一次遍历得到的正是数学期望**——`set_count` 给出的也是期望数量而非某一次抽样的值。没有随机、不造中间 `ItemStack`、没有循环，启动价一遍是毫秒级。
     结构不用手写类遍历：原版公开了 `LootPoolEntryContainer#expand`（展开容器树，自带 conditions 与 alternatives/sequence/group 语义；mod 自定义 entry 类型也能被正确展开）与 `LootPoolEntry#getWeight`；真正缺的只有 `LootTable#pools` / `LootPool#entries` 两个无 getter 的字段，走 AT 提 public（`META-INF/accesstransformer.cfg`，第二个用例）。
     **估算值整体 ×2**（`ESTIMATE_SCALE`）：元素映射是按「造它要多少料」定价的，它诚实反映了箱子的**价值**，但低于"发现一个箱子该有多值"的手感——实测发出来只有预期的一半。
     放在这里而不是 `exp_ratio`，是因为经验就是元素总量除以那个比值：动比值只抬经验、元素照旧小，动价值才是一起抬。**只乘估算出来的部分**，开发者手写的 `reward.value` 一律原样使用。
     已知近似：entry 引用另一张战利品表时只有一次探针通过，嵌套表自身的加权选择会塌到单个分支——原版宝箱表极少这样嵌套，命中时是偏差不是归零。
   - **价值算不出来 = 什么都不给**：战利品表缺失、或折算出的元素总值为 0 时（模组物品没有 `element_mappings`，很容易触发），`createFallback` 返回经验 0、元素空。
     以前给 `经验 50 × 危险系数` + `土 10~30`——那只会让"没数据"看起来像"便宜箱子"，何况那点量本来也没有存在意义。现在零就是零：不发奖、不放粒子音效、不上 HUD，只 `Log.info` 记一行（带 `degenerate` 标记）。
   - **没被声明的战利品表，启动时自动生成 region**：`ExplorationRegionGenerator` 挂在 `ServerStartingEvent`（此时战利品表与 region 数据都已加载完），
     枚举 `reloadableRegistries().getKeys(Registries.LOOT_TABLE)`，挑出路径段含 `chest`/`chests` 且没有任何 region 命中的表，按权重算出价值写成**标准 region JSON**（`mode: fixed` + 算出的值），
     落在 `<world>/wandscape/generated_regions/`。放世界下不放 config：战利品表值取决于该世界跑的数据包集合。
     查找是**两层，声明层优先、生成层兜底**（`ExplorationRegionLoader.findMatchingRegion`）——手工写一个 region 就自动接管同名表，自动文件永远盖不住数据包的意图。
     **只给算得出价值的表落文件**：算不出价值的表本来就什么都不给，写一份只是把默认行为抄一遍——整合包里上百张未映射的宝箱表会留下一堆一模一样、毫无用处的文件（这是实测踩过的，第一版真落了四十多份）。
   - **代码侧唯一介入点**：`ExplorationChestRewardEvent`——摇完奖之后、入账与 HUD 之前触发，可改 `exp`/`elements`、可取消，所以上屏的就是最终值。
     数据包能表达的（改数值）一律不进代码，它只负责数据包做不到的：按运行时状态决定、追加物品类产出、整个拦掉。
   - **地域名与地区匹配**：`exploration_regions/*.json` 的正则是**按特异性取胜**的——`ExplorationRegionLoader.findMatchingRegion` 遍历全部命中项取「字面字符最多」的那条（同分按地区 id 排序），
     因为 `SimpleDataRegistry.getAll()` 是 `Map.copyOf(HashMap)`、**迭代顺序不确定**，原来那个「先命中先返回」在整合包加 `.*` 兜底条目时会随机生效。这条排序只对同一个档内生效，跨档永远是声明层先赢。
     正则建议不绑命名空间（`.*/simple_dungeon$` 这类），别的模组 / 数据包用同名路径也能对上号。
     匹配不到（本模组现在等于「没有任何数据包声明」）就由 `ExplorationRegionConfig.deriveDisplayName` 从战利品表 id 现推名字，`somemod:chests/dragon_den` → `Dragon Den`、`minecraft:chests/abandoned_mineshaft` → `Abandoned Mineshaft`。
     名字是**从 id 现推的英文、不进 lang 不本地化**，这是删掉内置声明区域后接受的取舍——换来的是平衡数值单源、没有一份会与 `DEFAULT` 静默分叉的镜像数据。
   - **元素分配一半看战利品表、一半随机撒**：原版宝箱战利品以金属（铁/铜/金）为主，纯按战利品表折算会让所有箱子都给金属。
     `ExplorationRewardRange.rollElements` 只让**总额的一部分**沿用战利品表比例（比例来自 `reward.loot_share`，默认 0.5，0 = 全随机、1 = 纯战利品），另一半按随机权重（0.5~1.5 抖动、最大余数法配平）平摊到七元素；
     总额与经验折算不受影响（经验是按元素总值算的，没变）。
   - **双轨入库**：经验直加小镇等级，元素直入小镇 `ColonyItemBank` 金库；无小镇玩家由 Action Bar 提示并保留原版物品。
2. **开容器 GUI 那一刻的提示只能走 `ExplorationHudOverlay`（动作栏与 `ScreenFeedbackPacket` 都被盖住）**：
   - 玩家打开容器界面（宝箱 / 仓库等）时两条常见反馈通道都不可见：`player.displayClientMessage(component, true)` 画的是动作栏、在 Screen 之下；`ScreenFeedbackPacket` 只在 `MedievalScreen` 上弹 toast、否则退回动作栏——同样在 Screen 之下。
   - 唯一能盖在容器界面上的通道是 `content/colony/exploration/client/ExplorationHudOverlay`：注册在 `ScreenEvent.Render.Post`（另加 `RenderGuiEvent.Post` 覆盖无 GUI 场景），z 层抬到 800 且绘制前 `flush()`。
   - **做法**：任何「开箱子 / 开容器那一刻」要给玩家看的反馈都发 `ExplorationRewardPacket`（`sendNotice(player, component)` 走提示卡，`send` 走经验 + 元素卡），不要用动作栏或 `ScreenFeedbackPacket`。注意卡片只有**单槽位**，后来的通知会顶掉前一条。

---

## 十一、运行时输出目录（按作用域分桶）

会往磁盘写东西的地方，按**作用域**归三个桶，别再造第四处：

| 桶 | 位置 | 放什么 |
|---|---|---|
| 世界 | `<world>/wandscape/` | 只对这个世界成立、又不算正式数据的：自动生成的探索 region（`generated_regions/`，值取决于该世界的数据包集合） |
| 世界 | `<world>/datapacks/<pack>/data/wandscape/` | 导出成正式数据包的东西（建筑扫描器 `buildings/`、道路预设 `road_presets/`）——玩家 `/datapack enable` 后直接当数据用 |
| 世界 | `<world>/data/` | `SavedData`（`wandscape_colony.dat`），原版标准位置 |
| 全局 | `<gameDir>/config/wandscape/` | 与机器/客户端绑定、跨存档共享的：`splines/`（道路样条）、`previews/`（建筑预览帧缓存）、`scanner_presets/`（扫描器预设） |
| 诊断 | `<gameDir>/logs/` | 性能剖析 CSV 一类，跟着日志走 |

- 判断标准只有一条：**这份数据换一个世界还成立吗？** 成立 → `config/wandscape/`；不成立 → 世界目录下。
- 历史包袱：`previews/` 与 `scanner_presets/` 原来写在 `<gameDir>/wandscape/`，既不在 config 也不在世界下，已统一进 `config/wandscape/`——**没做老数据迁移**，升级后旧的扫描器预设要手动拷过去。
- 开发期产物不算运行时输出、不受此表约束：写源码树的生成命令（`/wandscape` 的 element mapping 生成）、仓库根的 `gen_*.py`。
- `logs/` 是明知的第三桶：诊断数据跟着日志走是生态惯例，但它不属于上面那条二分法。
