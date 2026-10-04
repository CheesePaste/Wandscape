# 迁移与重构活清单（checklists）

> 信息截至 2026-09-02 | Minecraft NeoForge 1.21.1

- **【何时读】**：大版本升级、重构推进、提交 PR 或发版前进行质量与规范核对时。
- **【不包含什么】**：琐碎日常提交 log。

---

## 一、大重构活清单（Refactor Checklist）

各阶段做完后直接在原地划 `~~` 留痕，不删不重写：

### Tier 0: 摸底与认知对齐
- [x] ~~全仓 29 个顶层包真实代码摸底，产出事实源 `newplan/packages.md`~~
- [x] ~~建立重构进度唯一定位器 `newplan/status.md` 与开发指南 `CLAUDE.md`~~

### Tier 1: 死代码与死字段清理
- [x] ~~物理删除 0 引用死类（`InterruptRecord`, `EquipmentPreset` 等）~~
- [x] ~~全仓清理 37 处未读私有死字段与 13 处真死私有方法~~
- [x] ~~物理删除全部 `src/test` 目录（拒绝低效测试灌注）~~

### Tier 2: 改名与去撞名
- [x] ~~核心撞名类重命名（`ecs/System` → `EcsSystem`, `AttributeModifier` → `NpcAttributeModifier`, `Inventory` → `NpcInventory`）~~
- [x] ~~生产级测试类改名（`GuideTestScreen` → `GuideScreen`, `GuideTestPacket` → `GuideDocOpenPacket`）~~
- [x] ~~删除历史残留死接口与死服务（`HouseApi`, `StatsService`）~~

### Tier 3: 样板与规则合并
- [x] ~~NPC 属性五处定义统一收敛至 `content/npc/attributes/NpcAttributes` 单类~~
- [x] ~~制作站合成动作与抄写动作统一至 `production:craft` 与 `CraftRecipeView`~~
- [x] ~~配方解析公共抽取 `ElementMaps.parse`~~

### Tier 4: 骨架迁移与桥层消解
- [x] ~~21 个旧顶层功能包迁入 `content/` 目标骨架~~
- [x] ~~消融 `shared/` 桥层（139 类分配至 content/foundation/api）~~
- [x] ~~消融 `core/` 与 `engine/` 桥层（88 类分配至 content/task/npc/colony/warehouse/foundation/impl）~~
- [x] ~~解散 `WandscapeEngine` 上帝定位器，收敛至 `TaskRuntime`~~
- [x] ~~API 瘦身与归口，内部调用废除搭桥直接引用~~

### 横切基建与功能深化
- [x] ~~日志 SLF4J 治理体系与 16 域分类降噪~~
- [x] ~~天平数值持久化 JSON 覆盖（`wandscape_balance.json`）~~
- [x] ~~新手引导与指南书概念拆分（`content/tutorial` 独立）~~
- [ ] UI 去堆框架（通用 Screen 数据驱动样板，替代每建筑一个独立 Screen）
- [ ] `newplan/api-ledger.md` 中待实现 API 落地（`NpcApi.spawnNpc`, `ColonyApi.setName` 等）
- [ ] 暂缓项归位（`content/command/` 清理、`mixin/` 按域划分）

---

## 二、开发与 PR 守门员清单（Code Review Checklist）

在提交代码前逐项核对：

- [ ] **直接调用**：域间协作是否直接调用业务类，未引入新的 API 中转或 EventBus 强制解耦。
- [ ] **纯逻辑解耦**：`content/task`、算法、公式、属性规则中是否绝对无 Minecraft / NeoForge import。
- [ ] **NBT 安全**：对外暴露复合标签时是否使用了 `tag.copy()`。
- [ ] **任务归属**：新任务发布时是否显式传递了 `colonyId`（未产生无主幽灵任务）。
- [ ] **统一日志**：是否使用 `Log.info/debug/warn`（严禁 `System.out.println`，严禁静默 catch 吞异常）。
- [ ] **文本规范**：玩家可见文本及源码注释中是否绝对无 emoji 与多余装饰符号。
- [ ] **构建编译**：`./gradlew compileJava` 与 `./gradlew build` 100% 通过。

---

## 三、发版与发布清单（Release Checklist）

版本号规范：**`<版本>-<游戏版本>`**，游戏版本去掉点号（1.21.1 → `-1211`，1.20.1 → `-1201`）。
例：`mod_version=2.1.2-1211` → jar `wandscape-2.1.2-1211.jar`、tag `v2.1.2-1211`。
双线并行后两版内容不同步，游戏版本必须焊进版本号，否则玩家在 CurseForge/Modrinth 上会装错线。

发版时按顺序执行：

1. [ ] 更新 `gradle.properties` 中的 `mod_version` 为新版本号（含游戏版本后缀）。
2. [ ] 清理 `build/libs/` 下旧构建 jar。
3. [ ] 运行 `./gradlew build` 确认全量编译通过。
4. [ ] 提交发版 commit（格式：`chore: mod_version <版本>-<游戏版本> — 发布说明`）。
5. [ ] 打 Git 标签 `git tag v<版本>-<游戏版本>`。
6. [ ] 推送分支与标签（分支名 = 游戏版本：主线 `1.21.1`、副分支 `1.20.1-forge`）：
   ```bash
   git push origin 1.21.1 --tags
   ```
