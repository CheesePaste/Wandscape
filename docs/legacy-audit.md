# 旧文档（docs_archive/）过时审计与真实事实对照（legacy-audit）

> 信息截至 2026-09-02 | Minecraft NeoForge 1.21.1 | refactor 分支

- **【何时读】**：查阅已归档的 `docs_archive/` 旧文件、发现旧设计文档与当前代码冲突、或需要确认某项机制是否已废弃时。
- **【不包含什么】**：已被彻底清除且对开发无任何参考意义的历史散文。

---

## 一、完全废弃与已删除内容（100% Dead / Deleted）

以下旧 `docs_archive/` 中记录的内容已被**彻底物理删除**，严禁在开发中重新引入：

| 旧文档说法 | 真实代码事实 | 废弃/删除原因 |
|---|---|---|
| **蓝图 JSON DSL** (`docs_archive/data/blueprints.md`) | `data/wandscape/blueprints/` 及其 DSL 解释器（`BlueprintConfigLoader`, `BlueprintInterpreter`, `StepNode`, `ExprNode` 等）**已彻底删除**；全部蓝图收敛为 `content/task/engine/dsl/BlueprintDefaults.java` 中的 **Java-lambda** 注册。 | 消除过度设计的脆弱 DSL 解释器栈，改用类型安全的 Java 原生函数。 |
| **道路自动生成 (MST)** (`docs_archive/data/road.md`, `gaps.md`) | `road_templates/`、`road_tiers.json`、`road_rules/` 及 MST 自动规划器（`RoadPlanner` 等）**已彻底删除**；仅保留玩家手动样条铺路。 | 自动生成破坏玩家建筑规划且与复杂地形严重冲突。 |
| **药水配方** (`docs_archive/data/craft_recipes.md`) | `mana_potion` 与 `stamina_potion` 两个配方 JSON **已彻底删除**；输出物品从未注册，无实际用途。 | 清除未接入实体的死数据。 |
| **单元测试体系** (`docs_archive/decisions.md` 等) | `src/test/` 目录（111 个单测文件）**已全量物理删除**；日常开发与重构以 `./gradlew build` 为唯一验证门槛。 | 消除低价值桩测试与测试灌注维护负担。 |
| **core/engine/shared 三桥层** (`docs_archive/README.md`) | `core/`, `engine/`, `shared/` 三个顶层桥包（254 类）**已彻底消融清空**，按语义直接迁入 `content/`, `foundation/`, `api/`, `impl/`。 | 彻底打破"为了不直接引用而层层搭桥"的架构反模式。 |
| **WandscapeEngine 上帝类** (`docs_archive/README.md`, `decisions.md`) | `WandscapeEngine.java` **已物理删除**；ECS 与底层执行器收敛至 `content/task/runtime/TaskRuntime.java`，领域服务按域自闭环。 | 解散单例上帝定位器，落实按域自治。 |
| **死代码服务与接口** (`docs_archive/gaps.md`) | `StatsService`（空壳 TODO）、`HouseApi`（无实现）、`EquipmentSlot`（零引用）**已物理删除**。 | 清理历史残留空壳。 |

---

## 二、已重构升级与收敛内容（Refactored & Superseded）

以下机制在旧 `docs_archive/` 中记录为旧形态，现已完成重构收敛，必须以右侧事实为准：

