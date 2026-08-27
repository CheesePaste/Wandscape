# 设计决策记录

本文件记录偏离直觉的设计选择及其原因，供后续开发快速理解「为什么这么做」。

## 2026-08-27：法师等级上限 = 殖民地等级 + 1（colony 30 → 法师 31）+ MageHut 两位小数 + 护甲默认 4

**需求**（用户指令）：1) MageHut 属性显示两位小数——移动速度 0.25 被 `%.1f` 显示成 0.3；2) 法师等级上限改为"殖民地等级 + 1"——colony 上限保持 30、法师可升到 31：满级满基础生命正好 100（base ≤40 + 2×30 = 100），且殖民地刷 31 级游客（colony 30 的 C+1）天然有 31 级法师简历；3) 护甲最低 0、最高 8、默认 4。

**决策**：
- `MageHutAttributes.canLevelUp`：`level < colonyLevel` → `level <= colonyLevel`——法师可达 colony+1（colony 30 → 法师 31）。`Config.COLONY_MAX_LEVEL` **保持 30**。
- **不封顶游客**：`rollTouristLevel` 维持 C-1/C/C+1 无上限——colony 30 时 C+1=31 恰好是法师上限；colony 上限回到 30 后不存在 32 级游客，无需 clamp。
- **护甲默认 4**：范围保持 [0,8]（`MageHutAttributes` lower 0/upper 8/步进 0.4、`MageAttributeRoller` 掷 0-8），仅 `EquipmentComponent.BASE_VALUES` 护甲默认 0→4——与其它属性默认取中位数一致（移速 0.3、法术强度 1）。
- `MageHutScreen.fmt`：`%.1f` → `%.2f`（整数仍无小数），属性行/单次特训步进/tooltip 统一。

**为什么**：colony 等级是建筑解锁/游客节奏的锚（平衡口径按 30 标定），整体抬到 31 会拖慢后续平衡；法师上限 +1 是"百级生命"的纯收益且与 31 级游客简历吻合。护甲默认 4 让无装备 NPC 的护甲落中位，避免默认 0 与"4-8 才合理"的落差。

**影响**：`MageHutAttributes.canLevelUp`、`EquipmentComponent.BASE_VALUES`（护甲 0→4）、`MageHutScreen.fmt`；`Config.COLONY_MAX_LEVEL` 保持 30（游客不加 clamp）。测试：`canLevelUpAllowsOneAboveColony`（colony+1 语义）、`EquipmentComponentTest` 护甲默认 4、`MageAttributeRollerTest` 护甲范围 [0,8]。

## 2026-08-27：幽灵 NPC 不派活——区块卸载后任务循环失败卡死

**需求**（用户指令）：日志反复刷 `TaskExec | NPC 51 op ResourceRequestOp failed: [ResourceReq] NPC 51 not found` 与 `navigateTo: unknown or removed NPC 51`，任务无人施工卡死。

**根因**：NPC 所在区块卸载 → 原版 `setRemoved(UNLOADED_TO_CHUNK)` → `Entity.isRemoved()`=true（源码已核）→ `onRemovedFromLevel` 的 UNLOADED 分支刻意保留 ECS 组件（供重连）但不释放绑定任务。于是幽灵 NPC 的组件全在 → `TaskExecutionSystem` 每 tick 驱动它 → 所有 MC 边界操作命中 `npc == null || npc.isRemoved()` 守卫失败 → 任务释放回池 → `SchedulerSystem` 又把它当空闲工人（组件在、state=IDLE）重新派给它 → 失败 → 释放 → 再派，无限循环。

**决策**：
- **`EntityOps.isNpcAlive(npcId)` 边界方法**：MC 实体存在且未 removed（`WandscapeEntityOps` 经 EntityComponentBridge 实现，同 isFollowing 模式）。
- **调度器排除幽灵**：`SchedulerSystem` 收集空闲候选时过滤 `!isNpcAlive`——任务绝不派给不存在的工人（切断循环）。
- **执行系统遇幽灵即释放**：`TaskExecutionSystem` 第 0.6 步守卫，`releaseForPhantom` 复用抽取的 `releaseBoundGlobalTask`（`returnAndReset` 退还已取元素 + 重置步进 + `releaseTaskForReassign` 保留步进归还任务池）并丢弃 `global:` 包、取消导航、跳过执行。
- **卸载时任务归还任务池**（而非留在幽灵身上等待）：任务系统无"暂停-恢复"机制、重连路径不恢复保持绑定的包，与 KILLED/DISCARDED、跟随/休息中断同语义——"NPC 不能干活 → 任务交他人续跑"。他人续跑靠 `returnAndReset` 避免空背包打到资源短缺死循环；无他人时任务留在池中等重连后的 NPC 重新接取（stepIndex 保留，不丢进度）。

**为什么**：幽灵 NPC 的唯一正确处置是"不作为工人存在"——保留 ECS 组件只为重连复用，绝不该承担派活/驱动。在边界层一次判定（isNpcAlive），调度与执行两处消费，避免各自猜 `isRemoved` 语义。

**影响**：`EntityOps`（+isNpcAlive）、`WandscapeEntityOps`、`SchedulerSystem`、`TaskExecutionSystem`（守卫 + releaseBoundGlobalTask 抽取）、`MockBoundary`（removedNpcs 模拟）、`docs/modules/core.md`。测试：SchedulerPhantomNpcTest 3 例（调度排除幽灵 / 执行释放幽灵任务 / 释放后健康 NPC 续跑）。不改 `onRemovedFromLevel`：任务释放由执行守卫统一承担（带正确退还），MC 层不重复释放逻辑；运输/预留按时间完成，卸载不泄漏。

## 2026-08-27：法师装备/策略菜单有效性只绑 NPC 存活——不绑玩家与法师的距离

**需求**（bug 报告）：法师离玩家太远时，在法师小屋面板里远程打开「装备/策略」子面板，打开瞬间就被关掉。

**根因**：`NpcMenu`/`NpcStrategyMenu.stillValid` 沿用原版容器惯例 `player.canInteractWithEntity(npc, 64.0)`——玩家须距法师 ≤64 格。法师常远在殖民地各处执行任务，从小屋面板远程打开子菜单时该检查立即失败，原版每 tick 容器有效性检查在下一 tick 关闭菜单（服务端发 `ClientboundContainerClosePacket`，客户端容器屏闪开即关，小屋面板也被替换丢失）。

**决策**：`stillValid` 改为 `!npc.isRemoved() && npc.isAlive()`——有效性只绑法师存活，不绑距离。直接右键 NPC 打开（本就贴近）与小屋远程打开统一走同一语义。

**为什么**：法师小屋是**远程管理站**（2026-08-24 设计），管理法师的全部操作都应在小屋就地完成，不要求玩家陪在法师身边；而法师是移动实体，距离判定与「远程管理」天然冲突。保留存活判定仍能在法师死亡或区块卸载（`UNLOADED_TO_CHUNK` 置 `isRemoved`）时关闭菜单，交互不会落到无效实体上。

**影响**：`NpcMenu.java`、`NpcStrategyMenu.java` 的 `stillValid`（各删 `player.canInteractWithEntity(npc, 64.0)`）。

## 2026-08-27：法师小屋属性训练/升级重定价——每属性专用元素 + 指数曲线 + 统一 20 步

**需求**（用户指令）：小屋训练/升级原为固定全元素×1000，与收入模型不匹配（各元素产出不均，暗/金属短缺）；改为每属性消耗不同元素、数量合理规划；训练前期便宜后期极贵（前期有存在感、大后期为过剩收入的最终凹点），升级温和（有存在感但不卡玩家）；每属性统一 20 步方便规划；升级七种元素均匀消耗。

**决策**：
- **统一 20 步**：`trainStep = range/20`——SPELL_POWER/WORK/SPELL_SPEED 0.1→0.05、MAX_HP 2→1、MOVE_SPEED 0.02→0.01、ARMOR 0.5→0.4、MAX_MANA 保持 5。单步增益减半但步数翻倍，上限不变。
- **每属性 2 种元素**（7 元素各恰好出现 2 次，晚后期属性耗金/暗稀缺元素）：生命=土+金、移速=木+风、法术强度=火+暗、建造工速=土+木、施法速度=风+水、护甲=金+暗、法力=火+水。
- **训练指数曲线**：`cost(k) = 500 × 1.28^k`（k=0..19，按值域档位计，不按已训次数——高roll法师补最后一步也贵）。0.5→0.6 两步 ~2.3k、1.4→1.5 两步 ~194k；单属性 20 步 ~49.5 万、满配 7 属性一法师 ~350 万——大后期过剩收入的最终凹点，暗/金属成为自然门控。
- **升级线性**：`150 × 目标等级` 每元素，**七元素均匀消耗**；Lv1→2 各 300、Lv29→30 各 4,500，全程 ~49 万/法师（训练满配的 ~14%）——随殖民地等级自然节奏、不卡玩家。
- 成本函数放 `MageHutAttributes`（纯 Java，服务端扣费 + 客户端经同一函数同步显示）；`chargeElements` 参数化（训练扣该属性 2 元素、升级扣 7 元素）；删 `MAGE_HUT_COST_PER_ELEMENT`。

**为什么**：固定全元素×1000 既不匹配供给（暗/金属短缺时所有训练同价却只有部分元素真正缺）也不构成决策（前期 0.5 天日收入、后期对 90 万日产出是零钱）。训练是永久定向提升，指数曲线让"每次购买是决策"且给印钞机经济一个真正的回收端；升级绑殖民地等级节奏，线性足够。

**影响**：`MageHutAttributes`（SPECS 步进 + 成本模型）、`MageHutServerHandler`（chargeElements 参数化 + 不足元素明细）、`MageHutScreen`（训练卡元素图标+数量、升级卡成本行，按钮下移 8px）、`WandscapeConstants`（删 MAGE_HUT_COST_PER_ELEMENT）、`lang/{zh_cn,en_us}.json`（train_cost→upgrade_cost、insufficient 消息）、`guide/{zh_cn,en}/mage_hut_guide.md`。测试：MageHutAttributesTest 更新步进断言 + 新增 6 个成本模型用例（20 步不变式/指数首尾/元素映射平衡/升级线性）。

## 2026-08-27：NPC 盔甲改存 vanilla 装备槽——不再用独立 armorInventory

**需求**（用户指令）：当前 NPC 的 4 件盔甲存在独立 `armorInventory`（`SimpleContainer`）、不在原版装备槽——从原版/其它模组的标准 API（`getItemBySlot`/`getArmorSlots`/`ArmorItems` NBT）看就是"没穿盔甲"。问这会不会影响装备兼容性。分析后确认为三处实损：附魔不生效（`EnchantmentHelper.runIterationOnEquipment` 按 `getItemBySlot` 遍历，Protection 等全失效，仅耐久因走 `hurtAndBreak` 正常）、非 ARMOR 属性全丢（护甲韧性/击退抗性/第三方自定义属性——vanilla 每 tick 装备结算只作用于槽内物品）、读写互不可见。且发现原"独立容器防外观覆盖巫师袍"的前提是**误判**：`WandscapeNpcRenderer extends HumanoidMobRenderer`，后者只加 CustomHeadLayer/ElytraLayer/ItemInHandLayer、`WandscapeNpcRenderer` 只加巫师帽层——**没有 HumanoidArmorLayer，盔甲即使放 vanilla 槽也不会渲染**。故决定彻底改。

**决策**：
- **盔甲直存 vanilla 槽**：删 `armorInventory`/`getArmorItem`/`setArmorItem`/`armorValueOf`。装备经 `Mob.setItemSlot(HEAD/CHEST/LEGS/FEET)` 写入，原版每 tick `detectEquipmentUpdates` 自动结算护甲值/韧性/击退/移速与附魔，其它模组可见。`NpcMenu` 的盔甲容器改为包装 vanilla 槽（写 `setItemSlot`）。
- **耐久仍手动结算**：原版 `LivingEntity.hurtArmor` 对非玩家生物是空实现、`Mob` 不覆盖——即使盔甲在 vanilla 槽，原版也不会扣槽内耐久。保留 `hurtArmor` 覆盖按原版语义结算（`hurtAndBreak` 吃耐久/经验修补），破损后槽位变化由原版装备结算自动撤销属性。
- **属性模型拆分**：core `ARMOR_VALUE` 只含天生（招募）+ 法杖加成，`applyEffectiveAttributes` 照旧推 vanilla `ARMOR` base；槽内盔甲由原版叠加 transient，总护甲 = base + 槽内。GUI 显示（`NpcDataPacket`/`TaskPanelSyncTracker`）改读 `getEffectiveArmorValue()` = vanilla `getAttributeValue(ARMOR)`。
- **铁魔法桥收窄为两条**：`IronSpellsAttributes` 移除 `MOVEMENT_SPEED` 映射（原版直接结算，再映射会与 base 推送双重叠加 1.25²=1.5625）；`MAX_MANA`/`SPELL_POWER` 仍须桥进 ECS——铁魔法自有属性不在 NPC 的 AttributeMap（`AttributeSupplier.createInstance` 对未注册属性返回 null，原版静默跳过），且 Wandscape 的魔力/法强从 core 枚举读取，桥是两套属性模型之间的固有接口。
- **旧存档迁移**：`readAdditionalSaveData` 捕获旧 `armorInventory` tag（vanilla 槽全空时暂存），`onAddedToLevel` 用 `setItemSlot` 落地，不丢铁套。

**为什么**：vanilla 装备槽是装备的唯一标准事实来源——放进去，原版结算属性/附魔、`ArmorItems` NBT 持久化、其它模组可读，全免费；独立容器只是把"原版不管、模组不见"的负担全揽到自家手工结算上。前提误判（无盔甲层）意味着独立容器没有任何不可替代的价值。

**影响**：`WandscapeNpc`（删容器/`syncIronArmorAttributes`/`hurtArmor` 读槽/`dropEquipment` 读槽/NBT 迁移）、`NpcMenu`（`NpcArmorContainer` 包装 vanilla 槽）、`ColonyCommand`（铁套写 `setItemSlot`）、`IronSpellsAttributes`（去 MOVEMENT_SPEED）、`NpcDataPacket`/`TaskPanelSyncTracker`（护甲显示改 vanilla 有效值）、`docs/modules/npc.md`、`architecture/packages/npc.md`/`compat.md`。行为收益：铁甲韧性/击退、Protection 等附魔现在生效；NPC 装备可被 `/data`、`/item`、datapack 与其它模组读写。注意：非殖民地敌对法师（EvilMage）不注册 ECS，天然护甲不生效的既有语义不变。

## 2026-08-27：铁魔法冷却/吟唱缩减折叠进 SPELL_SPEED

**需求**（用户指令）：盔甲改 vanilla 槽后，继续评估铁魔法剩余属性——问法术抗性、学派法术强度、冷却缩减、吟唱缩减是否要兼容，并提议后两者统一乘入模组施法速度。

**决策**：
- **`COOLDOWN_REDUCTION`/`CAST_TIME_REDUCTION` → `SPELL_SPEED`（MULTIPLY_BASE）**：二者与 Wandscape"冷却/吟唱 ÷ SPELL_SPEED"同语义。已核对铁魔法自身公式（`冷却=基础×(2−值)`、`吟唱=基础×值`）与 `IronSpellsCaster`（用 `getCastTime` 基础值 + ÷SPELL_SPEED，未走 `getEffectiveCastTime`）——折叠不会双重叠加。常见护甲幅度（+5%~15%）下 `÷(1+A)` 与铁魔法 `×(1−A)` 近似等价（0.10 → 0.909 vs 0.90）。折叠只影响冷却与铁魔法吟唱；原生 Wandscape 法术锁时长固定（`durationTicks/2` 不随 SPELL_SPEED 缩放）——把原生锁也改 ÷SS 属独立平衡决策，本轮不做。
- **其余不映射**：各学派 `*_spell_power` 折进通用 SPELL_POWER 语义错（学派加成 buff 一切）；`mana_regen` 无属性归处（回蓝是配置驱动）；`spell_resist`/各系抗性无受击减伤钩子（要做是新增 SPELL_RESIST 属性 + 魔法受击减伤钩子的新机制，非兼容映射）；`casting_movespeed`/`summon_damage` 无归处。

**为什么**：缩减与 SPELL_SPEED 天然同构（都是"除以速度"），是最低成本、语义最干净的两条；其余要么折进去语义错、要么需要新增机制，超出"装备属性兼容"范畴。

**影响**：`IronSpellsAttributes.mapType` +2 映射（`AttributeRegistry.COOLDOWN_REDUCTION`/`CAST_TIME_REDUCTION`）、javadoc、`docs/modules/npc.md`、`architecture/packages/compat.md`。

## 2026-08-27：法杖/卷轴重定价——可拆卸调换的永久 buff 不再是"死亡即消失"的一次性投入

**需求**（用户指令）：按 `balance/` 经济模型，现有法杖/卷轴造价太便宜——前期法杖只占该档日收入的 0.8%~2%，"买杖"是零钱不是决策；只有 lv30 毕业杖约一游戏日、用户认可。要贵一点，分析并落地合理幅度。

**决策**：
- **倍率阶梯**：lv1 ×5、lv5 ×4、lv10 ×2.5、lv20 ×1.8、lv30 ×1.2（用户拍板按推荐曲线）。单支总价 lv1 450→2,400、lv5 3,000→12,000、lv10 12~13k→30,000、lv20 50~60k→90~105k、lv30 150~180k→180~215k。
- **锚点**：每个元素组件 ≈ 0.7~3 天该元素日收入（回到 2026-08-26「攒大半天到一天」的原始意图，那批数值实际只有 0.3~0.5 天，属未达标的坑）；单支总价占该档日总产 4%~25%。lv30 按用户判断"一游戏日就好"基本保持（×1.2 微调）。
- **卷轴**：保持同档法杖约 1/2（消耗品，每法师一张教一个魔法），lv1 225→1,200、lv10 6,500→15,000、lv20 27,500→47,000。

**为什么**：法杖设计时是"死亡即消失"的一次性风险投入，现在随时拆卸调换、全队共用——同一价格下价值翻倍，等价变相降价；前期杖 450 只有 tavern1（3,080）的 15%、招募（10,000/元素）的 4.5%，不构成任何取舍。提价安全：新 NPC 自带免费 `basic_wand`，12 支预设杖纯属可选成长，不会卡死新手。

**影响**：12 个法杖 + 8 个卷轴 `craft_recipes/*.json` 造价、`docs/data/craft_recipes.md`、`docs/modules/wand.md`（成本锚点说明）。测试无造价断言，不需改。

## 2026-08-27：NPC/游客水中两栖寻路——不再因落水卡死触发传送兜底

**需求**（bug 报告）：NPC 和游客落水后难以正常寻路移动——要么站在水里不动/反复触发 rescue 传送，要么一渡河就被强制传送。

**根因**（两层）：
1. **纯陆地寻路器**：`WandscapeNavigation` 继承 `GroundPathNavigation`、用 `WalkNodeEvaluator`。该评估器只探索水平邻居、没有水中垂直邻居——较深的水里无法向上/向岸寻路，`moveTo` 失败，落入传送兜底（NPC self_teleport 仪式 / 游客 rescue teleport）。`GroundPathNavigation` 的水面路径也只在 `canFloat=true`（由 `FloatGoal` 隐式开启）下成立。
2. **硬超时与慢移动不匹配**：原版陆地生物水中水平移速 ≈ 0.02×移速/0.2 ≈ 0.6~1 格/秒，而 NPC `PATHFIND_TIMEOUT=200tick`、游客 `totalNavTicks>600` 硬上限——任何稍长的渡水都在到达前被强制传送。

**决策**：
- **两栖寻路器**（`WandscapeNodeEvaluator extends AmphibiousNodeEvaluator`）：保留陆地语义（继承自 `WalkNodeEvaluator`）的同时提供水中垂直邻居（可上浮/爬岸）；把 `AmphibiousNodeEvaluator` 的 WALKABLE 代价 6.0 恢复为 0（中立）——殖民地 NPC 是走路生物，只把水当作可通行地形，不偏向游泳绕行。
- **显式 `setCanFloat(true)`**：`WandscapeNavigation` 构造器与 `createPathFinder` 都开启，不再依赖 `FloatGoal` 的注册顺序。
- **水中移速**：NPC 与游客 attribute supplier 加 `WATER_MOVEMENT_EFFICIENCY=1.0`（接近陆地速度）。
- **水中放宽硬超时**：NPC `PATHFIND_TIMEOUT` 在 `npc.isInWater()` 时跳过（改靠 STUCK 卡死进度三连兜底，真正卡死仍会被传送）；游客 `totalNavTicks` 硬上限同样在水中跳过（只认 `noMoveTicks` 水平不动）。

