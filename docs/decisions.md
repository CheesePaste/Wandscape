# 设计决策记录

本文件记录偏离直觉的设计选择及其原因，供后续开发快速理解「为什么这么做」。

## 2026-08-10：修复游客交互时长/满意度 2 倍 + 下调 1 级需求

**需求**（用户实测）：花店 `interaction_duration_ticks=2400` 实测 ~4800 tick；满意度三值比 JSON 大一倍（10/3/2 → +20/+6/+4）。要求 1 级游客三条 need = 40% 均衡 50/50/50、60% 侧重 80/35/35 类。

**决策**：
- **交互时长 2 倍根因 = vanilla goal 每 2 tick 才跑一次**：`Mob.serverAiStep()`（final）按 `(tickCount+id)%2` 交替 `goalSelector.tick()` 与 `tickRunningGoals(false)`，默认 `Goal.requiresUpdateEveryTick()`=false → `TouristMoveGoal.tick()` 半速，倒计时（以及排队容忍/卡死检测等所有 goal 内计时器）都按 2× 真实 tick 跑。修复：`TouristMoveGoal.requiresUpdateEveryTick()` 覆盖返回 `true`。副作用是把其余 goal 内计时器一并修正为真实 tick 速率（原写死的阈值本来就按真实 tick 意图）。
- **满意度 2 倍根因 = `TOURIST_BAR_GAIN_COEFF` 默认 2.0**：`fillBar = round(值×coeff)` 把 JSON 值翻倍。修复：默认改 1.0（增益 = JSON 值）。保留配置旋钮便于调参。
- **1 级需求下调**：`TOURIST_NEED_BASE` 默认 300 → 150；侧重画像权重 `{1.4,0.8,0.8}` → `{1.6,0.7,0.7}`（配合 needBase=150 → 1 级均衡 50/50/50、侧重 80/35/35）。与 coeff→1.0 组合后「每需求条填满所需访问次数」与旧值大致持平（旧 100 需求/20 增益=5 次 → 新 50/10=5 次），只是显示数字更直觉、更贴近 JSON。
- **旧存档游客不迁移**：已生成的游客 keep 旧 need（100/140）直到离场；新生成游客用新值。三值 `set*Need` 有 `>=1` clamp，混合值安全。
- **sim 路径不参与本次修复**：未观察游客（`TouristSimSystem`）到点即结算、无视 `interaction_duration_ticks`，是既有简化（无可见站立），保持原样。

**为什么**：JSON 是数据唯一真源（`interaction_duration_ticks` = 真实游戏 tick、建筑三值 = 实际增益），运行时 2 倍是 vanilla goal tick 频率与系数默认值的双重偏差，应当修到「JSON 写多少就是多少」，而非给 JSON 打补丁。

## 2026-08：游客经济大改造（满意度→三条需求条 / interact_spots / 四类 category）

**需求**：把游客从「碰建筑进 CD 干晃悠」变成「真在城镇生活」：三条需求条无惩罚填条、画像驱动多样城镇、spot 占位做动作+排队、精力循环+relax、ATM 取现、停留上限防挂机。完整目标见 `architecture/plan/goal.md`。

