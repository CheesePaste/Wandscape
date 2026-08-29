# 重构进度跟踪

> 此文件是重构进度的唯一事实源。每个阶段做完、每发现一个新问题，都更新这里。
> 起点：`docs/plan/refactor/why`（2026-08-29 写出的十条痼疾）
> 约定重写：`CLAUDE.md`（重构状态、代码组织约定、Testing、重构进行中）

上次更新：2026-08-30

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

## 阶段 2 — 结构重构（未开始，方案已定）

方案：`plan.md`（业余版重构方案）。目标：提取真正有用的模块，拆掉无意义分层。按风险分档排队：

- **Tier 1 删除**（2a）：0 引用类 + 废 JSON + 无用 log + 陪葬测试。完成判定：行数砍到 ~8 万以下，build 绿。
- **Tier 2 改名**（2b）：Mage→Npc 全仓统一（连注册 id/lang key/NBT 字符串一起 grep）。完成判定：grep 旧名零命中。
- **Tier 3 合并**（2c）：消灭五处属性定义，收敛到一处（纯逻辑 + 数据驱动），样例等值验证。
- **Tier 4 重组**（2d）：拆除 core/engine/shared 三层的搭桥类，按功能域重排。移动不改逻辑。
- **横切三件套**（3/4/5 阶段）：UI 去堆（抽公共 Screen 样板先行）、lang 分文件（样板先行）、Log 治理（删除随 2a 顺手做，输出点审计放最后）。

## 其他（日志）

- 2026-08-29：用户写出 `why`；本次会话重写 CLAUDE.md + 建本文件（阶段 0/1）。
- 2026-08-29：摸底现状（648 文件 / 10.1 万行，shared/* 桥包 100+ 文件，element_mappings 1187 JSON，lang 每语言 2031 行）；给出业余版重构方案并落库 `plan.md`（定调/侦察/修改/复核 + 阶段序列），阶段 2 改为"方案已定"。
- 2026-08-30：**plan.md 修订**：补核心侦察结论（行数构成/四方重复清单/参考解剖判据）+ 目标形态 30→5 顶层包 + API 收敛 + 文档裁决 + 四项用户拍板的决策（顶层形态/域内切法/UI 硬目标/持久化维持）。CLAUDE.md 域内切法条款同步改为"按功能块切 + 网络/UI 收全局"。三参考模组（MineColonies/Create/Botania）浅克隆于 `_refs/`（已 gitignore）供解剖对照。
- 2026-08-30：CLAUDE.md「代码发现」新增第 5 条：动结构/API/UI/数据类改动前先查 `_refs/` 对应参考（含速查表），禁整段照搬。