**为什么**：修正寻路根因（评估器）而非继续堆传送兜底；「慢但前进」的渡水是合法行为，不应被固定超时判死。速度属性选 `WATER_MOVEMENT_EFFICIENCY`（原版机制）而非自定义水中加速度，避免另起一套移动逻辑。

**影响**：新增 `WandscapeNodeEvaluator`；`WandscapeNavigation` 换用 + 强制 canFloat；NPC/游客各加 `WATER_MOVEMENT_EFFICIENCY` 属性；`NavigationSystem`/`TouristMoveGoal` 三处硬超时水中放宽。陆地寻路行为不变（评估器继承陆地逻辑、代价中立）。

## 2026-08-27：光束施法期间横移走位 + 施法姿态与光束同步

**需求**（用户实测）：1) 殖民法师释放光束时几乎不走位——光束是 12 秒持续效果，整个期间站桩当固定炮台；2) 施法姿态比光束持续时间短，目标死亡后光束残留在原地几秒才消失（NPC 已放下手走开，光束还冻在死者位置）。

**根因**（三点叠加）：
1. **安全距离分支主动站桩**：`GuardCombat.engage` 在 LOS 通且目标 ≥ `guard.kiteStartDist`(9) 时 `cancelNpcNavigation` 钉住 NPC；风筝只在怪进入 9 格触发，远程怪时整根光束一次不动。
2. **光束每 tick 抢转向**：`MagicBeamEntity.trackTarget` 强制 `casterNpc.faceTarget(aim)`，与 `MoveControl`（MOVE_TO 靠设 yRot + 前进输入寻路）每 tick 互抢 yRot、后 tick 者赢——即便走位被触发，路径也会被掰回目标方向。
3. **光束活过战斗结束**：`SelfDefenseExecutor` / `EvilMageCastGoal` 战斗结束不淡出光束（守卫 `GuardAttackExecutor` 已 `setLifetime(5)`），目标死亡后光束冻结在死者位置继续渲染剩余寿命（最多 ~11s）；且施法姿态 `manualCast`（锁 = 全程一半 = 120t）比光束（240t）短。

**决策**：
- **光束持续期间横移走位**（`GuardCombat`）：`engage` 安全距离分支 `beam != null` → `strafe`（沿以目标为圆心、`standoff` 半径圆周、按 `npc.strafeDir` 方向 30° 步进的可站立落点，可达性约束与 `findRetreatPos` 同款：可站立 + NPC→落点 无墙 + 落点→目标 有 LOS）；`beam == null`（法阵引导/两发之间）才站定瞄准再施法。`strafeDir` 每新发一束交替，避免始终同向绕圈漂移。落点不可用（贴墙）静默站定继续打。
- **走位期间不强制转向**（`MagicBeamEntity.trackTarget`）：`faceTarget` 仅当 `casterNpc.getNavigation().isDone()`（静止）时执行，否则交给 `MoveControl` 转向；光束源点仍每 tick 跟随持杖手，输出不受影响。
- **施法姿态拉满到光束全程**（`MagicCaster.castNpcBeam`）：`tryCastSpell`（机械锁仍保持减半 120t）后追加 `startManualCast(全程 240t)`——视觉上举杖施法与光束同生同灭；战斗结束由 `GuardCombat.markCombatEnd → npc.endManualCast()` 落姿，避免战后站姿残留。
- **战斗结束淡出光束**：`SelfDefenseExecutor`（peace 无威胁 / target 为 null 两完成分支）与 `EvilMageCastGoal.stop()` 补 `beam.setLifetime(5)`，与守卫一致。

**为什么**：光束是长持续效果，站桩 12s 是活靶子，「边走边打」是远程施法者的标准生存手段，且光束独立实体 + `suppressWandering` 放行天然支持移动施法——零新移动机制，纯行为补充。施法同步的**真正根因**是光束活过战斗结束（自防御不淡出），淡出光束即两者同灭；姿态拉满只补视觉（举杖到光束消失），机械锁保持减半不阻塞 L0 危机自奶（2026-08 施法锁减半决策的边界不破）。

**影响**：`GuardCombat` 增 `strafe`/`findStrafePos`/`STRAFE_STEP`；`WandscapeNpc` 增瞬态 `strafeDir` + `endManualCast()`；`MagicBeamEntity.trackTarget` faceTarget 门控；`MagicCaster` 姿态拉满；`SelfDefenseExecutor`/`EvilMageCastGoal` 战斗结束淡出光束。

## 2026-08-26：NPC 盔甲受击扣耐久——独立 armorInventory 手动结算

**需求**（用户指令）：NPC 装备栏的护甲耐久从不被消耗，要求按受击正常磨损。

**根因**：NPC 的 4 盔甲格存于独立 `armorInventory`（`SimpleContainer`）、不进 vanilla 装备槽（防外观覆盖巫师袍）；原版耐久结算在 `LivingEntity.getDamageAfterArmorAbsorb → hurtArmor → doHurtEquipment`，其中 `doHurtEquipment` 按 `getItemBySlot(EquipmentSlot)` 读取物品——`PathfinderMob` 的 `hurtArmor` 是空实现，且即便有实现也够不到 `armorInventory`，故耐久永不消耗。

**决策**：
- `WandscapeNpc` 覆盖 `hurtArmor(DamageSource, float)`：按原版语义每次受击每件扣 `max(1, ⌊damage/4⌋)`，逐件走 `ItemStack.hurtAndBreak`（免费吃耐久/经验修补附魔，破损 shrink 空槽并广播破坏事件）。该钩子在伤害**不绕盔甲**（非 `bypasses_armor`）时由原版 `getDamageAfterArmorAbsorb` 调用，与数值减伤同边界——绕盔甲伤害（如原版 `magic`/`indirect_magic`）不减伤也不掉耐久，与原版一致；Wandscape 光束 `beam.json` 不在 `bypasses_armor`，正常磨损。
- **破损后重算属性**：某件破损（槽空）后立即 `syncArmorAttributes()` 把该槽 `unequip`，ECS 护甲加成/铁魔法属性随之撤销，下一 tick `applyEffectiveAttributes` 推送 vanilla `Attributes.ARMOR`——否则破损前加的减伤会一直残留。

**为什么**：覆盖 `hurtArmor` 是原版耐久结算的确切接缝（而非事件监听），自动继承「绕盔甲伤害不掉耐久」边界；盔甲不在 vanilla 槽，`doHurtEquipment` 够不到，只能在此手动结算。

**影响**：NPC 盔甲（含建镇赠送铁套）随战斗受损、会磨坏——铁套不再是永久，仍是开局一次性免费增益（见 2026-08-16「初始法师赠送铁套」）。旧存档受损盔甲照常累计。

## 2026-08-26：铁魔法伤害倍率去重——SPELL_POWER 只在伤害入口乘一次

**需求**（bug 报告）：玩家实测铁魔法伤害异常高——10.5 基础伤害的魔法飞弹，3.0 法术强度无套装 NPC 应打 31.5 却打出 91.4；3.5 → 应为 36.75 却打 130.6；5.0（湮灭法杖）→ 应为 52.5 却打 257.3。伤害随法术强度二次方增长，而 Wandscape 原生魔法（光束/陨石）完全正常。

**根因**：同一份铁魔法伤害被 SPELL_POWER 连乘两次。铁魔法 `DamageSources.applyDamage` 先发 `SpellDamageEvent`（`compat` 包的 `IronSpellsDamageHandler` 在此乘一次），随后仍调用 `target.hurt()` → 触发 `LivingIncomingDamageEvent`（`guard/NpcSpellPowerHandler` 又乘一次）。原生魔法只走 `LivingIncomingDamageEvent` 一次，故正常。观测值 ≈ `基础 × SPELL_POWER² × 魔力强化²` 与实测完全吻合。

**决策**：删除 `IronSpellsDamageHandler`，铁魔法伤害倍率统一由 `NpcSpellPowerHandler` 在 `LivingIncomingDamageEvent` 入口乘一次（该入口本就是「光束/未来法术/铁魔法」的统一倍率点，还一并承载友军边界与和平模式，见「殖民地友军名单」条目）。`SpellDamageEvent` 端不再有任何倍率监听。

**为什么**：`NpcSpellPowerHandler` 是所有 NPC 伤害的必经入口（铁魔法所有伤害经 `applyDamage → hurt()`），且友军/和平边界必须在它这里取消伤害——倍率若移到 SpellDamageEvent 端，仍无法让铁魔法绕过边界，只会把边界与倍率拆到两处。删除冗余监听是最小且不散落的修法。

**影响**：删除 `compat/ironspellbooks/IronSpellsDamageHandler.java` 及注册；`IronSpellsCompat.init` 不再注册事件；`NpcSpellPowerHandler` javadoc 与 `architecture/packages/compat.md` 增「勿在 SpellDamageEvent 端重新乘倍率」的契约说明。修复后铁魔法伤害回归线性：`基础 × SPELL_POWER × 魔力强化`。

## 2026-08-26：跟随攻击 + 伤害边界放宽为非友军

**需求**（用户指令）：1) 跟随模式的 NPC 现在玩家打怪后它不帮忙——要求像原版狼一样，玩家攻击的目标让跟随 NPC 获得仇恨并攻击；2) 之前「各种魔法只能打 `Enemy`」判定为设计失误太窄——改为**友军名单管什么不能打，其他的都能打**（友军 = 玩家 + 同殖民地 NPC/铁魔法随从/游客），主动索敌/锁定目标保持 Enemy 不变，只是可伤害对象变广。

**决策**：
- **伤害边界唯一钩子放宽**：`WandscapeNpc.canBeamHurt` 由「Enemy \|\| 当前仇恨目标」改为 `!isFriendlyForce(target)`。下游光束（`MagicBeamEntity`）、统一伤害入口（`NpcSpellPowerHandler`，铁魔法 AoE 也走它）、陨石溅射、虚弱力场、L2 普攻全自动跟随——此前逐处「`Enemy \|\| canBeamHurt` + 友军前置排除」的结构在放宽后塌缩为「非友军」，冗余的窄判定一并清理。玩家施法光束保持只伤 Enemy（玩家自身行为，不受 NPC 友军边界管辖）。
- **敌数快照保持「战斗威胁」口径**而非「非友军」：`GuardCombat.countEnemies` = 半径内 Enemy（排除友军，己方亡灵随从不计）+ 非 Enemy 的当前战斗目标（跟随攻击目标/受击仇恨/敌对法师的生存玩家目标）——否则放宽后殖民地里村民会让 AOE 门控（敌数≥3）恒开、NPC 为打一只怪甩陨石溅射全村。**主动索敌不动**：`isHostileTarget`、守卫/自防御 Enemy 扫描、陨石重瞄、感化（conversion）均保持 Enemy 目标选择（conversion 回退上一个人放宽的非友军选取）。
- **跟随攻击（原版狼 OwnerHurtTarget 行为）**：新增 `FollowAttackHandler`（`LivingIncomingDamageEvent`）——玩家攻击生物时，把该生物标记为「跟随该玩家且非和平/非休息」的殖民地 NPC 的跟随战斗目标；`SelfDefenseExecutor.resolveTarget` 优先消费，复用整套战斗引擎。目标有效性：跟随开、未过期（`guard.followAttackDurationTicks` 默认 300，玩家再攻击刷新）、存活、在 `guard.hateRange`(48) 追击范围内、非友军。友军名单内的目标绝不标记（玩家打自己人，跟随 NPC 不参战）。
- **EvilMage 保测试语义**：`canBeamHurt` 覆盖为 `super（非友军）\|\| 生存玩家`——沿用新边界但保住「能伤生存玩家」的实战测试能力。

**为什么**：友军名单是既有唯一边界（仇恨/伤害/索敌统一走它），「非友军可打」是它的自然延伸——再套 Enemy 反而造成「NPC 能打打不过的目标」或「帮玩家打怪却打不出伤害」的割裂。主动索敌保持 Enemy 是为防 NPC 无端扫射平民；可伤害放宽只影响「已交战/玩家点名」的目标，不改变主动行为。跟随攻击复用自防御链路而非另起原版目标 goal，避免第二套战斗路径。

**影响**：`canBeamHurt` 放宽为非友军；`countEnemies` 改「Enemy + 当前战斗目标」口径；新增 `FollowAttackHandler`、`WandscapeNpc` 跟随战斗目标态、`guard.followAttackDurationTicks`；`SelfDefenseExecutor.resolveTarget` 跟随目标优先。**后果**：村民/动物/异殖民地 NPC 不再是 NPC 伤害的天然豁免——友军名单成为唯一保护（要额外保护需扩展 `isFriendlyForce`）。

## 2026-08-26：铁魔法装备属性桥接到 NPC 属性系统

**需求**（用户指令）：铁魔法装备穿到 NPC 身上后其独有属性加成不生效（流浪法师兜帽 +25 最大法力、+5% 法术强度、速度靴 +25% 移速）。要求这些对到 Wandscape 的魔力值/法术强度/移动速度；各学派法术增强与施法时速度提升等铁魔法特色属性**不兼容**。

**根因**：NPC 的 4 盔甲格走独立 `armorInventory`、不挂 vanilla 装备槽，`ExtendedArmorItem` 写在 `ItemAttributeModifiers` 里的加成从不被 vanilla 属性系统结算；`syncArmorAttributes` 只把 `Attributes.ARMOR` 抄进 ECS，其余属性全丢。

**决策**：
- **core 增加百分比乘区 `MULTIPLY_BASE`**：`ModifierOperation` 变为 `{ADDITION, MULTIPLY_BASE}`；`EquipmentComponent.recalculateAll` 按 vanilla 顺序 `effective = (base + ΣADDITION) × (1 + ΣMULTIPLY_BASE)`。**不用一次性折成加法**——NPC base 会被法师小屋训练/复活经 `seedBaseValues` 重新播种，折成加法的 +25% 移速会失真（0.3×0.25=0.075 固定，基础升到 0.36 时应为 0.09）。无乘区时退化为纯加法，现有 12 支法杖（全 addition）零影响。
- **映射放 compat 且只映射三条**：新增 `IronSpellsAttributes.modifiersFor(stack)` 遍历 `ItemAttributeModifiers`——`MAX_MANA`→`MAX_MANA`（ADD_VALUE→ADDITION）、`SPELL_POWER`→`SPELL_POWER`（ADD_MULTIPLIED_BASE→MULTIPLY_BASE）、vanilla `MOVEMENT_SPEED`→`MOVE_SPEED`（ADD_MULTIPLIED_BASE→MULTIPLY_BASE）。各学派 `*_spell_power`/`casting_movespeed`/`mana_regen`/`cooldown_reduction`/`cast_time_reduction`/`summon_damage`/各系抗性等铁魔法特色属性**不映射**（用户明确不兼容）。
- **syncArmorAttributes 合并**：每盔甲槽修饰符列表 = `[ARMOR_VALUE] + 映射结果` 一次 `eq.equip`；空槽 `unequip` 自动撤销。法力上限/法术强度/移速提升经既有链路生效（`getEffectiveAttribute`/`applyEffectiveAttributes`/伤害倍率），GUI 显示自动跟新。

**为什么**：加乘区是唯一正确做法（base 可变 + 百分比语义）；映射收敛在 compat 保证零硬编码耦合（未装铁魔法返回空列表）；只映射可对到 Wandscape 属性的三条，不把铁魔法特色机制塞进我们的属性模型。

**影响**：`ModifierOperation`/`EquipmentComponent`（乘区公式，+6 个纯逻辑单测）；新增 `compat/ironspellbooks/IronSpellsAttributes`；`WandscapeNpc.syncArmorAttributes` 合并映射；`core.md`/`npc.md`/`compat.md` 文档同步。

## 2026-08-26：殖民地友军名单——NPC 不记仇、不伤害友军（含铁魔法）

**需求**（用户指令）：铁魔法兼容后，NPC 的铁魔法（AoE/溅射/召唤物）会打到其他 NPC 与玩家，且同一殖民地的 NPC 被友伤后互相记仇、彼此开战。要求「每个殖民地有一个友军名单，NPC 不对名单内生物记仇，所有攻击也不伤害名单内生物」。

**根因**（三个叠加）：
1. **铁魔法伤害绕过 `canBeamHurt`**：Wandscape 原生魔法（光束/陨石/普攻/虚弱）在**施法前**按 `canBeamHurt` 过滤目标；铁魔法由 Iron's 库内部结算伤害（`SpellDamageSource`，causing=施法 NPC），不检查此边界——AoE/弹射物/召唤物打到谁算谁。
2. **`isRetaliationTarget` 布尔写反**（b33a5540 引入）：`if (other instanceof WandscapeNpc other) return sameColonyAs(other)` 对同殖民地返回 `true`（=可反击），与「仅排除玩家与同殖民地 NPC」的意图相反。于是同殖民地 NPC 被友伤后记仇（`SelfDefenseHandler`）→ 仇恨目标被 `canBeamHurt` 放行 → 互相打。
3. **`NpcSpellPowerHandler` 只跳过倍率、不取消伤害**：对非 `Enemy`/非 `canBeamHurt` 目标 `return` 只是不乘 SPELL_POWER，基准伤害仍落地。

**决策**：
- **友军名单派生而非存储**（`core/types/FriendlyForce`，纯 Java 零 MC）：同 `colonyId` 的 NPC + **所有玩家**（用户确认：与既有魔法一致，NPC 永不伤任何玩家，不区分殖民地拥有者）。`null`/占位殖民地互认为同殖民地。`PLACEHOLDER_COLONY` 常量收拢至此，`EntityComponentBridge` 别名引用。
- **仇恨与伤害统一走 `WandscapeNpc.isFriendlyForce`**：`isRetaliationTarget = !isFriendlyForce`（修掉布尔写反——同殖民地与玩家不记仇；不同殖民地 NPC 属非友军，仍可按此反击）。`SelfDefenseHandler` 记仇、`SelfDefenseExecutor` 仇恨分支、`canBeamHurt` 三处行为随之修正。
- **伤害入口取消友军伤害**：`NpcSpellPowerHandler.onLivingDamage` 对非 `Enemy` 且非 `canBeamHurt` 的目标、及和平模式，从「跳过倍率」改为 `event.setCanceled(true)` 整伤取消。这是所有 NPC 伤害（含铁魔法与召唤物，causing 恒为施法 NPC）的唯一入口，铁魔法不再能绕过边界。

**为什么**：铁魔法伤害入口散在 Iron's 库内无法逐一挂钩，而所有伤害最终都经 `LivingEntity.hurt()` → `LivingIncomingDamageEvent`，在唯一核算入口设边界是代价最小且不散落的做法。`canBeamHurt` 仍是唯一伤害边界钩子（光束/倍率/敌数/伤害入口共用），友军名单只新增一个纯函数来源。

**影响**：新增 `core/types/FriendlyForce`（+`FriendlyForceTest` 决策表 7 例）；`WandscapeNpc` 增 `isFriendlyForce`、修 `isRetaliationTarget`、删 `sameColonyAs`；`NpcSpellPowerHandler` 改取消；`EntityComponentBridge` 常量别名。铁魔法/召唤物打到友军与玩家不再结算，同殖民地 NPC 不再互开战。

## 2026-08-26：友军名单扩展——同殖民地召唤物与游客

**需求**（用户指令 + 实测反馈）：NPC 用铁魔法「驱役亡灵」（`irons_spellbooks:raise_dead`）召唤的亡灵随从会被自己/守卫攻击。召唤物是原版 `Skeleton`/`Zombie`（`Enemy`）；铁魔法的友伤免疫（`IMagicSummon.shouldIgnoreDamage` → `isFriendlyFireBetween`）只对 **Player 施法者**生效（双方都解析成 Player 走 `canHarmPlayer` 分支 → 免疫），对 NPC 无效——非 Player、无 scoreboard 队 → 兜底 `isAlliedTo`（原版纯队伍判定）恒 false → 免疫失效。且我方索敌/伤害全看 `Enemy`，召唤物恒被锁定。要求把「自己/同殖民地 NPC 召唤的」亡灵加入友军名单，并把同殖民地游客一并加入避免溅射误伤。

**决策**：
- **`FriendlyForce` 增两类**：`AllyKind.MAGIC_SUMMON` / `TOURIST`，均按 `sameColony` 判定（与 `WANDSCAPE_NPC` 同语义，switch 归并）。
- **`isFriendlyForce` 增分支**：`IMagicSummon` → `getSummoner()` 若为 `WandscapeNpc` 则比其殖民地（`instanceof` 前置 `IronSpellsCompat.isLoaded()` 守卫——未加载时类不在类路径，直接 instanceof 抛 NCDFE）；游客经新增共享标记 `shared/entity/ColonyVisitor`（暴露 `getColonyId()`，`TouristEntity` 实现）判定——用共享标记而非 npc 模块直接引用游客实体，遵守模块隔离。玩家/非本模组施法者的召唤物不在豁免范围。
- **`canBeamHurt` 前置友军判定**：友军（含 `Enemy` 召唤物）永不受伤——此前 `instanceof Enemy` 分支短路放行了己方亡灵随从。
- **伤害/索敌入口逐处过滤友军**：`MagicBeamEntity.canDamage`、`NpcSpellPowerHandler`（统一伤害入口，铁魔法 AoE 也走它）、陨石溅射、NPC 陨石重瞄/虚弱/感化 AoE、守卫普攻/敌数快照/群殴扫描、自防御索敌、守卫区扫描（`GuardScanner` 增带 `Predicate` 重载）、守卫触发（`WandscapeNpc.isColonyNpcSummon` 过滤殖民地随从，避免发布后立即 stand-down 空转）。
- **自防御仇恨**：召唤物打伤 NPC 时 `isRetaliationTarget = !isFriendlyForce` 自动不记仇（无需额外改动）。

