# 多人权限系统（多殖民地）

> 信息截至 2026-10-05 | Minecraft NeoForge 1.21.1
> 本文是**功能与设计说明**，不是考察报告。旧版为 MineColonies 设计拆解（对照系与反面教材），已随本系统落地被本文取代。

- **【何时读】**：改动殖民地归属 / 成员 / 权限判定、新增任何「服务端包操作殖民地」的入口、改 `ColonySavedData` 结构、动小镇面板或切换逻辑之前。
- **【一句话】**：玩家与小镇是**多对多**关系，每个 `(玩家, 小镇)` 格子上放一个**档位**；玩家的「当前操作的小镇」是一件**可切换、跨重连持久化**的独立状态，一切「包里不带镇 id」的解析都问它。
- **【不包含什么】**：玩家可见的指南（走游戏内 Guidebook）；`ColonySavedData` 的通用落盘纪律（见 [data-formats.md](data-formats.md)）；MineColonies 对照（已删，git 历史里有）。

---

## 一、核心模型

### 1.1 三个不再成立的旧等式

改造前（单殖民地时代）有三个隐含假设，现在**全部作废**，读代码时别再按它们推理：

| 旧假设 | 为什么不成立 |
|---|---|
| 「我在哪座镇」=「我创始的那座」 | 一人可拥有多座镇，也可只是别人镇的成员 → 当前镇是**独立可切换状态** |
| 「按玩家反查他创始的镇」可靠 | 单人可拥有多座，反查**必然歧义**（旧实现是首次匹配的线性扫描）→ 该 API 已从 `ColonyApi` **删除** |
| 「每个玩家只能有一座镇」 | 与「每座镇恒有一个 OWNER」是两件事；前者已放开，后者保留 |

### 1.2 关系矩阵

```
            小镇 A        小镇 B        小镇 C
玩家甲      OWNER         MEMBER        —（非成员）
玩家乙      MANAGER       —             ALLY
```

- **每座镇恒有一个 OWNER**（花名册里 `ColonyRole.OWNER`，不变量由 `ColonySavedData.migrate` 守护）。
- **一人可拥有多座镇**、也可参与多座镇；不参与 = 非成员（`null`，无任何权限）。
- 一格 = 一个档位，存在**该镇自己的花名册**里：`colonyId → (playerUuid → ColonyRole)`。

### 1.3 四档能力（唯一权威表）

| 档位 | 能做什么 | 关键限制 |
|---|---|---|
| **OWNER** | 全部操作权限 + **改小镇设置**（镇名 / 起名风格 / 游客开关）+ **调他人档位** + 转让 | **每镇唯一**；可转让，转让后前任降 MANAGER；不可被移出/降级 |
| **MANAGER** | 建造 / 拆除 / 复原 / 撤销建筑、法师招募解雇改策略装备跟随、任务调度与取消、领地破坏与放置、权杖指令、祭坛施法、商店库存、解锁配方、撤路与道路交互 | **不能**调档位、**不能**改设置 |
| **MEMBER** | 仓库存取（含便携仓库终端）、工坊 / 节点下单 | 不碰建筑、不碰法师管理、不能调档位 |
| **ALLY** | **仅**友军白名单（不被该镇法师攻击） | **零操作权限**；**不可切换当前镇**（切了也是白看） |
| 非成员 | **无任何权限** | 含商店消费、旅店入住（游客经济只对 NPC 开放） |

> 「能管」与「归属」是两个轴：MANAGER 能操作镇内法师，但**不是法师的 Owner**（法师归属是 `WandscapeNpc.colonyId`，属于镇，不随管理人变）。

---

## 二、当前操作的小镇（系统的心脏）

### 2.1 唯一真源

`content/colony/ActiveColonyTracker` 是「我在哪座镇」的**唯一真源**：

```java
@Nullable UUID activeColony(ServerPlayer)          // 无则 null（= 建镇引导态）
Map<UUID, ColonyRole> switchable(ServerPlayer)     // 可切换 = 档位 >= MEMBER
boolean setActive(ServerPlayer, @Nullable UUID)    // 校验 + 持久化；拒时不动已存值
@Nullable UUID resolveDefault(ServerPlayer)        // 默认解析（不落盘）
```

各域调的是 `ColonyOwnership.activeColony(player)`（同一层封装）。**不要绕过它自备副本**，也不要按位置猜。

### 2.2 规则（别改，都是裁定过的）

- **可切换 = 档位 ≥ `MEMBER`**（`ActiveColonyTracker.MIN_SWITCH_ROLE`）。**ALLY 不可切换**。
- 默认解析顺序：**已存且仍可切 → 自己拥有的镇 → 档位最高者 → null**。
- **跨重连持久化**：选过的镇记进存档（`ColonySavedData` v3 的 `activeColonies`）。失效记录（被降级 / 被移出 / 镇被删）在解析时自动清理。
- **绝不做空间推断**：不回退「最近小镇」。位置推断是跨镇观察/串镇的根因，历史事故就是它。

