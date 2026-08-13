# 设计决策记录

本文件记录偏离直觉的设计选择及其原因，供后续开发快速理解「为什么这么做」。

## 2026-08-12：ImGui 字体烘焙与 GLFW/GL3 Native 钩子解耦——实现零卡顿零崩溃预热

**需求**（用户指令/问题诊断）：首次按下按键 2 调出 ImGui 道路编辑器时有一次约 0.2~0.3 秒的字体烘焙卡顿，且直接对整个 ImGui 预热会导致 GLFW/OpenGL 在启动阶段发生 C++ 崩溃（`-1073741819 / 0xC0000005`）。

**决策**：
- **`ImGuiManager` 架构解耦**：将 `init` 拆分为 `initFontsOnly()`（纯 CPU 内存中解压 TTF 并使用 FreeType 烘焙 20,000+ CJK 汉字 Font Atlas）与 `ensureBackendInit()`（GLFW Native 回调 Hook 与 OpenGL GL3 Shaders 绑定）。
- **预热策略**：当玩家进入游戏世界后（`mc.level != null`），在渲染帧静默触发 `initFontsOnly()`。由于 `initFontsOnly()` 不调用 `imGuiGlfw.init`，零触碰 GLFW 窗口回调与 OpenGL，崩溃率 100% 为零。
- **按键秒开**：当玩家在游戏世界里按下 2 时，`ensureBackendInit()` 仅耗费 0.1ms 完成 GLFW/GL3 绑定；由于耗时 0.3s 的字体 Atlas 已经在显存/内存中生成完毕，UI 界面实现**零掉帧、零卡顿秒开**。

## 2026-08-12：道路编辑器侧边栏默认宽度扩大与完全弹性 (Flex) 布局重构

**需求**（用户指令）：道路编辑器侧边栏默认扩大一点，并改成完全弹性 flex 布局，拖动改变宽度时内部控件自适应弹性伸缩。

**决策**：
- **默认宽度调大**：将 `SplineEditorImGui` 初始默认面板宽度由 `370px` 提升至 `440px`，最小限制宽度由 `300px` 提升至 `340px`。
- **全组件弹性 (Flex) 布局化**：
  - **坐标输入框 (X/Y/Z)**：起点/终点坐标、整体平移偏移量、节点 3D 精确坐标三组输入框由固定硬编码宽度 (`65px`/`75px`) 改为基于 `ImGui.getContentRegionAvailX()` 3 等分计算，并与清除/平移按钮右侧自动对齐。
  - **滑动条 (Sliders)**：动态道路规格（宽度/深度）、采样步距改用 `pushItemWidth(-1)` 撑满容器；3D 姿态旋转（Roll/Pitch/Yaw）滑动条与重置按钮按比例弹性分割。
- **为什么**：写死固定像素宽度会导致面板拖宽后右侧存在大片死区空白、面板拖窄后文本被截断挤压。全组件采用 Flex 弹性比例计算，能完美适应 340px ~ 800px 的任何侧边栏宽度。

## 2026-08-12：防守卫死循环——封闭空腔怪物 10 秒无视线超时放弃 + 30 秒黑名单 + Y 轴同层优先

**需求**（用户指令）：防范建筑内部封闭空腔刷怪导致法师一直打不到、卡在房顶死循环的问题。要求：1) 索敌/目标选择过滤不可达怪；2) 10 秒无视线超时放弃守卫任务。

**决策**：
- **`GuardScanner` 引入不可达黑名单 (`UNREACHABLE_BLACKLIST`)**：`GuardScanner.blacklistMob(entityId, gameTime, 600)` 将目标记录进 30 秒（600 ticks）黑名单。`nearestInZones` 与 `hasMonsterInZones` 在索敌和脱离判定时均忽略黑名单中的怪物，从源头防止任务池在放弃后立即重复发布该怪物的任务。
- **`GuardAttackExecutor` 增加 10 秒无视线超时放弃与 `isActuallyMoving` 精准移动判定**：`Pending` 追踪 `noLosTicks`；若 `hasLineOfSight(npc, nearest)` 为 false，每轮重检 (`RECHECK_TICKS`=10) 累加 `noLosTicks`。为防止传送/卡在房顶/已在目的地小范围打转时误判为“在赶路”而清零计时，增加 `isActuallyMoving` 精准判定（必须处于 `PATHFINDING` 模式、离目的地水平距离 >5 格、导航未完成且非传送引导中才算赶路中）。若静止/卡住/在落点附近且持续无视线达到 `UNREACHABLE_TIMEOUT_TICKS`=200（10 秒）时，自动将目标怪物登记入 30 秒黑名单、取消导航、结束战斗态，并完成 (`complete future`) / 放弃当前 `guard:attack` 任务。一旦恢复视线或真正大跨度赶路中，`noLosTicks` 归零。
- **`GuardCombat.findStandingYNear` 修正 Y 轴搜索优先级**：将楼层寻找顺序由自上而下（`+2` 到 `-4`）修改为**优先同层、再上下交替**（`0, +1, -1, +2, -2, -3, -4`）。当怪物在室内/地表时，法师优先选择同层地面，避免误把空腔正上方的屋顶当作首选落脚点。

**为什么**：原守卫系统使用建筑 AABB 索敌，不进行视线与可达性前置检查，且 `findStandingYNear` 优先检查 `+2` 格高度，导致空腔怪刷新时法师极易定位并卡到屋顶；同时 `GuardAttackExecutor` 缺乏无视线超时机制，导致法师在屋顶无法攻击怪却永不脱离。无视线 10 秒超时 + 30 秒黑名单能够在法师无法触及怪物时迅速解脱，恢复自由执行其它任务，且 30 秒内不被该怪骚扰；同层优先的 Y 轴搜索防止法师无脑爬楼顶。

## 2026-08-12：分解折价回调 1/5 并配置化 + 新增合成消耗倍率

**需求**（用户指令）：Workstation 分解产出默认映射值的 1/10「用力过猛」，改回 1/5；并把分解除数与合成消耗倍率做成 Config，服务器管理员可调。

**决策**：
- 分解除数移出硬编码：删除 `WandscapeConstants.DECOMPOSE_DIVISOR`（10），改为 Config `element.decomposeDivisor`，**默认 5.0**（1/5，回调此前的「加深折价」决策）。拒绝阈值随之变为 count×总价值 < divisor；产出 = `(元素值 × count) / divisor` 向下取整。
- 新增 Config `element.craftCostMultiplier`，**默认 1.0**：Workstation 合成（synthesize）、法杖制作（craft_wand）、酿造（brew_potion）的元素消耗 × 该系数，消耗向上取整（ceil，保证不因倍率少扣）。设为 2.0 则合成消耗翻倍。
- 客户端 Workstation 面板的「每格分解产出」显示同步读 `element.decomposeDivisor`（COMMON config，两端同值），不再读常量。

**为什么**：1/10 回收率对早期殖民地元素应急补充偏苛（原 2026-08 决策旨在弱化白嫖分解，但连带把正常应急补元素也压得太低）；回调 1/5 同时保留「低于映射值、鼓励正常获取」的反复制语义。两个数值做成 Config 后，平衡调整无需改代码重编译。

**影响**：分解回收率提升一倍；服务器可整体放大/缩小合成消耗；客户端显示与服务器实际产出保持一致。

## 2026-08-12：工作站相邻同类生产任务自动合并——补货不再被 x1/x2 刷屏

**需求**（用户指令）：工作站里来一个合并机制——**只要任务相邻 + 要合成物品相同，自动合并成一个任务**，避免补货时大量 x1/x2 任务占满工作站队列。

