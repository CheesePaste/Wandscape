# 1.9 — 代码清理与包结构修整 计划

> 状态：待实施（2026-08-16）
> 目标版本：并入 1.9（与 remove-imgui / jei 并列的第三条方向）
> 规模：535 main + 69 test Java 文件；27 顶层包；92 子包

## 背景与目标

当前 `com.wsteam.wandscape` 下包数量多且个别杂乱：单文件小包、根级散落类、死代码/死字段量大，`architecture/`（2.3M 历史快照）长期过期靠 `docs/gaps.md` 打补丁。目标：

1. **清死代码/死字段**：用代码图谱 + IDE 双重验证，只删可证未引用的。
2. **修整包结构**：保持域模块结构（与 CLAUDE.md 依赖规则一致），删死包、归位乱放类、统一子包命名规范。
3. **文档单源化**：`architecture/` 有价值内容并入 `docs/`，`architecture/` 只留 `plan/` + 参考纹理，`gaps.md` 差异清单清零。

## 已确认决策（2026-08-16，用户拍板）

| 决策 | 结论 |
|---|---|
| 时序/分支 | **独立分支 `feat/1.9-cleanup` 先行**；另两条 1.9 分支（remove-imgui/jei）目前纯计划无代码，完成时 rebase 到新结构 |
| 目标结构 | **修整现状而非推倒**：删死/归位/统一子包，不搞 api/impl 大分层 |
| 死代码力度 | **图谱可证 + IDE 验证才删**；保留 public API 面（WandscapeApis、模块接口、事件监听、Mixin 目标、数据驱动注册） |
| 文档 | **并入 docs/ 单源**，删 `architecture/` 过时快照 |

## 现状诊断（已核实）

### 结构并非"乱到要推倒"
- 顶层 27 个域包**基本对应 CLAUDE.md 文档化模块**（building/road/tourist/npc/…），子包也有规律（client/network/internal/data）。
- 真正的问题：**死代码多** + **个别包/类归位不当** + **子包规范不统一** + **architecture/ 过时**。

### 小包 / 疑似死包清单（逐个核实，不盲目删）
| 包 | 文件数 | 判断 | 处理 |
|---|---|---|---|
| `gametest/` | 1 | `ElementAuditRunner` **活着**（@EventBusSubscriber + `wandscape.runAudit` 系统属性，跑元素覆盖审计） | **搬迁**到恰当包（如 `element/internal/` 或 `tools/`），不删 |
| `client/` | 1（`renderer/` 子包） | 根级孤立 | 归位到所属域（查 `TransportItemEntityRenderer` 归属） |
| `guidebook/` | 2 | `GuideBookItem` + `GuideBookOpenPacket` | 并入 `shared/` 或独立 `books/`（与 `shared/ui/guide` 职责合并评估） |
| `dataconfig/` | 2 | `WandscapeDataLoader` 框架 | 并入 `shared/registry/`（同属数据注册框架） |
| `imgui/` | 2 | 将被 `1.9-remove-imgui` 删除 | **本分支不动**，避免双改；仅文档标注 |
| `raid/` | 3 | 独立机制（袭击触发/胜利跟踪） | 保留（机制独立） |
| `wand/`+`element/` | 3+6 | 元素经济两大件 | 保留；评估是否合一 |
| `command/` | 17 | `/wandscape` 调试命令 | 保留（调试入口）；评估并入 `debug/` |
| `mixin/` | 4 | Mixin | 保留（机制特殊） |
| `overview/` `stats/` `warehouse/` | 5/5/6 | 独立视角/统计/仓库 | 保留 |

### 死代码候选（图谱查询示例，**待重新索引 + IDE 验证**）
- 旧图谱查出的候选：`shared/data/InterruptRecord`、`road/core/RouteSegment`、`WandscapeBlockInteractExecutor.Pending`、`core/types/EquipmentPreset`（存疑）。
- **旧图谱不可靠**：含已删除的旧 imgui 类（"Blueprint Canvas"/"Inspector"），USAGE 边漏同类/静态访问（`Config` 被误报）。→ **必须先重新索引**。

## 实施步骤（分阶段提交，每步编译绿）

### 阶段 0：基线 + 工具准备
1. 重新索引代码图谱（`index_repository`，大改后重索引，见 CLAUDE.local.md）。
2. `./gradlew build` + `./gradlew test` 确认绿基线。
3. 以 `docs/gaps.md` 已知死数据（wonder_config/potion_station/HouseApi 等）为起点清单。