**决策**：
- **满意度 → 三条需求条（Comfort/Magic/Wonder）**：删除单一 `satisfaction` 与 `typePreferences`（字段/NBT/接口/调用/配置全清）。填充无惩罚：`sat += round(值 × TOURIST_BAR_GAIN_COEFF)` 封顶 need；满条 = 三条 ratio 全 1，**满条夜晚离场才给经验**（防刷）。离场载荷 `registerDeparture(UUID, UUID, BarRatio)`，stats/HUD 走三条。
- **画像 + 等级缩放**：40% 均衡 / 20% 舒适 / 20% 魔法 / 20% 奇观；`totalNeed = BASE + (level-1)×PER_LEVEL` → 等级越高总需求越高、越难满足（自然难度曲线，不惩罚普通建筑）。
- **`interact_spots` 取代 `tourist_interact_aabb`**：每点带动作（`Activity` 子集 browse/eat/bathe/view/pay/rest/withdraw），**spot 数量 = 同时交互人数上限**（全满排队，超 `TOURIST_QUEUE_WAIT_TOLERANCE_TICKS` 放弃）；交互时长由模式预设块 `interaction_duration_ticks` 决定（与 spot 无关）；0-spot 游客目标建筑不选（**无 spiral-scan 兜底**）。旧字段不保留 JSON 兼容解析。
- **交互位唯一真源 = world 里 `interact_spot_marker` 方块**：BE 不存 spot 列表；放置=标记、右键循环动作、潜行右键移除，action 存 blockstate（无 BE/NBT）。导出扫 boundary 内 marker → `interact_spots`，marker 格跳过 pattern（创作者自行留空该格）。
- **四类 category 保持独立（不合并）**：`shop`（卖物品）/`service`（产元素+耗精力，`max_occupancy>0`=旅店）/`relax`（回精力）/`atm`（取现 `min(withdrawAmount, travelFund)`）；统一成 `interact` 的 `interaction` 块 → **二阶段**（`architecture/plan/phase-2/`）。动作只决定游客活动状态/粒子，精力/经济效果由模式预设块决定。
- **目标选择 = Find-Best-Action，只看视野内**（`TOURIST_VISION_RADIUS` 且已加载）：`Σ(需求缺口×建筑值) + 精力紧急(relax) + 钱包紧急(atm) − 排队惩罚`；视野内无目标 → 闲逛；精力 0 → 只能去 relax、无则闲逛（**不离场**）。
- **停留上限 + `visitedBuildings` 不重置**：停留 2-4 天（`departureDeadline`），整个停留一栋建筑只逛一次，防挂机。
- **`Activity` 枚举放 `shared/data`**（building/data 要引用，避免跨模块直接引用）；`TouristState` 保持移动标签不扩展为状态机。
- **瞬时头顶条移除**：删 `SatisfactionBarRenderer`；气泡仍在（图标+文案），不画 before→after 进度条。

**为什么**：三条无惩罚 + 画像自组织 = 「多样城镇」由游客行为引导而非规则逼迫；spots/排队 = 多建同类型有实际收益（多交互位=排队短）；视野限制 = 省寻路开销且行为真实；满条才给经验 = 经验是里程碑不是流水。

**与方案文档的偏差**：
- `VisitMemory` 用**三维增量**（comfortDelta/magicDelta/wonderDelta）而非方案文档的单个 `barDelta` 聚合：面板行程逐维显示（`舒适+X 魔法+Y 奇观+Z`），`Emotion.fromDelta(三维之和)` 语义与 C4 的「三条 ratio 增量之和」一致。信息更丰富、贴近 goal 的三条表示，保留此实现。

## 2026-08：交互位朝向 facing + 预览假人 + 活动同步修复

**需求**（用户实测反馈）：交互位没有朝向，用餐等动作可能朝向不对；且希望能在交互位看到动作效果的循环预览。

**决策**：
- **`interact_spots` 增加 `facing`（水平朝向）**：游客在该位做动作时面朝的方向。缺省 `south`，Y 轴/非法值回退 `south`；建筑旋转时随 `BuildingRotation.rotateDirection` 一起旋转（用户要求「旋转后方向也正确旋转」）。`TouristMoveGoal` 活动期间持续 `setYRot/yBodyRot/yHeadRot` 面向 spot（含 look control 拉偏兜底）。
- **marker 交互改为「右键循环动作、潜行右键循环朝向、敲掉=移除」**（用户拍板，放弃原来的潜行右键移除）。放置时 facing 取玩家面朝方向作为起点。marker 改为**无碰撞 + 贴地薄板模型**（`getCollisionShape` 返回空），让预览假人可站在同一格、且不被整格方块挡住。
- **预览假人（始终常态）**：`MarkerPreviewManager`（服务器端单例）为每个 marker 维护一个 preview 模式 `TouristEntity`——站桩循环播放该 spot 动作（复用现有游客渲染：姿态/粒子/朝向/动作名 name tag）。生命周期靠 `BlockEvent.EntityPlaceEvent`（放置生成）+ marker `useWithoutItem` 后回调（改动作/朝向即时更新）+ `BlockEvent.BreakEvent`（敲掉移除）+ `ChunkEvent.Load`（palette `maybeHas` 高效发现，chunk 卸载即消失、重载重建）+ 周期 reconcile 兜底。preview 不参与生成/离开/孤儿清除、不持久化、免疫伤害、不可交互。**为何不用客户端渲染**：服务器实体复用全部现有渲染（姿态/粒子/气泡开关），且多方可见；客户端 ghost 需自建模型渲染管线。
- **活动同步修复**：原 `currentActivity` 是普通字段，**不同步到客户端** → 游客姿态/粒子其实渲染不出来（红线 #10「看到游客真的在泡澡/排队」隐患）。改为 `DATA_ACTIVITY` synched data（ordinal，-1=无），客户端渲染直接读实体同步值，预览假人与真实游客一并受益。

