# Wandscape 开发者文档（docs）

> 信息截至 2026-09-02 | Minecraft NeoForge 1.21.1 | refactor 分支

- **【何时读】**：首次接手模组开发、查阅系统设计决策、修改数据格式或排查反直觉问题时。
- **【不包含什么】**：面向玩家的指南书/手册（由游戏内 Tutorial/Guidebook 承载）、长篇叙述性架构设计（代码即事实源）、无坑模块的常规 CRUD 代码说明。

---

## 一、文档导航

| 文档 | 核心内容 | 何时查阅 |
|---|---|---|
| [legacy-audit.md](legacy-audit.md) | **旧文档过时审计与真实事实对照表**（废弃/重构/保留明细） | 查阅旧设计或怀疑某项机制是否过时时 |
| [adr.md](adr.md) | 架构决策记录表（ADR）：日期 + 决策 + 一句话原因 | 准备重构设计或探究代码为何如此编写时 |
| [domain-notes.md](domain-notes.md) | 核心功能域避坑手册（NPC/游客/魔法/任务/建筑/仓库等） | 开发或修改对应功能域代码前 |
| [data-formats.md](data-formats.md) | 数据格式与迁移纪律（JSON/NBT/SavedData 规范） | 新增或修改数据文件、调整持久化格式时 |
| [checklists.md](checklists.md) | 迁移与重构活清单（NeoForge 适配、重构阶梯、PR 守则） | 版本迁移、大重构、发版或提交 PR 时 |
| [fabric-port-survey.md](fabric-port-survey.md) | **Fabric 1.21.1 官方移植考察报告**：规模/耦合量、六条平台接缝、事件映射表、难度分级、仓库结构选择面 | 评估双 loader 官方版可行性或规划长期仓库演进时 |
| [neoforge-26-upgrade-survey.md](neoforge-26-upgrade-survey.md) | **NeoForge 26.1 升级考察报告**：版本线/工具链变化、全库契约改名与深水重写分区、分项难度表、与 fabric 移植横评 | 评估升级到 Minecraft 26.1 / NeoForge 26.1 的体量与难易，规划升级顺序时 |
| [guidebook.md](guidebook.md) | **手册全流程（内容 · 文风 · 管线 · 美术）**：md 单源 → 帕秋莉 JSON / 无帕秋莉时的兜底屏两处渲染、「改什么 = 改哪里」唯一源表、玩家文案文风（只写「做了什么」不写能力清单，附我们踩过的真实反例）、md→帕秋莉映射表与样式栈两个坑、生成与分页自检、书皮 512×256 的 23 类槽位全表与美术产出清单 | **动手写或改任何玩家看得到的文案之前**——手册条目、物品/魔法介绍、JEI 信息页、界面标签；改 `guidebook/*.md`、重生成手册、替换手册美术素材、或接续解锁与入口收口时 |
| [lang-pipeline.md](lang-pipeline.md) | **语言文件生成管线（lang_src 单源 → lang/zh_cn.json + en_us.json）**：为什么这么拆、源文件分法、编译期校验（中英齐全 / 占位符对齐 / 产物漂移）、以及「为什么不按命名空间拆 / 不自写加载器」的决策留档 | 加或改任何上屏文案、新增界面、或想动 `lang/` 下那两个 JSON 时 |

---

## 二、架构核心速查（1 分钟认知）

Wandscape 采用 **6 顶层包 + 13 核心功能域** 架构形态，废除过度专业软件化分层与搭桥：

```
com.wsteam.wandscape/
├── api/          公开契约（面向 addon/整合包）：极薄接口 + 公开事件 + WandscapeApis
├── content/      13 个核心业务功能域：
│   ├── colony      殖民地等级/经验/激活/统计/袭击
│   ├── building    建筑核心/升级/拆除/蓝图/投影/扫描器
│   ├── command     /wandscape 根命令族（审计/日志/建镇/测试等运维命令）
│   ├── npc         NPC（WandscapeNpc）实体/AI/招募/属性（NpcAttributes）
│   ├── tourist     短居游客/游客经济/偏好模拟/旅店结算/商店消费
│   ├── production  生产站/合成配方/生产队列
│   ├── road        道路生成/路网连接/Spline 路径
│   ├── magic       法术系统/祭坛施法/法术执行/施法决策（CastBrain）
│   ├── task        任务池（GlobalTaskPool）/调度（SchedulerSystem）/原子操作
│   ├── warehouse   仓库网络/物品流转/存储索引
│   ├── element     元素网络/元素节点/元素映射与转化
│   ├── items       功能性物品（法杖/权杖/戒指/终端/指南书）
│   └── tutorial    新手引导系统内核（进度/事件/引导渲染）
├── foundation/   跨域共享基建：UI框架/网络包基类/日志（Log）/工具/SavedData
├── compat/       第三方模组集成（JEI, Curios, Iron's Spells 等，compileOnly）
├── impl/         @ApiStatus.Internal 装配与生命周期门禁
└── mixin/        少量必要 mixin（相机/存档/袭击）
```

### 核心铁律
1. **直接调用，废除搭桥**：功能域之间协作直接调用对应业务类，禁止建立内部中转 bridge 或滥用全员 EventBus 解耦。
2. **基建收 foundation，特性留域**：通用 Screen 框架、网络包泛型基类收 foundation；专属渲染器、专属 Menu、专属网络包留域内。
3. **纯逻辑与 MC 解耦**：纯算法、蓝图解析、任务评分等禁止 import MC 类，保证可移植性。
4. **活清单与事实源**：重构已完结（2026-09）；日常以代码 + 本 docs 为准。大版本迁移/发版等活清单见 [checklists.md](checklists.md)。
