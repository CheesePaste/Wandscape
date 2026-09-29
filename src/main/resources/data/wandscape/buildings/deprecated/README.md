# 本目录向下兼容载荷

这些建筑 JSON 是**旧版本法的向下兼容载荷**，不是死数据。

- 代码仍按内层 `id` 字段注册/查找（如 `tavern`）：`WandscapeDataLoader` 用 MC `listResources` 递归扫 `data/wandscape/buildings/*.json`，`deprecated/` 子目录会被扫到并注册，故旧档/隐藏建筑仍能按 id 解析。
- `ProjectionNetwork` 隐藏 deprecated 建筑但仍按 id 解析参与查找。删掉即断开旧档加载。

> 注：`WandscapeDataLoader` 只加载 `*.json`，本 `README.md` 不会被当作数据解析，放在此目录安全。

## 怎么弃用一栋建筑

`example_deprecated.json` 是**最小可用的样本**（一格的占位建筑，只为演示 `deprecated` 这个键），照它做即可：

1. `git mv buildings/<id>.json buildings/deprecated/<id>.json`——**移文件，不要删**。加载器只认内层 `id`，文件名随意，但保持一致便于查找。
2. 在 JSON 里加 `"deprecated": true`，位置放在 `"category"` 之后（与本目录其它文件对齐）。
3. 别处的引用**看着办**：手册/引导里"怎么造这栋"的条目可以删（新档造不出来了），但 `lang_src/content/building.json` 里的 `building.wandscape.<id>` **必须留着**——弃用的建筑仍会出现在旧档的世界里，名字还得靠这个键翻（`potion_store` / `latern_shop` 弃用后键都留着）。
4. `./gradlew build`。弃用不污染造价表：`balance/calc_balance.py` 与 `extract_buildings.py` 都只统计非 deprecated 建筑（`sim_spatial.py:1163` 同理）。

**唯一要权衡的**：要不要把已弃用建筑的 id 让给新楼。让了就是"原地重做"——旧档里那栋会直接变成新楼（形状、参数全换），`service_hall` 就是这么处理的；不让就得另起一个 id，旧档保持原样。两种都合法，取决于新楼是不是同一个设施的新版本。
