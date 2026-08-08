# Block 5 — 集成清理

> **顺序执行**，在 Block 1-4 全部合并后运行。清理残留、全量验证、更新文档、版本号。

## 目标

1. 删除 BuildingConfig 兼容访问器 + ShopConfig/ServiceConfig。
2. grep 验证零残留（satisfaction / typePreferences / `"shop"`/`"service"`）。
3. 全量编译 + 单测 + 手测。
4. 更新架构/包文档 + `docs/decisions.md`。
5. 递增版本号。

## 具体改动

### 1. 删兼容层
- `building/data/BuildingConfig.java`：删除 `shop()/service()/touristInteractAabb()` 派生访问器；确认无调用残留。
- 删除 `shared/data/ShopConfig.java`、`shared/data/ServiceConfig.java`（先确认无其它引用；若 Block 0-4 漏了引用，先补迁移再删）。

### 2. grep 验证零残留
```bash
# 应全部为 0 命中（或仅剩注释/文档）
grep -rn "getSatisfaction\|setSatisfaction" src/
grep -rn "getTypePreference\|adjustTypePreference\|typePreference" src/
grep -rn "\"shop\"\|\"service\"" src/            # category 字符串比较
grep -rn "touristInteractAabb\|tourist_interact_aabb\|touristInteractZones" src/
```
> 注意排除：`interact_spot_marker` 等合法字符串、`"service"` 出现在非 category 语义处（如 `element_output` 无此词；`AchievementService` 类名等）——逐条人工核对。

### 3. 全量验证
- `./gradlew build` 全绿。
- `./gradlew test` 全绿（补 JUnit：InteractionConfig 反序列化、fillBars 公式、need-gap 评分、画像 roll、停留截止计算）。
- 手测（README「验证」节 6 条）。

### 4. 文档
- `architecture/packages/building.md`：category 列表（去 shop/service，加 interact）、interaction 块说明、扫描器字段。
- `architecture/packages/tourist.md`：三条/画像/停留/活动/排队机制重写。
- `docs/decisions.md`：记录本次设计变更（满意度→三条、去 satisfaction/typePreferences、interaction 块、spots+排队、停留上限、扫描器）。
- `architecture/plan/*` 标记完成状态（可选）。

### 5. 版本号
- `gradle.properties` 的 `mod_version`：大重构 → **第二位递增，第三位归零**（如 1.7.30 → 1.8.0）。
- 若 `build/libs/` 有旧次版本 jar，按规则清理（第二位变化才清理）。

## Done 判定
- 全仓库编译/测试/手测通过；零残留 grep。
- 文档与代码一致；版本号已递增；commit 符合仓库规则（`refactor:`/`feat:` 前缀，中文一句）。
