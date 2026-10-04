# 多人交互调研：模拟殖民地（MineColonies）设计拆解

> 状态：**调研稿，未提交**。目的不是照搬，是为「Wandscape 多人交互」找参照系与反面教材。
> 参照版本：`_refs/MineColonies`（MC 1.20.1 Forge，源码头 `0.0.1-LOCAL`）。
> 结论先行见 §1；MineColonies 设计见 §2-§7；**合作与多镇模型（本次需求核心）见 §8**；Wandscape 现状见 §9；可借鉴/不该抄见 §10；代码索引见 §11。

---

## 1. 结论先行

Wandscape 的多人需求有**两个方向**，MineColonies 恰好各有一层对应：

1. **合作开发一座小镇**（多人共建）：需要「镇内层」——玩家在本镇的角色 + 逐动作权限。对应 MineColonies 的 `Action` 位标志 + `Rank` 模型。
2. **一个人参与管理多个小镇**：需要「归属 vs 成员」的分离——玩家与小镇是**多对多**关系，且每个 (玩家, 小镇) 对上有独立角色。

MineColonies 这两点**都支持**，但各有一处与 Wandscape 不同，必须留意：

- 它的**成员身份天然多对多**：`Permissions.addPlayer` 里**没有任何「一个人只能在几个殖民地」的检查**（已核对 `core/colony/permissions/Permissions.java:804`、`core/network/messages/PermissionsMessage.java:260`）。玩家可以被任意多个殖民地收进名单。
- 它只有**归属（owner）是一对一**：`CreateColonyMessage` 用 `getIColonyByOwner` 挡住「已有殖民地的人再建一个」。也就是说 MineColonies 的「一个人一个殖民地」限制的是**当 owner**，不是**当成员**。

→ **「一个人参与管理多个小镇」在 MineColonies 里是既有能力，不是要新造的东西**。Wandscape 要做的，是把它从「owner 一个 + 成员多个」推广到 **owner 也可多个**（或干脆不设 owner 独占）。

**Wandscape 现状的最大差距**：`ColonyOwnership.ownColony(player)` 只认「按 founder 绑定的那唯一一座」，`isOwn` 是等值比较（`content/colony/ownership/ColonyOwnership.java`）。这是**唯一归属判定入口**，也是模型改动唯一需要动的地方——领地保护（`ColonyLandProtectionHandler`）已经全部经由它，改动面是收敛的。详见 §8.3。

**仍然成立的一条红线**：MineColonies 把「未登记玩家」默认当 `NEUTRAL`（`Permissions.getRank(UUID)` 查不到就返回 neutral rank），即**外人有一档默认权限**。Wandscape 的平行隔离要求相反——**未登记 = 无权限**，成员身份必须**显式邀请**产生，绝不靠接近/途经自动授予。这条不能照搬，见 §10.2。

---

## 2. 权限数据模型（镇内层）

三个核心类型，全部在 `api/colony/permissions/`：

- **`Action`**（`Action.java`）：枚举，每个常量持有一个**位**（`0x1L << bit`）。共 31 个动作：`ACCESS_HUTS`、`PLACE_HUTS`、`BREAK_HUTS`、`EDIT_PERMISSIONS`、`MANAGE_HUTS`、`PLACE_BLOCKS`、`BREAK_BLOCKS`、`OPEN_CONTAINER`、`RIGHTCLICK_BLOCK`、`ATTACK_CITIZEN`、`TELEPORT_TO_COLONY`、`EXPLODE`、`RALLY_GUARDS`、`HURT_CITIZEN`、`MAP_BORDER`、`ACCESS_TOGGLEABLES` 等。
  - 注释明确警告：新增 action 若要「默认不对所有人开放」，必须同步 bump `permissionsVersion` 并写升级逻辑（对应 Wandscape 硬规则 §7 的「带版本号迁移」）。
- **`Rank`**（`Rank.java`）：`{id, name, isInitial, isColonyManager, isHostile, long permissionData}`。`addPermission` / `removePermission` 就是 set/clear 某一位；`isColonyManager` / `isHostile` 是两个**正交的角色标志**（不是权限位），分别决定「能管人」与「能打人/被打」。
- **`ColonyPlayer`**（`ColonyPlayer.java`）：`{UUID, name, Rank}`。玩家→rank 的映射是 `Map<UUID, ColonyPlayer>`，**存在每个殖民地内部**——这正是「多对多」的落点：同一玩家在不同殖民地的 map 里各有一条记录，互不影响。

**内置 rank**（`IPermissions.java`）固定 id：`OWNER=0, OFFICER=1, FRIEND=2, NEUTRAL=3, HOSTILE=4`；自定义 rank 从 5 起。默认权限集是**包含关系**：OWNER ⊃ OFFICER ⊃ FRIEND ⊃ NEUTRAL，HOSTILE 单独一套（能攻击）。

