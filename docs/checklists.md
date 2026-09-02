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

发版时按顺序执行：

1. [ ] 更新 `gradle.properties` 中的 `mod_version` 为新版本号。
2. [ ] 清理 `build/libs/` 下旧构建 jar。
3. [ ] 运行 `./gradlew build` 确认全量编译通过。
4. [ ] 提交发版 commit（格式：`chore: mod_version X.Y.Z — 发布说明`）。
5. [ ] 打 Git 标签 `git tag vX.Y.Z`。
6. [ ] 推送分支与标签 `git push origin main --tags`。
7. [ ] 使用 GitHub CLI 发布 Release 并上传构建产物：
   ```bash
   gh release create vX.Y.Z build/libs/wandscape-X.Y.Z.jar --title "Wandscape X.Y.Z" --notes-file RELEASE_NOTES.md
   ```