## 2026-08：扫描器装饰实体用「修剪 NBT + 独立朝向字段」而非结构化 JSON

**需求**：物品展示框/画是实体，扫描器（只遍历方块格子）扫不到，NPC 建造也只会放方块。端到端补上：扫描捕获 → 导出 JSON → 建造重建（含旋转）。

**决策**：
- JSON 用 `entities` **数组**：`{offset, type, facing, nbt}`。数组而非按 offset 作 key 的 map——同一格空气正反两面可挂两个展示框。`nbt` 是**修剪后实体 NBT**（base64，与 `block_nbt` 同风格）：去掉 `UUID/Pos/Motion`，位置重定基为相对偏移（文件与绝对坐标解耦），`id` 显式写入。
- `facing` 独立成 Direction 字符串字段，**不塞进 base64**——建筑旋转时只转结构化字段（offset + facing），NBT 保持不透明，免去解码/重编码。
- 重建走 `EntityType.create(tag, level)` 通用往返，按类型写朝向字节（item_frame 用 `Facing`+3D 值，painting 用 `facing`+2D 值——两个原版字段名大小写不同，已核源码）。生成前先清除同格悬挂实体，避免新旧展示框共存互相 `survives()` 踢掉。
- 新原子操作 `SpawnDecorationOp` + DSL 步骤 `spawn_entity` + `EntityOps.spawnDecoration` 边界方法；执行器走 sync——建造时序已保证实体在方块后生成（异步 TransformOp 逐个完成推进 stepIndex 后才执行 `for_each $entities`）。

**为什么**：结构化 JSON 需要按类型解析实体 NBT（枚举性）；base64 NBT 往返是通用机制，与既有 `block_nbt` 一致，任何悬挂实体加白名单即可支持。朝向是唯一需要旋转的字段，独立出来把旋转成本压到最低。

**v1 边界**：白名单 = item_frame/glow_item_frame/painting（都是 BlockAttachedEntity）。盔甲架/display 实体位置重定基已通用，但朝向内嵌 NBT 无法随建筑旋转，留待后续。实体装饰不参与材料成本（`computeMaterialData` 只算方块）。修复路径（`BuildingBreakHandler`）不带实体。

## 2026-08：敌对测试法师（EvilMage）复用 NPC 施法管线

**需求**：实战测试法术系统强度——一个与殖民地法师外观/属性/施法完全一致的敌对生物，索敌生存玩家，创造模式右键可编辑施法表/策略。

**决策**：`EvilMage extends WandscapeNpc implements Enemy`，而非独立实现。

**为什么**：施法管线（`MagicCaster.castNpcAt`、`MagicCastManager`、`MagicBeamEntity`、`GuardCombat`、`CastBrain`、`MagicState` 门控、SPELL_POWER 倍率）全部绑定具体类型 `WandscapeNpc`。子类化即零成本复用全部施法/NBT/渲染/编辑能力；独立类需要把整个管线重构为接口，风险大且偏离「测试工具」定位。