**关键默认值**：未登记的玩家 = `NEUTRAL`（`Permissions.getRank(UUID)` 查不到就返回 neutral rank）。NEUTRAL 恒不可 `EDIT_PERMISSIONS` / `TELEPORT_TO_COLONY`（`hasPermission` 里硬编码短路，即使位被翻转也无效）。

**owner 存在哪**：不在 `Colony` 上，在 `Permissions` 里（`ownerName` / `ownerUUID` / `fullyAbandoned`）。归属信息与殖民地本体分离，且**归属只是「一个 rank 为 OWNER 的名单条目」**——这是 Wandscape 值得对齐的抽象：owner 不是特殊字段，而是名单里角色最高的一员。

---

## 3. 权限怎么被执行（执行的四个入口）

MineColonies 把「谁能做什么」的判断全部收敛到一个方法：`Permissions.hasPermission(Player, Action)`（`core/colony/permissions/Permissions.java`）。判定逻辑：`testFlag(rank.permissions, action.flag)`；若 rank 是 NEUTRAL 且动作是 `EDIT_PERMISSIONS`/`TELEPORT_TO_COLONY` 直接 false；另有 `fullyAbandoned` 广开权限的兜底；最后有**创造模式 + OP 等级**的旁路（`OP_RANK` 全权限）。

四个会调用它的入口：

### 3.1 每殖民地一个 Forge 事件订阅者（主战场）
`ColonyPermissionEventHandler`——**每个 `Colony` 一个实例**，世界加载时注册到 `MinecraftForge.EVENT_BUS`，卸载时注销。覆盖几乎全部「物理世界」事件：

| 事件 | 检查的 Action |
|---|---|
| `EntityPlaceEvent` | `PLACE_HUTS`（建筑方块）/ `PLACE_BLOCKS` |
| `BreakEvent`（建筑） | `BREAK_HUTS` |
| `BreakEvent`（普通方块） | `BREAK_BLOCKS` |
| `RightClickBlock` | `ACCESS_HUTS` / `RIGHTCLICK_BLOCK` / `OPEN_CONTAINER` / 门→`ACCESS_TOGGLEABLES` |
| `EntityInteract` / `EntityInteractSpecific` | `RIGHTCLICK_ENTITY` |
| `ItemTossEvent` / `EntityItemPickupEvent` | `TOSS_ITEM` / `PICKUP_ITEM` |
| `FillBucketEvent` / `ArrowLooseEvent` | `FILL_BUCKET` / `SHOOT_ARROW` |
| `AttackEntityEvent` | `ATTACK_CITIZEN` / `ATTACK_ENTITY` |
| `ExplosionEvent` | `EXPLODE`（另有按配置过滤受爆方块） |

统一走 `checkEventCancelation`：`配置开启领地保护 && 坐标在该殖民地 && !hasPermission` → 取消事件（PVP 模式下合法入侵者例外，见 §7.4）。

**对 Wandscape 的映射**：Wandscape 已有等价的 `ColonyLandProtectionHandler`（监听 `BreakEvent`/`EntityPlaceEvent`/`RightClickBlock`/`ExplosionEvent.Detonate`），只是判定用 `ColonyOwnership.isOwn` 而非 rank 表。**监听点已经齐了，差别只在判定函数**——这是最省力的接入点，§8.3 展开。

### 3.2 全局静态订阅者
`EventHandler`（`core/event/EventHandler.java`）：建筑方块右键、手持建筑物品右键、床占用等。与 3.1 有重叠，属于历史分层。

### 3.3 网络包闸门
`AbstractColonyServerMessage` 是所有「客户端→服务端殖民地 GUI 操作包」的基类，默认要求 `MANAGE_HUTS`，子类可覆写 `permissionNeeded()` / `ownerOnly()`。伪代码：解析 colony → `hasPermission(player, permissionNeeded())` → 通过才 `onExecute`。
- 权限编辑专用包 `PermissionsMessage.Permission` 直接委托 `alterPermission`。
- **注意**：调研中发现 `AbstractColonyServerMessage` 的 `ownerOnly()` 分支疑似逻辑写反（判定条件里没有取反，导致 owner-only 包反而拒绝 owner）。这是**待核对上游**的疑点，不要当成正面样例照抄。

### 3.4 命令闸门
`IMCColonyOfficerCommand`：要求 OP 等级或 `rank.isColonyManager()`。`CommandAddOfficer` / `CommandSetRank` / `CommandChangeOwner` / `CommandSetAbandoned` / `CommandDeleteColony` 直接调模型方法。

**「谁能改谁的权限」规则**（`Permissions.canAlterPermission`）值得单独记：
1. 只有 owner 能改 Owner rank；
2. 改任意 rank 需 `EDIT_PERMISSIONS`；
3. **不能在自己的 rank 上关掉 `EDIT_PERMISSIONS` / `MANAGE_HUTS` / `ACCESS_HUTS`**（防止自锁门外）。