**决策**：
- **入队时合并队尾同类任务（唯一漏斗）**：生产任务只有两条入队路径——`RequestProductionTaskPacket`（玩家 GUI 点合成）与 `ResourceSupplySystem.enqueueSynthesize`（自动补货），都走 `BuildingApiImpl.enqueueWork`。在 `enqueueWork` 里 `addLast` 前先尝试 `mergeSameRecipeTail`：新任务若以 `production:` 开头、且与队尾任务「签名相同」，则并入队尾——`count`、`channel_ticks` 求和，保留队尾 priority，不占新队列槽位（因此放在容量检查之前）。
- **「相邻」= 队尾**：新任务恒追加到队尾，唯一相邻的既有任务就是队尾；签名 = blueprint + 除 `count`/`channel_ticks` 外的全部参数（`anchor`、`recipe_id`/`item_id`）排序序列化。中间隔了别的任务（如 build）就不合并。
- **channel_ticks 求和**：四个生产蓝图（synthesize/decompose/craft_wand/brew_potion）的 `channel_ticks` 都是本任务的通道总时长，两任务合并后求和等价于顺序执行两请求，时长语义不变。
- **只合并 `production:`**：`build:` 施工、`node:gather` 等不合并（无 count 语义/不适用）。

**为什么**：补货（尤其自动补货 + 玩家手点）会把同一配方拆成大量 x1/x2 独立条目塞满队列（容量 60），既占槽位又难管理。入队合并是纯队列层操作、对调度与蓝图透明，一个漏斗覆盖全部来源；「只合并相邻队尾」严格匹配用户「相邻 + 同类」的定义，不会跨任务强行归并而打乱玩家手动排队的顺序。

## 2026-08-12：V 面板相机飞行速度统一 + 移除游戏内调速——速度走 Config

**需求**（用户指令）：ROAD 子模式下 WASD 飞行太慢（样条 `0.15×20=3 BPS` vs 鸟瞰 `10 BPS`），要与「正常 V 无子模式」统一；Build 一并核对。速度统一后**全模式提到 15**，并**删除游戏内调速设置**（滚轮/Ctrl 调速），改为 **Config 可调**。

**决策**：
- **统一相机飞行速度**：鸟瞰 / 道路（样条 3D + 俯视）/ 建造共用 `Config.panel.flySpeed`（默认 15 BPS）。样条编辑器删掉 `flyingSpeed`（0.15×20）与 `topDownSpeed` 字段，3D 飞行、俯视平移、鸟瞰飞行全部读同一 Config 值。Build 从鸟瞰进入时保持 overview 相机活跃（`MixinOverviewCamera` 在样条编辑时让位），天然同速，无需改。
- **WASD 不再要求按住右键才飞**：样条编辑器 3D 的飞行门控 `!cameraActive || imguiWantsKb` 改为 `imguiWantsKb`——与鸟瞰一致，WASD 随时飞，右键仅拖旋转。原「按住右键 + WASD 飞行」是 ROAD 下 WASD 无响应（或极慢）的根源。
- **删除游戏内调速**：overview 的 Ctrl+滚轮调速分支、样条的滚轮调速（3D 与俯视 Ctrl）全部移除；滚轮统一改为沿视线方向移动（缩放）。样条 3D 的 Ctrl 加速一并删除。
- **速度改 Config 可调**：新增 `panel.flySpeed`（DoubleValue，默认 15，范围 1~200），COMMON 配置。

**为什么**：ROAD 的样条编辑器与鸟瞰同属「脱离式相机」，但速度模型分裂（样条 3 BPS、鸟瞰 10 BPS）且要求按住右键才飞，玩家在 V 面板内切换子模式后 WASD 手感割裂。统一为单一 Config 值 + 移除游戏内调速，既保证全模式手感一致，又给服主/玩家留一个可调入口，不再需要游戏内临时调速。

## 2026-08-12：NPC 玩家级索敌——怪物会主动攻击 NPC，游客仍村民级

**需求**（用户指令）：原来 NPC 和游客都是村民级索敌（VillagerLike，只有僵尸/灾厄追）。改成 **NPC 和玩家一样**（骷髅/史莱姆/苦力怕等也会主动攻击 NPC），**游客保持和村民一样**。

**决策**：
- **新增 `PlayerLike` 标记接口（shared/entity），NPC 改实现它**：`WandscapeNpc` 从 `implements VillagerLike` 改为 `implements PlayerLike`；游客 `TouristEntity` 保持 `VillagerLike`。标记只表达「获得玩家级索敌」这一行为契约，不引入玩家任何其它行为。
- **`HostileTargetingHandler` 从只处理村民级扩为双轨**：生物加入世界时扫描目标选择器——凡有对 `Player` 的 `NearestAttackableTargetGoal`（骷髅/史莱姆/苦力怕/僵尸/灾厄等，含其它 mod 生物）→ 追加同优先级、目标宽类 `PathfinderMob`、谓词收窄 `PlayerLike && !Enemy` 的等价 goal；对 `AbstractVillager` 的 → 追加 `VillagerLike && !Enemy` 等价 goal（游客保留原行为）。依旧不枚举生物清单。
- **敌对测试法师（EvilMage）不追加玩家级索敌**：它是 `PlayerLike`（继承自 NPC）+ `Enemy`，光束伤害钩子打不了殖民地 NPC（`canBeamHurt` 排除）——若给它玩家级索敌，它会死盯打不死的 NPC。故「自身 `instanceof PlayerLike` 的生物跳过玩家级索敌追加」。
- **已追加标记共用「宽类 `PathfinderMob` 目标」**：两个等价 goal 都用 `PathfinderMob.class` 作目标类（原版生物不会直接索敌该宽类），任一已存在即跳过，防维度传送/chunk 重载时叠加。原「村民级专用」标记升级为共用。

**为什么**：玩家级与村民级的猎食者集合不同——骷髅/史莱姆/苦力怕只追玩家不追村民，若 NPC 只挂村民级，这些生物不会理它。用一个标记接口区分两级索敌，handler 按「生物本身已有的目标类型」自动追加，不硬编码生物清单；`!Enemy` 谓词保证敌对测试法师不会被原版怪当目标。NPC 被更多怪主动攻击后，自防御（`SelfDefenseExecutor` 扫 `Enemy` 反击）+ 投掷物躲避 + 走位会让殖民地有真实的战斗压力，这是玩家想要的效果。

## 2026-08-12：市政厅无仓库时充当仓库——防无 storage 建筑建造卡死

**需求**（用户指令）：殖民地一个仓库（`storage` 建筑）都没有时，试图建造「没有首免」的建筑（该类型首次免费已用过，需扣材料）会不会卡住？能否让市政厅在这种情况下充当仓库？

**决策**：
- **发货点兜底（治卡死）**：`ResourceRequestExecutor.findNearestStorage` 找不到 `storage` 建筑时回退到本殖民地 `government`（市政厅）建筑位置作物品发射点。此前无 storage 建筑时直接抛 `IllegalStateException("[ResourceReq] no storage...")` → `TaskExecutionSystem` 按普通异常走 `releaseToGlobalPool` → `GlobalTaskPool.releaseTaskForReassign` 无上限重试，哪怕银行里已有物品也永远建不出来。
- **市政厅仓库存取按钮（治缺料等待）**：`TownHallOpenPacket` 增 `canUseWarehouse`（殖民地无 storage 建筑为 true）；市政厅面板此时显示「仓库存取」按钮（**不替换原面板**），点击发 `TownHallWarehouseRequestPacket` → 服务端校验是 government 建筑后回 `WarehouseDataPacket` 打开 `WarehouseScreen`。`WarehouseActionPacket` 放行 government 建筑，使市政厅可作为存取终端。
- **材料本质是物理物品，非元素**：非首免建造的 `material_list` 请求物理方块物品；首建注资只给元素各 2000，物品存储为空。所以玩家必须能手动存料——市政厅按钮正是补上「无仓库时的存入入口」。

