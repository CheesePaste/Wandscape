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
| [multiplayer-parallel-isolation.md](plan/multiplayer-parallel-isolation.md) | **「完全平行」殖民地隔离的方案与可持续推进路线图**：已完成/待实测/后续可选（own-context 绑定 + 咽喉归属判定 + 领地方块防破坏；防恶意客户端与统一入口的时机） | 接续多人生存隔离实现、实测验收、或规划后续多人权限系统前 |
| [guidebook-patchouli-transform.md](plan/guidebook-patchouli-transform.md) | **手册改造与引导合并方案评估**：教程 HUD 并入 Patchouli 跟玩手册（advancement 解锁/quest 打勾）+ Markdown 只读兜底，奖励去掉；含逐项修改难度表与分阶段路线 | 规划"跟着书玩"改造、评估工作量、或动手前读决策点 D1–D5 |
| [guidebook-patchouli.md](guidebook-patchouli.md) | **手册落实现状（md 单源 → 帕秋莉 JSON 生成管线）**：生成命令、目录结构、md→帕秋莉映射表、样式栈两个坑、书皮/配图/模型素材坐标与重画规格、后续未做项 | 改 `guidebook/*.md` 内容、重生成手册、替换手册美术素材、或接续解锁与入口收口时 |
| [patchouli-art-asset-spec.md](plan/patchouli-art-asset-spec.md) | **帕秋莉手册美术资产规格书**：书皮 512×256 图集 23 类槽位全表、配方图集 6 槽位、插图 256×256/内容限左上 200×200、字号行高与各页型文字区、`_refs` 六本手册的装饰手法与用量实测 | 设计/重画手册美术、调整排版字号、或用模板做自定义版面时 |

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