**与殖民地解耦的三条钩子**（行为保持的扩展点）：
- `isColonyNpc()`（默认 true）：false 时不注册进 ECS / 不入任务调度，`NpcDeathHandler` 跳过死亡记录（不可复活），`onAddedToLevel` 仍做外观/魔法表/法杖初始化。
- `canBeamHurt(LivingEntity)`（默认 `instanceof Enemy`）：决定光束能伤害哪些目标。**普通 NPC 的光束永不伤玩家**；`EvilMage` 覆盖为「Enemy 或 生存玩家」。光束伤害（`MagicBeamEntity`）、SPELL_POWER 倍率（`NpcSpellPowerHandler`）、战斗快照敌数（`GuardCombat.countEnemies`）三处统一走此钩子，边界唯一。
- `tickCastingState()`（protected，默认 ECS 驱动）：`EvilMage` 覆盖为空，施法姿态改由 `EvilMageCastGoal` 驱动（不加入 ECS 故无 TaskExecutor）。

**其它**：
- `HostileTargetingHandler` 的村民级索敌谓词排除 `Enemy`——僵尸/灾厄不会追杀同为敌对的邪恶法师。
- `SpellcastingApiImpl.resolve` 桥查失败回退按 UUID 扫世界（界面编辑/显示的低频路径），使非 ECS 实体也能读写施法策略。
- 装备/护甲属性依赖 ECS `EquipmentComponent`，邪恶法师不进 ECS → 法杖换色生效（`getMainHandItem` 取色），属性加成不生效，恒为默认属性。

## 2026-08：光束连发无停顿 → 每魔法 CD 改为锁结束后起算

**需求**：实测邪恶法师几乎 0 间隔连发光束——`base_cooldown: 40` 明明设了却没停顿。

**根因**：`MagicState` 的每魔法 CD 与施法互斥锁**同时从施法开始倒计时**。光束锁 = 20(法阵延迟)+200(法阵时长)+20(拖尾) = 240 tick，锁 240 > CD 40，`canCast` 要求锁和 CD 都为 0 → 下一发由锁决定，恰在上一发光束消失时（240 tick）就绪，光束无缝衔接、CD 从未产生停顿。

**决策**：
- `MagicState.tickRegen`：锁占用期间每魔法 CD **冻结**，锁释放后才倒计时——CD 表示「施法结束后的恢复间隔」，施法时间不计入。总间隔 = 锁时长 + CD。
- beam CD 40 → **400**：光束（240 tick）结束后再停 400 tick，总间隔 640 tick。`beam.json base_cooldown` 与 `MagicCaster.BEAM_BASE_CD` 同步。

**为什么**：设计意图（「施法时间不参与 CD」）本应让 CD 在施法后追加一段间隔，但并发倒计时把它吸收成 0。冻结语义对传送（CD 300）同样成立且更合理——CD 不再被引导锁盖掉。

**影响**：所有走 `tryCastSpell` 的魔法 CD 语义统一改为「锁释放后起算」；`MagicStateTest.castingLockBlocksAllMagics` 随之更新（锁期间 CD 冻结断言）。

## 2026-08：光束伤害类型与命中节流

**需求**：实战测试发现原版 `magic`/`indirect_magic` 伤害类型都在 `damage_type/bypasses_armor` tag 里——护甲不减伤、耐久不掉，邪恶法师对穿甲玩家是真伤，太强。

**决策**：
- 新增自定义伤害类型 `data/wandscape/damage_type/beam.json`（`message_id: "magic"` → 死亡消息复用「被魔法杀死」，不在 bypasses_armor → 护甲减伤 + 耐久递减）。
- 光束保持**每 tick 结算**（`invulnerableTime = 0` 重置），帧伤节奏经实测确认保留（屏幕受击抖动为已知代价）；曾试过靠原版 20 tick 无敌帧节流到 1 次/秒（单次峰值 10=0.5×20，DPS 不变），实测后回退。

## 2026-08：指南书 md 链接格式从 guide:doc_id 改为原生 doc_id.md

**需求**：游戏内指南书 md 文档原先用自定义 `[文本](guide:doc_id)` 链接格式，GitHub 预览/IDE 无法识别、不能点击跳转，开发不便。