| 模块 / 机制 | 旧文档记录（已过时） | 当前真实代码事实（唯一权威） |
|---|---|---|
| **NPC 属性定义** | 分散在 `core/types/NpcAttributes`, `MageHutAttributes`, `MageAttributeRoller`, `AttributeType` 等 5 处。 | **全套规则唯一定义在 `content/npc/attributes/NpcAttributes.java`**（7 可见 + 2 隐藏，ARMOR 默认 5，perLevel 加成为 0），支持 `NpcAttributesApi` 运行时覆盖。 |
| **制作站合成动作** | 按 craft_wand / brew_potion / misc 分散在不同包与 switch 分发。 | **统一为 `production:craft` 动作**，由 `CraftRecipeView` 统一解析；魔法工坊卷轴保持 `craft_spell` 独立。 |
| **游客排队评分** | Spot 满员时扣除固定 3000 分（`QUEUE_PENALTY=3000`）。 | **等比例排队降权**：1~3 人分别乘以 0.75 / 0.5 / 0.25（封顶 −75%），避免热门建筑被压到地板分。 |
| **NPC 施法装备** | 默认法术书 8 个魔法（`SpellbookComponent` 开局全会）。 | **4 分类装备桶制**（`EquippedMagicComponent`，每桶 ≤ 3，默认仅 beam + heal），玩家在策略界面拖放卷轴装备。 |
| **新手引导与手册** | 共用 `Guide*` 词根（`GuideProgressService`, `GuideScreen` 等）。 | **新手引导更名为 `Tutorial` 并独立成域**（`content/tutorial`），指南书手册保持 `Guidebook`（`content/items`），两系统彻底解耦。 |
| **天平平衡参数** | 仅能在运行时通过 Java API 覆盖，重启即失效。 | **支持 `data/wandscape/wandscape_balance.json` 文件持久化**，服务器 `/reload` 时确定性重置并重新注入覆盖。 |
| **Curios 饰品兼容** | 通过反射改写 Curios 内部类 `CuriosEntityManager`。 | **改用官方数据包声明**（`data/curios/curios/entities/wandscape_npc.json`），彻底移除运行时反射。 |
| **建筑扫描器导出** | 统一导出方块实体 NBT（存在容器刷物品漏洞）。 | **保真分层导出**：生存扫描器 `isSafeExport=true`（剥离方块 NBT 与展示框物品）；创造扫描器完整保真导出。 |
| **NPC 盔甲存储** | 存在独立 `armorInventory` 容器。 | **直接存入原版装备槽**以兼容原版属性与附魔，在 `hurtArmor` 中手动扣减耐久。 |

---

## 三、依然有效并保留的核心事实（Still Valid & Preserved Truth）

以下内容经核验与真实源码完全一致，作为核心机制在 `docs/` 中继续保留：

1. **游客偏好与模拟管线** (`docs/domain-notes.md`)：
   - 视野 48 格候选收集 → `visited` 过滤（ATM 缺钱 / relax 缺精力豁免）→ 三条增益评分 → 加权抽取。
   - 画像分布（40% 均衡 / 20% 舒适 / 20% 魔法 / 20% 奇观）。
   - `interact_spots` FIFO 排队与朝向。
   - 交互结算（`fillBars`）无惩罚，满三条才给经验。
2. **施法决策三层架构** (`docs/domain-notes.md`)：
   - L0 紧急保命/治疗 > L1 玩家策略预设与优先级 > L2 普攻兜底（5 物理伤害，受 SPELL_POWER 放大）。
   - 施法互斥锁占用期间 CD 冻结（CD 表示施法结束后的恢复间隔）。
   - 祭坛独占重大法术与复活（`altar_only`），复活生成虚弱状态（1 血 0 蓝），每祭坛 CD 独立。
3. **权杖与戒指机制** (`docs/domain-notes.md`)：
   - 权杖限定本殖民地，庇护扩友军边界，敌对单槽最高优先集火，仅持久化 UUID。
   - 盟誓戒指固定 4 槽不塌缩，整份 NBT 存取。
4. **建筑与数据格式** (`docs/data-formats.md`)：
   - 建筑 JSON `pattern / palette / block_indices` 结构。
   - 关键建筑拆除保护（全世界最后 1 座市政厅/仓库/工作站禁止拆除）。
   - `src/main/resources/data/wandscape/buildings/deprecated/` 兼容载荷必须保留。
   - 元素 370+ 种子权威库、7 大元素映射与价值流向。