第 3 条是一个很实用的防呆规则，任何「玩家可自管权限」的系统都该有。

---

## 4. 领地边界与拒止反馈

### 4.1 领地是「区块认领」，不是「半径」
`Colony.isCoordInColony` 读的是**方块所在的 LevelChunk 上的 capability** `IColonyTagCapability.getOwningColony()`，返回 `NO_COLONY_ID` 就代表「不属于任何殖民地 → 不保护」。认领由建筑的 claim 半径驱动。
→ Wandscape 用的是「方块属于某建筑（pattern/交互区）才算该殖民地领地」（`getBuildingIdAt` / `getBuildingIdInInteractionZone`），**同为「只保护有主之物、不圈地」的哲学**，比 MineColonies 更精细。

### 4.2 拒止反馈（三档，值得抄）
`ColonyPermissionEventHandler.cancelEvent`：
1. **即时聊天**：`PERMISSION_DENIED`，**每人 10 秒限流**（`lastPlayerNotificationTick`），避免刷屏；
2. **镇务日志**：`new PermissionEvent(uuid, name, action, pos)` 塞进市政厅 `BuildingTownHall.permissionEvents`（有界环形），随建筑同步下发客户端，GUI 里可回看「谁被拒了几次」；
3. **主动反制**：10 秒窗口内被拒 > 10 次 → 给玩家挂 **10 秒漂浮（Levitation）** 作为反破坏惩罚；
   - `FakePlayer`（自动化/机械）静默拒绝，不聊天、不惩罚。

**Wandscape 现状**：`ColonyOwnership.deny()` = Action Bar + ScreenFeedback 包 + 村民拒绝音效 + `Log.warn`。**做到了第 1 档（而且比 MineColonies 更友好，有音效），缺第 2、3 档**。第 2 档（可回看的越权日志）在多人服务器上价值很高——而且它顺带解决了「有人想加入但没权限」的线索收集（见 §6）。第 3 档（主动反制）看定位决定，Wandscape 偏经营向，未必想要惩罚。

### 4.3 拒止发生在「哪一档」很重要
MineColonies 拒止既有服务端权威判定，也有**同一套规则的客户端镜像**（`PermissionsView` + GUI 里 `canAlterPermission` 决定按钮灰不灰）。客户端那份**只是 UX**，服务端永远重判。Wandscape 的 `ColonyOwnership` 目前也是服务端判定 + 客户端提示，方向一致。

---

## 5. 工具物品的设计（与 Wandscape 权杖最相关）

**先纠正一个常见误解**：MineColonies 这个版本**没有自己的 Build Tool / Scan Tool**——蓝图放置与选区工具是前置库 **Structurize** 提供的（`com.ldtteam.structurize.items.*`），MineColonies 只**消费**它们并加权限门（`ItemScanTool` → `Action.USE_SCAN_TOOL`）。

MineColonies 自己的管理类物品（`core/items/` + `api/items/ModItems.java`）里，和「管理」相关的有：

| 物品 | 作用 | 权限 Action | 状态存哪 |
|---|---|---|---|
| `ItemScepterPermission` | 增删「免交互」方块/位置 | `EDIT_PERMISSIONS` | NBT `scepterMode ∈ {modeBlock, modeLocation}` |
| `ItemScepterGuard` | 给卫兵塔指定巡逻点 | — | NBT `TAG_LAST_POS/TAG_ID/TAG_POS`；**完成后自毁** |
| `ItemScepterLumberjack` | 划伐木禁区 | — | NBT `start_pos/end_pos`；**左键设角 A、右键设角 B** |
| `ItemClipboard` | 绑定殖民地看板 | — | NBT `colony`（右键建筑绑定） |
| `ItemResourceScroll` | 建筑资源报告 | — | NBT + **workorder 哈希**，哈希不符则请服务端清缓存 |
| `ItemBannerRallyGuards` | 集结守卫 | `RALLY_GUARDS` | NBT 列表 + `isActive`；**active 时 `isFoil()` 发光** |
| `ItemColonySign` | 连两个殖民地 | `MANAGE_HUTS` | — |

提炼出的**物品状态设计套路**（与语义无关，直接可迁移）：

