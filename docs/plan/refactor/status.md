# 重构进度跟踪

> 此文件是重构进度的唯一事实源。每个阶段做完、每发现一个新问题，都更新这里。
> 起点：`docs/plan/refactor/why`（2026-08-29 写出的十条痼疾）
> 约定重写：`CLAUDE.md`（重构状态、代码组织约定、Testing、重构进行中）

上次更新：2026-08-29

## 阶段 0 — 确认问题（已完成）

写清了十条痼疾（见 `why`），并核实补充：

- 死代码确认：`core/types/NpcAttributes` 全仓库 0 引用。
- 属性重复定义确认：`NpcAttributes`、`MageHutAttributes`、`MageAttributeRoller`、`WandscapeNpc` 字段、`TouristEntity` 字段，至少五处。
- 测试灌注确认：760 个类挂 779 个 `@Test`，从未拦截过几次回归。
- 文档死引用确认：CLAUDE.md 原引用 `docs/roadmap.md`、`docs/jingying.md`，均已不存在。
- 文档多源漂移确认：`architecture/README.md` 自称历史快照；`docs/architecture.md` + `docs/modules/` + `architecture/packages/` 三套镜像共存且互相漂移。

## 阶段 1 — 约定去专业软件化（进行中）

目标：CLAUDE.md 与工作约定改成"业余模组开发者"的常识，删掉无意义的专业软件仪式。

- [x] 重写 CLAUDE.md
  - [x] 废除"互不直接引用 / getXxxApi() 搭桥 / 事件通信"约定，改为按功能包直接调用
  - [x] 保留唯一硬边界：纯逻辑不 import MC 类（保单测能力）
  - [x] 文档即代码 → 文档讲人话：只写接手人需要的，不为写而写
  - [x] Testing 从"补强断言、结构不变式"降为"守门员不是简历"：只为纯逻辑有分支/解析/计算处写几个代表用例，禁止堆量
  - [x] 工作流简化：不做先后读 architecture/packages → roadmap → per-包 的仪式链
  - [ ] ~~提交流程简化~~（CLAUDE.md 已给多 AI 并行约定；若实际操作仍烧钱，再进一步砍）
- [x] 新建本文件（进度跟踪）

## 阶段 2 — 结构重构（未开始，方案待规划）

目标：提取真正有用的模块，拆掉无意义分层。规划要点（待细化，用 grill-me 敲定先后）：

- 属性统一：消灭五处属性定义，收敛到一处（纯逻辑 + 数据驱动）。
- 删除死代码：`NpcAttributes` 等 0 引用类。
- 命名统一：Mage/NPC 二选一（倾向 `Npc`，类/文件/包全迁移）。
- 包重组：按功能域重排，拆除 core/engine/shared 三层的搭桥类。
- UI 去堆：抽公共 Screen 框架，建筑 UI 用数据驱动而不是每建筑一个类。
- lang 重构：去掉 switch-case 地狱，按功能分文件。
- Log 治理：删无用 log，聊天区/上屏只留错误与完成反馈。

## 其他（日志）

- 2026-08-29：用户写出 `why`；本次会话重写 CLAUDE.md + 建本文件（阶段 0/1）。