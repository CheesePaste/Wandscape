# 架构决策记录表（ADR）

> 信息截至 2026-09-02 | Minecraft NeoForge 1.21.1

- **【何时读】**：准备修改核心架构设计、评估重大方案或探究某项反直觉代码的设计原因时。
- **【不包含什么】**：长篇复盘散文、已被代码类型/命名直接表达的平凡实现细节、已废弃且无参考价值的陈旧试验。

---

## 一、架构与重构决策

| 日期 | 决策摘要 | 一句话原因 (Why) | 关联模块 / 代码 |
|---|---|---|---|
| 2026-09-02 | **NpcData 转纯字段读模型 + 属性读写分离**：NpcData 由 interface 转 `record` 只放字段，属性读面唯一化为 `attributes()`（effective 全量，含隐藏），删全部逐属性读法（getSpellPower/getWorkSpeed/getSpellSpeed/getArmorValue/getMaxHealth/getMaxMana 共 6 个）；写路径完整保留在 `NpcAttributesApi`（setNpcAttributes/setNpcLevel/trainNpc/levelUpNpc），其读桩 getNpcAttributes 删除；scepterHostileRange/mageHutRestTicks 归位各自域 API。 | 属性增减不再改读模型形状；读写边界清晰（单实体快照投影=字段、跨实体/改状态=API 方法）；兑现 ledger 悬置的「读面二选一去重」裁定。 | `content/npc/data/NpcData`, `api/NpcAttributesApi`, `api/NpcApi`, `api/ScepterApi`, `api/MageHutApi` |
| 2026-09-02 | **原版私有字段改 NeoForge AT 读取**：`NearestAttackableTargetGoal#targetType` 经 AccessTransformer 提为 public 直读，AT 失效时禁用增强而非反射兜底。 | 消除反射读取原版私有字段的最后一处残留，维持零反射契约。 | `content/npc/HostileTargetingHandler`, `META-INF/accesstransformer.cfg` |
| 2026-09-02 | **天平配置持久化覆盖**：`wandscape_balance.json` 扁平键根文件由专用 Loader 在 `/reload` 时确定性重置覆盖。 | 整合包作者需文件级一劳永逸修改数值，避免仅限运行时 API 覆盖而在重启后失效。 | `foundation/util/BalanceValues`, `WandscapeBalanceLoader` |
| 2026-09-02 | **新手引导与指南书解耦**：新手引导系统更名为 `Tutorial` 并独立成域，彻底分离指南书手册 `Guidebook`。 | 消除共用 `Guide*` 词根引发的概念模糊与 API 混杂。 | `content/tutorial/`, `content/items/` |
| 2026-09-01 | **日志治理接入 SLF4J**：16 域细粒度分类 + 毫秒节流，移除 JUL 劫持，全仓高频日志降噪。 | 杜绝每秒高频刷屏与静默异常，提供游戏内动态调级能力。 | `foundation/log/Log`, `content/command/LogCommand` |
| 2026-09-01 | **解散 WandscapeEngine 上帝类**：ECS 与底层执行器收敛至 `TaskRuntime`，领域服务按域自闭环。 | 消除单例上帝定位器，落实按域自治与极简生命周期驱动。 | `content/task/runtime/TaskRuntime`, `impl/` |
| 2026-09-01 | **API 瘦身与归口重设计**：API 仅保留极薄公开契约，内部通信废除搭桥改直接调用，未实现桩显式抛 `@Unimplemented`。 | 彻底打破过度微服务搭桥反模式，诚实暴露契约状态。 | `api/`, `WandscapeApis` |
| 2026-09-01 | **数据驱动收敛与治理**：蓝图 DSL 解释器删除并收敛为 Java-lambda（`BlueprintDefaults`），删除孤儿 `road_templates`，保留 `buildings/deprecated/`。 | 消除无维护价值的脆弱 DSL 解释器，保留旧存档建筑向下兼容载荷。 | `content/task/engine/dsl/`, `data/wandscape/buildings/deprecated/` |
| 2026-09-01 | **NPC 属性收敛唯一源**：五处重复定义收敛进 `NpcAttributes` 单类，规则表支持 API 覆盖，ARMOR 默认 5。 | 彻底根除因多处分散定义导致的漏同步崩溃，建立单事实源。 | `content/npc/attributes/NpcAttributes`, `api/NpcAttributesApi` |
| 2026-09-01 | **删除全部单元测试**：删除 `src/test` 目录，日常验证以 `./gradlew build` 为唯一准绳。 | 拒绝测试灌注与低价值桩测试维护负担，参考 Botania/Create 实践。 | `CLAUDE.md`, 构建配置 |
| 2026-08-31 | **消融 core/engine/shared 桥层**：254 个桥类按语义彻底分配到 `content/<domain>`、`foundation/` 与 `impl/`。 | 归属看语义服务谁，彻底消灭为规避反向依赖而生的搭桥层。 | `content/`, `foundation/`, `impl/` |
| 2026-08-31 | **制作站动作统一为 craft**：合成法杖/药水/杂物统一为 `production:craft` 并由 `CraftRecipeView` 解析，魔法卷轴保持独立。 | 消除添加新合成物时必须扇出修改一堆执行器与包的缺陷。 | `content/production/`, `content/task/` |
| 2026-08-30 | **数据格式与兼容纪律**：开发期不承诺无版本号兼容、删字段真删、SavedData 升级走显式版本迁移链。 | 阻断"缺 key 补默认"内联兼容代码指数级滋生。 | `CLAUDE.md`, `newplan/plan.md` |
| 2026-08-30 | **纯逻辑与 MC 严格解耦**：无 MC 依赖的算法/评分/DSL/公式等禁止 import MC 类。 | 保留纯粹清晰度与零 MC 依赖移植能力，作为架构唯一硬边界。 | `content/task/`, `content/npc/attributes/` |