1. **多模式用 NBT 字符串 + 右键空气切换**（`ItemScepterPermission` 是最干净的模板）。Wandscape 的 `OmniScepterItem`（`mode` 键 + `ScepterKind`）已经同构，甚至更完整（shift+右键循环 + tint 着色）。
2. **左键/右键 = 两个角**（`ItemScepterLumberjack`）：需要「选两点」时的标准手法，配 `IBlockOverlayItem` 画红/绿框。
3. **右键建筑绑定，右键空气开 GUI**：Clipboard / 地图 / 任务日志 / 资源卷轴四件套共用同一模式，绑定值一律存 NBT 的小镇 id。
4. **绝不在客户端直接改世界/殖民地**：要么物品服务端专用（客户端返回 `FAIL`），要么客户端发包、服务端干活。**并且会在侧别用错时大声打日志**——这是很好的排障习惯。
5. **GUI 改手持物品的属性要绕服务端**（`ItemSettingMessage`：校验物品类 → 写 NBT → `broadcastChanges`）。
6. **权限挂在「动作」上，不挂在「物品」上**：所有物品都复用通用 `Action` 位（`EDIT_PERMISSIONS` / `MANAGE_HUTS` / `RALLY_GUARDS` / `PLACE_BLOCKS`），没有一件物品有专属权限位。
7. **缓存显示数据进 NBT 让 tooltip 离线也能渲染**（`cached_colony_name`）；**选区类状态加超时**（`ItemScanAnalyzer` 2 分钟过期）防脏选择。
8. **状态可见**：`isFoil()` 直接返回 `isActive()`，一眼看出工具开没开；`onDroppedByPlayer` 里顺手关掉。

**与 Wandscape 权杖的对照**：Wandscape 是「一根万能权杖四模式」（`OmniScepterItem`），MineColonies 是「许多小工具各管一件事」。Wandscape 的路子本身没问题（`SOUL`：按最佳实践而非枚举），**关键差别是 MineColonies 每个工具都有一句「权威判定在服务端」的纪律**，且会在侧别用错时留痕——这点 Wandscape 值得对齐。

---

## 6. 客户端↔服务端同步（镇内层的通信）

模式：**推送式订阅**，没有客户端主动拉取。

- **订阅**：玩家进入被认领的区块时（`EventHandler.onEnteringChunk`，每 100 tick 检查一次），自动 `addCloseSubscriber`。官员/线上成员额外 `addImportantColonyPlayer`。**进区块 ≠ 入籍**，只是拿到同步。
  - 一个值得注意的怪异行为：`Permissions.addPlayer` 里，**非官员的新成员只有当「加入那一刻本人正站在该殖民地已认领区块里」才会被登记为 close subscriber**（`Permissions.java:832-838`）。即「站在别人镇里被批准入伙」才拿得到持续同步——**成员身份没有可靠地驱动同步**，这是个坑，Wandscape 别学。
- **推送**：`ColonyPackageManager.updateSubscribers` → 有 dirty 或新订阅者就发 `PermissionsMessage.View`（含**整个权限模型**：所有 rank 位掩码 + 玩家列表）。每个收件人按**自己的 rank** 拿到个性化的一份（`getRank(p)` 决定下发内容）。
- **客户端模型**：`PermissionsView implements IPermissions`。**所有写方法在客户端是本地乐观改 + 空操作**（`addPlayer` 返回 false），GUI 手感靠本地 rank map 变更支撑；真正的权威改动由服务器随后推回。
- **权限编辑包**（`PermissionsMessage.java`）：`Permission`（翻转某 rank 的某 action）、`AddPlayer`（按名字加，落到 NEUTRAL）、`AddPlayerOrFakePlayer`（从「被拒记录」里批准）、`ChangePlayerRank`（升降级）、`RemovePlayer`（含**自我移除 = 退镇**）、`AddRank`/`RemoveRank`/`EditRankType`。**服务端全部重判** `EDIT_PERMISSIONS`。
- **没有独立的 invite/accept 包**：「邀请」= 官员按名字加人；「接受」= 从被拒事件列表里批准（这条挺巧——**把「越权尝试」变成了「入籍申请线索」**）。

**Wandscape 现状对照**：Wandscape 已有 `ScreenFeedbackPacket`、各种 `*SyncPacket`（`ColonyStatsSyncPacket`、`ColonyAmbientPacket`）、`WandscapePanelState` 等同步设施，**但殖民地归属/权限没有下发到客户端**（客户端 `getColonyByFounder` 恒 null，`getColonyLevel` 恒 0，见记忆）。MineColonies 的「服务端权威 + 推送订阅 + 客户端只读镜像」是 Wandscape 要补同步时最值得对齐的骨架；但**同步范围要收窄**——只推「我参与的小镇 + 我在那里的角色」，不是全量（见 §10.2）。

---

## 7. 镇际外交（第二层）

这一层独立于 rank 系统。核心是 `ColonyConnectionManager`（每殖民地一个）+ `api/colony/connections/`。

### 7.1 模型
- `DiplomacyStatus { ALLIES, NEUTRAL, HOSTILE }`；
- `ConnectionEventType { ALLY_REQUEST, ALLY_CONFIRMED, FEUD_STARTED, NEUTRAL_SET, DISCONNECTED }`；
- `ColonyConnection { id, name, gatePos, diplomacyStatus }`（成对 record，可 NBT/网络序列化）；
- 管理器 API：`getDirectlyConnectedColonies()` / `getIndirectlyConnectedColonies()` / `attemptEstablishConnection()` / `triggerConnectionEvent()` / `getColonyDiplomacyStatus(int)`。

