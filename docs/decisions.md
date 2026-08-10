# 设计决策记录

本文件记录偏离直觉的设计选择及其原因，供后续开发快速理解「为什么这么做」。

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