**为什么**：铁魔法免疫只保护 Player 施法者，NPC 恰好在其外；友军名单是既有唯一边界（仇恨/伤害/索敌统一走它），把召唤物/游客按「召唤者/游客所属殖民地」归并进同一派生，边界不散落。EvilMage 等非殖民地施法者的召唤物不在豁免范围（`isColonyNpcSummon` 仅用于守卫触发，区分敌对施法者）。

**影响**：`FriendlyForce` +2 类（`FriendlyForceTest` +2 例）；新增 `shared/entity/ColonyVisitor`；`WandscapeNpc.isFriendlyForce`/`canBeamHurt`/`isColonyNpcSummon`；光束/陨石/虚弱/感化/普攻/守卫/自防御逐处过滤；`GuardScanner` 增重载；`GuardTaskSource` 过滤殖民地随从触发。

## 2026-08-26：安全传送落点与 3D 导航到达判定修复（杜绝矿洞悬空坠亡与进墙传送死循环）

**需求**（用户反馈）：NPC 会传送至施工地点周围，但落点可能在墙内导致掉入矿洞顶部悬空坠亡，且坠亡后任务回滚导致下一个 NPC 循环送命；若未摔死则在矿洞内反复原地传送死循环。

**根因**：
1. **安全落点算法存在悬空漏洞**（`WandscapeRitualOps#findSafeLanding`）：第二遍放宽搜索（`requireGround = false`）时未校验脚下支撑面，头脚两格为空气即可通过，向上遍历时触及矿洞/空腔洞顶下方即判定成功，将 NPC 传送到数十格高的半空中引发坠亡。
2. **安全落点失败时强行回退 raw target**：找不到安全地面时直接塞进实心目标坐标，使 NPC 埋入墙体/地下。
3. **导航到达判定忽略垂直高度差**（`NavigationSystem#update`）：到达只算水平距离平方（`hDistSq <= 25`），导致 NPC 在地下矿洞（相隔数十格垂直高度）时被误判为到达，随后执行操作再次因不可达寻路失败，重新传送到矿洞原地死循环。
4. **施工寻路直接以实心方块为目的地**：原版寻路进实心方块必定返回 false 触发传送。

**决策**：
- **安全落点坚决杜绝悬空**：`isSafeLanding` 无论是严格双层实心还是宽松模式，脚底 `ground` 必须有物理碰撞箱支撑（`!getCollisionShape().isEmpty()`）、无液体且非伤害/下陷方块（岩浆/火焰/仙人掌/细雪等），绝对禁止脚底为空气时传送；落点搜索按垂直高度差由近及远遍历。
- **无安全落点时放弃传送**：`findSafeLanding` 找不到安全落点时返回 null，`executeRitual` 取消本次传送并重置导航，严禁强行传送至实心方块内。
- **3D 到达判定与高度门控**：`NavigationSystem` 到达判定增加垂直高度差要求（`dy <= 3.5`）；长距离传送门控增加高度差（`dy > walkThreshold`）。
- **实心方块寻路目的地修正**：`NavigationSystem#resolveWalkTarget` 检测到目标方块为实心时，自动将寻路终点上移至其上方空位，避免原版寻路必定报错。
- **任务执行器 3D 范围检查**：`TaskExecutionSystem` 触发单步导航的范围判定补充垂直高度差（`dy > 4.0`），避免高度悬殊时错误跳过导航。

**影响**：`WandscapeRitualOps`（安全落点算法重构 + 悬空与危险方块防护 + 取消无效传送）；`NavigationSystem`（3D 到达校验 + `resolveWalkTarget` 目标修正）；`TaskExecutionSystem`（3D 距离范围检查）。

## 2026-08-26：Wandscape × Iron's Spells 'n Spellbooks（铁魔法）全面兼容

**需求**（用户指令）：使 Wandscape 的 NPC 法师能够装备并施放铁魔法（Iron's Spells 'n Spellbooks）的法术。玩家可以把任意铁魔法卷轴放入 4 大分类（单体/群体/防御/支援）的任意门类中，施法时从大类遍历，按优先级 + CD=0 释放。

**决策**（通过 grill-me 决策树对齐）：
- **数据与等级存储（Q1 A）**：`EquippedMagicComponent` 升级存储 `SpellEntry(id, level, customData)`，支持带 `@level` 字符串编解码。放进 5 级铁魔法卷轴即按 5 级施法，取下/法师战死掉落时无损还原原等级的铁魔法卷轴。
- **大类语义与目标判定（Q2 A）**：由放入的大类决定触发时机与目标模式。`single_target`/`aoe` 面向敌对发射（AOE 需敌数 ≥ 2）；`defense` 在处于战斗且自身血量 < 80% 时释放；`support` 在友方/自身血量 < 80% 时释放。
- **施法生命周期桥接（Q3 A）**：瞬发（`INSTANT`）占 0.5s 施法互斥锁；蓄力（`LONG`）与持续（`CONTINUOUS`）法术按 `castTime / SPELL_SPEED` 占用互斥锁，每 server tick 维持面向与 `onServerCastTick`，到期触发 `onServerCastComplete` 并播放法术音效。
- **魔力与冷却主控管线（Q4 A）**：直接从 NPC 扣除 `spell.getManaCost(level)`（**1:1，2026-08-26 用户要求**，不再按 0.25/0.10 缩放与下限钳制——昂贵的铁魔法如黑洞 300/传送门 200 超过 NPC 默认蓝池 200，经 CastBrain 门控自动跳过）；冷却 `baseCooldown = spell.getSpellCooldown()`（该 API 已返回 tick，`COOLDOWN_IN_SECONDS × 20`，不再乘 20），由 `MagicState` 记录并享受 `SPELL_SPEED` 冷却缩减（`baseCooldown / spellSpeed`）；底层同步至 `MagicData`。**所有类型（含持续引导 CONTINUOUS）都施法开始一次性扣全量**——铁魔法自身无按秒扣蓝机制，最初按 tick 扣的方案已回退（2026-08-26）。合并时未采纳 origin/main 的 `getAdaptedCooldown`（×0.08 + [20,200] 钳制）缩放方案。
- **法强放大与优雅降级（Q5 A）**：订阅 `SpellDamageEvent`，自动按 `SPELL_POWER × 魔力强化倍率` 放大伤害；所有实现隔离于 `compat/ironspellbooks/`，未安装铁魔法时不加载任何铁魔法逻辑，保持零硬编码耦合与高容错。

**影响**：`build.gradle`（compileOnly 铁魔法依赖）；`compat/ironspellbooks/`（`IronSpellsCompat`/`IronSpellsHelper`/`IronSpellsCaster`/`IronSpellsDamageHandler`）；`EquippedMagicComponent`（`SpellEntry` 结构化支持）；`NpcStrategyMenu`（支持放置铁魔法卷轴）；`CastBrain`（支持合成 `MagicDef` 与 `EquippedMagicComponent` 查询）；`MagicSpellExecutors`（`dispatch` 桥接 `IronSpellsCaster`）；`Wandscape`（生命周期接线）。

## 2026-08-26：保卫殖民地复活——阵亡于建筑附近者直接在市政厅门口复活，复用全灭保底

**需求**（用户指令）：法师战死若发生在守卫殖民地期间（距最近建筑 ≤20 格），不应强制走祭坛复活仪式，而是与全灭保底一致直接在市政厅门口复活，复用既有机制。

**决策**：
- **判定**：阵亡坐标到**本殖民地**任一建筑 AABB 的 **3D 欧氏距离** ≤ `Config.REVIVE_NEAR_BUILDING_RANGE`(20，`revive.nearBuildingRange` 可调)；点在盒内记为 0。只认本殖民地建筑，不跨殖民地捞建筑——「保卫自己的殖民地」。
- **复用全灭保底链路**：新增 `ReviveHandler.checkAndReviveNearColonyBuilding` 复用 `resolveTownHallDoorOrAnchor`（市政厅门口）+ `spawnFromRecordAt`（虚弱复活 1 血 0 蓝 + 失败保留记录可重试），不新写复活逻辑。挂在 `NpcDeathHandler` 存记录之后、全灭检测之前——复活后该法师存活，全灭检测自然不触发。
- **点到 AABB 距离** `distSqToAabb` 为纯逻辑静态方法（配单测），判定与守卫/袭击的「近建筑」语义一致但用 3D 距离而非水平外扩——高空坠落不误判为守卫战死。

**为什么**：守卫战死是高频且非玩家过错的事件，若每次都要求玩家跑祭坛仪式，防线一崩战力断档、生产停摆；而全灭保底已备好「市政厅门口 + 虚弱复活」整套机制，直接复用把新增复杂度压到最低（一个距离判定 + 一个入口）。

**影响**：`Config` 增 `revive.nearBuildingRange`(20)；`ReviveHandler` 增 `checkAndReviveNearColonyBuilding`/`isWithinRangeOfColonyBuilding`/`distSqToAabb`；`NpcDeathHandler` 存记录后先跑近建筑判定；新增 `ReviveHandlerTest`（点到 AABB 距离 6 例）。

## 2026-08-26：走位距离加大 + 低血逃跑态——参数移入 Config

**需求**（用户指令）：法师走位控距离太短，仍会被苦力怕炸、偶尔被近战打到。要求：① 增大走位距离；② 血量低于 30% 进入逃跑状态、走位距离更大，低血能跑远避免被打死。

**决策**（用户选定）：
- **数值**：`guard.kiteStartDist` 6→9、`guard.kiteStandoff` 10→13、`guard.engageStandoff` 6→9——最低间距 9 格，普通苦力怕爆炸致死半径 ~4（充能 ~8）与近战射程 ~3 都够不着，10 tick 重检间隙（怪能贴 ~2 格）也有余量；光束射程 200，拉远不影响输出。
- **低血逃跑态**（`guard.fleeHpThreshold`=0.30）：触发/后撤距离改用 `guard.fleeStartDist`=12 / `guard.fleeStandoff`=18；**LOS 被墙挡不再走近交战、继续后撤**（保命优先，墙后近战威胁大），光束边走边打仍在输出。L0 紧急奶（HP<50%）已在上游拦截可奶的低血，逃跑态是「奶不了」的兜底。
- **参数归属改为 Config（TOML）**：原硬编码 `KITE_START_DIST`/`KITE_STANDOFF`/`ENGAGE_STANDOFF` 移入 `guard.*`，并新增 flee 三参数——玩家改配置即可调平衡，不用改代码重编译。反之前「留 GuardCombat 私有常量」决策（2026-08-12）：用户反复实测调平衡，配置化更省事，代价是常量变配置读（每轮循环多两次 `Config.get()`，可忽略）。
- **实现**：`engage` 每轮按血量比例选「正常档 / 逃跑档」的 startDist/standoff（flee 分支同时覆盖风筝与群殴后撤）；`navigateAway` 增 standoff 参数（5 参重载按 `kiteStandoff`，供和平模式逃跑复用，其间距 10→13 顺带变大）；`findRetreatPos`/`findEngagePos` 参数化 standoff。

**为什么**：原 6→10 走位带中，法师常停在 6~7 格（`ENGAGE_STANDOFF`=6 与风筝触发 6 重叠，站定施法时怪贴上来），普通苦力怕 6 格爆炸仍有可观伤害、充能苦力怕更致命。9→13 让最低间距超出爆炸致死半径；逃跑态 18 格连充能苦力怕都安全，LOS 被挡也后撤避免低血法师走近墙后近战自杀。

**影响**：`Config` 增 `guard.kiteStartDist`/`guard.kiteStandoff`/`guard.engageStandoff`/`guard.fleeHpThreshold`/`guard.fleeStartDist`/`guard.fleeStandoff`；`GuardCombat` 删 3 个私有常量、`navigateAway` 增 standoff 重载、`findRetreatPos`/`findEngagePos` 参数化、`engage` 增逃跑态分支。

## 2026-08-26：合成队列改为「发布时找第一个够的」——缺元素配方留在原位标记，不再进不可见的 AWAITING_RESOURCES

**需求**（用户指令）：合成时元素不足的配方会进入一个 UI 看不见的「候选列表」（`AWAITING_RESOURCES` + `parkHead` 挂起），像"丢失"一样。期望更简单的模型：合成列表从上到下找第一个元素够的来合成；不够的留在原位、UI 显示「缺元素」；都不够则 NPC 自然空闲；并自动去收集不足的元素。

**根因**：`BuildingTaskSource` 每 20 tick 只把队首条目发布为头任务；执行到一半元素不足 → `markAwaitingResources`（休眠不可见）→ `parkHead` 让出队首、顶上下一条。被挂起的任务在 UI 里完全消失，且只有 `ResourceSupplySystem`/`onResourceAdded` 唤醒后才会回来，玩家无法感知、也无法操作。

**决策**（发布时预判 + 缺元素留队 + 中途回收）：
- **发布扫描**：`BuildingTaskSource` 发布前用新增 `BuildingApi.dequeueWorkEligible` 从队首到队尾找第一个"当前元素够"的生产配方发布；元素不足的留在队列原位被跳过。可负担性判定收敛到 `engine/boundary/ProductionEligibility`（`requiredElements` = 配方成本 × 数量 × `ELEMENT_CRAFT_COST_MULTIPLIER`，与执行器 `checkElements` 同源），供发布扫描、UI 标记、自动补元素三处复用。
- **都不够 → 不发布**：相关 NPC 自然空闲（无新状态机）。先判 eligible 再 `leaseBuilding`，避免为"排队但跑不了"的队列每 20 tick 强制加载/释放区块。
- **中途竞态回收**：发布后元素被并发任务抢走 → 执行器照旧抛 `ResourceShortageException`；`BuildingTaskSource` 清理区对生产任务不再 `parkHead`，而是用 `GlobalTask.blueprintId/taskParams/priority` 重建 WorkItem 重新入队 + `cancelTask`，下轮扫描按「缺元素」跳过——全程可见。`AWAITING_RESOURCES`/`parkHead` 保留给建材运输等非生产任务。
- **UI**：`TaskQueueDataPacket.QueueEntry` 带 `insufficient` + `missingElements`，`TaskQueuePanel` 对不足行渲染暗红「缺元素」标签 + 缺失元素图标（保留上移/下移/删除）。
- **自动补元素**：`ResourceSupplySystem` 心跳在扫 `AWAITING_RESOURCES` 之外，新增扫描工作站系队列里元素不足的条目，按 (colony,typeId) 去重聚合缺口，走既有 `trySupplyResource`（先合成物品、回退节点采集，自带 in-flight 去重）。

**为什么**：生产短缺是常态经营分支，不该让任务"消失"。把"元素够不够"的判定从执行期（失败才知）提前到发布期（先判再发），NPC 只会接到当前能跑的任务，队列天然从队首推进；缺元素的留在原位、UI 可见、可手动移除，补齐后自然被挑中——模型只有一个概念："缺元素的任务留在队列里等元素"。

**影响**：`BuildingApi.dequeueWorkEligible` + `pollFirstEligible`（BuildingApiImpl）；`ProductionEligibility`（新增，engine/boundary）；`BuildingTaskSource`（发布扫描 + 清理区回收，`elementSnapshot` 取 `ServerLifecycleHooks` overworld）；`TaskQueueDataPacket.QueueEntry` 增字段 + `TaskQueueModifyPacket` 计算 + `TaskQueuePanel` 渲染 + 三个生产屏幕透传并加宽队列面板；`ResourceSupplySystem.scanProductionQueues`；`WandscapeBlockInteractExecutor.checkElements` 收敛到 `ProductionEligibility`；`Wandscape` 接线 `ProductionEligibility.setProductionRecipeLoader`。测试：`ProductionEligibilityTest`（新）、`BuildingApiSharedQueueTest`（pollFirstEligible 4 例）。`BuildingTaskPoolTest`/`TaskExecutionResourceShortageTest` 测的是通用 AWAITING_RESOURCES/park 机制，不受影响。

## 2026-08-26：建造落点吸附——草/花/蘑菇/树叶不算立足点，建筑落在真实地面

**需求**（用户反馈）：建造模式下建筑会吸附到草、花、蘑菇、树叶上，整栋建筑被垫高一层（影响高度）。

**根因**：落点取自射线命中方块 `placePos = hitPos.relative(hitDir)`。客户端 raycast 用 `ClipContext.Block.OUTLINE`（取方块 `getShape` 轮廓形状），而原版草本植物轮廓是整格方块、树叶是完整实体方块——射线命中植物顶面时 `hitDir=UP`，锚点就落在植物上方。

**决策**：新增 `projection/BuildPlacement`，命中不可立足的方块时沿该列向下找第一个真正可立足的方块，锚点落回其上方：
- **立足判定**（与 `road/network/DestroyFillPacket` 找真实地面口径一致）：跳过 `canBeReplaced()`（草/花/蘑菇/雪/水等）+ 空碰撞箱（无碰撞不可站立），额外显式排除 `minecraft:leaves` 标签（树叶碰撞箱完整、不可替换，但显然不是建筑落脚点）。
- **保留既有行为**：命中合规支撑（草方块/泥土/墙体/楼梯等）时仍贴命中面放置——含贴墙/侧面放置，不破坏墙边建造。
- `resolve(BlockPos, Direction, SupportTest, int)` 为纯逻辑核心（无 MC 依赖），`isStandable(Level, BlockPos)` 为 MC 实现；步行建造（ProjectionFlightController）与俯瞰建造（OverviewFlightController 每帧落点）两个入口共用。

**为什么**：用「可替换 + 碰撞箱 + 树叶标签」的通用判定而非枚举方块清单，数据驱动地覆盖所有植物/树叶变体（含模组方块走 `minecraft:leaves` 标签）；纯逻辑核心抽出便于单测 `resolve` 的下降分支。

**影响**：新增 `projection/BuildPlacement.java`；`ProjectionFlightController.updateGhostPosition`、`OverviewFlightController.updateGhostPositionPerFrame` 改用 `BuildPlacement.resolve`。测试：`BuildPlacementTest`（7）覆盖贴面放置/贴墙放置/植物下沉/穿透空气/树叶下沉/无立足点回退/世界底界。


## 2026-08-26：游客防卡死——卡死作废路径 + 传安全点，连续 3 次卡死直接传送到目标（不作废目标）

**需求**（用户指令）：`>100 tick 无移动`即视为卡死，此时**作废当前路径**并做之前的防卡死传送（传安全点）；连续 **3 次卡死 → 直接传送到目标点**，**不作废目标**。

**根因**：旧防卡死只搬人、不改计划——传送只重置计数器，失败的目标与路线原样保留，游客传送后沿老 waypoint 列表**重走同一个卡死点**。但「作废目标」又过头了：目标本身可达时，放弃会导致游客反复换目标、永远完不成行程。

**决策**（统一梯子，按物理卡死计数）：
- **卡死判定**：`noMoveTicks > 100 || totalNavTicks > 上限`（outdoor 600 / indoor 400 / POI 400），与既有判定一致。
- **每次卡死**：`nav.stop()` + 清 `outdoorWaypoints`/`currentWaypointIndex`（作废路径，D）+ `TouristTeleport.findSafeSpot` 传送到安全点（之前的防卡死传送）。目标保留，重寻路继续。
- **连续第 3 次卡死**（`stuckFallbacks` 计数）：`teleportToNavTarget` 直接传送到目标入口附近（outdoor/POI 走 `findSafeSpotNearEntry`，indoor 直接落 spot 后 `startActivityAtSpot`），让到达判定接管——**目标从不作废**。
- **计数清零**：目标到达（`switchToIndoorNav`）/ 切换目标（`beginNavigation`、`pickNextPoiAndGo`）/ 模式切换（`switchMode`）/ 传送目标成功，均清零。

**为什么**：卡死 = 当前位置动不了，传送是"治标"，但目标是"治好"的方向——传安全点让游客换一条路再试，连续 3 次仍到不了就强制送到目标完成行程。目标不可达时 3 次后传送到目标附近（`findSafeSpotNearEntry` 保证落在可站立点），不放弃、不重走老路。寻路失败（`nav.isDone()`）本身会让游客原地不动，自然落入本梯子，无需单独的寻路失败计数。