### 7.2 建交必须「修路」
连接**不是**点一下按钮，而是**实体建筑**：`ItemColonySign` / `BuildingGateHouse` 触发 `attemptEstablishConnection`，管理器要**寻路验证**（`PathJobSignConnection`）指示牌路径能连通**两个殖民地各自的门楼**，才算连上，初始 `NEUTRAL`。连接是**双向**的（两边管理器互写）。

**这是一个很有启示的做法**：把「外交关系」绑定到玩家实际建造的基础设施（路 + 门楼），而不是纯 UI 操作。Wandscape 已有道路系统，这个思路天然契合——**道路连通 = 建立关系**，几乎不需要新机制。

### 7.3 状态变更与传递
`triggerConnectionEvent`：`ALLY_CONFIRMED→ALLIES`、`FEUD_STARTED→HOSTILE`、`NEUTRAL_SET→NEUTRAL`，**两边管理器都写**。
**盟友关系会传递**：盟友的直连对象被拉进 `indirectlyConnectedColoniesCache`（即「盟友的盟友」也可见，默认 NEUTRAL）。

### 7.4 盟友关系实际给了什么
**很少**——只确认到两个效果：
1. **可传送到盟友殖民地**（`TeleportToColonyMessage` 只允许 `ALLIES`；门楼传送按钮只在 `ALLIES` 时可用）；
2. 别的一概没有：**不共享资源、卫兵、市民、建筑、战斗，也没有协同经济或联合作战**。

另外还有一层**独立的 PvP**（不是镇际外交）：`Colony.isValidAttackingPlayer` / `AttackingPlayer`（`core/colony/pvp/`），由配置 `pvp_mode` 控制。规则是「两殖民地互相把对方 owner 标为 hostile 时，入侵才合法」，且入侵方必须有卫兵随行。**注意区分**：这层里的 `HOSTILE` 是「玩家在某个殖民地里的 rank」，与 `DiplomacyStatus.HOSTILE`（殖民地之间的关系）**不是一个东西**。

### 7.5 结论
MineColonies 的镇际关系**形式大于内容**：有完整的状态机与 UI，但实际玩法收益只有一个传送。对 Wandscape 的启示是**结构**（关系状态 + 双向写 + 传递 + 绑定实体建筑），不是**收益设计**——Wandscape 若做镇际交互，应当想清楚「盟友之间到底能共享什么」，那是 MineColonies 没回答的问题。

---

## 8. 合作与多镇：归属 vs 成员模型（本次需求核心）

这是把「多人共建一座小镇」与「一人参与多座小镇」统一起来的关键抽象。

### 8.1 一个矩阵：玩家 × 小镇 = 角色

把关系画成矩阵，MineColonies 与 Wandscape 目标的差别一目了然：

| | 同一座小镇内多个玩家 | 同一玩家多座小镇 |
|---|---|---|
| **MineColonies** | 支持：名单里多条记录，各带 rank | **部分支持**：成员可多座，但**只能 owner 一座** |
| **Wandscape 目标** | 需要 | 需要（含 owner 级参与） |

MineColonies 的落点是 `Map<UUID, ColonyPlayer>`（每殖民地一份）——**每个格子存一个 rank**。它对「行方向」（一人多镇）没有限制，对「列方向」（一镇多人）也没有限制；**唯一的一对一约束是 owner 那一格**（`getIColonyByOwner` 只返回第一个匹配，且建制时挡重复）。

→ **Wandscape 要的模型就是这张矩阵本身**：一个 `(玩家, 小镇) -> 角色` 的多对多关系。MineColonies 已经证明了它的可行性；Wandscape 只需要把「owner 独占」的约束拿掉（或改为「一个玩家可以在多座小镇里当 owner」）。

### 8.2 需要的数据形状（两个方向都要能查）

矩阵有两个访问方向，缺一不可：

- **小镇 → 成员名单**：`colony.roster: Map<UUID, Member{name, role}>`。用于「这镇里谁能干什么」——每次权限判定都要查它（`hasPermission(player, colony, action)`）。
- **玩家 → 参与的小镇列表**：`player -> Set<colonyId>`（可由名单反查，或维护反向索引）。用于「这个玩家现在能操作哪些镇」——建镇引导态判定、客户端同步「我的小镇」列表、传唤面板等。

MineColonies 只显式维护了第一个方向（殖民地内的名单），第二个方向靠**遍历所有殖民地**反查（`getColonyByOwner` 就是线性扫）。殖民地一多这会是 O(N) 查询——Wandscape 若追求「一人多镇」，建议**直接维护反向索引**，别走遍历。

