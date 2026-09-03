# 核心功能域避坑手册（domain-notes）

> 信息截至 2026-09-02 | Minecraft NeoForge 1.21.1

- **【何时读】**：第一次接触或修改某个具体功能域（NPC/游客/魔法/任务/建筑/仓库/道路/新手引导）代码前。
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
   - 成员：所有玩家、同殖民地 NPC / 其铁魔法随从 / 游客、玩家训养的宠物（`OwnableEntity` 有主：狼/猫/鹦鹉/马/骆驼/羊驼）、守护召唤（铁傀儡/雪傀儡）、玩家召唤的铁魔法随从、其它模组经 `FriendlyForceApi.registerAlly` 注册的友军。
   - **单向**（NPC 不攻击/不记仇/不溅射友军）走 `isFriendlyForce`；**双向互不侵犯**（玩家宠物/随从不打殖民地单位、殖民地随从不打玩家）走 `FriendlyTargetingHandler` 监听 `Mob.setTarget` 的 `LivingChangeTargetEvent`。只覆盖「真实殖民地侧」，`EvilMage` 等 `isColonyNpc()==false` 的敌意行为刻意保留。
   - ⚠️ **做其他模组兼容时，务必把该模组的召唤物/宠物经 `FriendlyForceApi.registerAlly` 加入盟友名单，避免殖民地 NPC 误伤**——这是硬性提醒，遗漏会导致兼容模组的召唤生物被己方法师当敌人打死。

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

---

## 四、任务与 ECS 域 (`content/task`)

1. **任务发布显式绑定 colonyId**：
   - `TaskRequest` 中的 `colonyId` 是显式必须字段。建筑/生产任务必带对应殖民地 ID；无主任务（如通用守卫）只派发给真实殖民地 NPC，未注册/全零占位殖民地 NPC 绝不派活。
2. **纯逻辑零 MC 依赖**：
   - `content/task` 内的核心 ECS、任务评分、调度算法、状态计算严禁 import 任何 Minecraft / NeoForge 类，保持纯 Java 运行与快速测试能力。
3. **蓝图 Java-lambda 化**：
   - 蓝图 DSL 解释器已废除，默认蓝图全部收敛为 `content/task/engine/dsl/BlueprintDefaults.java` 中的 Java lambda 函数注册。

---

## 五、建筑与扫描域 (`content/building`)

1. **建筑扫描器保真分层导出**：
   - `ScannerBlockEntity`（生存扫描器）导出时 `isSafeExport() == true`，强制跳过所有方块实体 NBT，并剥离物品展示框内的物品，彻底防止玩家通过扫描容器刷物品。
   - `CreativeScannerBlockEntity`（创造扫描器）完整保真导出 NBT。
2. **关键基础设施拆除防护**：
   - 全世界范围内仅剩最后 1 座市政厅（government）、仓库（storage）或工作站（workstation）时，禁止拆除或取消，防止殖民地系统运转瘫痪。
3. **向下兼容目录不可删**：
   - `src/main/resources/data/wandscape/buildings/deprecated/` 包含旧存档兼容建筑载荷，属于必须加载项，禁止删除。
4. **重叠规则——盒可叠、方块不可叠（判定唯一源 `BuildingVoxels`）**：
   - 建筑注册的唯一限制是「同一世界坐标不能被两座建筑 pattern 共用」；boundary 包围盒可任意重叠，室内/贴墙/嵌套建筑都允许。
   - 归属：建筑自注册起拥有其 pattern 所占每个格。建造=放、拆除=清、修复=补都只落 pattern 坐标；盒内非 pattern（空气/家具/装饰/嵌套建筑的墙）永不触碰。玩家替换进 pattern 格的方块会照拆、修复也会覆盖回原样（该格归建筑）。
   - 重叠判定是两阶段：先拿 pattern 所占 AABB 粗筛、不相交直接放行，只有相交对才精判格子。服务端 register 与客户端幽灵预览**共用** `BuildingVoxels`，改重叠逻辑只动这一处，别在两处各自写一套。
   - 旋转：pattern/精判/预览全部按 `rotationSteps` 旋转后的世界坐标算，动手前先查 `BuildingRotation` 调用链。
5. **建造不再清整盒**：
   - `build:clear_and_build` 已不再把 boundary 体积置 air（退化纯放置），`EnqueueHelper` 也不注入 clear_offsets。新建筑叠进已有建筑内部不会清除其内容；非平地放置时盒内残土会保留，玩家需自行选平地。
6. **锚点与查询不再依赖盒互斥**：
   - anchor 落在其它建筑盒内是合法的（室内建筑的前提）。完成/拆除事件优先按 `building_id` 定位（`EnqueueHelper`/repair 都带该参），别只靠 anchor 反查。
   - posIndex 加载时从持久化 pattern 重建，重启后点 pattern 格仍精确归属；盒兜底/交互区查询取「最内层（体积最小）」建筑。

---

## 六、仓库与物流域 (`content/warehouse`)

1. **仓库终端行为**：
   - 仓库终端支持 Curios 手饰槽（`hands`/`bracelet`）或快捷键开仓，开仓前必须服务端验证佩戴状态或背包持有。
2. **大数量渲染限制**：
   - 原版 Minecraft 浮动物品数量渲染上限为 999（`renderItemDecorations`），仓库中大于 999 的物品显示为 `999+` 属正常原版行为。

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
