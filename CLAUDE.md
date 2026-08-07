你是一名资深的 MC 模组开发者，正在开发 Minecraft NeoForge 1.21.1 模组 **Wandscape**。

**两大系统**：(1) **殖民地自动化** — NPC 法师通过法杖执行原子操作，建造/采集/合成；(2) **模拟经营（游客经济）** — `docs/jingying.md`、`docs/simulation.md`。

## 构建

```bash
./gradlew build                    # 编译
./gradlew test                     # 运行单元测试
./gradlew runGameTestServer        # 运行 GameTest
./gradlew neoForgeIdeSync          # 首次运行前/runClient 报错`clientRunVmArgs.txt` 不存在时
```

## 核心原则

1. **高兼容性**：不修改原版行为，JSON 数据驱动，方块映射用标签。
2. **原子化设计**：模块间通过 `WandscapeApis` + EventBus 通信，不跨包直接引用类。
3. **稳定性优先**：所有失败路径必须有兜底。不允许静默失败或崩溃。
4. **文档即代码**：改结构→更新 `architecture/packages/`，改 JSON→更新 `architecture/data/`，改设计→更新 `docs/`。
5. **引擎是请求层，适配层是实现**：`core/` 禁止 import MC 类，MC 实现放 `engine/` 或各模块 `internal/`。
6. **日志用** `shared/log/Log.java`。

## 架构

包地图 + 数据流 → **`architecture/README.md`**（开始任何工作前先读）。设计意图 → `architecture/packages/*.md`。JSON 格式 → `architecture/data/`。

```
shared/   ← 所有包可见（API + 事件 + 数据类型）
engine/   ← MC 适配，实现 core 边界接口
building/wand/element/npc/warehouse/production/tourist/
projection/road/stats/task   ← 通过 WandscapeApis + EventBus 通信
core/     ← 纯 Java 21，零 MC 依赖。不依赖 shared/
```

`equipment` 是 cross-cutting 关注点（`core/types/` + `core/component/`，桥接在 `npc/internal/`）。

## 代码发现

1. 结构查询（类/调用链/位置）优先用 **codebase-memory-mcp**：`search_graph` → `trace_path` → `get_code_snippet`
2. MC/NeoForge API 必须用 `minecraft-source` skill 查源码，不靠记忆
3. 大改后重新索引：`index_repository '{"repo_path":"."}'`

## 提交规则

- **AI 的每次实质性更改都必须 commit**（改代码、增删文件、改文档），commit 后才继续下一步
- **只 commit AI 本次做的更改**，不要混入他人的未提交工作
- **大重构必须每个步骤完成后立即 commit**，禁止攒多个步骤再统一提交
  - 例：移动文件 → commit，改引用 → commit，更新文档 → commit
- **Commit message 格式**：中文一句（改动什么 + 为什么）。
  - `fix:` 修复 bug，`refactor:` 重构，`feat:` 新功能，`doc:` 文档，`chore:` 杂项
- **未版本管理的文件必须处理**：新文件要么 `git add` 纳入版本，要么加 `.gitignore` 排除。不允许有未处理的 untracked files。`.gitignore` 改完后立即 commit。

## 版本管理

- **自动更新版本号**：任务全部做完、最后一次提交时，同步递增 `gradle.properties` 的 `mod_version` 并一起 commit；若只完成一步/两步、任务还要继续，则只 commit 不递增版本号，等整个任务完成时再统一递增。bug修复，小功能改进改第三位，功能重构改第二位，第三位归零，大的新功能上线/破坏性大重构改第一位，第二，三位归零。纯文档（`docs/`、`architecture/`、`CLAUDE.md`）不递增。
- **清理 build/libs/ 旧版本**：仅当第二位（次版本号）变化时清理旧 jar。例如 1.2.x → 1.3.0 时删除所有 1.2.x 的 `wandscape-*.jar`；仅第三位（补丁号）变化（如 1.2.0 → 1.2.1）**不删除**旧 jar，保留补丁迭代便于回退。

### 发布 release 流程

里程碑发布（进入新阶段/次版本号变化/大版本重置）按以下顺序操作：

1. 更新 `gradle.properties` 的 `mod_version`；若后缀方案变化（如 a→b），同步改本文件版本规则
2. 按上方规则清理 `build/libs/` 旧 jar
3. **release commit**：`chore: mod_version <旧> → <新> — 发布 <新>（关键词/）`，如 `chore: mod_version 1.10.39a → 1.0.0b — 发布 1.0.0b（进入 Beta：多语言适配/新手引导/供应链闭环/游客经济）`，与版本号/规则改动一起提交
4. 打 tag：`git tag v<版本>`（如 `v1.0.0b`）
5. push：`git push origin main && git push origin v<版本>`
6. 构建 jar：`./gradlew build` 产出 `build/libs/wandscape-<版本>.jar`（**发布必须带 jar 资产**，漏了要补 `gh release upload`）
7. 创建 release：`gh release create v<版本> --title "Wandscape <版本>" --notes "<正文>"`
8. 上传 jar：`gh release upload v<版本> build/libs/wandscape-<版本>.jar --clobber`

### release 正文排版

- 标题：`# Wandscape <版本号>`
- 引言段：自上次 release 版本发布以来，模组经历哪些版本迭代，本次一并发布：<本次主要板块>；里程碑发布在引言点明（如「正式进入 Beta 阶段」）
- 分区：emoji + 分区标题（🌍 多语言 / 🎓 新手引导 / 🔗 供应链 / 👛 经济 / 🐛 修复 等），每区 3-6 条要点
- 条目：一句一个要点，保留关键细节（数字/版本号/具体机制），只列用户可感知的重要更改，琐碎内部改动不写
- 汇总区间：`git log --oneline <上次release tag>..HEAD`，结合各 commit 描述按主题归类

## 工作流

- **澄清后再写**：需求模糊时先 `grill-me` skill 追问，不直接动代码
- 写代码前读 architecture/README.md → search_graph 查关键类 → roadmap.md 确认阶段
- 写代码时：新接口→`shared/api/`，新事件→`shared/event/`，注册→更新 package 文件
- 写完后：改设计→`docs/decisions.md`，发现问题→`docs/gaps.md`

## 子代理

并行委派互不依赖的子任务。prompt 里写明边界和返回格式，避免空转。

## Testing

- 纯逻辑代码（不依赖 MC 运行时）必须有 JUnit 5 单元测试，`./gradlew test` 必须全绿
- `ItemStack`/`BlockState`/`Level`/渲染/GUI 留待集成测试

## 常见陷阱

1. **跨模块 new 类** → `WandscapeApis.getXxxApi()`
2. **硬编码数值** → `WandscapeConstants` 或 TOML
3. **NBT 传出不 copy** → `return tag.copy()`
4. **事件依赖执行顺序** → 事件仅通知，需顺序用 API
5. **BE 直接调 engine** → BuildingTaskSource 是唯一入口
6. **另起炉灶任务分发** → 走 `TaskRequest → GlobalTaskPool → SchedulerSystem`
7. **静默 catch 不记日志** → 至少 `Log.warn()`
8. **游客 ≠ 常驻市民** → 游客是短居访客，无职业/床位/住宅/状态机。`TouristState` 是移动状态标记，不是状态机——禁止扩展。