### 8.3 Wandscape 的收敛点：`ColonyOwnership` 就是那个唯一入口

现状（`content/colony/ownership/ColonyOwnership.java`）：

```java
public static UUID ownColony(ServerPlayer player) {   // 只认「按 founder 绑定的那一座」
    return api != null ? api.getColonyByFounder(player.getUUID()) : null;
}
public static boolean isOwn(UUID colonyId, ServerPlayer player) {   // 等值比较
    ...
    return own != null && own.equals(colonyId);
}
```

这个类本身就是「完全平行隔离的唯一归属判定入口」——它的类注释写着铁律：「一个玩家的一切 Wandscape 上下文只能是他自己创建的小镇」。**模型改动只需要动这一个类**（对应硬规则「一个概念收敛进唯一命名类」）：

- `ownColony(player)` → 语义从「那唯一一座」变为「玩家参与的小镇集合」；
- `isOwn(colonyId, player)` → 语义从「等值」变为「该玩家在这座小镇是否持有**所需角色/权限**」。

**因此 `isOwn` 的签名需要补一个「要做什么」的参数**（哪个动作/什么角色），判定才能从「是不是我的镇」升级为「我在这镇有没有这个权限」。这正好对应 MineColonies 的 `hasPermission(player, action)`——即 `ColonyOwnership` 从**归属比较器**长成**权限判定器**。

**好消息**：`ColonyLandProtectionHandler` 的每个保护点已经全部经 `isOwn`（`protect()` 里一次调用），所以「给 `isOwn` 加一个 action 参数」会沿着既有调用链自然铺开，**没有散落的第二套校验**。

### 8.4 角色集：从 MineColonies 的 rank 映射到 Wandscape

MineColonies 的五个内置 rank 里，Wandscape 需要的映射：

| MineColonies rank | Wandscape 是否用 | 说明 |
|---|---|---|
| `OWNER` | 用（可多个） | 小镇创始人 / 最高管理者 |
| `OFFICER` | 用 | 可管理建筑/成员（`isColonyManager` 语义） |
| `FRIEND` | 用 | 可建造/使用，不可管人 |
| `NEUTRAL` | **弃用** | 平行隔离下「未登记」必须=无权限，不能有一档默认权限 |
| `HOSTILE` | **不在此处** | Wandscape 的敌意由 `FriendlyForce` / 权杖敌对标记表达，别在角色表里重复一个 `isHostile` 标志 |

**逐动作权限位**（`Action` 位掩码）是这套模型的可扩展性来源。Wandscape 的动作集大致是：建造/拆除建筑、改建筑配置、招/解雇佣法师、仓库存取、改商店库存、领地放置/破坏、开容器、使用权杖标记……每个动作一个位，角色是一包位。这样「加一个新权限」= 加一个枚举常量 + 一格迁移，不需要给每个角色加字段（呼应硬规则 §7：带版本号迁移或断档）。

**要保留的两条 MineColonies 规则**：
1. **防自锁**（`canAlterPermission` 第 3 条）：不能把自己的 `管理成员` 权限关掉。
2. **owner 不可被非 owner 移除/降级**（`ChangePlayerRank` / `RemovePlayer` 里都挡 `rank == getRankOwner()`）。

**要避开的**：别照抄 `isColonyManager` + `isHostile` 两个正交布尔标志的写法——语义会和逐动作位重叠（MineColonies 自己就在 `Action` 里同时有 `MANAGE_HUTS` 位和 `isColonyManager` 标志，边界含糊）。Wandscape 用「角色 + 位掩码」一套即可。

### 8.5 对现有「平行隔离」铁律的修订

现铁律：「一个玩家在服务器上的一切 Wandscape 上下文只能是他自己创建的小镇；没有小镇 = 建镇引导态」。

要支持多镇参与，必须**精确地放松一处、守住两处**：

- **放松**：上下文从「自己创建的那一座」扩到「自己参与的所有小镇」。
- **守住 1（显式加入）**：成员身份**只能由邀请产生**，绝不因接近/途经/最近的镇而授予。MineColonies 的 `NEUTRAL` 默认档 + 「进区块即订阅」在这里都是反例。Wandscape 应保持：**没被邀请 = 什么都看不到、什么都动不了**。
- **守住 2（不回退最近小镇）**：`ownColony` 的注释明确禁止回退到「空间最近小镇」，说是「跨镇泄密的根因」——这条在多镇模型下**更**要守：玩家可能同时站在好几个镇附近，任何基于距离的归属推断都会串镇。

### 8.6 连锁影响（改动会波及的地方）

