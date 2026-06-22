# 已知问题与待澄清

## 设计缺陷

### GlobalTaskPool 内存泄漏
tasks Map 不清理 COMPLETED 任务。100+ 任务后内存持续增长。建议：定时清理或上限策略。

### 祭坛多方块检测跑在 tick()
当前设计每 tick 校验整个多方块区域。应缓存完整性状态，仅在方块放置/破坏时重检。注：祭坛模块尚未构建，此问题在设计中而非代码中。

### 连续执行加成硬编码
SchedulerSystem 中 `score += 50` 是 magic number。应移至 TOML 全局配置（Config.java 已有 `sameBuildingContinuationBonus` 但代码未使用）。

### 殖民地删除未实现
殖民地系统未构建。删除时需清理：BuildingSavedData(建筑记录) + RoadSavedData(路网) + ColonyItemBank(物品) + NPC的ColonyMember组件。世界中方块为原版方块（stone_bricks等），无需特殊处理——它们就是普通方块。注意：项目已无自定义建筑 BE，不要引入 BE 方案。

## 代码问题（2026-06-22 代码审查发现）

### AtomicStep 与 AtomicOp 两套并行类型
`shared/data/AtomicStep.java`（4变体：OperationA/B/C/D）是旧设计。引擎实际用 `core/op/AtomicOp.java`（7变体）。AtomicStep 未被引擎使用但保留在 shared 层，增加混淆。

### WandscapeConstants 与 Config 值重复
`WandscapeConstants.java` 硬编码默认值（SCHEDULER_HEARTBEAT_TICKS=40 等），`Config.java` 定义相同的 TOML 可配值。两者的优先级关系无文档说明。

### 6 个 API 接口无实现
WandscapeApis 中 TaskApi、ColonyApi、HouseApi、ManaPoolApi、TavernApi、AtomicExecutor 的 getter 永远抛 "not loaded"。要么移除，要么标注为预留。

### PLACEHOLDER_COLONY 零 UUID
EntityComponentBridge 使用全零 UUID 作为占位殖民地，注释标记"阶段2占位"。殖民地系统完成后需替换。

### BuildingSavedData posIndex 重建不完整 ✅ 已修复 (2026-06-21)
~~从 NBT 加载时 posIndex 无法完全重建（需要 BuildingConfig pattern）。~~ 已在 `getBuildingIdAt()` 中添加 chunkIndex fallback：posIndex miss 时遍历同区块建筑，用 `BoundingBox.isInside()` 匹配并缓存到 posIndex。重进游戏后所有建筑右键正常。

### GlobalTaskPool.onChanged 脆弱模式
公开 Runnable 字段用于持久化脏标记，外部设置。如果创建 TaskPool 时未设置此字段，持久化静默失败。

## 后续待办

- 魔力恢复速率调优（当前 2/tick 可能过高）
- 多人游戏同步（底层模型已兼容，需网络包+权限UI）
- 性能压测（100+ NPC、50+ 建筑场景）
- 区块加载保证（NPC 执行任务时确保目标区块已加载）
- JSON 版本迁移（格式变更时的自动迁移）
- 进度/指南书（Patchouli 或自定义）