**为什么**：仓库是抽象银行（`ColonyItemBank`，仓库方块只是终端），但取料送达依赖实体 `storage` 建筑的坐标作发射点，缺了就抛异常被无限重试。让市政厅充当仓库同时补上「发货点」与「存入入口」两端，玩家无需先建仓库也能给非首免建造补料、继续发展。用按钮而非替换面板，保留市政厅的信息功能。

## 2026-08-12：NPC 躲避敌对投掷物——复用走位形式走开，不搞专门跳跃/侧跳

**需求**（用户指令）：NPC 能走位了，但箭、凋零骷髅头等投掷物不会躲。要求**能躲避敌对的投掷物**。用户明确：**别跳跃，就和走位一样走开，复用走位的形式，移速够快就能躲**。

**决策**：
- **复用 `GuardCombat.navigateAway` 的走位形式，不加导航新模式**：不引入 `NavigationState.DODGE` 模式、不做跳闪/侧跳、不做「躲避后恢复原任务导航」的保存恢复。投掷物躲避就是一个普通的走位导航（`movementOps.navigateTo` 走到安全落点），与战斗风筝/群殴规避/和平逃跑同一套机制——零新移动机制，纯行为补充。
- **新增 `ProjectileDodge`（guard）侦测**：每 3 tick 扫所有殖民地 NPC 周围 20 格内的敌对投掷物（发射者 `owner instanceof Enemy`：骷髅箭/凋零骷髅头/火球/女巫药水等），**轨迹预判命中才躲**（`willHit` 纯数学：直线飞行最近距离 <1 格且 2~16 tick 内会到，非正对不躲、已飞过不躲、太远不躲），命中则 `GuardCombat.navigateDodge` 沿弹道**垂直方向走开 2.5 格**（DODGE_DIST）让出弹道。单 NPC 冷却 12 tick 防持续弹幕把 NPC 来回拽；传送引导中（定身）跳过。
- **`findDodgePos` 复用走位落点的可达性约束**：落点只选「NPC→落点 无墙」的可站立格（走过去可达，短躲不寻路进墙、不触发传送兜底）；两个垂直方向都不可达返回 null → 站定硬吃（靠减伤/脱战回血兜底）。
- **方向是「垂直弹道 + 少许远离弹道源」**：纯反方向（朝弹道源）跑会一直留在弹道上被追上，垂直让开才真正躲掉；0.7 垂直 + 0.3 远离的混合保证是让开而不是迎着弹道跑。
- **`willHit` 抽成无 MC 依赖的纯数学**（入参全 double），配 JUnit 单测（正对命中/平行偏移/远离/太远/太近/静止/斜向接近）。

**为什么**：投掷物躲避本质是一种走位，现有走位机制（ECS 导航 + 可站立/LOS 落点）完全够用——为它单开一个 DODGE 导航模式 + 保存/恢复原任务导航，复杂度不成比例（一个 2.5 格的短走位不值得打断任务语义）。用户明确不要跳跃/侧跳；「走开 + 移速」即是用户要的形态。弹道垂直方向是让箭/骷髅头真正落空的关键（纯反方向会被直线弹道追上），落点可达性约束让这次「走位」和战斗走位一样不会失败进墙。

## 2026-08-12：跟随模式暂停殖民地任务——不接新任务 + 释放手头任务

**需求**（用户指令）：NPC 跟随状态下仍会接取城镇任务（导致被传送走去干活）。要求：**跟随状态不接取任何任务、手头任务也停下，但不影响自防御等个人行为**。

**决策**：
- **调度器跳过跟随 NPC（治本）**：`SchedulerSystem` 收集空闲 NPC 时经 `EntityOps.isFollowing(npcId)` 排除跟随中的 NPC——新任务不再派给它们（`assignLight` 唯一调用方就是调度器，无绕过路径）。跟随标记存 MC 实体，核心层零 MC 依赖：`EntityOps` 增 `isFollowing` 边界方法，`WandscapeEntityOps` 实现为 `npc.isFollowMode()`，测试用 `MockBoundary` 按 npcId 模拟。
- **执行器释放手头任务（兜底）**：`TaskExecutionSystem` 在「无工作→idle」分支前加跟随门控——跟随且持有 `global:*` 包（当前/pending/挂起栈任一位置）时 `releaseForFollow`：先 `syncStepToPool` 保留进度 + `returnAndReset` 归还资源，再 `releaseTaskForReassign` 回池供其他 NPC 接取，最后 `dropGlobalPackages` 清空队列里的 global 包。门控放 idle 分支**之前**，因为挂起栈里可能压着被自防御抢断的 global 包（此时 `hasWork()=false` 但 `hasGlobalPackage()=true`，先走 idle 会让该包永驻挂起栈）。
- **自防御等个人包不受影响**：`dropGlobalPackages` 只删 `global:*` 源头的包，`self_defense` 等个人包保留；释放时若当前包是个人包，其异步 future 由对应执行器独立驱动，`releaseForFollow` 不误清（只有当前包是 global 时才清 `pendingFuture` + 取消导航）。
- **守卫任务同步抑制**：`GuardTaskSource.hasAggressiveNpc` 同时排除跟随 NPC——全殖民地都跟随时不发布 `guard:attack`（跟随 NPC 不会从池里接守卫任务，发布后无人可接会空挂，与和平模式同构）。

**为什么**：跟随是「玩家把 NPC 当贴身随从」的行为指令，殖民地任务会把人拽走、违背玩家意图；只挡调度器不挡执行器会在「跟随时被抢断的 global 包在自防御后恢复」的边缘情况破口，故两处都做。个人行为（自防御/逃跑）是玩家的贴身保护预期，必须保留——用「source 前缀」而非「队列整体清空」区分，语义最稳。

## 2026-08-12：NPC 走位——战斗风筝 / 群殴规避 / 和平模式逃跑

**需求**（用户指令）：给 NPC 加走位能力，远离怪物、避免被群殴。用户选定三项：**战斗风筝**（近战怪贴脸后撤拉开、边走边打）、**群殴规避**（被围时往敌方质心反方向走位）、**和平模式逃跑**（不战斗但会躲）。

**决策**：
- **集中改在共用战斗引擎 `GuardCombat.engage`**（守卫 + 自防御自动同时生效）。分支顺序：L0 紧急奶 → 和平 return → beam.retarget → **群殴**（可见敌数 ≥3 → navigateAway 质心反方向）→ LOS 被挡 → 靠近寻路（原有）→ **风筝**（LOS 通但目标进入威胁距离 <6 → navigateAway 到威胁点 10 格外）→ 站定施法（原有）。抽 `castSelected`（CastBrain 选魔法 → dispatch → L2 普攻兜底）三处复用。
- **走位由 ECS 导航驱动**（`movementOps.navigateTo`）：**施法不再锁移动**——`WandscapeNpc.tickCastingState` 不再有「施法停移动」硬钉（删 `isCasting() && !suppressWandering` 时的 `getNavigation().stop()+setDeltaMovement(ZERO)`），`isCasting` 期间也能走位，光束等长施法不会被钉在原地；空闲乱走由 `RandomStrollGoal` 自己让路（尊重 `isEngineIdle`/`isCasting`/`manualCastTicks`，与 `FollowPlayerGoal.busy()` 同语义），殖民地任务施法期仍不乱走。光束 `MagicBeamEntity` 是独立实体、每 tick 跟随施法者并径向伤害（无 LOS 要求），风筝期间持续输出。
- **战斗中保持战斗态（禁 wandering）**：`engage` 每轮 `markInCombat`（`setAiWanderingEnabled(false)` → suppressWandering=true）防止战斗期间 NPC 闲逛走神；自防御/守卫执行器在战斗结束时 `markCombatEnd` 恢复。走位全由 ECS 导航驱动，不再依赖"顶住施法硬钉"。
- **后撤落点可达性**：`findRetreatPos` 增加「NPC→落点 无墙」LOS 约束（走过去可达），源头减少寻路失败→传送。正常走位不失败、不传送；self_teleport 传送回退保留，供狭小地带真正走投无路时逃生（不采用「走位禁传送 walkOnly」——会把狭小地带的逃生也一刀切掉）。
- **后撤落点安全**：复用 `findStandingYNear`/`isStandable`/`staffOf`/`positionHasLineOfSight`，采样角集中在「远离威胁」±半圆，优先「有 LOS 且离 NPC 最近」的可站立格；贴墙无落点则静默站定继续打（不寻路进墙、不卡死）。
- **和平模式逃跑**：`SelfDefenseExecutor` 不再跳过和平 NPC——可见怪进入 `guard.peaceFleeRange`（默认 8）时同样抢占注入 `self_defense` 包（抽 `injectSelfDefense` 共用抢占块）；`runCycle` 和平分支 `navigateAway` 后撤、无威胁 complete 恢复挂起任务（复用挂起栈恢复机制）。
- **数值归属**：风筝/群殴常量留 `GuardCombat` 私有（KITE_START_DIST=6 / KITE_STANDOFF=10 / CROWD_THRESHOLD=3 / CROWD_RADIUS=10，与现有 `ENGAGE_STANDOFF` 同风格）；`peaceFleeRange` 跨类被 SelfDefenseExecutor 用，走 Config。初版 KITE_START_DIST=3.5（贴脸才退）实测在光束长施法下几乎不触发，放宽到 6 让怪还在逼近就后撤。

