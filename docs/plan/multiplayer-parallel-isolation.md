# 多人生存「完全平行」殖民地隔离 —— 方案与可持续推进路线图

> 创建日期：2026-09-04 | 对应分支：`multiplayer-isolation`（基于 `c37edc05` 前的基座）
> 性质：活清单文档——按「已完成 / 待实测 / 后续可选」推进，每步落到代码事实（文件 + 方法），随时可接续。
> 关联：[多人生存隔离现状评估与 RBAC 演进](../multiplayer-isolation-survey.md)、[架构决策记录](../adr.md)

---

## 一、目标与模型

**目标**：玩家 A 独立玩 A 的小镇，玩家 B 独立玩 B 的小镇；互不可见、互不可操作。

**模型**：完全平行（own-colony-only）
- 一个玩家在服务器上的一切 Wandscape 上下文 = **他自己创建的小镇**；
- 没有小镇 = **建镇引导态**，绝不显示/操作别人的小镇；
- 世界内他人的方块/建筑/NPC 一律不可交互、不可破坏。

**为什么是结构隔离而不是枚举式权限（关键认知）**：
- 底层数据早已按 `colonyId` 隔离（`ColonySavedData` 存多个镇，`BuildingSavedData` 每座建筑带 `colonyId`，
  仓库/任务/元素均按镇记账）。数据不是病根。
- 病根在「**玩家上下文解析**」：代码里到处用「无自有镇 → 回退到**空间最近镇**（最近原点 ≤256）」，
  这正是玩家 B 无镇按 V 弹出 A 镇数据的来源（主凶 `PanelStateTogglePacket.handleServer`）。
- 因此解法不是给每个动作补权限校验（Phase0 那套 34 处 `ColonyGate` 判断——臃肿、防不胜防、让殖民地
  分开几乎不可能），而是**结构上只让玩家触达自己的小镇**：own-context 绑定 + 咽喉归属判定 + 领地方块防破坏。

---

## 二、关键决策（已裁定，改动前先读）

| 决策 | 说明 | 状态 |
|---|---|---|
| **不强制小镇隔离距离** | 小镇可建任意近，靠归属判定隔离；两镇工作圈（最近原点 ≤256）重叠时，归属由「最近原点」决定。 | 生效。`ColonyCommand.createColonyAt` 的距离守卫**注释保留、未启用**。 |
| **道路铺设不查镇归属** | 道路是 level-global 图，与镇归属脱钩；避免误杀正常铺路。 | 生效。`RoadPlacePacket` 等**未加**归属判定。 |
| **防恶意客户端（构造直发包）** | 合法玩家因界面开在咽喉已被拦、基本够不到；只有作弊客户端能直接构造带 `colonyId`/`buildingId`/`entityId` 的包。 | **待实测确认本方案可行后**再做。 |
| **统一判定入口（网络注册包装）** | 在 `Wandscape.java` 的 40 处 `.playToServer` 注册外包一层 helper，每个包实现 `resolveColony(level, player)` 上报目标镇，分发处**一处**判定。 | **待做多人权限系统（RBAC）时**一次到位，避免二次返工。 |
| **归属判定孤例** | 收敛进 `content/colony/ownership/ColonyOwnership`，其余域只调用、不重写。 | 生效。 |

**单条铁律（扩展时保持一致）**：目标（建筑/NPC/位置/镇）的 `colonyId == 玩家自己的镇`（`getColonyByFounder`）；
目标为 `null`（无归属：建镇流程/未关联建筑）→ 放行；操作者 OP（权限 ≥ 2）→ 旁路放行。

---

## 三、已完成实现（编译通过，提交 `44c6d31f`）

### A. 基座编译修复
- `Config.java`：补上缺失的 `public static final ModConfigSpec SPEC = BUILDER.build();`
  （基座 `c37edc05` 本身因缺这行而编译失败，属修复，非隔离功能）。