**影响**：`TouristMoveGoal.java`（`stuckFallbacks`/`STUCK_FALLBACK_TELEPORT_THRESHOLD`、outdoor/indoor/POI 硬兜底梯子、`teleportToNavTarget`/`teleportToIndoorTarget`、`switchMode`/`switchToIndoorNav` 清 waypoint）。室内去 spot 途中卡死由「放弃访问」改为同一梯子（第 3 次直接落 spot 开始交互）。

## 2026-08-26：12 支法杖 + 卷轴重定价；法杖 attributes[] 从"只解析不应用"改为"装备即生效"

**需求**（用户指令）：按 `balance/` 经济产出与 MageHut 各等级属性，设计 12 支有鲜明特色的法杖（工作/爆发/蓝量/血量/极速 + 牺牲型），1/5/10/20/30 级解锁；卷轴重定价（同档法杖约 1/2）；卷轴解锁改 1/10/20 三档。补充要求：Lv20 工作杖叫「工匠法杖」（Lv1 改「木工法杖」）、法杖进创造栏、卷轴/法杖补 i18n。

**决策**：
- **12 支法杖**：Lv1 木工/学徒；Lv5 烈焰/工坊；Lv10 铁壁/秘泉/疾风；Lv20 工匠/堡垒/奥术；Lv30 湮灭/创世。属性为纯加法（`EquipmentComponent` 只支持加法），数值锚定该级中位数 ±20%~60%（如 Lv30 湮灭 +2.0 强度 = 玻璃炮）。牺牲型只在 Lv20/30（Lv1-10 纯加成，教学友好）。
- **成本锚点**：单支总价 ≈ 该档日收入 3%~40% 递增（Lv1 ~450 → Lv30 ~165k），元素成本避开单元素瓶颈（Lv1-10 少用金/暗，Lv20+ 才放开）。卷轴 = 同档法杖约 1/2（消耗品，每法师一张教一个魔法）。
- **修 bug：法杖属性从不应用**。此前 `attributes[]` 只解析进 `WandPreset.attributes`、写入物品 NBT，但 `EquipmentComponent` 的 WAND 槽永远只有 `equipDefaultWand()` 的 +0 修饰符——玩家装高阶法杖只变外观。现 `WandscapeNpc.syncWandAttributes()` 读手部物品 preset_id → `WandApi.getWandModifiers` → `eq.equip(WAND, presetId, mods)`，在 `NpcMenu.clicked` / `onAddedToLevel` / `EntityComponentBridge` 注册路径同步。
- **旧杖处置**：删 basic/adept/master JSON；`basic_wand` 保留为中性默认预设（无 JSON，`EquipmentComponent` 硬编码），新 NPC 出生自带、合成 GUI 不可见。创造栏 `acceptWandPresets` 按预设数据驱动补发全部变体。
- **卷轴 i18n**：display_name 与 `magic.wandscape.*` 一致（魅惑/背水一战/防御强化/光束/治疗…）；合成站法杖名走新增 `craft_recipe.wandscape.<id>` 键。

**为什么**：殖民地自动化模组的核心乐趣在"给法师分工"——工作型法杖是建造线的直接杠杆，值得从 Lv1 贯穿到 Lv30 一条成长链。牺牲型放后期避免前期负反馈；成本锚定"攒大半天到一天"保证每次购买是决策而非零钱。属性应用是既有数据模型的空洞（解析了却没用），接上是让整套设计成立的前提。

**影响**：12 个法杖 JSON + 8 个卷轴 JSON、`WandscapeNpc`（syncWandAttributes）、`WandApi`/`WandApiImpl`、`WandscapeApis`、`WandItem`（tooltip）、`EquipmentComponent`（公开默认修饰符）、`Wandscape`（创造栏）、`lang/{zh_cn,en_us}.json`、命令 seed（builder_wand→carpenter_wand）、`docs/data/craft_recipes.md`、`docs/modules/wand.md`、`architecture/packages/wand.md`。测试：`EquipmentComponentWandTest`（4）、`WandItemTest`（2）、`WandPresetLoaderTest` 新增 trade-off 解析用例。
**JEI 展示修复（同日追加）**：`ElementRecipe` 增 `outputNbt`（法杖 preset_id+wand_color、卷轴 magic_id），`ElementRecipeCategory.resolveItem` 带 NBT 构建输出物品——JEI 法杖/卷轴悬停显示具体变体与 tooltip（此前全显示通用"法杖"/"未绑定魔法卷轴"）；`elementStack` 去掉 `Math.min(count,64)` 数量封顶，>64 元素成本正确显示（`ItemStack.setCount` 不截断，源码已核实）。测试：`ElementRecipeCollectorTest` 新增 2 个 NBT 传导用例。

## 2026-08-26：移除并发建筑上限 Config.MAX_CONCURRENT_BUILDINGS——工作站并行不再受全局预算挤压

**需求**（用户指令）：删掉 `Config.MAX_CONCURRENT_BUILDINGS`，不设并发建筑数量限制。

**决策**：
- 删除 Config 定义 `general.maxConcurrentBuildings`（默认 3）。
- `BuildingTaskSource` 发布新任务时不再检查 `leasedCount() >= budget`——凡有待办工作的建筑直接 `ChunkLoadManager.leaseBuilding` 强加载 footprint。
- 删除 `ChunkLoadManager.leasedCount()`（预算删除后无调用方）。

**为什么**：共享队列并行化后（见 2026-08-25「生产站/节点按类型/元素共享队列」），工作站并行度应由「同类型站数量 × 空闲 NPC 数」决定；全局 3 建筑预算把施工和生产挤在同一桶里，殖民地一开建就会把工作站并行压回 1-2 座，成为并行生产的隐性瓶颈。去掉后强加载成本由引用计数 + 队列排空即释放兜底，每座建筑 footprint 有限——最坏情况是所有待办建筑同时强加载，这正是「多站并行」的预期形态。

**影响**：`Config.java`、`BuildingTaskSource.java`（移除 budget 判断，lease 分支简化）、`ChunkLoadManager.java`（删 `leasedCount`）、`docs/modules/engine.md`、`architecture/packages/engine.md`。存档 TOML 中残留的 `general.maxConcurrentBuildings` 键不再被读取，无害。

## 2026-08-25：移除面板 G 键 overview↔ground 切换，V 强制进入俯瞰

**需求**（用户反馈）：按 G 切到地面（第一人称）后无法正常操控（要么能飞要么动不了）；要求直接去掉该键位，按 V 打开面板后强制处于俯瞰（鸟瞰）模式。

**决策**：
- 删除 `WandscapeClient.OVERVIEW_TOGGLE` 键位（G）及其注册、交互处理（`WandscapePanelController.handleGKeyToggle`）与语言/引导文档条目。
- 面板开合语义不变：`openPanel()` 仍默认 `enterSubMode(OVERVIEW)`，V 打开即进入俯瞰相机。移除 G 后，面板开启期间**不存在任何出口**把相机切出俯瞰（子模式 BUILD/ROAD/STATS/TASKS 均叠加在俯瞰相机上）；想回到第一人称只能关闭面板（ESC / V 再按），这本来就是「关面板夺回操控」的正常路径。

**为什么**：地面/第一人称模式与俯瞰飞行控制器存在输入争夺（玩家移动被 `MovementInputUpdateEvent` 清零或残留飞行），长期是问题入口；面板核心价值在俯瞰管理小镇，关闭面板即可回第一人称，保留「面板内切地面视角」没有不可替代的收益。删除比修复更低风险、更符合"直接去掉该功能"的诉求。

**影响**：`WandscapeClient.java`、`shared/ui/panel/WandscapePanelController.java`、`lang/{zh_cn,en_us}.json`、`guide/{zh_cn,en}/overview_guide.md`、`docs/modules/overview.md`、`architecture/packages/overview.md`、`architecture/packages/projection.md`、`docs/modules/projection.md`、`docs/gaps.md`。

## 2026-08-25：修复仓库物品分解触发无限自动合成死循环（Issue #18）

**需求**：玩家在工作站发布物品分解（`decompose`）任务后，如果仓库中物品数量不足或被消耗完，工作站会不停地自动生成对应物品和对应数量的 `synthesize` 合成订单，造成死循环且消耗大量元素。

**根本原因**：
1. `WandscapeBlockInteractExecutor.checkDecomposePreconditions` 与 `executeDecompose` 在仓库中目标物品数量不足（`available < count`）时，错误地抛出了 `ResourceShortageException`。
2. 该异常导致任务被引擎标记为 `TaskState.AWAITING_RESOURCES`，并在调度器中释放。
3. `ResourceSupplySystem`（每 40 tick 扫描一次阻塞任务）检测到任务在等待该物品，以为是生产/建造缺材料，便自动调用 `enqueueSynthesize` 向工作站下发自动合成订单。
4. 合成出物品后，分解任务被唤醒将物品分解（返还 1/5 元素，净亏 80%），若队列未完全满足则再次缺物、再次自动合成，导致无限死循环。

**决策**：
1. **分解操作语义修正**：分解是单向的“废旧材料回收元素”操作，永远不是生产消费链条。当仓库物品不足时，`WandscapeBlockInteractExecutor` 严禁抛出 `ResourceShortageException`。若 `available <= 0` 直接正常结束；若 `0 < available < count`，则取实际可用数量 `actualCount = (int) Math.min(count, available)` 进行分解并产出元素后正常完成。
2. **深度防御（Defense-in-depth）**：在 `ResourceSupplySystem.scanStuckTasks` 中增加防御检查，若检测到 `production:decompose` 类型的任务，直接唤醒而不派发任何 `enqueueSynthesize` 自动合成任务。

**影响**：
- `engine/boundary/WandscapeBlockInteractExecutor.java`：修正 `checkDecomposePreconditions` 与 `executeDecompose`。
- `engine/system/ResourceSupplySystem.java`：增加对 `production:decompose` 的防御唤醒。
- 新增单元测试 `DecomposeNoAutoSupplyTest.java`。

## 2026-08-25：仓库公开 API 补全与变更事件广播（支持 AE2 联动与附属生态，Issue #17）

**需求**：在开发 Wandscape × AE2（Applied Energistics 2）仓库存储磁盘等外部联动附属时，附属模组遇到了 4 个关键 API 缺口：
1. `WarehouseApi` 无法列出全量物品清单，被迫跨模块依赖内部类 `ColonyItemBank.get(level).getSnapshot(colonyId)`。
2. 缺乏公共仓库变更事件，附属只能通过高频全量轮询做缓存同步。
3. `WarehouseApi.extractItem` 仅返回 `boolean` 且单次限额 64，无法方便提取大批量物品或获取实际取出数量。
4. 配方产物不支持自定义 NBT/DataComponents 与 `$colony_id` 占位符变量注入。

