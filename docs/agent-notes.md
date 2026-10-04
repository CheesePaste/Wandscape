# 跨切面工作方式与工具知识（agent-notes）

> 信息截至 2026-10-04 | Minecraft NeoForge 1.21.1

- **【何时读】**：接手跨模块任务、与并发写入者共用工作区、要跑本机工具、或准备提「这里缺个保护」这类改进建议之前。
- **【不包含什么】**：硬规则与功能域避坑——那些在 `AGENTS.md`（唯一真源）与 `docs/domain-notes.md` 对应小节；本文只装**没有功能域归属**的跨切面细节，与两者重复的一律改成指针。

---

## 一、工作流与提交

1. **并发写入者下按「我改过吗」划线，不按「这行是谁写的」划线**（见 `AGENTS.md` §工作流与提交的三条硬规则）：
   - 我改过的文件**整份**提交，包括与并发写入者交错的文件（`Wandscape.java`、`gen_patchouli.py`、`lang/*.json` 生成物）；我没碰过的文件一律不进本次 commit、也不动它们。提交前跑 `git status --short`，逐行问「我动过吗」，然后显式列路径 `git add <path>`——`git add <目录>` 会把对方 untracked 的新文件一并拉进来。
   - **Why**：用户 2026-09-26 明确裁定。当时另一个会话在并发改同一批文件，我提议「先不提交、等对方收工」被否掉——等对方会无谓卡住进度；而按行 / Hunk 拆分在只有「整文件 `git add`」的条件下做不到。
   - **提交后如实说两件事**：① 提交态可能暂时编译不过（我改的同文件里可能调用了对方未提交的新方法）；② 对方还剩哪些未提交 / untracked 文件留在工作区。**不要**为了让提交态能编译就顺手把对方未完成的功能文件一起提——那是替对方提交。
2. **读大文件前先核对「我读到的是不是全文」**：
   - 动手移植 / 重构前，先 `wc -l <file>` 加一份方法清单（`grep -nE "^\s*(private|public|static)"`）与读到的内容对账；对不上就分段重读（每段 ≤220 行），每段确认返回的首尾行号，最后确认总行数吻合。更稳的做法是直接 `git show <ref>:<path>` 取权威版本，别依赖工作区的一次读取。
   - **Why**：2026-09-19 移植手册渲染器（当时 830 行，该类现已不在仓库里）成图集时，一次 Read 只返回 **469 行**，而且是更旧的实现（缺 3 个反射字段、着陆页铭牌、7 个图标按钮、小箭头）。没核对行数就动手，23 个图集槽位只画了 13 个，玩家侧表现为「横幅没了、编辑器 / 调整大小按钮没了」。本仓库大量中文注释，更容易被单次读取截断。
3. **生成器脚本放仓库根并入库**：内容管线生成器默认放仓库根、与生成物在同一条 commit 里提交；只有自带独立项目结构（自己的 `package.json` / 构建体系）的工具才进 `tools/`——那里已被 gitignore。落位细节见 `docs/file-layout.md` §五。
4. **`node` / `npm` 类命令交给用户跑**：
   - `npm install` / `npm run build` / `npm run dev` 由用户在自己的终端执行，我只写文件 + 给出命令；改 UI 后的浏览器实测同样由用户执行。
   - **Why**：会话中代跑 `npm install` 被中断，并触发 Vite 8（Rolldown）原生绑定装坏 + Windows 文件占用；用户原话「给我命令行我来，你运行不了这个」。toolchain 出问题优先换稳定方案（Vite 7 / esbuild + TS 5 纯 JS），别反复重试同一条命令。

## 二、通用实现取向

1. **优先通用 / 自省机制，而不是硬编码枚举清单**：
   - 需求是「XX 和原版行为一样」时，先问有没有能**自动跟随原版**的通用判定（反射读已有 goal、接口标记 + `isInstance`、标签 / 数据驱动），再考虑列枚举。
   - **Why**：实现村民级索敌时我先枚举僵尸 / 掠夺者 / 劫掠兽逐个分析，用户打断说「不用管哪些生物攻击哪些不攻击，直接和原版村民的仇恨吸引一样就行了」。枚举既要随原版增减维护，还容易漏边界（僵尸猪灵继承自 `Zombie` 却是中立、不追村民）。最终方案是 `EntityJoinLevelEvent` 里反射检查生物已有的对 `AbstractVillager` 索敌 goal、命中则追加对 `VillagerLike` 接口的等价 goal。