### 2.3 切换怎么发生

```
面板点某一行（非 ALLY）
  → 客户端只发 SELECT（不再本地改选中，见 §6）
  → 服务端 ActiveColonyTracker.setActive（校验 MEMBER+，拒则反馈且不动已存值）
  → ColonyContextSync.push(player)  ← 统一推送入口，一次推齐
       ColonyStatsSyncPacket（当前镇唯一真源，必须最先）
       BuildingAreaSyncPacket（边界）
       tutorial
       ColonyRosterSyncPacket（花名册）
       ColonyListSyncPacket（我的镇列表）
  → 客户端 WandscapePanelState.colonyId 更新 → 顶栏 / 面板高亮 / 子面板一起变
```

**接受邀请后自动切到该镇**（**不传送**：整条路径没有任何位置/传送调用）；**建镇成功后自动切到新镇**。两处都只差 `setActive` + `push`，别只 push 不 setActive（否则顶栏停在旧镇）。

---

## 三、权限判定：唯一入口

```java
ColonyOwnership.hasRole(player, colonyId, MIN)   // 「我在这座镇能干什么」——所有权限判定用它
ColonyOwnership.role(player, colonyId)           // 档位本体（非成员 = null）
ColonyOwnership.activeColony(player)             // 「我在哪座镇」——上下文解析用它
ColonyOwnership.isOwn(colonyId, player)          // 只是「这是不是我当前那座镇」的是非题，**不是权限**
```

铁律：

1. **权限一律按档位判**，不给 OP 静默的全局旁路。`isOwn` 的旧注释曾声称「OP 旁路」，但**代码里从来没有这个分支**——那是文档撒谎，已改成「无 OP 旁路」。管理员干预走 `/wandscape colony ...`（op-2 门控的命令面）。
2. **`colonyId == null` 视为放行**（未归属市政厅 → 建镇引导依赖它）；非殖民地 NPC（教学等）同样放行。这两条是**故意**的，收紧会打断建镇与教学。
3. **防自锁**：OWNER 不能把自己降级 / 移出。
4. `colonyId` 来自**目标物件自己的归属**（`BuildingState.colonyId` / `npc.colonyId` / 任务记录），**不要**用 `getColonyId(BlockPos)` 那类 ≤256 空间最近查询去判权限。

### 3.1 建筑 GUI 的咽喉

`content/building/internal/BuildingInteractHandler` 是**所有建筑 GUI 的唯一分发点**，它按 `requiredRole(category, state)` **按意图分流**（不是一刀切）：

| 分类 | 档位 |
|---|---|
| storage / workstation / crafting_station / magic_station / node / shop / 工地面板 | MEMBER |
| tavern / mage_hut / altar | MANAGER |
| government / service / relax / decoration / atm | ALLY（纯信息面板） |

---

## 四、网络层：统一网关（机制已就位，覆盖在迁移中）

**机制**：包实现 `foundation/networking/ColonyScopedPayload`，声明自己的作用域；`PayloadRegistry.c2s(...)` 用 `instanceof` 在进 handler 前统一接管，判定走 `ColonyScope.admit`（内部调 `hasRole`），不够则 `ColonyOwnership.deny` 反馈并直接 return。

- **登记表零改动**：不认识该接口的包行为完全不变。
- **失败方向是「放行」**：作用域缺失/不齐时 `admit` 返回 true，把权威判定留给 handler，避免网关自己不确定就误拒。
- ⚠️ **当前覆盖率 = 1 个包**：只有 `ColonyMemberActionPacket` 声明了作用域（`INVITE`→MANAGER、`SET_ROLE`/`REMOVE`→OWNER、`SELECT`→ALLY、`ACCEPT`/`DECLINE`→不过网关）。**其余约 30 个操作包仍在包内自查 `hasRole`**（§3 的表）。要接网关时，**包内自查与作用域声明必须同档位**，别出现「包里 MANAGER、网关 ALLY」的错位。

---

## 五、邀请与成员管理

- **入口只有一个**：侧边栏「小镇」面板（`SubMode.COLONY`，数字键 3，位置在道路之下、任务大厅之上）。**不做市政厅分页**。
- 面板内可完成：切换当前镇、看成员、**发起邀请 / 接受 / 拒绝**、调档位、移除成员、新建小镇引导。
- **邀请只在内存**（`ColonyInviteRegistry`），被邀方必须在线；`ServerStoppedEvent` 清空。**本阶段不支持离线邀请**（那需要过期语义）。
- 服务端权威重判，客户端只发意图：

