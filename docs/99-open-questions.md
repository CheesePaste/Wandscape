# 待澄清问题与设计缺陷

文档编号：NEW-99
版本：3.0
状态：未解决问题 + 后续阶段待办。已解决项移至 `docs/98-resolved-issues.md`

---

## 一、未解决 — 设计缺陷 [需修改]

### 1.1 祭坛多方块检测跑在 tick()

`RitualAltarBE.tick()` 每 tick 调用 `MultiblockValidator.validate()`，但 13 §2.2 写"在玩家放置组成方块时检测"——设计和实现不一致。每 tick 校验整个多方块区域非常浪费。
- **建议**：缓存完整性状态，仅在 `BlockEvent.EntityPlaceEvent` / `BlockEvent.BreakEvent` 时重检

### 1.2 殖民地删除：无法"只移除 BE 保留方块"

15 §4 写"所有建筑方块保留但 BE 移除"。MC 中方块类型决定了 BE 类型——方块存在就会创建 BE。两种可行方案：
- **方案 A**：替换方块为纯装饰版本（新方块 ID，无 BE）
- **方案 B**：保留方块和 BE，BE 在 `onLoad()` 时检测殖民地已删除，进入惰性模式
- 建议选 B，省去注册一堆纯装饰方块

### 1.3 连续执行加成硬编码

调度器评分中 `score += 50`（同建筑连续执行加成）是 magic number。该加成的作用：NPC 在某建筑完成任务后，同一建筑队列中还有下一个任务时，给该 NPC +50 评分让其优先接同建筑的下个任务，减少在不同建筑间来回跑。
- **建议**：移至 TOML 全局配置（全殖民地共享此值），无需写入建筑 JSON

### 1.4 结构损坏检测未实现

2026-06-20：`docs/08-building-core.md` §六设计了结构完整性检测——`BlockEvent.BreakEvent` 触发 → `checkStructureIntegrity()` → 缺失方块入队修复。当前已有：
- `AbstractWandscapeBE.checkStructureIntegrity()` ✅
- `isStructureIntact` 标记 ✅
- `BlockPlaceHandler` 监听 `EntityPlaceEvent` ✅
缺失：
- `BlockEvent.BreakEvent` 处理器不存在
- `checkStructureIntegrity()` 只设 boolean，不自动入队修复任务
- `BlockPlaceHandler` 虽在放置时检测并入队 repair，但 break 时不检测
- **建议**：新建 `BlockBreakHandler` 监听 `BlockEvent.BreakEvent` → 判断被破坏方块是否属于某建筑 pattern → `checkStructureIntegrity()` → false 时入队 `build:<typeId>` repair

### 1.5 GlobalTaskPool 内存泄漏

`tasks` Map 永不清除 COMPLETED 任务。100+ 任务后内存持续增长。
- **建议**：定时清理 or 上限策略（保留最近 N 个，或 COMPLETED 超过 5 分钟后清除）

---

## 二、后续阶段待办

| 事项 | 说明 |
|------|------|
| 魔力恢复速率调优 | 当前 2/tick 可能过高，实际游玩后根据反馈调整 |
| 多人游戏同步 | MVP 后实施。底层数据模型已兼容多人（colonyId 隔离），主要增量工作：网络包同步、权限 UI、WandscapeApis 按 colony 查找实现 |
| 进度/指南书 | Patchouli 或自定义指南书 |
| JSON 版本迁移 | 格式变更时的自动迁移 |
| 性能压测 | 100+ NPC、50+ 建筑场景 |
| 区块加载 | NPC 执行任务时确保目标区块已加载 |
| 多殖民地 | 一玩家多殖民地、殖民地间资源调配 |
| 坟墓系统 | NPC 死亡后物品保管 |

---

## 三、维护规则

- 新发现问题 → 追加到 §一，标注日期
- 问题被解决 → 移至 `docs/98-resolved-issues.md` §一
- 问题被确定为"刻意设计" → 移至 `docs/98-resolved-issues.md` §三
- 后续待办完成 → 从 §二中删除
- **不在此文件积累已解决的问题**，保持精简