**决策**：
1. **全量快照公开**：在 [`WarehouseApi`](file:///D:/Projects/MCMOD/Wandscape/src/main/java/com/wsteam/wandscape/shared/api/WarehouseApi.java) 中新增 `Map<ItemKey, Long> getItemSnapshot(UUID colonyId)`，由 [`WarehouseManager`](file:///D:/Projects/MCMOD/Wandscape/src/main/java/com/wsteam/wandscape/warehouse/WarehouseManager.java) 委托至 [`ColonyItemBank.getSnapshot`](file:///D:/Projects/MCMOD/Wandscape/src/main/java/com/wsteam/wandscape/warehouse/ColonyItemBank.java)。
2. **公开变更事件与增量广播**：在 `shared/event/` 新增 [`WarehouseItemChangedEvent`](file:///D:/Projects/MCMOD/Wandscape/src/main/java/com/wsteam/wandscape/shared/event/WarehouseItemChangedEvent.java)（物品出入库）与 [`WarehouseElementChangedEvent`](file:///D:/Projects/MCMOD/Wandscape/src/main/java/com/wsteam/wandscape/shared/event/WarehouseElementChangedEvent.java)（元素变动），在 `ColonyItemBank` 中发生增减时实时广播，支持 $O(1)$ 增量网络同步，并保留单元测试 Seam。
3. **提取接口升级为返回提取数量 (`long`)**：将 `extractItem(UUID, ItemKey, long, Container)` 返回值改为 `long`（实际放入容器的数量），并优化为支持跨多个槽位连续填充分配。
4. **配方自定义 NBT 与 `$colony_id` 注入**：[`BrewPotionRecipe`](file:///D:/Projects/MCMOD/Wandscape/src/main/java/com/wsteam/wandscape/production/data/BrewPotionRecipe.java) 等配方产物支持 `output.nbt` JSON 对象，并在 [`WandscapeBlockInteractExecutor`](file:///D:/Projects/MCMOD/Wandscape/src/main/java/com/wsteam/wandscape/engine/boundary/WandscapeBlockInteractExecutor.java) 中自动完成 `$colony_id` 等占位符动态替换后生成 `ItemKey` 入库。

**为什么**：仓库是殖民地自动化与模拟经营的核心数据源，干净的公共 API 与可观察的事件机制让外部附属与内部系统均能以高内聚、低耦合、零轮询的方式实现高性能联动。

**影响**：
- `shared/api/WarehouseApi.java`：新增 `getItemSnapshot`，`extractItem` 返回类型升级为 `long`。
- `shared/event/`：新增 `WarehouseItemChangedEvent.java`、`WarehouseElementChangedEvent.java`。
- `warehouse/ColonyItemBank.java`：增加增减变动事件广播。
- `warehouse/WarehouseManager.java`：实现 `getItemSnapshot` 与多槽位 `extractItem`。
- `warehouse/WarehouseMenu.java`：适配 `api.extractItem` 返回值。
- `production/data/BrewPotionRecipe.java`：支持 `output.nbt` 解析。
- `engine/boundary/WandscapeBlockInteractExecutor.java`：支持配方产物 NBT 占位符注入。
- 新增单元测试 `ColonyItemBankChangedEventTest.java`、`BrewPotionRecipeNbtTest.java`。

## 2026-08-25：鸟瞰视角与曲线编辑器飞行输入拦截与按键映射绑定（防 GUI 内 Shift 冲突）

**需求**：在鸟瞰视角（Overview）或样条道路编辑器中打开仓库等 GUI 界面（`mc.screen != null`）时，玩家按 Shift 快捷移动物品或按 WASD 时，飞行控制器的每帧位移检测未做 GUI 状态过滤，依然响应按键位移，导致视角直接向下坠落或乱飞；同时飞行控制器硬编码了 GLFW 的 `W/S/A/D/Space/Left_Shift`，无法随玩家在原版 Controls 设置中修改的按键（如 Sneak/Jump/Move）生效。

**决策**：
1. **Screen 状态强拦截**：在 [`OverviewFlightController`](file:///D:/Projects/MCMOD/Wandscape/src/main/java/com/wsteam/wandscape/overview/client/OverviewFlightController.java) 与 [`SplineEditorController`](file:///D:/Projects/MCMOD/Wandscape/src/main/java/com/wsteam/wandscape/road/client/SplineEditorController.java) 的位移检测外层增加 `if (mc.screen == null)` 判断。当任何 GUI 界面打开时，彻底屏蔽飞行位移检测，保证仓库 Shift 挪物品、文本框输入等操作完全不干扰相机。
2. **按键映射与原版 KeyMapping 联动**：将硬编码的 GLFW 键位改为基于 `mc.options` 的标准 KeyMapping（`keyUp`, `keyDown`, `keyLeft`, `keyRight`, `keyJump`, `keyShift`），并通过 `isKeyDown(KeyMapping, window)` 辅助方法统一兼容 `isDown()` 及 GLFW 按键状态。玩家在游戏设置中自定义的潜行/跳跃/移动键位即时对鸟瞰飞行生效。

**为什么**：GUI 打开时玩家的键盘意图属于当前界面，严禁泄露至背景场景飞行；遵循原版按键绑定规范，保障非 QWERTY 键盘（如 AZERTY）或自定义键位玩家的正常体验。

**影响**：
- `overview/client/OverviewFlightController.java`：增加 `mc.screen == null` 拦截及 `mc.options` KeyMapping 绑定。
- `road/client/SplineEditorController.java`：同样增加 `mc.screen == null` 拦截及 `mc.options` KeyMapping 绑定。

## 2026-08-25：任务执行与方块交互执行器缺资源异常防护（防服务端 Tick Loop 崩溃）

**需求**：在 NPC 执行合成（synthesize）等生产操作时，如果所需元素（如木元素）不足且该操作的 channel duration 为 0（低阶物品瞬间合成），`WandscapeBlockInteractExecutor` 在同步执行分支中直接抛出 `ResourceShortageException`，未被捕获并转化为 `failedFuture`，导致异常直穿 `TaskExecutionSystem.processNpc`、`World.tick` 及 `Wandscape.onServerTick`，引起服务端崩溃（`Exception in server tick loop: ResourceShortageException: Resource shortage: need 128 x wood`）。

**决策**：
1. **执行器前置校验与同步短路保护**：`WandscapeBlockInteractExecutor` 增加 `checkPreconditions` 前置资源校验，在进入引导前若缺资源立即返回 `CompletableFuture.failedFuture(e)`，避免无谓等待；当 `channelTicks <= 0` 同步执行时，捕获 `ResourceShortageException` 并返回 `CompletableFuture.failedFuture(e)`。
2. **调度系统异常安全闭环**：
   - `TaskExecutionSystem.processNpc` 在 Step 4f 调用 `executor.execute(...)` 时增加 `try-catch` 防御，捕获 `ResourceShortageException`（标记任务为 `AWAITING_RESOURCES`，解绑 NPC，清空队列）及未捕获的 `Throwable`（安全回退至全局池，不炸服）。
   - 在 Step 2 处理已完成的 `pendingFuture` 时，增加 `isCompletedExceptionally()` 判定，捕获异步失败的 `ResourceShortageException` 并正确流转任务状态。
   - `executeParallel` 子任务执行时增加异常捕获并转化为 `failedFuture`。

**为什么**：核心原则第四条「稳定性优先：所有可能失败的路径必须有兜底。不允许静默失败或崩溃」。缺资源是正常的经营生产业务分支，应当优雅转入 `AWAITING_RESOURCES` 等待资源补充唤醒，绝不能使服务器崩溃。

**影响**：
- `engine/boundary/WandscapeBlockInteractExecutor.java`：新增 `checkPreconditions`，安全包装同步与异步执行。
- `task/scheduler/TaskExecutionSystem.java`：Step 2 与 Step 4f 及 `executeParallel` 增强异常拦截与状态流转。
- `task/scheduler/TaskExecutionResourceShortageTest.java`：新增单元测试，覆盖同步/异步/未捕获异常全场景。

## 2026-08-25：V 面板全局任务与法师管理抽屉——RTS 式 3D 镜头联动 + 动态节流同步

**需求**：在殖民地发展中，玩家无法直观掌握法师正在忙什么、有哪些任务正在执行、排队等待、哪些卡在前置资源不足（缺少具体什么元素），也无法在鸟瞰/全局模式下便捷调整任务优先级（加急/取消）或一键跟踪/调度法师。

**决策**：
1. **交互形式**：在 V 面板 Overview / 鸟瞰模式下新增第 4 个侧边栏 Tab（快捷键 `4`），呼出 RTS 风格半透明暗色磨砂玻璃抽屉（`TaskManagementOverlay`，宽度 380px，支持鼠标独立滚轮滚动与多维度卡片操作）。关闭抽屉自动无缝退回纯 Overview 鸟瞰视角。
2. **视图架构**：
   - **【📜 任务大厅】**：支持 `全部/进行中/缺资源/排队中/待办` 5 大维度过滤与中英文即时关键词搜索。展示任务所属建筑/蓝图、执行法师、步骤/吟唱进度条，缺资源时醒目红底标签透传缺失元素种类及 `缺口量 / 库存量`。卡片右侧集成 `[🔍 定位镜头]`, `[⚡ 加急 (+50优先级)]`, `[✕ 取消任务]` 操作。
   - **【🧙 法师名册】**：展示法师头像状态、生命/魔力双条、四维战斗属性（法强/工速/施法/护甲）、手持法杖与当前执行任务。提供 `[🔍 聚焦镜头]`, `[🎥 持续跟踪]`, `[🛡 跟随模式]`, `[🕊 和平模式]` 四大快捷控制。持续跟踪状态下，Overview 相机每一帧平滑插值追踪法师实体，玩家按 WASD 即自动脱离追踪。
3. **网络同步与性能**：
   - 采用**按需订阅机制**（`TaskPanelSubscribePacket` 进入订阅/退出退订），无玩家打开面板时服务端 0 开销。
   - 服务端 `TaskPanelSyncTracker` 采用 **10-tick (0.5s) 合并节流**，仅在有订阅玩家且面板脏或周期到达时生成精简 DTO 快照（`TaskSummaryDto`/`MageSummaryDto`/`ResourceShortageDto`）推送，严禁高频泛洪。
   - 任务优先级调整在 `GlobalTaskPool` 中动态自平衡 `assignableSet` 排序，保持调度器原子性。

**为什么**：RTS 抽屉与 3D 镜头的深度结合，使宏观管理与微观调度合二为一；订阅制与 10-tick 节流保证大型殖民地多人联机下的极低网络与 CPU 开销。

**影响**：
- `shared/network/tasks/`：新增 `TaskPanelSubscribePacket`, `TaskManagementSyncPacket`, `TaskManagementActionPacket`, `MageModeActionPacket`, `TaskPanelSyncTracker`。
- `shared/ui/panel/`：新增 `TaskManagementClientState`, `TaskManagementOverlay`；升级 `WandscapePanelState`（新增 `SubMode.TASKS`）、`WandscapePanelOverlay`（4 Tab 渲染）、`WandscapePanelController`（按键与点击分发）、`OverviewFlightController`（法师相机跟踪）。
- `task/engine/pool/`：`GlobalTask.priority` 改为可修改并在 `GlobalTaskPool.updatePriority` 中动态重排 `assignableSet`。
- 单元测试与构建：新增 `GlobalTaskPriorityUpdateTest`、`TaskManagementClientStateTest`，全量测试 100% 通过。


## 2026-08-25：游客头顶瞬态气泡统一为物品渲染——服务元素走元素物品、空交互不弹泡

**需求**：游客逛完商店，头顶气泡显示购买的商品；逛服务建筑则显示产出的元素。过去元素不是物品、商品是物品，气泡渲染因此按 `iconKind` 分两套（`drawItemIcon` 渲真实物品 vs `drawElementIcon` 渲主题色染色精灵）。现在七种元素有了物品形态（`ElementItem`），「物品 vs 元素」的渲染分野失去依据，可合并简化。

**决策**：把气泡塌缩成「一个物品图标」——`TransientBubbleStore` 删掉 `ICON_NONE/ICON_ITEM/ICON_ELEMENT` 三态，`Event(iconKind,iconId,count,startTick)` → `Event(iconId,count,startTick)`；`TouristBubblePacket` 退化为 `(entityId, iconId, count)`（去 `iconKind`）；`SpeechBubbleRenderer.renderEventBubble` 恒走 `drawItemIcon`，删 `drawElementIcon`。服务元素经新增的 `ElementApi.elementItemId(ElementType)` 解析成元素物品 registry id（如 `wandscape:element_fire`）后走同一条物品渲染。商店没买到/服务无产出/休闲/ATM 重访不再弹空泡（原 `ICON_NONE` 事件会短暂盖住环境气泡 4 秒，一并消除）。`WandscapeTheme` 的主题色染色精灵与 `ICON_ELEMENT_*` 常量保留——它们被仓库面板/元素面板/工作站界面广泛使用，不在本次范围。

**为什么**：元素有了物品形态后，气泡里不应再区分「物品/元素」两种渲染；保留只留死分支与 `iconKind` 管道，统一成物品渲染是纯删除。服务元素写进 `ColonyItemBank`（游客不拿实物），气泡只是把它可视化成元素物品，语义与 JEI/配方中的元素代币一致。空交互弹空泡是既有怪癖（`renderBubble` 只要事件存在就 `return`，即使 NONE 画不出任何东西也盖住环境文本），删掉更干净。

**影响**：`ElementApi` 新增 `elementItemId(ElementType)`（纯字符串，`ElementApiImpl` 按 `element_<id>` 约定构造，已由 `ElementApiImplTest` 锁定）；`TouristMoveGoal.interactWithShop/interactWithService/interactWithRelax/interactWithAtm` 的 `sendBubble` 去 `iconKind`、空 `iconId` 不发送；客户端 `WandscapeClient` trigger 同步去掉 `iconKind`。服务泡泡外观从染色精灵变为元素物品贴图（与仓库/工作站界面有轻微视觉差异）。

## 2026-08-25：生产站/节点按类型/元素共享队列——多站并行 + 面板多进度条

**需求**：过去工作站/node 的任务只进「玩家点开提交的那一座建筑」自己的队列（`RequestProductionTaskPacket`/`RequestGatherTaskPacket` 用 `stationPos`/`nodePos` 定位到单建筑 `enqueueWork`），自动补给也只挑第一座可用站/同元素第一座 node，于是「建多座工作站/多个同元素 node 也只一座在干活」。要求同型工作站/同元素 node 共享一条队列，每座空闲站认领队首未认领任务，每站同时跑 1 个，面板显示多条运行进度。

**决策**：引入**共享生产队列**——一个组 = `(colonyId, groupKey)`，工作站按 `buildingTypeId` 分组（`production:*` 任务），node 按 `node_config.element()` 分组（`node:gather` 任务）。任务入队到组队列；空闲站 `dequeueWork` 认领队首并把任务 `anchor` **重绑到认领站**（`WandscapeBlockInteractExecutor.getChannelProgress` 按 `op.target()` 匹配进度，不重绑则多站共用同一 anchor、进度互相冲突）。未建成站不认领（只做自身构建），shutdown 站不认领生产（仅 repair），与既有 per-building 语义一致；非共享建筑（仓库/商店/民居/市政府…）完全不受影响。`ResourceSupplySystem` 适配：自动补给把缺口拆成多条 `count=1` 采集任务入元素组队列（node 并行），并按 `(colony, groupKey)` 去重计数防重复补给；`countSynthesizingWorkstations` 改按「正在跑头的站数 + 有排队的组数」。队列无上限（延续 queue 字段移除后的语义）。

**为什么**：共享队列才能让「多建站/多 node = 提速」成立；重绑 anchor 是逐站进度条成立的前提。按 `buildingTypeId`/`element` 而非 category 分组，保证同型站/同元素 node 共享而不同型/不同元素互不串，也避免跨殖民地串（key 含 colonyId）。

**影响**：`BuildingSavedData` 新增 `sharedQueues`(Map<SharedGroup,Deque<WorkItem>>) + `peekSharedQueue/hasSharedWork/groupMembers/isSharedQueueCategory/groupKeyFor` + NBT 持久化；`BuildingApiImpl` 的 `enqueueWork/dequeueWork/getBuildingsWithPendingWork/getQueue/removeFromQueue/moveUp/moveDown` 按角色路由到组队列，新增纯函数 `isProductionWork/isGatherWork/rebindAnchor`；`TaskQueueDataPacket.current`(单) → `currents`(List)，`TaskQueueModifyPacket.buildCurrentTasks` 聚合组内运行头；`TaskQueuePanel` 渲染多条运行任务行；`WorkstationScreen/CraftingStationScreen/MagicStationScreen/NodeScreen` 接线 `currents`；`ResourceSupplySystem` 去重计数 + node 并行分发 + `countGatherInFlight`；新增 `BuildingApiSharedQueueTest`。auto-synthesize 同样受益（任务落组队列、任意空闲站认领）。

## 2026-08-25：生产任务数量滑块窗口化——滑条固定 64 宽，±64 整页翻页

**需求**：工作站/合成站/魔法工坊发布合成任务时，数量滑条最多只能拉到 64（一组），想一次合成大量物品（如 3000 个）只能反复拖到 64 再提交，极其繁琐。

**决策**：取消「单次最多 64」的硬上限，并把滑条改成**窗口化**：滑条始终只显示一页、最多 64 个连续数值（1–64、65–128、…），左右各一个 `-64`/`+64` 按钮把窗口整页上移/下移，`Slider.setRange` 换页时把当前值夹进新窗口；翻到顶/底为安全 no-op。上限取真实总量（合成/法杖/法术 = `ProductionAffordability.computeMaxAffordable` 按当前元素可负担量，分解 = 物品库存数），最后一页不满一页自动收窄（如总量 100 → 首页 1–64，+64 后 65–100）。为此：(1) 三个 packet 的私有 `computeMaxAffordable` 以 `MAX_PER_OPERATION=64` 为起点硬顶 `maxAffordable`，全部改为引用共享纯函数 `ProductionAffordability`（起点改为真实可负担量）；(2) 执行层（`WandscapeBlockInteractExecutor`）本就不限 count（蓝图 `count` 是普通 int），抬升安全。

**为什么**：单次 64 是拍脑袋的便利上限，与「能负担多少」不符；钳到 64 反而逼玩家反复提交小任务。滑条直接铺到几千会让拖拽精度崩坏（每像素≈几十），窗口化让滑条永远处于一个可精调、跨度 ≤64 的区间。`QuantityWindow` 管窗口数学，`QuantityStepper` 管三个 widget 与翻页。

**影响**：三个生产界面数量滑条带 `-64`/`+64` 按钮并按页翻窗口；滑条上限 = 真实总量。`QuantityWindow`（纯）+ `QuantityWindowTest`、`ProductionAffordability`（纯）+ `ProductionAffordabilityTest` 新增守护。采集节点（NodeScreen）与商店补货（ShopScreen）不改——后者本就有 ±1 步进，语义不同。

## 2026-08-25：撤回未完成建筑的材料返还——只退「未放置」部分，已放置交给拆除 salvage

**需求**：撤回（V 面板撤销，`cancelBuilding`）一个「未完成建造」的建筑时，需把玩家为它支付的材料退还给仓库。但原实现 `refundMaterials` 按蓝图**全量**退款，紧接着 `demolishBuilding` 又会逐块 `place air` 触发 `AsyncTransformExecutor.performSalvage`，按 `Block.getDrops` 把已建方块的实物再返一遍——两段叠加，净返还 = 全量成本 + 已放部分实物 = **刷物品**（例：蓝图 100 木，开工批量扣除 100 木，建到一半撤回 = 退 100 + 拆掉 60 块掉落 60 = 拿回 160，净赚 60）。

**决策**：撤回时**只退「尚未放置」块的材料，已放置块交给拆除 salvage 物理返还**。用 `BuildCompleteListener.findDamagedBlocks`（返回与蓝图不一致的 offset）筛出缺失/未放好的 offset，`materialCountsForMissingOffsets` 只对这些 offset 累计材料——口径与建造扣费 `EnqueueHelper.computeMaterialCounts`（某块有元素映射才有 cost、air 跳过、每 offset 计 1）完全一致，只是先排除已放块。

**为什么**：建造是 `build:place_structure` 的 `request_resource` 在开工时**一次性扣全量**；若撤回再全额退款、又因拆除把已放实物收回来，同一批材料被计两次。拆成两段——已放块实物由 salvage 收、空位材料由 refund 补——总和恰好等于建造扣费，不刷也不亏，且玩家能直观看到每块已建方块掉落回仓。

**影响**：`BuildingApiImpl.refundMaterials` → `refundUnplacedMaterials`（findDamagedBlocks 判定缺失块）；新增可测纯函数 `materialCountsForMissingOffsets(config, rotationSteps, missingOffsets, hasElementMapping)`（注入 element-mapping 判定，裸 JVM 可测，rotation 由 `BuildingRotation.rotateOffsets` 保序配对 `config.blockIdAt(i)`）；`BuildingApiImplTest` 新增 5 用例覆盖缺失/已放/旋转/air/无映射。正常拆除（destroy 成品建筑）不走 refund、仅靠 salvage，行为不变。

## 2026-08-24：法师小屋——单法师住宅 + 入住记录与法师实体解耦

**需求**：新增建筑类别 `mage_hut`（法师小屋），一间只住一名法师、入住后锁定。小屋面板提供 3D 预览 + 属性面板（`初始值/初始值上界 + 等级加成 + 装备加成`）+ 装备/策略/升级/休息/训练属性操作；法师死亡时入住状态不变（可复活），只是不能操作。

**决策**：
- **入住记录存 `BuildingSavedData`（每 buildingId 一条 `MageHutResident`），与法师实体解耦**：法师实体死亡即被移除、复活是全新实体（新 UUID），若把养成进度（等级/基础值）存在实体上会随死亡丢失。故小屋是养成进度的**权威源**（level + base[7]），存活法师只是「投影」——升级/训练时由服务端重算实体 flat 属性 + `seedBaseValues` 重播种 ECS；死亡时小屋记录不变，复活时按 `resident.npcId == rec.npcId()` 反查小屋重挂并恢复等级/基础（装备从零，与既有复活不掉护甲一致）。
- **属性三件套收敛到纯逻辑 `MageHutAttributes`**：每个属性 `{lower, upper, perLevel, trainStep}`，`effective = clamp(base)+perLevel*(level-1)+equip`，训练只把 base 抬向 upper。`MageAttributeRoller` 把每级加成烘进掷出值，小屋用 `baseFromFlat` 反推 base——存量法师 level 默认 1，base=该掷出值。
- **休息复用「跟随」的中断链路**，不另开调度路径：`EntityOps.isResting` + `SchedulerSystem` 排除休息 NPC + `TaskExecutionSystem` 第 0 步 `releaseForInterruption`（原 `releaseForFollow` 抽出复用）释放全局任务回池——休息即「抛弃原任务交他人」，与需求一致。休息用实体级 `RestGoal`（vanilla 寻路）走到小屋休 2 分钟回满，不占用 ECS 导航。
- **装备/策略按钮走现有容器菜单**（`NpcMenu`/`NpcStrategyMenu`），不新做屏幕；升级/训练/休息/指派走一个 C→S `MageHutActionPacket`（action 枚举）。
- **费用**：升级/训练各扣 7 元素 ×1000（`MAGE_HUT_COST_PER_ELEMENT`），指派免费；升级上限 = 殖民地等级（`ColonyLevelManager.getLevel`）。

**为什么**：养成练度必须活在「建筑」而非「生物」上（否则死亡即归零）；属性/休息/费用都是可复用既有机制（`EquipmentComponent`/跟随中断链/`ColonyItemBank`），把新复杂度压到最低。小屋 UI 全走 `I18n.name`，不硬编码翻译。

**影响**：新增 `mage_hut1.json`、`MageHutAttributes`/`MageHutResident`（共享数据）、`WandscapeNpc.level/homeHutId/resting`+`RestGoal`、`EntityOps.isResting`、`MageHutDataPacket`/`MageHutActionPacket`/`MageHutServerHandler`、`MageHutScreen`、复活重挂（`ReviveHandler.rebindToMageHut`）。


## 2026-08-23：交互界面原版容器化（仓库/NPC 装备/施法策略）——对齐 Refined Storage 交互

**需求**（用户实测反馈）：仓库原先的自绘 MedievalScreen 交互（点击交换页签无效、背包快捷键失效、无法在空白处存入）体验差；要求对齐 RS/AE2 的仓储交互，并把 NPC 装备/策略界面也原版容器化。

**决策**：
- 仓库/NPC/策略全部改为 `AbstractContainerScreen` + 真实容器菜单（注册 MenuType），玩家背包槽统一用共享组件 `VanillaPlayerInventory`（`ToggleableSlot` 可显隐 + 箱类坐标公式 + 原版槽底渲染）——数字键/Q/Shift/拖拽/整理 mod 原生生效，杜绝"仓库有而其他界面没有"的组件漂移。
- 仓库 Exchange 页 = 原版 6 行箱纹理 + RS 交互语义：光标带物品点存储区任意位置（含空白/空格子）即存入（左=整叠/右=1 个）；提取左=整叠/右=半叠/Shift=到背包；滚轮转移（Shift/Ctrl 组合）；无修饰滚轮翻页（方向按 MC 语义：scrollY>0 上滚——RS 源码的 scroll<0 约定在 1.21.1 不成立）。
- 数量显示用 RS `ResourceSlotRendering` 算法：z 抬到图标之上 + 白字描边 + 长文本半尺寸（解决"数字被贴图盖住"）。
- NPC 策略槽从"magicId 列表 + 点击销毁"改为**真实卷轴槽**（放卷轴=装备、取出=拿回卷轴），槽变更重建扁平装备态写回 `EquippedMagicComponent`；`NpcStrategyPacket` 降级为仅切预设。
- 实体 id 不经菜单构造传递（客户端 MenuType 工厂拿不到），统一由 `NpcDataPacket` 下发（客户端 `apply` 更新），避免"点策略按钮发 -1 找不到实体"。

**为什么**：仓储/装备交互的行业标准（AE2/RS）是"真实槽 + 修饰键组合"，自绘点击式既反直觉又无法兼容快捷键生态；复用共享组件避免多界面行为不一致（用户明确要求"组件一模一样，不能有缺失功能"）。

**影响**：`NpcEquipPacket` 删除（装备改由菜单槽驱动）；策略槽交互与数据模型改变；新增 `NpcMenu`/`NpcStrategyMenu`/`NpcOpenStrategyPacket` 与 `shared/ui/vanilla/` 共享组件。

## 2026-08-21：聊天组件跨网参数消毒——translatable 参数只允许原始类型或 Component

**需求**（用户实测 1.9.2）：托管服务器上导出建筑（`export_building_ok`）崩出 `EncoderException: Failed to encode: This value needs to be parsed as component translation{...}`，直接断线。1.8 用时聊天区直出 String 无问题，1.9.2 换成翻译键后触发。

**根因**：1.9.2 的 i18n 重构把所有聊天消息从纯 String 换成 `Component.translatableWithFallback(key, fallback, args)`。MC 1.21.1 将聊天组件打包跨网（`ClientboundSystemChatPacket` 的 `ComponentSerialization.TRUSTED_STREAM_CODEC`）时，`TranslatableContents.ARG_CODEC` 里 `filterAllowedArguments` 只认 **Number / Boolean / String / Component** 四种参数，其余（`Path`、`BlockPos` 等）`DataResult` 直接报错 → 服务端编码抛 `EncoderException` → 连接被关。`ScreenFeedbackPacket`（同为 `ComponentSerialization` 编码）同样受影响。

**决策**：
- `I18n.name(key, fallback, args)` 作为全部聊天消息的唯一工厂，统一走 `sanitize`：任何非原始类型/非 Component 参数包成 `Component.literal(String.valueOf(arg))`。通用兜底，覆盖所有现在与未来的调用点，不逐个调用点修补。
- 仅 `export_building_ok`/`export_road_ok` 传 `Path`、`no_scanner`/`value_no_scanner` 传 `BlockPos` 会崩；后两处以 `toShortString()` 传参，聊天显示为 `x, y, z` 而非 `BlockPos{...}`。

**为什么**：聊天参数合法类型是 MC 网络编解码的硬约束而非本模组业务规则，把约束收敛到消息工厂一处即可「不允许静默失败或崩溃」，避免以后新增调用点再踩。

**影响**：任意原始对象参数不再炸编码；`I18nTest` 覆盖 `sanitize` 的参数类型转换。

## 2026-08-18：TickProfiler 性能分析器与夜间寻路风暴（Pathfinding Storm）优化

**背景与实测**：
在 `tick rate 1000` 压测下，白天 MSPT < 10ms，而夜晚/傍晚会暴增到 50~64ms。通过新实现的 `TickProfiler`（`/wandscape profile on|off`）对三份采样数据（normal, jump, lot_tourist）分析抓出根因：
1. **傍晚旅店并发寻路风暴**：未满条且无旅店的游客在 12000 时刻集中并发调用 `moveTo` 同步 A* 寻路（单次 3.5~7.3ms），多游客同时触发导致单 Tick 堆叠 20ms+。
2. **排队就位/队序推进触发同步寻路**：队列推进 1 格时所有排队者同时调用 `getNavigation().moveTo`，单 Tick 产生 10~20ms 尖峰。
3. **短距离反复路网规划**：<12 格距离仍调用 `RoadRouter.plan` 产生不必要计算。

**优化决策**：
- **旅店路由错峰与前置过滤**：
  - 在 `TouristMoveGoal.tick()` 中增加 `!targetingHotel() && !hotelRouteBackoff.isActive()` 前置过滤。
  - 增加 `(timeBase + entityId) % 10 == 0` 错峰轮询，将傍晚全镇游客的路由分散在 10 个 tick 内平滑执行。
- **排队就位与队列推进轻量化**：
  - 排队站位在 3 格内（`distSq <= 9.0`）时，直接使用 `tourist.getMoveControl().setWantedPosition(...)` 纯物理逼近，完全绕过原版 Pathfinder A* 寻路图（耗时由 3.5ms 降至 0.001ms）。
  - 队首认领 spot 瞬间直接调用 `startActivityAtSpot()`，移除对 0 距离坐标的冗余 `moveTo` 调用。
- **RoadWalkPlanner 短距离旁路**：起点与终点 <= 12 格时直接返回空列表（走直线），不走路网拆点计算。

---

## 2026-08-18：游客通行能力修复——碰撞箱降为玩家身高 + 防卡死只看水平位移

**需求**（用户实测 F3+B）：游客卡在 2 格高通道；碰撞箱 ~2 格、比玩家（1.8）高。且贴墙误寻路时反复跳跃，防卡死系统不触发，要求「x/z 不动即视作卡死，y 不管」。

**根因**：
- 碰撞箱注册 `.sized(0.6f, 1.95f)`——1.95 只比通道净空 2.0 少 0.05，遇到半砖/叠层地面等净空不足的「2 格高」位置就卡住；玩家 1.8 有 0.2 余量畅行。
- 防卡死用三维 `pos.distSqr(lastPos) < 1.0` 判「未移动」：游客贴墙反复跳时 y 每跳跨越整块（distSqr=1）被误判为「在移动」，卡死计数永不累积。

**决策**：
- 游客 `EntityType.sized` 高度 1.95 → **1.8**（与玩家/渲染模型一致，视觉无变化）。
- `TouristMoveGoal` 新增 `sameHorizontal(a,b)`（纯 x/z 相等判定），替换全部防卡死位置判定（outdoor nav / indoor nav / POI / wander 兜底 / 屋顶救援）——y 上下跳动不再算移动，只有 x/z 未推进才累计卡死计数。

**为什么**：通行目标是「游客在哪都能走，与玩家同通行能力」——降到玩家身高即可；防卡死的本质是「水平没有前进」，而 y 波动（跳跃）恰好是被困时最常出现的假移动信号，必须排除。

**影响**：游客可穿过 2 格高通道/矮净空；贴墙弹跳的游客会在 `noMoveTicks` 阈值（100/120 tick）后触发传送兜底，不再永久卡住。

## 2026-08-17：夜晚旅店路由性能修复——无空闲旅店闩锁 + 过远传送加固

**需求**（用户指令）：夜晚 MSPT 严重（95%ile 88ms / max 363ms），火焰图 `TouristMoveGoal.eveningRouteToHotel()` 占 ~70%。要求兜底：1) 无空闲旅店 → 剩余游客不再反复找旅店、照常逛；2) 有空闲旅店/回店且近 → 寻路前往；3) 远 → 直接传送；并严禁游客对极远自家旅店强制长距离寻路。

**根因**：`eveningRouteToHotel()` 对每个无旅店未满条游客**每 tick** 调用 `findHotelTarget()`（O(建筑数) 全扫，每次新建 List）。夜晚旅店全满时所有游客持续每 tick 全扫，是 CPU 大头（寻路本身仅 ~1.5%，非瓶颈）；sim 侧 `SIM_INTERVAL=1` 对无旅店影子同理。

**决策**：
- **新增 `HotelRouteBackoff` 闩锁**（纯逻辑，可单测）：当晚 `findHotelTarget` 无空闲旅店或过远传送失败 → 闩上，当晚不再搜索（夜晚无退宿，重扫白费）；次日白天 `tick()`/sim `simStep` 清除，下一晚重新尝试。闩锁只在傍晚路由块生效，游客其余行为照常（继续逛店），18000+ 离场窗口照旧兜底。
- **`routeToHotelBuilding` 传送前置**：过远先尝试传送；找不到安全落点 → 放弃本次路由返回 false（不落回远距离寻路），调用方闩锁。
- **住店客 `returnToOwnHotel` HEADING 短路修复**：已在回店路上但过远（如经普通访问路径 `selectNextTarget` 选中远处自家旅店）→ 仍触发传送，不再长距离寻路；传送失败 → 取消当前路由 + 闩锁。
- **sim 侧镜像**：`TouristShadow` 持同一闩锁，`runTick` 傍晚路由块闩锁后不再每 sim-tick 重扫。

**为什么**：夜晚无退宿 → 重扫是纯浪费 CPU；瓶颈是每 tick 的搜索循环而非寻路，闩锁把 O(游客×建筑) 的持续全扫降为每游客当晚一次。远距离寻路到超远目标代价大且无必要，直接传送。

**影响**：夜晚 MSPT 不再随旅店全满线性恶化；无旅店游客照常逛店直至 18000 离场窗口；过远且传送失败的住店客当晚保持登记、放弃赶路，次日可再试。

## 2026-08-16：初始法师赠送铁套——开局生存兜底

**需求**（用户指令）：早期城镇法师太脆容易死，给建镇时赠送的三个法师加上铁套。

**决策**：
- 在 `ColonyCommand.createColonyAt` 生成初始 builder NPC 的循环里，给每个 NPC 的 `armorInventory`（0=头盔 1=胸甲 2=护腿 3=靴子）塞满铁套四件（铁盔/铁胸甲/铁护腿/铁靴），随后 `syncArmorAttributes()` 把护甲值同步进 ECS `EquipmentComponent`（每槽一个加法 `ARMOR_VALUE` 修饰符），下一 tick `applyEffectiveAttributes` 推送到原版 `Attributes.ARMOR`，实际生效约 15 护甲点。
- 外观不渲染（盔甲格走的是"仅数值生效"路径，巫师袍外观不受影响）；`armorInventory` 不进原版装备槽，原版 `doHurtEquipment` 够不到它——耐久改由 `WandscapeNpc.hurtArmor` 覆盖按原版语义手动结算（见 2026-08-26「NPC 盔甲受击扣耐久」），铁套不再永久、会随战斗磨损。
- 覆盖全部建镇入口：`/wandscape colony create` 与市政厅命名弹窗（`ColonyCreateRequestPacket`）共用 `createColonyAt`，一处改动两边生效。
- 延迟入 ECS（engine 未就绪）场景由 `EntityComponentBridge.onNpcJoinWorld → syncArmorAttributes` 兜底：盔甲已存 NBT/内存，join 时自动同步。

**为什么**：开局三位法师是玩家唯一劳动力，阵亡即生产停摆（虽有全灭自动复活保底，但复活是 1 血虚弱态，重开生产前仍易再死）。免费铁套是纯数值生存增益、无恢复成本，符合"轻度不硬核"原则。

**影响**：初始法师护甲值从 0 提升到约 15，开局受普通怪物的伤害大幅下降；死亡掉落/复活不保留盔甲（DeathRecord 无盔甲字段，与玩家手动装备的既有行为一致）——铁套是一次性开局赠送。

## 2026-08-15：路网拓扑构建升级——支持 T 型/十字交叉路口拆分接驳与多段野路跳跃（野路-road-野路-road）

**需求**（用户指令）：此前的路网拓扑仅支持端点对接（首尾相连），玩家建设分支道路（T 型路口、十字路口）时无法识别中段交点导致寻路断裂；且断开的多段道路无法在路段之间跨越野路（野路-road-野路-road）联合寻路。要求升级路网构建支持 T 型路口与野路跳跃，同时严控性能。

**决策**：
- **T 型路口与十字交叉点自动发现**：
  - 在构建拓扑图时，遍历道路边缘端点并在相交道路上执行垂直投影。若距离 $\le 4.0$ 格（`MAX_JUNCTION_GAP`），自动在相交道路上插入分割点（Split Parameter $u_{proj}$）。
  - 道路内部按分割参数拆解为若干连续子段并双向连通，并在交叉点处建立权重为 $d \times \text{ticksOnRoad}$ 的平滑接驳边（`offRoad = false`）。
- **多段野路跳跃接驳（野路 - road - 野路 - road - 野路）**：
  - 若两个断开的路段端点/节点间距在 $4.0 < d \le 24.0$ 格（`MAX_ROAD_HOP_GAP`），在拓扑图中建立跨越野路的越野跳跃边，权重为 $d \times \text{ticksOffRoad}$（`offRoad = true`）。
  - Dijkstra 寻路在同一个图模型中统一度量「沿路高速巡航」与「野路跳跃」的综合时间成本，自动计算出最优的混合路线。
- **多候选吸附与广播范围修复**：
  - 起终点吸附范围扩大至 48 格（`MAX_SNAP_DISTANCE`），支持多候选最近道路接入。
  - `ItemTransportManager` 发包改为向当前维度（`ServerLevel`）所有玩家分发，彻底解决玩家在目的地时因未加载起点区块而看不到飞行物品的问题。
- **严格性能边界**：
  - 仅在宏观拓扑图（$V \le 200, E \le 500$）上执行，Dijkstra 带 500 步硬上限，50 条道路网络下单次计算耗时 $< 0.05\text{ ms}$。

## 2026-08-15：角色命名三风格化——西幻/中文/英文，殖民区级切换，只影响后续生成

**需求**（用户指令）：原单一中文名池（44 个，英译为拼音）种类太少、易重名，对外国玩家不友好，也不符合魔法世界观。升级为三种命名规则：西幻（拉丁/罗马单名，默认）、中文（姓+名）、英文（名+姓）；在市政厅 UI 切换；只影响后续生成的游客/NPC 名称，不影响已定下来的。

**决策**：
- **三个池**：西幻池 = 拉丁/罗马人名 413 个（66 男 + 347 女，有名无姓，源 `js.fantasy-names` MIT 许可的 `real/romans.js`，过滤重音/非 ASCII）；中文池 = 姓 50 × 名 50（组合 2500）；英文池 = 名 50 × 姓 50（组合 2500，名+姓 John Smith，非 Smith John——英语自然顺序，英文玩家最熟悉）。
- **存储格式（lang key，双语自适应）**：
  - 西幻：`wandscape.character_name.fantasy.<i>`，zh=音译（塞西莉亚式），en=原名。
  - 中文组合：`wandscape.character_name.zh.s<i>.g<j>`，拆成 `zhs.<i>`（姓）+ `zhg.<j>`（名）两条 lang key，zh 客户端渲染汉字（王明）、en 客户端渲染拼音（Wang Ming）。
  - 英文组合：`wandscape.character_name.en.f<i>.l<j>`，拆成 `enf.<i>` + `enl.<j>`，zh 渲染音译（约翰·史密斯）、en 渲染原名（John Smith）。
  - 分隔符也走 lang key（`sep_zh`/`sep_en`）——组合名称由服务端生成、客户端渲染，分隔符必须由客户端语言决定，不能服务端写死。
- **音译规范**：女名末字用柔美字（娅/娜/莉/莎/琳），男名用中性阳刚字（克/斯/特/恩/尔）；已有标准译名（马库斯、奥古斯都、提比略、约翰、史密斯）直接用，生僻名按拉丁音规则转写。
- **旧存档兼容**：旧 44 个 `wandscape.character_name.<i>` key 的 lang 条目与回退逻辑永久保留。
- **殖民区级设置**：存 `ColonySavedData`（新殖民区默认西幻），`ColonyApi` 暴露 `get/setNamingStyle`；市政厅 UI 三按钮切换，`TownHallNameStylePacket` 传输。
- **生效范围**：游客生成时读殖民区风格（`TouristSpawnSystem`，含单名池重名重试——排除当前殖民区在线游客的显示名，最多重试 8 次）；法师 NPC 无自定义名时按生成位置检测所属殖民区用其风格，殖民区外用默认西幻（`WandscapeNpc.onAddedToLevel`）；酒馆招募法师继承游客名。
- **单测**：key 格式/解析/池完整性为纯逻辑（不触 MC Language 运行时），`CharacterNamesTest` 覆盖；`localizedString` 的 `getString` 依赖 Language 运行时，留待集成测试。

**为什么**：单名池（西幻）重名概率随游客数上升，组合池（中文/英文 2500 组合）天然几乎不重名；lang-key 方案保持"服务端存 key、客户端按自己语言渲染"的既有架构（旧 44 名即如此），切换风格不影响已存储的 key；法师按位置归属殖民区与游客按殖民区生成保持一致。

**影响**：新殖民区游客/法师默认西幻名；玩家可在市政厅随时切换风格，后续新名字跟随，旧名不变。

## 2026-08-15：休闲/ATM 三值只加一次——酒店成为唯一可重复的满意度来源

**需求**（用户指令）：休闲/ATM 不应无限制加三值（精力低/钱低就能反复刷）；能重复加三值的只有酒店（住几晚加几次，可预测，模型更稳定）。

**决策**：
- `TouristSimulation.performRelaxInteraction` / `performAtmInteraction`：仅当建筑不在游客 `visitedBuildings` 中（首次访问）才 `fillBars`；重访（豁免 visited 去回精力/取钱）只回精力/取钱，不再加三值。
- 仍保留豁免 visited（精力<25%/钱包<初始1/4 时可反复去）——否则精力/现金耗尽后无法自救，游客会卡死；只是重访不再贡献三值。
- 覆盖两条路径：实体（`TouristMoveGoal`，交互后 `addVisitedBuilding`）与影子 sim（`TouristSimSystem`）都走 `performRelaxInteraction`/`performAtmInteraction`，改一处即可。

**为什么**：三值供给变成"每建筑一次"（与商店/服务一致）+ "酒店每日一次"（唯一可重复）。模型确定、可预测，sim 与游戏对齐；同时去掉"取钱 → 加奇观""坐长椅 → 加舒适"这种角色混杂的隐性乘数。

**影响**：休闲建筑的三值不再可刷（靠精力门控刷条已被移除）；酒店每早 `grantHotelNightStay` 成为推动游客满意度的核心引擎，酒店质量（三值 × 床位 × 住晚数）对殖民地升级至关重要。

## 2026-08-15：经验曲线调整——低于殖民地等级给部分经验 + expToNext 二次曲线

**需求**（用户指令）：达到节奏目标 5级≈5天、10级≈12天、15级≈22天、20级≈34天、30级满≈68天；此前"游客级<殖民地级→0 exp"的自限效应使前期升级过慢。

**决策**：
- `ColonyLevelManager.computeExpContribution`：游客级 < 殖民地级 → `COLONY_EXP_EQUAL_LEVEL / 2`（原为 0）。
- `ColonyLevelManager.expToNext(level)`：`500×(level+1)` 线性 → `300×(level+1) + 55×(level+1)²` 二次曲线（前期便宜后期贵）。
- Config：`COLONY_EXP_EQUAL_LEVEL` 250→700，`COLONY_EXP_ABOVE_LEVEL` 500→1400。

**为什么**：游客按生成时殖民地等级生成（40% 低一级），停留 2-4 天期间殖民地又升级，导致大量满条游客离场时"低于殖民地"给 0 exp → 前期经验流枯竭、升级极慢（原 L5@116 天）。给部分经验消除自限效应；二次 expToNext 让前期快、后期慢，避免整条曲线同速变快。

**影响**：满条游客几乎总能贡献经验（低一级给一半、同级全量、高一级双倍）；升级曲线前快后慢，与解锁档节奏匹配。sim_tourist.py 同步实现。

## 2026-08-15：酒店满意值由「入住即时结算」改为「每晚晨起结算」

**需求**（用户指令）：入住酒店不再即时加满意值；改为每天晚上入住酒店（住店一晚）结算一次满意值，数值用该旅店建筑的三值（comfort/magic/wonder），与参观建筑一致。

**决策**：
- **入住不再结算满意值**：`tryHotelCheckIn`（实体）/ `interact` 入住分支（sim）/ `checkInAtNight`（快进夜）三处只登记入住 + 发叙事，删掉原来的 `fillBars` + 记「入住」行程。
- **每晚晨起结算一次**：新增 `TouristSimulation.grantHotelNightStay`（`fillBars` 旅店建筑三值 + 记「住宿」行程），三处晨起路径各调一次——`HotelStayHandler.wakeUp`（实体心跳）/ `simStep` 晨起分支（sim）/ `wakeUpShadow`（快进夜）。与精力回满、住店晚数 +1 同节奏，一晚一次。

**为什么**：住店客机制的奖励应与「住店」绑定而非「入住」——入住只是登记，每晚住宿才是满意值来源；一次性入住白嫖整段停留的满意值既失真也让短期入住游客与长期住店客无差别。

**影响**：首次入住当晚不涨满意值，次日起每晚晨起 + 该旅店建筑三值（`fillBars` 封顶需求，与参观一致）；满条游客满条当晚离场、不走到晨起结算，不受影响。

## 2026-08-14：游客移动分层路网寻路与优先贴路闲逛

**需求**（用户指令）：游客移动不应仅依赖原生纯 A* 寻路（直接走直线翻山越岭，无视玩家建造的道路），要求在前往建筑与闲逛时优先寻找并沿着 `RoadNetwork`（`RoadEdge`）移动。

**决策**：
- **分层路网路标导航（`RoadWalkPlanner`）**：
  - 前往建筑/长途移动时，通过 `RoadWalkPlanner.plan` 调用 `RoadRouter` 获取道路骨架路线，沿线每隔 16 格采样为中间路标点（Waypoints）。
  - `TouristMoveGoal.tickOutdoorNav` 逐段推进 Waypoint，让原版 `PathNavigation` 只负责 16 格内的短距离局部寻路，既贴着大路行走，又防止远距离寻路由于节点预算超标而失败。
  - 若无可用道路或绕路严重，自动平滑回退为原版直接寻路。
- **闲逛优先从 `RoadNetwork` 取点**：
  - `TouristMoveGoal.pickWanderTarget` 优先在 `anchor` 范围内的已建成 `RoadEdge`（样条道路与直线道路）采样路径点作为漫步目标，使游客自然聚集在城镇街道和广场上漫步，无路时回退到标签扫描与微闲逛。

**为什么**：道路是城镇规划的骨架，游客沿大路行进让城镇呈现出真实的市民/游客生活气息，分段 Waypoint 机制在零卡顿的前提下实现了高保真的沿路移动。

## 2026-08-14：物品运输恢复贴路寻路飞行——轻量拓扑寻路 + 5 倍高速 + 零卡死保护

**需求**（用户指令）：8282876b 一刀切将物品运输改为直线飞行，删除了沿玩家自建道路寻路贴路飞行的核心玩法与视觉机制；要求恢复贴路寻路飞行，但解决旧版速度过慢（1~2 格/秒）与服务器 O(B²) 卡死风险。

**根因分析与历史教训**：
- 旧版 `RoadRouter` 之所以引发服务器事故卡满 60 秒，在于试图用 BFS 懒扫描地表所有 `custom_roads` 方块并组织成数千节点的 Blob，并在主线程每次寻路时执行两两遍历（`allEndpoints × allPoints`，O(B²) 复杂度）构建全连接图。
- 旧版速度模型过慢（离路 20 ticks/格 = 1 格/秒，贴路 10 ticks/格 = 2 格/秒），导致长途运输严重拖延施工与补货节奏。

**决策**：
- **纯拓扑路网寻路（零方块 BFS，微秒级计算）**：
  - 寻路直接基于已建成的 `RoadNetwork`（`RoadEdge` 拓扑端点与相交节点），不扫描地表方块。节点规模受控在数十至上百以内。
  - 起终点投影接驳（`RoadProjection`）限制最大距离 32 格，超出直接直飞。
  - A* 寻路设置 300 步硬上限（`MAX_SEARCH_STEPS`），搜索失败/无路时 100% 优雅回退到直飞（`TransportRoute.direct`）。
  - 绕路比检查（`MAX_DETOUR_FACTOR = 1.8`）：若路网总耗时超过直飞耗时 1.8 倍，智能选择直飞，防止严重反向绕路。
- **5 倍高速与流畅度提升**：
  - 贴路巡航速度提升为 **2 ticks/格（10 格/秒）**（原为 10 ticks/格），离路速度提升为 **4 ticks/格（5 格/秒）**（原为 20 ticks/格）。
  - 新增 `Config.transport.ticksPerBlockOnRoad` 与 `Config.transport.ticksPerBlockOffRoad`，服主可自定义调节。
- **客户端平滑贴路渲染**：
  - `TransportItemEntity` 沿 `TransportRoute` 的多段 `SplineLeg` 平滑飞行，贴路段离地 0.4 格平飞并伴随微光流光粒子，离路段平滑抛物线跳跃。
  - `TransportStartPacket` 采用紧凑流式序列化传输 route 样条数据。

**为什么**：道路是玩家精心建造的基础设施，物流贴路飞行是核心正反馈。彻底剥离方块扫描并收敛为稀疏拓扑图 A*，既完全排除了 O(B²) 卡死风险，又保留并升级了贴路飞行的视觉与功能价值。

## 2026-08-14：殖民地离线冻结（colony.runWhenPlayerOffline）

**需求**（用户指令）：服务器默认没人时小镇也在运行；要求加一个 Config「不在线是否运行」，默认 true，设为 false 后玩家不在线就不运行它的殖民地（全部自动化暂停 + 原地冻结 + 上线恢复）。

**决策**：
- **Config 开关**：`colony.runWhenPlayerOffline`（默认 `true` = 服务器无人也运行；`false` = 创始人不在线则其殖民地冻结）。
- **激活判定**：`engine/colony/ColonyActivation.isColonyActive(colonyId)`——配置为 true 恒激活；false 时查 `ColonyApi.getFounder(colonyId)`，创始人玩家在线才激活；**无创始人视为始终激活**（历史/命令创建的殖民地无法判定在线状态，避免被误冻结）。
- **core 访问边界**：`EntityOps` 增 `isColonyActive(UUID)`，`WandscapeEntityOps` 委托给 `ColonyActivation`——core 的调度/执行系统不 import MC，经边界接口取 engine 判定（同 `isFollowing` 模式）。
- **冻结范围（全部自动化）**：
  - NPC 建造/生产：`SchedulerSystem` 跳过冻结殖民地不分配任务；`TaskExecutionSystem` 跳过冻结殖民地 NPC 不推进执行（原地冻结，保留队列/步骤/async future）；`BuildingTaskSource` 不为冻结殖民地建筑发布新任务（排队工作与占地保留）。
  - 游客经济：`TouristSpawnSystem` 冻结殖民地不生成/不清除游客；`TouristSimSystem` 冻结殖民地 shadow 不 sim/不实体化/不离场/不快进夜。
  - 每日结算：`DailySettlementSystem` 跳过冻结殖民地（商店补货/统计随之停）。
- **原地冻结而非清理**：冻结期间游客停留、NPC 任务挂起、占地保留，创始人上线后全部自动恢复继续——不做离场/取消的清理动作。

**为什么**：无人在线时殖民地仍全速运转（游客来逛、NPC 建造、每日结算）会让「挂机党」的殖民地离线也攒钱/升级，且空服持续跑 force-load 造/产白白耗资源。按创始人判定满足「一人一殖民地」模型；无创始人兜底防误冻。

**影响**：关掉开关后，创始人不在线的殖民地完全冻结（不新游客/不建造/不结算），上线瞬间恢复；游客占位/排队、NPC 任务、建筑 footprint lease 全部保留。

## 2026-08-27：离线收益折减取代全有全无冻结（colony.offlineIncomeMultiplier）

**需求**（用户指令）：`colony.runWhenPlayerOffline` 一个 bool 决定离线殖民地要么 100% 收入要么 0%——100% 对在线玩家不公平、0% 对离线玩家太不友好。改为一个 float 收益系数（默认 0.2 = 20%）：创始人不在线时，**商店利润、服务设施元素产出、殖民地经验获取**三路收入降到 20%，殖民地照常运行。

**决策**（取代 2026-08-14 的 `colony.runWhenPlayerOffline`）：
- **Config**：`colony.runWhenPlayerOffline`（bool）→ `colony.offlineIncomeMultiplier`（double，`defineInRange [0,1]`，默认 0.2）。0 = 完全等价旧 `false`（整镇冻结）；1.0 = 等价旧 `true`（离线满收益）。旧 key 在 TOML 中成为孤儿条目，不迁移。
- **激活判定**：`ColonyActivation.isColonyActive` 改为「收益系数 > 0 即运行」；新增 `getIncomeMultiplier(colonyId)`——创始人在线/无创始人/无服务器 → 1.0，否则返回配置值。
- **只折收入侧，不折消耗侧**：物品售价不变（商店按「成本 + 折减利润」入账，永不亏损）；NPC 建造/商店补货的元素消耗照常 100%。离线挂机净收益自然低于在线，消耗不打折是「挂机收益降低」的代价，不额外动 BuildingTaskSource/restock。
- **折减点（三个收益来源，各一处统一收口）**：
  - 商店利润：`ShopStockManager.purchase`（profit = 售价 − 元素成本，折减后总收入 = 成本 + round(利润×系数)）。
  - 服务产出：`TouristSimulation.performServiceInteraction`（`elementOutput` × 系数；TouristMoveGoal 服务气泡同步显示折减后数量）。
  - 殖民地经验：`TouristSpawnSystem.grantExperience` + `TouristSimSystem.grantExperience`（live 与 shadow 双路径都折减；`ColonyLevelManager.addExperience` 不动，未来非游客经验源不受影响）。
  - 游客/影子 sim 的商店与服务交互都走 `ShopStockManager` / `TouristSimulation` 同一条收口，实体路径与卸载 sim 路径一致。
- **纯逻辑**：折减计算收敛为 `ColonyActivation.scaleIncome(value, m)`（四舍五入、不超原值）与 `scaleProfit(cost, profit, m)`（成本不变、永不亏损），单测 `ColonyActivationTest`。

**为什么**：全有全无对两端都苛刻——离线 100% 让挂机党离线攒钱升级碾压在线玩家，0% 让短期不上线的玩家殖民地彻底停滞（游客占位、NPC 任务、建筑 lease 全冻结，回来才恢复）。连续系数让服主在「公平」与「友好」间细调：默认 0.2 保留挂机价值但显著低于在线；0 保留旧硬冻结选项；1.0 保留旧全速选项。只折收入不折消耗是用户明确指定的边界，避免商店亏损与改动面扩散。

**影响**：默认值从旧 `true`（离线 100% 收入）变为 0.2（离线 20% 收入），现有服务器离线经济产出显著下降；愿恢复旧行为的服主设 1.0 即可。

## 2026-08-14：玩家睡觉跳过夜晚 → 游客夜间批量快进

**需求**（用户指令）：游客夜晚必须找旅馆睡觉否则消失；玩家睡觉会跳过夜晚（NeoForge `SleepFinishedTimeEvent`，时间瞬间从夜间跳到次晨 dawn），离场窗口 18000–24000 被整体跳过，本应离场的游客滞留、人口每晚只增不减。要求睡觉跳夜那一刻在 sim 状态快速模拟「睡→醒」这一整段。

**决策**：
- **触发点 = `SleepFinishedTimeEvent`**（`TouristSimSystem` 订阅，已查 `EventHooks.onSleepFinished` post 到 `NeoForge.EVENT_BUS`）：事件在 `ServerLevel.tick` 的 `setDayTime(EventHooks.onSleepFinished(...))` 参数求值阶段触发，此时 `level.getDayTime()` 仍是旧时刻，`getNewTime()` = 次晨 dawn 绝对时刻 → `skipped = getNewTime() - getDayTime()` 即被跳过的夜晚 tick 数。
- **批量结算，不逐 tick 跑 `simStep`**：`simStep`/`checkDeparture`/`interact`/`selectNextTarget` 都直接读 `level.getDayTime()`，逐 tick 快进需把模拟时钟穿透到共享的 `TouristSimulation`（会侵入实体路径）。改为对影子注册表做一次「夜间结果批量结算」：`nightOutcome` 纯函数判定（到点→离场；满条→当晚离场；住店客→晨起；无旅店→找旅店，有→入住+晨起，无→离场），复用 `findHotelTarget`/`fillBars`/`depart`/`addVisitMemory`，行为与真实夜晚一致。
- **覆盖所有游客（含观察中实体）**：影子注册表是人口权威源；观察中的活实体快进后 `importToEntity` 推回（否则下一 tick `exportToShadow` 覆盖影子、撤销快进），住店客实体补 `stopSleeping` 解除睡姿。
- **`/time set day` 等命令跳夜不触发**该事件，不在本次范围。

**为什么**：不快进则「夜晚必须找旅馆否则消失」规则在玩家天天睡觉时完全失效——游客只进不出，人口积压到上限，经济/排队失衡。批量结算保持规则简单（有旅店入住、无旅店离场），避免距离/走位判定。

**影响**：睡觉跳过夜晚后游客处于与真实夜晚过去后一致的终态（离场/入住/晨起照常发生）；观察中游客在醒来瞬间被推到夜间终态位（如回入住前站位），属「一夜过去」效果。

## 2026-08-14：建筑包围盒区域不自然刷怪

**需求**（用户指令）：加一条设计——建筑包围盒区域内不会刷怪。

**决策**：
- **拦截点 = `MobSpawnEvent.SpawnPlacementCheck`**：自然刷怪每个候选位置做刷怪规则判定（`SpawnPlacements.checkSpawnRules`）时触发。比 `PositionCheck` 更前置——位置一进包围盒即 `setResult(FAIL)`，实体根本不会被创建（已查 NeoForge 21.1.233 `MobSpawnEvent` 源码确认：本版本**没有**旧版 Forge 的 `FinalizeSpawn` 事件，只有 `SpawnPlacementCheck`/`PositionCheck`）。
- **只拦 `MobSpawnType.NATURAL`**：刷怪笼/结构刷怪/指令/spawn egg 走原版机制；NPC/游客经 `addFreshEntity` 生成，天然不受影响。
- **建筑过滤 = 完好且运营中**：`BuildingApi.getBuildingAt(pos)` 查到建筑且 `!isShutdown() && isStructureIntact()` 才拦截——建造中/破损/停摆建筑不提供安全区（与守卫/袭击判定一致）。
- **查询走 `WandscapeApis.getBuildingApi()`**：跨模块事件订阅统一 API + EventBus，不跨包引用 SavedData；`getBuildingAt` 按 chunkIndex 快速命中，无建筑区块 O(1) 返回，刷怪热路径开销可忽略。
- **Config 开关**：`building.noSpawnInBuildingArea`（默认 true），服主可关。

**为什么**：玩家建起的城镇夜里在建筑内部刷怪很烦（封闭建筑内部黑暗 → 原版怪直接在脚下生成）；让运营中的建筑成为安全区，既保留刷怪笼/结构刷怪等原版机制，又让殖民地「建起来就安全」。

**影响**：完好运营中的建筑包围盒（含 Y）内不再自然刷怪；建造中/破损/停摆建筑照常刷怪；开关关闭即回退原版行为。

## 2026-08-14：游客长椅/交互点重启恢复与半砖防抖防摔死

**需求**（用户反馈）：重启服务器后为夜晚，三个长椅上都有游客在休息，但仍有新游客试图坐上椅子，随后上下剧烈摇晃并被摔死。

**根因分析**：
1. **Spot 占用未恢复**：`TouristSpotManager` 为内存态单例。重启后虽然 `TouristEntity` 读档还原了 `occupiedSpot`、`currentActivity` 和 `activityTicks`，但 `onAddedToLevel` 未调用 `TouristSpotManager.claimAt` 恢复占用，导致长椅被判定为 0 占用，其他游客（特别是夜晚精力低需要 relax 的游客）仍会选择长椅并重复 claim spot 0。
2. **Goal 活动未恢复**：`TouristMoveGoal` 在读档后为新对象，`performingActivity` 为 `false`，未感知实体已有的活动，导致重新规划寻路。
3. **排队高频 `setPos` + 半砖震荡**：`TouristMoveGoal.navigateToQueueSlot` 在到达目标后**每帧（20次/秒）** 调用 `setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5)`，而长椅是 `bamboo_slab`（高度 0.5 格）。MC 物理引擎每帧将实体推到 0.5 格表面，下一帧又被 `setPos` 拉回整数 Y（陷入方块），导致 20Hz 垂直剧烈抽搐；`fallDistance` 持续叠加并在落地判定时结算超高跌落伤害将游客摔死。

**决策**：
- **读档恢复 Spot 占用与活动**：
  - `TouristEntity.onAddedToLevel` 检测到 `occupiedSpot >= 0 && getCurrentActivity() != null && activityTicks > 0` 时，调用 `TouristSimulation.claimSpotAt` 恢复 `TouristSpotManager` 占用记录（失败则安全清空活动字段）。
  - `TouristMoveGoal.start` 检测到进行中的活动时，直接恢复 `MoveMode.VISITING_BUILDING`、`indoorPhase = true`、`performingActivity = true`、`claimedSpot` 和 `interactPoint`，`startBuildingVisit` 对进行中活动直接 return 保持原位继续计时。
- **精确地面高度 + 消除排队每帧 `setPos`**：
  - 新增 `TouristSimulation.getFloorSurfaceY`，根据方块碰撞箱（`getCollisionShape`）精确计算 Slab、Stairs、Carpet 等非完整方块的站立表面 Y，使实体稳固踩在半砖顶面。
  - `navigateToQueueSlot` 仅在首次到达站位时对齐一次坐标（锁定 yaw），到位后保持静止，严禁每 tick 重复调用 `setPos`。
- **摔落伤害与动量保护**：
  - `TouristEntity.hurt` 对 `DamageTypes.FALL` 免疫伤害，彻底杜绝坐标对齐/微调/半砖碰撞带来的意外暴毙。
  - 所有 `setPos` 及传送调用处同步调用 `tourist.resetFallDistance()` 与 `tourist.setDeltaMovement(Vec3.ZERO)`。

## 2026-08-13：Tab 改为只折叠新手引导，光标抬起收归 C 键

**需求**（用户指令）：Tab 在引导激活时被 `GuideSession` 折叠占用，用户实测 Tab 根本抬不起光标；要求 Tab 不再负责抬/放光标，只折叠/展开新手引导。

**决策**：
- **Tab（`GUIDE_FOLD_TOGGLE`，原名 `PANEL_CURSOR_TOGGLE`）**：只在 `GuideSession.shouldShow()`（引导显示期）折叠/展开引导卡片；引导结束后 Tab 不接管，恢复原版玩家列表。`onClientTickPost` 的 `keyPlayerList.setDown(false)` 抑制也门控在引导显示期。
- **C（`RAISE_CURSOR`）**：专属「抬起光标」键，面板打开时**按一次抬起（`liftCursorForUI()`）、再按一次收回（`releaseCursorToGame()`）**，形成抬/放切换，不再受引导状态影响——欢迎语本就说「按 C 抬起鼠标」，补上后言行一致，且收回不再只能靠 ESC/切子模式。
- **删除 `WandscapePanelState.toggleCursor()`**：Tab 唯一调用方移除后成死代码，直接删除（建筑条开/关仍由进子模式/双击建筑触发，无功能损失）。

**为什么**：引导占 Tab、Tab 又得管光标，两个功能挤一个键导致引导期「Tab 失灵」的困惑；一个键一个职责，C 抬光标、Tab 折叠引导，互不干扰。

**影响**：建造子模式里 Tab 不再开/关建筑栏（进入建造即自动开栏，双击也可进出放置）；光标抬放唯一入口是 C。历史决策 2026-08-10「Tab 抬/放光标（替换已移除的 C 键）」被本决策推翻——C 键回归并独占抬光标。

## 2026-08-13：建造中建筑可撤销——未开工移除、开工拆掉并退还材料

**需求**（用户指令）：放错建筑后悔了却撤销不了。要求建筑「等待材料/建造中」时准心对准建筑 HUD 的第一个按钮（修复）换成「撤销」；未开工只停，开工拆掉已建部分并返还资源。

**决策**：
- **`BuildingApi.cancelBuilding(buildingId)` 新增**：仅未完工（`!hasEverCompleted`）且非拆除中的建筑可撤销。
  - **未开工**（`!isConstructionStarted`）：材料未从仓库扣除，直接 `unregisterState` 移除建筑，**不返还**（无东西可还，也杜绝「放置→立即撤销→白嫖材料」）。
  - **开工**（`isConstructionStarted`）：材料在施工开始时已整批 commit 到仓库，先 `refundMaterials` 全额退还（`EnqueueHelper.computeMaterialCounts` → `ColonyItemBank.add` 方块物品，键=裸 block id，与 `build:place_structure` 的 request_resource 消耗键一致），再 `demolishBuilding` 异步拆掉已建部分（复用既有 NPC 拆除管线）。
- **UI**：准心 HUD（`BuildingDebugOverlay`，V 面板开启时对准建筑显示）里，建造中/等待材料建筑的第一个按钮「修复」→「撤销」（金色），点按发 `BuildingActionPacket(action=cancel)`；完成/损坏/关闭建筑不变（损坏→修复、关闭→营业/重启、拆除）。异常报告不显示撤销按钮。

**为什么**：材料是「施工开始时整批扣一次」，开工后退全额=正好退掉已扣的；未开工没扣过，退全额会造成刷材料。拆除走既有管线，与拆完整建筑一致（不恢复地形，仅清掉已建块）。

**影响**：放错建筑可在建造期撤销；已建成的建筑不可撤销（仍走拆除）。建造中/等待材料建筑准心 HUD 第一个按钮由「修复」变为「撤销」。

## 2026-08-13：删除路网图路由——RoadRouter/blob/TransportRoute 整套移除，改方块条件

**需求**（服务器事故诊断）：服务器（Linux，多模组）上玩家建 3-4 条路后单个 tick 卡满 60 秒被看门狗杀服。线程转储指向 `RoadRouter.buildGraph`。根因：buildGraph 的断点桥接是「端点数 × 全部点数」双层循环，blob 边界点同时进两边 → O(B²)；`custom_roads` 标签含 stone/cobblestone 等常见方块，RTS 模组/铲平铺出的大片路面全被懒扫描成 blob，B 到数千后单次 buildGraph 数秒，每次 `planWithRoads` 都重算 → 服务端持续卡死。本地只画几条路（RoadEdge 网络很小）故不触发。

**决策**：
- **整体删除图路由**：`RoadRouter`（buildGraph/Dijkstra/断点桥接/虫洞）、`RoadBlobCache`+`RoadBlobExplorer`（懒扫描）、`RoadRoutingHelper`、`RoadWalkPlanner`、`TransportRoute`/`SplineLeg`/`SplinePointCache`/`RouteSegment`、`RoadApi.getBlobCache`、`RoadEdge.detailedPathCache`——不留桩，杜绝死灰复燃。
- **替代为方块条件（参考 MineColonies：路=方块+标签，无抽象图）**：
  - 物品运输改直线飞行：`ItemTransportManager` 沿直线采样地表方块（`custom_roads` 标签，上限 128 采样），≥1/2 是路面 → 上路 5 tick/块平飞，否则离路 10 tick/块抛物线；`TransportStartPacket` 改发 from/to/duration/onRoad。
  - 游客/NPC 移动改 vanilla A* 直寻（原回退路径直接提升为正式路径）；游客保留方块条件闲逛（目标选路块、锚点沿路漂移、硬上限）并新增「脚下非路面减速 ×0.8」。
- **保留**：`RoadNetwork`/`RoadEdge` 作元数据（游客出生/救援锚点、成就计数）、样条编辑器与 `road:build_segment` 建造任务（路照建，成装饰地形）、`custom_roads` 标签（方块条件）。

**为什么**：图路由的价值（游客/物品沿路移动）与维护成本（O(B²) 卡死风险、识别不出"石=路"的误伤）不成比例；所有消费方本就有无图回退（直线飞/vanilla A*/传送），删除后功能无损且卡死根因从代码层面消失。

**影响**：物品飞行不再精确贴路（直线+速度分级）；游客长途不再贴路走（A* 直寻+离路减速）；路网数据仅剩元数据用途。

## 2026-08-13：商店补货合成任务队首插入——缺货商品不被建材合成阻塞

**需求**（用户指令）：shop 补货时商品不足会自动发布合成任务，但该任务追加到 Workstation 队尾，会被前面排队的建材合成长期阻塞，游客买不到货；要求补货任务放到队首。

**决策**：
- **`BuildingApi.enqueueWork` 新增 `atFront` 重载**：`enqueueWork(buildingId, work, atFront)`，原两参版本改为 default 委托 `atFront=false`。`atFront=true` 时跳过队尾合并、`addFirst` 插入队首；容量检查（queue 容量 / 施工 5 上限）与顺序不变。
- **`ResourceSupplySystem.enqueueSynthesize` 新增 `atFront` 参数**：旧重载委托 `false`（建材短缺的通用供应路径维持原队尾行为）；`ShopStockManager.requestSynthesize` 传 `true`——这是唯一「补货缺货→合成」入口，覆盖动态补货与 pendingRestock 重试两条路径。
- **队首插入不参与同类合并**：`mergeSameRecipeTail` 只在队尾生效，队首任务跳过合并（合并会把任务折回队尾、抵消插队）。同物品短缺已由 `countSynthesizeInFlight` 按殖民地去重，不会因跳过合并而刷屏。

**为什么**：工作站队列是 FIFO（`BuildingTaskSource` 用 `pollFirst` 取任务，WorkItem 的 priority 只影响全局池调度、不改变建筑内队列顺序），补货合成若排在建材料后面，缺货商品要等前面所有合成完成才开工，游客持续买不到货。队首插入让缺货补货抢在建材前开工；通用建材供应不插队，保持原排队语义。

**影响**：商店缺货时补货合成优先执行；玩家手点的其他合成/施工入队顺序不受影响（默认仍队尾）。

## 2026-08-13：补货合成队首插入参与队首同类合并——不再堆积连续 *7/*9

**需求**（用户指令）：补货发布的合成任务加在队首，不走队尾合并同类项，连续多个同物品 *7、*9 挨在一起不被合并；要求队首也做同类合并。

**决策**：
- **`mergeSameRecipeHead` 新增**：队首插入（`atFront=true`）时若队首是相邻同签名生产任务，折进队首（count/channel_ticks 求和、保留队首位置），否则照旧 `addFirst`。合并消耗零队列槽位，仍在容量检查前执行。
- **共用合并逻辑**：`mergeWork`（求和）+ `mergeable`（签名判定）抽成私有方法，`mergeSameRecipeTail` 与 `mergeSameRecipeHead` 复用，队尾/队首语义完全对称。
- **推翻上文「队首插入不参与同类合并」**：原理由「合并会把任务折回队尾、抵消插队」只对队尾合并成立；折进队首后任务仍在最前，插队语义不丢。`countSynthesizeInFlight` 只按「短缺量−在途量」出数，重试会追加新条目（先 x7 再补 x2 → 队首 x2 紧挨 x7），相邻同物品却因跳过合并而堆积。

**为什么**：补货缺货→合成是紧急路径必须插队，但同一物品连续补货/重试不应在队首产生多个相邻条目占槽位；合并进队首既保紧急又去重，`countSynthesizeInFlight` 与合并协同（合并后 inFlight 计数仍是总和）。

**影响**：连续补货短缺合并成一个队首条目（count 累加）；其他物品的队首插队行为不变。

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

## 2026-08：魔力强化（magic_enhance）独立魔法输出乘区

**需求**：赐福/背水此前给 vanilla 力量（`DAMAGE_BOOST`），但 NPC 伤害全部走 `hurt()` 自定义伤害类型（光束 `wandscape:beam`、陨石 `indirectMagic`、L2 普攻 `wandscape:melee`），vanilla 力量只改 `ATTACK_DAMAGE` 属性、对纯法师完全无效。玩家实测后发现「给了力量却没加伤害」。

**决策**：
- 新增 MobEffect `magic_enhance`（魔力强化）：纯标记，倍率 = `1 + 0.2 × 等级`（I 级 +20%，独立乘区）。
- **为什么不用 attribute modifier**：SPELL_POWER 是 ECS 自定义属性（`EquipmentComponent`，非 vanilla `Attribute`），MobEffect 的 `addAttributeModifier` 挂不上；故在核算入口手动乘（`MagicSpellExecutors.magicEnhanceMultiplier`）。
- **应用范围 = 所有乘 SPELL_POWER 处**：伤害统一钩子 `NpcSpellPowerHandler`（光束/陨石/未来魔法自动生效；L2 物理普攻兜底也走此钩子，一并被放大——既定行为）+ 治疗 `castHeal`（治疗也吃）。
- 赐福/背水把 vanilla 力量替换为魔力强化（NPC 与玩家一致）；玩家暂无施法入口，`castForPlayer` 只给 buff 栏显示、无实际效果，等玩家施法回归后自动生效。

**为什么**：魔力强化作为第二独立乘区（`基础 × SPELL_POWER × 魔力强化`），与 SPELL_POWER 各自乘算，数值语义清晰；挂在统一伤害钩子保证任何未来魔法自动生效，不在单个魔法里写乘算（沿用 SPELL_POWER 的同款架构决策）。


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

## 2026-08：建筑任务队列按优先级分段排序

**需求**：玩家在工坊发布任务会排到 Workstation 队尾，被自动合成/补货任务堵住半天干不了。原队列是纯 FIFO，补货又用 `atFront` 队首插入，玩家任务永远排在最后。

**决策**：
- **优先级三段**：`WandscapeConstants.TASK_PRIORITY_PLAYER=80`（玩家手动发布的生产/采集）＞ `TASK_PRIORITY_RESTOCK=60`（商店补货，原 `atFront` 语义改由更高优先级段承担）＞ `TASK_PRIORITY_AUTO=40`（自动补产/采集短供）。
- **入队改按优先级段插入**：`enqueueWork` 不再 addFirst/addLast，改为 `insertByPriority` 把新任务插到本优先级段队尾（队列保持高→低有序，`dequeueWork` 的 pollFirst 恒出最高优先）；`mergeBandTail` 把同配方生产任务折进本段队尾同类项（取代旧队尾/队首合并）。删掉 `enqueueWork(buildingId, work, atFront)` 三参接口——紧急补货不再靠"插队首"，而是靠更高优先级段。
- **跨段不合并**：玩家/补货/自动的同配方任务分属不同段，不会互相合并；同段内连续同配方仍合并（count/channel_ticks 累加）。

**为什么**：优先级应编码在任务自身而非"插队"这类位置技巧；段尾插入保证同段内 FIFO 不饿死，段间严格按玩家＞补货＞自动执行。

---

## 2026-08-21：magic_station 落地——potion_station 更名 + 卷轴元素合成 + 药水配方归合成站

**需求**：P 阶段 C——把 potion_station 改为 magic_station，在其中用元素合成「物品形式的魔法卷轴」（SpellItem 绑定 magic_id，阶段 A 产物）。

**决策**：
- **类别 key 更名 `potion_station` → `magic_station`，存档 category 于加载时按 BuildingConfig 迁移**：`category` 持久化在 `BuildingSavedData`（TAG_CATEGORY）。纯改类别 key 会让旧存档已建「药水工坊」失配（交互落 default）。故 `BuildingSavedData.load` 中 category 一律以当前 `BuildingConfig.category()` 为准（type 有 config 就用 config，缺失回退存档值）——类别本就是建筑类型的派生属性，改名自愈无需专项迁移数据。建筑文件 id `potionstation1` 保留（type id 更名会孤儿化旧存档建筑）。
- **魔法合成消耗=仅元素**（`ColonyItemBank` 扣元素），无需空卷轴原料；**产物入殖民地仓库**（与 craft_wand/brew_potion 一致，卷轴写 CUSTOM_DATA magic_id 入库），不走玩家背包。
- **旧 mana/stamina potion 配方归属 crafting_station**：`craft_station=crafting_station`，随法杖配方在合成站 GUI 列出、走 brew_potion 蓝图（校验输入玻璃瓶）。**输出物品（`wandscape:mana_potion`/`stamina_potion`）仍不注册**（用户拍板不注册；产出入仓为数据条目、无图标，属已知残留记入 gaps）。
- **CraftingStationPacket 泛化**：RecipeEntry 增加 `type`（wand/potion）与 `extra_inputs`，合成站 GUI 按 type 路由 craft_wand/brew_potion、行内显示药水额外原料。

**为什么**：魔法卷轴是「装备给 NPC 的道具」，走殖民地仓储管线与法杖一致，且阶段 B 策略页正是从背包/殖民地取卷轴装备；把旧药水收编到合成站可保留已有配方数据而无需维护第二把酿造 GUI。未注册药水物品是刻意收敛（药水非当前主线，避免为幽灵配方注册无效果物品）。

**影响**：magic_station 右键打开卷轴合成 GUI；crafting_station 同时展示法杖+药水；旧存档 potionstation1 建筑自动以 magic_station 类别加载。

## 2026-08-22：仓库 GUI 原版化——真实 Menu + AE2 式只读仓库槽

**需求**：仓库 GUI 的玩家物品栏是手工绘制（`WarehouseScreen` 自绘 `renderSlot` + 手算坐标点击），缺少原版快捷键（数字键/Q/Shift/拖拽），且与一键整理类模组完全不兼容；Exchange 页是文本列表而非大箱子格子样式。要求对齐其他仓储模组。

**调研结论**（读 1.21.1 源码 + AE2/RS/InventorySorter 源码）：
- 原版所有快捷键由 `AbstractContainerScreen` 基于 `menu.slots` + `hoveredSlot` 自动处理；一键整理（cpw/InventorySorter）要求屏幕是 `AbstractContainerScreen` 且悬停槽在 `menu.slots` 里。
- 仓储模组共识：玩家物品栏 = menu 真槽（vanilla 语义）；仓库/终端物品 = 只读槽（`mayPickup=false`/`mayPlace=false`/`set()` 空实现）+ 自定义点击包（AE2 `ClientReadOnlySlot` + `InventoryAction`），大数量自绘数字（AE2 `AmountFormat`）。

**决策**：
- **打开链路从"数据包直开 Screen"改为真实容器流**：注册 `MenuType`，右键仓库建筑/市政厅按钮 → `player.openMenu` → 客户端 vanilla 流程构造 `WarehouseMenu` + `WarehouseScreen`；`WarehouseDataPacket` 退化为纯数据刷新（元素/物品列表）。
- **玩家槽 = 真 vanilla `Slot`**（`TabAwareSlot` 仅覆写 `isActive` 供页签显隐，保留全部 vanilla 语义）→ 快捷键与一键整理自动兼容。`quickMoveStack` 实现玩家槽 Shift 点击存入银行（`insertItems` 全叠，不受分页限制）；仓库槽返回 EMPTY（防整理模组搬动仓库）。
- **仓库槽 = AE2 式只读槽**（`WarehouseSlot`：mayPickup/mayPlace=false、set() 空实现、getItem 由 Screen 绑定 supplier）→ 天然免疫 Q 键、数字键、Shift、整理模组；点击全部走自定义 `WarehouseActionPacket`（光标取整叠/半叠、Shift 快速取出、光标存入/存入 1）。
- **光标同步走 vanilla 机制**：服务端 `menu.setCarried(stack)` → `broadcastChanges` → `sendCarriedChange` 自动同步客户端光标，无需自定义光标包；大数量（>64）进光标与 AE2/RS 一致。
- **Exchange 页 = 大箱子式 9×6 格子**：每格物品 + 右下角数量（`WarehousePager.formatCount`：<1000 原数，之后 K/M/B 缩写），搜索+分页纯客户端（`WarehousePager` 纯逻辑类可单测）。Overview 页保留元素面板+只读列表。
- **Q 键对仓库槽无副作用**（mayPickup=false → THROW 的 safeTake 空），玩家槽 Q 正常丢弃。

**为什么**：真 Menu 是"与一键整理兼容"的必要条件（模组要求 menu.slots 中的槽）；仓库槽若做成可写真槽，整理模组会把仓库条目搬进玩家栏（行为怪异），AE2 的只读槽 + 自定义点击正是为此设计。自定义点击包 + `setCarried` 同步光标既对齐仓储模组交互语义，又完全复用 vanilla 光标同步链路。

**影响**：旧交互（左键取 1/右键取 64/点击玩家槽直接存入）被光标语义取代（点击取整叠/半叠进光标、Shift 快速移动、光标点击存入）；`WarehouseActionPacket` 字段重构（containerId + action + itemId + nbt）；`ReplayScreenGuard` 拦截条件改为 `ReplayProtectedScreen` 标记接口（新 Screen 不再是 MedievalScreen 子类）；引导文案与 guide 文档更新为新交互。

## 2026-08-23：修复中立生物仇恨吸引——北极熊/铁傀儡/狼无端攻击 NPC + NPC 不还手

**需求**（用户指令）：北极熊、铁傀儡、狼会主动攻击 NPC 且 NPC 不还手。要求：1) 修掉这个「仇恨吸引」；2) NPC 受攻击时无论对方是不是 Enemy 都还手，除非攻击者是玩家或同殖民地 NPC。

