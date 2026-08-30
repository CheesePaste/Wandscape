# tier0 — 可信全项目文档摸底（临时认知地图）

> 先于一切结构重构。旧 `docs/` 下的 `architecture.md` / `modules/` / `data/` **全是过期图**（`architecture.md` 连 `scepter/` 都漏），照它们做任何重构构想都是白搭。
> 目标：在这个 `newplan/` 根目录（与旧 `docs/` **完全隔离**）下，用真实代码逐包核实，产出一份**临时**可《项目认知地图》（`newplan/packages.md`），作为重构决策的地基。
> ⚠️ **这份 docs 注定会删**：它只为这次重构服务，重构决定做出来、代码搬完，`newplan/` 使命结束即整体删除。所以**写得够用就行，绝不追求专业文档的复杂与正式**——那只会复制旧 `docs/` 的病（文档比代码还贵）。宁缺勿繁、点到即止，够支撑"这包该合并/该拆"的决策即可。

## 一、为什么必须先做（证据，不是推测）

| 证据 | 说明 |
|------|------|
| `docs/architecture.md` 包地图漏 `scepter/` | 代码 `src/main/java/.../scepter/` 有 9 个文件，文档包图里**根本没有**。写的人没更新，读的人按旧图决策。 |
| `docs/README.md` 缺好多包 | 用户点名的过期。 |
| 三套镜像各自缺包、互相漂移 | `architecture.md` + `modules/`(20 文件) + `data/` 是同一批内容的三份拷贝；`modules/` 有 `scepter.md`，`architecture.md` 没有——连该有的包都对不齐。 |
| 文档与代码 "描述 vs 实现" 漂移 | 如 `ColonyLevelManager.expToNext` 的 Javadoc 写 `55×(level+1)²`、代码是 `25×m²`（史上调过参，注释改了、Javadoc 忘了）。文档甚至能在"同一份报告"里描述两套不同调参。 |

**结论**：现有文档没有一份可信。重构的正确顺序是**先用真实代码重建认知**，再动手改结构；否则任何"这个包该合并/该拆"的判断都建立在会漏包的旧地图上。

## 二、目标产物：`newplan/packages.md`（临时地图，不入旧 docs/）

- 一张**临时认知地图**：每个功能域一小节，说清"这个域干什么、改它先看谁、依赖谁、有什么坑、该归哪"。**记要点不记详情**——一句话能说清的就别写成一篇。
- 写在这本 `newplan/` 下，**与 `docs/` 完全隔离**；旧 `docs/architecture.md` / `modules/` 不更新、不迁移，tier0 完成后按需删除。
- **不追求正式/完整**：能支撑重构判断即止。填不出来就标"未探明"，别为凑字数编。

## 三、每节模板（简短，拼起来一致即可）

```
# <功能域/包名>
- **职责**：一句话 + 几句话展开（它处理什么玩法）。
- **改它先看**：1~2 个核心类（唯一入口），给类名。
- **数据流**：谁喂它、它产出给谁（进出）。
- **依赖**：import 了哪些其它域/技术层。
- **坑/旧文档矛盾**：旧 docs 错在哪（只记这，旧文档不作真相）。
- **归属**：独立成 content 域 / 并入某域 / 降为子包 / 进 foundation 或 items。
```

**规则**：一个概念（如"元素值"）全地图只定义一次，其它节交叉引用；各节由不同 AI 写也靠这条拼成整体。**简写优先**，能半行说清绝不写一段。

## 四、分批探索任务清单

按功能域聚成 **8 批**，每批一个独立 AI（子代理）扫真实代码、产出该批节稿，**只读不提交**（守多 AI 并行约定：同分支同时刻只一个 AI 写提交；探索只读可并行）。批间并行，同批只一个 AI。**判据：只读代码，旧 docs 仅用来对照、标错，不作真相。**

**先建空 `newplan/packages.md` 骨架**（列全节名），各批填自己的节，天然互不冲突。最后汇总轮校一遍概念一致性。

每批给「该批包 + 待验证初始假设（来自已完成的侦察）+ 探索重点」。初始假设是**锚点不是结论**，探索批用真实代码核验/推翻。

| 批 | 包 | 待验证初始假设 | 探索重点 |
|----|----|--------------|---------|
| 1 建筑域 | `building` `projection` `overview` `raid` `stats` | projection/overview/raid/stats 都是 building 周边，**并入 building**；stats 是 colony 报表层 | 建筑内核（状态+存档+配置加载+贡献/装饰/商店/市政厅）+ 扫描器；4 个小域真实耦合度 |
| 2 法师/市民域 | `npc` `guard` `ring` `scepter` | scepter/ring **只并物品类进 items、系统留 npc**；guard 并入 npc（或独立 defense）；WandscapeNpc 是核心实体 | scepter 的 ScepterMarks/SavedData/Service/Interact/Death 是真系统还是物品；ring 的 OathRingStorage；guard 战斗内核 |
| 3 游客/经济域 | `tourist` `element` `production` | element（方块→7元素映射）该归 magic 还是独立经济域；production 配方式生产；tourist 是短居访客+离线影子仿真 | 元素是贯穿配方/建造/合成/经济的主线，判断它的归属对经济影响最大 |
| 4 道路/玩家工具域 | `road` `wand` `compass` `guidebook` | wand/compass/guidebook 无系统内核，**整包并入 items**；road 独立域 | road 的 RoadNetwork/Spline 核心图结构 + 放置/编辑器；wand 是不是纯数据驱动属性物品（WandPresetLoader） |
| 5 任务/施法域 | `task` `op` `magic` | op（AtomicOp 执行框架）**并入 task**；magic 独立并接收 element | task 的蓝图 DSL/任务池/调度器；op 是否纯执行原语被 TaskExecutionSystem 用；magic 的 CastBrain/光束调度 |
| 6 仓储/公共数据域 | `warehouse` `shared` | warehouse 独立（ColonyItemBank/仓库菜单/终端）；shared 该拆哪些进 foundation | Warehouse 双标签 GUI/运输；shared 的 api/event/network/ui 哪些是真公共、哪些是搭桥 |
| 7 核心两大层 | `core` `engine` | core=纯 Java 零 MC（ECS+类型+边界接口）；engine=MC 适配层唯一实现 core 边界；两者是被拆的桥层 | 边界接口真实使用方（判断哪些内联）；ECS/组件真实性；纯逻辑红线覆盖范围 |
| 8 技术层/资源杂项 | `client` `compat` `command` `dataconfig` `mixin` `gametest` `guidebook` + `resources/(data/lang/assets)` | 全归 foundation/compat；清理判断 | 各技术包职责；compat 第三方集成；resources 里 JSON/lang/指南实际数量（摸底 JSON 泛滥） |

## 五、复核（宽松，够用就行）

1. **只读代码**：每个断言能指到一个类/方法；旧 docs 只在「坑」条出现。
2. **概念一致**：同一概念全地图一个定义（跨节交叉引用）。
3. **归属一句话**：每节给"独立/并入/降级/进层"，即使维持现状也要写一句。

## 六、完成判定（临时，不追完美）

- `packages.md` 各节**够支撑 content/ 分包 + 合并判断**即可；空点标"未探明"，**不许为填满而编**。
- 各节归属汇总成一张**结论表** = content/ 分包依据。
- 与真实顶层包 29 个对齐（含 `scepter/`），杜绝"漏包"重演。
- 旧 `docs/architecture.md` + `modules/` 在结论表敲定后删除。
- 这份临时地图的价值做完即失，重构落定后随 `newplan/` 一并删。
