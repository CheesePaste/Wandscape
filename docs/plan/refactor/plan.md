# Wandscape 重构方案（业余版·修订）

> 目的：给重构定调——定义侦察、修改、复核的方法与**终点目标**，不追求重构后多完美，只求可控、快速、成果可预期，能维持开发。
> 问题清单：`why`；进度：`status.md`。
> 修订（2026-08-30）：补入核心侦察结论、目标包形态（30→5 顶层）、API 收敛、文档裁决、决策记录；结构解剖参考自 `_refs/`（MineColonies/Create/Botania 浅克隆，不入库）。本次修订经用户拍板（见【四、决策记录】）。

## 一、定调

1. **北极星是维持开发速度，不是架构优美。** 加一个"拆除按钮"从碰 20 个文件降到 3 个，就是胜利。
2. **净减量法则。** 重构步骤必须让代码变少（行数/文件数）**或让下次改动明显更省力**。只搬不动、换了名字还是同样的缠绕，叫"横移"，不算重构。
3. **风险分级，小步快跑。** 顺序永远是：删（零风险）→ 改名（编译兜底）→ 合并（需人工判断）→ 重组（高风险）。确定性高的先做，把信心和显性成果先落袋。
4. **一个 commit = 一个可编译可测的原子步，每步可回滚。**
5. **做到病痛消失就停，不追完美。** 正式阶段做完后，把"顺手清理"养成日常习惯。
6. **禁止顺手重构。** 一个改动只做一件事：不改格式、不改旁边类、不改注册 id。
7. **重构必须能被打断。** 阶段之间随时能回去做真实需求，代码仍然健康。

## 二、侦察