| 动作 | 规则 |
|---|---|
| `INVITE` | 发起者 ≥ MANAGER；目标须在线；不得邀已在花名册的人；**只能授予严格低于自己档位的档位**；**任何人不得授予 OWNER** |
| `ACCEPT` | 只有被邀方本人；**只认服务端存的那条邀请**——包体里的 `colonyId` / `role` **一律不采信**（否则篡改客户端即可用 OWNER 入伙）；成功后**自动切换**到该镇 |
| `DECLINE` | 丢弃该邀请 |
| `SET_ROLE` | 仅 OWNER；不得设成 OWNER（转让走 `ColonyApi.transferOwner`）；不得动 OWNER |
| `REMOVE` | 仅 OWNER；OWNER 不可被移出；**不能移除/降级自己** |

---

## 六、客户端同步与「单一真源」

| 关注点 | 唯一真源 | 说明 |
|---|---|---|
| **当前是哪座镇** | `WandscapePanelState.colonyId`，**只由 `ColonyStatsSyncPacket` 写入** | 顶栏、面板高亮、设置页、子面板全读它。**少发 stats 包 = 客户端不知道该显示哪座镇** |
| 我的镇列表 | `ColonyListSyncPacket` → `ColonyPanelClientState` | 只带 `myRole`，**不带「是否当前镇」**（加了就会造出第二个真源） |
| 各镇花名册 | `ColonyRosterSyncPacket` → `ColonyPanelClientState` | 纯 `colonyId → 花名册` **缓存**，`roleOf/nameOf/membersOf` |
| 统一切换后推送 | `content/colony/network/ColonyContextSync.push(ServerPlayer)` | 切换 / 接受邀请 / 建镇后的**唯一入口**；无当前镇时发空包清缓存 |

**为什么这样切**：面板点击**不再本地改选中**，一律等服务端推回来。所以「切了但顶栏不变」在结构上不可能发生——**没有第二份状态可以漂移**。`ColonyPanelClientState` 里**刻意不保存**「当前是哪座镇」。

---

## 七、存档格式（`ColonySavedData`）

顶层 `version` 走**显式迁移链**，不许留「缺 key 补默认」的兼容分支。

| 版本 | 内容 | 迁移 |
|---|---|---|
| v1 | `colonies`（id→原点）+ `founders` | — |
| v2 | + `rosters`（colonyId → (playerUuid → 档位)） | v1→v2：`founder` 提升为唯一 OWNER |
| **v3** | + `activeColonies`（playerUuid → colonyId） | v2→v3：新增空表（没选过 = 没选过，由 Tracker 按默认规则解析） |

载入时的两个不变量校正（都会留痕）：① 有 `founder` 的镇必须有一个 OWNER；② 当前镇必须仍然存在。

> **`founders` 只许正向查**（`getFounder(colonyId)`）。**绝不要按玩家反查**——一人多镇下必然歧义。

---

## 八、友军名单（与权限正交的另一层）

`content/npc/types/FriendlyForce`（**零 MC 依赖**，纯判定）：

- 白名单**从花名册派生**（不再从「创始人身份」派生），玩家侧实体的所属镇是**集合**（一人多镇）。
- **成对 `linked(X, Y)`**，两侧共用，天然对称：

  ```
  linked(X, Y) = X == Y
               ∨ ∃P (P ∈ X.roster ∧ P owns Y)
               ∨ ∃P (P ∈ Y.roster ∧ P owns X)
  ```

  只在一侧加特例必留单向漏洞（历史上「A 不还手、C 下死手」就是这么来的）。
- **刻意不做传递闭包**：`linked(A,C) ∧ linked(C,D)` **不**推出 `linked(A,D)`。**别**把「友军镇集合」预展开再求交——那会凭空多豁免一层，而这个写法看起来更简单，最容易被好心改坏。
- 两个入口**不要合并**：`isAlliedTo`（双向、受 config 开关约束、只认本镇直接成员）vs `isFriendlyForce`（单向、覆盖全部实体类别、含跨镇 `linked`）。
- `content/npc/guard/NpcFriendlyFireHandler` 同样按花名册判「自己人」（用 `getColonyByFounder` 会让 MANAGER/MEMBER/ALLY 砍自家法师照样掉血）。

---

## 九、关键文件索引