- **`FriendlyForce`**：玩家侧友军判定（PVP 开启时「仅同殖民地」）要从「同一座小镇」变成「**是否共享任一参与的小镇**」（两玩家若同属某镇，则该镇内互不侵犯）。NPC 侧仍按 colonyId 等值，不受影响。需要确认 `WandscapeNpc.classify` 里「玩家 → 殖民地」的解析是否也假定唯一——若假定唯一，同样要改成集合。（**待核对**，见 §10.3。）
- **客户端同步**：要为每个玩家下发「我参与的小镇 + 我在各镇的角色」，这样面板/UI 才能列出多个小镇。这是当前完全缺失的一环（客户端恒 null）。
- **权杖**：`ScepterMarks` / `ScepterApi` **本来就按 colonyId 存**（`isSheltered(colonyId, …)`、`forcedHostile(level, colonyId)`），天然适配多镇——权杖的使用权限应挂在「对该 colonyId 的某个动作位」上，这正好是 §8.3 加的 action 参数要判的东西。
- **领地保护**：`ColonyLandProtectionHandler` 全部经 `isOwn` → 随签名升级自动覆盖，无需另改。

---

## 9. Wandscape 现状（对照基线）

摘自现有代码，作为「改动前」快照：

| 维度 | Wandscape 现状 | MineColonies 对应 |
|---|---|---|
| 归属模型 | **一玩家一小镇**，按 founder 绑定（`ColonyOwnership.ownColony`）；**绝不回退空间最近小镇**；多人共管的支持缺失 | 成员**多对多**，owner **一对一**（建制时挡） |
| 判定入口 | `ColonyOwnership.isOwn(colonyId, player)`（OP 旁路）——**无动作/角色维度** | `Permissions.hasPermission(player, action)`（rank 位掩码） |
| 角色 / 权限 | **无**（只有「是不是 owner」） | OWNER/OFFICER/FRIEND/NEUTRAL/HOSTILE + 自定义 rank + 逐动作位 |
| 领地保护 | `ColonyLandProtectionHandler`：只保护「属于某建筑」的方块 | 区块认领 + 每殖民地事件处理器 |
| 拒止反馈 | Action Bar + ScreenFeedback + 音效 + 日志（**1 档**） | 聊天限流 + 镇务日志 + 漂浮惩罚（**3 档**） |
| 友军判定 | `FriendlyForce`：**派生**（同 colonyId 的 NPC + 玩家 + 召唤物…），PVP 开启时收紧为同镇 | `Permissions` 里的 rank（`isHostile` 等） |
| 权杖 | `OmniScepterItem` 四模式（和平/跟随/庇护/敌对），标记存 `ScepterMarksSavedData`（**按殖民地**） | 多件小工具，权限挂 `Action` |
| 镇际关系 | **无**（`FriendlyForce` 只管同镇） | `ColonyConnectionManager` 外交 |
| 客户端同步 | 殖民地归属/等级**不下发**（客户端恒 null/0） | 推送式订阅，个性化下发 |

**核心差距一句话**：MineColonies 已经有「玩家 × 小镇 = 角色」的矩阵；Wandscape 目前只有「一个玩家 → 一座小镇」的**函数**。要补的是**把函数升级为矩阵**，落点就是 `ColonyOwnership`（§8.3）。权杖已按 colonyId 存储（§8.6），是最贴合这套模型的既有资产。

---

## 10. 可借鉴 / 不该照搬 / 待澄清

### 10.1 值得借鉴（与语义无关的工程做法）
1. **权限 = 位掩码 + 通用 Action 枚举**，而非每种操作写一个布尔字段。可扩展、易序列化、易做 GUI 开关网格。
2. **玩家与小镇是多对多、角色存在「小镇内的名单」里**（每格一个 rank）——这就是「一人多镇 + 多人一镇」的通用形状。
3. **防自锁规则**：不允许在自己的角色上关掉「管成员 / 管建筑 / 进建筑」三项。
4. **owner 只是名单里角色最高的一员**，不是特殊字段——便于「多个 owner」与转让。
5. **拒止反馈的镇务日志**（第 2 档）：可回看的越权记录；顺带把「被拒尝试」变成「入籍申请线索」。
6. **工具状态纪律**：权威判定恒在服务端；物品 NBT 只存指向数据；侧别用错要留日志；选区类状态加超时。
7. **关系绑定实体建筑**：修路/建门楼才能建交——Wandscape 有道路系统，天然适配（镇际层）。
8. **关系状态双向写 + 可传递**（盟友的盟友可见）。
9. **服务端权威 + 推送订阅 + 客户端只读镜像**，GUI 本地乐观改只是 UX；但同步范围要收窄到「我参与的镇」。

