# 多人生存殖民地隔离现状评估与权限系统演进方案（multiplayer-isolation-survey）

> 信息截至 2026-09-04 | 考察基线：分支 `1.21.1`（Minecraft 1.21.1 / NeoForge 21.1.233 / Mojmap）
> **性质：多人生存/服务器环境下的玩家隔离现状评估与权限组系统演进路线图。**

- **【何时读】**：规划多人生存联机功能、排查多人服务器安全漏洞、或设计开发殖民地所有权与权限组体系前阅读。
- **【核心问题解答】**：
  1. 上服务器时，玩家隔离有无？现状是什么，是否已经完全隔离？
  2. 所有交互操作是否只能 Owner 交互？
  3. 最终演进成权限组（Owner 可指定其他玩家交互权限）需要多少工作量？

---

## 一、评估结论速览（TL;DR）

1. **隔离现状定性：极度初级、大面积不设防（处于纯单人可用向多人过渡期）**。
   - **底层数据层已完成按 Colony 隔离**：仓库存储（`ColonyItemBank`）、建筑记录（`BuildingSavedData`）、小镇数据（`ColonySavedData`）在物理上已经完全按 `colonyId`（UUID）划分。`ColonySavedData` 也持久化记录了创始人 `founder`（UUID），且创建时有一人一小镇限制。
   - **但业务与网络层完全未做权限隔离**：`ColonySavedData.java` 第 45 行注释明确写道：
     `/** colonyId → founding player UUID (informational; permissions remain shared). */`
     即创始人字段最初**仅作为展示信息记录**，模组此前默认所有玩家对所有殖民地共享操作权限。
2. **所有交互是否只能 Owner 交互？—— 绝非如此！90% 以上的操作完全开放**。
   - **仅有 4 个玩家专属道具限制了 Owner**：权杖（Scepter）下令、盟誓戒指（Oath Ring）收放法师、指南针（Compass）定位与传送、便携仓库终端（Warehouse Terminal）快捷打开。
   - **其余所有核心交互对所有玩家完全敞开**：任何人右键任何建筑均可打开对应 GUI；任何人可搬空他人仓库、解雇他人法师、扒掉法师装备、修改法术预设、拆除他人建筑、在他人领地放蓝图施工、调度/取消任务、甚至是原版直接拿镐子拆毁方块。
3. **最终目标**：
   - 确立「Owner（领主）绝对主权」原则，构建包含 `OWNER`、`MANAGER`（官员）、`MEMBER`（成员）、`VISITOR`（访客）的**角色与细粒度权限控制体系（RBAC）**，Owner 可在市政厅界面自由添加协作者并授予指定权限。
4. **工作量评估**：
   - 全套系统预估工作量为 **8 - 14 人天**，建议按「Phase 0 紧急止血防盗（1-2天）→ Phase 1 数据与权限模型（2-3天）→ Phase 2 全链路鉴权拦截（3-4天）→ Phase 3 市政厅管理 GUI（4-5天）」循序渐进落地。

---

## 二、当前多人生存代码事实全面盘点

### 2.1 底层数据与所有权（已具备雏形）

| 维度 | 现状代码事实 | 评价 |
|---|---|---|
| **小镇唯一标识** | 核心实体与持久化全走 `UUID colonyId`，空间以 Town Hall 坐标为中心（`origin`，默认半径 64 格 `MAX_COLONY_RANGE`）。 | 极佳，底层天然支持多殖民地并存。 |
| **小镇创始人记录** | `ColonySavedData` 持久化 `founders: Map<UUID, UUID>`，`ColonyApi` 暴露 `getFounder(colonyId)` 与 `getColonyByFounder(playerUuid)`。 | 已有 Owner 物理记录。 |
| **创建配额约束** | `ColonyCommand.createColonyAt` 校验 `if (founder != null && getColonyByFounder(founder) != null)` 拒绝创建第二个。 | 已有一人一小镇约束。 |
| **离线运行与收益** | `ColonyActivation.isFounderOnline(colonyId)` 检测该小镇创始人是否在线；离线收益按系数折减（`Config.COLONY_OFFLINE_INCOME_MULTIPLIER`）。 | 已实现按 Founder 在线状态驱动经济逻辑。 |
| **权限标记现状** | `ColonySavedData.java:45` 显式注明 `permissions remain shared`。 | **关键事实：权限共享是既有设计留白**。 |