**为什么**：原「看得见就 `cancelNavigation` + 站定施法」让近战怪贴脸/被围殴时 NPC 无脑站桩挨打；风筝是远程施法者的标准生存手段，且本模组光束独立实体 + `suppressWandering` 放行天然支持移动施法——零新机制、纯行为调整。和平 NPC 原本被彻底跳过（不战也不躲），逃跑让它真正「活下来」。

## 2026-08-12：传送更快 + NPC 环境伤害逃生

**需求**（用户指令）：1) 传送魔法持续时间（施法互斥锁 = 引导时长）与 CD 都减半，释放更快；2) NPC 因窒息、岩浆等**非生物伤害**受伤时，能用传送魔法则尝试用传送魔法离开危险区域。

**决策**：
- **锁/CD 减半**：`self_teleport` 法阵 `duration_ticks` 160→80（同时驱动引导时长、施法互斥锁与法阵动画），`teleport.json` `base_cooldown` 300→150；`WandscapeRitualOps`/`NavigationSystem` 的兜底常量同步减半。
- **环境伤害 = 无活体攻击者**：`SelfDefenseHandler` 在 `attackerFrom(source) == null` 时进入逃生分支——涵盖窒息/岩浆/火烧/溺水/摔落等，不区分具体伤害类型。
- **只救空闲 NPC**：`isEngineIdle()` 才触发，任务中的 NPC 由 `NavigationSystem` 卡住检测→传送兜底，避免打断任务执行。
- **逃生走直发仪式，不写 NavigationState**：`NpcEscapeTeleport` 在 r=4..16 方形外壳上搜最近安全落点（复用 `findSafeLanding` 判定：不落液体/不卡墙/实心地面），门控复用 `tryCastSpell`（锁/CD/蓝），`world.ritualOps.beginRitual(SELF_TELEPORT)` 直达 + `startManualCast` 举杖动画；触发的那一下伤害仍结算（保证脱战回血计时正确），之后引导期间起 shield。
- **引导期屏蔽环境伤害**（`isEscapeShielded`）：岩浆每 tick 4 点、40HP 撑不到 80 tick 引导结束，不屏蔽则岩浆逃生必死、功能失效。屏蔽只针对环境伤害，不挡生物攻击。
- **失败静默兜底**：非空闲/无落点/无蓝/在 CD → 不传不崩；落点扫描 40 tick 节流，防失败后每 tick 全扫。

**为什么**：8s 引导 + 15s CD 的传送作为移动手段过慢，减半后更实用；环境伤害（尤其岩浆/窒息）是空闲 NPC 最致命的死亡路径，主动逃生 + 引导期屏蔽让它真正活下来，而不是「传了但中途烧死」。锁与 CD 减半共用同一个 `duration_ticks` 数据源，改一处（JSON）即同时生效，不散落硬编码。

## 2026-08-11：NPC 普通攻击（L2 兜底，无有效魔法时）

**需求**（用户指令）：NPC 没有有效魔法可用时（如满血不该用治疗、魔法全在 CD/蓝不足）用普通攻击兜底——发射与建筑交互一致的白色粒子线，单体伤害 5 点，攻速 2s，不耗蓝。

**决策**：
- **挂在 `GuardCombat.engage` 的 L2 兜底**：`CastBrain.select` 返回 null（列表全不可施 / conditions 不满足）即普攻，守卫/自防御共用；施法互斥锁占用期间不普攻（不打断引导视觉）；冷却存 `WandscapeNpc` 瞬时字段（2s=40t，服务端瞬时态不持久化）。
- **伤害 5 点 × SPELL_POWER**：新伤害类型 `wandscape:melee`（`data/wandscape/damage_type/melee.json`，物理近战走正常护甲流程）；`damageSources().source(key, npc, npc)` 使 `getEntity()`=NPC → 怪物 `HurtByTargetGoal` 反击（记仇自防御）+ `NpcSpellPowerHandler` 按法术强度结算。用户选定「5 点×法术强度」而非固定 5，因此不改统一伤害钩子。
- **白色粒子线复用建筑交互的 CastBolt 粒子**：服务端 `sendParticles(Wandscape.CAST_BOLT, …)` 沿持杖手→目标身体中心 0.4 步长撒白色星点，与 NPC 做建筑交互时渲染器画的射线同一粒子，零新美术。

**为什么**：L2 原为「现有行为保持 = 站着挨打」，普攻让无蓝/CD 中的 NPC 不再空转；白色线复用既有 CastBolt 视觉；伤害走统一 SPELL_POWER 钩子（不破坏「任何 NPC 伤害源 `getEntity()`=NPC」契约，也不碰 `NpcSpellPowerHandler`）。

## 2026-08-11：NPC 面板新增「和平/跟随」行为切换

**需求**（用户指令）：NPC 右键面板加两个切换按钮——**和平**（不攻击任何生物）与**跟随**（离玩家 >5 格时走向玩家），放在策略按钮左侧。

**决策**：
- **状态存实体 + NBT 持久**：`WandscapeNpc` 增 `peaceMode`/`followMode`/`followerUuid` 字段并读写 NBT；经 `NpcDataPacket` 下发客户端渲染按钮文字，`NpcTogglePacket` 客户端→服务端切换后回发 `NpcDataPacket` 刷新（与改名/换装同模式）。跟随目标 = 开启跟随的玩家（UUID 持久）。
- **和平 = 攻击路径全阻断，分层兜底**：目标选择层（`SelfDefenseExecutor` 跳过和平 NPC；守卫任务中途开启即完成）、施法层（`GuardCombat.engage` 和平门控，L0 紧急自奶不受影响——治疗不是攻击）、伤害层（`MagicBeamEntity.canDamage` + `NpcSpellPowerHandler` 和平即 0 伤害，活跃光束立即停手）。`GuardTaskSource` 殖民地全和平时不再发布守卫任务，避免和平 NPC 反复接任务立即完成的空转。
- **跟随 = 原版 Goal，不与 ECS 导航打架**：`FollowPlayerGoal`（优先级 1，高于闲逛 5）用 vanilla `PathNavigation` 直行，起步 >5²、停止 <3²（滞回防启停抖）；ECS 任务/施法接管（`suppressWandering`/`isCasting`）时自动让路，stop 只在空闲态清导航。
- **面板加高 28px**：背包 hotbar 占满底部、策略/关闭按钮在右下角，两按钮直接放策略左侧会压到 hotbar → `PH 230→258`，四个按钮整行移到背包区下方。

