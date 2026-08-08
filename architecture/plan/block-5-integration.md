# Block 5 — 集成清理

> **顺序执行**，在 Block 1-4 全部合并后运行。清理残留、全量验证、更新文档、版本号。
> **一阶段不删四类 Config**：`ShopConfig`/`ServiceConfig`/`RelaxConfig`/`AtmConfig` 与 `shop()/service()/relax()/atm()` 字段**保留到二阶段**才统一删除（见 phase-2/README.md）。本块只删 `touristInteractAabb()` 派生访问器与 satisfaction/typePreferences 残留。

## 目标

1. 删除 BuildingConfig 的 `touristInteractAabb()` 派生访问器（spots 迁移完成后无调用者）。
2. grep 验证零残留（satisfaction / typePreferences / tourist_interact_aabb）。
3. 全量编译 + 单测 + 手测。
4. 更新架构/包文档 + `docs/decisions.md`。
5. 递增版本号。

## 具体改动

### 1. 删兼容层
- `building/data/BuildingConfig.java`：删除 `touristInteractAabb()` 派生访问器（由 `interactSpots` 派生，Block 1/3 迁移完成后已无调用者）；确认无调用残留。
- **保留** `ShopConfig shop`、`ServiceConfig service`、`RelaxConfig relax`、`AtmConfig atm` 字段与 `shop()/service()/relax()/atm()`——二阶段才删。

### 2. grep 验证零残留
```bash
# 应全部为 0 命中（或仅剩注释/文档）
grep -rn "getSatisfaction\|setSatisfaction" src/
grep -rn "getTypePreference\|adjustTypePreference\|typePreference" src/
grep -rn "touristInteractAabb\|tourist_interact_aabb\|touristInteractZones" src/
```
> 注意排除：`"service"` 出现在非 category 语义处（如 `AchievementService` 类名等）；`"shop"`/`"service"`/`"relax"`/`"atm"` category 字符串**应保留**——逐条核对每个按 category 的 switch 是否四类都覆盖（Block 4 已做）。

### 3. 全量验证
- `./gradlew build` 全绿。
- `./gradlew test` 全绿（补 JUnit：RelaxConfig/AtmConfig 反序列化、fillBars 公式、need-gap 评分、画像 roll、停留截止计算）。
- 手测（README「验证」节 7 条）。

### 4. 文档
- `architecture/packages/building.md`：category 列表加 `relax`、`atm`（shop/service 保留）、interact_spots 说明、扫描器四类编辑字段。
- `architecture/packages/tourist.md`：三条/画像/停留/活动/四类交互/排队机制重写。
- `docs/decisions.md`：记录本次设计变更（满意度→三条、去 satisfaction/typePreferences、interact_spots、四类 category、relax/atm、停留上限、扫描器；category 合并 → 二阶段）。
- `architecture/plan/*` 标记完成状态（可选）。

### 5. 版本号
- `gradle.properties` 的 `mod_version`：大重构 → **第二位递增，第三位归零**（如 1.7.30 → 1.8.0）。
- 若 `build/libs/` 有旧次版本 jar，按规则清理（第二位变化才清理）。

## Done 判定
- 全仓库编译/测试/手测通过；零残留 grep（satisfaction/typePreferences/tourist_interact_aabb）。
- 四类 category（shop/service/relax/atm）在所有按 category 的 switch 中都被处理。
- 文档与代码一致；版本号已递增；commit 符合仓库规则（`refactor:`/`feat:` 前缀，中文一句）。