**决策**：
- 链接格式改为原生 markdown 相对链接 `[文本](doc_id.md)`，GitHub/IDE 可直接点击跳转到同目录 md 文件（zh_cn/、en/ 各 24 篇，共 48 文件、239 处链接）。
- 链接分发 `GuideTestScreen.handleLinkAction` 重构为四分支：`action:` 游戏动作（保留 stub）/ 外部 URL（http/https/mailto/ftp/file，优雅忽略）/ 纯锚点 `#xxx`（优雅忽略，当前不支持页内跳转）/ 文档引用（`.md` 后缀或裸 doc_id，交 DocumentLoader）。
- **保留 `guide:` 前缀向后兼容**：DocumentLoader 与 handleLinkAction 仍剥离 `guide:` 前缀，旧 md / 历史示例 / 第三方片段不破坏。
- 解析层（MarkdownParser）与资源定位（DocumentLoader）**零改动**——后者早已支持 `.md` 后缀补全与 locale 目录回退；解析器本就把括号内 target 原样存入 `FormattedSpan.linkAction`，不区分前缀。

**为什么**：开发态可点击性是日常高频痛点；运行时分发改动集中在一个方法 + 一条兼容分支，风险最低；保留兼容避免破坏存量内容。`action:` 链接（如「开启鸟瞰模式」）是游戏动作而非文档跳转，原生 markdown 无对应概念，保留 `action:` 前缀不动。

## 2026-08：子模式拆分 suspend/exit + 光标每 tick 双向 reconcile

**需求**（玩家实测反馈）：切 tab / 按 G / ESC / 关面板 / 按 C 时，已选的建筑、朝向、pin 位置、道路起终点、搜索筛选瞬间清空，要完全重来；另一侧，OS 鼠标指针在 UI 心态下偶尔突然消失。

**根因**：所有选取态是客户端 static volatile，清空链「宁可错杀」——`enterBar`/`enterPlacing`/`openBuildingBar`/`closeBuildingBar` 每次都清位置/工具/筛选；子模式退出一律走全清 `exitProjection`。光标 enforcer 只单向（Screen 关闭后把鼠标重新 release 给 UI），反向（该 grab 时没 grab）不兜底。

**决策**：
- **拆分 suspend（保留选取）与 exit（仅登出全清）**：`ProjectionClientState` / `RoadPlacementState` 各新增 `suspendProjection()`——只落 projecting 标志、保留全部选取；`exitProjection()` 保持原样，仅在 `WandscapePanelState.reset()`（登出/断线）调用。`WandscapePanelState.exitCurrentSubMode()` 的 BUILD/ROAD 分支改调 suspend（仍发 ProjectionExitPacket 通知服务端）。
- **相位翻转纯化**：`RoadPlacementState.enterBar`/`enterPlacing` 删除 clearAll/ghostPos/工具/参考块重置，只翻 roadPhase；`clearAll()` 不变，仍供提交（`RoadPlacementController.handleEnter` 发包后）/显式撤销使用。`ProjectionClientState.enterProjection` 重装服务端 slots 时只把 selectedSlotIndex 钳到合法区间（抽出 package-private `clampSlotIndex` 供单测），保留 rotation/pin，丢弃未 pin 的准星跟随位置。
- **建筑条停止清空**：`openBuildingBar`/`closeBuildingBar` 删除分类/搜索/滚动/ghost/pin 重置，只 defocus + 重同步 selectedIndex；`reset()` 仍全清 bar 字段。提交后清虚影移到 `ConstructionScreen.submit`（`setGhostPos(null)`，已放置建筑不再需要预览）。
- **光标双向自愈**：`WandscapePanelController.onClientTickPost` 每 tick（无 Screen 时）按 `cursorLifted` 双向 reconcile——该 release 则 release、该 grab 则 grab，消除转换后「光标卡死显/隐」两侧故障。同 tab 点击改为 no-op（不再退出子模式，避免误点丢工作；用 ESC 退出）。

**为什么**：玩家痛点本质是「临时离开」与「真正结束」被当成同一回事。suspend/exit 二分让切模式/切相位成为无损操作，全清只在登出或显式提交/撤销发生——符合「会话内连续作业」心智模型。光标每 tick reconcile 是状态机自愈，比「在某个转换点打补丁」更鲁棒，避免遗漏新的转换路径。

**约束保留**：ConstructionScreen 的 Close 按钮语义不变（保留 pin + 回准星复查）；`exitProjection` / `clearAll` / `reset()` 三个全清入口行为不变，只是调用方收紧。