### 10.2 明确不该照搬
1. **不要 `NEUTRAL` 默认档**：MineColonies 把未登记玩家当 neutral（有默认权限）、进区块即订阅。Wandscape 的隔离要求「未登记 = 无权限」，成员身份必须**显式邀请**产生。
2. **不要靠接近/途经授予任何东西**：连 MineColonies 自己都在 `ownColony` 注释里点名「空间最近小镇 = 跨镇泄密的根因」。多镇模型下更要守住。
3. **不要同步全量权限模型给所有人**：只推「我参与的镇 + 我的角色」。
4. **不要照抄 `isColonyManager` + `isHostile` 双正交标志**：与逐动作位语义重叠，边界含糊。
5. **不要照抄 `AbstractColonyServerMessage.ownerOnly()` 的写法**（疑似逻辑反了，见 §3.3）。
6. **不要照抄它的 manager 数量**：一个 `Colony` 挂十几个 manager、接口散落三处。Wandscape 硬规则是「一个概念收敛进一个命名类」，应保持更少类型。
7. **别为「关系」而关系**：MineColonies 镇际盟友实际只换来一个传送（§7.4）。做镇际前先定义「盟友能共享什么」。
8. **别学「非官员新成员只在本人站进镇里时才订阅」**（§6 的坑）：成员身份应可靠地驱动同步。

### 10.3 待澄清（做之前必须先定）
- **角色划分**：需要几档？（如 owner / 管理 / 成员 / 访客）还是照 MineColonies 五档？
- **动作粒度**：逐动作权限位要覆盖哪些 Wadscape 操作（建造、招法师、仓库、商店、权杖…）？粒度太细会让 GUI 爆炸。
- **邀请/加入流程**：谁能邀请？被邀请方要不要「接受」？还是官员单向加人即可？
- **owner 语义**：一个玩家能在多座小镇当 owner 吗？owner 能否转让 / 一座镇能否多 owner？
- **客户端要显示什么**：一个人的多个小镇如何列出与切换？「当前操作的小镇」是显式选择还是按位置/面板上下文？
- **与权杖的关系**：权杖标记归属谁的权限？（敌人/盟友标记是「镇对所有者的指令」，其使用应挂在对该 colonyId 的哪个动作位上？）
- **`WandscapeNpc.classify` 对「玩家 → 殖民地」的解析是否假定唯一**（§8.6 待核对）——若假定唯一，多镇下要改成集合判定。

---

## 11. 关键代码索引

### MineColonies（`_refs/MineColonies/src/main/java/com/minecolonies/`）
| 关注点 | 文件 |
|---|---|
| 权限模型 | `api/colony/permissions/{Action,Rank,IPermissions,ColonyPlayer,PermissionEvent}.java` |
| 权限实现 | `core/colony/permissions/Permissions.java`（`hasPermission` / `alterPermission` / `canAlterPermission` / `addPlayer` / owner / abandoned） |
| 世界事件执行 | `core/colony/permissions/ColonyPermissionEventHandler.java`、`core/event/EventHandler.java` |
| 领地判定 | `core/colony/Colony.java`（`isCoordInColony`）、`api/util/ColonyUtils.java`（`getOwningColony`） |
| 权限 GUI | `core/client/gui/townhall/WindowPermissionsPage.java` |
| 权限网络包 | `core/network/messages/PermissionsMessage.java`、`server/AbstractColonyServerMessage.java` |
| 同步 | `core/colony/managers/ColonyPackageManager.java`、`core/colony/permissions/PermissionsView.java` |
| 殖民地注册/生命周期 | `core/colony/{ColonyManager,ColonyList,Colony}.java`、`core/network/messages/server/CreateColonyMessage.java` |
| **镇际外交** | `core/colony/managers/ColonyConnectionManager.java`、`api/colony/connections/*`、`core/client/gui/townhall/WindowAlliancePage.java` |
| PvP（独立于外交） | `core/colony/pvp/AttackingPlayer.java` |
| 工具物品 | `core/items/ItemScepter*.java`、`ItemClipboard.java`、`ItemResourceScroll.java`、`ItemBannerRallyGuards.java`、`api/items/ModItems.java` |
| 市民对话系统（另一套） | `api/colony/interactionhandling/*`、`core/colony/interactionhandling/*` |

### Wandscape（`src/main/java/com/wsteam/wandscape/`）
| 关注点 | 文件 |
|---|---|
| **归属判定（唯一入口，模型改动落点）** | `content/colony/ownership/ColonyOwnership.java` |
| 领地保护（全部经 isOwn） | `content/colony/guard/ColonyLandProtectionHandler.java` |
| 殖民地 API / 实现 | `api/ColonyApi.java`、`content/colony/ColonyApiImpl.java` |
| 友军判定（PVP 下按同镇） | `content/npc/types/FriendlyForce.java`、`api/FriendlyForceApi.java` |
| 权杖（标记按 colonyId） | `content/items/scepter/{ScepterKind,ScepterItem,OmniScepterItem}.java`、`internal/{ScepterService,ScepterMarks,ScepterMarksSavedData}.java`、`api/ScepterApi.java` |
| 殖民地设置 | `content/colony/settings/ColonySettings.java` |
| 多人隔离进展 | 分支 `multiplayer-isolation`（见 `docs/` 相关记录） |