### 原则（沿用）
- 只侦察下两步需要的范围，不搞全项目体检。产出是候选改动清单表，不是文档。
- **数字摸底，让数字自己说话**：包、文件、行、JSON 计数。
- **引用计数定生死**（codebase-memory 图谱查 in-degree）：0 引用→直接删；1 引用→内联进调用方；同包 2~N 引用→合并收敛对象；跨包高引用→只改名字不动结构。
- **依赖图找搭桥点**：shared/event、shared/api、engine/boundary 的真实使用方，纯转发类→Tier 3，环→Tier 4。
- **痛点反向侦察**：每加一个功能/修一个 bug 记一笔：绕了几个包、碰几个文件。
- **适配 MC 特有坑**：类名被 lang/*.json、NBT 键、注册 id、JSON 数据引用时 grep 字符串一起改，避免运行期崩/存档炸。

### 核心侦察结论（已完成，数字作为计划基线）
1. **摸底**：648 文件 / 101,531 行；顶层 30 个包；core/engine/shared 三桥 254 文件 / ~28k 行（占 27.5%），21 个功能域占 72.5%；<40 行碎片 170 个；15 份 SavedData 样板；30 份网络包样板；NPC 属性五处定义。
2. **行数构成**（抽样 ~1 万行逐行分类，行加权）：真逻辑 55–62%；样板（NBT 存档/packet/注册）18–23%；注释 12–14%；防御性 6–10%。**关键**：防御全删也到不了目标，主要回吐靠样板收敛 + 重复删除；注释是资产不能动。
3. **重复清单**（NPC 属性之外另有四重项）：
   - 配方 record 集群：`parseElementMap` 五法逐字节相同，6 个配方 record 平行同构 → 收敛成 1 个 `CraftRecipe`。
   - 纯 Java 点/向量自造族：`SplineVec3` 是 vanilla `Vec3` 全文复刻，加之 `GridPos`/`PathPoint`/`XZPoint`/`BlockOffset` 各写一份 int 三元组 → 改用 vanilla 类型（与"纯逻辑不 import MC"红线的裁决口见 Tier 3）。
   - SavedData 包装样板 15 份（`FACTORY` + `get()` 同构，注释自认仿写）→ 抽 `SavedDataUtil`/抽象基类。
   - 网络包静态 handler 样板 ~30 份 → 抽泛型 `AbstractPayload`。
   - 中轻：config record 群（Relax/Shop/Service/Wonder 同构）、3 组同名常量分两文件、`shared/data/InterruptRecord` 死代码、TouristEntity↔Shadow 镜像。
4. **参考解剖判据**（MineColonies 2094 文件 / Create 2016 / Botania 1083，三审观点一致）：
   - 顶层包收敛到 3–9（我们 30）；**域可多、顶层必须少**。
   - API 是**平级顶层包**（api/apiimp 或 api/impl），配装配/门禁层，不在深目录。
   - 数据真相放代码/生成物（datagen 或 JSON datalistener），不维护手写镜像。
   - UI 用公共框架/模块组合（MineColonies 4 窗、Botania 2 GUI），不堆每建筑 Screen。
   - 复杂域按纵向切片：契约 → 装配 → 共享基类 → 薄业务类。

## 三、目标形态

### 顶层包地图（30 → 5）
```
com.wsteam.wandscape/
├── api/        公开契约（addon/整合包）：现有 shared/api + WandscapeApis 收敛搬迁，瘦身后只留 5-7 接口 + 公开事件
├── content/    全部功能域，域内按功能块切
│   ├── building/  colony/  npc/  tourist/  road/  wand/  task/
│   ├── production/  warehouse/  guard/  raid/  projection/
│   └── (compass/stats/overview 等小型域并入相关域或做 content 下小目录)
├── foundation/ 跨域基建：ui（全部 Screen 集中）、networking（基类 + 全局包）、registry、saveddata 工具、log、effect
├── compat/     jei/curios/ironspellbooks 第三方集成（保留）
└── impl/       @ApiStatus.Internal 装配门禁（薄，可选）
```
规则：
- **域内按功能块/机器切，不设 client/network/data 子包**；域内 Screen/packet 不收，全局收。
- **网络包全局一层**（foundation/networking），**UI 全局一层**（foundation/ui/screens/<域>/）。
- 跨域直接调用普通类 = 正常（沿用现有约定），防火墙只在 api 面。

### API 面
- 位置：平级顶层 `api/`；`impl/package-info.java` 一行 `@ApiStatus.Internal` 门禁。
- **判据一条**：这个方法，addon 作者没有会不会写不出来？不会用到的全内联回功能域（`BuildingApi` 的 demolish/cancel/place/task-bridge 方法全内联，查询面保留）。
- 稳定机制：接口新增方法带 default（二进制兼容）；淘汰标注 `@Deprecated(since, forRemoval)` 注明版本，绝不静默删。
- 公开事件：Colony 生命周期 / Raid / Tourist 到达离开 / Element 变化（真事件流保留，假事件内联）。
- `WandscapeApis` 静态注册表搬迁进 `api/` 并瘦身（去掉 14 套 get/set 样板，只留真公开面）。

### 文档裁决
- **删 `architecture/` 整树**（自认过时的历史快照）。
- **三镜像合一**：`docs/architecture.md` + `docs/modules/` → 一份 `docs/packages.md`（包地图 + 职责 + 坑），文件头写死规则"改包即改它"。
- `docs/decisions.md` 只留真决策；`docs/plan/` 下已完成的一次性设计标记状态；`docs/gaps.md`、`docs/bugs/` 保留（排查清单是真有用）。
- **新文档不产独立任务**：改包/改功能顺手回填 packages.md 对应小节，与代码同 commit（纯文档任务才用 `doc:`）。
- 数据真相放代码/生成物：配方等先试点 datagen（见决策记录 5）。

## 四、决策记录（本次已拍板）

| # | 决策 | 选择 | 理由/备注 |
|---|------|------|----------|
| 1 | 顶层形态 | **Create 风味**：`api/content/foundation/compat/impl` 五顶层 | 域心智模型保留 + 网络/UI 全局 + 顶层降到 5。MineColonies 三包也能活，但复用 `core` 名与"拆 core"目标冲突 |
| 2 | 域内切法 | **按功能块切 + 网络/UI 收全局** | 三家解剖一致否定现有"域内分 client/network/data 子包"约定；**同步改 CLAUDE.md 对应条款** |
| 3 | UI 目标 | **公共 Screen 框架 + 建筑窗口数据驱动/模块组装，硬目标** | MineColonies（模块窗 4 个）/Botania（2 GUI）双证 |
| 4 | 持久化 | **维持 SavedData，抽工具收样板，不迁格式** | 换 Capability/data attachment 动存档格式，撞高兼容红线；存格不动，只减样板 |
| 5 | （待议）datagen 推广 | 先配方试点，样板可行再铺 | 最大工程决策之一，本阶段只试点，不铺全 |

## 五、修改（Tier 阶梯）

**Tier 0 基线**：build + test 全绿、git 工作区干净。

**Tier 1 删除（最高价值，最低风险，先做）**：0 引用类、`deprecated/` 等废 JSON、无用 log（配合 Log 治理）、陪葬测试。验收：build 绿。

**Tier 2 改名（编译器当安全网）**：Mage→Npc 全仓统一，连注册 id/lang key/NBT 字符串一起 grep。验收：build 绿 + grep 旧名零命中。

**Tier 3 合并（用侦察重复清单做靶子）：**
- 属性五处→一处（纯逻辑 + 数据驱动 + 几个单测，样例等值验证）。
- 配方集群 6→1、点/向量自造族→vanilla 类型、SavedData 样板→`SavedDataUtil`、packet 样板→`AbstractPayload`。
- **裁决口**：点/向量改用 vanilla 是否违反"纯逻辑不 import MC"硬边界——按 road 路由算法是否仍需 JUnit 单测逐个裁决，不以偏概全。
- 跨包高引用只改名字不动结构。
验收：样例等值 + build + 相关测试绿。

**Tier 4 重组（最高风险，最后做；拆桥层到五顶层形态）：**
- 四个结构动作：① Api 门脸内联（内部 use >80% 的直接删接口、消费方直连实现类）；② ops 双层合一（engine/boundary + core/boundary 同类概念合并成一份）；③ 自制 ECS 拆除（core/ecs + core/component，15+ 文件）；④ 假事件内联（刷新 UI/通知型回调改直接调用，真事件流保留）。
- **铁的纪律：移动不改逻辑、改逻辑另开一步。** 每步一个 commit，独立分支/worktree 做，做完合回。
验收：目标包地图落地 + 移动前后纯逻辑测试全绿（行为不变即成功）。

**横切四件套**（各当独立 mini 阶段，结构稳定之后最顺）：
- **UI 去堆**（硬目标）：从现有建筑 Screen 抽公共框架 + 模块组装，1 个样板域验证可行性再铺全。
- **lang 分文件**：先切 1 个功能域做样板；顺手拆 switch-case。
- **Log 治理**：危险/无用 log 删除随 Tier 1；输出点审计（上屏/聊天只留错误+完成）放最后。
- **文档收敛**：删 architecture/、三镜像合一、立 packages.md。

## 六、复核

1. **编译是头号复核刑具。** build 绿 = 基本盘。
2. **测试是守门员不是简历。** 纯逻辑相关测试保持绿；测死代码的测试跟删。重构不改变行为，不因重构加新测试。
3. **三查清单**（每次 commit 前）：`grep 旧类名` 零命中；`grep 字符串 id` 与代码一致；改动前后行数对比应净减，净增说明在横移停下问值不值。
4. **运行复核短清单**：行为改动最多 1~2 条手测路径，能跑 GameTest 的用 GameTest，禁止 runClient。
5. **提交复核**：`git status` 只含本步文件；提交后立即更新 status.md。**同一改动返工两次停手重新侦察。**
6. **不变式红线**：高兼容不硬编码（方块/物品走标签 JSON）；纯逻辑不 import MC（Tier 3 有裁决口，裁决后白纸黑字记进 packages.md）。

## 阶段序列

| 阶段 | 内容 | 对应 | 完成判定 |
|------|------|------|----------|
| 0/1 | 问题确认 / 约定去专业化 | — | ✅ / 进行中（本次修订含：顶层形态五包、域内规则改、UI/持久化/API/文档决策） |
| 2a | 死代码清理 | Tier 1 | build 绿；候选表核算删减，**基线 648→540~580 文件、10.1万→8万± 行** |
| 2b | Mage→Npc 统一 | Tier 2 | grep 旧名零命中 |
| 2c | 重复收敛（属性+配方+向量+双样板） | Tier 3 | 各集群收敛到一处 + 样例等值 |
| 2d | 拆桥层，五顶层形态落地 | Tier 4 | 目标包地图落地，行为不变 |
| 2e | API 收敛：api/ 顶层 + WandscapeApis 搬迁瘦身 | Tier 4 | api 包启用，内部 0 泄漏（impl 门禁） |
| 3–6 | UI(硬目标) / lang / Log / 文档收敛 | 横切 | 各有样板判据 |

**核心一句话**：先把确定性高的减法做掉，再用编译器兜底改名，合并用侦察清单做靶子，最后重组到五顶层形态——每一步有验收、能停；成果（文件/行数/包结构）由候选表核算得出，不靠拍脑袋。