| 关注点 | 位置 |
|---|---|
| **档位枚举** | `content/colony/roster/ColonyRole` |
| **当前镇（唯一真源）** | `content/colony/ActiveColonyTracker` |
| **权限 / 上下文唯一入口** | `content/colony/ownership/ColonyOwnership` |
| 花名册 + 当前镇落盘 / 迁移 | `content/colony/ColonySavedData` |
| 档位契约 | `api/ColonyApi`（`getRole/getRoster/getColoniesOf/setRole/removeMember/transferOwner/getActiveColony/setActiveColony`） |
| 网络网关 | `foundation/networking/ColonyScopedPayload`、`ColonyScope`、`PayloadRegistry.c2s` |
| 邀请 / 成员动作 | `content/colony/network/ColonyMemberActionPacket`、`ColonyInviteRegistry`、`ColonyRosterSyncService` |
| **切换后统一推送** | `content/colony/network/ColonyContextSync` |
| 客户端镜像 | `content/colony/network/ColonyPanelClientState`、`foundation/ui/panel/WandscapePanelState` |
| 小镇面板 | `foundation/ui/panel/WandscapePanelOverlay`（渲染）/ `WandscapePanelController`（命中与分发）/ `WandscapePanelState.SubMode.COLONY` |
| 建筑 GUI 咽喉 | `content/building/internal/BuildingInteractHandler` |
| 友军名单 | `content/npc/types/FriendlyForce`、`content/npc/entity/WandscapeNpc`、`content/npc/guard/NpcFriendlyFireHandler` |
| 领地保护 | `content/colony/guard/ColonyLandProtectionHandler` |

---

## 十、已知边界与后续待完善

**明确未做（不要当成 bug 修）**

1. **无 OP 全局旁路**——有意为之（§3 铁律 1）。管理员走命令面。
2. **不支持离线邀请**——邀请只存内存，被邀方须在线。
3. **游客经济只对 NPC 开放**：非成员连商店/旅店都不能用，这是「未登记 = 无权限」的字面执行；NPC 游客走 `TouristEntity`，不经玩家权限路径。

**已知缺口（可做，按优先级）**

4. **网关覆盖率 = 1 个包**：其余约 30 个操作包仍是包内自查（§4）。收益是「判定落点收敛到一处」，代价是要逐个迁移并保持同档位。
5. **任务面板不在 `ColonyContextSync.push` 里**：切换后进「任务」页才按当前镇重推（数据正确，只是非切换瞬间）。
6. **被移出当前镇后顶栏要等下一次 stats 推送才清空**：既有同步节奏，非本系统引入。
7. **`PanelStateTogglePacket` 与 `ColonyContextSync.push` 有推送重复**：收口可省约 15 行，代价是每次开面板多发 2~N 包。
8. **`getColonyId(BlockPos)` 的 ≤256 空间最近归属**：多镇可任意近时，对其余非市政厅建筑仍有归属歧义（既有问题）。本系统只保证**建镇/关联判据不再受它影响**（已改看市政厅自己的 `BuildingState.colonyId`）。

**运行时验证状态**

9. 本系统经**全量编译**与**部署启动**验证（含 `ColonySavedData` v2→v3 迁移在真实存档上跑通、无启动报错），但**游戏内行为未逐条实测**（项目禁 `runClient`）。首验建议走：A 邀在线 B → B 接受并自动切换（不传送）→ B 作为 MEMBER 能开仓库/工坊但不能拆建筑 → 升 MANAGER 后能建能管法师 → B 另建自己的镇并自动切过去 → 面板两组互切、顶栏与边界跟着变 → 断线重连仍在最后那座镇。并确认 PVP 开启时各档位不挨自家法师打、盟友两镇互不侵犯且**不过度传递**。

---

## 十一、关键裁定留档（含「为什么」，避免被回退）

| 裁定 | 理由 |
|---|---|
| **每座镇一个 OWNER，但一人可拥有多座** | 「一镇一主」是花名册不变量；「一人一镇」是旧模型的反向假设，已废止 |
| **ALLY 不可切换当前镇** | 它零操作权限，切过去也无事可做 |
| **禁空间归属推断** | 位置推断是跨镇泄密的根因（历史事故） |
| **邀请须显式接受** | 单向加人会让玩家在不知情下获得/失去上下文 |
| **`ACCEPT` 只认服务端记录** | 采信包体 = 篡改客户端即可用 OWNER 入伙 |
| **邀请只能授予低于自己的档位** | 防越权提拔；OWNER 只能靠转让产生 |
| **无 OP 旁路** | 多殖民地的核心就是权限分离，静默给 OP 全局旁路是新增特权面，应是显式决定 |
| **`linked` 成对、不做传递闭包** | 传递闭包会凭空多豁免一层 |
| **删除 `getColonyByFounder`** | 一人多镇下必然歧义，且会把「作为成员参与别人的镇」误判成「没有镇」 |
| **面板不本地改选中** | 单一真源，避免「切了但顶栏不变」 |
| **不删档部署** | 常规上线只删模组 `config/*.toml` 让新默认值生效；仅不兼容或用户明确要求才删档，且删前备份（见 `CLAUDE.local.md`） |