**根因**：
- `HostileTargetingHandler` 对「含 Player 索敌 goal 的任意生物」追加无条件 `PlayerLike` 索敌 goal，没限定 `Enemy`。北极熊（`PolarBearAttackPlayersGoal`/`isAngryAt`）、铁傀儡（声望 `isAngryAt`）、狼（`isAngryAt`）的 Player 索敌都是**条件性**的（愤怒/声望），被追加的宽类 goal 却无条件——于是它们无端攻击 NPC。
- `SelfDefenseHandler` 只对 `Enemy` 记仇；`SelfDefenseExecutor.resolveTarget` 的仇恨分支再用 `isHostileTarget`（仅 Enemy）过滤；`canBeamHurt` 默认 `instanceof Enemy`——三层都要求 Enemy，导致中立生物攻击时 NPC 记不了仇、锁不了目标、光束也打不出伤害。

**决策**：
- **`HostileTargetingHandler` 只增强 `Enemy`**：非 Enemy（中立·防御生物）跳过，不追加 PlayerLike 索敌——它们的条件性索敌维持原版。
- **区分「主动索敌」与「受击反击」**：主动索敌（`isHostileTarget`/`nearestVisibleEnemyAround`）仍仅 Enemy；反击走新的 `WandscapeNpc.isRetaliationTarget(attacker)`（非玩家、非同殖民地 NPC 即可，不要求 Enemy）。`SelfDefenseHandler` 记仇与 `resolveTarget` 仇恨分支改用此判定。
- **`canBeamHurt` 放行当前仇恨目标**：默认仍 `Enemy`，但额外放行「当前仇恨目标」（UUID 匹配且未过期），使光束/普攻/SPELL_POWER 倍率三处统一能伤到被反击的中立生物；玩家与同殖民地 NPC 仍被 `isRetaliationTarget` 排除。