**为什么**：和平/跟随是「玩家对单个 NPC 的行为指令」，必须服务端权威（防作弊）+ 可存档；和平要覆盖 NPC 全部出手入口（守卫/自卫/光束/AOE）而非只挡一处，否则「不攻击」破口；跟随若走 ECS 任务导航会与调度打架，用独立 Goal + 让路判定最干净。

## 2026-08-11：relax 可重复逛——精力低豁免 visited 门

**需求**（用户实测）：游客精力不足时会去找 relax 建筑，但 relax 逛过一次就被 `visitedBuildings` 挡死 → 精力耗尽后唯一能去的恢复建筑不可达，游客原地闲逛到精力 0 卡死（无恢复建筑 → 闲逛不离场）。

**决策**：
- **`visitedBuildings` 停留期不重置是红线（#8），不碰**——沿 ATM 先例，给 relax 单独**豁免**：新增 `relaxReusable` 判定（精力比 `energy/maxEnergy < TOURIST_ENERGY_RESTORE_THRESHOLD(0.25)`，即默认 energy < 25；**精力 0 恒可去**，不受阈值影响）通过时，`selectNextTarget` 跳过 visited 过滤，游客可反复回同一 relax 歇脚回精力；判定不通过（精力充足）仍按 visited 门。
- **判定与 `buildingScore` 的 relax 紧急加分共用同一阈值**：精力低于阈值时 relax 既豁免 visited 又 +100 紧急加分，行为自洽（真正需要时稳定选 relax）。
- **只豁免不重置**：`visitedBuildings` 仍累计，靠精力比门槛让游客在真正需要时回 relax，而不是整段停留反复刷同一栋。

**为什么**：relax 是精力循环的「白天恢复载体」，精力 0 时是唯一合法目标；visited 一次性门把它也挡掉 = 精力循环断链。用**豁免 + 精力门槛**而非**重置 visited**，保住防挂机红线（#8）——ATM 是「缺钱」例外，relax 是「缺精力」例外，同构。

## 2026-08-10：V 面板交互嫁接——旧常态（准心右键）+ 新四模式 + 数字键/Tab

**需求**（用户指令）：把旧 V 面板（ffc5358c 时代）的常态交互与新 V 面板的四种模式融合。常态（无子模式）改为**游戏层**——鼠标抓取、屏幕中心准心瞄准、**右键**交互建筑/NPC（不再自由光标左键）；`1/2/3/4` 快速切换 Build/Road/Stats/Warning；`Tab` 抬/放光标（替换已移除的 C 键）；退出子模式回到常态抓取。只有 Build/Road 是「新模式」（自由光标），Stats/Warning 是边缘系统保留旧模式；Build/Road 内删掉左键及建筑/NPC 交互（目标是建建筑不是交互）。

**决策**：
- **修复根因**：`WandscapePanelState.isCursorLifted()` 从 `return panelOpen` 改回真实 `cursorLifted` 字段——这是「面板一开就持久自由光标」的根源。新增 `syncCursorToState()` 在子模式迁移时重算光标意图：OVERVIEW/NONE/STATS → 抓取；BUILD/ROAD → 抬起。手动 Tab 翻转不被覆盖。
- **常态交互**：OverviewFlightController 射线源按光标状态选（抓取=相机中心准心 / 抬起=鼠标射线）；仅常态（OVERVIEW/NONE + 抓取）右键触发 `OverviewEntityInteractPacket`/`OverviewInteractPacket`。Build/Road/Stats 子模式内不做建筑/NPC 交互。
- **快捷键**：`InputEvent.Key` 里 `1/2/3/4` → 先 `keyHotbarSlots[i].consumeClick()` 吞掉原版快捷栏切换（Key 事件在 handleKeybinds 前触发，吞点击即阻止切栏），`1/2/3` 进子模式、`4` 开 AnomalyScreen；`Tab` → `toggleCursor()`（BUILD 开/关建筑条，其余翻转光标）。面板开着时在 `onClientTickPost` 里 `keyPlayerList.setDown(false)` 抑制 Tab 原版玩家列表闪烁。

**为什么**：自由光标 + 左键交互把「常态」从原版第一人称拉成了「鼠标点建筑」，与玩家「飞行时准心右键交互、数字键切模式」的直觉相悖；Build/Road 是施工工具，交互会误开建筑面板干扰施工。

**注意**：数字键只在面板开着时接管快捷栏（面板关 = 原版行为）；STATS 保持抓取（纯覆盖层），Warning 直接开 AnomalyScreen；不引入旧提交 ac99924f 的 LEGACY/FREE_CURSOR 双模式与 M 键。

## 2026-08-10：移除 WandscapeClient 的 `@Mod` 声明，修复专用服务器识别为“纯客户端模组”问题

**需求**（用户反馈）：模组放入 Dedicated Server（专用服务器）后，服务器与客户端无法正常注册，提示“这是个纯客户端模组，注册完成不了”。

**决策**：
- **移除客户端类上的重复 `@Mod`**：删除 `WandscapeClient.java` 类上的 `@Mod(value = Wandscape.MODID, dist = Dist.CLIENT)`。NeoForge 下一个 jar 内每个 modid 只能有一个 `@Mod` 主入口类；在客户端类上标注带 `dist = Dist.CLIENT` 的同名 `@Mod` 会导致专用服务端加载时认定该模组只在 Client 端生效（Client-Only），引发连接握手与注册失败。
- **重构客户端初始化入口**：将 `WandscapeClient` 的构造函数重构为静态 `public static void init(IEventBus modEventBus, ModContainer container)` 方法，并在 `Wandscape.java` 主构造函数末尾根据 `FMLEnvironment.dist == Dist.CLIENT` 物理侧判断安全调用。
- **清理与标准化事件订阅**：移除过时且易混淆的 `@EventBusSubscriber(bus = Bus.MOD)`，在 `init` 方法内部显式使用 `modEventBus.register(WandscapeClient.class)` 将客户端渲染、按键、粒子与 ReloadListener 订阅到 MOD 事件总线。
- **清除 Common/Network 层的客户端类泄露 (Client Class Leak)**：创建 `ScannerClientHelper` 与 `ClientSoundHelper`，隔离 `CreativeScannerBlock`/`ScannerBlock`/`SoundService` 中对 `net.minecraft.client.*`（如 `Minecraft`/`Screen`）的硬引用；将 7 个 S→C 网络包的 Handler 统一升级为 `setClientHandler` 委托模式，杜绝 Server 装载字节码时触发的 `invalid dist DEDICATED_SERVER`。
- **修复注册未绑定 (Unbound Value) NPE**：为 `CreativeScannerBlockEntity` 与 `ScannerBlockEntity` 增加两参构造函数并使用 `Wandscape.XXX_BE::get` 方法引用，移除错乱的 `creativeScannerBeTypeRef`，解决 `BlockEntityType` 注册阶段解包未绑定 Block 的 NPE。
- **修复多人服务器配置缺失与建造模式不可用 (BuildingConfig Sync)**：
  1) `WandscapeDataLoader.prepare` 增加 `manager.listResources` 回退机制，确保客户端侧资源重载也能装载 Mod Jar 内置的 `data/wandscape/buildings/*.json` 兜底配置。
  2) 新建 `BuildingConfigSyncPacket` 网络包并在 `OnDatapackSyncEvent` 阶段广播，专用服务器（Dedicated Server）在玩家进服或数据包 reload 时自动把最新 `BuildingConfig` 同步下发给客户端，解决多人联机下客户端报 `Config not found for slot` 以及【建造模式无法使用】的问题。
