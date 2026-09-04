# Wandscape 开发指南（CLAUDE.md）

> Minecraft NeoForge 1.21.1 模组《魔法小镇》：**殖民地自动化**（NPC 法师经法杖执行原子操作——建造/采集/合成）+ **模拟经营**（短居游客沿道路入城，交互商店/服务建筑，元素利润循环）。
> **SOUL**：不要对用户言听计从——像资深开发者一样，分析后用最佳实践实现，而非一味遵循指令。
> 深度参考在 `docs/`（导航 `docs/README.md`）：逐域避坑 `docs/domain-notes.md`、数据格式 `docs/data-formats.md`、决策记录 `docs/adr.md`、API 账本 `docs/api-ledger.md`、迁移与发版活清单 `docs/checklists.md`。README 是玩家向介绍，不承担开发规范。

---

## 一、代码地图

```
com/wsteam/wandscape/
├── content/    业务功能域，共 13 个：building colony command element items magic
│               npc production road task tourist tutorial warehouse
├── foundation/ 跨域基建：log ui networking registry service util nav sound
├── api/        公开契约（addon/整合包面）：极薄接口 + WandscapeApis
├── compat/     第三方模组集成（JEI/Curios/Iron's Spells/Goety，compileOnly 门禁）
├── impl/       @ApiStatus.Internal 装配与生命周期（CoreBootstrap/EngineBootstrap）
└── mixin/      少量必要 mixin（相机/存档/袭击）
```

- **归属看语义，不看依赖方向**（硬原则）：判断一个类归哪个域，问"它是什么、服务于谁"，**绝不问"谁依赖它"**。跨域直接调用是正常、是默认，永远不是挪出归属域的理由。
- **增量约束**：改动/新增某域逻辑，代码只落该域自己的包，通用基建进 `foundation/`，禁止另起炉灶或重建桥层。一个概念的全套规则收敛进该域唯一命名类（如 NPC 属性 → `NpcAttributes`），同域别处只引用、不清写。
- 动手前先读 `docs/domain-notes.md` 对应小节 + `docs/adr.md` 相关决策，不逐篇读文档。

## 二、硬规则

1. **build 即验证**：`./gradlew build` 是唯一门槛；项目不维护单测（`src/test` 已删），不写测试、不跑 `./gradlew test`。
2. **禁 `./gradlew runClient`**；首次运行或报 `clientRunVmArgs.txt` 缺失时先 `./gradlew neoForgeIdeSync`。模组已无 GameTest 与 datagen run 配置，开发验证只有 `./gradlew build`。
3. **高兼容**：不动原版行为、**不硬编码方块/物品引用**；功能 JSON 数据驱动、方块映射走标签。
4. **稳定性优先**：所有可能失败路径必有兜底，出错至少 `Log.warn()`（`foundation/log/Log`），禁静默失败/崩溃。
5. **纯逻辑不 import MC**：不依赖 MC 的算法/蓝图解析/任务评分/路由保持零 MC 依赖（唯一硬边界，为清晰可移植，将来想测随时能测）。
6. **API/事件只给两类真实需求**：addon/整合包要用的公开契约、真事件流。纯内部的"想调你一个方法"不许包装成 API 或事件——直接调用。
7. **改数据格式要么带版本号迁移、要么断档**：禁新增"缺 key 补默认"的无版本号兜底分支；删字段就真删，不留兼容别名。SavedData 顶层存 `version` 走显式迁移链。开发期不承诺存档兼容。
8. **上屏/聊天只留错误与完成反馈**，其余用 Log。
9. **禁 emoji 与装饰图标**：面向玩家文本（`lang/*`、`guide/**`、I18n、Screen 内联、叙事 JSON）与源码注释都禁；只留 →←↑↓、×、⌊⌋。
10. **Config 注释中英双语**：`Config.java`/`ClientConfig.java`（及任何 `ModConfigSpec` `.comment()`）每条必须保留中文原文并紧跟一条英文翻译；新增键同样，禁只写单语。这些注释会生成进 config TOML 供玩家/整合包作者阅读。

## 三、易踩的代码事实（改这些代码前先读）

- **NPC 属性唯一源** `content/npc/attributes/NpcAttributes`：9 项（7 可见 + 2 隐藏），改规则只碰这一个文件，别处只引用。
- `content/magic/internal/CastBrain` **不是死码**（12 文件引用）。判死码用「编译 + grep 双关」，**不信代码图谱 in-degree**——实测定为 0 抽出的候选全是活类。
- `data/wandscape/buildings/deprecated/` 是**旧档兼容载荷，不可删**——旧档建筑按内层 `id` 解析，删即断旧档加载；本目录多次被误删过。
- **游客 ≠ 常驻市民**：`TouristState` 只是移动状态标记，禁扩展成带迁移的复杂状态机；真状态机是 `TouristMoveGoal.MoveMode`；禁向游客添加任何常住概念。
- **任务派发唯一通道** `TaskRequest → GlobalTaskPool → SchedulerSystem`，别另起炉灶。
- 自造点/向量族保留非 vanilla（`SplineVec3`/`PathPoint`/`XZPoint`/`GridPos`/`BlockOffset`），正是靠它保住"纯逻辑不 import MC"。
- NBT 传出 `return tag.copy()`；事件仅通知、要顺序用直接调用；不猜 MC/NeoForge 类名，用 `minecraft-source` skill 查源码再写。

## 四、工作流

- **设计/实现问题先澄清再写码**：用 `grill-me` skill 追问到需求明确、决策树每支敲定，禁止需求模糊时直接动手。
- **修 bug**：复现 → 修根因 → `./gradlew build` 验证。常见坑见 §三与 `docs/domain-notes.md`。
- 一个完整逻辑任务做完提交一次（见 §五）；中途被迫打断 flush 提交一次。

## 五、提交与发布

- 按逻辑任务聚合提交，同任务多次小改动合并；**代码+文档合一条 commit**，前缀按代码类型（`fix:`/`feat:`/`refactor:`/`chore:`），纯文档任务才 `doc:` 单列；中文一句：改动什么 + 为什么。
- 同一分支同一时刻只允许一个 AI 提交；**绝不 reset/stash/rebase 他人提交**，对方提交落中间就 cherry-pick 到最新；要并行开独立分支/worktree，最后手动合并。
- 新文件要么 `git add` 要么 `.gitignore` 排除，不残留 untracked。
- 版本号不主动递增；发布流程与 release 正文模板见 `docs/checklists.md`。

## 六、子代理使用

- **先拆解再委派**：分析任务可拆哪些独立子任务再决定并行；互不依赖的多 Agent 并行缩短墙钟。
- 单文件查找/一行修改自己做；只有多文件扫荡/跨模块搜索/独立研究有并行收益才委派，并给足上下文（找什么、边界、返回格式）。
- 重构/移动期间**禁止就同一批文件并行写**：同一分支只允许一个 AI 写。