**为什么**：主动索敌维持 Enemy 是防 NPC 无端扫射和平中立生物；反击放开 Enemy 限制是让 NPC 被中立生物攻击时不再当沙包。`canBeamHurt` 仍是唯一伤害边界（光束/倍率/敌数三处共用），只在该钩子内加仇恨目标放行，边界不散落。

**影响**：北极熊/铁傀儡/狼不再主动攻击 NPC；若仍被打（如玩家引怪/狼护主），NPC 会记仇还手，直到仇恨过期（`guard.hateDurationTicks`）。

## 2026-08-25：复用工地面板给施工中道路 + 建筑/道路撤回（防刷）

**需求**（用户指令）：准心对准施工中的道路时，道路区域显示白框高亮，右键打开工地面板；面板加"撤回"按钮，能撤回建筑/道路并退料。用户强调**不能多次退料刷物品**。背景是 Road 与 Building 两套平行机制（幽灵/瞄准/面板/同步）重复劳动。

**根因（防刷的关键）**：`AsyncTransformExecutor.performSalvage`（engine/boundary/AsyncTransformExecutor.java:211）在原子操作层拦截 transform：把方块替换成不同类（拆除处 place→air）时 `Block.getDrops` 掉回仓库。所以**已放置方块被 salvage 返还一次**，`WandscapeBlockOps.setBlock` 本身不 drop。→ 退料铁律：**只退"未放置"的，已放置交给 salvage**，否则双重返还刷物品。建筑侧 `BuildingApiImpl.cancelBuilding` 已按此实现（用户已改），`demolishing` 标志同步置位保证重复撤回 no-op。