- **重构殖民地建立与初始法师生成流程 (Colony Founding & Initial Mage Fix)**：
  1) 移除 `PanelStateTogglePacket` 中玩家按 V 键时静默、偷摸自动建殖民地的隐藏逻辑，消除静默自动创建引发的法师生成失败死锁以及对【殖民地命名弹窗】的无限锁死拦截。
  2) 调整 `BuildingApiImpl.placeBuilding` 与 `BuildingUnlockChecker` 门控逻辑：未建立殖民地（`colonyId == null`）时，系统限制唯一允许建造的只有【市政厅】（`category="government"` 带有 `firstFree` 标记的启动建筑），其它非政府建筑在选单及服务端均锁定提示 `"需要先建造市政厅建立殖民地"`。
  3) 恢复放置/右键市政厅时的正规【创建殖民地】客户端命名弹窗；玩家确认提交名称后正规建立殖民地，并在市政厅前举行诞生烟花广播与刷出第 1 名带法杖及首批施工建材的初始法师（适用于单人与多人 Dedicated Server 专用服务器）。

**为什么**：NeoForge/FML 对物理侧和 `@Mod` 入口有严格规定，重复标注客户端 `@Mod` 破坏了服务器端网络握手的模组列表匹配逻辑；通过逻辑判定 (`FMLEnvironment.dist`) + 显式 `modEventBus.register`，既保留了模组在客户端的全部视觉 UI 逻辑，又恢复了在 Dedicated Server 下的标准双侧注册与正常联机。

## 2026-08-10：游客闲逛约束到道路——目标 = custom_roads 标签方块 + 沿路漂移 + 硬上限

**需求**（用户实测）：游客闲逛目标 = 锚点附近**随机地面点**，锚点每走出半径一半就整体漂移且无上限，时间一长游客越逛越远、在野外乱走。用户要求「闲逛要在道路上面闲逛，不能乱逛」。

**决策**：
- **道路方块 = `wandscape:custom_roads` 标签**（扩充默认值为草径/圆石/石砖/砂土等常见铺路方块），玩家自铺的方块也算，数据驱动、数据包可扩展，与 RoadNetwork 建路系统解耦。
- **闲逛目标选取**：锚点半径内随机的标签方块 → 2 倍半径内最近的路（拉回路上）→ 无路时锚点附近小范围微逛兜底。
- **锚点只沿路漂移**：仅当脚下是标签方块时闲逛区域中心才随动；野外不漂移。
- **硬上限**：离闲逛起点 > 32 格强制折返。
- 目标取点用短缓存（100 tick）的方块扫描，不引入 blob 缓存；通勤（去建筑/POI）仍走 RoadRouter 路网寻路，不受影响。

**为什么**：随机地面点 + 无界漂移导致游客脱离道路/城镇区域乱跑；把目标限定为玩家定义的"道路"方块（无论模组建路还是手铺），并让闲逛区域只在路上跟随，游客行为就稳定贴合城镇布局。不依赖 RoadNetwork 是避免"没建路游客就完全不动"的耦合，玩家手铺任意标签方块即可获得正常闲逛。

**注意**：若某区域完全无标签方块，游客只在该处小范围微逛（有 40% 概率周期性转去逛建筑，不会卡死）；`custom_roads.json` 默认值改动只影响新配置/数据包合并。

## 2026-08-10：游客生成防高 tick rate——生成路径每 tick flush

**需求**（用户实测）：把游戏 tick rate 调成极端值（如 1000）后，游客「来不及生成」——每天实际到达远少于固定新增数（只剩 1~2 人）。原因：`onServerTick` 开头 `tickCounter % CHECK_INTERVAL(100) != 0` 直接 return，生成路径只在每 100 tick 跑一次；高 tick rate 下游戏时间在两次 flush 之间推进得比窗口 [1000, 8000] 还快，`flushPendingSpawns` 还没跑、窗口就过去了，未到的 pending 在次日清晨重置时被 `pendingSpawns.clear()` 丢弃。

**决策**：`onServerTick` 拆成两段——**生成路径（清晨重置 + 调度 + flush）每 tick 执行**，不经过 CHECK_INTERVAL 门；重型工作（`cleanupTourists`/`processNightDepartures`）保持每 100 tick。每个游客的到达时间仍在 [1000, 8000] 内**随机**取，错峰到达；只要某个 pending 的 spawnTime 已到，下一次 tick 就立即生成，窗口内绝不漏。

**为什么**：生成路径本身很便宜（遍历 ≤7 个 pending 做一次 dayTime 比较，真正 spawn 每天只有 5~7 次、含一次 findSafeSpot），每 tick 跑无性能负担；换来的是高 tick rate 下每日新增可靠落地。保持随机错峰到达，不改成一次性生成。

**注意**：若 tick rate 极端到整个生成窗口在**两次服务器 tick 之间**被跳过（当天完全无生成），游戏时间逻辑无法兜底，属于该设置本身的限制。

## 2026-08-10：游客生成改为「每天固定新增」，废弃目标人口模型

**需求**（用户实测）：1 级每天生成 5~7 个游客，但殖民地已有游客（尤其前一晚住店的游客仍占着坑）时，当天新生成数明显变少——`toSpawn = targetCount - existing` 把「每日新增」做成了「维持目标人口」。

**决策**：`createSchedule` 不再用影子注册表统计 `existing` 去扣减，每天固定新增 `toSpawn`（1 级 5~7，等级每 +1 上下界各 +1）个游客；顺带删除废弃的 `countExistingTourists`，并修正生成区间 off-by-one（`nextInt(width)` 而非 `nextInt(width+1)`，使 1 级真实为 5~7 而非 5~8）。人口仍由 `TOURIST_MAX_PER_COLONY`（默认 100）、夜晚离场、停留截止、闲置超时兜底，不会无限膨胀。

**为什么**：玩家预期是「每天来一批新游客」，而非「殖民地维持恒定人口」。目标人口模型下住店客越多、新客越少，与直觉相悖。

**注意**：游客停留 2~4 天，在驻人口会随日新增累积到 ≈ 每日新增 × 平均停留天数（稳态约 20~30 人），属预期「城镇热闹起来」。

## 2026-08-10：住店客机制——入住后记住酒店、夜晚回店睡觉、不再因天黑被清场

**需求**（用户实测）：游客天黑了还在逛商店，然后被清场刷掉（sim 从 13000 起、实体从 18000 起清无旅店游客）。

**决策**：
- **住店客（resident）机制**：游客入住酒店后 `checkedInBuildingId` **常驻**（NBT/影子持久化）——清晨只「晨起」（`HotelStayHandler.wakeUp`：精力回 100、回入住前站位、住店晚数 +1），**名单不删**；白天照常外出逛街，夜晚回**自己**旅店睡觉。住店客**无论多晚不被清场**，只按停留截止（departureDeadline）或满条当晚开心离场（用户确认「满条当晚就离场」，腾床位、给经验）。满条离场/到点离场时 `checkOut` 才从酒店名单删除。
- **傍晚路由**：`tourist.eveningRoutingStart`（默认 16000）起，无旅店未满条游客**停止当前任务**去旅店（`findHotelTarget` 全殖民地找最近可用旅店；过远 > `tourist.hotelTeleportDistance`（默认 64）**直接传送**，省寻路开销）；住店客夜晚**空闲**时回自己旅店（不打断进行中的交互，与「停止当前任务」区分）。
- **夜晚阈值 13000 → 14000**（`tourist.nightStart`，可配置）：游客多逛 40 分钟（14000 起才优先旅店/可入住）。
- **sim 清场窗口对齐实体路径**：未观察游客也只在 **18000–24000** 离场窗口被清（原 sim 从 13000 起清，比实体早 5 小时）。sim 住店客同步白天外出/夜晚回店/晨起保留登记。
- **入住强制躺床**：入住即 `settleIntoBed`——有空床躺空床；床不够（全被占用）躺最近一张床（纯视觉可共用）；旅店一张床都没有 → 卡原地不动。床判定 = 建筑 bbox 内 `BedBlock`（跳过原版 OCCUPIED），纯视觉不上床方块占用。
- **入住即时完成**：到达旅店（bbox 内/到达入口/已进店内）即 `tryHotelCheckIn`，不占 spot、不等 `interaction_duration_ticks`；夜晚意图入住但旅店满员 → 不当 service 逛/排队，放弃重新规划（避免排队拖到被清场）。