---

### 2.2 已具备隔离特性的模块（少数特例）

当前代码中，仅有**在设计时明确与单个玩家强绑定的功能道具**实现了 Owner 校验，均通过 `ownColony(player) = api.getColonyByFounder(player.getUUID())` 守卫：

1. **权杖系统（`ScepterService`）**：
   - `requireOwnMage` 强制比对目标法师所属殖民地是否为执行玩家自己创建的殖民地（`ownColony(player)`）；非自家族人拒绝执行指令（`message.wandscape.scepter.other_colony`）。
2. **盟誓戒指（`OathRingService`）**：
   - 只能将玩家自己殖民地的法师收纳进戒指；从戒指中释放法师时也校验玩家所属殖民地。
3. **魔法指南针（`CompassService`）**：
   - 定位与传送目标严格绑定为玩家自己殖民地的市政厅（`resolveTownHall(player)`）。
4. **便携仓库终端（`WarehouseTerminalItem`）**：
   - 快捷键或右键手持打开时，内部走 `ownColony(player)`，仅打开玩家自己的小镇仓库。
5. **友军与伤害保护（`NpcFriendlyFireHandler` / `WandscapeNpc.isFriendlyForce`）**：
   - 误伤免疫（`NpcFriendlyFireHandler`）仅免疫「该殖民地 Founder」造成的误伤，其他玩家对法师的攻击不会被取消；
   - 开启 `Config.PVP` 时，`WandscapeNpc` 仅将 Founder 与该殖民地成员视为友军，不同殖民地玩家按敌意/中立结算。

---

### 2.3 完全未隔离的交互面与安全隐患（重大缺口）

除了上述 4 个道具外，模组其余所有与殖民地相关的操作面**全部处于裸奔状态**：

#### 1. 方块物理右键交互（`BuildingInteractHandler.java`）
- 触发拦截仅校验 `PanelStateTracker.isPanelOpen(player)`（玩家按 V 打开了主面板），**完全不校验玩家身份**。
- **仓库（Warehouse）被盗风险**：任何玩家只要按 V 走到他人仓库方块前右键，即可打开对应 `WarehouseMenu`；配合 `WarehouseActionPacket`（`take_to_inventory`、`cursor_take_all`），**任何玩家可瞬间搬空他人仓库的所有物品与元素**。
- **市政厅（Town Hall）篡改风险**：任何玩家右键他人市政厅，可直接发送 `ColonyNameUpdatePacket` 篡改镇名、发送 `TownHallNameStylePacket` 篡改起名风格、发送 `TownHallTouristSpawnPacket` 关闭他人游客生成。
- **酒馆（Tavern）恶意消耗**：任何玩家右键他人酒馆，发送 `TavernRecruitPacket`，会**直接扣除该殖民地仓库中的元素**为该殖民地招募法师，或恶意拒绝（`reject_mage`）待选简历。
- **法师小屋（Mage Hut）越权管理**：任何玩家发送 `MageHutActionPacket`，可随意给他人法师小屋分配法师、消耗他人每种元素上千点进行升级（`upgrade`）与属性训练（`train`）、并远程打开法师装备栏。
- **工坊/合成站（Workstation / Crafting Station）**：任何玩家可下派分解与合成任务，消耗该殖民地的材料与元素。
- **商店（Shop）**：任何玩家发送 `ShopMaxStockPacket` 可篡改他人商店的最大库存。

#### 2. 灵魂投影与蓝图建造（`ProjectionPlacePacket.java` / `BuildingActionPacket.java`）
- **蓝图乱建与资源盗刷**：`ProjectionPlacePacket` 在放置建筑时，根据锚点空间坐标 `getColonyId(anchor)` 确定殖民地。任何玩家走到他人小镇地界上使用蓝图，会直接消耗**他人小镇的仓库建材**与**首座免费（first_free）额度**，并强制调遣他人小镇的 NPC 前来施工。
- **建筑恶意拆毁与撤销**：`BuildingActionPacket.handleServer` 接收 `UUID buildingId` 和 `action`：
  - `action="destroy"`：直接执行 `api.demolishBuilding(buildingId)`，**任何玩家都可以发包拆毁他人殖民地的任何建筑**！
  - `action="cancel"`：直接撤销在建建筑并将建材原样退回仓库。