2. **优先复用原版 / 现有系统，不要从 0 重写**：
   - 面对「给 X 加一个类似 Y 的机制」，先查原版是否已有可复用的对应系统（如村庄袭击 `Raid`/`Raids` 与 `BadOmen`→`RAID_OMEN`→`createOrExtendRaid` 状态链），评估「直接调用 / 小钩子」能否覆盖，再决定要不要新写。评估时必须**读原版源码确认关键前提**（例如 `Raid.tick()` 的 `isVillage(center)` 硬前提要用 mixin 绕过），别凭记忆假设。
   - 用户原话是「你看一看原版村庄袭击机制能不能直接套过来，别从0开始写袭击内容」——复用波次表 / 袭击者阵容 / Boss 条 / 持久化之后，只剩「触发 + 事件」两小块要写。
3. **取舍的唯一标准是「让代码变少，或让下次改动明显更省力」**（`AGENTS.md` §架构归属最后一条的展开）：
   - 用户对重构质量的定调（2026-09-01 包审计时）：「有一点反模式无所谓，代码不需要这么干净，又不是企业级，怎么舒服怎么来。」追求架构优美、消灭一切反模式（god object、命名稍偏、包过碎、跨域直接调）是「专业软件化」的病。
   - 交付重构质量清单时**只报两类**：① 会实际咬人的错置（依赖倒置、公开契约泄漏内部类型、违反高兼容 / 纯逻辑硬边界、断旧档的移除）；② 高价值 / 低成本的整理（清死 import、改会误导的命名、删空包 / 死壳、一概念双份）。**不报**审美类反模式，除非它直接让下一个功能更难加。报告里把发现分成「该动」与「只是观感、可不动」两档。
4. **没有反射的地方别写「避免反射」式注释**：
   - 注释只陈述事实，把否定句删掉：反例 `经 AT 提为 public 后直接读字段，不再反射。` → 正例 `经 AT 提为 public 后可直接读该字段。` 同样避免写出 ASM 等字节码关键词。覆盖 Java 注释 / Javadoc、`accesstransformer.cfg` 等配置注释、生成器脚本 docstring、玩家可见的 md / json 文案。
   - **Why**：CurseForge 审核与第三方安全扫描按关键词命中，会把本来干净的文件也标出来（用户 2026-09-19 指出）。
   - **例外**：`docs/adr.md` 这类决策记录必须写清「为了过审移除反射」这一决策本身，且 `docs/` 不进 jar、不改；二进制文件里的随机字节（如 `.ogg` 里的 `ASM`）是误报，不用管。

## 三、调试与日志

1. **用户的应用内实测是决定性证据；现象与文档理论矛盾时先核对代码事实**（另见 `AGENTS.md` §工作流与提交第一条）：
   - 用户描述现象时先当成事实，优先在代码里核对硬事实（旋转 / 坐标 / 相位 / 触发条件），而不是扩展文档里的旧假设；改代码前用 `minecraft-source` 技能核实 MC API。
   - **Why**：修游客行为 bug 时，我按 `docs/bugs/tourist-behavior-bugs.md`（该目录已不存在，需要时去 git 历史取）的「Y 外扩交互区误判到达」理论推治本方案，被用户纠正——实测现象是「游客完全包裹在交互区内却 VISITING 不交互」，明显不是寻路问题。核对代码后真因是 `planNextBuilding` 计算 `touristInteractZones` 时未按 `rotationSteps` 旋转，到达判定命中错位框，`arrived` 恒 false 后卡死兜底「传送到自己」死循环。
2. **`Log.debug` 在未开 debug 时会被打成带 `[DEBUG]` 前缀的 INFO**（`AGENTS.md` §非显然陷阱已有同一结论，这里给机制与做法）：
   - 机制在 `foundation/log/Log.java` 的 `logInternal` DEBUG 分支：底层 log4j logger 未开 debug 时**不丢弃**，而是 `logger.info("[DEBUG] " + msg)` 提升输出。于是「降级为 debug」分两段——默认各类目都是 INFO，`LogConfig.isEnabled(cat, DEBUG)` 为 false、函数提前 return，确实静默；但管理员把某类目调成 DEBUG 想排查时，`LogConfig` 放行而 log4j 仍卡在 INFO，这些日志会以 `[DEBUG]` 前缀**重新刷屏**，且失去 debug / info 的区分能力。
   - **做法**：日志刷屏要治理时别默认「降级为 debug 就等于留了个可控开关」——要么直接删（2026-10 清理服务器一天 69 万条 INFO 时选的就是直接删），要么同时把 log4j 对应 logger 的 level 也放开。`Log.infoThrottled` / `Log.warnThrottled` 是另一条正路，但截至该次清理全仓使用数为 **0**。