**为什么**：原机制「夜晚无旅店 → 离场」把游客当一次性消费品，天黑后还在逛商店的游客必然被清；住店客机制把「有酒店」变成游客的庇护——一次入住、每晚回店，清场只针对真的无店可住的游客。满条当晚离场保留「开心回家给经验」的情绪回报。

**注意**：`tourist.nightStart`/`tourist.eveningRoutingStart` 等新配置默认值只对新生成配置生效；已有存档的 `serverconfig` TOML 需手动改或删除后重新生成。

## 2026-08-10：排队惩罚改等比例降权 + ATM 加分下调

**需求**（用户实测）：500 满钱游客在低价值自动售货机前排长队、不排属性好几十的好店，宁可闲逛/取现也不等。排查确认 `QUEUE_PENALTY=3000` 相对单次满意度增益（~15-150）高 20-200 倍，好店一满员就被压到 `weightedPick` 权重地板 0.5、与 0 分垃圾建筑等权；且惩罚二元（spot 全满 **或** 有 1 人排队即全罚），不看排队深度。

**决策**：
- **排队惩罚从固定减分改等比例降权**：spot 全满时按**总排队人数**等比缩小——1 人 ×0.75、2 人 ×0.5、3 人 ×0.25，封顶 ×0.25（人再多不再加深），0 人 ×1.0。多建同类型 = 排队短 = 降权轻；排队短的好店仍比空置低价值建筑更受欢迎，「分流」回到设计本意而非「驱逐」。
- **ATM/精力加分与单次增益同量级**：`WALLET_LOW_BONUS` 2000 → 50（钱包 < 初始 1/4）、`WALLET_EMPTY_BONUS` 4000 → 100（钱包 = 0）、`ENERGY_URGENCY_BONUS` 2000 → 100（精力低）。三类加分都不再碾压选店。
- 保留 `isFull` 触发门（spot 空则无惩罚，即使 queue 有残留也不误伤）。

**为什么**：惩罚意图是「多建同类型 = 排队短 = 有收益」，旧量级把满店从最优做成最差，正反馈让好店被全城嫌弃。百分比降权保留「排队要等」的分流压力，但不抹掉建筑本身的价值排序。

## 2026-08-10：ATM 分批取现——豁免 visited 不重置，加取现冷却

**需求**（设计审查）：游客钱包低时偏好 ATM，但 `visitedBuildings` 一次停留只逛一次 → ATM 只能取一次钱（level-1 池子 travelFund=1500 只取得出一部分、剩余滞留花不出去），且池子耗尽后游客仍可能因偏好跑去 ATM 取 0。

**决策**：
- **`visitedBuildings` 停留期不重置是红线（#8），不碰**——不通过清空已逛集合来实现「可重复取现」，而是给 ATM 单独**豁免**：`atmReusable` 判定（池子有余额 + 钱包低于初始 1/4 + 取现冷却已过）通过时，`selectNextTarget` 跳过 visited 过滤，游客可再去同一台 ATM 分批取现；判定不通过（池子空/钱包充足/冷却中）仍按 visited 门。
- **取现冷却**：`tourist.atmWithdrawCooldownTicks`（默认 2400 tick = 2 分钟）控制分批节奏，防止游客连跑 ATM 一次性清空池子；上次成功取现记 `lastAtmWithdrawTime`（实体/影子 NBT 持久化，timeBase 制）。
- **池子空不再偏向**：`buildingScore` 的 ATM 紧急加分要求 `atmReusable` 通过——池子空/冷却中不加分，游客不会因偏好跑去 ATM 却一分钱取不到。
- **ATM 取现模型改为「单次取现 = 初始钱包随机 20%~50%」**（封顶 travelFund 池子）：删除 `withdraw_amount` 固定上限（`AtmConfig`/`atm.json` 同步去除），单次取不完、天然配合冷却分批取现。

**为什么**：travelFund = 随身现金 ×3 的池子设计意图就是「分批多次取现」，visited 一次性门恰好打破它；用**豁免 + 冷却**而非**重置 visited**，保住防挂机红线（#8）——整段停留仍一栋建筑只逛一次，ATM 是唯一例外（缺钱时）。

## 2026-08-10：道路 4 大模式统一整合进 ImGui 道路制作工坊 (`SplineEditorImGui`)

**需求**：把 ROAD 的 4 种模式（Replace 直线地表替换、Fill 立方体填充、DestroyFill 铲平垫平、Spline 样条曲线）统一整合进入 ImGui 界面，以原 `SplineEditorImGui` 为基准架构呈现。

**决策**：
- **拓展升级 `SplineEditorImGui` 为【道路制作工坊 (Road Studio)】**：在面板顶部增加横向 4 模式切换器（`[ 替换 ] [ 填充 ] [ 铲平 ] [ 样条 ]`），共享 `RoadPlacementState.getActiveTool()` 作为 ToolMode 唯一真源。
- **模式 1 (REPLACE)**：预设下拉框 + 起终点 BlockPos 坐标手动微调与【捕捉脚下方块】/世界点选双重机制 + 跨度/距离计算 + `【下发直线铺设任务】`。
- **模式 2 (FILL)**：预设下拉框 + 3D 对角点坐标微调/捕捉 + 体积计算 (W×H×D) + `【下发立方体填充任务】`。
- **模式 3 (DESTROY_FILL)**：参照基准方块捕获与展示 + 平整边界坐标 + 平整面积计算 + `【下发地形平整任务】`。
- **模式 4 (SPLINE)**：保留原本 Spline 编辑器的 3 大 Tab（曲线节点/3D Axis Gizmo/2D 俯瞰、阵列生成、模板导出与工具）。
- **完全替换旧 Overlay**：废弃原底部 2D HUD `RoadPlacementOverlay`。从 V 面板呼出【道路】栏时直接拉起 ImGui 道路制作工坊，通过 `C` 键随时在 ImGui 鼠标交互与 3D 世界视角操作间切换。

## 2026-08-10：生成窗口收窄到 1000–8000

**需求**（用户实测）：游客生成太晚、下午晚上都有生成；晚到游客没时间逛/走向旅店，当晚就被清场消失。

**决策**：`TOURIST_SPAWN_WINDOW_END` 默认 13000（约 18:30 黄昏）→ **8000（约 14:00）**，`SPAWN_WINDOW_START=1000`（约 07:00）不变，窗口内仍均匀分布。游客集中在上午到，最晚的也有整个下午逛、傍晚走向旅店，减轻「晚生成 → 没时间逛 → 夜晚无旅店可达被清场」。**离场规则保持现状**（未满条游客夜晚无旅店 → 离场，goal.md 规则 3，用户确认不改）。

**注意**：配置默认值只对新生成配置生效；已有存档的 `serverconfig` TOML 里 `spawnWindowEnd` 仍是 13000，需手动改或删配置再生成。

## 2026-08-10：游客目标选择偏好改为「总三值满意度增益」+ 晃悠根因分析

**需求**（用户实测）：游客在建筑附近（spot 有空位）仍一直晃悠不去逛，尤其「一维数值夸张、另两条很低」的游客；Comfort 侧重游客 Comfort 满条后仍被高 Comfort 建筑吸走。

**决策**：
- **满意度偏好 = 总三值满意度增益**：`score(满意度) = Σ_d min(需求缺口_d, round(建筑该维值 × TOURIST_BAR_GAIN_COEFF))`，即「这次访问能把总三值满意度提升多少（潜在总三值 − 现在三值）」，与 `fillBars` 实际结算逐维一致。旧式 `Σ 需求缺口 × 建筑值` 会把单维数值夸张（如 Comfort 90/其余 0）的建筑权重抬得过高——Comfort 满条的游客仍被高 Comfort 建筑吸走，浪费访问、另两条常年填不满。增益式下均衡建筑（30/30/30）比单维夸张（90/0/0）总增益更高（90>80），游客会优先去能把三条总满意度抬得更高的地方。精力/钱包紧急加分、排队惩罚不变。