### 阶段 1：死代码/死字段清理（先删，为重构腾地）
**方法论（关键）**：
- **图谱出候选**：只查高置信子集——私有成员（外部不可引用）+ 无 IMPORT/USAGE 的类；public 面全部跳过（事件/反射/接口分发无法被 CALLS 边覆盖）。
- **IDE 逐条确认**：对每个候选跑 Find Usages（含反射/事件/数据注册场景）。
- **编译兜底**：删除顺序按依赖叶→根，每批删完即 `./gradlew build`。
- **保留面清单（永不删）**：`WandscapeApis`/`WandscapeDataRegistry`、模块接口（shared/api）、`@SubscribeEvent` 方法、`@EventBusSubscriber` 类、`@JeiPlugin`（将来）、Mixin 目标、`DeferredRegister` 注册项、数据 JSON 引用到的类（dataconfig loader 的 fromJson 目标）、`Config.*`（TOML 绑定）。
- 每删一批一个提交（`refactor: 删死代码 …`）。

**第一批候选（已高置信/需核实）**：
- 高置信死：`shared/data/InterruptRecord`、`road/core/RouteSegment`、`WandscapeBlockInteractExecutor.Pending`（IDE 验证后）。
- 待核实后搬（非删）：`ElementAuditRunner` → 从 `gametest/` 迁出。
- 旧 `docs/gaps.md` 已标死数据（wonder_config 等）随文档重写一并清理或标注。

### 阶段 2：包结构修整（保持域结构）
1. **归位**：根级 `client/renderer` 迁入所属域；`gametest/` 改名/并入；`guidebook/` 并入 `shared/`；`dataconfig/` 并入 `shared/registry/`。
2. **统一子包规范**：每个域统一 `client/`（客户端界面/渲染）、`network/`（网络包）、`internal/`（实现）、`data/`（数据类）；缺的补、散的收。
3. **顶层包收敛到 ~20 个**（去掉归并后的小包），与 CLAUDE.md 依赖规则逐一对齐。
4. 每步一次提交（`refactor: 归位 …` / `refactor: 包重命名 …`），**移动类必须改包名 + 更新引用**，IDE Rename 辅助。
5. `docs/gaps.md` 中"architecture 与代码差异"条目随重构同步消失。

### 阶段 3：文档并入 docs/ 单源
1. `architecture/packages/*` 有价值内容 → 并入 `docs/modules/*`（重写为现状）。
2. `architecture/data/*` → 并入 `docs/data/*`。
3. `architecture/README.md` + `conventions.md` → 并入 `docs/`（或转为 docs/README + 合并进 docs/architecture.md）。
4. `docs/architecture.md` 按重构后结构重写（包地图/数据流/依赖规则以真实代码为准）。
5. `architecture/` 删除，仅保留 `architecture/plan/`（计划，含本文件）与 `magic/usefulmagic-examples/`（参考纹理，或移入 docs/ 参考目录 / gitignore）。
6. `docs/gaps.md` 重写：删掉"architecture 差异"类目，只留真问题。
7. 更新 `CLAUDE.md` 项目导航表（`architecture/` → `docs/` 指向）。
8. 提交：`doc: 文档并入 docs/ 单源，删除 architecture/ 过时快照`

### 阶段 4：收尾
1. `./gradlew build` + `./gradlew test` 全绿。
2. 版本：并入 1.9，发布时 `mod_version` → 1.9.0（与 remove-imgui/jei 合并时统一 bump）。
3. 清理 `tmp/JEI/`（临时调研产物，不入库）等未管理文件，确保 `git status` 干净。

## 测试计划
- 每阶段 `./gradlew build` + `./gradlew test` 绿。
- 涉及客户端类移动：用户手动冒烟（runClient 禁用，见 CLAUDE.md）。
- 元素审计工具搬迁后：`./gradlew runGameTestServer -Dwandscape.runAudit=true` 验证仍可跑（若保留）。

## 风险与注意
- **死代码误删**：靠保留面清单 + IDE 逐条验证 + 编译兜底。事件监听/反射/数据驱动是误删高发区。
- **图谱旧索引**：必须先重新索引再审计，否则候选含已删类。
- **与另两条分支 rebase**：清理先行，remove-imgui/jei 完成时 rebase 到新结构（其代码量小，冲突可控）。
- **`imgui/` 本分支不动**：避免与 remove-imgui 双改冲突。
- **2.1M 参考纹理**（`architecture/magic/usefulmagic-examples/`）：属参考材料，删除前确认无引用。

## 待核实清单（写代码前必须确认）
- [ ] `InterruptRecord` / `RouteSegment` / `Pending` 是否真死（IDE 验证）。
- [ ] `EquipmentPreset` 是否死（图谱存疑）。
- [ ] `guidebook/` 并入 `shared/` 还是新建 `books/`；与 `shared/ui/guide`（GuideTestScreen）职责边界。
- [ ] `command/` 是否并入 `debug/` 或保留。
- [ ] `wand/`+`element/` 是否合一。
- [ ] `ElementAuditRunner` 搬迁去向 + 其 runGameTestServer 工作流是否保留。
- [ ] `architecture/magic/usefulmagic-examples` 纹理处置（保留/移动/gitignore）。
