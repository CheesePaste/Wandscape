# 架构决策记录表（ADR）

> 信息截至 2026-09-02 | Minecraft NeoForge 1.21.1

- **【何时读】**：准备修改核心架构设计、评估重大方案或探究某项反直觉代码的设计原因时。
- **【不包含什么】**：长篇复盘散文、已被代码类型/命名直接表达的平凡实现细节、已废弃且无参考价值的陈旧试验。

---

## 一、架构与重构决策

| 日期 | 决策摘要 | 一句话原因 (Why) | 关联模块 / 代码 |
|---|---|---|---|
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