**晃悠根因分析**（用户问「太挑剔还是视野太小」）：
- **不是挑剔**：`weightedPick` 兜底权重 0.5，候选非空必选；视野内无目标才闲逛。真正挡住目标是**过滤**而非评分：
- **① visited 耗尽**：`visitedBuildings` 一次停留不重置（红线 #8，防挂机），小/同质殖民地很快把视野内（48 格）建筑逛完 → 闲逛，直到漂到新的未逛建筑附近。
- **② 傍晚旅店锁（与「一维夸张两维低」最吻合）**：`selectNextTarget` 里 `nightHotel = 夜晚 && !满条` → 未满条游客夜晚只能去旅店；而离场窗口 18000 才开。13000–18000 视野内无旅店 → 闲逛 5000 tick。一维夸张两维低的游客几乎永远不满条 → **每天傍晚都晃悠**。
- **③ gap×value 评分浪费访问**：侧重 Comfort（80/35/35）游客起步被 Comfort 90 建筑吸走（score 7200 > 均衡 4500），但实际总增益 80<90——另两条常年低 → 加剧 ② + 更快逛完视野内建筑。
- **④ 精力 0 → 只去 relax**（无恢复建筑 → 闲逛，不离场，goal 非协商项）。视野 48 是次要因素（殖民地建筑更分散时确实够不着，可按需调 `TOURIST_VISION_RADIUS`）。

- **决策②（傍晚回退，本次一并落地）**：`selectNextTarget` 夜晚 + 未满条 → **优先旅店**（不查 visited）；视野内无旅店 → **回退普通建筑**（尊重 visited、精力 0 只去 relax），未满条游客傍晚不再干晃 5000 tick；18000 后仍由离场窗口接管（入旅店/离场）。满旅店不入回退候选（夜晚不该当普通 service 逛）。

**本次改动修 ③ + ②**；① visited 不重置是设计红线（防挂机）不碰；视野 48 可按需调 `TOURIST_VISION_RADIUS`。

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
- **排队站位 = 每 spot 一队、沿 spot 朝向排开（2026-08 新增）**：原「全满排队」只是站在建筑旁随机等位，不好看。改为**每个 spot 各排一队**：新游客均匀分散到队最短的 spot 后（并列取最小下标），沿该 spot 的 `facing` **反方向**一个贴一个向后排（`tourist.queueSlotSpacing` 默认 1.0 格），队首紧贴正在交互的游客，**朝向 = spot 朝向**（和交互游客同向）；队首离队后续自动前移。**严格 FIFO**（只有队首可认领该 spot 空位）。队列注册在 `TouristSpotManager`（按 buildingId→spotIndex 分队列，内存态），站位坐标计算在 `TouristSimulation.queueSlotPos`。
- **交互位唯一真源 = world 里 `interact_spot_marker` 方块**：BE 不存 spot 列表；放置=标记、右键循环动作、潜行右键移除，action 存 blockstate（无 BE/NBT）。导出扫 boundary 内 marker → `interact_spots`，marker 格跳过 pattern（创作者自行留空该格）。
- **四类 category 保持独立（不合并）**：`shop`（卖物品）/`service`（产元素+耗精力，`max_occupancy>0`=旅店）/`relax`（回精力）/`atm`（取现 `min(withdrawAmount, travelFund)`）；统一成 `interact` 的 `interaction` 块 → **二阶段**（`architecture/plan/phase-2/`）。动作只决定游客活动状态/粒子，精力/经济效果由模式预设块决定。
- **目标选择 = Find-Best-Action，只看视野内**（`TOURIST_VISION_RADIUS` 且已加载）：`Σ(总三值满意度增益) + 精力紧急(relax) + 钱包紧急(atm) − 排队惩罚`；视野内无目标 → 闲逛；精力 0 → 只能去 relax、无则闲逛（**不离场**）。
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

## 2026-08：游客 1 级需求基数下调 + 分解折价加深

**需求**：1 级游客均衡需求 50/50/50 对早期殖民地仍偏高、喂满偏慢；分解 1/5 折价让元素应急获取偏易，弱化工坊/商店经济。

**决策**：
- `TOURIST_NEED_BASE` 默认 150 → **60**（`tourist.needBase`），`TOURIST_NEED_PER_LEVEL` 保持 20 —— 1 级均衡 20/20/20、侧重 32/14/14；每级 +20 的难度曲线不变。
- `DECOMPOSE_DIVISOR` 5 → **10**：分解产出 = 元素值 × 1/10 向下取整；提前拒绝阈值随之变为 count×总价值 < 10。

**影响**：游客更容易喂满三条（满条给经验更快），1 级新手更顺；分解折价加深，应急补充变贵、鼓励正常获取元素。

## 2026-08：建筑数据调色板 + 分块网络同步

**需求**：进服同步建筑配置崩溃——`BuildingConfigSyncPacket` 把整店 JSON 当单个字符串发，sea_store 紧凑 519KB 超 `writeUtf` 262144 上限。且只有几万方块就超，未来更大建筑仍会超。

**根因**：① `block_mapping` 是 `{"x,y,z": "完整方块态字符串"}`，每方块重复写完整 ID，占 JSON 66–79%；sea_store 8502 块只有 462 种方块。② 单字段 `writeUtf` 有 262144 硬上限，不拆分就无法根治。

**决策**：
- **数据格式改调色板**：`block_mapping`（N 条重复 ID）→ `palette`（M 个去重方块态）+ `block_indices`（N 个索引，与 `pattern` 对齐）。`BuildingConfig` 字段换成 palette/blockIndices，`blockMapping()` 改为派生方法（调用方零改动），`blockIdAt(i)` 供快路径。**仅新格式**：解析器拒绝旧 `block_mapping`，全部 39 个建筑 JSON 用脚本迁移。
- **旋转调色板级**：旋转 = 旋转 pattern 位置 + 旋转每个 palette 方块态一次（M 次而非 N 次），block_indices 不变；蓝图 `blocks` 参数仍传派生 map（WorkItem 走内存无上限，DSL 零改动）。
- **`block_nbt` 保持 `"x,y,z"` 键**：改索引键要动 DSL `keyof` 函数，block_nbt 只占 10% 不值。
- **网络分块同步**：`BuildingConfigSyncPacket` 删除，新 `BuildingConfigSyncChunkPacket`——zlib 压缩后按 16KB 切块（`writeByteArray`，避开 writeUtf 上限），客户端 `BuildingConfigSyncReceiver` 按 configIndex+chunkIndex 重组注册；sea_store 紧凑 207K → zlib 约 40K → 3 块。
- **渲染端缓存**：投影/施工幽灵/面板预览每帧重复做 N 次方块态字符串解析 → 按 config 弱缓存 `Map<BlockOffset,BlockState>`（WeakHashMap，config 不可变不泄漏），渲染走 `blockIdAt(i)` 快路径。

**为什么**：体积（N→M 去重）与结构上限（单字符串→多包分块）是两个独立根因，分别根治才能既当前不崩又未来可扩。调色板复用 MC 区块思路，向后兼容靠解析期转换而非双格式常驻。`block_nbt`/蓝图契约/渲染热路径按"改动面 vs 收益"取舍，最小化波及。

**约束保留**：蓝图 DSL（`build:clear_and_build`/`place_structure` 的 `blocks` map 契约）不动；`blockMapping()` 派生方法保留供事件型调用（完整/破损检查）；老世界 datapack 导出的旧格式文件将无法加载（需用扫描器重新导出）。