### B. own-context 绑定（删「无镇回退空间最近镇」）
统一改为：`colonyApi.getColonyByFounder(player.getUUID())`，为 null 即空态（建镇引导），**绝不回退**。
- `foundation/ui/panel/PanelStateTogglePacket.handleServer` —— 主凶，根治 B 按 V 弹 A 镇。
- `foundation/ui/panel/PanelStateTracker.syncHudForColony` —— HUD 只推给「面板开着且该镇属于本人」的玩家。
- `content/colony/stats/internal/StatisticsCollector.pushStatsToPlayers` —— 统计只按 founder 匹配。
- `content/building/projection/network/ProjectionEnterPacket.handleServer` —— 投影建筑槽只关联自己镇。

### C. 咽喉归属判定（在各入口一次判定，目标镇 ≠ 自己镇则拒）
- `content/building/internal/BuildingInteractHandler.handleInteraction` —— **全部建筑 GUI 的唯一分发点**
  （`onRightClickBlock` + `OverviewInteractPacket` 都走这里）。仓库/市政厅/酒馆/商店/工坊/小屋全被覆盖。
- `content/building/projection/network/BuildingActionPacket.handleServer` —— 拆/撤/修建筑。
- `content/building/projection/network/ProjectionPlacePacket.handleServer` —— 只能在自己镇内放建筑；
  未归属地只允许**政府建筑**（建镇）；禁止把建筑放进别人小镇（避免误归属/耗对方材料）。
- `content/npc/entity/WandscapeNpc.mobInteract` —— 打开法师装备/信息菜单前判定。

### D. 归属孤例与拒止反馈
- `content/colony/ownership/ColonyOwnership`：`ownColony(player)`、`isOwn(colonyId, player)`、
  `isOwnColonyOf(colonyId, player)`、`deny(player, what)`（Action Bar + Toast + 拒音 + 日志）。
  `isOwn`: `colonyId == null` 放行 / OP 旁路 / 只许自己的镇。`deny` 是唯一拒止出口。

### E. 领地方块防破坏（原版方块层）
- `content/colony/guard/ColonyLandProtectionHandler`（已在 `Wandscape.java` NeoForge 总线注册）：
  - `BlockEvent.BreakEvent` / `EntityPlaceEvent`：若方块属于某殖民地建筑（`getBuildingIdAt`/`getBuildingIdInInteractionZone`
    → `BuildingState.getColonyId()`），且非 Owner → 取消 + 反馈。
  - `PlayerInteractEvent.RightClickBlock`：属于殖民地建筑、且是 vanilla 容器（箱/熔炉等）→ 非 Owner 拦截（防翻箱）。
  - `ExplosionEvent.Detonate`：从受影响方块中**滤除**所有「属于殖民地建筑」的方块（防 TNT/苦力怕/袭击炸镇）。
  - **只保护「属于建筑」的方块，不圈整个 256 半径**——不干扰野外地形，避免过度限制。

### F. 法师实体 id 直发包门禁（可被作弊客户端伪造 entityId）
- `NpcDismissPacket` / `NpcRenamePacket` / `NpcStrategyPacket` / `NpcTogglePacket` / `MageModeActionPacket`：
  按 `npc.colonyId` 归属判定，非 `PLACEHOLDER_COLONY` 且非本人 → 拒。

---

## 四、为什么这样就能「分开」

1. **上下文**：所有「我的小镇」入口（面板/统计/投影/道具）只认 `getColonyByFounder`，无镇即空态，
   从根上杜绝了「观察/操作别人的镇」。
2. **交互咽喉**：合法玩家打开任何镇内界面，先在咽喉比对目标镇 == 自己镇，否则界面都开不了 ——
   这是**结构隔离**，不是「按钮灰了但你还能发对应包」的枚举式防线。
3. **物理**：领地方块防挖/防放/防炸，B 无法在 A 镇里造成破坏。
4. **纵深**：法师实体 id 直发包按归属门禁，堵住简单可伪造的破坏性路径。

配合「一人一镇」（`ColonyCommand.createColonyAt` 已有守卫）与「数据按镇分账」，即可达成 A/B 各自独立玩。
剩余缺口仅为「构造带真实 `colonyId`/`buildingId`/`entityId` 的直发包」——合法玩家够不到，属下一层。

---

## 五、待实测（验收清单）

