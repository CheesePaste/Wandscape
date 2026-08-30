# tier0 — 可信全项目文档摸底

> 先于一切结构重构。旧的 `architecture.md` / `modules/` / `data/` 全是过期图，照它们做任何重构构想都是白搭。
> 目标：用真实代码逐包核实，产出一份**单一可信**的《项目全文档》（最终收敛成 `packages.md`），作为后续所有重构的判断地基。
> ⚠️ 与 plan.md 的「Tier 0 = 基线（build+test 绿）」不是一回事：这里是**文档真相基线**。为避免混淆，本阶段文中一律叫 **tier0**（小写）或「摸底」。

## 一、为什么必须先做（证据，不是推测）

| 证据 | 说明 |
|------|------|
| `docs/architecture.md` 包地图漏 `scepter/` | 代码 `src/main/java/.../scepter/` 有 9 个文件，文档包图里**根本没有**。写的人没更新，读的人按旧图决策。 |
| `docs/README.md` 缺好多包 | 用户点名的过期。 |
| 三套镜像各自缺包、互相漂移 | `architecture.md` + `modules/`(20 文件) + `data/` 是同一批内容的三份拷贝；`modules/` 有 `scepter.md`，`architecture.md` 没有——连该有的包都对不齐。 |
| 文档与代码 "描述 vs 实现" 漂移 | 如 `ColonyLevelManager.expToNext` 的 Javadoc 写 `55×(level+1)²`、代码是 `25×m²`（史上调过参，注释改了、Javadoc 忘了）。文档甚至能在"同一份报告"里描述两套不同调参。 |

**结论**：现有文档没有一份可信。重构的正确顺序是**先用真实代码重建认知**，再动手改结构；否则任何"这个包该合并/该拆"的判断都建立在会漏包的旧地图上。

## 二、目标产物：`docs/packages.md`（单一定源）

一份**手写、随代码改**的文档。每个功能域一节，统一模板。改包/改功能时同步该节（与代码同 commit）——这就是 plan.md「文档收敛」要的三镜像合一，只是**先重建再收敛**，不能直接在过期三镜像上合并（合并出来的还是错的）。

落成后**删除**：`architecture.md`、`modules/`（整目录）、`data/` 描述性文档（`data/` 里是数据格式说明，可留作配方/JSON 参考，但功能概述一律收进 packages.md）。

## 三、每节模板（批次产出，保证拼起来一致）

```
# <功能域/包名>
- 一句话职责
- 核心类 / AI 一眼该找的"唯一入口"（这个域想改东西先看哪个类）
- 核心数据流（进/出：谁喂它、它产出给谁）
- 依赖（import 了哪些功能域 / 技术层）
- 坑 + 与旧文档矛盾处（旧文档错在哪，写下来防再被骗）
- 归属判断（独立成 content 域 / 并入某域 / 降为子包 / 进 foundation 或 items）
```

**节间一致性规则**：一个概念（如"元素元素值"）在全文档只能有一个定义，各节交叉引用，不许各写一遍——这同时是把 tier3 重复收敛的靶子提前暴露出来。

## 四、分批探索任务清单

按功能域聚成 **8 批**，每批一个独立 AI（子代理）扫真实代码、产出该批节稿，**只读不提交**（遵守多 AI 并行约定：同分支同一时刻只一个 AI 写提交；探索只读可并行）。批间并行，同批只一个 AI。**判据：只读代码，旧文档仅用来对照、标错，不作真相。**

**先建空 `packages.md` 骨架**（列全所有节名），各批填充自己的节，天然隔离不冲突。最后设一轮汇总校验节间一致性。

每批给「该批包 + 待验证的初始假设（来自已完成的侦察）+ 探索重点」。初始假设是**锚点不是结论**，探索批用真实代码核验/推翻。

| 批 | 包 | 待验证初始假设 | 探索重点 |
|----|----|--------------|---------|
| 1 建筑域 | `building` `projection` `overview` `raid` `stats` | projection/overview/raid/stats 都是 building 周边，**并入 building**；stats 是 colony 报表层 | 建筑内核（状态+存档+配置加载+贡献/装饰/商店/市政厅）+ 扫描器；4 个小域各自的真实耦合度 |
| 2 法师/市民域 | `npc` `guard` `ring` `scepter` | scepter/ring **只并物品类进 items、系统留 npc**；guard 并入 npc（或独立 defense）；WandscapeNpc 是核心实体 | scepter 的 ScepterMarks/SavedData/Service/Interact/Death 是真系统还是物品；ring 的 OathRingStorage；guard 战斗内核 |
| 3 游客/经济域 | `tourist` `element` `production` | element（方块→7元素映射）该归 magic 还是独立经济域；production 配方式生产；tourist 是短居访客+离线影子仿真 | 元素是贯穿配方/建造/合成/经济的主线，判断它的归属对整个经济影响最大 |
| 4 道路/玩家工具域 | `road` `wand` `compass` `guidebook` | wand/compass/guidebook 无系统内核，**整包并入 items**；road 独立域 | road 的 RoadNetwork/Spline 核心图结构 + 放置/编辑器；wand 是不是纯数据驱动属性物品（WandPresetLoader） |
| 5 任务/施法域 | `task` `op` `magic` | op（AtomicOp 执行框架）**并入 task**；magic 独立并接收 element | task 的蓝图 DSL/任务池/调度器；op 是否纯执行原语被 TaskExecutionSystem 用；magic 的 CastBrain/光束调度 |
| 6 仓储/公共数据域 | `warehouse` `shared` | warehouse 独立（ColonyItemBank/仓库菜单/终端）；shared 该拆哪些进 foundation | Warehouse 双标签 GUI/运输；shared 的 api/event/network/ui 哪些是真公共、哪些是搭桥 |
| 7 核心两大层 | `core` `engine` | core=纯 Java 零 MC（ECS+类型+边界接口）；engine=MC 适配层唯一实现 core 边界；两者是被拆的桥层 | 边界接口的真实使用方（判断哪些该内联）；ECS/组件真实性；纯逻辑红线覆盖范围 |
| 8 技术层/资源杂项 | `client` `compat` `command` `dataconfig` `mixin` `gametest` `guidebook` + `resources/(data/lang/assets)` | 全归 foundation/compat；清理判断 | 各技术包的职责；compat 第三方集成；resources 里 JSON/lang/指南的实际数量（摸底 JSON 泛滥） |

## 五、每批复核

1. **覆盖**：该批包的顶层类全覆盖，无遗漏（对照批的包清单逐类过）。
2. **只读代码**：节稿每个断言能指到一个类/方法；旧文档只在「坑 + 矛盾」条出现，不充当真相。
3. **节间一致**：同一概念全文档一个定义（跨节交叉引用）。
4. **归属透明**：每节给出"独立/并入/降级/进层"的明确判断 + 理由（即使结论是"维持现状"也必须写一句，不许沉默）。

## 六、完成判定

- `packages.md` 每节覆盖全包、无「待确认」留白（要空就标"未探明"并说明）。
- 旧三镜像（architecture.md / modules/ 目录 / 数据功能概述）删除。
- 每节结尾的归属判断汇总成一张**结论表**，用作 content/ 分包与其他变更的依据。
- 全文档与真实顶层包 29 个对齐（含 `scepter/`），杜绝"漏包"重演。