## 2026-08：空中视角——相机位置缓存 + 玩家旋转冻结 + 第三人称渲染 + 受伤退出

**需求**（玩家实测反馈）：空中视角的鼠标移动会污染玩家视角（退出后常指向天空）；默认「正上方/视角正下」空间感知负担大；误触关闭后再进丢失飞到的相机位置；空中视角看不到玩家自己；空中视角下受伤无法立即夺回操控。

**根因**：空中视角期间光标 grabbed，原版 `MouseHandler.turnPlayer` 每帧 `player.turn(...)` 改玩家真实旋转，退出后 mixin 不再覆写摄像机 → 玩家视角停在漂移位置（`prevYaw/prevPitch` 存了却从没用）。每次 `enterOverview` 都重算位置、`exitOverview` 全清 cam 字段，无跨会话缓存。第一人称不渲染 LocalPlayer。无受伤退出。

**决策**：
- **相机位置缓存与玩家旋转快照分离**：camX/Y/Z/yaw/pitch + `aerialCacheValid` 跨 enter/exit 保留，但玩家水平离开缓存锚点（建立缓存时的玩家位置）超过 8 格则失效重算（`exitOverview` 改 suspend 语义只落 active + 清瞬态目标）；`hardReset()`（`WandscapePanelState.reset()` 登出调用）无条件清。这让「误触关闭原地重开」复用相机、「走远后重开」重算合适位置；`prevYaw/prevPitch` 每次 `enterOverview` 从 `mc.player` 重新采样（冻结基准不跨会话，否则地面转头后再进被冻回旧朝向）。
- **默认视角改角色后上方 45°**：`enterOverview` 无缓存时 camPitch=45、位置=脚位−水平前向×14、Y+14、camYaw=玩家朝向（取代旧的 py+20/pitch=90 正下方）。
- **玩家旋转每帧冻结（reconcile）**：`OverviewFlightController.onRenderLevelStage`（AFTER_SKY，早于实体渲染）末尾每帧把玩家 yRot/xRot/yRotO/xRotO + yBodyRot/yBodyRotO + yHeadRot/yHeadRotO 冻结回快照，抵消 `MouseHandler` 污染；`exit()` 显式落定防退出瞬间甩头。两个「玩家视角」（原版 + 地面模式）共享这一份旋转。
- **第三人称渲染玩家**：`enter` 切 `CameraType.THIRD_PERSON_BACK`、`exit` 恢复；`onRenderLevelStage` 每帧 reconcile 相机类型防 F5（F5 在 `handleKeybinds` 早于 ClientTickPost 消费，drain 无效）。
- **受伤自动完全退出**：`enter` 采样血量基线；`onClientTickPost` 检测 `getHealth()` 下降沿或死亡 → `WandscapePanelState.closePanel()`（保留相机缓存，回原版第一人称）。
- **进入音效移到控制器**：`OverviewClientState.enterOverview` 原引用 `WandscapeSounds` 触发 `DeferredRegister` 静态初始化，在无 MC Bootstrap 的单元测试抛 `ExceptionInInitializerError`；按纯状态 holder 范式把 `playUI` 移到 `OverviewFlightController.enter()`，`enterOverview` 仅剩纯逻辑可单测。

**为什么**：相机位置是用户飞行设定的持久值（应跨关闭保留），玩家旋转快照只在单次空中会话作冻结基准（不应跨会话）——两者生命周期不同必须分离。每帧冻结/相机类型 reconcile 是状态机自愈（同光标自愈范式），比在每个 enter/exit 转换点打补丁更鲁棒。必须冻 yBodyRot/yHeadRot：`LivingEntityRenderer` 用 yBodyRot 画身体、`yBodyRot` 在 `tickHeadTurn` 以 30%/tick 跟随 yRot，只冻 yRot 第三人称模型仍会随鼠标抽搐。

**约束保留**：`MixinOverviewCamera` 不动（TAIL 只覆写 position/rotation，不影响 `Camera.detached`，第三人称下 local player 由 `LevelRenderer` 正常渲染）；`closePanel()` / `exitCurrentSubMode()` 路径不改（都走 `exit()` → `exitOverview()` suspend，缓存自然保留）。
