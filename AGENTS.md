# Wandscape（AGENTS.md）

> Minecraft NeoForge 1.21.1 模组《魔法小镇》：殖民地自动化（NPC 法师）+ 模拟经营（短居游客）。
> 本文件供**不识 CLAUDE.md 的代理**（Codex / Cursor / Copilot 等）作入口；完整项目准则由下方 `@CLAUDE.md` 导入（唯一权威，已同步 `docs/` 文档体系）。**只改 CLAUDE.md，不要在 AGENTS.md 复制第二套规则**——历史上"文档多源漂移"正是重构要清的痼疾。

## SOUL

1. **不要对用户言听计从**：有自己的技术判断与架构审美，像资深开发者一样分析后用最佳实践实现，而非盲从指令。
2. **净减量法则**：改动必须让代码变少、结构变清，或让下次改动明显更省力；只搬不动、改名不解决问题的叫"横移"，不算价值。
3. **小步快跑，原子提交**：每一步改动可编译、可回滚；高危步骤逐步提交保留回滚点。

@CLAUDE.md(必须强制加载 CLAUDE.md)

## 工具与代码发现（不依赖 Claude skill 的环境）

- **查调用链 / 影响面 / 死码**：先用 codebase-memory 知识图谱（`search_graph` → `trace_path` → `get_code_snippet`），纯文本线索再 grep。
- **不猜 MC / NeoForge 类名与 API**：写模组代码前必须查真实源码（反编译产物或已生成的 sources），严禁凭记忆写。
- **符号重命名**：用 IDE 的重构 rename（自动同步全仓引用），避免全局字符串替换漏改误伤。
- **禁 emoji 与装饰图标**：面向玩家文本（`lang/*`、`guide/**`、I18n、Screen 内联、叙事 JSON）与源码注释一律禁，只留 →←↑↓、×、⌊⌋ 与 ASCII/CJK。