环境：`./gradlew build` 后，服务端/局域网双账号。

1. **B 无镇不再看到 A**（核心症状）：A 建镇；B 新进不建镇，进 A 镇按 V → 顶部 HUD 应为**空**，
   看不到 A 的三值/经济/在建/建造槽。
2. **B 能独立开局**：B 离开 A 镇按 V（面板空）→ 建造模式放市政厅 → 右键命名 → 成功建 B 镇；
   此后 B 在 B 镇的 V/统计/仓库/建造/NPC 全是 B 自己的。
3. **B 无法动 A**：矿镐拆 A 建筑被拦；放蓝图/拆/撤 A 建筑被拦；右键开 A 仓库/市政厅/酒馆界面被拦；
   解雇/改名/改配置 A 法师被拦；TNT/爆炸不损 A 建筑方块。
4. **A 无感**：A 在自己镇内一切操作正常（Owner 旁路）。

**边界情形（若实测不符，走第六节路线 1）**：A/B 若建得极近（≤256 工作圈交叠），归属按「最近原点」，
B 在交叠区（判定属 A）内放建筑/开物会被拦——这是预期。若体验过差，再考虑加最小距离或改归属判定。

---

## 六、后续路线（按顺序推进）

1. **实测后收尾（本方案可行则跳过）**
   - 若近距/交叠归属误伤：选项 A 恢复最小隔离距离（`createColonyAt` 守卫，已注释保留）；
     选项 B 把归属判定从「最近原点」改为「建筑记录归属」（更精确）。
2. **防恶意客户端（构造直发包）** —— 实测确认方案可行后再做
   - 剩余包清单：`ColonyNameUpdatePacket`、`TownHallNameStylePacket`、`TownHallTouristSpawnPacket`、
     `ShopMaxStockPacket`、`TavernRecruitPacket`、`MageHutActionPacket`、`AltarCastRequestPacket`、
     `TownHallWarehouseRequestPacket`、`RequestProductionTaskPacket`、`RequestGatherTaskPacket`、
     `TaskQueueModifyPacket`、`TaskManagementActionPacket`、`WarehouseActionPacket`（取物部分）、
     `SplineBuildPacket`/`RoadWithdrawPacket`/`FillBoxPacket`/`DestroyFillPacket`（若决定道路查归属）。
   - 两种做法：逐个用 `ColonyOwnership` 加一行判定+`deny`；或（推荐与 RBAC 一并）做统一入口（见 3）。
3. **统一判定入口 + 多人权限系统（RBAC）** —— 参考 `docs/multiplayer-isolation-survey.md`
   - 网络注册包装：把 `Wandscape.java` 40 处 `.playToServer` 收敛为 `regServer(...)` 单 helper，
     每个包实现 `@Nullable UUID resolveColony(ServerLevel, ServerPlayer)`，分发处**一次**判定归属。
   - `ColonyOwnership` 演化为 `ColonyPermissionService`（角色权限矩阵）；`ColonySavedData` 增成员与版本迁移；
     `ColonyApi` 暴露权限契约；TownHall GUI 增「成员与权限」分页 + 在线邀请协议。
   - 此时「只许自己的镇」升级为「角色权限」，统一入口一次到位。

---

## 七、关键代码事实速查

| 事实 | 位置 |
|---|---|
| 空间最近镇解析（最近原点 ≤256，无归属） | `content/colony/ColonyApiImpl.getColonyId` |
| 玩家自己的镇（founder 绑定） | `content/colony/ColonyApiImpl.getColonyByFounder` |
| 每镇多字段存储（id/origin/founder/nameStyle/tourist 开关） | `content/colony/ColonySavedData` |
| 每建筑带 colonyId | `content/building/internal/BuildingState.getColonyId` |
| 建筑 GUI 唯一分发点 | `content/building/internal/BuildingInteractHandler.handleInteraction` |
| 一人一镇守卫 | `content/command/ColonyCommand.createColonyAt` |
| 归属孤例 | `content/colony/ownership/ColonyOwnership` |
| 领地方块防破坏 | `content/colony/guard/ColonyLandProtectionHandler` |