#### 3. NPC 实体交互与管理（`WandscapeNpc.java` / `Npc*Packet.java`）
- **扒光装备**：空手右键任何 NPC 触发 `WandscapeNpc.mobInteract` 打开 `NpcMenu`。由于 `NpcMenu.stillValid` 仅判定 `npc.isAlive()`，**任何玩家都可以直接打开他人法师的装备栏，把法师穿的盔甲和施法法杖直接拿走**。
- **恶意解雇（永久抹除）**：`NpcDismissPacket` 接收 `entityId` 后直接调用 `npc.dismissFromColony()`，从世界中永久清除实体且不留死亡记录，**任何玩家均可一键开除他人小镇的所有法师**。
- **篡改战术与改名**：`NpcRenamePacket` 允许任何人重命名他人法师；`NpcStrategyPacket` 允许任何人清空或篡改他人法师的战斗魔法插槽；`NpcTogglePacket` 允许任何人强行开启跟随（`follow`），把他人法师直接拐走。

#### 4. 任务池与调度系统（`TaskManagementActionPacket.java` / `TaskQueueModifyPacket.java`）
- **任务取消与插队**：`TaskManagementActionPacket` 允许任何玩家取消（`CANCEL`）、加急（`RUSH`）或随意调整全服任意任务的优先级（`SET_PRIORITY`）。
- **生产队列篡改**：`TaskQueueModifyPacket` 允许任何玩家清空、删除或调整他人建筑中的生产和建造队列。

#### 5. 领地与方块破坏保护（Land Claim）
- **现状完全为 0**：模组目前未对 `BlockEvent.BreakEvent` 和 `BlockEvent.EntityPlaceEvent` 做任何领地范围内的保护。
- 任何外来玩家拿普通原版镐子/斧头，可以直接把他人殖民地的市政厅、仓库、民居敲掉；TNT、苦力怕爆炸也没有领地豁免。

#### 6. 面板穿透与 HUD 回退（`PanelStateTracker.java`）
- 当玩家自己尚未创建殖民地时，按 V 会通过 `colonyApi.getColonyId(player.blockPosition())` 回退为「就近殖民地」，导致玩家在他人领地中打开 V 面板会直接观察到他人殖民地的全量统计数据（HUD 三值、经济数据、在建项目）。

#### 7. 运维指令漏洞（`RecoveryCommand.java`）
- `/wandscape recover clear`（清空任务池与建筑队列、重置全服法师）当前的权限级别为 **0（免权限）**，任何普通玩家在聊天框敲入该指令，即可摧毁全服所有玩家小镇正在运行的任务流。

---

## 三、目标架构设计：基于角色的权限控制体系（RBAC）

为了让 Owner 能够自由指定哪些玩家可以交互自己的殖民地，需要引入轻量、清晰且易扩展的角色权限体系。

### 3.1 角色定义（Colony Roles）

| 角色 | 标识符 | 定位与权限边界 |
|---|---|---|
| **领主（Owner）** | `OWNER` | 殖民地创建者/现任所有者。拥有全部权限；享有改名、解散小镇、转让所有权、任命/移除官员的独占特权。每个殖民地恒有且仅有 1 人。 |
| **副官/官员（Manager）** | `MANAGER` | 核心管理者。可规划蓝图、拆除建筑、调动法师、管理装备、调度任务、存取全部仓库物资。不可转让小镇或任免同级官员。 |
| **居民/协作者（Member）** | `MEMBER` | 普通合作玩家。可存取仓库物资（可配置是否限额）、使用工坊派单合成、享受小镇法师防御庇护。不可拆除建筑、不可解雇法师。 |
| **访客/外人（Visitor / Public）** | `VISITOR` | 默认非成员玩家。只能进入小镇参观、在商店购买商品、在旅店入住消费。**禁止拿取仓库、禁止拆建、禁止触碰 NPC 装备与指令**。 |

---

### 3.2 细粒度权限节点（Permission Nodes）

