# 迁移到 DeepSeek Harness（dsh）

> 信息截至 2026-10-04，来源为 `deepseek-ai/deepseek-harness` 官方仓库文档（`docs/`、`packages/*/README`）与 GitHub API / dshbase.com 实测。
> **dsh 处于 developer preview，官方明示「THERE WILL BE COMPATIBILITY-BREAKING CHANGES」。** 本文的命令、配置键、包名以官方仓库为准；升级 dsh 后请复核本页。

---

## 一、dsh 是什么

一个开源 agent harness，架构口号「一切皆插件」，跑在 [Cordis](https://github.com/cordiverse/cordis) 运行时上：模型、工具、skill、会话、沙箱、存储、agent loop、UI 全都是插件。CLI 名 `dsh`，MIT。

### 安装与启动

```sh
npx @deepseek-ai/dsh web          # 默认 http://127.0.0.1:3080，并打开浏览器
npx @deepseek-ai/dsh web --no-open
```

源码方式：`git clone https://github.com/deepseek-ai/deepseek-harness.git` → `pnpm install` → `pnpm run build` → `pnpm dsh web`。

模型凭据在 **UI 的 Settings → Models** 里填，不是配置文件，保存后即时生效。

### 桌面版（官方 Electron，不是第三方套壳）

DeepSeek 有**第一方桌面版**：官方仓库的 `apps/desktop/` 是完整 Electron 应用（electron-builder 配置、NSIS 安装器、托盘图标、`dsh.cmd`）。README 原话：「The desktop application is an Electron shell around the complete dsh Web application.」

- 下载（landing page `https://www.deepseek.com/harness/`）：
  - Windows：`https://download.deepseek.com/desktop/dsh-latest-windows-x64.exe`
  - macOS：`https://download.deepseek.com/desktop/dsh-latest-macos-arm64.dmg`
- **不走 GitHub Releases**——官方 25 个 tag 的 release assets 全是空的，只能从 DeepSeek 自己的 CDN 取，别去 release 页找安装包。
- 装完是托盘应用，关窗即最小化；菜单里有 Manage dsh Command… 可把 `dsh` 加进 PATH。
- `desktop` 是 Electron 独占的 profile：CLI 不能 boot 它，**给它装插件要用 `--profile desktop`（且需先退出应用）**；CLI/浏览器版插件则是 `--profile web`。
- 与 npm 的 `@deepseek-ai/dsh` **1:1 锁版本**。

### 入口模式（对照 CC 的「就是 `claude`」）

| 命令 | 用途 |
|---|---|
| `dsh web` | Web UI profile（默认主入口） |
| `dsh --profile <name>` | 启动 `$DSH_HOME/profiles/<name>` 下的 profile |
| `dsh --profile headless "任务"` | 跑一次会话、打印最终答案、退出（适合脚本/CI） |
| `dsh --profile acp` | 以 ACP stdio 服务自动化客户端 |
| `dsh --profile sdk` / `sdk-minimal` | JSON-RPC stdio |
| `dsh plugin --profile <name> <pnpm args>` | 管理该 profile 的插件（转发给 pnpm） |
| `dsh --dump-config` / `--dump-default-config` | 不启动，直接看合成后的配置树 |

启动目录即默认工作区根。

### 运行时预设（类比 CC 的模式切换）

Web 侧随发行提供四个 agent preset：`standard`（Standard mode，默认，全工具）、`ptc`（PTC mode，批量调工具后再过滤/去重/汇总，即「Code 模式」）、`minimal`（Minimal mode，只有终端工具，供测试对比）、`cordis`（Creator mode，让 agent 写插件改 UI）。注意发行版**没有 TUI profile**，`tui` 只是文档里的示例名。`DSH_TOOLS_MODE` 可切 `native` / `ptc` / `both`。

### 问答与审批（对应 CC 的 AskUserQuestion）

dsh **内置** `ask_user_question`（包 `@deepseek-ai/dsh-tool-ask-user`），基本是 CC `AskUserQuestion` 的**超集**：同样 `question` / `header` / 2–4 个选项 + 每项一句说明；额外支持**一次问多个问题**、**multi-select**、显式自由文本 `custom`（即 Other）、「推荐项放第一并标注 (Recommended)」约定，以及可选的 **timed 模式**（超时后放模型继续干活，问题仍可回答）。Web 端有专门的问答卡片占住输入框（`packages/client/ui-user-questions/`），TUI 端有 `QuestionDialog`，并通过 `$.ui.ask` 开放给插件作者。

**两处没有**：MCP 协议的 elicitation 不支持；`approval` 闸门只能是 yes/no（`allowed-once` / `rejected` / `cancelled` / `unavailable`）——设计如此，和问答是两套独立机制。

### 子代理与嵌套深度（默认已禁止层层委派）

委派工具是 `subagent`（包 `@deepseek-ai/dsh-tool-subagent`；base profile 另挂 `subagent_fork`）。**好消息：我们要的「子代理不许再开子代理」是官方默认行为。**

- **`maxDepth` 默认 1**：根（depth 0）可委派一层，**子代理（depth 1）再开孙代理会被硬拒**（`SubagentDepthError: subagent depth 2 exceeds maxDepth 1`）。语义正好。
- **但仍建议显式钉死 `maxDepth: 1`**：官方这个默认值改过一次（2026-07 的 Agent Note 写 3，现行 catalog 是 1）。钉死可防止将来默认值被调大时静默重新打开递归。
- 取值：`0` = 连根都不许委派；`'provider-managed'` = 不设上限。
- 为什么根上的设置能管子代：子代理继承父的 preset，连带同一个委派工具实例，所以根配的 `maxDepth` 对子代同样生效——这正是「根能委派、子不能」可被强制的原因。
- **并发上限**：`@deepseek-ai/dsh-subagent` 的 **`maxActiveSubagents` 默认 8**（想对齐我们的「并发 ≤4」就调成 4）；另有 `@deepseek-ai/dsh-agent-loop` 的 `maxParallelToolCalls`（每步并行工具数）与 `@deepseek-ai/dsh-jobs-local` 的 `maxConcurrentJobsPerOwner`（默认 10，后台任务按属主计）。
- 备用硬闸：委派工具上的 `toolFilter: { deny: ['subagent', 'subagent_fork'] }`——被过滤的工具**从子代理 prompt 里消失且拒绝执行**。
- **做不到的**：`SubagentStart` hook **不能拦截**（它 detached 且只加上下文，没有任何扩展点 await 它）；`PreToolUse` hook 能 deny，但分不清根与子（桥只报常量 `agent_type`），写下去等于把根也一起禁掉。所以嵌套限制别指望 hook。
- `DELEGATED_CALLER` 确实是「根 vs 子」的真实接缝，但它目前**只守** `ask_user_question` / `exit_plan_mode`，没接在委派路径上——将来官方若把它接上，这里才有更细的余地。

### 配置模型（这是和 CC 差别最大的地方）

没有 `settings.json`，也没有 `.mcp.json`。全部是 **Cordis 的 YAML patch 层**，从下往上叠加：

```
bundle 默认 patch
  → profile 的 cordis.patch.yml
  → $DSH_HOME/cordis.patch.yml        （全机器所有 profile）
  → --patch <file>                    （单次运行覆盖）
```

`$DSH_HOME` 默认 `~/.dsh`。**patch 是整行替换，不是深合并**——针对同一个 id 写 patch 会把它整个 `config` 换掉，不是逐键覆盖。YAML 里要写 `!!js`（**不是 `!js`**）。普通 patch 编辑走 HMR 热重载，但 bundle 增删要重启进程。

### CC ↔ dsh 概念对照

| Claude Code | DeepSeek Harness |
|---|---|
| `CLAUDE.md` | `AGENTS.md`（`CLAUDE.md` 也仍被读，见 §二.1） |
| `~/.claude/rules/*.md` | 无此机制，内容并入 `~/.dsh/AGENTS.md` |
| `.claude/skills/<n>/SKILL.md` | `.agents/skills/<n>/SKILL.md`（格式兼容） |
| `.mcp.json` | `cordis.patch.yml` 里的 `@deepseek-ai/dsh-mcp-client` 条目 |
| `settings.json` 的 `permissions.allow` | 无逐命令白名单；改用 sandbox + approval preset |
| hooks（`hooks.json` / settings hooks） | `@deepseek-ai/dsh-hooks-claude-code` 桥 |
| `/plugin marketplace` | `dsh plugin`（pnpm） |
| memory 目录 | **无内置**，见 §四 |

---

## 二、现有资产迁移清单

### 1. 项目指令：已收拢为单文件（已执行）

dsh 的 `@deepseek-ai/dsh-agent-instructions`（`dsh-base` 默认启用）加载：`$DSH_HOME/AGENTS.md`（全局）+ 项目链，候选文件名 `['AGENTS.md', 'CLAUDE.md']` 为基底、`['AGENTS.local.md', 'CLAUDE.local.md']` 为本地覆盖层，项目根标记 `.git`，预算 `maxBytes: 65536`，broad→specific 顺序注入，**内容相同者去重**。

本仓库已完成的处理：原 `CLAUDE.md` 正文整体并入 `AGENTS.md`（`AGENTS.md` 成为准则唯一真源），`CLAUDE.md` 收缩为一行 `@AGENTS.md`。

**为什么不直接删掉 `CLAUDE.md`（关键，别踩）**：

- 本机 CC 是 **v2.1.241**，而 CC 读 `AGENTS.md` 需要 **≥ 2.1.277**；网关 / 关闭遥测场景要到 **≥ 2.1.281**（本机走 `ANTHROPIC_BASE_URL=http://127.0.0.1:15721` 本地代理，正属这一类）。
- 即便升级，CC 也**只在工作目录及其所有上级都没有 `CLAUDE.md`/`CLAUDE.local.md` 时才读 `AGENTS.md`**——本仓库有私有的 `CLAUDE.local.md`，它同样会挡住。
- 留一行 `@AGENTS.md` 指针，CC 的 `@import` 会展开，**任何版本都能用**，不必升级、不必改全局设置。

dsh 侧代价：读到 `AGENTS.md`（全文）+ `CLAUDE.md`（一行字面量 `@AGENTS.md`，dsh 不展开 import），去重不触发但只多 10 个字符。将来想彻底删掉，条件是两个都满足：CC 升到 ≥ 2.1.281，**且**把用户级 settings 的 Project instructions 设为 `claude-md-and-agents-md`（绕开 `CLAUDE.local.md` 的阻挡）。

顺带：`docs/` 里原有的「CLAUDE.md §二.9」这类交叉引用已同步改名为 `AGENTS.md`。

> 注意：dsh 的指令发现只跟随结构化文件工具（read/write/edit），**不跟随 bash 的 cd**；靠 `.git` 向上找根。另外 CC **不认** `AGENTS.local.md`（只认 `CLAUDE.local.md`），而 dsh 两者都认——所以本地覆盖层继续用 `CLAUDE.local.md`，两边通吃。

### 2. 全局规则：`context7.md` 搬进 `~/.dsh/AGENTS.md`（已执行）

`~/.claude/rules/context7.md` 在 dsh 下**不会**被加载（`.claude/rules/` 不被解释）。

**已执行**：内容原样搬进 `~/.dsh/AGENTS.md`（ctx7 的命令与判据一字未改，只把散文改写成中文），`~/.claude/rules/context7.md` 已删——留着就是同一份规则两处维护。实测 dsh 打开会话即注入该文件（无需重启，会话中途新建也立刻生效）。同理，将来加全局规则直接写这个文件。

> 注意这是**跨项目**的用户级规则，不随本仓库走（`.dsh/` 不在仓库里）；仓库自己的准则仍然只写 `AGENTS.md`。

### 3. Skills：格式兼容，搬目录 + 改路径

dsh 的本地 skill 搜索根（按 rank 优先）：

| rank | 来源 | 目录 |
|---:|---|---|
| 100 | project-dsh | `<repo>/.dsh/skills` |
| 200 | project-agents | `<repo>/.agents/skills` |
| 300 | custom | `Config.customSkillDirs` |
| 400 | user-dsh | `<DSH_HOME>/skills` |
| 500 | user-agents | `<agentsHome>/skills` |
| 600 | bundled | 配置的 `bundledSkillDir` |

**`.claude/skills` 不在列表里。** 但格式是兼容的：目录束 `<name>/SKILL.md` 或平铺 `<name>.md`，名字必须 kebab-case（`^[a-z0-9]+(?:-[a-z0-9]+)*$`），frontmatter **必填 `name` 与 `description`**，另认 `whenToUse` / `metadata` / `disable-model-invocation` / `user-invocable`（后两个省略即 `true`）。**不支持递归 `**/SKILL.md`。** 有 Chokidar 监视根目录，改完不用重启。

**已完成**：两个 skill 都搬到 `.agents/skills/`，SKILL.md 内的脚本路径同步改成 `.agents/skills/...`；`.gitignore` 从 `.agents`（整目录忽略）改为 `.agents/*` + `!.agents/skills/` + 单独忽略 `scripts/cache/`——**从此 skill 真正随仓库走**（此前 `.claude/` 被整体忽略，它们只存在于本机）。

> **踩过的坑（2026-10-04 复核补记）**：上一版改动其实是**静默失效**的。`.gitignore` 里原本另有一条 `.agents/`（在「Dev tools」段），而 git **不会进入被排除的目录**，后面的 `!.agents/skills/` 再也捞不回来——`git check-ignore` 与 `git ls-files` 双向核实：skill 当时一个文件都没入库。后来那版无效配置被回滚成了 `.agents/`。**正确写法是把原来那条 `.agents/` 换成 `.agents/*` + `!.agents/skills/`**，不能两条并存。已按此修正，`minecraft-source` 的 4 个文件现已 `git add` 入库。

**坑一：description 会被截断。** dsh 的模型可见目录只收 `name` + `description`，且 `catalogDescriptionMaxLength` **默认 500**。原 `minecraft-source` 的 description 是 **1100 字符**，第 500 字符断在句子中间——尾部那整串触发词（`Triggers on: …`）**全部丢失**；技能仍能按名加载，但模型路由时的命中率会明显下降。已重写为 **456 字符**并把触发词前移。

> 直觉陷阱：dsh 有 `whenToUse` 字段，看着正好装触发词——但官方明确它**对模型隐藏**（目录 "omits … routing hints"）。所以触发词必须留在 `description` 里，不能挪进 `whenToUse`。

**坑二：CC 兼容靠 junction。** `.claude/` 被 gitignore，真文件必须住在 `.claude/` 之外才能入库；而 CC 只从 `.claude/skills` 发现技能。处理：真文件放 `.agents/skills/`，把 `.claude/skills` 做成指向它的 **Windows 目录 junction**（`mklink /J`，无需管理员权限），CC 与 dsh 都能看到。CC 退役后删掉这个 junction 即可。

其余：`scripts/cache/` 由脚本以 `$(dirname $0)/cache` 定位、随目录搬家，已单独 gitignore（LRU 上限 50 个文件）；`compatibility:` 不是 dsh 文档化的 frontmatter 键，会落进 `metadata`，无害。

未采用的可选替代：不搬目录，把 `.claude/skills` 配进 `Config.customSkillDirs`（rank 300）——能跑，但那就永远绑在 `.claude` 这个名字上。

### 4. MCP：唯一一个 server，改写成 YAML 条目

现在 `codebase-memory-mcp` 声明在 `~/.claude/.mcp.json`：

```json
{ "mcpServers": { "codebase-memory-mcp": { "command": "C:/Users/huhai/.local/bin/codebase-memory-mcp.exe" } } }
```

dsh **不读这个文件**。要在 `$DSH_HOME/cordis.patch.yml`（全 profile）或 `$DSH_HOME/profiles/<name>/cordis.patch.yml`（单 profile）里加一条：

```yaml
- insert:
    - id: codebase-memory
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: codebase-memory
        transport: stdio
        command: C:/Users/huhai/.local/bin/codebase-memory-mcp.exe
        args: []
        env: {}
        cwd: !!js process.cwd()
```

要点：

- 工具暴露名变成 `mcp__<serverName>__<tool>`，即 `mcp__codebase-memory__search_graph` 等。**CC 侧的短名 `search_graph` 不再成立**——`AGENTS.md` 与全局技能里凡提到工具名的地方都要跟着改。
- `serverName` 须匹配 `[A-Za-z0-9_-]{1,32}` 且在存活实例中唯一。
- stdio 桥启动子进程前会**剥离环境变量名匹配 `/KEY|PASSWORD|SECRET|TOKEN/i` 的，以及所有 `DSH_*`**；需要传的 secret 写进 `config.env`（配置的 env 叠加在被清洗过的环境之上），别放 YAML 明文。
- 其余键与默认值：`toolCallTimeoutMs`（60000）、`failOnStartupError`（false）、`maxInstructionBytes`（32768）、`reconnect`（`enabled` true / `initialDelayMs` 500 / `maxDelayMs` 30000 / `maxAttempts` 10）。远程 server 用 `transport: streamable-http` + `url` + `headers`。
- 不支持：MCP prompt 模板、elicitation、task 执行、resource 订阅。

**已执行 + 实测复核（2026-10-04）**：条目落在 `~/.dsh/profiles/desktop/cordis.patch.yml`（`desktop` 是桌面版独占 profile，CLI 不能 boot 它，连 `--dump-config` 都被拒，所以只能靠配置热重载实测）。实测结论：

- 顶层 `- insert:` **不带 `id`** 时是追加到根 entry 列表（`dsh-app-boot` 的 `applyEntryPatches`：`if (insert) { if (id) {...group...} else data.push(...insert) }`），新增一行 MCP server 用这个形状即可，不必指向某个 group。
- **`cwd` 必须显式给一个非空真实目录——这是最容易静默踩死的一格。** `mcp-client` 的 stdio transport 把 `cwd` 原样交给 `spawn`，而它的 schema 默认值是**空串**（`cwd: z.string().default("")`，见 `lib/index.js` 的 `Config`）；`spawn(cmd, args, { cwd: '' })` 实测直接 `ENOENT`。**照抄官方默认 = 一个工具都注册不出来**，而且 `failOnStartupError` 默认 false，只在日志里留一行 error，看起来像「配置好了但没反应」。另外该 server 拿 `cwd` 当 session root，启动即按它推导项目名并自动索引/监听——给错目录会去索引无关的大目录。本机写成 `cwd: !!js "process.env.DSH_MCP_CWD ?? '<repo>'"`：`!!js` 在**宿主**进程求值，不受 mcp-client 对子进程环境的清洗（`DSH_*` 与 `/KEY|PASSWORD|SECRET|TOKEN/i` 一律丢弃）影响，所以环境变量仍能覆盖。
- **改完不用重启**：配置层走热重载，当场就能看到工具出现。实测 14 个工具全部注册为 `mcp__codebase-memory__*`，`list_projects` 调用成功。
- **工具名确实变长**，全仓引用已同步：`AGENTS.md`（知识图谱那条）、`CLAUDE.local.md`。
- **全局 skill 也要跟着改**：`~/.claude/skills/codebase-memory/` 是 CC 侧的，而 dsh 的本地 skill 根不含 `.claude/skills`。已在 **`~/.dsh/skills/codebase-memory/SKILL.md`**（rank 400 `$DSH_HOME/skills`）落一份 dsh 版：工具名带前缀、写明 `project` 参数、并把本仓「in-degree 不能判死码」的提醒写进去。CC 侧那份**刻意保留**——两边工具名不同，不是同一份文件的重复，硬做 junction 反而会让 CC 拿到错误的名字。
- **一个待处理项：同一路径两个索引**。`list_projects` 同时列出 `C-Users-huhai-Desktop-Java-mcmod-wandscape`（约 43.7k 节点，由 cwd 推导、服务端自动索引与监听的就是它）和 `wandscape`（约 23.7k 节点，覆盖不全）。**用前者**，skill 与 `CLAUDE.local.md` 都写明了；要清掉旧的用 `delete_project`（本次未删，留给你决定）。

### 5. Hooks：三个 cbm 脚本当前是死的，先查再迁

`~/.claude/hooks/` 下有三个脚本（`cbm-code-discovery-gate`、`cbm-session-reminder`、`cbm-subagent-reminder`），但**在 `~/.claude/settings.json`、`settings.local.json` 和整个 `.claude/` 里找不到任何注册它们的 `hooks` 键，也没有 `hooks.json`**。也就是说它们根本没在触发，是 codebase-memory-mcp 安装残留。

**已核实（2026-10-04）：确实没在跑**——逐个查了用户级 `settings.json` / `settings.local.json`、仓库 `.claude/settings.local.json`，全都没有 `hooks` 键；`~/.claude` 下也不存在 `hooks.json`（`find` 命中的那些 `hooks.json` 全在第三方插件缓存目录里，属插件自己的清单）。所以**没有迁移问题**：不用给 `@deepseek-ai/dsh-hooks-claude-code` 配 `configPath`。

那三个脚本仍留在 `~/.claude/hooks/`（本次未删，属 CC 侧残留）。真要迁的话结论依旧成立——`cbm-session-reminder` 是 `cat` 到 stdout，而 dsh 的 `SessionStart` 只消费 JSON 的 `additionalContext`；`cbm-code-discovery-gate` 是「只加图上下文、从不拦截」，而 `PreToolUse` 的 `additionalContext` 被忽略。两个都属「往上下文里塞东西」型，桥复用不了。图谱那份上下文提示已由 `~/.dsh/skills/codebase-memory/` 的 skill 承担。

要迁的话，dsh 有桥：`@deepseek-ai/dsh-hooks-claude-code`，`configPath` 指向你的 `hooks.json` 或含 `hooks` 键的 settings 文件，支持 `SessionStart` / `UserPromptSubmit` / `PreToolUse` / `PostToolUse` / `Stop` / `SubagentStart` / `SubagentStop`。但**有限制，且正好打在这三个脚本上**：

- `SessionStart` 只消费 JSON 的 `additionalContext`，**纯 stdout 不生效**。`cbm-session-reminder` 正是 `cat` 到 stdout → 必须改写为输出 JSON 才会工作。
- `PreToolUse` 的 `additionalContext` **被忽略**（只有 `deny` / `ask` 生效）。`cbm-code-discovery-gate` 的语义是「只加图上下文、从不拦截」，其输出会被丢弃——等于该 hook 在 dsh 下无事可做。
- 只跑 shell-form command handler；`http`/`mcp_tool`/`prompt`/`agent` handler 跳过。30 个 CC hook 事件里只支持 7 个。
- 一个进程只解析一个 `configPath`，加载时读一次；没有 CC 的分层发现与热重载。
- 事件 payload 里的 `transcript_path` **永远是空串**：dsh 的会话日志默认是 Zstandard 压缩的，hook 脚本读不了。

结论：桥能复用「拦/放行」型 hook，**复用不了「往上下文里塞东西」型 hook**。cbm 这两个恰好都是后者。

### 6. 权限：allowlist 无法迁移

CC 的 `permissions.allow`（`Bash(./gradlew build *)`、`Bash(git commit *)` 等一长串）在 dsh **没有等价物**——官方文档、config catalog 里都没有「允许命令清单」这类配置。dsh 把执行策略拆成两个正交旋钮——sandbox mode（`sandbox/mode`：`read-only` / `workspace-write` / `danger-full-access`，只管文件系统副作用）与 approval policy（`approval/policy`：`ask` / `never`）——再打包成 preset 供 UI 选择。preset 表**可配置**，base bundle 里预设了三档：

- `read-only` + `ask`
- `workspace-write` + `ask`（默认，由 `DSH_PERMISSION_MODE` 覆盖）
- `danger-full-access` + `never`

也就是说粒度从「逐命令模式匹配」退化成「整档信任级别」。这一条是**净损失**，需要有心理准备：要么接受更频繁的审批弹窗，要么整档放开。

> Windows 注意：sandbox 在 Windows 上由 ACL 受限令牌实现，官方明确 enforcement 可能是 `full` 也可能是 **`partial`**——别假设它和 Linux 的 bwrap 一样严密。

### 7. 已装的 CC 插件：不迁

`superpowers`、`planning-with-files`、`playwright-skill`、`aidevdesk-aidesk`、`example-skills` 这些是 CC 插件生态的东西，与 dsh 无关，不迁移。需要哪个功能去 dsh 侧找对应物再装（`dsh plugin`）。

社区确实有两个「把 CC 资产搬过来」的第三方桥：`dsh-claude-marketplace`（1 星，能读 `.claude` 的 skills/commands/agents 和 CC marketplace）与 `dsh-plugin-session-import`（7 星，导入 CC 会话历史）。按你自己定的标准，1 星和 7 星都在「完全不值」区间，且本仓的 skill 只有两个、直接手工搬更快——**不推荐引入**。官方也**没有** `.mcp.json` 或 `.claude/skills` 的导入器。

---

## 三、dsh 插件生态调研：对 Java / Minecraft / 游戏开发，结论是「不值得」

直说：**没有一个够格推荐。**

生态总量不小——GitHub `dsh-plugin` topic 共 **17,472** 个仓库，`deepseek-ai/deepseek-harness` 本体 243k 星。但结构很差：

| 星标阈值 | 仓库数 | 占 topic |
|---|---:|---:|
| >100 | 340 | 1.9% |
| >500 | 137 | 0.8% |
| >1000 | 86 | 0.5% |

- **注水严重**：高星榜里混着大量蹭 topic 的无关项目（`reactive-resume` 43.7k 是简历生成器、`PicGo` 27.3k 是图床工具、`nocobase` 24.4k 是低代码平台、`Tencent/WeKnora` 31.9k 是 RAG 平台）。真实 dsh 工具生态的星数远低于榜单观感。
- **语言偏斜**：TypeScript 5,413 个、Python 613、Kotlin 18、**Java 11**。生态主体是 Web UI 皮肤、桌面壳、聊天客户端和 memory/RAG 集群，**不是多语言开发工具生态**。

Java / Minecraft / 游戏相关，全部落在 0–57 星：

| 星 | 仓库 | 相关性 |
|---:|---|---|
| 57 | `opdsh/unity-plugin` | 用 `unity` CLI 驱动 Unity 编辑器 |
| 32 | `akira399/dsh-godot-skill` | Godot 4.x 全栈 skill |
| 26 | `PerryLink/dsh-lsp-actions` | 通用 LSP（诊断/格式化/补全/重命名） |
| 19 | `sikadi233-hub/minecraft-dev` | **最相关**：Paper/Fabric/Forge/NeoForge + Gradle/JDK，MC 1.7.10–26.x |
| 17 | `HarcoChen/dsh-vsc-integration` | VS Code 集成 |
| 13 | `PeterTXPan/dsh-unreal-mcp` | UE 5.8 MCP |
| 3 | `988hj7tczd-oss/dsh-lsp-packs` | 含 `dsh-lsp-java`（Eclipse JDT）子包 |
| 3 | `Chocomintopia/dsh-jetbrains-plugin` | dsh WebUI 塞进 JetBrains 工具窗 |
| 1 | `Javelle1025/dsh-mcmod-configurator` | MC mod 脚手架 |

另外 `topic:dsh-plugin gradle` 全 topic **只有 2 个仓库、都是 0 星**；`maven` 为 0。榜单前 100 里没有任何 LSP 插件。

插件市场 dshbase.com 收录 7,797 个插件，但**不显示任何单插件下载/安装数**，站长自己明确拒绝按星排名（"stars don't equal installability"）。它的增长曲线在 08-25 有一个从 ~1.9k 到 ~7.8k 的跳变，是一次批量目录合并，不是自然增长。

**结论**：在 dsh 上做 Java/MC 开发，工具面基本等于「CC 那套通用 MCP + 裸 bash/编辑」，不要指望这个生态。真要试，只有一个 `sikadi233-hub/minecraft-dev`（19 星）勉强对口，且属 hobby 规模、无采用数据——**装它是冒险，不是投资**。

### 通用工作流插件：有，但同样不成气候

抛开 Java/MC，只看「通用工作流」这一类（对应 CC 的 superpowers / planning-with-files / grill-me），确实有东西可选：

> 本仓的 `grill-me` 技能**已删**（2026-10-04）：它本来就只是「追问到决策树每支明确」的一段 prompt，实际用下来价值不足；`AGENTS.md` 里那条要求保留为一条普通准则（「需求或设计没敲定就先追问清楚再动手」），不再挂技能。下面提到它是为了说明 dsh 生态在这一档有没有替代品。

| 星 | 仓库 | 用途 |
|---:|---|---|
| 13,337 | `EverMind-AI/EverOS` | 便携、本地优先的 Markdown 记忆层（跨 agent） |
| 11,693 | `MemTensor/MemOS` | 自演化记忆 OS，显式支持 dsh |
| 6,178 | `Q00/ouroboros` | 「Agent OS」：访谈门控 + 分阶段评估 |
| 2,711 | `zilliztech/memsearch` | 持久记忆层（Markdown + Milvus） |
| 1,836 | `bowenliang123/dsh-context` | dsh 原生上下文仪表盘 |
| 1,503 | `hyhmrright/brooks-lint` | 基于 12 本工程书的 AI 代码审查 |
| 1,317 | `GanyuanRan/Aegis` | 让 agent 有架构意识：基线优先、证据校验、漂移检查 |
| 101 | `LayneChai/superpowers-dsh` | **superpowers 的 dsh 移植**（15 个技能）——最好的一版也只有 101 星 |
| 54 | `PerryLink/dsh-doublecheck` | 「拷问需求 → 测实现 → 证交付」，最接近 `grill-me` 的一档 |

注意榜首那几个（EverOS / MemOS / memsearch / brooks-lint / Aegis）都是**跨 agent 工具顺带支持 dsh**，不是 dsh 原生。而「苏格拉底式追问 / grill」这一整类**没有一个超过 ~50 星**。

> **星数口径警告**：`EverOS` 的 13,337 星是**记忆框架本身**的，它的 dsh 插件在另一个 **7 星**的侧仓库里（`EverMind-AI/Plugins` 的 `dsh/` 子目录），README 自称还是「pre-release verification」；`MemOS` 的 dsh 适配器在 npm 上只有 0.1.1、最后发布 2026-09-07。**别被框架的星数误导**，那不代表 dsh 侧可用。
> 另外：本仓 memory 已决定落回仓库文档（见 §四），不引外部 memory server——所以这三个对本文读者价值不大。

**值得装的**（活跃、dsh 原生、npm 可装）：

| 插件 | 星 | 作用 |
|---:|---|---|
| `dsh-context` | 1,837 | 上下文用量仪表盘 / 侧栏 |
| `dsh-deja` | 1,127 | 会话历史全文搜索（单个 Go 二进制，不走 LLM） |
| `superpowers-dsh` | 101 | CC `superpowers` 的 dsh 移植（TDD / 调试 / 规划） |
| `dsh-doublecheck` | 54 | 「拷问需求 → 测实现 → 证交付」 |

装法统一：`dsh plugin --profile <web|desktop> add <包名>`。`dsh-pocket`（1,509 星）与 `dsh-dafeiyu`（392 星）都已 2.5–3 周未更新，且官方桌面版（见 §一）已覆盖「悬浮/托住」这类需求，可跳过。

### 「悬浮球」：能看的有，能操作的没有

想在 MC 全屏时一边测一边管 agent：

- `QCYTSN/dsh-dafeiyu`（**392 星**）——唯一有像样采用的**置顶原生窗口**，由 agent 真实状态驱动；但 README 明说**不接受键盘输入**，点击只触发动画。
- `cyh3436332528/dsh-float-chat`（**1 星**）——唯一真正**带输入框的置顶窗口**（WinForms/WebView2，仅 Windows）。星数约等于零采用。
- `jtt0001/dsh-progress-overlay`（**1 星**）——Windows 置顶进度板，可在上面直接答复审批请求。
- 更成熟的路子是**不装桌面插件，改用手机当第二屏**：`shaobeichen/dsh-pocket`（**1,508 星**，扫码把 `dsh web` 投到手机）、`Clarklevis1995/dsh-mobile`（445 星，iOS）、`saya-ch/dsh-mobile`（367 星，Android）。这条比置顶窗口靠谱得多。
- 另有 `Lxiayu/DshCockpit`（38 星）带全局热键 Quick Ask；`drewnekota/cetus`（146 星）有热键启动器但**只有 macOS**。

> Windows 上 MC 的「全屏」实际是无边框窗口，所以置顶窗口通常**能**压在游戏上；只有独占全屏才会盖住它。

---

## 四、memory 怎么迁移

### 前提事实：dsh 没有内置 memory

官方给 memory 的答案是**接入第三方 memory MCP server**，且示例配置默认全部关闭。可选项：Memorix、MCP Reference Memory（`@modelcontextprotocol/server-memory`）、Engram——都是通过 `--patch` 挂载的外部进程，dsh 自己不存、不迁移、不做摘要/冲突消解/遗忘策略。

所以 memory 只有两个去处：

- (a) 外部 MCP memory server；
- (b) 写进 `AGENTS.md` / 项目文档。

**推荐 (b)。** 理由很直接：我们这 38 条 memory 里几乎全是**项目事实与团队约定**——「三值叫舒适值/魔法值/奇观值」「法师死亡是刻意设计」「提交只按『我改过吗』划线」。这些本来就该是仓库自己的资产：随仓库走、能 review、能 diff、能随代码改动一起提交。塞进第三方 memory server 等于把它变成黑盒，还多一个要维护的进程。

顺带一提：memory 是 CC 的机制。迁移完成后，CC 侧也不该再双写——否则又回到「文档多源漂移」。

### 现状盘点：38 条里大部分不该留

对现有 memory 逐条核对了「是否已被 docs 覆盖」和「引用的文件/类名是否还存在」：

- **已被现有文档完全覆盖：6 条** → 删。例：`feedback_debug_console_vs_chat`（已是 `AGENTS.md` 硬规则「上屏只留错误与完成反馈」）、`project-lang-placeholder-s-only`（已是 `AGENTS.md` 的 `%s` 规则）、`project-npc-damage-intentional`（已是 `docs/domain-notes.md` §一.11）、`feedback_building_rotation`（已是 `domain-notes.md` §五.4）、`project-building-scanner`（已是 §五.1 + §五.3）、`project_element_audit_run`（已在 `AGENTS.md` §构建与验证）。
  > **订正（2026-10-04 执行时）**：本节原先还把 `reference-bytebufferbuilder-leak` 也列进这一类，说它「现已收进 `domain-notes.md` §五.13」。实际 §五.13 当时只覆盖了「没有 `Cleaner` / `MeshData.close()` 不 free 指针」这一层，**「谁关它 / try-finally / `resize` 是 realloc」是缺的**，执行时才补上。教训：一条 memory 与落位表指向同一处，不等于内容已经全在——落位时必须回到那份文档逐句核对，不能凭「表格里写了」就判覆盖。
- **已过时／锚点消失：4 条**（订正：`project-building-scanner` 归上一类，它行为已在 §五.1）→ 删，不迁。例如 `feedback_plan_block_means_code`（锚在已删除的 `architecture/plan/`、`newplan/`）、`project_eventbus_nondispatch_in_test`（项目已无单测，`src/test` 在 2026-09-01 的 ADR 里删了，前提不存在）、`feedback_tier2_word_decisions`（锚在已删的 `newplan/tier2-rename.md`）、`project-guide-audit-baseline`（v1.11.1 的时间点快照，模组已是 2.x）。
- **引用了重命名/搬走的符号**：`reference-log-debug-promotion` 等多条需修指针（如 `shared/ui/ReplayScreenGuard.java` → `foundation/ui/ReplayScreenGuard.java`；`GuardCombat.CAST_MIN_INTERVAL` → `MELEE_COOLDOWN_TICKS`；`docs/bugs/` 目录已不存在）。

### 落位规则：**别建万金油**

技术类 memory 一律并进**已有的对应文档**——新建一个 `agent-notes.md` 大杂烩装它们，只会重演 CLAUDE.md 明确警告的「文档多源漂移」：

| 内容 | 去处 |
|---|---|
| 生产建筑按产物命名、三值定名、`BuildingConfig` 当键的坑、扫描器 | `docs/domain-notes.md §五` |
| 虚影不降级 / 实心壳观感 / translucent 根因 | `docs/domain-notes.md` §五.13 |
| 手册分页两坑（跳页档、`$(l:)` 被硬切） | `docs/guidebook.md §4.1` |
| 客户端 `getColonyLevel()` 恒 0 的陷阱 + `WandscapePanelState` 解法 | `docs/domain-notes.md §一.8` |
| 友军名单 + `registerAlly` 已知未修风险（`PLACEHOLDER_COLONY`/`sameColony`） | `docs/domain-notes.md §一.6` |
| 生成器脚本放仓库根、curseforge 描述不提交 | `docs/file-layout.md §五` / `docs/checklists.md §三` |
| 元素审计命令 `/wandscape audit_elements` | `CLAUDE.md §四` |

### 唯一值得新建的：`docs/agent-notes.md`（跨切面工作流知识）

真正没有归宿的，是**不属于某个功能域**的工作方式与工具知识，约 7–9 条。建议结构：

1. **工作流与提交** — 并发写入者下按「我改过吗」划线提交，显式列路径；生成器脚本放仓库根；node/npm 命令交给用户跑；移植前先 `wc -l` 对账确认文件读全。
2. **通用实现取向** — 优先通用机制而非硬编码枚举；优先复用原版机制；不追求企业级干净；没反射的地方别写「避免反射」注释（关键词会被扫描器误命中）。
3. **调试与日志** — 用户实测是决定性证据，优先核对代码事实；`Log.debug` 会被提升成 INFO 前缀的日志。
4. **本机/外部工具** — `tools/jar-audit/` 自检脚手架；WebFetch 预检在本机可能失败；ReforgedPlay 回放检测反射 API（路径已修）。
5. **未修风险与待实测** — 多人平行隔离分支的剩余待验证；`FriendlyForce` 的 `PLACEHOLDER_COLONY`/`sameColony`。

`docs/README.md` 的导航表加一行指向它。

### 执行顺序

1. 先删（12 条：7 冗余 + 5 过时）——零风险，且不再误导。
2. 再并（技术项 → 已有文档），顺手修正过时指针。
3. 最后建 `docs/agent-notes.md` 装跨切面项，并给 `docs/README.md` 加导航。
4. 迁移完成后清空 memory 目录与 `MEMORY.md`，避免 CC 与 docs 双写。

### 执行结果（2026-10-04）

真源是 `~/.claude/projects/C--Users-huhai-Desktop-Java-mcmod-wandscape/memory/`（38 篇 + `MEMORY.md`）。落位共 **+46 行、0 删除**：

| 去处 | 落了什么 |
|---|---|
| `docs/domain-notes.md` | §一.6（`sameColony` 把 null / `PLACEHOLDER_COLONY` 当具体 UUID 比对的未修风险）、§一.8（客户端 `getColonyLevel()` 恒返 0 + `WandscapePanelState` 解法 + `RecipeUnlockChecker` 同型埋雷）、§三.5（魔法冷却常量就地定义 + `CAST_MIN_INTERVAL` → `MELEE_COOLDOWN_TICKS` 漂移）、§五.13 补子项（「谁关它」/ try-finally / `resize` 是 realloc）、§五.15（建筑显示名按产物命名 + 三值定名 + 动作词「复原」）、§五.16（`BuildingConfig` 当 map 键的坑 + 剩 5 张表未改）、§九.2（回放检测只反射公开 API）、§十.2（开容器 GUI 只 `ExplorationHudOverlay` 可见） |
| `docs/guidebook.md` §4.1 | 改正文触发重排的两坑（奇数正文页约束、`_hard_cut()` 硬切 `$(l:)`） |
| `docs/file-layout.md` §五 | 新生成器放仓库根、别放 `tools/` |
| `docs/checklists.md` §三.10 | curseforge / modrinth 描述是本地文件、不入库 |
| **`docs/agent-notes.md`（新建，64 行）** | 五节跨切面知识：工作流与提交、通用实现取向、调试与日志、本机工具、未修风险与待实测 |
| `docs/README.md` | 导航表 +1 行指向 `agent-notes.md` |

判定**已被 docs 完整覆盖、零动作** 6 条：`feedback_debug_console_vs_chat`、`project-lang-placeholder-s-only`、`project-npc-damage-intentional`、`feedback_building_rotation`、`project-building-scanner`、`project_element_audit_run`。判定**过时 / 锚点消失** 4 条：`feedback_plan_block_means_code`（`architecture/plan/`、`newplan/` 都没了）、`feedback_tier2_word_decisions`（锚在 `newplan/tier2-rename.md`）、`project_eventbus_nondispatch_in_test`（前提是单测，`src/test` 已删）、`project-guide-audit-baseline`（v1.11.1 时间点快照，模组已 2.x）。其余并入对应文档，或改写成指针（`agent-notes.md` 与 `AGENTS.md` 重复的一律留指针）。

落位时**顺手修正的失真**（memory 自己已经漂了，照抄会把错的写进文档）：

- `shared/ui/ReplayScreenGuard.java` → 实为 `foundation/ui/ReplayScreenGuard.java`（`shared/` 桥层已消融）。
- `GuardCombat.CAST_MIN_INTERVAL` → 现名 `MELEE_COOLDOWN_TICKS`（值仍 40）。
- 类名只写了 `ByteBufferBuilder`，实际 FQN 是 `com.mojang.blaze3d.vertex.ByteBufferBuilder`（用 `minecraft-source` 技能核过）。
- `docs/bugs/` 目录已不存在；`feedback_no-reflection-keywords` 引用的 `docs/legacy-audit.md` 也不存在，只保留 `docs/adr.md`。
- `project_curseforge_desc_gitignored` 的行号已变，且旁边**多了一个** `modrinth-description.md`（memory 里没有）。
- **纠正本节上面的口径**：`reference-bytebufferbuilder-leak` 原先被列进「已被 docs 完整覆盖 → 删」，实际只覆盖了一半（详见该条下的订正）。

清空方式：整个 memory 目录（38 篇 + `MEMORY.md`）**移动**到 `~/.dsh/archive/cc-memory-wandscape-2026-10-04/`，而不是硬删——CC 侧不再双写，同时留一份可回溯的存档（39 个文件）。同目录下的会话 `.jsonl` 是 CC 的会话日志、不是 memory，未动。

---

## 五、迁移待办清单

**已在 CC 侧做完（纯本地仓库改动，不依赖 dsh 安装）**：

- [x] `CLAUDE.md` 正文并入 `AGENTS.md`，`AGENTS.md` 成为唯一真源；`CLAUDE.md` 收缩为一行 `@AGENTS.md`（见 §二.1 为什么不能直接删）
- [x] `docs/` 里所有「CLAUDE.md」交叉引用改名为 `AGENTS.md`
- [x] 两个 skill 搬到 `.agents/skills/`，SKILL.md 内脚本路径同步更新
- [x] `minecraft-source` 的 description 由 1100 字符压到 456 字符，触发词前移
- [x] `.claude/skills` 做成指向 `.agents/skills` 的 junction，CC 侧继续可用

**CC 侧遗留：已查完，无待办**

- [x] `~/.claude/rules/context7.md` → 已并入 `~/.dsh/AGENTS.md`（原件已删，避免同一份规则两处维护）
- [x] 三个 cbm hook 确认**从来没在跑**（任何 settings 里都没有 `hooks` 键、也没有 `hooks.json`）→ 无迁移问题
- [x] `.gitignore` 的 skill 入库：**上一版是静默失效的**（被另一条 `.agents/` 挡住），已改成 `.agents/*` + `!.agents/skills/` 并 `git add`，`git check-ignore` 双向核实（见 §二.3 踩过的坑）

**切到 dsh 之后做**：

- [x] `cordis.patch.yml` 加 `@deepseek-ai/dsh-mcp-client` 条目；全仓工具短名改成 `mcp__codebase-memory__*`（`AGENTS.md`、`CLAUDE.local.md`、`~/.dsh/skills/codebase-memory/`；实测 14 个工具注册成功、`list_projects` 调用通过）
- [x] 选 sandbox/approval preset：现用 `danger-full-access` + `never`（顶替原 allowlist，粒度变粗已接受）
- [x] 按 §四 执行 memory 三分类落位并清空 memory 目录（见 §四「执行结果」）
- [ ] **唯一剩下的一条**：`list_projects` 里同一路径有两个索引（`C-Users-huhai-Desktop-Java-mcmod-wandscape` 43.7k 节点 = 该用的那个；`wandscape` 23.7k 节点 = 覆盖不全的旧索引）。要清就用 `delete_project`，本次没动
- [ ] 复核时机：dsh 每次升级后，因为它还是 developer preview

---

## 六、一句话总结

**项目规则与 skill 几乎白送**（`AGENTS.md` 已收拢为唯一真源、`CLAUDE.md` 只剩一行指针；SKILL.md 格式兼容，搬目录 + 压 description 即可）；**MCP 要改写成 YAML 且工具名变长**；**hooks 与权限 allowlist 是净损失**；**「AI 提问给选项」这个 CC 亮点，dsh 内置且有超集**；**memory 没有去处，但这恰恰是好事——落回仓库自己的文档**；**插件生态在通用工作流和「悬浮球」两个方向都不成气候，别在这上面花时间**。
