# 本目录向下兼容载荷

这些建筑 JSON 是**旧版本法的向下兼容载荷**，不是死数据。

- 代码仍按内层 `id` 字段注册/查找（如 `tavern`）：`WandscapeDataLoader` 用 MC `listResources` 递归扫 `data/wandscape/buildings/*.json`，`deprecated/` 子目录会被扫到并注册，故旧档/隐藏建筑仍能按 id 解析。
- `ProjectionNetwork` 隐藏 deprecated 建筑但仍按 id 解析参与查找。删掉即断开旧档加载。

> 注：`WandscapeDataLoader` 只加载 `*.json`，本 `README.md` 不会被当作数据解析，放在此目录安全。