建议抽象枚举 `ColonyPermission`，统一作为鉴权网关的凭证：

```java
public enum ColonyPermission {
    // 领地与方块
    TERRITORY_BREAK,       // 破坏领地内的方块
    TERRITORY_PLACE,       // 在领地内放置方块
    TERRITORY_CONTAINER,   // 开启领地内的原版箱子/熔炉等

    // 建筑与规划
    BUILDING_PLACE,        // 使用蓝图/投影放置新建筑
    BUILDING_DEMOLISH,     // 拆除或撤销已有建筑
    BUILDING_REPAIR,       // 触发建筑维修
    ROAD_BUILD,            // 规划与修建道路

    // 仓储与经济
    WAREHOUSE_WITHDRAW,    // 从仓库取出物品或元素
    WAREHOUSE_DEPOSIT,     // 向仓库寄存物品或元素
    SHOP_MANAGE,           // 调整商店最大库存与定价

    // NPC 与人事
    NPC_RECRUIT,           // 从酒馆招募新法师
    NPC_DISMISS,           // 解雇法师
    NPC_INTERACT_EQUIP,    // 打开法师装备栏并穿脱装备
    NPC_COMMAND,           // 使用权杖下达指令 / 调整战斗法术与 AI 策略
    MAGE_HUT_MANAGE,       // 法师小屋分配入住、消耗元素升级/训练

    // 任务调度与管理
    TASK_ORDER_PRODUCTION, // 在工坊/合成台下发生产任务
    TASK_QUEUE_MODIFY,     // 调整或删除建筑生产队列
    TASK_GLOBAL_MANAGE,    // 全局任务面板取消/加急任务

    // 小镇治理（仅 Owner 与指定 Manager）
    COLONY_SETTINGS,       // 修改镇名、起名风格、游客生成开关
    MEMBER_MANAGE          // 邀请/踢出成员、调整角色权限组
}
```

#### 默认角色权限映射矩阵

| 权限节点 | VISITOR (外人) | MEMBER (成员) | MANAGER (官员) | OWNER (领主) |
|---|:---:|:---:|:---:|:---:|
| 参观 / 商店购买 / 旅店入住 | ✔ | ✔ | ✔ | ✔ |
| 仓库寄存（Deposit） | ✘ | ✔ | ✔ | ✔ |
| 仓库取出（Withdraw） | ✘ | ✔ | ✔ | ✔ |
| 工坊派单合成（Production） | ✘ | ✔ | ✔ | ✔ |
| 领地方块破坏与放置 | ✘ | ✘ / 可配 | ✔ | ✔ |
| 蓝图放置与道路施工 | ✘ | ✘ | ✔ | ✔ |
| 建筑拆除与撤销 | ✘ | ✘ | ✔ | ✔ |
| 招募 / 训练法师 | ✘ | ✘ | ✔ | ✔ |
| 穿脱法师装备 / 调 AI 策略 | ✘ | ✘ | ✔ | ✔ |
| 解雇法师（Dismiss） | ✘ | ✘ | ✘ | ✔ |
| 任务取消与强制加急 | ✘ | ✘ | ✔ | ✔ |
| 小镇设置（改名/游客开关） | ✘ | ✘ | ✘ | ✔ |
| 成员邀请与角色分配 | ✘ | ✘ | ✘ | ✔ |

---

### 3.3 架构实现接缝与唯一网关（Choke Point）

根据 CLAUDE.md 硬原则（直接调用、逻辑聚敛唯一类、禁过度分层），权限判定应当在 `content/colony/` 内部聚敛为单一鉴权网关：

```java
public final class ColonyPermissionService {
    /** 检查玩家在目标殖民地是否具备某项操作权限。 */
    public static boolean hasPermission(UUID colonyId, UUID playerId, ColonyPermission permission) {
        if (colonyId == null || playerId == null) return false;
        
        // 1. OP 管理员绕过（可配置是否开启）
        if (isOpBypass(playerId)) return true;

        // 2. 创始人 / Owner 拥有全权
        UUID founder = WandscapeApis.getColonyApi().getFounder(colonyId);
        if (playerId.equals(founder)) return true;

        // 3. 读取成员角色列表
        ColonySavedData data = ColonySavedData.get(level);
        ColonyRole role = data.getMemberRole(colonyId, playerId); // null 视为 VISITOR

        // 4. 判定角色是否包含该权限节点
        return role != null && role.has(permission);
    }
}
```