## 四、本机 / 外部工具

1. **`tools/jar-audit/`：上传前 / 被 CurseForge 拦下时的 jar 自检脚手架**：
   - 该目录**在 `.gitignore` 的 `tools/` 下、不入库**，新会话默认不知道它存在：CFR 反编译器 + `audit.py` 字节码常量池能力面审计器。`bash tools/jar-audit/run.sh` 一条命令复跑默认对照（已通过审校的旧 jar vs 被拦下的新 jar）。
   - **核心方法论是 A/B 差值，不是绝对评分**：社区没有成熟的 Minecraft 模组扫描器（Jarspect 要 Rust 工具链 + Azure OpenAI key、jarsec 要 Docker，本机都跑不起来），所以拿「已通过」与「被拦下」两个 jar 跑同一套探测、**只看差值**。
   - **误报清单**：`MethodHandles` 几乎必中（javac 给 lambda / record 生成的 `invokedynamic` 引导，本仓 987 个类里 578 个命中）、`java/lang/ClassLoader` 是读自身资源、`java/util/zip` 是遍历建筑蓝图。判断看**证据字符串**，不看类名。
   - **解析器踩过的坑**：`parse_class` 里 Methodref 的 `class_index` 指向 `CP_Class` 条目，修好后 `Class.forName` / `Runtime.exec` / `ProcessBuilder` / `ObjectInputStream` / `System.load` 这类只在方法引用里出现的信号才扫得出来。修好前生成的 `out/report-*.json` 与据此写的结论都不能再信——**看报告「没命中 EXEC_PROCESS / NETWORK」并不能证明没有**。
2. **本机取资料**：库 / 框架 / API 文档走全局 `ctx7` CLI（规则在 `~/.dsh/AGENTS.md`）；网页抓取在本机可能被网络环境挡住。CC 时代那条「域名安全预检失败 → `skipWebFetchPreflight`」的修法是 **CC 专属机制，dsh 下不成立**，别再照搬配置修法。真正要记住的只有一句：**别把「取不到资料」误判成「没有这份资料」**并据此下结论——抓不到就换途径（`web_search`、直取 URL、`gh api`）。

## 五、未修风险与待实测

1. **多人生存「完全平行」隔离（分支 `multiplayer-isolation`）的待实测范围**：
   - 模型是「每个玩家只操作自己的小镇，无镇 = 建镇引导态，绝不显示 / 操作别人的镇」，提交 `44c6d31f` 已落地且 `./gradlew build` 通过：面板 / 统计 / 投影解析只绑 `getColonyByFounder`（删掉「无镇回退空间最近镇」）；咽喉归属判定覆盖 `BuildingInteractHandler`、`BuildingActionPacket`、`ProjectionPlacePacket`、`WandscapeNpc.mobInteract`；新增 `content/colony/ownership/ColonyOwnership`（唯一归属判定 + 拒止反馈入口）与 `content/colony/guard/ColonyLandProtectionHandler`（领地防挖 / 防放 / 防翻包 / 防爆炸）；法师实体 id 直发包按归属门禁。
   - **用户明确裁定**：小镇间**不强制隔离距离**（`ColonyCommand.createColonyAt` 的距离守卫注释保留、不启用）；**道路铺设不查镇归属**（避免误杀）；剩余「恶意直发包」防范**先实测再决定**，暂不补。
   - **待实测（双账号服务端 / LAN）**：A、B 各自建镇独立玩；B 无镇按 V 是空面板；B 无法工 / 开 / 改 A 的任何东西（含原版镐子拆、蓝图、仓库、法师）。
2. **`FriendlyForce` 的 `PLACEHOLDER_COLONY` / `sameColony` 误伤风险，用户拍板暂不修**：机制、可达路径与窄口径修法见 `docs/domain-notes.md` §一.6。真修只放宽 `WANDSCAPE_NPC`/`MAGIC_SUMMON`/`TOURIST` 三类，别顺手扩大。**看到法师意外死亡时，先确认是否可归因、逃生是否可达，不要提议「加伤害免疫 / 加血」**——那几件事是刻意设计（见 `docs/domain-notes.md` §一.11）。