7. [ ] 使用 GitHub CLI 发布 Release 并上传构建产物：
   ```bash
   gh release create "v<版本>-<游戏版本>" "build/libs/wandscape-<版本>-<游戏版本>.jar" \
     --title "Wandscape <版本>-<游戏版本>" --notes-file RELEASE_NOTES.md
   ```
8. [ ] CurseForge / Modrinth 的 Game Version 标签按线选（1.21.1 或 1.20.1），文件名保持同一格式。
9. [ ] **不兼容提示**：本次发版若含**破坏存档兼容**的改动（数据格式断档、SavedData 迁移链缺口、
   已放置建筑/道路/殖民地的既有数据无法解析），release 正文（`RELEASE_NOTES.md`）**必须**有一段
   醒目的不兼容说明：说清楚哪种世界会受影响、需要开新档还是有部分数据会丢。
10. [ ] **CurseForge / Modrinth 描述是本地文件，不入库**：`curseforge-description.md` 与
    `modrinth-description.md` 都在 `.gitignore` 里（那是平台发布文案，避免与 GitHub release notes 双源维护）。
    用户要求「更新 curseforge 描述」时，**改完文件即算完成**，不需要 `git add` / `commit`；发布 GitHub release 时
    工作树可能仍是 clean 的，别因为「没改动可提交」而困惑。GitHub release notes 另写（发布时临时生成，不入库）。

**关于不兼容的口径**（硬规则 7）：项目**不维护新旧两套格式并存的兼容层** —— 断档是允许的选择，
但代价是"发布时说清楚"，不是"代码里长期养一个兼容分支"。所以发版前先问一句：
这次改动有没有让旧档读不出来？有就写进正文，没有就跳过第 9 步。

---

## 四、遗留待办（性能 · 工具 · 待验证）

> 2026-10-04 从已删的性能考察文档（`docs/plan/server-tick-hotspots.md`、`docs/plan/large-building-render-perf.md`、`docs/plan/earlygame-building-materials.md`）收拢，**它们是这些未完成项的唯一留存**。

- [ ] **C12 对象模型去装箱**：`List<BlockOffset>` → `int[]`、`List<Integer>` → `short[]`；`blockMapping()`（`BuildingConfig.java:209`，58 万条 String 键 HashMap，被 `BuildCompleteListener.java:189` / `BuildingRepairHandler.java:43` / `EnqueueHelper.java:448` 调用）要么删、要么改 int 键。收益：常驻堆 −30 MB 级，并消掉一个约 100 MB 的瞬时峰值。
- [ ] **C13 `rawJsons` 不再常驻 Gson `JsonElement` 树**（`BuildingConfigLoader.java:289`）；网络同步直接走序列化字节（`Wandscape.java:905` 现在是先 `json.toString()` 再压）。影响 `getRawJsons()` 的全部消费者。**量级是估算不是实测**：单三元组约 270 B、58 万条 ⇒ 150–200 MB。
- [ ] **A5 `warmAll()` / `stableName()`**：当初按决策未做（预设超大建筑优化到位后不再是问题）。注：`stableName()` 每次重建约 15 MB 字符串，与建筑大小无关。
- [ ] **`block_nbt` 是下一个压缩目标**：现为最大单项（magic_academy 159 KB / 1087 条逐条 gzip 的 base64 NBT）。
- [ ] **`extract_buildings.py:97` 售价取整 bug**：写成 `-(-int(cost * (1.0 + profit_rate)))`，注释说 ceil，但对正数实际是 **floor**（Python `int()` 向零截断）；游戏侧 `ShopStockManager:258` 用的是真 `Math.ceil`。**会让商店收入系统性偏低**，低档商品最明显（1 元素商品被工具记成 2），修法一行：换 `math.ceil`。
- [ ] **清场（C1）世界状态一致性未验证**：清场后盒内方块集合与仓库回收量是否与改前逐格一致。建议小建筑先做 A/B：提交 → 比较「清场后盒内非 pattern 格是否全为空气」与「回收进仓库的物品数量」，再用大建筑复跑 spark 看单帧尖峰是否消失。
- [ ] **批量跳过尖峰从未实测**：`TaskExecutionSystem` 4a 的「已是目标方块就 skip」路径，外推 2–7 s 单帧（666 万次 `blockOps.getBlock()`）。C1 预期已消掉它，但没有实测数据。
- [ ] **SavedData 任务参数序列化残留**：`BuildingSavedData:674` 把每个 WorkItem 的 params 用 Gson 序列化进 NBT；C1 已把 340 MB 级压到 25 MB 级。彻底消掉要让 params 不再携带 pattern（蓝图按 building_type 自查配置），但那违反「蓝图不 import MC」的纯逻辑边界，暂不做。