---

## 四、实施阶段拆解与工作量评估

整个权限系统的建设建议分为 4 个演进阶段。由于当前是「初级阶段」，推荐先以最小代价实施 **Phase 0** 进行防护，再逐步演进完整体系。

```
┌─────────────────────────────────────────────────────────────┐
│  Phase 0: 紧急止血与纯 Owner 门禁（1-2 天，快速堵住重大漏洞）        │
│  - 拦截所有毁灭性数据包 (拆建筑/解雇NPC/清空任务池)              │
│  - 仓库取物与 NPC 装备栏加 Owner 判定                        │
│  - /wandscape recover clear 改为 op 2 权限                  │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│  Phase 1: 权限数据模型与多玩家持久化（2-3 天，奠定核心底座）         │
│  - ColonySavedData 增加 members 表与版本迁移链               │
│  - 抽象 ColonyPermission 与 ColonyRole 枚举                 │
│  - ColonyApi 暴露权限与成员管理契约                           │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│  Phase 2: 全链路交互与网络鉴权接入（3-4 天，系统级收拢）           │
│  - 改造 15+ 个网络包，接入统一鉴权网关                          │
│  - 改造 BuildingInteractHandler 与 NpcMenu                  │
│  - 补齐 BlockEvent 领地破坏与放置保护                         │
│  - 客户端拒止反馈（ScreenFeedbackPacket / 浮字）              │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│  Phase 3: 市政厅权限管理 GUI 与体验打磨（4-5 天，交付玩家可用）       │
│  - 市政厅界面新增「成员与权限」Tab                            │
│  - 成员名单列表、邀请在线玩家、分配角色、踢出成员                │
│  - HUD 面板访客状态友好提示与操作按钮置灰                      │
└─────────────────────────────────────────────────────────────┘
```

---

### Phase 0：紧急安全防御与纯 Owner 硬隔离（极小成本，快速堵漏）
- **目标**：在不改动 UI、不设计复杂权限界面的前提下，直接封死所有能对他人殖民地造成毁灭性破坏的后门。
- **改动范围**：
  1. **网络包加固**：
     - `BuildingActionPacket`：校验 `player.getUUID().equals(founder)`，非 Owner 禁止拆除（`destroy`）与撤销（`cancel`）。
     - `NpcDismissPacket`：非 Owner 禁止解雇。
     - `NpcRenamePacket` / `NpcStrategyPacket` / `NpcTogglePacket`：非 Owner 禁止修改他人 NPC。
     - `ProjectionPlacePacket`：如果放置地属于已有殖民地且执行玩家不是 Owner，拒绝放置。
     - `TaskManagementActionPacket` / `TaskQueueModifyPacket`：非 Owner 禁止修改任务与队列。
  2. **仓库与装备防御**：
     - `WarehouseActionPacket`：如果当前仓库所属小镇的 Founder != player，仅允许 deposit（寄存），禁止任何形式的 take（取出）。
     - `NpcMenu`：非 Owner 右键 NPC 时仅打开只读展示面板，禁止拖拽或 Shift 取走装备。
  3. **指令封堵**：
     - 将 `RecoveryCommand` 的 `clear` 节点权限要求从 `0` 提升至 `2`（OP 专属）。
- **预估工作量**：**1 ~ 2 人天**。

---

### Phase 1：权限数据模型与多玩家持久化（底层底座）
- **目标**：建立持久化存储与 API 契约，支持一个小镇绑定多个成员及其角色。
- **改动范围**：
  1. `ColonySavedData` 扩展：
     - 增加 `Map<UUID, Map<UUID, ColonyRole>> colonyMembers`（小镇ID -> (玩家UUID -> 角色)）；
     - 遵守 CLAUDE.md 硬规则 7（SavedData 带 `version` 显式升级，旧存档默认仅有 founder=OWNER，其余成员为空）。
  2. 权限模型抽象：
     - 新建 `ColonyPermission` 枚举、`ColonyRole` 枚举或 record。
  3. API 扩充（`ColonyApi`）：
     - `hasPermission(UUID colonyId, UUID playerId, ColonyPermission perm)`；
     - `getMemberRole(UUID colonyId, UUID playerId)`；
     - `setMemberRole(UUID colonyId, UUID playerId, ColonyRole role)`；
     - `getMembers(UUID colonyId)`。