**决策**：
- **Road 复用 `ConstructionSiteScreen` / `ConstructionSiteDataPacket`**：给包加 `kind`（0=BUNDING/1=ROAD），服务端 `RoadSiteData.fromEdge` 组装 Road 版 packet（材料需求从 `RoadEdge.materialCounts`、供应状态沿用建筑同套 `ColonyItemBank`/`ResourceSupplySystem` 口径、`completed=status==COMPLETE`），客户端 `setClientHandler` 零改动复用该 Screen。
- **Road 撤回 `RoadApi.cancelEdge`**：撤段任务（`RoadEdge.segmentTaskIds` 需运行时填充，现为死字段）+ 退料 + 清方块 + 同步移除 edge 作**幂等墓碑**（二次撤回 no-op）。材料需求与段任务 id 在发布时写回 `RoadEdge` 并 NBT 持久化。
- **Road 清方块用直接 `setBlock(air)`（不走 transform 执行器，无 salvage）**：唯一返还路径是退料 → 只退一次。退料**仅当施工已开始**（≥1 个 footprint 格当前是道路材料方块）才全额，避免给"从未动工"的已发布任务退料刷物品。
- **瞄准/白框**：`RoadAreaSyncPacket.raycastUnderConstruction`（footprint AABB，镜像建筑 `raycastUnbuilt`）；`WandscapeHighlightRenderer` 画白框；ground 模式 `WandscapePanelController.onMouseButtonPreGrabbed`、overview 模式 `OverviewFlightController.performRaycast` 分别接入，右键发 `RoadInteractPacket`/`RoadWithdrawPacket`。

**为什么**：Road 与 Building 数据系统不同源（样条 vs 矩形框），不能硬合并数据模型；但**展示外衣**（瞄准/幽灵/面板）可共享——抽一条"施工工地"复用线，既满足用户"少做重复工"又不破坏建筑/道路各自的正确性。Road 与 Building 的退料机制结果一致（玩家整体拿回），只是落地路径不同（Building=salvage 已放+退未放；Road=无salvage+全退）。

**影响**：`ConstructionSiteDataPacket` 加 `kind`（codec 向后兼容由读端新字节）；`RoadEdge` 新增 `materialCounts`/`segmentTaskId` 持久化；新增 `findEdgeAt`、`RoadSiteData`、`RoadInteractPacket`、`RoadWithdrawPacket`；`RoadAreaSyncPacket` 加 `raycastUnderConstruction`+`RoadBoxHit`。施工中道路可瞄可退，已完成道路不显示白框、不开面板。

## 2026-08-26：NPC 魔力回复改为按上限比例——每 10 tick 结算回 1% 上限

**需求**（用户指令）：NPC 的魔力回复从「每 10 tick 固定回 1 点」改为「每 10 tick 结算一次，回最大魔力 × 1%」，回复速度与魔力上限成正比。

**决策**：
- `MagicState.tickRegen(maxMana, regenIntervalTicks, regenFraction)`：结算间隔 tick 不变（`npc.manaRegenTicks` 默认 10），单次回复量从 `+1` 改为 `+maxMana × regenFraction`；新增配置 `npc.manaRegenFraction` 默认 0.01（1% 上限）。
- 回复仍按上限封顶、满蓝时结算累计清零；施法锁占用期间回复照常（与每魔法 CD 冻结解耦）。
- 配置注释与代码注释只描述行为本身，不标注参考来源。

**为什么**：按固定点数回复时满蓝耗时 = 上限 × 结算间隔，随装备把 MAX_MANA 抬高而线性拉长——高魔力法师回蓝慢到失衡；按上限比例回复则满蓝耗时恒定（默认约 50s），数值随装备成长同步加速，无需按上限区间分段调参。

**影响**：`Config` 新增 `npc.manaRegenFraction`；`MagicState` 单测改为断言「每结算回 1% 上限」；旧存档无需迁移（结算累计 tick 字段与持久化键不变）。

## 2026-08-26：魔法分类收敛 + 敌数门控跟随策略组

**需求**（bug 报告 + 用户定案）：NPC 对单个目标放不出陨石。根因是 a455928b 让策略槽位放置不再校验分类匹配（`mayPlace 去分类匹配`），玩家可把任意法术放进任意策略组，但 `CastBrain.enemyCountGate` 仍按法术自己的 `MagicDef.category()` 判敌数门槛——门控与实际所在策略组脱节（meteor 拖进单体组仍被 aoe 的 ≥3 挡掉）。用户定案：单个法术 category 合并为 normal/special/altar 三种；策略组保持 4 个；敌数门控跟随策略组。

**决策**：
- `MagicDef.Category` 收敛为 `NORMAL`/`SPECIAL`/`ALTAR`，只表性质；四个进攻类（single_target/aoe/defense/support）并入 NORMAL。
- 策略组 = `EquippedMagicComponent` 4 桶（single_target/aoe/defense/support），玩家自由放置。
- `CastBrain` 引入 `SpellRef(MagicDef, group)`：`knownSpells` 从桶循环带回组，`select` 敌数门控与 `resolvePriority` 预设排序都按 `group` 判——单体组 ≤3、群攻组 ≥3、防御/支援无门槛。
- `MagicDef` 新增可选 `default_group`（默认策略组）：beam→single_target、meteor→aoe 等，供默认装备种子与 `equippableCategoryOf` 兜底装桶；缺省 → support。

**为什么**：category 在放置层面已名存实亡（不校验匹配），继续用它驱动门控必然踩坑；改为跟随玩家可见、可操作的策略组，门控与分组一致。陨石想打单体，把它拖进单体组即可，不必改每个法术的门控配置。

**影响**：spell JSON 的 category 全部改 normal + 补 default_group；CastBrain 接口改吃 SpellRef；铁魔法合成 def category 恒 NORMAL、targetMode/conditions 按组名；策略预设排序按策略组；MagicDefTest/CastBrainTest 同步更新。

## 2026-08-27：敌数门控不匹配 = 最低优先级降级，非硬禁用

**需求**（用户反馈）：敌数 > 3 时若只剩单体攻击魔法，NPC 也应施放；敌数 < 3 时只剩群攻魔法同理——不匹配的组不应被硬性禁用，只是优先级最低。

**决策**：`CastBrain.select` 不再 `continue` 跳过门控不匹配的法术，改为两态选择——敌数与策略组匹配的法术按原优先级扫描，命中即返回；全部不可用时回退到第一个通过其余全部检查（`castable`/目标规则/`conditions`）但门控不匹配的法术（`known` 已是优先级序，第一个即最高优先级候选）。`enemyCountGate` 语义从「硬门槛」变为「优先级分层」。

**为什么**：原实现把敌数门控当成硬性开关，导致「怪多但只有单体、怪少但只有群攻」时 NPC 一个法术也不放、只会基础攻击，浪费已装备的魔法。降级语义保留「敌数匹配优先」的意图（避免敌少时砸 AOE 浪费蓝、敌多时单发效率低），又消除僵局。`castable`/目标规则/`conditions` 仍是硬门槛，只有敌数门控是优先级。

**影响**：`CastBrainTest` 相关断言从 `assertNull`（不选）改为断言降级后仍选中；`docs/spell-casting.md` 5.2 敌数门控段落与二十三章同步更新。无存档迁移。