---

## 二、业务与玩法系统决策

| 日期 | 决策摘要 | 一句话原因 (Why) | 关联模块 / 代码 |
|---|---|---|---|
| 2026-09-05 | **建造清盒改默认可开关**：Build 子模式右侧参数栏新增「建造清盒」（是/否），默认**是**（本次会话记忆、重启回是）。「是」= EnqueueHelper 把旋转后 boundary 盒内无 pattern 映射的格补成 `minecraft:air` 映射、前置进 offsets，`clear_and_build` 单遍放格即等价 7a254bca 之前的「先整盒清再砌墙」；「否」= 纯 pattern 放置（7a254bca 之后），可叠放/嵌套。修复/拆除只落 pattern、不吃开关。无开关路径（fill/建镇脚手架）默认按「是」。 | 7a254bca 把整盒 clear 一刀切删掉对多数玩家太偏激——平地放置期望像旧版一样把残土/杂物清掉；把「清不清盒」从全局决策降为按玩家选择的建造参数，默认回到旧体验，需要叠放的人再显式关掉。 | `EnqueueHelper.buildWorkItem`+`fillBoundaryAsAir`, `BlueprintDefaults.clearAndBuild`(数据驱动,无改动), `BuildingApi.placeBuilding(…,clearBox)`, `ProjectionPlacePacket`(+clearBox), `ProjectionClientState.isClearBoxBeforeBuild`, `BuildPopPanelOverlay`(新按钮,面板压扁), `WandscapePanelController` |
| 2026-09-04 | **游客生成双开关（全局 Config + 殖民地级市政厅）**：全局 `Config.tourist.spawnEnabled`（默认 true）关闭时**所有殖民地一律不生成游客**，无视各市政厅开关；殖民地级开关存 `ColonySavedData.touristSpawnDisabled`（缺省 = 开启），经市政厅 UI「生成游客」按钮控制。两道闸都只拦**新增**游客、不清理已在场游客，收敛在 `TouristSpawnSystem.createSchedule`（每日排期）+ 生成窗口；开关中途关闭清掉当日已排计划，避免重新开启时一次性倾泻。 | 玩家需要一个把整颗星球的游客经济停掉的全局闸门，也需要每殖民地单独关掉游客以精准控局；只在生成入口收敛、不动已到场游客，保证「关生成」语义清晰（游客该逛的逛完自然离场）。 | `Config.tourist.spawnEnabled`, `ColonySavedData`(touristSpawnDisabled), `TouristSpawnSystem`(createSchedule/onServerTick), `TownHallTouristSpawnPacket`(新), `TownHallOpenPacket`, `TownHallScreen`, `BuildingInteractHandler` |
| 2026-09-04 | **殖民地仓库容量上限**：仓库物品账本总量上限 = 殖民地「仓库」建筑数 × 50000（每座仓库 +50000；每物品占 1、不可堆叠也占 1；元素独立账本不计）。满仓拦截集中在银行净新增入口——玩家全部存入路径整批拒收（ScreenFeedback「仓库容量不足」）、合成站/制作站/魔法工坊产出物品入仓的生产任务按「缺元素」同款镜像等待（发布资格 + 队列 `capacityBlocked` 徽标 + 执行期 `checkCapacity` 守卫，满仓以伪资源 `warehouse_capacity` 抛短缺回收回队列，容量空出自动续）；豁免补货驱动的自动合成（`supply=restock`，防殖民地瘫痪）与分解/拆迁回收/任务退还等净零归还；拆迁掉落满仓改落地不丢。容量读数进仓库 UI（总览页元素下方 + 交换页标题右侧，满仓变红）。上限按 `Config.warehouse.itemCapacity`（每座默认 50000；0 = 关闭）。 | 玩家囤货与 NPC 自动产线使仓库无界膨胀、殖民地越堆越没意义；加容量制造「清仓/分解」压力，且把判定收敛在银行入账口而非逐事件枚举（平地/拆迁也大量产块入仓）；容量随仓库建筑数线性累加让"多建仓储"成为显式投资。 | `ColonyItemBank`(capacityFor/used/tryAdd), `WarehouseManager.insertItems`, `WarehouseMenu`, `WandscapeBlockInteractExecutor.checkCapacity`, `BuildingTaskSource`, `ResourceSupplySystem`, `TaskQueueDataPacket`/`TaskQueueModifyPacket`, `TaskQueuePanel`, `WarehouseScreen`, `Config.warehouse.itemCapacity` |
| 2026-09-03 | **Goety 施法 volley 化**：`IChargingSpell`（EverCharge 持续 / 蓄力连发如 Steam / 呼吸）由「固定 20–60t 引导 + 每 20t 补一发」改为 Goety 玩家侧 volley 语义——充能（`castUp/speed`）→ 按 `Cooldown(caster,staff,shots)` 节拍逐发 SpellResult → 打到 `shotsNumber()` 或单轮齐射硬顶（`MagicApi.get/setSustainedCastMaxTicks`→BalanceValues，默认 100t，不进 Config）自然收尾并上 `defaultSpellCooldown` 冷却；volley 全程不占施法互斥锁（各魔法 CD 照走），GuardCombat 每轮复选只让位严格更高优先，L0 奶等经 dispatch 打断；魔力对齐 Goety 扣费节拍（EverCharge/呼吸每 20 发扣一次、Steam 类每发扣一次），不足自动停、不罚 CD。遗留：① 非 `IChargingSpell` 但 `defaultCastDuration()>0` 的蓄力弹（PrismaBeam 类走 useSpell/stopSpell）仍被 instant 秒发待修；② 呼吸每 tick 齐射 DPS≈20× 现状，需实测调数。 | 原实现把齐射节拍 `Cooldown()` 误当冷却、60t 上限凭空捏造，导致持续聚晶（issue #22）放不出连发、蓄力型被压成单发；volley 化后与玩家侧一致，且施法可让位、不阻塞其它技能。 | `compat/goety/GoetyCaster`, `compat/goety/GoetyHelper`, `content/npc/component/MagicState`, `content/npc/guard/executor/GuardCombat`, `content/magic/internal/MagicSpellExecutors`, `api/MagicApi`, `foundation/util/BalanceValues` |
| 2026-09-03 | **建筑包围盒放开重叠 + 只动自有 pattern 方块**：注册只挡「同一世界坐标被两座建筑 pattern 共用」，包围盒可任意重叠（室内/贴墙/嵌套都解）；建造取消整盒 clear，只放 pattern 方块；拆除/修复只落 pattern 坐标、照拆玩家替换块；重叠判定收敛到共享类 `BuildingVoxels` 两阶段（AABB 粗筛→pattern 精判），服务端 register 与客户端幽灵预览同口径；完成/拆除事件改按 building_id 定位（anchor 不再唯一）；posIndex 加载即从持久化 pattern 重建、盒兜底改最内层获胜。 | 归属规则收敛为「一座建筑自注册起拥有其 pattern 所占格，盒内非 pattern 永不触碰」——无需所有权持久化，拆/修天然只清自己；旧 clear 整盒既破坏叠放内容又是巨量 tick 开销；方块级冲突本就该唯一，两阶段避免建筑多了全量比对卡顿。 | `BuildingVoxels`(新), `BuildingSavedData`, `EnqueueHelper`, `BlueprintDefaults`, `BuildingApiImpl`, `BuildCompleteListener`, `DemolishCompleteListener`, `BuildingAreaSyncPacket` |
| 2026-09-03 | **诡厄巫法 (Goety) 聚晶兼容与随从免误伤**：聚晶无限制装入 4 大策略栏，附魔保真（Base64 存 `customData`），灵魂能量转魔力/冷却可配置；随从识别为友军，法伤由 `NpcSpellPowerHandler` 单一入口乘算防成方膨胀。 | 贯彻高兼容与无感降级原则，复用策略槽管线与实体友军判定；单一入口乘算法伤彻底杜绝二次方膨胀 Bug。 | `compat/goety/`, `content/npc/NpcStrategyMenu`, `content/npc/component/EquippedMagicComponent`, `content/magic/internal/CastBrain`, `content/npc/entity/WandscapeNpc` |
| 2026-09-03 | **铁魔法卷轴门控 = Curios 法术书槽容量**：策略栏铁魔法卷轴上限 = 法术书 spell container 槽位数；无可用法术书禁入；删除「法术书铭刻即自动施放」，铁魔法只认策略栏卷轴为最终判定权。旧档 / 换小书留下的超容条目禁用不删（补书自动恢复）。提示：策略屏底部容量 / 停用状态行 + 悬停被拦红字原因。 | 铁魔法需「装备魔法书」的语义与容量制衡：法术书既是施法许可也是槽位上限，杜绝 12 格卷轴无门槛全装；删除铭刻自动施放，避免铭刻 + 卷轴双通道叠加、且让「装了哪些卷轴」成为唯一判定权。 | `content/magic/internal/CastBrain`, `content/npc/NpcStrategyMenu`, `compat/ironspellbooks/IronSpellsHelper`, `content/npc/client/NpcStrategyScreen`, `content/npc/network/NpcDataPacket` |
| 2026-09-03 | **诡厄聚晶门控 = 主手持诡厄法杖**：策略栏聚晶需 NPC 主手（法杖栏）持 `goety:wands` 法杖才可放入，且全策略栏上限 1 个；未持杖禁入，残留聚晶禁用不删（持杖恢复）。复用铁魔法的门控管线（CastBrain 决策过滤 + NpcStrategyMenu mayPlace/clicked + 策略屏底部状态与悬停红字）。 | 与铁魔法同构：第三方法术装备即「施法许可 + 容量制衡」，主手法杖既是准入也是上限；配合刚放开的主手法杖槽，让诡厄施法必须有对应法杖。 | `compat/goety/GoetyCompat`, `content/magic/internal/CastBrain`, `content/npc/NpcStrategyMenu`, `content/npc/client/NpcStrategyScreen`, `content/npc/network/NpcDataPacket` |
| 2026-09-02 | **友军名单扩玩家侧 + 双向互不侵犯**：`isFriendlyForce` 扩成员（玩家宠物/守护召唤/玩家铁魔法随从），新增 `FriendlyTargetingHandler` 监听 `Mob.setTarget` 的 `LivingChangeTargetEvent` 做双向目标过滤，仅限真实殖民地侧（`isColonyNpc()` 王国 NPC/其随从/游客）。 | 单向友军名单管不住 Vanilla/铁魔法 AI——宠物会记仇 NPC、玩家随从会索敌 NPC、殖民地亡灵随从会打玩家；在目标切换层统一拦截「互不为敌」，且 EvilMage 等 `isColonyNpc()==false` 的敌意行为刻意保留。 | `content/npc/entity/WandscapeNpc`, `content/npc/types/FriendlyForce`, `content/npc/guard/FriendlyTargetingHandler`, `api/FriendlyForceApi` |
| 2026-09-02 | **NPC 击杀归属殖民地主人**：伤害入口把目标 `lastHurtByPlayer` 记为殖民地创始人玩家（无殖民地记录时单在线玩家兜底），damage source 实体保持施法 NPC 不变。 | `killed_by_player` 掉落条件只认 `lastHurtByPlayer` 而 NPC 来源伤害从不写它——烈焰棒/凋灵骷髅头/亡灵装备掉落率与经验球全丢；挂归属而不改 source 实体，掉落得解的同时怪物仇恨与法术强度判定原样。 | `content/npc/guard/NpcSpellPowerHandler`, `api/NpcApi` |
| 2026-09-02 | **法杖属性 Tooltip 显式渲染**：默认 attribute modifiers 为空导致 Tooltip 不列属性，在 `appendHoverText` 手动渲染主手属性块。 | 玩家手持虽无法杖加成但需可查阅属性，修复自动结算废除后 Tooltip 消失。 | `content/items/wand/item/WandItem` |
| 2026-09-02 | **NPC 法杖属性桥接补全 NBT 加载/放出行**：`onAddedToLevel`、戒指放出、菜单 Shift 均显式 `syncWandAttributes`。 | 实体从 NBT 恢复（区块加载/戒指放出）不经 `setItemSlot`，否则法杖属性加成静默丢失。 | `content/npc/entity/WandscapeNpc`, `content/items/ring/internal/OathRingService` |
| 2026-09-02 | **铁魔法装备属性桥收窄为资源类**：只桥 `max_mana→MAX_MANA`、`mana_regen→MANA_REGEN`；豁免 `spell_power→SPELL_POWER`、`cooldown_reduction/cast_time_reduction→SPELL_SPEED`。 | 铁魔法库已按施法者 iron 属性表结算法伤，再把 iron 加成桥进我们 SPELL_POWER 会把铁魔法法伤算两次（伤害按强度成方增长）、冷却/吟唱缩减经 SPELL_SPEED 泄漏进我们法术；独立结算后铁魔法法术吃铁魔法自身属性、我们法术只吃自有属性，互不放大；魔力/回蓝属装备对资源池的合理投入故保留。 | `compat/ironspellbooks/IronSpellsAttributes`, `content/npc/entity/WandscapeNpc`, `compat/curios/CuriosCompatImpl` |
| 2026-09-02 | **陨石法伤倍率收归伤害入口单处结算**：删除 `castMeteor` 施法时的 SPELL_POWER×魔力强化预乘，统一由 `NpcSpellPowerHandler` 在落地结算时乘一次。 | 与光束一致（光束只在伤害入口乘），否则陨石预乘 + `hurt()` 再乘，原生法伤也按强度成方增长；倍率在伤害核算唯一入口核算，任何新法术自动生效不漏写。 | `content/magic/internal/MagicSpellExecutors` |
| 2026-08-29 | **CurseForge 审核去风险**：法师 Curios 槽位改数据包声明，彻底移除反射镜像与任务面板反射块。 | 消除自动扫描器高危反射特征，确保平稳过审与跨版本安全。 | `compat/curios/`, `data/curios/` |
| 2026-08-29 | **建筑扫描器保真分层**：生存扫描器导出为纯建筑（剥离方块 NBT 与展示框物品），创造扫描器完整保真。 | 从导出源头封死生存模式利用容器 NBT 刷物品漏洞，创造端保留全保真。 | `content/building/scanner/` |
| 2026-08-28 | **权杖范围限定本殖民地**：庇护权杖并入 `isFriendlyForce` 边界，敌对权杖单槽最高优先集火，仅持久化 UUID。 | 保持"只能指挥自己殖民地 NPC"语义，存 UUID 杜绝实体引用内存泄漏。 | `content/items/scepter/`, `content/npc/` |
| 2026-08-28 | **盟誓戒指固定槽存储**：共享空间 4 槽固定不塌缩，整份 NBT 存取，仅限本殖民地法师。 | 避免释放后槽位自动补位破坏高档戒指存取语义。 | `content/items/ring/` |
| 2026-08-28 | **关键基础设施拆除保护**：全世界仅剩最后 1 座市政厅/仓库/工作站时禁止拆除并提示。 | 拆到 0 座会导致坐标定位丢失、资源掉入死账户或生产完全停摆。 | `content/building/` |
| 2026-08-28 | **建镇移除初始塞包**：移除 `computeStarterInventory`，建镇法师背包保持裸态。 | 避免首免建筑导致建材永久滞留背包并短路仓库索取扣料。 | `content/colony/command/` |
| 2026-08-27 | **NPC 属性迁移至 Vanilla Attribute**：废除 ECS EquipmentComponent，专属属性注册为原版 Attribute。 | 消除双重属性模型与铁魔法属性双倍叠加放大 bug。 | `content/npc/`, `WandscapeAttributes` |
| 2026-08-27 | **任务发布必带 colonyId**：`TaskRequest` 必填 colonyId，无主任务只派真实殖民地，占位殖民地绝不派活。 | 任务归属是发布时显式事实，切断刷怪蛋 NPC 抢任务卡死。 | `content/task/GlobalTaskPool` |
| 2026-08-27 | **幽灵 NPC 不派活**：调度器过滤 `!isNpcAlive`，卸载 NPC 任务直接退回池中交他人续跑。 | 区块卸载后实体 removed，组件残留会导致反复派发-失败死循环。 | `content/task/SchedulerSystem` |
| 2026-08-27 | **法师管理菜单有效性解绑距离**：`stillValid` 只查法师存活，不查玩家 64 格距离。 | 法师小屋是远程管理站，玩家应能在小屋内就地管理远方游荡法师。 | `content/npc/menu/` |
| 2026-08-27 | **NPC 盔甲存原版装备槽**：移除独立 armorInventory，在 `hurtArmor` 中手动扣减耐久。 | 让原版与外部模组识别 NPC 装备，手动补齐非玩家生物耐久扣减。 | `content/npc/entity/WandscapeNpc` |
| 2026-08-26 | **游客模拟等比例排队降权**：排队降权改为等比例（1~3 人乘 0.75/0.5/0.25，封顶 −75%），废除固定 3000 减分。 | 避免高价值热门建筑因满员被减到地板分导致游客宁闲逛不排队。 | `content/tourist/internal/TouristSimulation` |
| 2026-08-26 | **游客满三条才给经验**：游客停留期间三条需求全满才结算经验。 | 经验是阶段里程碑而不是流水，防止无脑挂机刷经验。 | `content/tourist/` |
| 2026-08-26 | **游客 visited 停留期不重置**：整个停留期同一建筑只逛一次，仅 ATM 缺钱与 relax 缺精力豁免。 | 避免游客在单建筑无限循环消费，引导多样化城镇建设。 | `content/tourist/` |
| 2026-08-12 | **施法决策三层架构**：L0 硬性保命/紧急治疗 > L1 玩家策略预设 > L2 普攻兜底。 | 确保濒死保命逻辑永远优先，不受玩家策略排序干扰。 | `content/magic/internal/CastBrain` |
| 2026-08-11 | **施法互斥锁与 CD 分离**：互斥锁占用期间 CD 冻结，锁释放后倒计时。 | CD 代表施法结束后的恢复间隔，避免长法阵锁覆盖 CD 造成连发。 | `content/npc/component/MagicState` |
| 2026-08-07 | **祭坛独占重大魔法与复活**：`altar_only` 魔法禁止 NPC 自由施法，每祭坛 CD 独立存储。 | 重大仪式集中在神圣设施并以任务形式驱动，防止玩家随时随地滥用。 | `content/magic/`, `content/building/` |