- **预估工作量**：**2 ~ 3 人天**。

---

### Phase 2：全链路交互网关与领地保护接入（系统闭环）
- **目标**：将 Phase 0 中的纯 Owner 硬编码统一重构为 `ColonyPermissionService.hasPermission`，并补全领地防护。
- **改动范围**：
  1. **方块破坏与交互保护**：
     - 监听 NeoForge `BlockEvent.BreakEvent` 与 `EntityPlaceEvent`；
     - 若事件位置位于某殖民地范围内（`getColonyId(pos) != null`），校验玩家是否具备 `TERRITORY_BREAK` / `TERRITORY_PLACE` 权限；不具备则 `event.setCanceled(true)` 并给出提示。
     - 防爆处理：`ExplosionEvent.Detonate` 过滤保护区内方块（可配置是否开启苦力怕/TNT防爆）。
  2. **网络包与建筑交互网关接入**：
     - 逐一接入 15+ 个相关网络包与 `BuildingInteractHandler`；
     - 统一拒止反馈：当权限不足时，向客户端回发 `ScreenFeedbackPacket`（"§c你没有该殖民地的操作权限"）。
- **预估工作量**：**3 ~ 4 人天**。

---

### Phase 3：市政厅管理 GUI 与玩家交互界面（用户体验）
- **目标**：给 Owner 提供直观、美观的游戏内管理界面，彻底摆脱指令配置。
- **改动范围**：
  1. **市政厅 Screen 改造（`TownHallScreen`）**：
     - 增加一个「成员与权限」分页（Tab）；
     - 列表展示当前小镇的成员列表、在线状态、当前身份头衔；
     - 提供「添加/邀请玩家」、「调整身份（官员/成员）」、「移出小镇」按钮。
  2. **邀请协议与交互流（Networking）**：
     - C2S `ColonyInviteRequestPacket`（Owner 邀请指定在线玩家）；
     - S2C `ColonyInviteNotifyPacket`（被邀请玩家收到屏幕弹窗或可点击的聊天栏邀请）；
     - C2S `ColonyInviteResponsePacket`（被邀请玩家接受/拒绝加入）。
  3. **HUD 面板（V 键）自适应**：
     - 访客在他人小镇按 V 时，面板顶部清晰标识「【访客模式】当前小镇所有者：XXX」，并隐藏或禁用交互与派单按钮。
- **预估工作量**：**4 ~ 5 人天**。

---

## 五、总结与下一步建议

| 阶段 | 核心任务 | 工期预估 | 产出价值 |
|---|---|---|---|
| **Phase 0** | 紧急修补毁灭性后门（拆除/解雇/偷物资硬门禁 + 指令提权） | 1-2 天 | **立刻消除服务器被恶意玩家破坏的致命风险**。 |
| **Phase 1** | 数据模型与持久化扩展（SavedData 升级 + API 契约） | 2-3 天 | 完成一人小镇向多玩家协作的底层架构蜕变。 |
| **Phase 2** | 全链路网络包改造 + 原版方块破坏保护 | 3-4 天 | 建立完整的服务器级安全防御与权限拦截闭环。 |
| **Phase 3** | 市政厅权限管理 UI + 邀请协议 + 界面适配 | 4-5 天 | 交付玩家端友好易用的经营管理体验。 |
| **合计** | **全套权限系统开发演进** | **8 - 14 天** | **完全满足多人联机服务器环境下的自主管理与安全隔离需求**。 |

**行动建议**：
在当前的初级阶段，无需立即投入精力开发复杂的 Phase 3 图形界面。**强烈建议优先用 1 天时间落地 Phase 0**，对关键破坏性数据包进行 Owner 校验，将 `/wandscape recover clear` 提权至 OP 2，以极低成本迅速获得服务器环境下的基础安全性；后续再根据联机玩法的深化逐步推展 Phase 1 ~ 3。
