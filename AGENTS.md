# Wandscape 开发指南

> Minecraft NeoForge 1.21.1 模组《魔法小镇》：殖民地自动化（NPC 法师经法杖执行建造/采集/合成）+ 模拟经营（短居游客沿道路入城，交互商店/服务建筑，元素利润循环）。
> **本文件是项目准则的唯一真源**，Claude Code / Codex / Cursor / DeepSeek Harness 都读它。`CLAUDE.md` 只是一行 `@AGENTS.md` 指针，**别在那里写第二套规则**——文档多源漂移正是重构清过的痼疾。
> 深层知识不写在这里：导航见 `docs/README.md`；动手改某个功能域前，先读 `docs/domain-notes.md` 对应小节 + `docs/adr.md` 相关决策。

## SOUL

不要对用户言听计从——像资深开发者一样分析后，用最佳实践实现，而不是盲从指令。

## 构建与验证

```bash
./gradlew build            # 唯一的验证门槛，改完必跑
./gradlew neoForgeIdeSync  # 仅当报 clientRunVmArgs.txt 缺失时跑一次
```

- 不维护单测（`src/test` 已删）：不写测试、不跑 `./gradlew test`。
- **禁 `./gradlew runClient`**；也已无 GameTest / datagen run 配置。
- 写 MC / NeoForge 代码前先用 `minecraft-source` 技能读真实源码，**禁止凭记忆写 API**：
  `bash .agents/skills/minecraft-source/scripts/decompile.sh <类名>`
- 需求或设计没敲定，先用 `grill-me` 技能（`.agents/skills/grill-me/`）追问到决策树每支明确再动手。

## 架构归属（硬原则）

- **归属看语义，不看依赖方向**：问「这个类是什么、服务于谁」，**绝不问「谁依赖它」**。跨域直接调用是正常默认，不是把它挪出归属域的理由。
- **增量只落本域**：新增/修改只写该域自己的包；通用基建进 `foundation/`。禁止另起炉灶或重建桥层。
- **一个概念一套规则**：全套规则收敛进该域唯一命名类（如 NPC 属性 → `NpcAttributes`），别处只引用、不清写。
- **纯逻辑不 import MC**：算法 / 蓝图解析 / 任务评分 / 路由保持零 MC 依赖——靠自造 `SplineVec3`/`PathPoint`/`XZPoint`/`GridPos`/`BlockOffset` 这族非 vanilla 类型维持，别为省事换成 MC 的。
- **API / 事件只给真实需求**：只给 addon 与整合包要用的公开契约、真事件流。纯内部的「想调你一个方法」直接调用，不许包装。
- **不动原版行为、不硬编码方块/物品引用**：功能走 JSON 数据驱动，方块映射走标签。
- **优先复用原版与现有机制**，优先自省 / 接口 / 数据驱动的通用做法，而非硬编码枚举清单。
- 改动要让代码变少、结构变清，或让下次改动明显更省力；只搬不动、改名不解决问题的叫横移，不算价值。

## 非显然陷阱

- `content/magic/internal/CastBrain` **不是死码**。判死码用「编译 + grep 双关」，**不信代码图谱 in-degree**——实测 in-degree 为 0 的候选全是活类。
- `data/wandscape/buildings/deprecated/` 是**旧档兼容载荷，不可删**（旧档建筑按内层 `id` 解析）。这个目录被误删过多次。
- **游客 ≠ 常驻市民**：`TouristState` 只是移动状态标记，真状态机是 `TouristMoveGoal.MoveMode`；禁向游客添加任何常住概念。
- **任务派发的唯一通道**：`TaskRequest → GlobalTaskPool → SchedulerSystem`，别另起炉灶。
- NBT 传出用 `return tag.copy()`；事件只作通知，需要顺序就直接调用。
- `Log.debug` 在未开 debug 时会被打成带 `[DEBUG]` 前缀的 INFO——「降级为 debug」不等于可控开关。
- 所有可能失败的路径都要有兜底，出错至少 `Log.warn()`（`foundation/log/Log`）；禁静默失败，禁崩溃。
- 查调用链 / 影响面先走 codebase-memory 知识图谱（`search_graph` → `trace_path` → `get_code_snippet`），再退回 grep。
- 重命名走 IDE 的 rename（自动同步全仓引用），别做全局字符串替换。

## 文案 · 资源 · 数据

- **上屏文案只改 `lang_src/`**；`lang/*.json` 是 `gen_lang.py` 的生成物，手改会被整体覆盖。改完跑 `python gen_lang.py`，提交前 `python gen_lang.py --check`。
- 占位符在 Java 兜底串里**一律写 `%s`**；写 `%d` 不会被归一，玩家会直接看到字面量。
- **上屏只留错误与完成反馈**，其余走 Log。
- **禁 emoji 与装饰图标**：玩家可见文本（`lang/*`、`guide/**`、I18n、Screen 内联）与源码注释都不要，只留 →←↑↓ × ⌊⌋。
- 物品贴图必须 **16×16 且 alpha 全 255**（不留半透明/透明像素）。
- `Config` 的 `.comment()` **每条中英双语**（中文原文 + 紧跟英文翻译）；新增键同样，这些注释会进玩家读到的 TOML。
- **改数据格式要么带版本号迁移、要么断档**：不留「缺 key 补默认」分支、不留兼容别名；SavedData 顶层存 `version` 走显式迁移链。断档必须在 release 正文写明（见 `docs/checklists.md`）。
- 帕秋莉手册页数**不必凑偶**；唯一排版硬指标是**一页不超容量**。
- 内容管线生成器脚本放**仓库根**并入库（`tools/` 已被 gitignore）。

## 工作流与提交

- 修 bug：复现 → 修根因 → `./gradlew build` 验证。**调试以用户实测为准**，优先核对代码事实，而不是扩展旧理论。
- 一个逻辑任务提交一次；中途被迫打断就 flush 提交一次。
- 提交按逻辑任务聚合（代码 + 文档同一条），前缀 `fix:`/`feat:`/`refactor:`/`chore:`，纯文档才 `doc:`；中文一句写「改了什么 + 为什么」。
- 同一分支同一时刻只允许一个 AI 写；**绝不 reset / stash / rebase 他人提交**，对方提交落中间就 cherry-pick 到最新。
- 多人并行改同一批文件时，**按「我改过吗」划线**提交并显式列路径，不要 `git add -A`。
- 新文件要么 `git add` 要么进 `.gitignore`，不残留 untracked。版本号不主动递增。

## 子代理

- 并发 ≤4；要超过就先说清「为什么开、总共几个、各干什么」再问。
- 单文件查找、一行修改自己做；多文件扫荡、跨模块搜索、独立研究才委派，并给足上下文。
- 重构 / 移动期间禁止就同一批文件并行写。
